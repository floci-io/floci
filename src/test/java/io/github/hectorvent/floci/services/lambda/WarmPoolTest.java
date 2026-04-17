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

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
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
    void shutdownHookRegisteredAfterInit() throws Exception {
        WarmPool pool = buildPool();
        pool.init();

        Field hookField = WarmPool.class.getDeclaredField("shutdownHook");
        hookField.setAccessible(true);
        Thread hook = (Thread) hookField.get(pool);

        assertNotNull(hook);
        pool.shutdown();
    }

    @Test
    void shutdownHookDrainsEmptyPool() throws Exception {
        WarmPool pool = buildPool();
        pool.init();

        Field hookField = WarmPool.class.getDeclaredField("shutdownHook");
        hookField.setAccessible(true);
        Thread hook = (Thread) hookField.get(pool);

        // Running the hook on an empty pool must not throw
        hook.run();

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
}
