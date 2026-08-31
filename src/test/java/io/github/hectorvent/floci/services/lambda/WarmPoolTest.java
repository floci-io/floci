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

    private WarmPool buildPool() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.ephemeral()).thenReturn(false);
        when(lambda.containerIdleTimeoutSeconds()).thenReturn(0);
        return new WarmPool(containerLauncher, config);
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
}
