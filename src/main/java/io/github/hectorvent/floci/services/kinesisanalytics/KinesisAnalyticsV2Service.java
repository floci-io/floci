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
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    // AWS: ApplicationName Length Constraints: 1-128, Pattern: [a-zA-Z0-9_.-]+
    private static final Pattern APPLICATION_NAME = Pattern.compile("[a-zA-Z0-9_.-]{1,128}");

    // AWS: "the maximum number of user-defined application tags is 50" (the stated 200-tag ceiling
    // on the Tags/TagKeys shapes includes AWS-managed system tags, which floci does not model).
    private static final int MAX_USER_TAGS = 50;

    // CreateApplicationPresignedUrl's SessionExpirationDurationInSeconds valid range (30 min - 12 hr).
    private static final long MIN_SESSION_EXPIRATION_SECONDS = 1800;
    private static final long MAX_SESSION_EXPIRATION_SECONDS = 43200;

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
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, null, null, null, 1);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties) {
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, environmentProperties, null);
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties,
                                              Boolean snapshotsEnabled) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ApplicationName is required", 400);
        }
        if (!APPLICATION_NAME.matcher(applicationName).matches()) {
            throw new AwsException("InvalidArgumentException",
                    "ApplicationName '" + applicationName
                            + "' does not match the required pattern [a-zA-Z0-9_.-]{1,128}", 400);
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
        if (tags != null && tags.size() > MAX_USER_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The maximum number of user-defined application tags is " + MAX_USER_TAGS, 400);
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
        app.setCodeS3Bucket(codeS3Bucket);
        app.setCodeS3Key(codeS3Key);
        app.setCodeS3ObjectVersion(codeS3ObjectVersion);
        app.setParallelism(parallelism < 1 ? 1 : parallelism);
        if (tags != null) {
            app.setTags(new LinkedHashMap<>(tags));
        }
        if (environmentProperties != null) {
            app.setEnvironmentProperties(new LinkedHashMap<>(environmentProperties));
        }
        if (snapshotsEnabled != null) {
            app.setSnapshotsEnabled(snapshotsEnabled);
        }

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
        // Defends state persisted by a floci build older than the ApplicationName check in
        // createApplication: applicationARN is baked as literal text into the CloudWatch-log-format
        // config FlinkContainerManager writes on startup, and a name outside AWS's charset (most
        // notably '$', which can't be escaped to a safe literal there -- see
        // FlinkContainerManager#literalForLog4j2Pattern) either gets dropped from every emitted
        // applicationARN (breaking correlation with the real ApplicationARN) or, if ever un-dropped,
        // risks leaking an environment variable/system property via a log4j2 Lookup. Failing loudly
        // here beats either outcome, and real AWS could never have had this state to begin with.
        if (!APPLICATION_NAME.matcher(applicationName).matches()) {
            throw new AwsException("InvalidArgumentException",
                    "ApplicationName '" + applicationName + "' does not match the required pattern "
                            + "[a-zA-Z0-9_.-]{1,128}; this application predates that validation and "
                            + "must be deleted and recreated with a valid name", 400);
        }
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
            } catch (AwsException e) {
                // Already an AWS-shaped client error (e.g. a missing/empty/unreadable application-code
                // S3 object from readJar); preserve its error code and message rather than masking it
                // as a 500.
                throw e;
            } catch (RuntimeException e) {
                // Unexpected provisioning failure (e.g. Docker unavailable). Keep the cause in the logs;
                // don't leak internal details into the AWS envelope. "InternalFailure" (no "Exception"
                // suffix, HTTP 500) is AWS's documented generic error common to every service's API
                // (e.g. https://docs.aws.amazon.com/comprehend/latest/APIReference/CommonErrors.html) —
                // it isn't declared per-operation in kinesisanalyticsv2's model (no operation here
                // declares any 5xx shape at all), so this isn't a code this specific API's model dictates,
                // it's the AWS-wide platform fallback, same as JsonErrorResponseUtils' own default.
                LOG.errorv(e, "Failed to start application {0}", applicationName);
                throw new AwsException("InternalFailure",
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
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                null, null, null, null);
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism) {
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                codeS3Bucket, codeS3Key, codeS3ObjectVersion, parallelism, null);
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism, Boolean snapshotsEnabled) {
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
        boolean codeChanged = codeS3Bucket != null && !codeS3Bucket.isBlank()
                && codeS3Key != null && !codeS3Key.isBlank();
        if (codeChanged) {
            app.setCodeS3Bucket(codeS3Bucket);
            app.setCodeS3Key(codeS3Key);
            app.setCodeS3ObjectVersion(codeS3ObjectVersion);
        }
        if (parallelism != null && parallelism > 0) {
            app.setParallelism(parallelism);
        }
        if (snapshotsEnabled != null) {
            app.setSnapshotsEnabled(snapshotsEnabled);
        }

        if (codeChanged && app.getApplicationStatus() == ApplicationStatus.RUNNING
                && !config.services().kinesisAnalytics().mock()) {
            if (app.getTaskManagerContainerId() == null) {
                // The application is running as a bare cluster (no code yet); attaching code to an
                // already-running cluster for the first time is not yet emulated.
                throw new AwsException("InvalidRequestException",
                        "Adding code to a running bare-cluster application is not supported; "
                                + "stop and start " + applicationName + " instead", 400);
            }
            try {
                // In-place redeploy: cancel the current job and swap in the new JAR without tearing
                // down the JobManager/TaskManager containers. The readiness poller resubmits it, the
                // same way it submits a fresh job on StartApplication.
                containerManager.redeployCode(app);
                app.setApplicationStatus(ApplicationStatus.STARTING);
            } catch (AwsException e) {
                throw e;
            } catch (RuntimeException e) {
                // Same AWS-wide "InternalFailure" generic 500 as startApplication's catch above.
                LOG.errorv(e, "Failed to redeploy code for application {0}", applicationName);
                throw new AwsException("InternalFailure",
                        "Failed to redeploy application code for " + applicationName, 500);
            }
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
            // Only on delete, never on a plain StopApplication (stopCluster above) — the savepoints
            // volume must survive a stop/restart cycle so snapshots remain describable/listable.
            containerManager.removeSavepointsVolume(app);
        }
        storage.delete(applicationName);
        LOG.infov("Deleted Kinesis Analytics V2 application {0}", applicationName);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return findByArn(resourceArn).getTags();
    }

    public Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
        FlinkApplication app = findByArn(resourceArn);
        Map<String, String> merged = new LinkedHashMap<>(app.getTags());
        if (tags != null) {
            merged.putAll(tags);
        }
        if (merged.size() > MAX_USER_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The maximum number of user-defined application tags is " + MAX_USER_TAGS, 400);
        }
        app.setTags(merged);
        putApplication(app);
        return app.getTags();
    }

    public Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        FlinkApplication app = findByArn(resourceArn);
        if (tagKeys != null) {
            tagKeys.forEach(app.getTags()::remove);
        }
        putApplication(app);
        return app.getTags();
    }

    public Snapshot createApplicationSnapshot(String applicationName, String snapshotName) {
        FlinkApplication app = describeApplication(applicationName);
        if (snapshotName == null || snapshotName.isBlank()) {
            throw new AwsException("InvalidArgumentException", "SnapshotName is required", 400);
        }
        // Real AWS requires a live, running job to snapshot — a bare cluster (no code) never has one.
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING || !app.hasCode()) {
            throw new AwsException("InvalidRequestException",
                    "Application " + applicationName + " must be RUNNING with a deployed job to "
                            + "create a snapshot", 400);
        }
        if (!app.isSnapshotsEnabled()) {
            throw new AwsException("InvalidRequestException",
                    "Snapshots are not enabled for application " + applicationName, 400);
        }
        if (app.getSnapshots().containsKey(snapshotName)) {
            throw new AwsException("ResourceInUseException",
                    "Snapshot already exists: " + snapshotName, 400);
        }

        Snapshot snapshot = new Snapshot(snapshotName, app.getApplicationVersionId(), app.getRuntimeEnvironment());
        if (config.services().kinesisAnalytics().mock()) {
            // No backing job to snapshot: come up READY immediately, same as StartApplication's
            // mock-mode shortcut.
            snapshot.setSnapshotStatus(SnapshotStatus.READY);
        } else {
            try {
                containerManager.createSnapshot(app, snapshot);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Failed to trigger snapshot {0} for application {1}",
                        snapshotName, applicationName);
                snapshot.setSnapshotStatus(SnapshotStatus.FAILED);
            }
        }
        app.getSnapshots().put(snapshotName, snapshot);
        putApplication(app);
        LOG.infov("Creating Kinesis Analytics V2 snapshot {0} for application {1}",
                snapshotName, applicationName);
        return snapshot;
    }

    public Snapshot describeApplicationSnapshot(String applicationName, String snapshotName) {
        FlinkApplication app = describeApplication(applicationName);
        Snapshot snapshot = app.getSnapshots().get(snapshotName);
        if (snapshot == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Snapshot not found: " + snapshotName, 400);
        }
        return snapshot;
    }

    public List<Snapshot> listApplicationSnapshots(String applicationName) {
        return List.copyOf(describeApplication(applicationName).getSnapshots().values());
    }

    public void deleteApplicationSnapshot(String applicationName, String snapshotName,
                                          Instant snapshotCreationTimestamp) {
        FlinkApplication app = describeApplication(applicationName);
        Snapshot snapshot = app.getSnapshots().get(snapshotName);
        if (snapshot == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Snapshot not found: " + snapshotName, 400);
        }
        if (snapshotCreationTimestamp == null || snapshot.getSnapshotCreationTimestamp() == null
                || snapshot.getSnapshotCreationTimestamp().getEpochSecond()
                        != snapshotCreationTimestamp.getEpochSecond()) {
            throw new AwsException("InvalidArgumentException",
                    "Provided SnapshotCreationTimestamp does not match snapshot " + snapshotName, 400);
        }
        if (snapshot.getSnapshotStatus() == SnapshotStatus.CREATING) {
            throw new AwsException("ResourceInUseException",
                    "Snapshot " + snapshotName + " cannot be deleted while still being created", 400);
        }
        if (!config.services().kinesisAnalytics().mock()) {
            containerManager.deleteSnapshotFiles(app, snapshot);
        }
        app.getSnapshots().remove(snapshotName);
        putApplication(app);
        LOG.infov("Deleted Kinesis Analytics V2 snapshot {0} for application {1}",
                snapshotName, applicationName);
    }

    /**
     * Real AWS returns a session-authorized URL to the application's Flink Dashboard (or, for a
     * Zeppelin Studio notebook, its UI — not supported here since floci already rejects Zeppelin
     * runtimes at CreateApplication). Since floci has no real IAM-backed session/proxy layer for this,
     * it returns the JobManager's own REST/dashboard URL directly rather than a genuinely time-limited,
     * signed one — the same "real behavior, stubbed authorization" tradeoff MWAA's CreateWebLoginToken
     * makes for its own SSO handshake.
     */
    public String createApplicationPresignedUrl(String applicationName, String urlType,
                                                 Long sessionExpirationDurationInSeconds) {
        FlinkApplication app = describeApplication(applicationName);
        if (!"FLINK_DASHBOARD_URL".equals(urlType)) {
            throw new AwsException("InvalidArgumentException",
                    "Unsupported UrlType: " + urlType + "; only FLINK_DASHBOARD_URL is supported", 400);
        }
        if (sessionExpirationDurationInSeconds != null
                && (sessionExpirationDurationInSeconds < MIN_SESSION_EXPIRATION_SECONDS
                        || sessionExpirationDurationInSeconds > MAX_SESSION_EXPIRATION_SECONDS)) {
            throw new AwsException("InvalidArgumentException",
                    "SessionExpirationDurationInSeconds must be between " + MIN_SESSION_EXPIRATION_SECONDS
                            + " and " + MAX_SESSION_EXPIRATION_SECONDS, 400);
        }
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING || app.getRestEndpoint() == null) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " is not available for this operation", 400);
        }
        return app.getRestEndpoint();
    }

    private FlinkApplication findByArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidArgumentException", "ResourceARN is required", 400);
        }
        // Resource format is "application/<name>"; application names never contain '/'.
        int slash = resourceArn.lastIndexOf('/');
        if (slash < 0 || slash == resourceArn.length() - 1) {
            throw new AwsException("InvalidArgumentException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String applicationName = resourceArn.substring(slash + 1);
        return storage.get(applicationName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No application found for ARN: " + resourceArn, 400));
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                if (config.services().kinesisAnalytics().mock()) {
                    return;
                }
                for (FlinkApplication app : allApplications()) {
                    boolean changed = false;
                    if (app.getApplicationStatus() == ApplicationStatus.STARTING) {
                        String previousJobId = app.getFlinkJobId();
                        if (containerManager.advanceToRunning(app)) {
                            LOG.infov("Kinesis Analytics V2 application {0} is now RUNNING",
                                    app.getApplicationName());
                            app.setApplicationStatus(ApplicationStatus.RUNNING);
                            app.setLastUpdateTimestamp(Instant.now());
                            changed = true;
                        } else if (!Objects.equals(previousJobId, app.getFlinkJobId())) {
                            // The Flink job was just submitted (flinkJobId newly assigned) but hasn't
                            // reached RUNNING yet. Persist now rather than waiting for that: pendingJars
                            // (an in-process-only cache) is already cleared at this point, so if the
                            // emulator restarts before the next RUNNING-triggered persist, an unpersisted
                            // flinkJobId would leave the application stuck — it could never re-fetch the
                            // JAR to resubmit, nor know a job was already running to poll instead.
                            changed = true;
                        }
                    }
                    for (Snapshot snapshot : app.getSnapshots().values()) {
                        if (snapshot.getSnapshotStatus() == SnapshotStatus.CREATING
                                && containerManager.advanceSnapshot(app, snapshot)) {
                            changed = true;
                        }
                    }
                    if (changed) {
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
