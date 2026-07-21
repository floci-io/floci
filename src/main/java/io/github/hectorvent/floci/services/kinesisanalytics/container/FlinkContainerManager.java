package io.github.hectorvent.floci.services.kinesisanalytics.container;

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
import io.github.hectorvent.floci.services.kinesisanalytics.KinesisAnalyticsRuntimes;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;
    private final FlinkRestClient flinkRest;

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
                                 S3Service s3Service,
                                 FlinkRestClient flinkRest) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
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
                .withEnv("FLINK_PROPERTIES", jmProps)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation();
        if (!containerDetector.isRunningInContainer()) {
            jmSpec.withDynamicPort(JOBMANAGER_REST_PORT);
        } else {
            jmSpec.withExposedPort(JOBMANAGER_REST_PORT);
        }

        ContainerInfo jm;
        try {
            jm = lifecycleManager.createAndStart(jmSpec.build());
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
                    .withEnv("FLINK_PROPERTIES", tmProps)
                    .withNetworkMode("container:" + jm.containerId())
                    .withLogRotation()
                    .build();
            try {
                ContainerInfo tm = lifecycleManager.createAndStart(tmSpec);
                app.setTaskManagerContainerId(tm.containerId());
                taskManagerIds.put(app.getApplicationName(), tm.containerId());
            } catch (RuntimeException e) {
                // Roll back the whole cluster so a failed start leaves nothing behind.
                lifecycleManager.removeIfExists(tmName);
                stopCluster(app);
                throw e;
            }
            pendingJars.put(app.getApplicationName(), jarBytes);
            submissionFailed.remove(app.getApplicationName());
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
}
