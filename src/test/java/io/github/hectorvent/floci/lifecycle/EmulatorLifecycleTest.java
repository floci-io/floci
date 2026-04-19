package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHooksRunner;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmulatorLifecycleTest {

    @Mock private StorageFactory storageFactory;
    @Mock private ServiceRegistry serviceRegistry;
    @Mock private EmulatorConfig config;
    @Mock private EmulatorConfig.StorageConfig storageConfig;
    @Mock private ElastiCacheContainerManager elastiCacheContainerManager;
    @Mock private ElastiCacheProxyManager elastiCacheProxyManager;
    @Mock private RdsContainerManager rdsContainerManager;
    @Mock private RdsProxyManager rdsProxyManager;
    @Mock private InitializationHooksRunner initializationHooksRunner;

    private EmulatorLifecycle emulatorLifecycle;

    @BeforeEach
    void setUp() {
        emulatorLifecycle = new EmulatorLifecycle(
                storageFactory, serviceRegistry, config,
                elastiCacheContainerManager, elastiCacheProxyManager,
                rdsContainerManager, rdsProxyManager, initializationHooksRunner);
    }

    private void stubStorageConfig() {
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.mode()).thenReturn("in-memory");
        when(storageConfig.persistentPath()).thenReturn("/app/data");
    }

    @Test
    @DisplayName("Should log Ready immediately when no startup hooks exist")
    void shouldLogReadyImmediatelyWhenNoHooksExist() throws IOException, InterruptedException {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(false);

        emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class));

        verify(storageFactory).loadAll();
        verify(initializationHooksRunner).hasHooks(InitializationHook.START);
        verify(initializationHooksRunner, never()).run(InitializationHook.START);
    }

    @Test
    @DisplayName("Should defer hook execution when startup hooks exist")
    void shouldDeferHookExecutionWhenHooksExist() throws IOException, InterruptedException {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(true);

        emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class));

        verify(storageFactory).loadAll();
        verify(initializationHooksRunner).hasHooks(InitializationHook.START);
        // run() is NOT called synchronously — it will be called by the virtual thread
        verify(initializationHooksRunner, never()).run(InitializationHook.START);
    }

    @Test
    @DisplayName("Should run shutdown hooks in the pre-shutdown phase, before the HTTP server stops")
    void shouldRunShutdownHooksInPreShutdownPhase() throws IOException, InterruptedException {
        emulatorLifecycle.onPreShutdown(Mockito.mock(ShutdownDelayInitiatedEvent.class));

        verify(initializationHooksRunner).run(InitializationHook.STOP);
        // Resource cleanup must NOT happen in pre-shutdown; it belongs to ShutdownEvent.
        verify(storageFactory, never()).shutdownAll();
        verify(elastiCacheProxyManager, never()).stopAll();
        verify(rdsProxyManager, never()).stopAll();
    }

    @Test
    @DisplayName("Should swallow RuntimeException from shutdown hook scripts")
    void shouldSwallowRuntimeExceptionFromShutdownHook() throws IOException, InterruptedException {
        doThrow(new IllegalStateException("boom")).when(initializationHooksRunner).run(InitializationHook.STOP);

        emulatorLifecycle.onPreShutdown(Mockito.mock(ShutdownDelayInitiatedEvent.class));

        verify(initializationHooksRunner).run(InitializationHook.STOP);
    }

    @Test
    @DisplayName("Should swallow IOException from shutdown hook scripts so resource cleanup still runs")
    void shouldSwallowIOExceptionFromShutdownHook() throws IOException, InterruptedException {
        doThrow(new IOException("io")).when(initializationHooksRunner).run(InitializationHook.STOP);

        emulatorLifecycle.onPreShutdown(Mockito.mock(ShutdownDelayInitiatedEvent.class));

        verify(initializationHooksRunner).run(InitializationHook.STOP);
    }

    @Test
    @DisplayName("Should swallow InterruptedException from shutdown hooks without poisoning cleanup thread")
    void shouldSwallowInterruptedExceptionWithoutPropagatingInterrupt() throws IOException, InterruptedException {
        doThrow(new InterruptedException("interrupted")).when(initializationHooksRunner).run(InitializationHook.STOP);

        Thread.interrupted();
        try {
            emulatorLifecycle.onPreShutdown(Mockito.mock(ShutdownDelayInitiatedEvent.class));
            // The thread must NOT be left interrupted: ShutdownEvent cleanup runs next and
            // interruptible I/O inside stopAll()/shutdownAll() would short-circuit otherwise.
            org.junit.jupiter.api.Assertions.assertFalse(Thread.currentThread().isInterrupted(),
                    "Interrupt flag must not leak into ShutdownEvent cleanup");
        } finally {
            Thread.interrupted();
        }
        verify(initializationHooksRunner).run(InitializationHook.STOP);
    }

    @Test
    @DisplayName("Should clean up resources on ShutdownEvent without running hooks again")
    void shouldCleanUpResourcesOnShutdownWithoutRunningHooks() throws IOException, InterruptedException {
        emulatorLifecycle.onStop(Mockito.mock(ShutdownEvent.class));

        verify(elastiCacheProxyManager).stopAll();
        verify(rdsProxyManager).stopAll();
        verify(elastiCacheContainerManager).stopAll();
        verify(rdsContainerManager).stopAll();
        verify(storageFactory).shutdownAll();
        // Hooks are handled by onPreShutdown, never from ShutdownEvent.
        verify(initializationHooksRunner, never()).run(InitializationHook.STOP);
    }

    @Test
    @DisplayName("Should still run full resource cleanup when a pre-shutdown hook fails")
    void shouldRunFullCleanupAfterFailingPreShutdownHook() throws IOException, InterruptedException {
        doThrow(new IOException("hook blew up")).when(initializationHooksRunner).run(InitializationHook.STOP);

        emulatorLifecycle.onPreShutdown(Mockito.mock(ShutdownDelayInitiatedEvent.class));
        emulatorLifecycle.onStop(Mockito.mock(ShutdownEvent.class));

        verify(initializationHooksRunner).run(InitializationHook.STOP);
        verify(elastiCacheProxyManager).stopAll();
        verify(rdsProxyManager).stopAll();
        verify(elastiCacheContainerManager).stopAll();
        verify(rdsContainerManager).stopAll();
        verify(storageFactory).shutdownAll();
    }
}
