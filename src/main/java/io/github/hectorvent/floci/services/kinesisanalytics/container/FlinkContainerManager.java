package io.github.hectorvent.floci.services.kinesisanalytics.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the backing Apache Flink JobManager Docker container for a Managed Service for
 * Apache Flink application. In native (dev) mode it publishes the JobManager REST/UI port
 * (8081) to a dynamic host port; in Docker mode sibling containers reach it over the docker
 * network. The container joins Floci's docker network, so the Flink JobManager can look up
 * {@code http://floci:4566} to consume local Kinesis or MSK data streams.
 *
 * <p>Modelled on {@code amazonmq/container/RabbitMqManager}: a JobManager-only cluster is
 * enough for a RUNNING application. A TaskManager is only needed once a job is actually
 * submitted (not yet in scope), so it is intentionally omitted.
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
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();

    @Inject
    public FlinkContainerManager(ContainerBuilder containerBuilder,
                                 ContainerLifecycleManager lifecycleManager,
                                 ContainerLogStreamer logStreamer,
                                 ContainerDetector containerDetector,
                                 EmulatorConfig config,
                                 RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /**
     * Deterministic container name for an application, stable across emulator restarts. Follows the
     * shared {@code floci-<service>-<name>} convention via {@link ContainerStorageHelper#resourceName}
     * so a configured {@code resourceNamespace} scopes the name (letting multiple Floci instances share
     * a Docker host without container-name collisions), matching OpenSearch/MSK/RDS/etc.
     */
    private String containerName(String applicationName) {
        return ContainerStorageHelper.resourceName(config, "kinesisanalytics", null, applicationName);
    }

    /**
     * Starts a standalone Flink JobManager for the application. Returns once the container is
     * started (not yet ready); the service's readiness poller flips the application to RUNNING
     * once {@link #isReady} observes the JobManager REST API answering.
     */
    public void startCluster(FlinkApplication app) {
        // Image is chosen from the requested RuntimeEnvironment (e.g. FLINK-1_19 → apache/flink:1.19),
        // honouring an optional operator override that pins every application to one image.
        String image = KinesisAnalyticsRuntimes.resolveImage(
                config.services().kinesisAnalytics().defaultImage(), app.getRuntimeEnvironment());
        String containerName = containerName(app.getApplicationName());
        LOG.infov("Starting Flink JobManager container for application {0} using image {1}",
                app.getApplicationName(), image);

        // Remove any stale container with the same name (e.g. leftover from a crash).
        lifecycleManager.removeIfExists(containerName);

        // `rest.bind-address: 0.0.0.0` so the REST/UI is reachable via the mapped host port
        // (the image default binds to localhost). `jobmanager.rpc.address` points at the
        // container's own name so a future TaskManager on the same network can reach it.
        String flinkProperties = "jobmanager.rpc.address: " + containerName + "\n"
                + "rest.bind-address: 0.0.0.0";

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd("jobmanager")
                .withEnv("FLINK_PROPERTIES", flinkProperties)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(JOBMANAGER_REST_PORT);
        } else {
            specBuilder.withExposedPort(JOBMANAGER_REST_PORT);
        }

        ContainerSpec spec = specBuilder.build();

        ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            // Roll back a partially-created container so a failed StartApplication does not
            // leave an orphaned container behind.
            lifecycleManager.removeIfExists(containerName);
            throw e;
        }
        app.setContainerId(info.containerId());
        containerIds.put(app.getApplicationName(), info.containerId());

        EndpointInfo rest = info.getEndpoint(JOBMANAGER_REST_PORT);
        app.setRestEndpoint("http://" + rest.host() + ":" + rest.port());
        LOG.infov("Flink JobManager container {0} started for application {1}: rest={2}",
                info.containerId(), app.getApplicationName(), app.getRestEndpoint());

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/kinesis-analytics/" + app.getApplicationName();
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region,
                "kinesisanalytics:" + app.getApplicationName());
        if (logHandle != null) {
            logStreams.put(app.getApplicationName(), logHandle);
        }
    }

    /**
     * Ready once the JobManager REST API answers on {@code /config}. That endpoint is served by
     * the dispatcher once the cluster is up, so a 200 here implies the application is RUNNING.
     */
    public boolean isReady(FlinkApplication app) {
        String restEndpoint = app.getRestEndpoint();
        if (restEndpoint == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(restEndpoint + "/config").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            // Expected while the JobManager is still booting (connection refused/timeout).
            // Logged at debug so a genuinely stuck probe is diagnosable without spamming this
            // 2s-interval hot path (AGENTS.md: no empty catch).
            LOG.debugf("Readiness probe for application %s at %s not ready: %s",
                    app.getApplicationName(), restEndpoint, e.toString());
            return false;
        } finally {
            // Release the socket; isReady() is polled every 2s per starting application.
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public void stopCluster(FlinkApplication app) {
        containerIds.remove(app.getApplicationName());
        Closeable logHandle = logStreams.remove(app.getApplicationName());
        String containerId = app.getContainerId();
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, logHandle);
            LOG.infov("Flink JobManager container {0} stopped and removed", containerId);
        } else {
            // containerId is in-memory bookkeeping and is null after an emulator restart. Fall
            // back to the deterministic container name so an explicit Stop/Delete still removes a
            // container left running from a previous run.
            lifecycleManager.removeIfExists(containerName(app.getApplicationName()));
        }
        app.setContainerId(null);
        app.setRestEndpoint(null);
    }

    /**
     * Stops and removes every running Flink container. Wired into
     * {@code EmulatorLifecycle.onStop()} so containers are torn down on shutdown alongside the
     * other container managers.
     */
    public void stopAll() {
        if (!containerIds.isEmpty()) {
            LOG.infov("Stopping {0} Flink container(s) on shutdown", containerIds.size());
        }
        for (String applicationName : new ArrayList<>(containerIds.keySet())) {
            String containerId = containerIds.remove(applicationName);
            if (containerId == null) {
                continue;
            }
            Closeable logHandle = logStreams.remove(applicationName);
            lifecycleManager.stopAndRemove(containerId, logHandle);
        }
    }
}
