package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarmPoolTest {

    @Mock LambdaRuntimeLauncher containerLauncher;
    @Mock EmulatorConfig config;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T12:00:00Z"));

    private WarmPool buildPool() {
        return buildPool(Optional.empty(), 0);
    }

    private WarmPool buildPool(Optional<Integer> maxPerFunction, int maxTotal) {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.ephemeral()).thenReturn(false);
        when(lambda.containerIdleTimeoutSeconds()).thenReturn(0);
        when(lambda.warmPoolMaxPerFunction()).thenReturn(maxPerFunction);
        when(lambda.warmPoolMaxTotal()).thenReturn(maxTotal);
        return new WarmPool(containerLauncher, config, clock);
    }

    private static LambdaFunction function(String name) {
        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn(name);
        return fn;
    }

    /** LRU order is by the release timestamp, so consecutive releases must land on distinct ticks. */
    private void nextMillisecond() {
        clock.advance(Duration.ofMillis(1));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void stopManagedContainersDrainsPool() {
        // Lifecycle-driven teardown replaces the old raw JVM shutdown hook: the pool
        // drains when EmulatorLifecycle.onStop invokes the ContainerTeardown contract.
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("drain-fn");
        ContainerHandle handle = new ContainerHandle("cid-drain", "drain-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(handle);

        pool.release(pool.acquire(fn));
        pool.stopManagedContainers();
        verify(containerLauncher).stop(handle);

        // Idempotent: a second drain (e.g. the @PreDestroy fallback) is a no-op.
        pool.stopManagedContainers();
        verify(containerLauncher, times(1)).stop(handle);
        pool.shutdown();
    }

    @Test
    void stopManagedContainersOnEmptyPoolIsNoOp() {
        WarmPool pool = buildPool();
        pool.init();

        pool.stopManagedContainers();

        pool.shutdown();
    }

    @Test
    void destroyHandleStopsContainerAndDoesNotReturnToPool() {
        WarmPool pool = buildPool();
        pool.init();

        ContainerHandle handle = new ContainerHandle("cid-123", "my-fn", null, ContainerState.BUSY);
        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("my-fn");
        when(containerLauncher.launch(any())).thenReturn(handle);

        ContainerHandle acquired = pool.acquire(fn);
        assertEquals(handle, acquired);

        pool.destroyHandle(acquired);
        verify(containerLauncher).stop(handle);

        // Pool must be empty — next acquire must cold-start
        ContainerHandle handle2 = new ContainerHandle("cid-456", "my-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(handle2);
        ContainerHandle secondAcquired = pool.acquire(fn);
        assertEquals(handle2, secondAcquired);

        pool.shutdown();
    }

    @Test
    void destroyHandle_doesNotAffectOtherContainersInPool() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("multi-fn");

        ContainerHandle h1 = new ContainerHandle("cid-a", "multi-fn", null, ContainerState.WARM);
        ContainerHandle h2 = new ContainerHandle("cid-b", "multi-fn", null, ContainerState.WARM);

        when(containerLauncher.launch(any())).thenReturn(h1, h2);
        when(containerLauncher.isAlive(any())).thenReturn(true);

        ContainerHandle acquired1 = pool.acquire(fn);
        pool.release(acquired1);

        ContainerHandle acquired2 = pool.acquire(fn);
        pool.release(acquired2);

        // Re-acquire both: h2 was released last so it's at the front of the deque
        ContainerHandle toDestroy = pool.acquire(fn);
        ContainerHandle survivor = pool.acquire(fn);

        pool.destroyHandle(toDestroy);
        verify(containerLauncher, times(1)).stop(toDestroy);
        verify(containerLauncher, never()).stop(survivor);

        // Survivor can be released back and re-acquired
        pool.release(survivor);
        ContainerHandle reacquired = pool.acquire(fn);
        assertSame(survivor, reacquired);

        pool.shutdown();
    }

    @Test
    void releaseAfterSuccessfulInvocation_returnsToPool() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("reuse-fn");

        ContainerHandle handle = new ContainerHandle("cid-reuse", "reuse-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(handle);
        when(containerLauncher.isAlive(any())).thenReturn(true);

        ContainerHandle first = pool.acquire(fn);
        assertEquals(ContainerState.BUSY, first.getState());

        pool.release(first);
        assertEquals(ContainerState.WARM, first.getState());

        // Second acquire should return the same handle from the pool (no cold start)
        ContainerHandle second = pool.acquire(fn);
        assertSame(handle, second);

        // containerLauncher.launch should only have been called once (cold start)
        verify(containerLauncher, times(1)).launch(any());

        pool.shutdown();
    }

    /**
     * An extension reporting init/exit error is fatal to the execution environment in real AWS,
     * so the container must be retired after the invocation rather than returned to the pool.
     */
    @Test
    void releaseAfterExtensionFatalError_retiresContainerInsteadOfPooling() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("faulted-fn");

        RuntimeApiServer faultedServer = mock(RuntimeApiServer.class);
        when(faultedServer.isFaulted()).thenReturn(true);
        ContainerHandle faulted = new ContainerHandle("cid-faulted", "faulted-fn", faultedServer, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-fresh", "faulted-fn", null, ContainerState.WARM);

        // Both acquires below cold-start (the pool is empty, then the faulted handle is discarded
        // before any liveness check), so no isAlive stubbing is needed.
        when(containerLauncher.launch(any())).thenReturn(faulted, fresh);

        ContainerHandle first = pool.acquire(fn);
        assertSame(faulted, first);

        // The extension died during this invocation; releasing must tear the container down.
        pool.release(first);
        verify(containerLauncher, times(1)).stop(faulted);

        // The next acquire cold-starts rather than handing the condemned container back out.
        ContainerHandle second = pool.acquire(fn);
        assertSame(fresh, second);
        assertNotSame(faulted, second);
        verify(containerLauncher, times(2)).launch(any());

        pool.shutdown();
    }

    /**
     * A container whose extension faults while it sits WARM in the pool is still *running*, so the
     * liveness probe alone would hand it back out. It must be skipped and discarded on acquire.
     */
    @Test
    void acquire_discardsPooledHandleWhoseExtensionFaulted() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("pooled-fault-fn");

        RuntimeApiServer server = mock(RuntimeApiServer.class);
        ContainerHandle pooled = new ContainerHandle("cid-pooled", "pooled-fault-fn", server, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-fresh", "pooled-fault-fn", null, ContainerState.WARM);

        when(containerLauncher.launch(any())).thenReturn(pooled, fresh);
        // Healthy at release time, so it goes into the pool as normal.
        when(server.isFaulted()).thenReturn(false);
        ContainerHandle seeded = pool.acquire(fn);
        assertSame(pooled, seeded);
        pool.release(seeded);

        // The extension now faults while the container sits idle in the pool. isAlive() is stubbed
        // true so the container is unambiguously still running: if the faulted check were removed,
        // this handle would be considered reusable and handed straight back out.
        when(server.isFaulted()).thenReturn(true);
        lenient().when(containerLauncher.isAlive(pooled)).thenReturn(true);

        ContainerHandle acquired = pool.acquire(fn);
        assertSame(fresh, acquired);
        assertNotSame(pooled, acquired);
        verify(containerLauncher, times(1)).stop(pooled);

        pool.shutdown();
    }

    @Test
    void versionsUseSeparateWarmPools() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction latest = mock(LambdaFunction.class);
        LambdaFunction version = mock(LambdaFunction.class);
        when(latest.getFunctionName()).thenReturn("versioned-fn");
        when(version.getFunctionName()).thenReturn("versioned-fn");
        when(latest.getFunctionArn())
                .thenReturn("arn:aws:lambda:us-east-1:000000000000:function:versioned-fn");
        when(version.getFunctionArn())
                .thenReturn("arn:aws:lambda:us-east-1:000000000000:function:versioned-fn:1");

        ContainerHandle latestHandle = new ContainerHandle(
                "cid-latest", "versioned-fn", null, ContainerState.WARM);
        ContainerHandle versionHandle = new ContainerHandle(
                "cid-version", "versioned-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(latestHandle, versionHandle);
        when(containerLauncher.isAlive(latestHandle)).thenReturn(true);
        when(containerLauncher.isAlive(versionHandle)).thenReturn(true);

        pool.release(pool.acquire(latest));
        ContainerHandle firstVersion = pool.acquire(version);
        assertSame(versionHandle, firstVersion);
        verify(containerLauncher, times(2)).launch(any());
        pool.release(firstVersion);

        ContainerHandle reacquiredLatest = pool.acquire(latest);
        ContainerHandle reacquiredVersion = pool.acquire(version);
        assertSame(latestHandle, reacquiredLatest);
        assertSame(versionHandle, reacquiredVersion);
        verify(containerLauncher, times(2)).launch(any());

        pool.release(reacquiredLatest);
        pool.release(reacquiredVersion);
        pool.shutdown();
    }

    @Test
    void drainingLatestPreservesPublishedVersionPool() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction latest = mock(LambdaFunction.class);
        LambdaFunction version = mock(LambdaFunction.class);
        when(latest.getFunctionName()).thenReturn("versioned-drain-fn");
        when(version.getFunctionName()).thenReturn("versioned-drain-fn");
        when(latest.getFunctionArn())
                .thenReturn("arn:aws:lambda:us-east-1:000000000000:function:versioned-drain-fn");
        when(version.getFunctionArn())
                .thenReturn("arn:aws:lambda:us-east-1:000000000000:function:versioned-drain-fn:1");

        ContainerHandle latestHandle = new ContainerHandle(
                "cid-drain-latest", "versioned-drain-fn", null, ContainerState.WARM);
        ContainerHandle versionHandle = new ContainerHandle(
                "cid-drain-version", "versioned-drain-fn", null, ContainerState.WARM);
        ContainerHandle refreshedLatest = new ContainerHandle(
                "cid-drain-latest-fresh", "versioned-drain-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(latestHandle, versionHandle, refreshedLatest);
        when(containerLauncher.isAlive(versionHandle)).thenReturn(true);

        pool.release(pool.acquire(latest));
        pool.release(pool.acquire(version));

        pool.drainEnvironment(latest);

        verify(containerLauncher).stop(latestHandle);
        verify(containerLauncher, never()).stop(versionHandle);
        assertSame(versionHandle, pool.acquire(version));
        assertSame(refreshedLatest, pool.acquire(latest));
        verify(containerLauncher, times(3)).launch(any());

        pool.shutdown();
    }

    @Test
    void acquire_discardsDeadPooledHandleAndColdStarts() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("dead-fn");

        ContainerHandle dead = new ContainerHandle("cid-dead", "dead-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-fresh", "dead-fn", null, ContainerState.WARM);

        // Seed the pool with the dead handle by acquiring + releasing it once.
        // The seed acquire is a cold start (empty pool), so isAlive isn't called.
        when(containerLauncher.launch(any())).thenReturn(dead, fresh);
        ContainerHandle seeded = pool.acquire(fn);
        assertSame(dead, seeded);
        pool.release(seeded);

        // Now the container "dies" out-of-band (docker rm -f, OOM, etc.).
        when(containerLauncher.isAlive(dead)).thenReturn(false);

        ContainerHandle acquired = pool.acquire(fn);
        assertSame(fresh, acquired);
        assertNotSame(dead, acquired);
        verify(containerLauncher, times(1)).stop(dead);
        verify(containerLauncher, times(2)).launch(any());

        pool.shutdown();
    }

    @Test
    void acquire_skipsDeadHandleAndReusesNextAlive() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("mixed-fn");

        ContainerHandle dead = new ContainerHandle("cid-dead", "mixed-fn", null, ContainerState.WARM);
        ContainerHandle alive = new ContainerHandle("cid-alive", "mixed-fn", null, ContainerState.WARM);

        // Seed deque with [dead, alive]: release(alive) first, then release(dead),
        // so dead ends up at the front (release uses addFirst). Both acquires
        // here are cold starts (empty pool) so no isAlive stub is needed yet.
        when(containerLauncher.launch(any())).thenReturn(alive, dead);
        ContainerHandle a1 = pool.acquire(fn);
        ContainerHandle a2 = pool.acquire(fn);
        assertSame(alive, a1);
        assertSame(dead, a2);
        pool.release(a1);
        pool.release(a2);

        // dead dies out-of-band, alive is still up.
        when(containerLauncher.isAlive(dead)).thenReturn(false);
        when(containerLauncher.isAlive(alive)).thenReturn(true);

        ContainerHandle acquired = pool.acquire(fn);
        assertSame(alive, acquired);
        verify(containerLauncher, times(1)).stop(dead);
        verify(containerLauncher, never()).stop(alive);
        // Only the original two cold starts; no extra launch was needed.
        verify(containerLauncher, times(2)).launch(any());

        pool.shutdown();
    }

    @Test
    void releaseAfterDrainStopsStaleHandleAndColdStarts() {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("updated-fn");
        ContainerHandle stale = new ContainerHandle(
                "cid-stale", "updated-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle(
                "cid-fresh", "updated-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(stale, fresh);

        ContainerHandle acquired = pool.acquire(fn);
        pool.drainFunction("updated-fn");
        pool.release(acquired);

        verify(containerLauncher).stop(stale);
        assertSame(fresh, pool.acquire(fn));
        verify(containerLauncher, times(2)).launch(any());

        pool.shutdown();
    }

    @Test
    void launchCrossingDrainIsNeverReturnedToPool() throws Exception {
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("racing-fn");
        ContainerHandle stale = new ContainerHandle(
                "cid-racing-stale", "racing-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle(
                "cid-racing-fresh", "racing-fn", null, ContainerState.WARM);
        CountDownLatch launchStarted = new CountDownLatch(1);
        CountDownLatch finishLaunch = new CountDownLatch(1);
        when(containerLauncher.launch(any()))
                .thenAnswer(invocation -> {
                    launchStarted.countDown();
                    assertTrue(finishLaunch.await(5, TimeUnit.SECONDS));
                    return stale;
                })
                .thenReturn(fresh);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ContainerHandle> acquisition = executor.submit(() -> pool.acquire(fn));
            assertTrue(launchStarted.await(5, TimeUnit.SECONDS));

            pool.drainFunction("racing-fn");
            finishLaunch.countDown();
            ContainerHandle acquired = acquisition.get(5, TimeUnit.SECONDS);
            pool.release(acquired);

            verify(containerLauncher).stop(stale);
            ContainerHandle postDrain = pool.acquire(fn);
            assertSame(fresh, postDrain);

            when(containerLauncher.isAlive(fresh)).thenReturn(true);
            pool.release(postDrain);
            assertSame(fresh, pool.acquire(fn));
            verify(containerLauncher, times(2)).launch(any());
        } finally {
            finishLaunch.countDown();
            executor.shutdownNow();
            pool.shutdown();
        }
    }

    /**
     * A drain detaches the deque from its pool and stops the contents itself. An eviction that
     * snapshotted the same deque before the drain must not remove from the orphaned object and
     * stop the container a second time. The LRU sort reads {@code getLastUsedMs()} between the
     * snapshot and the removal, which is the only hook into that window without a sleep.
     */
    @Test
    void totalCap_doesNotStopContainerAlreadyClaimedByDrain() {
        WarmPool pool = buildPool(Optional.empty(), 2);
        pool.init();

        LambdaFunction fnA = function("fn-a");
        LambdaFunction fnB = function("fn-b");
        LambdaFunction fnC = function("fn-c");
        CountDownLatch drainOnce = new CountDownLatch(1);
        ContainerHandle a1 = new ContainerHandle("cid-a1", "fn-a", null, ContainerState.WARM) {
            @Override
            public long getLastUsedMs() {
                if (drainOnce.getCount() > 0) {
                    drainOnce.countDown();
                    pool.drainFunction("fn-a");
                }
                return super.getLastUsedMs();
            }
        };
        ContainerHandle b1 = new ContainerHandle("cid-b1", "fn-b", null, ContainerState.WARM);
        ContainerHandle c1 = new ContainerHandle("cid-c1", "fn-c", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(a1, b1, c1);

        ContainerHandle leasedA1 = pool.acquire(fnA);
        ContainerHandle leasedB1 = pool.acquire(fnB);
        ContainerHandle leasedC1 = pool.acquire(fnC);
        pool.release(leasedA1);
        nextMillisecond();
        pool.release(leasedB1);
        nextMillisecond();
        // Two idle entries are snapshotted, so the LRU sort compares them and the hook drains
        // fn-a before the removal step. a1 is the LRU; it must be stopped by the drain only.
        pool.release(leasedC1);

        assertEquals(0, drainOnce.getCount());
        verify(containerLauncher, times(1)).stop(a1);
        verify(containerLauncher, never()).stop(b1);
        verify(containerLauncher, never()).stop(c1);

        pool.shutdown();
    }

    @Test
    void totalCap_evictsLeastRecentlyUsedAcrossFunctions() {
        WarmPool pool = buildPool(Optional.empty(), 2);
        pool.init();

        LambdaFunction fnA = function("fn-a");
        LambdaFunction fnB = function("fn-b");
        ContainerHandle a1 = new ContainerHandle("cid-a1", "fn-a", null, ContainerState.WARM);
        ContainerHandle b1 = new ContainerHandle("cid-b1", "fn-b", null, ContainerState.WARM);
        ContainerHandle a2 = new ContainerHandle("cid-a2", "fn-a", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(a1, b1, a2);

        // Lease all three at once so none is reused, then release oldest-first.
        ContainerHandle leasedA1 = pool.acquire(fnA);
        ContainerHandle leasedB1 = pool.acquire(fnB);
        ContainerHandle leasedA2 = pool.acquire(fnA);
        pool.release(leasedA1);
        nextMillisecond();
        pool.release(leasedB1);
        nextMillisecond();
        pool.release(leasedA2);

        // Third idle container exceeds the cap of 2: a1 is the global LRU and goes, even though
        // it belongs to the function that just released. b1 (older than a2) survives.
        verify(containerLauncher).stop(a1);
        verify(containerLauncher, never()).stop(b1);
        verify(containerLauncher, never()).stop(a2);

        when(containerLauncher.isAlive(any())).thenReturn(true);
        assertSame(a2, pool.acquire(fnA));
        assertSame(b1, pool.acquire(fnB));
        verify(containerLauncher, times(3)).launch(any());

        pool.shutdown();
    }

    @Test
    void totalCap_evictsOwnOldestContainerWhenSameFunction() {
        WarmPool pool = buildPool(Optional.empty(), 1);
        pool.init();

        LambdaFunction fn = function("fn-single");
        ContainerHandle h1 = new ContainerHandle("cid-1", "fn-single", null, ContainerState.WARM);
        ContainerHandle h2 = new ContainerHandle("cid-2", "fn-single", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(h1, h2);

        ContainerHandle leased1 = pool.acquire(fn);
        ContainerHandle leased2 = pool.acquire(fn);
        pool.release(leased1);
        nextMillisecond();
        pool.release(leased2);

        verify(containerLauncher).stop(h1);
        verify(containerLauncher, never()).stop(h2);

        when(containerLauncher.isAlive(h2)).thenReturn(true);
        assertSame(h2, pool.acquire(fn));

        pool.shutdown();
    }

    @Test
    void perFunctionCap_rejectsExcessWithoutEvictingOtherFunctions() {
        WarmPool pool = buildPool(Optional.of(1), 10);
        pool.init();

        LambdaFunction fnA = function("fn-a");
        LambdaFunction fnB = function("fn-b");
        ContainerHandle b1 = new ContainerHandle("cid-b1", "fn-b", null, ContainerState.WARM);
        ContainerHandle a1 = new ContainerHandle("cid-a1", "fn-a", null, ContainerState.WARM);
        ContainerHandle a2 = new ContainerHandle("cid-a2", "fn-a", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(b1, a1, a2);

        ContainerHandle leasedB1 = pool.acquire(fnB);
        ContainerHandle leasedA1 = pool.acquire(fnA);
        ContainerHandle leasedA2 = pool.acquire(fnA);
        pool.release(leasedB1);
        nextMillisecond();
        pool.release(leasedA1);
        nextMillisecond();
        pool.release(leasedA2);

        // fn-a already holds its one idle container, so a2 is the excess and is stopped. The
        // global LRU (b1) is untouched: the total cap only evicts to admit a container that the
        // per-function cap has accepted.
        verify(containerLauncher).stop(a2);
        verify(containerLauncher, never()).stop(a1);
        verify(containerLauncher, never()).stop(b1);

        pool.shutdown();
    }

    @Test
    void totalCapZero_keepsEveryIdleContainer() {
        WarmPool pool = buildPool(Optional.empty(), 0);
        pool.init();

        ContainerHandle a1 = new ContainerHandle("cid-a1", "fn-a", null, ContainerState.WARM);
        ContainerHandle b1 = new ContainerHandle("cid-b1", "fn-b", null, ContainerState.WARM);
        ContainerHandle c1 = new ContainerHandle("cid-c1", "fn-c", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(a1, b1, c1);

        pool.release(pool.acquire(function("fn-a")));
        pool.release(pool.acquire(function("fn-b")));
        pool.release(pool.acquire(function("fn-c")));

        verify(containerLauncher, never()).stop(any());

        pool.shutdown();
    }

    @Test
    void resolveMaxPerFunction_fallsBackToDerivedDefaultBelowOne() {
        int derived = Math.max(4, Runtime.getRuntime().availableProcessors());

        assertEquals(7, WarmPool.resolveMaxPerFunction(buildConfig(Optional.of(7))));
        assertEquals(derived, WarmPool.resolveMaxPerFunction(buildConfig(Optional.of(0))));
        assertEquals(derived, WarmPool.resolveMaxPerFunction(buildConfig(Optional.empty())));
    }

    /**
     * {@code 0} is the documented opt-out, so a negative value must not quietly become it:
     * the fallback is still unbounded, but it is logged rather than mistaken for a cap.
     */
    @Test
    void resolveMaxTotal_treatsNegativeAsUnboundedWithWarning() {
        assertEquals(24, WarmPool.resolveMaxTotal(buildConfig(Optional.empty(), 24)));
        assertEquals(0, WarmPool.resolveMaxTotal(buildConfig(Optional.empty(), 0)));
        assertEquals(0, WarmPool.resolveMaxTotal(buildConfig(Optional.empty(), -1)));
    }

    private static EmulatorConfig buildConfig(Optional<Integer> maxPerFunction) {
        return buildConfig(maxPerFunction, 0);
    }

    private static EmulatorConfig buildConfig(Optional<Integer> maxPerFunction, int maxTotal) {
        EmulatorConfig emulatorConfig = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(emulatorConfig.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        lenient().when(lambda.warmPoolMaxPerFunction()).thenReturn(maxPerFunction);
        lenient().when(lambda.warmPoolMaxTotal()).thenReturn(maxTotal);
        return emulatorConfig;
    }
}
