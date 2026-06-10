package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.InitLifecycleState;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHooksRunner;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.lambda.DynamoDbStreamsEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.KinesisEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.SqsEventSourcePoller;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock private EmulatorConfig.ServicesConfig servicesConfig;
    @Mock private EmulatorConfig.Ec2ServiceConfig ec2ServiceConfig;
    @Mock private ElastiCacheContainerManager elastiCacheContainerManager;
    @Mock private ElastiCacheMemcachedContainerManager elastiCacheMemcachedContainerManager;
    @Mock private ElastiCacheProxyManager elastiCacheProxyManager;
    @Mock private RdsContainerManager rdsContainerManager;
    @Mock private RdsProxyManager rdsProxyManager;
    @Mock private RdsService rdsService;
    @Mock private InitializationHooksRunner initializationHooksRunner;
    @Mock private SqsEventSourcePoller sqsPoller;
    @Mock private KinesisEventSourcePoller kinesisPoller;
    @Mock private DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    @Mock private PipesService pipesService;
    @Mock private Ec2MetadataServer ec2MetadataServer;
    @Mock private EcrRegistryManager ecrRegistryManager;
    @Mock private InitLifecycleState initLifecycleState;
    @Mock private EmulatorConfig.TlsConfig tlsConfig;

    private EmulatorLifecycle emulatorLifecycle;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(config.services()).thenReturn(servicesConfig);
        Mockito.lenient().when(servicesConfig.ec2()).thenReturn(ec2ServiceConfig);
        Mockito.lenient().when(ec2ServiceConfig.enabled()).thenReturn(false);
        Mockito.lenient().when(config.tls()).thenReturn(tlsConfig);
        Mockito.lenient().when(tlsConfig.enabled()).thenReturn(false);

        emulatorLifecycle = new EmulatorLifecycle(
                storageFactory, serviceRegistry, config,
                elastiCacheContainerManager, elastiCacheMemcachedContainerManager,
                elastiCacheProxyManager, rdsContainerManager, rdsProxyManager, rdsService,
                initializationHooksRunner, sqsPoller, kinesisPoller, dynamodbStreamsPoller,
                pipesService, ec2MetadataServer, ecrRegistryManager, initLifecycleState);
    }

    private void stubStorageConfig() {
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.mode()).thenReturn("in-memory");
        when(storageConfig.persistentPath()).thenReturn("/app/data");
    }

    @Test
    @DisplayName("Should run BOOT hooks before loading storage, then mark boot complete")
    void shouldRunBootHooksBeforeStorageLoad() throws IOException, InterruptedException {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class));

        var inOrder = Mockito.inOrder(initializationHooksRunner, storageFactory, initLifecycleState, rdsService);
        inOrder.verify(initializationHooksRunner).run(InitializationHook.BOOT);
        inOrder.verify(initLifecycleState).markBootCompleted();
        inOrder.verify(storageFactory).loadAll();
        inOrder.verify(rdsService).restorePersistedRuntime();
    }

    @Test
    @DisplayName("Should log Ready immediately when no startup or ready hooks exist")
    void shouldLogReadyImmediatelyWhenNoHooksExist() throws IOException, InterruptedException {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class));

        verify(storageFactory).loadAll();
        verify(initLifecycleState).markStartCompleted();
        verify(initLifecycleState).markReadyCompleted();
        verify(initializationHooksRunner, never()).run(InitializationHook.START);
    }

    @Test
    @DisplayName("Should defer hook execution when startup hooks exist")
    void shouldDeferHookExecutionWhenHooksExist() throws IOException, InterruptedException {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(true);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class));

        verify(storageFactory).loadAll();
        // run() is NOT called synchronously from onStart — it will be called by onHttpStart
        verify(initializationHooksRunner, never()).run(InitializationHook.START);
        verify(initLifecycleState, never()).markStartCompleted();
    }

    @Test
    @DisplayName("onHttpStart on port 4566 (non-TLS) triggers hook execution and marks lifecycle completed")
    void onHttpStart_nonTls_triggersHooksOnPort4566() throws IOException, InterruptedException {
        when(tlsConfig.enabled()).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(true);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        emulatorLifecycle.onHttpStart(new HttpServerStart(new HttpServerOptions().setPort(4566)));

        verify(initializationHooksRunner).run(InitializationHook.START);
        verify(initLifecycleState).markStartCompleted();
    }

    @Test
    @DisplayName("onHttpStart on wrong port (non-TLS) is ignored")
    void onHttpStart_nonTls_ignoresWrongPort() throws IOException, InterruptedException {
        when(tlsConfig.enabled()).thenReturn(false);

        emulatorLifecycle.onHttpStart(new HttpServerStart(new HttpServerOptions().setPort(4510)));

        verify(initializationHooksRunner, never()).run(InitializationHook.START);
        verify(initLifecycleState, never()).markStartCompleted();
    }

    @Test
    @DisplayName("onHttpStart on port 4510 (TLS mode) triggers hook execution and marks lifecycle completed")
    void onHttpStart_tls_triggersHooksOnBackendPort4510() throws IOException, InterruptedException {
        when(tlsConfig.enabled()).thenReturn(true);
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(true);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        emulatorLifecycle.onHttpStart(new HttpServerStart(new HttpServerOptions().setPort(4510)));

        verify(initializationHooksRunner).run(InitializationHook.START);
        verify(initLifecycleState).markStartCompleted();
    }

    @Test
    @DisplayName("onHttpStart on port 4566 (TLS mode) is ignored — public port is not the backend port")
    void onHttpStart_tls_ignoresPublicPort4566() throws IOException, InterruptedException {
        when(tlsConfig.enabled()).thenReturn(true);

        emulatorLifecycle.onHttpStart(new HttpServerStart(new HttpServerOptions().setPort(4566)));

        verify(initializationHooksRunner, never()).run(InitializationHook.START);
        verify(initLifecycleState, never()).markStartCompleted();
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

    // --- LocalStack-parity "Ready." log line ---

    /** Collects the messages EmulatorLifecycle logs while {@code action} runs. */
    private List<String> lifecycleLogMessages(Runnable action) {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(EmulatorLifecycle.class.getName());
        List<String> messages = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                messages.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return messages;
    }

    @Test
    @DisplayName("Should emit the LocalStack-parity \"Ready.\" line after the banner by default")
    void shouldEmitParityReadyLineByDefault() {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        List<String> messages =
                lifecycleLogMessages(() -> emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class)));

        assertEquals(1, messages.stream().filter("Ready."::equals).count(),
                "Exactly one parity \"Ready.\" line must be emitted");
        int banner = messages.indexOf("=== AWS Local Emulator Ready ===");
        assertTrue(banner >= 0, "The Floci ready banner must still be emitted");
        assertTrue(messages.indexOf("Ready.") > banner,
                "The parity line must follow the banner");
    }

    @Test
    @DisplayName("Should not emit the parity \"Ready.\" line when LOCALSTACK_PARITY=false")
    void shouldNotEmitParityReadyLineWhenParityDisabled() {
        stubStorageConfig();
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);
        emulatorLifecycle.localstackParity = "false";

        List<String> messages =
                lifecycleLogMessages(() -> emulatorLifecycle.onStart(Mockito.mock(StartupEvent.class)));

        assertFalse(messages.contains("Ready."),
                "No parity line may be emitted when parity is disabled");
        assertTrue(messages.contains("=== AWS Local Emulator Ready ==="),
                "The Floci ready banner must still be emitted");
    }

    @Test
    @DisplayName("Should emit the parity \"Ready.\" line on the deferred onHttpStart ready path too")
    void shouldEmitParityReadyLineOnHttpStartPath() throws IOException, InterruptedException {
        when(tlsConfig.enabled()).thenReturn(false);
        when(initializationHooksRunner.hasHooks(InitializationHook.START)).thenReturn(true);
        when(initializationHooksRunner.hasHooks(InitializationHook.READY)).thenReturn(false);

        List<String> messages = lifecycleLogMessages(() ->
                emulatorLifecycle.onHttpStart(new HttpServerStart(new HttpServerOptions().setPort(4566))));

        assertEquals(1, messages.stream().filter("Ready."::equals).count(),
                "Exactly one parity \"Ready.\" line must be emitted on the hook path");
    }
}
