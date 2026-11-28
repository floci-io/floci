package io.floci.aws.services.kinesisanalytics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kinesisanalytics.container.FlinkContainerManager;
import io.floci.aws.services.kinesisanalytics.model.ApplicationStatus;
import io.floci.aws.services.kinesisanalytics.model.FlinkApplication;
import io.floci.aws.services.kinesisanalytics.model.Snapshot;
import io.floci.aws.services.kinesisanalytics.model.SnapshotStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
public class KinesisAnalyticsV2Service implements StoredResourceArns {

    private static final Logger LOG = Logger.getLogger(KinesisAnalyticsV2Service.class);

    // AWS: ApplicationName Length Constraints: 1-128, Pattern: [a-zA-Z0-9_.-]+
    private static final Pattern APPLICATION_NAME = Pattern.compile("[a-zA-Z0-9_.-]{1,128}");

    // AWS: "the maximum number of user-defined application tags is 50" (the stated 200-tag ceiling
    // on the Tags/TagKeys shapes includes AWS-managed system tags, which floci does not model).
    private static final int MAX_USER_TAGS = 50;

    /** What an application's ARN resource starts with, its name following. */
    private static final String APPLICATION_RESOURCE = "application/";

    // CreateApplicationPresignedUrl's SessionExpirationDurationInSeconds valid range (30 min - 12 hr).
    private static final long MIN_SESSION_EXPIRATION_SECONDS = 1800;
    private static final long MAX_SESSION_EXPIRATION_SECONDS = 43200;

    private final StorageBackend<String, FlinkApplication> storage;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final FlinkContainerManager containerManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    /**
     * The applications a restore is bringing back, by {@code account:key}, which the readiness poller leaves alone:
     * their records still name the old cluster until the restore keeps it or starts a new one. In memory only, so
     * after a crash the record keeps what the next start probes a surviving cluster on.
     */
    private final Set<String> restoringApplications = ConcurrentHashMap.newKeySet();
    /**
     * Monitors over one stored application, by {@code account:key}. The restore reads an application again, and ends
     * its restore, under its lock, under which {@code StopApplication} finds whether a restore is starting the
     * application's cluster. If one is, the stop leaves the application {@code STOPPING} for the restore to finish,
     * so the restore never undoes a stop, and no other start or stop runs on the cluster it is starting.
     */
    private final StripedLocks storedApplicationLocks = new StripedLocks(256);

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
        return createApplication(applicationName, runtimeEnvironment, serviceExecutionRole,
                applicationDescription, applicationMode, codeS3Bucket, codeS3Key, codeS3ObjectVersion,
                parallelism, tags, environmentProperties, snapshotsEnabled, config.defaultRegion());
    }

    public FlinkApplication createApplication(String applicationName, String runtimeEnvironment,
                                              String serviceExecutionRole, String applicationDescription,
                                              String applicationMode, String codeS3Bucket, String codeS3Key,
                                              String codeS3ObjectVersion, int parallelism,
                                              Map<String, String> tags,
                                              Map<String, Map<String, String>> environmentProperties,
                                              Boolean snapshotsEnabled, String region) {
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
        if (storage.get(applicationKey(region, applicationName)).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Application already exists: " + applicationName, 400);
        }
        if (tags != null && tags.size() > MAX_USER_TAGS) {
            throw new AwsException("TooManyTagsException",
                    "The maximum number of user-defined application tags is " + MAX_USER_TAGS, 400);
        }

        String accountId = regionResolver.getAccountId();
        String arn = AwsArnUtils.Arn.of("kinesisanalytics", region, accountId,
                APPLICATION_RESOURCE + applicationName).toString();
        String mode = (applicationMode == null || applicationMode.isBlank()) ? "STREAMING" : applicationMode;

        FlinkApplication app = new FlinkApplication(applicationName, arn, runtimeEnvironment,
                serviceExecutionRole, mode);
        app.setAccountId(accountId);
        // Stamp the savepoints volume name now, with the current prefix, so it is persisted
        // rather than recomputed later. Only records predating this field fall back to the
        // legacy name.
        app.setDockerVolumeName(containerManager.savepointsVolumeName(app));
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

        putApplication(app);
        LOG.infov("Created Kinesis Analytics V2 application {0}", applicationName);
        return app;
    }

    public FlinkApplication describeApplication(String applicationName) {
        return describeApplication(applicationName, config.defaultRegion());
    }

    public FlinkApplication describeApplication(String applicationName, String region) {
        String accountId = regionResolver.getAccountId();
        return getStoredApplication(accountId, region, applicationName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Application not found: " + applicationName, 400));
    }

    public List<FlinkApplication> listApplications() {
        return listApplications(config.defaultRegion());
    }

    public List<FlinkApplication> listApplications(String region) {
        String prefix = region + "/";
        String accountId = regionResolver.getAccountId();
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            migrateLegacyApplications(aware, accountId, region);
            return aware.scanForAccount(accountId, key -> key.startsWith(prefix));
        }
        if (region.equals(config.defaultRegion())) {
            for (String key : new ArrayList<>(storage.keys())) {
                if (!key.contains("/")) {
                    getStoredApplication(accountId, region, key);
                }
            }
        }
        return storage.scan(key -> key.startsWith(prefix));
    }

    public PaginatedResult<FlinkApplication> listApplications(String region, Integer limit, String nextToken) {
        if (nextToken != null && !nextToken.isEmpty() && !APPLICATION_NAME.matcher(nextToken).matches()) {
            throw new AwsException("InvalidRequestException", "Invalid NextToken.", 400);
        }
        return Pagination.paginateUnencoded(listApplications(region), FlinkApplication::getApplicationName,
                limit, nextToken, 50, 50, 128, "InvalidRequestException");
    }

    public FlinkApplication startApplication(String applicationName) {
        return startApplication(applicationName, config.defaultRegion());
    }

    public FlinkApplication startApplication(String applicationName, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        return stopApplication(applicationName, config.defaultRegion());
    }

    public FlinkApplication stopApplication(String applicationName, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
        if (app.getApplicationStatus() != ApplicationStatus.RUNNING
                && app.getApplicationStatus() != ApplicationStatus.STARTING) {
            throw new AwsException("ResourceInUseException",
                    "Application " + applicationName + " cannot be stopped while in state "
                            + app.getApplicationStatus() + "; it must be RUNNING or STARTING", 400);
        }
        boolean restoring;
        synchronized (storedApplicationLock(regionResolver.getAccountId(), applicationKey(region, applicationName))) {
            restoring = restoringApplications.contains(restoringKey(app));
            if (restoring) {
                // The startup restore is starting its cluster, which it removes once that start is through, and the
                // application is READY then. Until then it is STOPPING, as AWS reports a stop in progress, which
                // StartApplication and DeleteApplication refuse: no other start begins under the cluster's names.
                app.setApplicationStatus(ApplicationStatus.STOPPING);
                app.setLastUpdateTimestamp(Instant.now());
                putApplication(app);
            }
        }
        if (!restoring) {
            if (!config.services().kinesisAnalytics().mock()) {
                containerManager.stopCluster(app);
            }
            // Stopping the JobManager is synchronous, so the application returns to READY directly.
            app.setApplicationStatus(ApplicationStatus.READY);
            app.setLastUpdateTimestamp(Instant.now());
            putApplication(app);
        }
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
        return updateApplication(applicationName, currentApplicationVersionId, serviceExecutionRole,
                codeS3Bucket, codeS3Key, codeS3ObjectVersion, parallelism, snapshotsEnabled,
                config.defaultRegion());
    }

    public FlinkApplication updateApplication(String applicationName, Long currentApplicationVersionId,
                                              String serviceExecutionRole, String codeS3Bucket,
                                              String codeS3Key, String codeS3ObjectVersion,
                                              Integer parallelism, Boolean snapshotsEnabled, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        deleteApplication(applicationName, createTimestamp, config.defaultRegion());
    }

    public void deleteApplication(String applicationName, Instant createTimestamp, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        String key = applicationKey(region, applicationName);
        if (app.getAccountId() != null && storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            aware.deleteForAccount(app.getAccountId(), key);
        } else {
            storage.delete(key);
        }
        LOG.infov("Deleted Kinesis Analytics V2 application {0}", applicationName);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        return findByArn(resourceArn).getTags();
    }

    /**
     * Gives the application a new map holding its tags and {@code tags}. Tag calls run one at a time, so two of them
     * never lose one's tags, and a lookup reads one whole set.
     */
    public synchronized Map<String, String> tagResource(String resourceArn, Map<String, String> tags) {
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

    /** Gives the application a new map without {@code tagKeys}, one tag call at a time, as {@link #tagResource}. */
    public synchronized Map<String, String> untagResource(String resourceArn, List<String> tagKeys) {
        FlinkApplication app = findByArn(resourceArn);
        Map<String, String> remaining = new LinkedHashMap<>(app.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(remaining::remove);
        }
        app.setTags(remaining);
        putApplication(app);
        return app.getTags();
    }

    @Override
    public String namespace() {
        return "kinesisanalytics";
    }

    /** A request names an application by its name, which its ARN carries whole: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the application whose ARN is {@code arn}, in the request's account, where the handlers read: an ARN
     * of another account names none. Read in place under the Region the ARN names and the application's name, and in
     * the default Region under the name alone, where an application stored before applications were kept by Region
     * still is, a record stored before storage was account-aware included, and matched on the whole ARN. Moving such
     * an application to its Regional key puts it there before deleting the name alone, so a read that misses both
     * while it moves looks under the Regional key again. Tag calls give the application a new map, so the read is of
     * one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "kinesisanalytics")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account) || !parsed.resource().startsWith(APPLICATION_RESOURCE)) {
            return Map.of();
        }
        String name = parsed.resource().substring(APPLICATION_RESOURCE.length());
        String key = applicationKey(parsed.region(), name);
        Optional<FlinkApplication> application = storedApplication(account, key, arn);
        if (application.isEmpty() && parsed.region().equals(config.defaultRegion())) {
            application = storedApplication(account, name, arn).or(() -> storedApplication(account, key, arn));
        }
        return application.map(app -> new LinkedHashMap<>(app.getTags())).orElseGet(LinkedHashMap::new);
    }

    /** The application stored under {@code key} in {@code account}, read in place, if its ARN is {@code arn}. */
    private Optional<FlinkApplication> storedApplication(String account, String key, String arn) {
        return AccountAwareStorageBackend.readInAccount(storage, account, key)
                .filter(app -> arn.equals(app.getApplicationArn()));
    }

    public Snapshot createApplicationSnapshot(String applicationName, String snapshotName) {
        return createApplicationSnapshot(applicationName, snapshotName, config.defaultRegion());
    }

    public Snapshot createApplicationSnapshot(String applicationName, String snapshotName, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        return describeApplicationSnapshot(applicationName, snapshotName, config.defaultRegion());
    }

    public Snapshot describeApplicationSnapshot(String applicationName, String snapshotName, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
        Snapshot snapshot = app.getSnapshots().get(snapshotName);
        if (snapshot == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Snapshot not found: " + snapshotName, 400);
        }
        return snapshot;
    }

    public List<Snapshot> listApplicationSnapshots(String applicationName) {
        return listApplicationSnapshots(applicationName, config.defaultRegion());
    }

    public List<Snapshot> listApplicationSnapshots(String applicationName, String region) {
        return List.copyOf(describeApplication(applicationName, region).getSnapshots().values());
    }

    public PaginatedResult<Snapshot> listApplicationSnapshots(String applicationName, String region,
                                                              Integer limit, String nextToken) {
        // AWS: ListSnapshotsInputLimit is 1 to 50 and NextToken is 1 to 512 with no pattern, so
        // unlike ListApplications the token is not checked against a resource name here.
        return Pagination.paginateUnencoded(listApplicationSnapshots(applicationName, region),
                Snapshot::getSnapshotName, limit, nextToken, 50, 50, 512, "InvalidArgumentException");
    }

    public void deleteApplicationSnapshot(String applicationName, String snapshotName,
                                          Instant snapshotCreationTimestamp) {
        deleteApplicationSnapshot(applicationName, snapshotName, snapshotCreationTimestamp,
                config.defaultRegion());
    }

    public void deleteApplicationSnapshot(String applicationName, String snapshotName,
                                          Instant snapshotCreationTimestamp, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        return createApplicationPresignedUrl(applicationName, urlType,
                sessionExpirationDurationInSeconds, config.defaultRegion());
    }

    public String createApplicationPresignedUrl(String applicationName, String urlType,
                                                Long sessionExpirationDurationInSeconds, String region) {
        FlinkApplication app = describeApplication(applicationName, region);
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
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgumentException", "Invalid resource ARN: " + resourceArn, 400);
        }
        String accountId = regionResolver.getAccountId();
        if (!"kinesisanalytics".equals(arn.service())
                || !arn.resource().startsWith(APPLICATION_RESOURCE)
                || arn.resource().length() == APPLICATION_RESOURCE.length()
                || arn.resource().substring(APPLICATION_RESOURCE.length()).contains("/")) {
            throw new AwsException("InvalidArgumentException", "Invalid resource ARN: " + resourceArn, 400);
        }
        if (!accountId.equals(arn.accountId())) {
            throw new AwsException("ResourceNotFoundException",
                    "No application found for ARN: " + resourceArn, 400);
        }
        String applicationName = arn.resource().substring(APPLICATION_RESOURCE.length());
        String region = arn.region().isEmpty() ? config.defaultRegion() : arn.region();
        return getStoredApplication(accountId, region, applicationName)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No application found for ARN: " + resourceArn, 400));
    }

    /**
     * Brings the persisted applications back after Floci restarts. Shutdown removes their Flink clusters
     * ({@link FlinkContainerManager#stopAll}), so an application stored {@code RUNNING}, or still starting, has no
     * cluster and no job. Each goes back to {@code STARTING} at once, and the clusters start in the background, so
     * Floci does not wait for them to report itself ready; the poller leaves an application alone while its restore
     * runs.
     *
     * <p>An application whose cluster outlived Floci, as when Floci is killed, keeps that cluster when it still
     * serves the application, its job and state included. Otherwise it gets a new cluster, as
     * {@code StartApplication} starts one, in its own account, which reads its code from S3 again, and the poller
     * runs its job and reports it {@code RUNNING}. Like {@code StartApplication}, the job starts afresh rather than
     * from a snapshot; the snapshots stay in their volume. An application whose cluster cannot be started is
     * returned to {@code READY}, from which {@code StartApplication} starts it again, and a {@code READY} one is left
     * as it is. A surviving cluster not kept is removed before a new one starts. An application stopped during its
     * restore is {@code STOPPING} until the restore has removed the cluster it kept or started, and {@code READY} then;
     * one still {@code STOPPING} at the next start has what survives of its cluster removed, and is {@code READY}
     * then. While Docker keeps a cluster the restore removes, its application stays {@code STOPPING}, for a later
     * start to remove it. Every application is tried, and the ones that could not be restored
     * fail the returned future together. A shutdown during the restore leaves the applications not started yet
     * {@code STARTING}, for the next start.
     */
    public CompletableFuture<Void> restorePersistedRuntime() {
        if (config.services().kinesisAnalytics().mock()) {
            return CompletableFuture.completedFuture(null);
        }
        List<RestoringApplication> pending = new ArrayList<>();
        for (AccountEntry<FlinkApplication> entry : persistedApplications()) {
            RestoringApplication restoring = prepareRestore(entry.accountId(), entry.key());
            if (restoring != null) {
                pending.add(restoring);
            }
        }
        return CompletableFuture.runAsync(() -> {
            RuntimeException failure = null;
            for (RestoringApplication restoring : pending) {
                if (containerManager.isShuttingDown()) {
                    // Applications not reached yet stay starting, which the next start restores.
                    break;
                }
                FlinkApplication app = restoring.app();
                try {
                    String region = regionResolver.regionOf(app.getApplicationArn());
                    RequestScopes.runAs(restoring.accountId(), region, () -> restoreApplication(restoring));
                } catch (FlinkContainerManager.ShuttingDownException e) {
                    LOG.debugv("Kinesis Analytics V2 application {0} not restored: {1}", app.getApplicationName(),
                            e.getMessage());
                } catch (RuntimeException e) {
                    RuntimeException restoreFailure = new IllegalStateException("Could not restore Kinesis "
                            + "Analytics V2 application " + app.getApplicationName() + " of account "
                            + restoring.accountId(), e);
                    if (failure == null) {
                        failure = restoreFailure;
                    } else {
                        failure.addSuppressed(restoreFailure);
                    }
                } finally {
                    endRestore(restoring);
                }
            }
            if (failure != null) {
                throw failure;
            }
        });
    }

    /** Marks an application the restore brings back, under its lock; null for one it leaves as it is. */
    private RestoringApplication prepareRestore(String accountId, String key) {
        synchronized (storedApplicationLock(accountId, key)) {
            FlinkApplication app = storedApplication(accountId, key).orElse(null);
            if (app == null || app.getApplicationStatus() == ApplicationStatus.READY) {
                return null;
            }
            if (app.getAccountId() == null) {
                app.setAccountId(accountId);
            }
            restoringApplications.add(restoringKey(app));
            // A STOPPING application was stopped during a restore that Floci did not live to finish: the stop stands,
            // and its restore removes what survives of its cluster before it turns READY.
            if (app.getApplicationStatus() != ApplicationStatus.STOPPING) {
                app.setApplicationStatus(ApplicationStatus.STARTING);
            }
            putApplication(app);
            return new RestoringApplication(accountId, key, app);
        }
    }

    private void restoreApplication(RestoringApplication restoring) {
        FlinkApplication app = restoring.app();
        Object lock = storedApplicationLock(restoring.accountId(), restoring.key());
        ApplicationStatus status;
        synchronized (lock) {
            status = storedStatus(restoring);
        }
        if (status != ApplicationStatus.STARTING) {
            if (status == ApplicationStatus.STOPPING) {
                // Stopped while it waited its turn, or before an earlier Floci finished restoring it: a cluster that
                // outlived Floci goes too. Nothing else starts one under its names while the application is STOPPING,
                // which it is until its restore ends.
                containerManager.removeSurvivingCluster(app);
                finishStop(restoring);
            }
            return;
        }
        boolean kept;
        try {
            kept = containerManager.adoptRunningCluster(app);
            if (!kept) {
                // What survives of a cluster not kept goes first, under either name form, so two jobs never run.
                containerManager.removeSurvivingCluster(app);
                // The job ran on the cluster shutdown removed; the new cluster runs it again.
                app.setFlinkJobId(null);
                containerManager.startCluster(app);
            }
        } catch (FlinkContainerManager.ShuttingDownException e) {
            throw e;
        } catch (RuntimeException e) {
            // READY, from which StartApplication starts it again, once its cluster is gone; STOPPING while Docker keeps
            // some of it, naming it for a later start to remove.
            ApplicationStatus settled = ApplicationStatus.READY;
            try {
                containerManager.removeOwnCluster(app);
                // A start that failed part way cleaned up after itself without confirming it, and forgot its
                // containers: what is left under the cluster's names goes too, confirmed.
                containerManager.removeSurvivingCluster(app);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
                settled = ApplicationStatus.STOPPING;
            }
            synchronized (lock) {
                if (storedStatus(restoring) != null) {
                    restoringApplications.remove(restoringKey(app));
                    app.setApplicationStatus(settled);
                    app.setLastUpdateTimestamp(Instant.now());
                    putApplication(app);
                }
            }
            throw e;
        }
        synchronized (lock) {
            if (storedStatus(restoring) == ApplicationStatus.STARTING) {
                // Its restore ends here: from now on a stop stops the cluster itself.
                restoringApplications.remove(restoringKey(app));
                if (kept) {
                    app.setApplicationStatus(ApplicationStatus.RUNNING);
                    app.setLastUpdateTimestamp(Instant.now());
                }
                putApplication(app);
                return;
            }
        }
        // Stopped while its cluster started: the cluster goes before the application turns READY, from which another
        // start could begin under the same names. Only this cluster goes, by its containers. While Docker keeps some of
        // it, this throws and the application stays STOPPING, for a later start to remove the rest.
        containerManager.removeOwnCluster(app);
        finishStop(restoring);
    }

    /** Ends the restore of an application stopped during it, now its cluster is gone: it turns {@code READY}. */
    private void finishStop(RestoringApplication restoring) {
        FlinkApplication app = restoring.app();
        synchronized (storedApplicationLock(restoring.accountId(), restoring.key())) {
            restoringApplications.remove(restoringKey(app));
            if (storedStatus(restoring) == ApplicationStatus.STOPPING) {
                app.setApplicationStatus(ApplicationStatus.READY);
                app.setLastUpdateTimestamp(Instant.now());
                putApplication(app);
            }
        }
    }

    /**
     * Ends an application's restore, if it has not ended already: the poller and {@code StopApplication} treat the
     * application as any other again. One still {@code STOPPING} here, when Docker kept its cluster or shutdown
     * overtook its restore, stays so, for the next start to remove what is left.
     */
    private void endRestore(RestoringApplication restoring) {
        synchronized (storedApplicationLock(restoring.accountId(), restoring.key())) {
            restoringApplications.remove(restoringKey(restoring.app()));
        }
    }

    /** The stored application's status, or null when it is gone or another application was created under its name. */
    private ApplicationStatus storedStatus(RestoringApplication restoring) {
        return storedApplication(restoring.accountId(), restoring.key())
                .filter(current -> Objects.equals(current.getCreateTimestamp(), restoring.app().getCreateTimestamp()))
                .map(FlinkApplication::getApplicationStatus)
                .orElse(null);
    }

    /**
     * The stored applications, each found as a describe finds it, by its account, Region and name. A record stored
     * under an older key moves onto its current one, so the restore writing it back leaves no second record behind
     * for the next start to restore again.
     */
    private List<AccountEntry<FlinkApplication>> persistedApplications() {
        List<AccountEntry<FlinkApplication>> stored;
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            stored = aware.scanAllAccountEntries(key -> true);
        } else {
            stored = storage.scan(key -> true).stream()
                    .map(app -> new AccountEntry<>(Objects.requireNonNullElse(app.getAccountId(),
                            regionResolver.getDefaultAccountId()), app.getApplicationName(), app))
                    .toList();
        }
        Map<String, AccountEntry<FlinkApplication>> applications = new LinkedHashMap<>();
        for (AccountEntry<FlinkApplication> entry : stored) {
            String accountId = entry.accountId();
            String region = regionResolver.regionOf(entry.value().getApplicationArn());
            String name = entry.value().getApplicationName();
            String key = applicationKey(region, name);
            getStoredApplication(accountId, region, name).ifPresent(app ->
                    applications.put(accountId + ":" + key, new AccountEntry<>(accountId, key, app)));
        }
        return List.copyOf(applications.values());
    }

    private Optional<FlinkApplication> storedApplication(String accountId, String key) {
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            return aware.getForAccount(accountId, key);
        }
        return storage.get(key);
    }

    private Object storedApplicationLock(String accountId, String key) {
        return storedApplicationLocks.lockFor(accountId + ":" + key);
    }

    /** An application the restore brings back, with the account and key it is stored under. */
    private record RestoringApplication(String accountId, String key, FlinkApplication app) {
    }

    private String restoringKey(FlinkApplication app) {
        return app.getAccountId() + ":" + app.getApplicationArn();
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(this::pollReadiness, 1, 2, TimeUnit.SECONDS);
    }

    /** One pass of the readiness poller: drives each starting application toward RUNNING, and its snapshots. */
    void pollReadiness() {
        try {
            if (config.services().kinesisAnalytics().mock()) {
                return;
            }
            for (FlinkApplication app : allApplications()) {
                boolean changed = false;
                if (app.getApplicationStatus() == ApplicationStatus.STARTING
                        && !restoringApplications.contains(restoringKey(app))) {
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
    }

    private List<FlinkApplication> allApplications() {
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            return aware.scanAllAccounts();
        }
        return storage.scan(k -> true);
    }

    private void putApplication(FlinkApplication app) {
        String region = regionResolver.regionOf(app.getApplicationArn());
        String key = applicationKey(region, app.getApplicationName());
        if (app.getAccountId() != null && storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            aware.putForAccount(app.getAccountId(), key, app);
        } else {
            storage.put(key, app);
        }
    }

    private Optional<FlinkApplication> getStoredApplication(String accountId, String region,
                                                             String applicationName) {
        String key = applicationKey(region, applicationName);
        if (storage instanceof AccountAwareStorageBackend<FlinkApplication> aware) {
            List<String> legacyKeys = region.equals(config.defaultRegion())
                    ? List.of(applicationName) : List.of();
            return aware.getForAccountMigratingLegacyKeys(accountId, key, legacyKeys,
                    application -> belongsTo(application, accountId, region, applicationName),
                    isDefaultScope(accountId, region));
        }
        synchronized (storage) {
            Optional<FlinkApplication> scoped = storage.get(key);
            if (scoped.isPresent() || !region.equals(config.defaultRegion())) {
                return scoped;
            }
            Optional<FlinkApplication> legacy = storage.get(applicationName)
                    .filter(application -> belongsTo(application, accountId, region, applicationName));
            legacy.ifPresent(application -> {
                storage.put(key, application);
                storage.delete(applicationName);
            });
            return legacy;
        }
    }

    private void migrateLegacyApplications(AccountAwareStorageBackend<FlinkApplication> aware,
                                           String accountId, String region) {
        if (!region.equals(config.defaultRegion())) {
            return;
        }
        aware.migrateLegacyEntries(accountId, key -> !key.contains("/"),
                application -> applicationKey(region, application.getApplicationName()),
                application -> belongsTo(application, accountId, region, application.getApplicationName()));
    }

    private boolean isDefaultScope(String accountId, String region) {
        return accountId.equals(regionResolver.getDefaultAccountId())
                && region.equals(regionResolver.getDefaultRegion());
    }

    private static boolean belongsTo(FlinkApplication application, String accountId, String region,
                                     String applicationName) {
        if (!Objects.equals(applicationName, application.getApplicationName())) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(application.getApplicationArn());
            return accountId.equals(arn.accountId()) && region.equals(arn.region())
                    && (application.getAccountId() == null || accountId.equals(application.getAccountId()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String applicationKey(String region, String applicationName) {
        return region + "/" + applicationName;
    }
}
