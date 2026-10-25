package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.ContainerTeardowns;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages a pool of warm Lambda containers per function.
 *
 * Two modes controlled by {@code emulator.services.lambda.ephemeral}:
 *  - {@code false} (default): containers are reused across invocations and evicted
 *    after {@code container-idle-timeout-seconds} of inactivity. Idle containers are
 *    bounded per function by {@code warm-pool-max-per-function} and, when
 *    {@code warm-pool-max-total} is set, across all functions by stopping the
 *    least-recently-used idle container of any function to make room.
 *  - {@code true}: each invocation gets a fresh container that is stopped immediately
 *    after the invocation completes.
 */
@ApplicationScoped
@Startup
public class WarmPool implements ContainerTeardown, Resettable {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);

    private static final int DEFAULT_MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final LambdaRuntimeLauncher lambdaRuntimeLauncher;
    private final ProvisionedConcurrencyPool provisioned;
    private final EmulatorConfig config;
    private final Clock clock;
    private final int maxPoolSizePerFunction;
    private final int maxIdleTotal;
    private final long drainTimeoutNanos;
    private final ConcurrentHashMap<String, PoolState> poolStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ContainerHandle, Lease> activeLeases = new ConcurrentHashMap<>();
    private final Set<ContainerHandle> owned = ConcurrentHashMap.newKeySet();
    private final Set<ContainerHandle> pendingTeardown = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ContainerHandle, CountDownLatch> stoppingHandles = new ConcurrentHashMap<>();
    private LifecycleState lifecycleState = new LifecycleState();
    /**
     * Serialises the return-to-pool step of {@link #release} so the global idle count is
     * checked and grown atomically. Lock order is this lock, then the releasing function's
     * pool, then any other pool the LRU eviction touches. Every other path holds a single pool
     * lock and never this one, so the nesting cannot cycle.
     */
    private final Object capLock = new Object();
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "warm-pool-evictor"); t.setDaemon(true); return t; });

    private static final class LifecycleState {
        private boolean stopped;
        private int activeLaunches;
        private long deadlineNanos;
    }

    private static final class PoolState {
        private final Map<String, ArrayDeque<ContainerHandle>> idleByEnvironment = new HashMap<>();
        private final Map<String, Long> epochByEnvironment = new HashMap<>();
    }

    private record Lease(PoolState poolState, long epoch, String environmentKey) {
    }

    private record IdleEntry(PoolState poolState, String environmentKey,
                             ArrayDeque<ContainerHandle> idle, ContainerHandle handle) {
    }

    @Inject
    public WarmPool(LambdaRuntimeLauncher lambdaRuntimeLauncher, EmulatorConfig config, Clock clock,
                    ProvisionedConcurrencyPool provisioned) {
        this.lambdaRuntimeLauncher = lambdaRuntimeLauncher;
        this.provisioned = provisioned;
        this.config = config;
        this.clock = clock;
        this.maxPoolSizePerFunction = resolveMaxPerFunction(config);
        this.maxIdleTotal = resolveMaxTotal(config);
        this.drainTimeoutNanos = TimeUnit.SECONDS.toNanos(resolveDrainTimeout(config));
    }

    public WarmPool(LambdaRuntimeLauncher launcher, EmulatorConfig config, Clock clock) {
        this(launcher, config, clock, null);
    }

    /** Package-private constructor for testing (empty pool, no containers to drain). */
    WarmPool() {
        this.lambdaRuntimeLauncher = null;
        this.provisioned = null;
        this.config = null;
        this.clock = Clock.systemUTC();
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
        this.maxIdleTotal = 0;
        this.drainTimeoutNanos = TimeUnit.SECONDS.toNanos(15);
    }

    static int resolveDrainTimeout(EmulatorConfig config) {
        int seconds = config.services().lambda().containerDrainTimeoutSeconds();
        if (seconds < 1 || seconds > 300) {
            throw new IllegalArgumentException("floci.services.lambda.container-drain-timeout-seconds "
                    + "must be between 1 and 300 seconds; received " + seconds);
        }
        return seconds;
    }

    static int resolveMaxPerFunction(EmulatorConfig config) {
        Optional<Integer> configured = config.services().lambda().warmPoolMaxPerFunction();
        if (configured.isEmpty()) {
            return DEFAULT_MAX_POOL_SIZE;
        }
        int max = configured.get();
        if (max < 1) {
            LOG.warnv("Ignoring floci.services.lambda.warm-pool-max-per-function {0}: must be "
                    + "at least 1; using {1}", max, DEFAULT_MAX_POOL_SIZE);
            return DEFAULT_MAX_POOL_SIZE;
        }
        return max;
    }

    static int resolveMaxTotal(EmulatorConfig config) {
        int max = config.services().lambda().warmPoolMaxTotal();
        if (max < 0) {
            LOG.warnv("Ignoring floci.services.lambda.warm-pool-max-total {0}: must be 0 (unbounded) "
                    + "or positive; leaving the total unbounded", max);
            return 0;
        }
        return max;
    }

    @PostConstruct
    void init() {
        if (config == null) {
            return;
        }

        int idleTimeout = config.services().lambda().containerIdleTimeoutSeconds();
        long checkInterval = idleTimeout > 0 ? Math.min(30, idleTimeout / 2 + 1) : 30;
        evictionScheduler.scheduleAtFixedRate(this::evictIdleContainers,
                checkInterval, checkInterval, TimeUnit.SECONDS);
        if (!config.services().lambda().ephemeral() && idleTimeout > 0) {
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
        LifecycleState shutdownState;
        List<ContainerHandle> knownHandles;
        synchronized (capLock) {
            shutdownState = lifecycleState;
            if (shutdownState.stopped) {
                return;
            }
            shutdownState.stopped = true;
            shutdownState.deadlineNanos = System.nanoTime() + drainTimeoutNanos;
            for (String functionName : new ArrayList<>(poolStates.keySet())) {
                detachFunction(functionName);
            }
            knownHandles = new ArrayList<>(owned);
            pendingTeardown.addAll(knownHandles);
            pendingTeardown.removeIf(handle -> !owned.contains(handle));
        }
        List<Runnable> drains = new ArrayList<>();
        if (provisioned != null) {
            drains.add(() -> provisioned.stopManagedContainers(shutdownState.deadlineNanos));
        }
        drains.add(() -> drainAll(shutdownState, knownHandles));
        ContainerTeardowns.stopInParallel(drains, Runnable::run, LOG, shutdownState.deadlineNanos);
    }

    @PreDestroy
    void shutdown() {
        evictionScheduler.shutdownNow();
        try {
            stopManagedContainers();
        } finally {
            if (provisioned != null) {
                provisioned.closeExecutors();
            }
        }
    }

    @Override
    public void clear() {
        // Removal failures remain owned across reset so the next drain can retry them.
    }

    @Override
    public void afterReset() {
        synchronized (capLock) {
            lifecycleState = new LifecycleState();
        }
    }

    /**
     * Acquires a container for the given function.
     * In ephemeral mode always cold-starts a new container.
     * Otherwise returns a warm container from the pool, or cold-starts a new one.
     */
    public ContainerHandle acquire(LambdaFunction fn) {
        LifecycleState admission;
        synchronized (capLock) {
            admission = lifecycleState;
            requireCurrentAdmission(admission);
        }
        boolean ephemeral = config != null && config.services().lambda().ephemeral();
        ContainerHandle handle = null;
        PoolState poolState = null;
        long leaseEpoch = 0;
        String environmentKey = executionEnvironmentKey(fn);

        if (!ephemeral) {
            synchronized (capLock) {
                requireCurrentAdmission(admission);
                poolState = poolStates.computeIfAbsent(fn.getFunctionName(), ignored -> new PoolState());
                synchronized (poolState) {
                    leaseEpoch = poolState.epochByEnvironment.computeIfAbsent(environmentKey, ignored -> 0L);
                }
            }
            // Probe outside the locks, then recheck this acquisition's lifecycle before leasing.
            while (true) {
                ContainerHandle candidate;
                synchronized (capLock) {
                    requireCurrentAdmission(admission);
                    synchronized (poolState) {
                        ArrayDeque<ContainerHandle> idle = poolState.idleByEnvironment.get(environmentKey);
                        candidate = idle == null ? null : idle.pollFirst();
                    }
                }
                if (candidate == null) {
                    break;
                }
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

        boolean launching = handle == null;
        if (launching) {
            synchronized (capLock) {
                requireCurrentAdmission(admission);
                admission.activeLaunches++;
            }
        }
        try {
            if (launching) {
                LOG.debugv(ephemeral ? "Ephemeral start for function: {0}" : "Cold start for function: {0}",
                        fn.getFunctionName());
                handle = lambdaRuntimeLauncher.launch(fn);
                owned.add(handle);
            } else {
                LOG.debugv("Reusing warm container for function: {0}", fn.getFunctionName());
            }
            boolean rejected;
            synchronized (capLock) {
                rejected = admission.stopped || admission != lifecycleState;
                if (!rejected && !ephemeral) {
                    activeLeases.put(handle, new Lease(poolState, leaseEpoch, environmentKey));
                }
                if (!rejected) {
                    handle.setState(ContainerState.BUSY);
                }
            }
            if (rejected) {
                long now = System.nanoTime();
                long cleanupDeadline = admission.deadlineNanos > now
                        ? admission.deadlineNanos : now + drainTimeoutNanos;
                stopInParallel(List.of(handle), cleanupDeadline);
                if (owned.contains(handle)) {
                    LOG.warnv("Lambda acquisition crossed shutdown; container {0} remains owned for cleanup",
                            handle.getContainerId());
                }
                throw new AwsException("ResourceConflictException", "Lambda executor is stopping", 409);
            }
            return handle;
        } finally {
            if (launching) {
                synchronized (capLock) {
                    admission.activeLaunches--;
                    capLock.notifyAll();
                }
            }
        }
    }

    private void requireCurrentAdmission(LifecycleState admission) {
        if (admission.stopped || admission != lifecycleState) {
            throw new AwsException("ResourceConflictException", "Lambda executor is stopping", 409);
        }
    }

    /**
     * Returns a container after an invocation completes.
     * In ephemeral mode the container is stopped immediately.
     * Otherwise it is returned to the warm pool.
     */
    public void release(ContainerHandle handle) {
        if (provisioned != null && provisioned.release(handle, false)) {
            return;
        }
        Lease lease = activeLeases.remove(handle);
        if (!owned.contains(handle)) {
            return;
        }
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
        List<ContainerHandle> evicted = List.of();
        synchronized (capLock) {
            synchronized (lease.poolState()) {
                stale = lifecycleState.stopped || lease.epoch() != lease.poolState().epochByEnvironment
                        .getOrDefault(lease.environmentKey(), 0L);
                returned = !stale && idleSize(lease.poolState()) < maxPoolSizePerFunction;
                if (returned) {
                    if (maxIdleTotal > 0) {
                        evicted = evictIdleBeyond(maxIdleTotal - 1);
                    }
                    handle.setState(ContainerState.WARM);
                    handle.touchLastUsed(clock.millis());
                    lease.poolState().idleByEnvironment
                            .computeIfAbsent(lease.environmentKey(), ignored -> new ArrayDeque<>())
                            .addFirst(handle);
                }
            }
        }
        for (ContainerHandle victim : evicted) {
            LOG.infov("Warm pool global cap {0} reached; stopping least-recently-used container {1} for function {2}",
                    maxIdleTotal, victim.getContainerId(), victim.getFunctionName());
            stopQuietly(victim);
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
     * Removes idle containers, oldest {@code lastUsed} first across every function, until at
     * most {@code keep} remain, and returns the removed handles for the caller to stop outside
     * the locks. Must be called under {@link #capLock}. The snapshot and the removal take each
     * pool's lock separately, so a handle that {@link #acquire} pops in between is simply no
     * longer in its deque and is left to its new lease. A drain in that window detaches the
     * whole deque from its pool and stops its contents itself; the removal checks the deque is
     * still the live one for its environment, so a drained handle is not stopped twice.
     */
    private List<ContainerHandle> evictIdleBeyond(int keep) {
        List<IdleEntry> entries = new ArrayList<>();
        for (PoolState poolState : poolStates.values()) {
            synchronized (poolState) {
                for (Map.Entry<String, ArrayDeque<ContainerHandle>> idle : poolState.idleByEnvironment.entrySet()) {
                    for (ContainerHandle handle : idle.getValue()) {
                        entries.add(new IdleEntry(poolState, idle.getKey(), idle.getValue(), handle));
                    }
                }
            }
        }
        int excess = entries.size() - keep;
        if (excess <= 0) {
            return List.of();
        }
        entries.sort(Comparator.comparingLong(entry -> entry.handle().getLastUsedMs()));
        List<ContainerHandle> evicted = new ArrayList<>(excess);
        for (IdleEntry entry : entries.subList(0, excess)) {
            synchronized (entry.poolState()) {
                ArrayDeque<ContainerHandle> live = entry.poolState().idleByEnvironment.get(entry.environmentKey());
                if (live == entry.idle() && live.remove(entry.handle())) {
                    evicted.add(entry.handle());
                }
            }
        }
        return evicted;
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
        if (provisioned != null && provisioned.release(handle, true)) {
            return;
        }
        activeLeases.remove(handle);
        LOG.debugv("Destroying timed-out container {0} for function {1}",
                handle.getContainerId(), handle.getFunctionName());
        stopQuietly(handle);
    }

    ContainerHandle acquireProvisioned(LambdaFunction fn, String qualifier) {
        return provisioned == null ? null : provisioned.acquire(fn, qualifier);
    }

    void deleteProvisionedFunction(LambdaFunction fn) {
        if (provisioned != null) {
            provisioned.deleteFunction(fn);
        }
    }

    void deleteProvisionedQualifier(LambdaFunction fn, String qualifier) {
        if (provisioned != null) {
            provisioned.deleteQualifier(fn, qualifier);
        }
    }

    void validateProvisionedAliasUpdate(LambdaFunction fn, String qualifier, String version) {
        if (provisioned != null) {
            provisioned.validateAliasUpdate(fn, qualifier, version);
        }
    }

    void validateProvisionedReservation(LambdaFunction fn, Integer reserved) {
        if (provisioned != null) {
            provisioned.validateReservation(fn, reserved);
        }
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
        List<ContainerHandle> handles = detachFunction(functionName);
        for (ContainerHandle handle : pendingTeardown) {
            if (owned.contains(handle) && handle.getFunctionName().equals(functionName) && !handles.contains(handle)) {
                handles.add(handle);
            }
        }
        stopInParallel(handles);
    }

    private List<ContainerHandle> detachFunction(String functionName) {
        PoolState poolState = poolStates.computeIfAbsent(functionName, ignored -> new PoolState());
        List<ContainerHandle> toStop;
        synchronized (poolState) {
            poolState.epochByEnvironment.replaceAll((ignored, epoch) -> epoch + 1);
            toStop = new ArrayList<>();
            poolState.idleByEnvironment.values().forEach(toStop::addAll);
            poolState.idleByEnvironment.clear();
        }
        LOG.infov("Draining {0} container(s) for function: {1}", toStop.size(), functionName);
        return toStop;
    }

    private void stopInParallel(List<ContainerHandle> handles) {
        long deadline;
        synchronized (capLock) {
            deadline = lifecycleState.stopped ? lifecycleState.deadlineNanos
                    : System.nanoTime() + drainTimeoutNanos;
        }
        stopInParallel(handles, deadline);
    }

    private void stopInParallel(List<ContainerHandle> handles, long deadline) {
        for (ContainerHandle handle : handles) {
            if (owned.contains(handle)) {
                pendingTeardown.add(handle);
                if (!owned.contains(handle)) {
                    pendingTeardown.remove(handle);
                }
            }
        }
        ContainerTeardowns.stopInParallel(handles, handle -> stopOwnedHandle(handle, deadline), LOG, deadline);
    }

    private void drainAll(LifecycleState shutdownState, List<ContainerHandle> knownHandles) {
        List<Runnable> drains = new ArrayList<>();
        for (ContainerHandle handle : knownHandles) {
            drains.add(() -> stopOwnedHandle(handle, shutdownState.deadlineNanos));
        }
        drains.add(() -> awaitLaunches(shutdownState));
        ContainerTeardowns.stopInParallel(drains, Runnable::run, LOG, shutdownState.deadlineNanos);
        if (!owned.isEmpty()) {
            LOG.warnv("Shutdown drain ended with {0} on-demand Lambda environment(s) still owned", owned.size());
        }
    }

    private void awaitLaunches(LifecycleState shutdownState) {
        synchronized (capLock) {
            try {
                long remaining;
                while (shutdownState.activeLaunches > 0
                        && (remaining = shutdownState.deadlineNanos - System.nanoTime()) > 0) {
                    TimeUnit.NANOSECONDS.timedWait(capLock, remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (shutdownState.activeLaunches > 0) {
                LOG.warnv("Shutdown deadline reached with {0} Lambda launch(es) still pending",
                        shutdownState.activeLaunches);
            }
        }
    }

    void evictIdleContainers() {
        synchronized (capLock) {
            if (config == null || lifecycleState.stopped) {
                return;
            }
        }
        for (ContainerHandle handle : new ArrayList<>(pendingTeardown)) {
            stopQuietly(handle);
        }
        long idleTimeoutMs = config.services().lambda().containerIdleTimeoutSeconds() * 1000L;
        if (idleTimeoutMs <= 0 || config.services().lambda().ephemeral()) {
            return;
        }
        long now = clock.millis();

        for (Map.Entry<String, PoolState> entry : poolStates.entrySet()) {
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
        boolean shutdown;
        long deadline;
        synchronized (capLock) {
            shutdown = lifecycleState.stopped;
            deadline = lifecycleState.deadlineNanos;
        }
        if (shutdown) {
            long now = System.nanoTime();
            stopInParallel(List.of(handle), deadline > now ? deadline : now + drainTimeoutNanos);
        } else {
            stopOwnedHandle(handle, System.nanoTime());
        }
    }

    private void stopOwnedHandle(ContainerHandle handle, long deadlineNanos) {
        CountDownLatch completion = new CountDownLatch(1);
        CountDownLatch existing = stoppingHandles.putIfAbsent(handle, completion);
        if (existing != null) {
            try {
                existing.await(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        try {
            if (!owned.contains(handle)) {
                return;
            }
            pendingTeardown.add(handle);
            lambdaRuntimeLauncher.stop(handle);
            owned.remove(handle);
            pendingTeardown.remove(handle);
            activeLeases.remove(handle);
        } catch (Exception e) {
            LOG.warnv(e, "Error stopping container {0}", handle.getContainerId());
        } finally {
            stoppingHandles.remove(handle, completion);
            completion.countDown();
        }
    }
}
