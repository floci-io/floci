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
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages a pool of warm Lambda containers per function.
 *
 * Two modes controlled by {@code emulator.services.lambda.ephemeral}:
 *  - {@code false} (default): containers are reused across invocations and evicted
 *    after {@code container-idle-timeout-seconds} of inactivity.
 *  - {@code true}: each invocation gets a fresh container that is stopped immediately
 *    after the invocation completes.
 *
 * <p>When a physical-environment cap is configured, each acquired handle also owns one
 * close-once admission permit. The permit spans the invocation (including warm reuse and
 * container teardown) and is released on every terminal path.
 */
@ApplicationScoped
public class WarmPool implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);

    private static final int DEFAULT_MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final LambdaRuntimeLauncher lambdaRuntimeLauncher;
    private final EmulatorConfig config;
    private final LambdaEnvironmentLimiter environmentLimiter;
    private final int maxPoolSizePerFunction;
    private final ConcurrentHashMap<FunctionPoolKey, PoolState> poolStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ContainerHandle, Lease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicInteger idleContainerCount = new AtomicInteger();
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "warm-pool-evictor"); t.setDaemon(true); return t; });

    private record FunctionPoolKey(String accountId, String region, String functionName) {
    }

    private static final class PoolState {
        private final Map<String, ArrayDeque<ContainerHandle>> idleByEnvironment = new HashMap<>();
        private final Map<String, Long> epochByEnvironment = new HashMap<>();
    }

    private record Lease(PoolState poolState,
                         long epoch,
                         String environmentKey,
                         LambdaEnvironmentLimiter.Permit environmentPermit) {
    }

    @Inject
    public WarmPool(LambdaRuntimeLauncher lambdaRuntimeLauncher,
                    EmulatorConfig config,
                    LambdaEnvironmentLimiter environmentLimiter) {
        this.lambdaRuntimeLauncher = lambdaRuntimeLauncher;
        this.config = config;
        this.environmentLimiter = environmentLimiter;
        this.maxPoolSizePerFunction = maxPoolSize(config);
    }

    /** Constructor retained for focused tests and embedders that do not use CDI. */
    public WarmPool(LambdaRuntimeLauncher lambdaRuntimeLauncher, EmulatorConfig config) {
        this(lambdaRuntimeLauncher, config, new LambdaEnvironmentLimiter(config));
    }

    /** Package-private constructor for testing (empty pool, no containers to drain). */
    WarmPool() {
        this.lambdaRuntimeLauncher = null;
        this.config = null;
        this.environmentLimiter = new LambdaEnvironmentLimiter();
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
        environmentLimiter.close();
        drainAll();
    }

    @PreDestroy
    void shutdown() {
        evictionScheduler.shutdownNow();
        environmentLimiter.close();
        drainAll();
    }

    /**
     * Acquires a container for the given function.
     * In ephemeral mode always cold-starts a new container.
     * Otherwise returns a warm container from the pool, or cold-starts a new one.
     */
    public ContainerHandle acquire(LambdaFunction fn) {
        String environmentKey = executionEnvironmentKey(fn);
        LambdaEnvironmentLimiter.Permit environmentPermit = environmentLimiter.acquire(environmentKey);
        try {
            return acquireLeased(fn, environmentKey, environmentPermit);
        } catch (RuntimeException | Error e) {
            environmentPermit.close();
            throw e;
        }
    }

    private ContainerHandle acquireLeased(LambdaFunction fn,
                                          String environmentKey,
                                          LambdaEnvironmentLimiter.Permit environmentPermit) {
        boolean ephemeral = config != null && config.services().lambda().ephemeral();
        ContainerHandle handle = null;
        PoolState poolState = null;
        long leaseEpoch = 0;

        if (!ephemeral) {
            FunctionPoolKey poolKey = functionPoolKey(fn);
            poolState = poolStates.computeIfAbsent(poolKey, ignored -> new PoolState());
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
                    if (candidate != null) {
                        idleContainerCount.decrementAndGet();
                        if (idle.isEmpty()) {
                            poolState.idleByEnvironment.remove(environmentKey);
                        }
                    }
                }
                if (candidate == null) {
                    break;
                }
                // A container whose extension reported a fatal error is still *running*, so the
                // liveness probe alone would hand it back out. Skip it for the same reason a dead
                // one is skipped: it can no longer serve invocations correctly.
                boolean faulted;
                try {
                    faulted = candidate.isFaulted();
                } catch (RuntimeException | Error e) {
                    stopQuietly(candidate);
                    throw e;
                }
                if (faulted) {
                    LOG.infov("Discarding pooled container {0} for function {1}: an extension reported a fatal error",
                            candidate.getContainerId(), fn.getFunctionName());
                    stopQuietly(candidate);
                    continue;
                }
                boolean alive;
                try {
                    alive = lambdaRuntimeLauncher.isAlive(candidate);
                } catch (RuntimeException | Error e) {
                    stopQuietly(candidate);
                    throw e;
                }
                if (alive) {
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
        activeLeases.put(handle, new Lease(poolState, leaseEpoch, environmentKey, environmentPermit));
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
        try {
            // An extension reporting an init/exit error is fatal to the execution environment in real
            // AWS. RuntimeApiServer already refuses new work at that point; the container is torn down
            // here rather than at fault time so the invocation that was in flight when the extension
            // failed still completes normally through the runtime.
            boolean faulted;
            try {
                faulted = handle.isFaulted();
            } catch (RuntimeException | Error e) {
                stopQuietly(handle);
                throw e;
            }
            if (faulted) {
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
                returned = !stale && idleSize(lease.poolState()) < maxPoolSizePerFunction
                        && reserveIdleSlot();
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
        } finally {
            if (lease != null) {
                lease.environmentPermit().close();
            }
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
        LOG.debugv("Destroying timed-out container {0} for function {1}",
                handle.getContainerId(), handle.getFunctionName());
        Lease lease = activeLeases.remove(handle);
        try {
            stopQuietly(handle);
        } finally {
            if (lease != null) {
                lease.environmentPermit().close();
            }
        }
    }

    /**
     * Stops and removes warm containers for one immutable execution environment, such as
     * {@code $LATEST}. Published versions keep their independently keyed warm containers.
     */
    public void drainEnvironment(LambdaFunction fn) {
        String environmentKey = executionEnvironmentKey(fn);
        FunctionPoolKey poolKey = functionPoolKey(fn);
        PoolState poolState = poolStates.computeIfAbsent(poolKey, ignored -> new PoolState());
        List<ContainerHandle> toStop;
        synchronized (poolState) {
            poolState.epochByEnvironment.merge(environmentKey, 1L, Long::sum);
            ArrayDeque<ContainerHandle> idle = poolState.idleByEnvironment.remove(environmentKey);
            toStop = idle == null ? List.of() : new ArrayList<>(idle);
            idleContainerCount.addAndGet(-toStop.size());
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
        List<ContainerHandle> toStop = new ArrayList<>();
        for (var entry : poolStates.entrySet()) {
            if (!entry.getKey().functionName().equals(functionName)) {
                continue;
            }
            toStop.addAll(drainPoolState(entry.getValue()));
        }
        LOG.infov("Draining {0} container(s) for function: {1}", toStop.size(), functionName);
        stopInParallel(toStop);
    }

    /**
     * Stops and invalidates every warm environment for one account/region/function identity.
     * The name-only overload above remains for callers that intentionally drain all accounts.
     */
    public void drainFunction(LambdaFunction fn) {
        PoolState poolState = poolStates.computeIfAbsent(functionPoolKey(fn), ignored -> new PoolState());
        List<ContainerHandle> toStop = drainPoolState(poolState);
        LOG.infov("Draining {0} container(s) for function: {1}", toStop.size(), fn.getFunctionName());
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
        for (PoolState poolState : new ArrayList<>(poolStates.values())) {
            stopInParallel(drainPoolState(poolState));
        }
    }

    private void evictIdleContainers() {
        if (config == null) {
            return;
        }
        long idleTimeoutMs = config.services().lambda().containerIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();

        for (var entry : poolStates.entrySet()) {
            String functionName = entry.getKey().functionName();
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
                idleContainerCount.addAndGet(-toEvict.size());
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
        return accountId(fn) + ":" + fn.getFunctionName() + ":" + fn.getVersion();
    }

    private static FunctionPoolKey functionPoolKey(LambdaFunction fn) {
        return new FunctionPoolKey(accountId(fn), regionId(fn), fn.getFunctionName());
    }

    private static String regionId(LambdaFunction fn) {
        String functionArn = fn.getFunctionArn();
        if (functionArn != null && !functionArn.isBlank()) {
            String[] segments = functionArn.split(":", 6);
            if (segments.length >= 4 && !segments[3].isBlank()) {
                return segments[3];
            }
        }
        return "unknown";
    }

    private static String accountId(LambdaFunction fn) {
        String accountId = fn.getAccountId();
        if (accountId != null && !accountId.isBlank()) {
            return accountId;
        }
        String functionArn = fn.getFunctionArn();
        if (functionArn != null && !functionArn.isBlank()) {
            String[] segments = functionArn.split(":", 6);
            if (segments.length >= 5 && !segments[4].isBlank()) {
                return segments[4];
            }
        }
        return "000000000000";
    }

    private List<ContainerHandle> drainPoolState(PoolState poolState) {
        List<ContainerHandle> toStop = new ArrayList<>();
        synchronized (poolState) {
            poolState.epochByEnvironment.replaceAll((ignored, epoch) -> epoch + 1);
            poolState.idleByEnvironment.values().forEach(toStop::addAll);
            poolState.idleByEnvironment.clear();
            idleContainerCount.addAndGet(-toStop.size());
        }
        return toStop;
    }

    /**
     * Reserves one global warm-container slot. A bounded physical profile must not
     * retain more idle Docker environments than its configured cap across all accounts
     * and functions; the historical unbounded profile keeps the old per-function bound.
     */
    private boolean reserveIdleSlot() {
        if (!environmentLimiter.bounded()) {
            idleContainerCount.incrementAndGet();
            return true;
        }
        int cap = environmentLimiter.configuredLimit();
        while (true) {
            int current = idleContainerCount.get();
            if (current >= cap) {
                return false;
            }
            if (idleContainerCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Status/readback for physical environment admission. */
    public LambdaEnvironmentLimiter.Status status() {
        return environmentLimiter.status();
    }

    /** Number of currently idle warm containers retained by this pool. */
    public int idleContainerCount() {
        return idleContainerCount.get();
    }

    private static int maxPoolSize(EmulatorConfig config) {
        if (config == null || config.services() == null || config.services().lambda() == null) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        var configured = config.services().lambda().maxPhysicalEnvironments();
        if (configured == null || configured.isEmpty() || configured.getAsInt() <= 0) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        return Math.min(DEFAULT_MAX_POOL_SIZE, configured.getAsInt());
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
