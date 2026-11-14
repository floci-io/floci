package io.floci.aws.lifecycle;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.ContainerTeardowns;
import io.floci.aws.core.common.ServiceRegistry;
import io.floci.aws.core.storage.PersistentPathValidator;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.lifecycle.inithook.InitializationHook;
import io.floci.aws.lifecycle.inithook.InitializationHooksRunner;
import io.floci.aws.services.amazonmq.container.RabbitMqManager;
import io.floci.aws.services.appsync.graphql.SchemaCreationWorker;
import io.floci.aws.services.docdb.container.DocDbContainerManager;
import io.floci.aws.services.dynamodb.DynamoDbRuntime;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentialsServer;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.elasticache.ElastiCacheMemcachedService;
import io.floci.aws.services.elasticache.ElastiCacheService;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.floci.aws.services.elasticache.proxy.ElastiCacheProxyManager;
import io.floci.aws.services.elb.ElbClassicService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.floci.ui.FlociUiManager;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kinesisanalytics.container.FlinkContainerManager;
import io.floci.aws.services.lambda.DynamoDbStreamsEventSourcePoller;
import io.floci.aws.services.lambda.KinesisEventSourcePoller;
import io.floci.aws.services.lambda.SqsEventSourcePoller;
import io.floci.aws.services.lambda.durable.DurableExecutionService;
import io.floci.aws.services.memorydb.MemoryDbService;
import io.floci.aws.services.memorydb.container.MemoryDbContainerManager;
import io.floci.aws.services.memorydb.proxy.MemoryDbProxyManager;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
import io.floci.aws.services.pipes.PipesService;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.proxy.RdsProxyManager;
import io.floci.aws.services.redshift.RedshiftDynamoDbZeroEtlConsumer;
import io.floci.aws.services.stepfunctions.StepFunctionsService;
import io.floci.aws.services.timestreaminfluxdb.TimestreamInfluxDbService;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class EmulatorLifecycle {

    private static final Logger LOG = Logger.getLogger(EmulatorLifecycle.class);

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    Optional<String> appVersion = Optional.empty();

    /**
     * Bound to the LOCALSTACK_PARITY environment variable through the standard
     * MicroProfile Config env-var mapping. Same gate as docker/entrypoint.sh:
     * parity behavior is enabled unless the value is exactly "false".
     */
    @ConfigProperty(name = "localstack.parity", defaultValue = "true")
    String localstackParity = "true";

    /** The port Quarkus HTTP listens on; under TLS, TlsConfigSource pins it to the internal backend. */
    @ConfigProperty(name = "quarkus.http.port")
    int httpPort = 4566;

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final IamService iamService;
    private final ElastiCacheService elastiCacheService;
    private final ElastiCacheMemcachedService elastiCacheMemcachedService;
    private final ElastiCacheContainerManager elastiCacheContainerManager;
    private final ElastiCacheMemcachedContainerManager elastiCacheMemcachedContainerManager;
    private final ElastiCacheProxyManager elastiCacheProxyManager;
    private final RdsContainerManager rdsContainerManager;
    private final RdsProxyManager rdsProxyManager;
    private final MemoryDbContainerManager memoryDbContainerManager;
    private final MemoryDbProxyManager memoryDbProxyManager;
    private final MemoryDbService memoryDbService;
    private final DocDbContainerManager docDbContainerManager;
    private final NeptuneContainerManager neptuneContainerManager;
    private final NeptuneProxyManager neptuneProxyManager;
    private final RabbitMqManager rabbitMqManager;
    private final FlinkContainerManager flinkContainerManager;
    private final RdsService rdsService;
    private final TimestreamInfluxDbService timestreamInfluxDbService;
    private final ElbV2Service elbV2Service;
    private final EcsService ecsService;
    private final ElbClassicService elbClassicService;
    private final InitializationHooksRunner initializationHooksRunner;
    private final SqsEventSourcePoller sqsPoller;
    private final KinesisEventSourcePoller kinesisPoller;
    private final DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller;
    private final PipesService pipesService;
    private final Ec2MetadataServer ec2MetadataServer;
    private final EcsTaskRoleCredentialsServer ecsTaskRoleCredentialsServer;
    private final EcrRegistryManager ecrRegistryManager;
    private final FlociUiManager flociUiManager;
    private final InitLifecycleState initLifecycleState;
    private final SchemaCreationWorker schemaCreationWorker;
    private final StepFunctionsService stepFunctionsService;
    private final DurableExecutionService durableExecutionService;
    private final Instance<ContainerTeardown> containerTeardowns;
    private final PersistentPathValidator persistentPathValidator;
    private final EksService eksService;
    private final DynamoDbRuntime dynamoDbRuntime;
    private final RedshiftDynamoDbZeroEtlConsumer redshiftZeroEtlConsumer;
    // Set on the boot thread, read by the async HttpServerStart observer.
    private volatile CompletableFuture<Void> metadataServerStarted = CompletableFuture.completedFuture(null);

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config,
                             IamService iamService,
                             ElastiCacheService elastiCacheService,
                             ElastiCacheMemcachedService elastiCacheMemcachedService,
                             ElastiCacheContainerManager elastiCacheContainerManager,
                             ElastiCacheMemcachedContainerManager elastiCacheMemcachedContainerManager,
                             ElastiCacheProxyManager elastiCacheProxyManager,
                             RdsContainerManager rdsContainerManager,
                             RdsProxyManager rdsProxyManager,
                             MemoryDbContainerManager memoryDbContainerManager,
                             MemoryDbProxyManager memoryDbProxyManager,
                             MemoryDbService memoryDbService,
                             DocDbContainerManager docDbContainerManager,
                             NeptuneContainerManager neptuneContainerManager,
                             NeptuneProxyManager neptuneProxyManager,
                             RabbitMqManager rabbitMqManager,
                             FlinkContainerManager flinkContainerManager,
                             RdsService rdsService,
                             TimestreamInfluxDbService timestreamInfluxDbService,
                             ElbV2Service elbV2Service,
                             ElbClassicService elbClassicService,
                             EcsService ecsService,
                             InitializationHooksRunner initializationHooksRunner,
                             SqsEventSourcePoller sqsPoller,
                             KinesisEventSourcePoller kinesisPoller,
                             DynamoDbStreamsEventSourcePoller dynamodbStreamsPoller,
                             PipesService pipesService,
                             Ec2MetadataServer ec2MetadataServer,
                             EcsTaskRoleCredentialsServer ecsTaskRoleCredentialsServer,
                             EcrRegistryManager ecrRegistryManager,
                             FlociUiManager flociUiManager,
                             InitLifecycleState initLifecycleState,
                             SchemaCreationWorker schemaCreationWorker,
                             StepFunctionsService stepFunctionsService,
                             DurableExecutionService durableExecutionService,
                             Instance<ContainerTeardown> containerTeardowns,
                             PersistentPathValidator persistentPathValidator,
                             DynamoDbRuntime dynamoDbRuntime,
                             RedshiftDynamoDbZeroEtlConsumer redshiftZeroEtlConsumer,
                             EksService eksService) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.iamService = iamService;
        this.elastiCacheService = elastiCacheService;
        this.elastiCacheMemcachedService = elastiCacheMemcachedService;
        this.elastiCacheContainerManager = elastiCacheContainerManager;
        this.elastiCacheMemcachedContainerManager = elastiCacheMemcachedContainerManager;
        this.elastiCacheProxyManager = elastiCacheProxyManager;
        this.rdsContainerManager = rdsContainerManager;
        this.rdsProxyManager = rdsProxyManager;
        this.memoryDbContainerManager = memoryDbContainerManager;
        this.memoryDbProxyManager = memoryDbProxyManager;
        this.memoryDbService = memoryDbService;
        this.docDbContainerManager = docDbContainerManager;
        this.neptuneContainerManager = neptuneContainerManager;
        this.neptuneProxyManager = neptuneProxyManager;
        this.rabbitMqManager = rabbitMqManager;
        this.flinkContainerManager = flinkContainerManager;
        this.rdsService = rdsService;
        this.timestreamInfluxDbService = timestreamInfluxDbService;
        this.elbV2Service = elbV2Service;
        this.elbClassicService = elbClassicService;
        this.ecsService = ecsService;
        this.initializationHooksRunner = initializationHooksRunner;
        this.sqsPoller = sqsPoller;
        this.kinesisPoller = kinesisPoller;
        this.dynamodbStreamsPoller = dynamodbStreamsPoller;
        this.pipesService = pipesService;
        this.ec2MetadataServer = ec2MetadataServer;
        this.ecsTaskRoleCredentialsServer = ecsTaskRoleCredentialsServer;
        this.ecrRegistryManager = ecrRegistryManager;
        this.flociUiManager = flociUiManager;
        this.initLifecycleState = initLifecycleState;
        this.schemaCreationWorker = schemaCreationWorker;
        this.stepFunctionsService = stepFunctionsService;
        this.durableExecutionService = durableExecutionService;
        this.containerTeardowns = containerTeardowns;
        this.persistentPathValidator = persistentPathValidator;
        this.eksService = eksService;
        this.dynamoDbRuntime = dynamoDbRuntime;
        this.redshiftZeroEtlConsumer = redshiftZeroEtlConsumer;
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.infof("=== AWS Local Emulator %s Starting ===", appVersion.orElse(""));
        LOG.infof("Endpoint:  http://0.0.0.0:%d", config.port());
        LOG.infof("Region:    %s  Account: %s", config.defaultRegion(), config.defaultAccountId());
        LOG.infov("Storage:   {0}  Path: {1}", config.storage().mode(), config.storage().persistentPath());
        LOG.infov("TLS:       {0}", config.tls().enabled() ? "enabled (HTTPS + HTTP dual mode)" : "disabled (HTTP only)");
        // Surfaced in the banner because a mistyped flag name (issue #3931) silently leaves
        // enforcement off, and nothing else in the logs reveals that policies are not evaluated.
        LOG.infov("IAM:       {0}", config.services().iam().enforcementEnabled()
                ? "policy enforcement enabled"
                : "policy enforcement disabled (set FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to enforce)");

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

        persistentPathValidator.validateAtBoot();

        serviceRegistry.logEnabledServices();
        storageFactory.loadAll();
        int sweptSessions = iamService.sweepOrphanedLambdaExecutionRoleSessions();
        if (sweptSessions > 0) {
            LOG.infov("Removed {0} orphaned Lambda execution-role session(s)", sweptSessions);
        }
        int sweptEc2Sessions = iamService.sweepOrphanedEc2InstanceSessions();
        if (sweptEc2Sessions > 0) {
            LOG.infov("Removed {0} orphaned EC2 instance session(s)", sweptEc2Sessions);
        }
        int sweptEcsTaskRoleSessions = iamService.sweepOrphanedEcsTaskRoleSessions();
        if (sweptEcsTaskRoleSessions > 0) {
            LOG.infov("Removed {0} orphaned ECS task-role session(s)", sweptEcsTaskRoleSessions);
        }
        schemaCreationWorker.recoverOrphans();
        schemaCreationWorker.rehydrateSchemas();
        stepFunctionsService.abortAbandonedExecutions();
        if (config.services().lambda().enabled()) {
            durableExecutionService.recoverAfterRestart();
        }

        // The selected DynamoDB backend is ready before any persisted stream consumer reads it.
        dynamoDbRuntime.start();
        sqsPoller.startPersistedPollers();
        kinesisPoller.startPersistedPollers();
        dynamodbStreamsPoller.startPersistedPollers();
        pipesService.startPersistedPollers();
        redshiftZeroEtlConsumer.startPersistedIntegrations();
        rdsService.restorePersistedRuntime();
        if (config.services().memorydb().enabled()) {
            memoryDbService.restorePersistedRuntime().exceptionally(ex -> {
                LOG.warnv(ex, "MemoryDB runtime restoration failed");
                return null;
            });
        }
        if (config.services().timestreamInfluxdb().enabled()) {
            timestreamInfluxDbService.restorePersistedRuntime();
        }
        if (config.services().elasticache().enabled()) {
            elastiCacheService.restorePersistedRuntime().exceptionally(ex -> {
                LOG.warnv("ElastiCache replication group restore failed: {0}", ex.getMessage());
                return null;
            });
            elastiCacheMemcachedService.restorePersistedRuntime().exceptionally(ex -> {
                LOG.warnv("ElastiCache Memcached restore failed: {0}", ex.getMessage());
                return null;
            });
        }
        if (config.services().elbv2().enabled()) {
            elbV2Service.restorePersistedRuntime();
        }
        if (config.services().elb().enabled()) {
            elbClassicService.restorePersistedRuntime();
        }
        // After the load balancers: the reconciler registers the tasks it starts as their targets.
        if (config.services().ecs().enabled()) {
            ecsService.restorePersistedRuntime();
        }

        if (isMetadataServerNeeded()) {
            metadataServerStarted = ec2MetadataServer.start().exceptionally(ex -> {
                LOG.warnv("EC2 IMDS server failed to start: {0}", ex.getMessage());
                return null;
            });
        }
        if (config.services().ecs().taskRoleCredentials().enabled()) {
            ecsTaskRoleCredentialsServer.start().exceptionally(ex -> {
                LOG.warnv("ECS task-role credentials server failed to start: {0}", ex.getMessage());
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
        // Compare against quarkus.http.port, not floci.port: it can be overridden alone, and
        // under TLS it is the internal backend while TlsProxyServer serves floci.port. A
        // hardcoded 4566 here made start/ready hooks never run on a non-default port (#2437).
        if (event.options().getPort() != httpPort) {
            return;
        }
        boolean hasStart = initializationHooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = initializationHooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            restoreEksClusters();
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
            restoreEksClusters();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Startup hook execution interrupted — shutting down", e);
            Quarkus.asyncExit();
        } catch (Exception e) {
            LOG.error("Startup hook execution failed — shutting down", e);
            Quarkus.asyncExit();
        }
    }

    /**
     * Creating EksService restarts persisted clusters; without this call that waited for the first EKS request.
     * It runs once Floci's HTTP port and IMDS server listen, because a recreated cluster's Pod Identity and IMDS
     * relays probe them before they route pod traffic there, and after the start and ready hooks, which it
     * could otherwise hold up for as long as the clusters take to start.
     */
    private void restoreEksClusters() {
        if (!config.services().eks().enabled() || config.services().eks().mock()) {
            return;
        }
        try {
            metadataServerStarted.join();
            eksService.restorePersistedRuntime();
        } catch (RuntimeException e) {
            // EKS requests retry the bean's creation, as before; the rest of the emulator still starts.
            LOG.error("Failed to restore persisted EKS clusters at startup", e);
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
        // Flush persisted state to disk FIRST, before the slow proxy/container teardown below.
        // Stopping Docker sidecars (RDS/ElastiCache/etc.) can block long enough to exhaust the
        // SIGTERM grace window and trigger SIGKILL; if the flush ran last it would be skipped and
        // in-memory (hybrid) data would be lost on an otherwise-graceful shutdown. shutdownAll()
        // still runs at the end to stop the flush schedulers and capture any shutdown-time writes.
        // Stream consumers stop first, so the flush holds their final checkpoints.
        runCleanup("DynamoDB Streams poller", dynamodbStreamsPoller::shutdown);
        runCleanup("Kinesis poller", kinesisPoller::shutdown);
        runCleanup("Redshift zero-ETL consumer", redshiftZeroEtlConsumer::shutdown);
        runCleanup("storage flush", storageFactory::flushAll);
        List<ContainerTeardown> prepared = ContainerTeardowns.prepareAll(containerTeardowns, LOG);
        runCleanup("EC2 metadata server", () -> {
            if (isMetadataServerNeeded()) {
                ec2MetadataServer.stop();
            }
        });
        runCleanup("ECS task-role credentials server", () -> {
            if (config.services().ecs().taskRoleCredentials().enabled()) {
                ecsTaskRoleCredentialsServer.stop();
            }
        });
        runCleanup("ElastiCache proxy", elastiCacheProxyManager::stopAll);
        runCleanup("RDS proxy", rdsProxyManager::stopAll);
        runCleanup("MemoryDB proxy", memoryDbProxyManager::stopAll);
        runCleanup("Neptune proxy", neptuneProxyManager::stopAll);
        runCleanup("ElastiCache container", elastiCacheContainerManager::stopAll);
        runCleanup("ElastiCache Memcached container", elastiCacheMemcachedContainerManager::stopAll);
        runCleanup("RDS container", rdsContainerManager::stopAll);
        runCleanup("MemoryDB container", memoryDbContainerManager::stopAll);
        runCleanup("DocDB container", docDbContainerManager::stopAll);
        runCleanup("Neptune container", neptuneContainerManager::stopAll);
        runCleanup("RabbitMQ", rabbitMqManager::stopAll);
        runCleanup("Flink", flinkContainerManager::stopAll);
        runCleanup("ECR registry", ecrRegistryManager::shutdown);
        runCleanup("Floci UI", flociUiManager::shutdown);
        // Centralized teardown for process-bound containers (Lambda warm pool, ECS tasks,
        // EC2 instances, in-flight build/job containers). Runs before shutdownAll() so any
        // state written while stopping is captured by the final flush.
        ContainerTeardowns.stopPrepared(prepared, LOG);
        runCleanup("DynamoDB backend", dynamoDbRuntime::stop);
        runCleanup("storage shutdown", storageFactory::shutdownAll);

        LOG.info("=== AWS Local Emulator Stopped ===");
    }

    private void runCleanup(String resource, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Shutdown cleanup failed for {0}; continuing with the remaining steps", resource);
        }
    }

    private boolean isMetadataServerNeeded() {
        if (config == null || config.services() == null) {
            return false;
        }
        EmulatorConfig.Ec2ServiceConfig ec2 = config.services().ec2();
        if (ec2 != null && ec2.enabled() && !ec2.mock()) {
            return true;
        }
        EmulatorConfig.EksServiceConfig eks = config.services().eks();
        return eks != null && eks.enabled() && !eks.mock() && eks.imds();
    }
}
