package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarmPoolTest {

    @Mock ContainerLauncher containerLauncher;
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

    @Test
    void release_afterDrain_doesNotReturnCheckedOutContainerToPool() {
        // drainFunction only stops containers sitting idle in the pool. A container checked out by
        // an in-flight invocation has been polled off the queue, so the drain cannot see it, and
        // release() then hands it back for a function that was deleted (and, on a redeploy,
        // recreated) — the next invoke reuses it and runs the OLD code and OLD baked env.
        // An ESM-driven function nearly always has one checked out, so the drain nearly always
        // misses; an idle redeploy drains cleanly, which is why this is invisible without
        // concurrent invocation.
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("drain-race-fn");

        ContainerHandle stale = new ContainerHandle("cid-stale", "drain-race-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-fresh", "drain-race-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(stale, fresh);
        lenient().when(containerLauncher.isAlive(any())).thenReturn(true);

        ContainerHandle inFlight = pool.acquire(fn);
        assertSame(stale, inFlight);

        // The function is deleted while that invocation is still running.
        pool.drainFunction("drain-race-fn");

        // The invocation finishes afterwards.
        pool.release(inFlight);

        // The drained container must be stopped rather than pooled...
        verify(containerLauncher).stop(stale);
        // ...so the next invoke cold-starts instead of serving stale code.
        assertNotSame(stale, pool.acquire(fn));
        verify(containerLauncher, times(2)).launch(any());

        pool.shutdown();
    }

    @Test
    void release_afterPushCodeUpdate_doesNotReturnCheckedOutContainerToPool() {
        // Same blind spot on the code-update path: pushCodeUpdate drains the pool to force a fresh
        // start on new code, but a container checked out mid-invocation is not in the pool, so
        // without this it is pooled again and keeps serving the pre-update code.
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("code-update-fn");

        ContainerHandle stale = new ContainerHandle("cid-old-code", "code-update-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-new-code", "code-update-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(stale, fresh);
        lenient().when(containerLauncher.isAlive(any())).thenReturn(true);

        ContainerHandle inFlight = pool.acquire(fn);
        pool.pushCodeUpdate(fn);
        pool.release(inFlight);

        verify(containerLauncher).stop(stale);
        assertNotSame(stale, pool.acquire(fn));

        pool.shutdown();
    }

    @Test
    void release_afterDrainDuringAcquire_doesNotReturnCheckedOutContainerToPool() {
        // The generation must be sampled before the container leaves the pool, not after. acquire
        // polls the handle off the queue and only then probes isAlive() — a Docker daemon
        // round-trip — or cold-starts a container, which is slower still. A drain landing in that
        // window bumps the generation, finds an empty queue and stops nothing, and the handle then
        // gets stamped with the POST-drain generation, so release pools it. Sampling after the
        // poll narrows the reported bug from "always" to "whenever a delete lands during a Docker
        // call", which a function under continuous ESM invocation reaches.
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction fn = mock(LambdaFunction.class);
        when(fn.getFunctionName()).thenReturn("acquire-race-fn");

        ContainerHandle stale = new ContainerHandle("cid-stale", "acquire-race-fn", null, ContainerState.WARM);
        ContainerHandle fresh = new ContainerHandle("cid-fresh", "acquire-race-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(stale, fresh);

        // Seed the pool, so the acquire under test takes the reuse path through isAlive().
        pool.release(pool.acquire(fn));

        // The function is deleted *inside* acquire: after pollFirst() has checked the handle out,
        // while the liveness probe is still in flight.
        when(containerLauncher.isAlive(any())).thenAnswer(invocation -> {
            pool.drainFunction("acquire-race-fn");
            return true;
        });

        ContainerHandle inFlight = pool.acquire(fn);
        assertSame(stale, inFlight);

        pool.release(inFlight);

        // Stamped with the generation in force when acquire started, so release sees it as stale.
        verify(containerLauncher).stop(stale);
        assertNotSame(stale, pool.acquire(fn));

        pool.shutdown();
    }

    @Test
    void release_withoutDrain_stillReusesContainerAcrossFunctions() {
        // Guard against over-correcting: a drain of one function must not invalidate another's
        // checked-out container.
        WarmPool pool = buildPool();
        pool.init();

        LambdaFunction kept = mock(LambdaFunction.class);
        when(kept.getFunctionName()).thenReturn("kept-fn");

        ContainerHandle keptHandle = new ContainerHandle("cid-kept", "kept-fn", null, ContainerState.WARM);
        when(containerLauncher.launch(any())).thenReturn(keptHandle);
        when(containerLauncher.isAlive(any())).thenReturn(true);

        ContainerHandle inFlight = pool.acquire(kept);
        pool.drainFunction("some-other-fn");
        pool.release(inFlight);

        assertSame(keptHandle, pool.acquire(kept));
        verify(containerLauncher, never()).stop(keptHandle);

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
}
