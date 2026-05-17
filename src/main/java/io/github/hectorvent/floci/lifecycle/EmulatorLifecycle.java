package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHooksRunner;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.lambda.DynamoDbStreamsEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.KinesisEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.SqsEventSourcePoller;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Optional;

@ApplicationScoped
public class EmulatorLifecycle {

    private static final Logger LOG = Logger.getLogger(EmulatorLifecycle.class);
    private static final int HTTP_PORT = 4566;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    Optional<String> appVersion = Optional.empty();

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ElastiCacheContainerManager elastiCacheContainerManager;
    private final ElastiCacheProxyManager elastiCacheProxyManager;
    private final RdsContainerManager rdsContainerManager;
    private final RdsProxyManager rdsProxyManager;
    private final InitializationHooksRunner initializationHooksRunner;
    private final SqsEventSourcePoller sqsPoller;
    private final KinesisEventSourcePoller kinesisPoller;
    private final DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    private final PipesService pipesService;
    private final Ec2MetadataServer ec2MetadataServer;
    private final InitLifecycleState initLifecycleState;

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config,
                             ElastiCacheContainerManager elastiCacheContainerManager,
                             ElastiCacheProxyManager elastiCacheProxyManager,
                             RdsContainerManager rdsContainerManager,
                             RdsProxyManager rdsProxyManager,
                             InitializationHooksRunner initializationHooksRunner,
                             SqsEventSourcePoller sqsPoller,
                             KinesisEventSourcePoller kinesisPoller,
                             DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller,
                             PipesService pipesService,
                             Ec2MetadataServer ec2MetadataServer,
                             InitLifecycleState initLifecycleState) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.elastiCacheContainerManager = elastiCacheContainerManager;
        this.elastiCacheProxyManager = elastiCacheProxyManager;
        this.rdsContainerManager = rdsContainerManager;
        this.rdsProxyManager = rdsProxyManager;
        this.initializationHooksRunner = initializationHooksRunner;
        this.sqsPoller = sqsPoller;
        this.kinesisPoller = kinesisPoller;
        this.dynamodbStreamsPoller = dynamodbStreamsPoller;
        this.pipesService = pipesService;
        this.ec2MetadataServer = ec2MetadataServer;
        this.initLifecycleState = initLifecycleState;
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.infof("=== AWS Local Emulator %s Starting ===", appVersion.orElse(""));
        LOG.infof("Endpoint:  http://0.0.0.0:%d", config.port());
        LOG.infof("Region:    %s  Account: %s", config.defaultRegion(), config.defaultAccountId());
        LOG.infov("Storage:   {0}  Path: {1}", config.storage().mode(), config.storage().persistentPath());
        LOG.infov("TLS:       {0}", config.tls().enabled() ? "enabled (HTTPS + HTTP dual mode)" : "disabled (HTTP only)");

        // BOOT hooks run before service initialization — scripts cannot use AWS APIs yet.
        try {
            initializationHooksRunner.run(InitializationHook.BOOT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Boot hook execution interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Boot hook execution failed", e);
        }
        initLifecycleState.markBootCompleted();

        serviceRegistry.logEnabledServices();
        storageFactory.loadAll();

        sqsPoller.startPersistedPollers();
        kinesisPoller.startPersistedPollers();
        dynamodbStreamsPoller.startPersistedPollers();
        pipesService.startPersistedPollers();

        if (config.services().ec2().enabled() && !config.services().ec2().mock()) {
            ec2MetadataServer.start().exceptionally(ex -> {
                LOG.warnv("EC2 IMDS server failed to start: {0}", ex.getMessage());
                return null;
            });
        }

        boolean hasStart = initializationHooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = initializationHooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            initLifecycleState.markStartCompleted();
            initLifecycleState.markReadyCompleted();
            LOG.info("=== AWS Local Emulator Ready ===");
        }
    }

    void onHttpStart(@ObservesAsync HttpServerStart event) {
        if (event.options().getPort() != HTTP_PORT) {
            return;
        }
        boolean hasStart = initializationHooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = initializationHooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            return;
        }
        try {
            if (hasStart) {
                initializationHooksRunner.run(InitializationHook.START);
            }
            initLifecycleState.markStartCompleted();
            if (hasReady) {
                initializationHooksRunner.run(InitializationHook.READY);
            }
            initLifecycleState.markReadyCompleted();
            LOG.info("=== AWS Local Emulator Ready ===");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Startup hook execution interrupted — shutting down", e);
        } catch (Exception e) {
            LOG.error("Startup hook execution failed — shutting down", e);
            Quarkus.asyncExit();
        }
    }

    void onPreShutdown(@Observes ShutdownDelayInitiatedEvent ignored) {
        LOG.info("=== AWS Local Emulator Shutting Down ===");
        initLifecycleState.markShutdownStarted();

        // Log-and-continue for every failure mode. Resource cleanup in onStop() must still run,
        // and cleanup routines (proxy/container/storage shutdown) must not see an interrupted
        // thread, so we intentionally do NOT restore the interrupt flag here.
        try {
            initializationHooksRunner.run(InitializationHook.STOP);
        } catch (InterruptedException e) {
            LOG.error("Shutdown hook execution interrupted", e);
        } catch (IOException e) {
            LOG.error("Shutdown hook execution failed", e);
        } catch (RuntimeException e) {
            LOG.error("Shutdown hook script failed", e);
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (config.services().ec2().enabled() && !config.services().ec2().mock()) {
            ec2MetadataServer.stop();
        }
        elastiCacheProxyManager.stopAll();
        rdsProxyManager.stopAll();
        elastiCacheContainerManager.stopAll();
        rdsContainerManager.stopAll();
        storageFactory.shutdownAll();

        LOG.info("=== AWS Local Emulator Stopped ===");
    }
}
