package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Control plane for Managed Service for Apache Flink (Kinesis Analytics V2). Holds the
 * application state machine and delegates the real Flink cluster to {@link FlinkContainerManager}.
 *
 * <p>Modelled on {@code AmazonMqService}: {@code CreateApplication} lands in {@code READY} with no
 * container (AWS-faithful — a created application is not running); {@code StartApplication} spins up
 * a Flink JobManager container and the readiness poller flips {@code STARTING → RUNNING} once the
 * JobManager REST API answers; {@code StopApplication} tears the container down and returns to
 * {@code READY}.
 */
@ApplicationScoped
public class KinesisAnalyticsV2Service {

    private static final Logger LOG = Logger.getLogger(KinesisAnalyticsV2Service.class);

    private final StorageBackend<String, FlinkApplication> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final FlinkContainerManager containerManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public KinesisAnalyticsV2Service(StorageFactory storageFactory, EmulatorConfig config,
                                     RegionResolver regionResolver,
                                     FlinkContainerManager containerManager) {
        this.storage = storageFactory.create("kinesisanalytics", "kinesisanalytics-applications.json",
                new TypeReference<Map<String, FlinkApplication>>() {});
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
    }

    @PostConstruct
    public void init() {
        startReadinessPoller();
    }

    @PreDestroy
    public void shutdown() {
        // Container teardown is wired into EmulatorLifecycle.onStop() via
        // FlinkContainerManager.stopAll() (ordered with the other container managers);
        // here we only stop the readiness poller. Wait briefly for an in-flight tick so a
        // mid-flight putApplication cannot race the storage flush in onStop().
        poller.shutdown();
        try {
            if (!poller.awaitTermination(5, TimeUnit.SECONDS)) {
                poller.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            poller.shutdownNow();
        }
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ApplicationName is required", 400);
        }
        if (runtimeEnvironment == null || runtimeEnvironment.isBlank()) {
            throw new AwsException("InvalidArgumentException", "RuntimeEnvironment is required", 400);
        }
        // Reject a runtime we cannot back with a Flink image up front (AWS-faithful
        // InvalidArgumentException), rather than failing later at StartApplication.
        KinesisAnalyticsRuntimes.validate(runtimeEnvironment);
        if (serviceExecutionRole == null || serviceExecutionRole.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ServiceExecutionRole is required", 400);
        }
        if (storage.get(applicationName).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Application already exists: " + applicationName, 400);
        }

        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("kinesisanalytics", config.defaultRegion(), accountId,
                "application/" + applicationName).toString();
        String mode = (applicationMode == null || applicationMode.isBlank()) ? "STREAMING" : applicationMode;

        FlinkApplication app = new FlinkApplication(applicationName, arn, runtimeEnvironment,
                serviceExecutionRole, mode);
        app.setAccountId(accountId);
        app.setApplicationDescription(applicationDescription);
        // AWS-faithful: a freshly created application is READY (not RUNNING); no container yet.
        app.setApplicationStatus(ApplicationStatus.READY);

        storage.put(applicationName, app);
        LOG.infov("Created Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication describeApplication(String applicationName) {
        return storage.get(applicationName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Application not found: " + applicationName, 400));
    }

    public List<FlinkApplication> listApplications() {
        return storage.scan(k -> true);
    }

    public FlinkApplication startApplication(String applicationName) {
        FlinkApplication app = describeApplication(applicationName);
        if (app.getApplicationStatus() != ApplicationStatus.READY) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be started while in state "
                            + app.getApplicationStatus() + "; it must be READY", 400);
        }

        if (config.services().kinesisAnalytics().mock()) {
            // No backing container: come up immediately.
            app.setApplicationStatus(ApplicationStatus.RUNNING);
        } else {
            try {
                // Start the container; the application stays STARTING until the readiness poller
                // observes the JobManager REST API answering.
                containerManager.startCluster(app);
                app.setApplicationStatus(ApplicationStatus.STARTING);
            } catch (RuntimeException e) {
                // Keep the cause in the logs; don't leak internal details into the AWS envelope.
                LOG.errorv(e, "Failed to start application {0}", applicationName);
                throw new AwsException("InternalFailureException",
                        "Failed to start application " + applicationName, 500);
            }
        }
        app.setLastUpdateTimestamp(Instant.now());
        putApplication(app);
        LOG.infov("Starting Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication stopApplication(String applicationName) {
        FlinkApplication app = describeApplication(applicationName);
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING
                && app.getApplicationStatus() != ApplicationStatus.STARTING) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be stopped while in state "
                            + app.getApplicationStatus() + "; it must be RUNNING or STARTING", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            containerManager.stopCluster(app);
        }
        // Stopping the JobManager is synchronous, so the application returns to READY directly.
        app.setApplicationStatus(ApplicationStatus.READY);
        app.setLastUpdateTimestamp(Instant.now());
        putApplication(app);
        LOG.infov("Stopped Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole) {
        FlinkApplication app = describeApplication(applicationName);
        // AWS requires CurrentApplicationVersionId and rejects a stale value with
        // ConcurrentModificationException (optimistic concurrency on the application version).
        if (currentApplicationVersionId == null) {
            throw new AwsException("InvalidArgumentException",
                    "CurrentApplicationVersionId is required", 400);
        }
        if (currentApplicationVersionId != app.getApplicationVersionId()) {
            throw new AwsException("ConcurrentModificationException",
                    "Provided CurrentApplicationVersionId " + currentApplicationVersionId
                            + " does not match the current version " + app.getApplicationVersionId(), 400);
        }
        if (serviceExecutionRole != null && !serviceExecutionRole.isBlank()) {
            app.setServiceExecutionRole(serviceExecutionRole);
        }
        app.setApplicationVersionId(app.getApplicationVersionId() + 1);
        app.setLastUpdateTimestamp(Instant.now());
        putApplication(app);
        LOG.infov("Updated Kinesis Analytics V2 application {0} to version {1}",
                applicationName, app.getApplicationVersionId());
        return app;
    }

    public void deleteApplication(String applicationName, Instant createTimestamp) {
        FlinkApplication app = describeApplication(applicationName);
        // AWS requires CreateTimestamp and rejects a value that does not match the stored one. The
        // wire value is epoch seconds, so compare at second granularity.
        if (createTimestamp == null) {
            throw new AwsException("InvalidArgumentException", "CreateTimestamp is required", 400);
        }
        if (app.getCreateTimestamp() == null
                || app.getCreateTimestamp().getEpochSecond() != createTimestamp.getEpochSecond()) {
            throw new AwsException("InvalidArgumentException",
                    "Provided CreateTimestamp does not match application " + applicationName, 400);
        }
        // AWS rejects deletion of a non-stopped application (RUNNING/STARTING) with
        // ResourceInUseException; the caller must StopApplication first.
        if (app.getApplicationStatus() != ApplicationStatus.READY) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be deleted while in state "
                            + app.getApplicationStatus() + "; stop the application first", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            // No-op for a READY app with no container; also clears any stale container left from a
            // previous run (containerId is not persisted across an emulator restart).
            containerManager.stopCluster(app);
        }
        storage.delete(applicationName);
        LOG.infov("Deleted Kinesis Analytics V2 application {0}", applicationName);
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                if (config.services().kinesisAnalytics().mock()) {
                    return;
                }
                for (FlinkApplication app : allApplications()) {
                    if (app.getApplicationStatus() == ApplicationStatus.STARTING
                            && containerManager.isReady(app)) {
                        LOG.infov("Kinesis Analytics V2 application {0} is now RUNNING",
                                app.getApplicationName());
                        app.setApplicationStatus(ApplicationStatus.RUNNING);
                        app.setLastUpdateTimestamp(Instant.now());
                        putApplication(app);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in Kinesis Analytics V2 readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    private List<FlinkApplication> allApplications() {
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putApplication(FlinkApplication app) {
        if (app.getAccountId() != null && storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            aware.putForAccount(app.getAccountId(), app.getApplicationName(), app);
        } else {
            storage.put(app.getApplicationName(), app);
        }
    }
}
