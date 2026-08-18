package io.github.hectorvent.floci.services.docdb.container;


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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DocDbContainerManager {

    private static final Logger LOG = Logger.getLogger(DocDbContainerManager.class);
    private static final int MONGO_PORT = 27017;
    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 200;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, DocDbContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public DocDbContainerManager(ContainerBuilder containerBuilder,
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
     * Attempts {@link #start} and reports the backend as unavailable instead of propagating the
     * failure, when the cause is that no Docker daemon is reachable from Floci — Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start DocumentDB containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public DocDbContainerHandle tryStart(String clusterId, String image, String masterUsername, String masterPassword) {
        try {
            DocDbContainerHandle handle = start(clusterId, image, masterUsername, masterPassword);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). DocumentDB metadata "
                        + "operations keep working and clusters still reach 'available', but they "
                        + "have no backing MongoDB container until a daemon becomes reachable.",
                        e.getMessage());
            }
            return null;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        try {
            lifecycleManager.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.debugv("Docker daemon is not reachable: {0}", e.getMessage());
            return false;
        }
    }

    public DocDbContainerHandle start(String clusterId, String image, String masterUsername, String masterPassword) {
        LOG.infov("Starting DocumentDB container for cluster: {0}", clusterId);

        String containerName = ContainerStorageHelper.resourceName(config, "docdb", null, clusterId);
        lifecycleManager.removeIfExists(containerName);

        List<String> envVars = List.of(
        "MONGO_INITDB_ROOT_USERNAME=" + masterUsername,
        "MONGO_INITDB_ROOT_PASSWORD=" + masterPassword
        );

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().docdb().dockerNetwork())
                .withLogRotation()
                .withEnv(envVars);

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(MONGO_PORT);
        } else {
            specBuilder.withExposedPort(MONGO_PORT);
        }
        

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(MONGO_PORT);

        LOG.infov("DocumentDB container for cluster {0}: {1}", clusterId, endpoint);

        DocDbContainerHandle handle = new DocDbContainerHandle(
                info.containerId(), clusterId, endpoint.host(), endpoint.port());
        activeContainers.put(clusterId, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/docdb/cluster/" + clusterId + "/audit";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "docdb:" + clusterId);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterId, endpoint.host(), endpoint.port());

        return handle;
    }

    public void stop(DocDbContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getClusterId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopAll() {
        List<DocDbContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} DocumentDB container(s) on shutdown", handles.size());
        }
        for (DocDbContainerHandle handle : handles) {
            stop(handle);
        }
    }

    private static void waitForBackendReady(String clusterId, String host, int port) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                if (attempt > 1) {
                    LOG.infov("MongoDB backend ready for cluster {0} after {1} probe attempt(s)",
                            clusterId, attempt);
                }
                return;
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("MongoDB probe for cluster {0} attempt {1}: {2}",
                            clusterId, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while waiting for MongoDB backend " + clusterId, ie);
            }
        }
        throw new RuntimeException(
                "MongoDB backend for cluster " + clusterId + " did not become ready on "
                        + host + ":" + port + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }
}
