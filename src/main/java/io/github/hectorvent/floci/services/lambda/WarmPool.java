package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
public class WarmPool implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);

    private static final int DEFAULT_MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final LambdaRuntimeLauncher lambdaRuntimeLauncher;
    private final EmulatorConfig config;
    private final int maxPoolSizePerFunction;
    private final ConcurrentHashMap<String, PoolState> poolStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ContainerHandle, Lease> activeLeases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "warm-pool-evictor"); t.setDaemon(true); return t; });

    private static final class PoolState {
        private final Map<String, ArrayDeque<ContainerHandle>> idleByEnvironment = new HashMap<>();
        private final Map<String, Long> epochByEnvironment = new HashMap<>();
    }

    private record Lease(PoolState poolState, long epoch, String environmentKey) {
    }

    @Inject
    public WarmPool(LambdaRuntimeLauncher lambdaRuntimeLauncher, EmulatorConfig config) {
        this.lambdaRuntimeLauncher = lambdaRuntimeLauncher;
        this.config = config;
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
    }

    /** Package-private constructor for testing (empty pool, no containers to drain). */
    WarmPool() {
        this.lambdaRuntimeLauncher = null;
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

    }

    /**
     * Invoked from EmulatorLifecycle.onStop for a deterministic drain during the
     * ShutdownEvent phase; the {@code @PreDestroy} below stays as an idempotent fallback.
     * This replaces the previous raw JVM shutdown hook, which raced the Quarkus-managed
     * shutdown sequence.
     */
    @Override
    public void stopManagedContainers() {
        drainAll();
    }

    @PreDestroy
    void shutdown() {
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
        PoolState poolState = null;
        long leaseEpoch = 0;
        String environmentKey = executionEnvironmentKey(fn);

        if (!ephemeral) {
            poolState = poolStates.computeIfAbsent(fn.getFunctionName(), ignored -> new PoolState());
            synchronized (poolState) {
                leaseEpoch = poolState.epochByEnvironment.computeIfAbsent(environmentKey, ignored -> 0L);
            }
            // Skip pooled handles whose container died out-of-band — otherwise the
            // caller would wait the full Lambda function timeout.
            while (true) {
                ContainerHandle candidate;
                synchronized (poolState) {
                    ArrayDeque<ContainerHandle> idle = poolState.idleByEnvironment.get(environmentKey);
                    candidate = idle == null ? null : idle.pollFirst();
                }
                if (candidate == null) {
                    break;
                }
                // A container whose extension reported a fatal error is still *running*, so the
                // liveness probe alone would hand it back out. Skip it for the same reason a dead
                // one is skipped: it can no longer serve invocations correctly.
                if (candidate.isFaulted()) {
                    LOG.infov("Discarding pooled container {0} for function {1}: an extension reported a fatal error",
                            candidate.getContainerId(), fn.getFunctionName());
                    stopQuietly(candidate);
                    continue;
                }
                if (lambdaRuntimeLauncher.isAlive(candidate)) {
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
            handle = lambdaRuntimeLauncher.launch(fn);
        } else {
            LOG.debugv("Reusing warm container for function: {0}", fn.getFunctionName());
        }
        if (!ephemeral) {
            activeLeases.put(handle, new Lease(poolState, leaseEpoch, environmentKey));
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
        Lease lease = activeLeases.remove(handle);
        boolean ephemeral = config != null && config.services().lambda().ephemeral();
        // An extension reporting an init/exit error is fatal to the execution environment in real
        // AWS. RuntimeApiServer already refuses new work at that point; the container is torn down
        // here rather than at fault time so the invocation that was in flight when the extension
        // failed still completes normally through the runtime.
        if (handle.isFaulted()) {
            LOG.infov("Retiring container {0} for function {1}: an extension reported a fatal error",
                    handle.getContainerId(), handle.getFunctionName());
            stopQuietly(handle);
            return;
        }
        if (ephemeral || handle.isHotReload()) {
            LOG.debugv("{0}: stopping container {1} after invocation",
                    handle.isHotReload() ? "Hot-reload" : "Ephemeral", handle.getContainerId());
            stopQuietly(handle);
            return;
        }

        if (lease == null) {
            LOG.warnv("Container {0} for function {1} has no active warm-pool lease; stopping it",
                    handle.getContainerId(), handle.getFunctionName());
            stopQuietly(handle);
            return;
        }

        boolean stale;
        boolean returned;
        synchronized (lease.poolState()) {
            stale = lease.epoch() != lease.poolState().epochByEnvironment
                    .getOrDefault(lease.environmentKey(), 0L);
            returned = !stale && idleSize(lease.poolState()) < maxPoolSizePerFunction;
            if (returned) {
                handle.setState(ContainerState.WARM);
                handle.touchLastUsed();
                lease.poolState().idleByEnvironment
                        .computeIfAbsent(lease.environmentKey(), ignored -> new ArrayDeque<>())
                        .addFirst(handle);
            }
        }
        if (stale) {
            LOG.debugv("Pool was invalidated while container {0} was busy; stopping it",
                    handle.getContainerId());
            stopQuietly(handle);
        } else if (returned) {
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
        drainEnvironment(fn);
    }

    /**
     * Stops and removes a single container that is no longer usable (e.g. after a timeout).
     * The container must have already been acquired (removed from the pool) so only a
     * stop is needed — no pool bookkeeping required.
     */
    public void destroyHandle(ContainerHandle handle) {
        activeLeases.remove(handle);
        LOG.debugv("Destroying timed-out container {0} for function {1}",
                handle.getContainerId(), handle.getFunctionName());
        stopQuietly(handle);
    }

    /**
     * Stops and removes warm containers for one immutable execution environment, such as
     * {@code $LATEST}. Published versions keep their independently keyed warm containers.
     */
    public void drainEnvironment(LambdaFunction fn) {
        String functionName = fn.getFunctionName();
        String environmentKey = executionEnvironmentKey(fn);
        PoolState poolState = poolStates.computeIfAbsent(functionName, ignored -> new PoolState());
        List<ContainerHandle> toStop;
        synchronized (poolState) {
            poolState.epochByEnvironment.merge(environmentKey, 1L, Long::sum);
            ArrayDeque<ContainerHandle> idle = poolState.idleByEnvironment.remove(environmentKey);
            toStop = idle == null ? List.of() : new ArrayList<>(idle);
        }
        LOG.infov("Draining {0} container(s) for Lambda environment: {1}",
                toStop.size(), environmentKey);
        stopInParallel(toStop);
    }

    /**
     * Stops and removes all warm containers for every environment of the given function.
     * Called on function deletion and emulator shutdown.
     */
    public void drainFunction(String functionName) {
        PoolState poolState = poolStates.computeIfAbsent(functionName, ignored -> new PoolState());
        List<ContainerHandle> toStop;
        synchronized (poolState) {
            poolState.epochByEnvironment.replaceAll((ignored, epoch) -> epoch + 1);
            toStop = new ArrayList<>();
            poolState.idleByEnvironment.values().forEach(toStop::addAll);
            poolState.idleByEnvironment.clear();
        }
        LOG.infov("Draining {0} container(s) for function: {1}", toStop.size(), functionName);
        stopInParallel(toStop);
    }

    private void stopInParallel(List<ContainerHandle> handles) {
        if (handles.isEmpty()) {
            return;
        }
        int parallelism = Math.min(handles.size(), 16);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism,
                r -> { Thread t = new Thread(r, "warm-pool-drainer"); t.setDaemon(true); return t; });
        try {
            List<Future<?>> futures = new ArrayList<>(handles.size());
            for (ContainerHandle handle : handles) {
                futures.add(pool.submit(() -> stopQuietly(handle)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.warnv("Drain task did not finish cleanly: {0}", e.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void drainAll() {
        for (String functionName : new ArrayList<>(poolStates.keySet())) {
            drainFunction(functionName);
        }
    }

    private void evictIdleContainers() {
        if (config == null) {
            return;
        }
        long idleTimeoutMs = config.services().lambda().containerIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();

        for (var entry : poolStates.entrySet()) {
            String functionName = entry.getKey();
            PoolState poolState = entry.getValue();
            List<ContainerHandle> toEvict = new ArrayList<>();

            synchronized (poolState) {
                for (ArrayDeque<ContainerHandle> idle : poolState.idleByEnvironment.values()) {
                    idle.removeIf(handle -> {
                        if (handle.getState() == ContainerState.WARM
                                && (now - handle.getLastUsedMs()) >= idleTimeoutMs) {
                            toEvict.add(handle);
                            return true;
                        }
                        return false;
                    });
                }
                poolState.idleByEnvironment.values().removeIf(ArrayDeque::isEmpty);
            }

            if (!toEvict.isEmpty()) {
                LOG.infov("Evicting {0} idle container(s) for function: {1}", toEvict.size(), functionName);
                for (ContainerHandle handle : toEvict) {
                    stopQuietly(handle);
                }
            }
        }
    }

    private static String executionEnvironmentKey(LambdaFunction fn) {
        String functionArn = fn.getFunctionArn();
        if (functionArn != null && !functionArn.isBlank()) {
            return functionArn;
        }
        return fn.getFunctionName() + ":" + fn.getVersion();
    }

    private static int idleSize(PoolState poolState) {
        int size = 0;
        for (ArrayDeque<ContainerHandle> idle : poolState.idleByEnvironment.values()) {
            size += idle.size();
        }
        return size;
    }

    private void stopQuietly(ContainerHandle handle) {
        try {
            lambdaRuntimeLauncher.stop(handle);
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", handle.getContainerId(), e.getMessage());
        }
    }
}
