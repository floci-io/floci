package io.github.hectorvent.floci.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.kinesisanalytics.KinesisAnalyticsRuntimes;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the backing Apache Flink cluster for a Managed Service for Apache Flink application.
 *
 * <p>The cluster is a standalone session cluster: a **JobManager** container (REST/UI on 8081) plus,
 * when the application has a code artifact to run, a **TaskManager** container that shares the
 * JobManager's network namespace ({@code --network container:<jobmanager>}) so the two communicate over
 * {@code localhost} without a dedicated Docker network. The container joins Floci's Docker network so
 * the JobManager can look up {@code http://floci:4566} to consume local Kinesis or MSK streams.
 *
 * <p>Job deployment: {@code StartApplication} reads the application JAR from Floci's local S3 (on the
 * request thread, so account context is available) and stashes the bytes; the readiness poller then
 * uploads and runs it against the cluster via {@link FlinkRestClient} once task slots are available, and
 * flips the application to RUNNING when the Flink job reaches {@code RUNNING}. An application without a
 * code artifact comes up RUNNING as a bare cluster (no job).
 */
@ApplicationScoped
public class FlinkContainerManager {

    private static final Logger LOG = Logger.getLogger(FlinkContainerManager.class);
    private static final int JOBMANAGER_REST_PORT = 8081;
    private static final String SAVEPOINTS_MOUNT = "/opt/flink/savepoints";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final LaunchedContainerAwsEnv awsEnv;
    private final S3Service s3Service;
    private final FlinkRestClient flinkRest;
    private final ObjectMapper objectMapper;

    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();
    private final Map<String, String> taskManagerIds = new ConcurrentHashMap<>();
    // Application JAR bytes read from S3 at StartApplication, pending upload by the readiness poller.
    private final Map<String, byte[]> pendingJars = new ConcurrentHashMap<>();
    // Applications whose job submission hard-failed (e.g. a bad JAR) — not retried by the poller.
    private final Set<String> submissionFailed = ConcurrentHashMap.newKeySet();

    @Inject
    public FlinkContainerManager(ContainerBuilder containerBuilder,
                                 ContainerLifecycleManager lifecycleManager,
                                 ContainerLogStreamer logStreamer,
                                 ContainerDetector containerDetector,
                                 EmulatorConfig config,
                                 RegionResolver regionResolver,
                                 LaunchedContainerAwsEnv awsEnv,
                                 S3Service s3Service,
                                 FlinkRestClient flinkRest,
                                 ObjectMapper objectMapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.awsEnv = awsEnv;
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
        this.flinkRest = flinkRest;
    }

    /**
     * Deterministic JobManager container name for an application, stable across emulator restarts.
     * Follows the shared {@code floci-<service>-<name>} convention via
     * {@link ContainerStorageHelper#resourceName} so a configured {@code resourceNamespace} scopes the
     * name (letting multiple Floci instances share a Docker host without collisions).
     *
     * <p>Like OpenSearch's domain names, the name is scoped by application name, not by account — two
     * accounts using the same application name would map to the same container. This mirrors AWS, where
     * an application name is account-and-region unique, and matches the accepted OpenSearch trade-off.
     */
    private String containerName(String applicationName) {
        return ContainerStorageHelper.resourceName(config, "kinesisanalytics", null, applicationName);
    }

    /**
     * Starts the JobManager (and, when the application has a code artifact, a TaskManager) for the
     * application. Reads the JAR from S3 up front (on the request thread) so a missing artifact fails
     * StartApplication fast; the JAR is uploaded/run asynchronously by the readiness poller.
     */
    public void startCluster(FlinkApplication app) {
        String image = KinesisAnalyticsRuntimes.resolveImage(
                config.services().kinesisAnalytics().defaultImage(), app.getRuntimeEnvironment());
        String jmName = containerName(app.getApplicationName());
        String tmName = jmName + "-tm";

        // Read the JAR before starting anything so a missing/empty artifact fails fast, before any
        // container is created. (S3 read runs on the request thread → account context is available.)
        byte[] jarBytes = app.hasCode() ? readJar(app) : null;
        List<String> awsBaselineEnv = awsEnv.sdkBaselineEnv(config.defaultRegion(), Optional.empty());

        LOG.infov("Starting Flink cluster for application {0} using image {1}{2}",
                app.getApplicationName(), image, app.hasCode() ? " (with TaskManager)" : "");
        lifecycleManager.removeIfExists(jmName);
        lifecycleManager.removeIfExists(tmName);

        // rpc.address=localhost so a same-netns TaskManager reaches the JobManager over loopback;
        // rest.bind-address=0.0.0.0 so the REST/UI is reachable via the mapped host port / container IP.
        String jmProps = "jobmanager.rpc.address: localhost\nrest.bind-address: 0.0.0.0";
        ContainerBuilder.Builder jmSpec = containerBuilder.newContainer(image)
                .withName(jmName)
                .withCmd("jobmanager")
                .withEnv(awsBaselineEnv)
                .withEnv("FLINK_PROPERTIES", jmProps)
                .withDockerNetwork(config.services().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "kinesisanalytics", app.getApplicationName(), regionResolver.getAccountId(),
                        regionResolver.getDefaultRegion()));
        // Named volume (not the container's ephemeral filesystem) so snapshots survive a
        // Stop/StartApplication cycle — stopCluster() removes the JobManager container, but this
        // volume is only removed on DeleteApplication (removeSavepointsVolume), mirroring how other
        // Docker-backed services keep persistent data outside the container lifecycle.
        ContainerStorageHelper.applyStorage(jmSpec, lifecycleManager, config, "kinesisanalytics",
                app.getApplicationName() + "-savepoints", app.getApplicationName() + "-savepoints",
                SAVEPOINTS_MOUNT);
        if (!containerDetector.isRunningInContainer()) {
            jmSpec.withDynamicPort(JOBMANAGER_REST_PORT);
        } else {
            jmSpec.withExposedPort(JOBMANAGER_REST_PORT);
        }

        ContainerSpec jmBuiltSpec = jmSpec.build();
        ContainerInfo jm;
        try {
            String jmContainerId = lifecycleManager.create(jmBuiltSpec);
            // Written before start (not via the post-start copyFileIntoContainer used for
            // application_properties.json below) because log4j2 reads its config file at JVM
            // startup -- copying it in after the process is already running would be too late for
            // anything the JobManager logs from its own boot onward.
            copyFileIntoContainer(jmContainerId, "/opt/flink/conf", "log4j-console.properties",
                    msfStyleLog4j2Config(app), true);
            jm = lifecycleManager.startCreated(jmContainerId, jmBuiltSpec);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(jmName);
            throw e;
        }
        app.setContainerId(jm.containerId());
        containerIds.put(app.getApplicationName(), jm.containerId());
        EndpointInfo rest = jm.getEndpoint(JOBMANAGER_REST_PORT);
        app.setRestEndpoint("http://" + rest.host() + ":" + rest.port());
        attachLogs(app, jm.containerId());
        LOG.infov("Flink JobManager {0} started for application {1}: rest={2}",
                jm.containerId(), app.getApplicationName(), app.getRestEndpoint());

        if (app.hasCode()) {
            // TaskManager shares the JobManager's network namespace so it registers over localhost and
            // provides the task slots the job needs to run.
            String tmProps = "jobmanager.rpc.address: localhost\n"
                    + "taskmanager.numberOfTaskSlots: " + Math.max(1, app.getParallelism());
            ContainerSpec tmSpec = containerBuilder.newContainer(image)
                    .withName(tmName)
                    .withCmd("taskmanager")
                    .withEnv(awsBaselineEnv)
                    .withEnv("FLINK_PROPERTIES", tmProps)
                    .withNetworkMode("container:" + jm.containerId())
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "kinesisanalytics", app.getApplicationName(), regionResolver.getAccountId(),
                            regionResolver.getDefaultRegion()))
                    .build();
            try {
                String tmContainerId = lifecycleManager.create(tmSpec);
                copyFileIntoContainer(tmContainerId, "/opt/flink/conf", "log4j-console.properties",
                        msfStyleLog4j2Config(app), true);
                ContainerInfo tm = lifecycleManager.startCreated(tmContainerId, tmSpec);
                app.setTaskManagerContainerId(tm.containerId());
                taskManagerIds.put(app.getApplicationName(), tm.containerId());
            } catch (RuntimeException e) {
                // Roll back the whole cluster so a failed start leaves nothing behind.
                lifecycleManager.removeIfExists(tmName);
                stopCluster(app);
                throw e;
            }
            // A real MSF environment always provides this file to every Flink process (JobManager and
            // TaskManager) so KinesisAnalyticsRuntime.getApplicationProperties() never has to handle a
            // missing file, even when no EnvironmentProperties were configured (then it's an empty
            // array). User code's main() runs on the JobManager during job submission; a sink/source's
            // open() can run on either, so both containers get it.
            // /etc/flink does not exist in the stock apache/flink image (confirmed against the real
            // image) and the container's non-root "flink" user can't create it — but naming the tar
            // entry "flink/application_properties.json" and extracting into the existing /etc lets the
            // archive extraction itself create the subdirectory, which the daemon-level copy API can do
            // even though the in-container user couldn't (verified live: root:root 775, world-readable).
            byte[] propertiesJson = applicationPropertiesJson(app);
            copyFileIntoContainer(jm.containerId(), "/etc", "flink/application_properties.json", propertiesJson);
            copyFileIntoContainer(app.getTaskManagerContainerId(), "/etc", "flink/application_properties.json",
                    propertiesJson);
            pendingJars.put(app.getApplicationName(), jarBytes);
            submissionFailed.remove(app.getApplicationName());
        }
    }

    /** Builds {@code /etc/flink/application_properties.json} exactly matching the real MSF/KDA runtime
     *  file shape: a JSON array of {@code {PropertyGroupId, PropertyMap}} objects, the same shape as
     *  the AWS API's own {@code EnvironmentProperties.PropertyGroups}. Package-private (not private)
     *  so FlinkContainerManagerTest can assert on the JSON shape directly. */
    byte[] applicationPropertiesJson(FlinkApplication app) {
        ArrayNode root = objectMapper.createArrayNode();
        app.getEnvironmentProperties().forEach((groupId, properties) -> {
            ObjectNode group = root.addObject();
            group.put("PropertyGroupId", groupId);
            ObjectNode map = group.putObject("PropertyMap");
            properties.forEach(map::put);
        });
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize application_properties.json", e);
        }
    }

    /** Builds a log4j2 {@code log4j-console.properties} that makes the JobManager/TaskManager emit
     *  one JSON object per log line, matching the schema real Managed Service for Apache Flink
     *  writes to CloudWatch Logs (applicationARN/applicationVersionId/locationInformation/logger/
     *  message/messageSchemaVersion/messageType/threadName/throwableInformation) -- so a JAR relying
     *  on that shape for its own log-based tests sees the same thing here as on real MSF. Overwrites
     *  the stock image's default (non-JSON) config entirely; not re-applied on {@link #redeployCode},
     *  so applicationVersionId in already-emitted log lines does not advance across an in-place code
     *  update (the JobManager/TaskManager JVMs, and therefore log4j2, are not restarted for that --
     *  matching real MSF, which also keeps the same processes running across an UpdateApplication).
     *  Package-private (not private) so FlinkContainerManagerTest can assert on the pattern shape
     *  directly. */
    byte[] msfStyleLog4j2Config(FlinkApplication app) {
        String pattern = "{"
                + "\"applicationARN\":\"%enc{"
                + literalForLog4j2Pattern(String.valueOf(app.getApplicationArn())) + "}{JSON}\","
                + "\"applicationVersionId\":\"%enc{"
                + literalForLog4j2Pattern(String.valueOf(app.getApplicationVersionId())) + "}{JSON}\","
                + "\"locationInformation\":\"%C.%M(%F:%L)\","
                + "\"logger\":\"%logger\","
                + "\"message\":\"%enc{%message}{JSON}\","
                + "\"messageSchemaVersion\":\"1\","
                + "\"messageType\":\"%level\","
                + "\"threadName\":\"%enc{%thread}{JSON}\","
                + "\"throwableInformation\":\"%enc{%ex}{JSON}\""
                + "}%n";
        String properties = "rootLogger.level = INFO\n"
                + "rootLogger.appenderRef.console.ref = ConsoleAppender\n"
                + "appender.console.type = Console\n"
                + "appender.console.name = ConsoleAppender\n"
                + "appender.console.layout.type = PatternLayout\n"
                + "appender.console.layout.pattern = " + pattern + "\n";
        return properties.getBytes(StandardCharsets.UTF_8);
    }

    /** Escapes an application-supplied value (e.g. the ARN, built from the caller's ApplicationName --
     *  {@link io.github.hectorvent.floci.services.kinesisanalytics.KinesisAnalyticsV2Service} restricts
     *  that to AWS's own {@code [a-zA-Z0-9_.-]} charset, but this stays defensive in case some other
     *  caller ever feeds it something else) so it can be embedded as literal text inside a log4j2
     *  {@code PatternLayout} pattern written to a {@code .properties} file: doubles {@code %} so
     *  log4j2's pattern parser can't interpret it as the start of a conversion specifier, drops
     *  {@code $} so it can't start a {@code ${...}} Lookup (log4j2 resolves those against config
     *  values -- including this pattern -- at config-load time, so an unescaped one could leak an
     *  environment variable or system property into every log line; doubling it like {@code %} only
     *  defers the lookup to render time rather than neutralizing it, so it isn't a safe escape here),
     *  then backslash-escapes control characters so the value survives
     *  {@code java.util.Properties}-style parsing of the config file intact. The surrounding
     *  {@code %enc{...}{JSON}} wrapper (already used for %message/%thread/%ex above) then JSON-escapes
     *  the resulting literal at log time, so quotes/backslashes in the original value can't break the
     *  emitted JSON. */
    private static String literalForLog4j2Pattern(String value) {
        String percentEscaped = value.replace("%", "%%");
        StringBuilder escaped = new StringBuilder(percentEscaped.length());
        for (int i = 0; i < percentEscaped.length(); i++) {
            char c = percentEscaped.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '$' -> { /* dropped: see method Javadoc -- can't be escaped to a safe literal */ }
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private void copyFileIntoContainer(String containerId, String remoteDir, String relativePath, byte[] content) {
        copyFileIntoContainer(containerId, remoteDir, relativePath, content, false);
    }

    /** @param required when {@code true}, a failed copy is rethrown instead of only logged, so a
     *  caller for whom the file is not optional (e.g. the log4j2 config that this cluster's whole
     *  CloudWatch-log-shape guarantee depends on) fails the start instead of silently running with
     *  the stock config. */
    private void copyFileIntoContainer(String containerId, String remoteDir, String relativePath, byte[] content,
            boolean required) {
        if (containerId == null) {
            return;
        }
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(new ByteArrayInputStream(tarSingleFile(relativePath, content)))
                    .withRemotePath(remoteDir)
                    .exec();
        } catch (Exception e) {
            if (required) {
                throw new IllegalStateException(
                        "Could not copy " + relativePath + " into Flink container " + containerId, e);
            }
            LOG.warnv("Could not copy {0} into Flink container {1}: {2}", relativePath, containerId, e.getMessage());
        }
    }

    private static byte[] tarSingleFile(String entryName, byte[] content) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(content.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for " + entryName, e);
        }
    }

    private byte[] readJar(FlinkApplication app) {
        try {
            S3Object obj = s3Service.getObject(app.getCodeS3Bucket(), app.getCodeS3Key(),
                    app.getCodeS3ObjectVersion());
            byte[] data = obj != null ? obj.getData() : null;
            if (data == null || data.length == 0) {
                throw new AwsException("InvalidArgumentException",
                        "Application code object is empty: s3://" + app.getCodeS3Bucket() + "/"
                                + app.getCodeS3Key(), 400);
            }
            return data;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidArgumentException",
                    "Unable to fetch application code from s3://" + app.getCodeS3Bucket() + "/"
                            + app.getCodeS3Key() + ": " + e.getMessage(), 400);
        }
    }

    /**
     * Drives the application toward RUNNING and reports whether it has reached it. For a bare cluster
     * (no code), RUNNING once the JobManager REST answers. For an application with code, this uploads
     * and runs the stashed JAR once task slots are available, then reports RUNNING when the Flink job
     * reaches {@code RUNNING}. Safe to call repeatedly from the readiness poller.
     */
    public boolean advanceToRunning(FlinkApplication app) {
        String rest = app.getRestEndpoint();
        if (rest == null || !flinkRest.isRestUp(rest)) {
            return false;
        }
        if (!app.hasCode()) {
            return true;
        }
        if (app.getFlinkJobId() == null) {
            if (submissionFailed.contains(app.getApplicationName())) {
                return false;
            }
            if (flinkRest.totalSlots(rest) < Math.max(1, app.getParallelism())) {
                return false; // TaskManager not registered yet
            }
            byte[] jar = pendingJars.get(app.getApplicationName());
            if (jar == null) {
                return false; // stashed at StartApplication; absent only after an emulator restart
            }
            try {
                String jarId = flinkRest.uploadJar(rest, jar);
                String jobId = flinkRest.runJob(rest, jarId, app.getParallelism());
                app.setFlinkJobId(jobId);
                pendingJars.remove(app.getApplicationName());
                LOG.infov("Submitted Flink job {0} for application {1}", jobId, app.getApplicationName());
            } catch (Exception e) {
                // Hard failure (e.g. a JAR with no main class) — do not resubmit every tick.
                submissionFailed.add(app.getApplicationName());
                LOG.errorv(e, "Failed to submit Flink job for application {0}", app.getApplicationName());
            }
            return false;
        }
        return "RUNNING".equals(flinkRest.jobState(rest, app.getFlinkJobId()));
    }

    /**
     * Swaps in a new application JAR on an already-running cluster ({@code UpdateApplication} with a
     * new {@code ApplicationCodeConfigurationUpdate}), without tearing down the JobManager/TaskManager
     * containers: cancels the current job (if any) and refreshes {@code application_properties.json},
     * then stashes the new JAR the same way {@link #startCluster} does so the readiness poller
     * ({@link #advanceToRunning}) picks it up and resubmits it as soon as task slots free up. Callers
     * must only invoke this when the application already has a TaskManager (a running job) — attaching
     * code to a bare cluster for the first time is not supported here.
     */
    public void redeployCode(FlinkApplication app) {
        String rest = app.getRestEndpoint();
        if (rest != null && app.getFlinkJobId() != null) {
            flinkRest.cancelJob(rest, app.getFlinkJobId());
            app.setFlinkJobId(null);
        }
        byte[] propertiesJson = applicationPropertiesJson(app);
        copyFileIntoContainer(app.getContainerId(), "/etc", "flink/application_properties.json", propertiesJson);
        copyFileIntoContainer(app.getTaskManagerContainerId(), "/etc", "flink/application_properties.json",
                propertiesJson);
        pendingJars.put(app.getApplicationName(), readJar(app));
        submissionFailed.remove(app.getApplicationName());
        LOG.infov("Redeployed code for Kinesis Analytics V2 application {0}", app.getApplicationName());
    }

    public void stopCluster(FlinkApplication app) {
        String rest = app.getRestEndpoint();
        if (rest != null && app.getFlinkJobId() != null) {
            flinkRest.cancelJob(rest, app.getFlinkJobId());
        }
        pendingJars.remove(app.getApplicationName());
        submissionFailed.remove(app.getApplicationName());

        // Stop the TaskManager first, then the JobManager (whose netns it shares).
        String tmId = taskManagerIds.remove(app.getApplicationName());
        if (tmId == null) {
            tmId = app.getTaskManagerContainerId();
        }
        if (tmId != null) {
            lifecycleManager.stopAndRemove(tmId, null);
        } else {
            lifecycleManager.removeIfExists(containerName(app.getApplicationName()) + "-tm");
        }

        containerIds.remove(app.getApplicationName());
        Closeable logHandle = logStreams.remove(app.getApplicationName());
        String jmId = app.getContainerId();
        if (jmId != null) {
            lifecycleManager.stopAndRemove(jmId, logHandle);
            LOG.infov("Flink cluster for application {0} stopped and removed", app.getApplicationName());
        } else {
            lifecycleManager.removeIfExists(containerName(app.getApplicationName()));
        }
        app.setContainerId(null);
        app.setRestEndpoint(null);
        app.setTaskManagerContainerId(null);
        app.setFlinkJobId(null);
    }

    /**
     * Triggers a Flink savepoint for the application's running job. Requires the application to
     * actually be RUNNING (a live {@code flinkJobId} and REST endpoint) — the caller
     * ({@link io.github.hectorvent.floci.services.kinesisanalytics.KinesisAnalyticsV2Service}) is
     * responsible for that AWS-shaped precondition check. Stores the async operation's
     * {@code request-id} on the snapshot for {@link #advanceSnapshot} to poll; throws on a failure to
     * even start the trigger (e.g. JobManager unreachable) so the caller can mark the snapshot FAILED.
     */
    public void createSnapshot(FlinkApplication app, Snapshot snapshot) {
        try {
            String requestId = flinkRest.triggerSavepoint(app.getRestEndpoint(), app.getFlinkJobId(),
                    SAVEPOINTS_MOUNT);
            snapshot.setFlinkRequestId(requestId);
            LOG.infov("Triggered Flink savepoint for application {0} snapshot {1}: request {2}",
                    app.getApplicationName(), snapshot.getSnapshotName(), requestId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to trigger Flink savepoint: " + e.getMessage(), e);
        }
    }

    /**
     * Polls a CREATING snapshot toward a terminal state, mutating {@code snapshot}'s status in place.
     * Returns {@code true} once terminal (READY or FAILED) so the caller stops polling; {@code false}
     * while still in progress, including when the JobManager probe itself fails (transient — the
     * caller should keep retrying, same as {@code advanceToRunning}'s job-state polling).
     */
    public boolean advanceSnapshot(FlinkApplication app, Snapshot snapshot) {
        String rest = app.getRestEndpoint();
        if (rest == null || snapshot.getFlinkRequestId() == null) {
            return false;
        }
        FlinkRestClient.SavepointStatus status = flinkRest.savepointStatus(rest, app.getFlinkJobId(),
                snapshot.getFlinkRequestId());
        if (status == null || !"COMPLETED".equals(status.statusId())) {
            return false; // still IN_PROGRESS, or a transient probe failure — keep polling
        }
        if (status.failed()) {
            snapshot.setSnapshotStatus(SnapshotStatus.FAILED);
            LOG.warnv("Flink savepoint failed for application {0} snapshot {1}",
                    app.getApplicationName(), snapshot.getSnapshotName());
        } else {
            snapshot.setSnapshotStatus(SnapshotStatus.READY);
            snapshot.setFlinkLocation(status.location());
            LOG.infov("Flink savepoint ready for application {0} snapshot {1}: {2}",
                    app.getApplicationName(), snapshot.getSnapshotName(), status.location());
        }
        return true;
    }

    /** Best-effort removal of a snapshot's files. A no-op if the JobManager isn't currently running —
     *  the files remain in the savepoints volume until {@link #removeSavepointsVolume} on delete. */
    public void deleteSnapshotFiles(FlinkApplication app, Snapshot snapshot) {
        String containerId = app.getContainerId();
        if (containerId == null || snapshot.getFlinkLocation() == null) {
            return;
        }
        try {
            execInContainer(containerId, new String[]{"rm", "-rf", snapshot.getFlinkLocation()});
        } catch (Exception e) {
            LOG.warnv("Could not remove savepoint files at {0} for application {1}: {2}",
                    snapshot.getFlinkLocation(), app.getApplicationName(), e.getMessage());
        }
    }

    /** Removes the persistent savepoints volume. Called only on DeleteApplication, never on a plain
     *  StopApplication (stopCluster), so snapshots survive a stop/restart cycle. */
    public void removeSavepointsVolume(FlinkApplication app) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager, "kinesisanalytics",
                app.getApplicationName() + "-savepoints", app.getApplicationName() + "-savepoints");
    }

    /**
     * Stops and removes every running Flink container (JobManagers and TaskManagers). Wired into
     * {@code EmulatorLifecycle.onStop()} so containers are torn down on shutdown alongside the other
     * container managers.
     */
    public void stopAll() {
        if (!containerIds.isEmpty()) {
            LOG.infov("Stopping {0} Flink cluster(s) on shutdown", containerIds.size());
        }
        for (String applicationName : new ArrayList<>(taskManagerIds.keySet())) {
            String tmId = taskManagerIds.remove(applicationName);
            if (tmId != null) {
                lifecycleManager.stopAndRemove(tmId, null);
            }
        }
        for (String applicationName : new ArrayList<>(containerIds.keySet())) {
            String jmId = containerIds.remove(applicationName);
            if (jmId == null) {
                continue;
            }
            Closeable logHandle = logStreams.remove(applicationName);
            lifecycleManager.stopAndRemove(jmId, logHandle);
        }
    }

    private void attachLogs(FlinkApplication app, String containerId) {
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/aws/kinesis-analytics/" + app.getApplicationName();
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        Closeable logHandle = logStreamer.attach(containerId, logGroup, logStream, region,
                "kinesisanalytics:" + app.getApplicationName());
        if (logHandle != null) {
            logStreams.put(app.getApplicationName(), logHandle);
        }
    }

    private ExecResult execInContainer(String containerId, String[] cmd) throws Exception {
        var dockerClient = lifecycleManager.getDockerClient();
        var exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDERR) {
                            stderr.writeBytes(frame.getPayload());
                        } else {
                            stdout.writeBytes(frame.getPayload());
                        }
                    }
                })
                .awaitCompletion(15, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new ExecResult(exitCode != null ? exitCode : -1,
                stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record ExecResult(long exitCode, String stdout, String stderr) {}
}
