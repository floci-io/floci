package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
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
}
