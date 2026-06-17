package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHooksRunner;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.floci.ui.FlociUiManager;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import io.github.hectorvent.floci.services.lambda.DynamoDbStreamsEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.KinesisEventSourcePoller;
import io.github.hectorvent.floci.services.lambda.SqsEventSourcePoller;
import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.services.rds.RdsService;
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
    private static final int TLS_HTTP_BACKEND_PORT = 4510;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    Optional<String> appVersion = Optional.empty();

    /**
     * Bound to the LOCALSTACK_PARITY environment variable through the standard
     * MicroProfile Config env-var mapping. Same gate as docker/entrypoint.sh:
     * parity behavior is enabled unless the value is exactly "false".
     */
    @ConfigProperty(name = "localstack.parity", defaultValue = "true")
    String localstackParity = "true";

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ElastiCacheContainerManager elastiCacheContainerManager;
    private final ElastiCacheMemcachedContainerManager elastiCacheMemcachedContainerManager;
    private final ElastiCacheProxyManager elastiCacheProxyManager;
    private final RdsContainerManager rdsContainerManager;
    private final RdsProxyManager rdsProxyManager;
    private final RdsService rdsService;
    private final InitializationHooksRunner initializationHooksRunner;
    private final SqsEventSourcePoller sqsPoller;
    private final KinesisEventSourcePoller kinesisPoller;
    private final DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    private final PipesService pipesService;
    private final Ec2MetadataServer ec2MetadataServer;
    private final EcrRegistryManager ecrRegistryManager;
    private final FlociUiManager flociUiManager;
    private final InitLifecycleState initLifecycleState;

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config,
                             ElastiCacheContainerManager elastiCacheContainerManager,
                             ElastiCacheMemcachedContainerManager elastiCacheMemcachedContainerManager,
                             ElastiCacheProxyManager elastiCacheProxyManager,
                             RdsContainerManager rdsContainerManager,
                             RdsProxyManager rdsProxyManager,
                             RdsService rdsService,
                             InitializationHooksRunner initializationHooksRunner,
                             SqsEventSourcePoller sqsPoller,
                             KinesisEventSourcePoller kinesisPoller,
                             DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller,
                             PipesService pipesService,
                             Ec2MetadataServer ec2MetadataServer,
                             EcrRegistryManager ecrRegistryManager,
                             FlociUiManager flociUiManager,
                             InitLifecycleState initLifecycleState) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.elastiCacheContainerManager = elastiCacheContainerManager;
        this.elastiCacheMemcachedContainerManager = elastiCacheMemcachedContainerManager;
        this.elastiCacheProxyManager = elastiCacheProxyManager;
        this.rdsContainerManager = rdsContainerManager;
        this.rdsProxyManager = rdsProxyManager;
        this.rdsService = rdsService;
        this.initializationHooksRunner = initializationHooksRunner;
        this.sqsPoller = sqsPoller;
        this.kinesisPoller = kinesisPoller;
        this.dynamodbStreamsPoller = dynamodbStreamsPoller;
        this.pipesService = pipesService;
        this.ec2MetadataServer = ec2MetadataServer;
        this.ecrRegistryManager = ecrRegistryManager;
        this.flociUiManager = flociUiManager;
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
        rdsService.restorePersistedRuntime();

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
            logReady();
        }
    }

    void onHttpStart(@ObservesAsync HttpServerStart event) {
        int expectedPort = config.tls().enabled() ? TLS_HTTP_BACKEND_PORT : HTTP_PORT;
        if (event.options().getPort() != expectedPort) {
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
            logReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Startup hook execution interrupted — shutting down", e);
        } catch (Exception e) {
            LOG.error("Startup hook execution failed — shutting down", e);
            Quarkus.asyncExit();
        }
    }

    /**
     * LocalStack prints a line ending in "Ready." when its gateway is up, and
     * ecosystem tooling keys on it — Testcontainers' LocalStackContainer default
     * wait strategy polls the log for the regex {@code .*Ready\.} and times out
     * against Floci's banner alone. Emit the parity line alongside the banner so
     * such tooling works out of the box.
     */
    private void logReady() {
        LOG.info("=== AWS Local Emulator Ready ===");
        if (!"false".equals(localstackParity)) {
            LOG.info("Ready.");
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
        elastiCacheMemcachedContainerManager.stopAll();
        rdsContainerManager.stopAll();
        ecrRegistryManager.shutdown();
        flociUiManager.shutdown();
        storageFactory.shutdownAll();

        LOG.info("=== AWS Local Emulator Stopped ===");
    }
}
