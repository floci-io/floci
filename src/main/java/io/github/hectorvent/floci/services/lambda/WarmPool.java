package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages a pool of warm Lambda containers per function.
 *
 * Two modes controlled by {@code emulator.services.lambda.ephemeral}:
 *  - {@code false} (default): containers are reused across invocations and evicted
 *    after {@code container-idle-timeout-seconds} of inactivity.
 *  - {@code true}: each invocation gets a fresh container that is stopped immediately
 *    after the invocation completes.
 */
@ApplicationScoped
public class WarmPool {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);

    private static final int DEFAULT_MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final ContainerLauncher containerLauncher;
    private final EmulatorConfig config;
    private final int maxPoolSizePerFunction;
    private final ConcurrentHashMap<String, ArrayDeque<ContainerHandle>> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "warm-pool-evictor"); t.setDaemon(true); return t; });
    private Thread shutdownHook;

    @Inject
    public WarmPool(ContainerLauncher containerLauncher, EmulatorConfig config) {
        this.containerLauncher = containerLauncher;
        this.config = config;
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
    }

    /** Package-private constructor for testing (empty pool, no containers to drain). */
    WarmPool() {
        this.containerLauncher = null;
        this.config = null;
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
    }

    @PostConstruct
    void init() {
        if (config == null) {
            return;
        }

        int idleTimeout = config.services().lambda().containerIdleTimeoutSeconds();
        if (!config.services().lambda().ephemeral() && idleTimeout > 0) {
            // Check for idle containers every 30 seconds (or half the timeout, whichever is less)
            long checkInterval = Math.min(30, idleTimeout / 2 + 1);
            evictionScheduler.scheduleAtFixedRate(this::evictIdleContainers,
                    checkInterval, checkInterval, TimeUnit.SECONDS);
            LOG.infov("Warm pool idle eviction enabled: timeout={0}s, check interval={1}s",
                    idleTimeout, checkInterval);
        } else if (config.services().lambda().ephemeral()) {
            LOG.infov("Lambda containers running in ephemeral mode (destroyed after each invocation)");
        }

        shutdownHook = new Thread(this::drainAll, "warm-pool-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @PreDestroy
    void shutdown() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }
        evictionScheduler.shutdownNow();
        drainAll();
    }

    /**
     * Acquires a container for the given function.
     * In ephemeral mode always cold-starts a new container.
     * Otherwise returns a warm container from the pool, or cold-starts a new one.
     */
    public ContainerHandle acquire(LambdaFunction fn) {
        boolean ephemeral = config != null && config.services().lambda().ephemeral();
        ContainerHandle handle = null;

        if (!ephemeral) {
            ArrayDeque<ContainerHandle> queue = pool.computeIfAbsent(fn.getFunctionName(), k -> new ArrayDeque<>());
            // Skip pooled handles whose container died out-of-band — otherwise the
            // caller would wait the full Lambda function timeout.
            while (true) {
                ContainerHandle candidate;
                synchronized (queue) {
                    candidate = queue.pollFirst();
                }
                if (candidate == null) {
                    break;
                }
                if (containerLauncher.isAlive(candidate)) {
                    handle = candidate;
                    break;
                }
                LOG.infov("Discarding dead pooled container {0} for function {1}",
                        candidate.getContainerId(), fn.getFunctionName());
                stopQuietly(candidate);
            }
        }

        if (handle == null) {
            LOG.debugv(ephemeral ? "Ephemeral start for function: {0}" : "Cold start for function: {0}",
                    fn.getFunctionName());
            handle = containerLauncher.launch(fn);
        } else {
            LOG.debugv("Reusing warm container for function: {0}", fn.getFunctionName());
        }
        handle.setState(ContainerState.BUSY);
        return handle;
    }

    /**
     * Returns a container after an invocation completes.
     * In ephemeral mode the container is stopped immediately.
     * Otherwise it is returned to the warm pool.
     */
    public void release(ContainerHandle handle) {
        boolean ephemeral = config != null && config.services().lambda().ephemeral();
        if (ephemeral) {
            LOG.debugv("Ephemeral: stopping container {0} after invocation", handle.getContainerId());
            stopQuietly(handle);
            return;
        }

        handle.setState(ContainerState.WARM);
        handle.touchLastUsed();
        ArrayDeque<ContainerHandle> queue = pool.computeIfAbsent(handle.getFunctionName(), k -> new ArrayDeque<>());
        boolean returned;
        synchronized (queue) {
            returned = queue.size() < maxPoolSizePerFunction;
            if (returned) {
                queue.addFirst(handle);
            }
        }
        if (returned) {
            LOG.debugv("Released container back to pool for function: {0}", handle.getFunctionName());
        } else {
            LOG.debugv("Pool full for function {0}, stopping excess container", handle.getFunctionName());
            stopQuietly(handle);
        }
    }

    /**
     * Pushes a code update to all warm containers in the pool for the given function.
     * In this implementation, we drain the containers to force a fresh start with new code.
     */
    public void pushCodeUpdate(LambdaFunction fn) {
        LOG.infov("Reactive S3 Sync: invalidating warm pool for function {0} to pick up new code",
                fn.getFunctionName());
        drainFunction(fn.getFunctionName());
    }

    /**
     * Stops and removes a single container that is no longer usable (e.g. after a timeout).
     * The container must have already been acquired (removed from the pool) so only a
     * stop is needed — no pool bookkeeping required.
     */
    public void destroyHandle(ContainerHandle handle) {
        LOG.debugv("Destroying timed-out container {0} for function {1}",
                handle.getContainerId(), handle.getFunctionName());
        stopQuietly(handle);
    }

    /**
     * Stops and removes all warm containers for the given function.
     * Called on function delete or code update.
     */
    public void drainFunction(String functionName) {
        ArrayDeque<ContainerHandle> queue = pool.remove(functionName);
        if (queue == null) {
            return;
        }
        List<ContainerHandle> toStop;
        synchronized (queue) {
            toStop = new ArrayList<>(queue);
            queue.clear();
        }
        LOG.infov("Draining {0} container(s) for function: {1}", toStop.size(), functionName);
        for (ContainerHandle handle : toStop) {
            stopQuietly(handle);
        }
    }

    private void drainAll() {
        for (String functionName : new ArrayList<>(pool.keySet())) {
            drainFunction(functionName);
        }
    }

    private void evictIdleContainers() {
        if (config == null) return;
        long idleTimeoutMs = config.services().lambda().containerIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();

        for (var entry : pool.entrySet()) {
            String functionName = entry.getKey();
            ArrayDeque<ContainerHandle> queue = entry.getValue();
            List<ContainerHandle> toEvict = new ArrayList<>();

            synchronized (queue) {
                queue.removeIf(handle -> {
                    if (handle.getState() == ContainerState.WARM
                            && (now - handle.getLastUsedMs()) >= idleTimeoutMs) {
                        toEvict.add(handle);
                        return true;
                    }
                    return false;
                });
            }

            // Re-check that the queue is still registered to avoid double-stop with drainFunction.
            if (!toEvict.isEmpty() && pool.get(functionName) == queue) {
                LOG.infov("Evicting {0} idle container(s) for function: {1}", toEvict.size(), functionName);
                for (ContainerHandle handle : toEvict) {
                    stopQuietly(handle);
                }
            }
        }
    }

    private void stopQuietly(ContainerHandle handle) {
        try {
            containerLauncher.stop(handle);
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", handle.getContainerId(), e.getMessage());
        }
    }
}
