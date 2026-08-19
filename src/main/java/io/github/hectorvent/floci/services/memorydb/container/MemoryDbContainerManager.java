package io.github.hectorvent.floci.services.memorydb.container;

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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages backend Docker container lifecycle for MemoryDB clusters.
 * In native (dev) mode, binds container port 6379 to a random host port.
 * In Docker mode, uses the container's internal network IP directly.
 */
@ApplicationScoped
public class MemoryDbContainerManager {

    private static final Logger LOG = Logger.getLogger(MemoryDbContainerManager.class);
    private static final int BACKEND_PORT = 6379;

    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 100;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;
    private static final byte[] RESP_PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, MemoryDbContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public MemoryDbContainerManager(ContainerBuilder containerBuilder,
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
     * so nothing changes for a Floci that can start MemoryDB containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public MemoryDbContainerHandle tryStart(String clusterName, String image) {
        try {
            MemoryDbContainerHandle handle = start(clusterName, image);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). MemoryDB metadata "
                        + "operations keep working and clusters still reach 'available', but they "
                        + "have no backing Redis/Valkey container until a daemon becomes reachable.",
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

    public MemoryDbContainerHandle start(String clusterName, String image) {
        LOG.infov("Starting MemoryDB backend container for cluster: {0}", clusterName);

        String containerName = containerName(clusterName);

        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().memorydb().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(BACKEND_PORT);
        } else {
            specBuilder.withExposedPort(BACKEND_PORT);
        }

        ContainerSpec spec = specBuilder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);

        LOG.infov("MemoryDB backend for cluster {0}: {1}", clusterName, endpoint);

        MemoryDbContainerHandle handle = new MemoryDbContainerHandle(
                info.containerId(), clusterName, endpoint.host(), endpoint.port());
        activeContainers.put(clusterName, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/memorydb/cluster/" + clusterName + "/engine-log";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "memorydb:" + clusterName);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterName, endpoint.host(), endpoint.port());

        return handle;
    }

    private static void waitForBackendReady(String clusterName, String host, int port) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                s.setTcpNoDelay(true);
                s.setSoTimeout(BACKEND_PROBE_CONNECT_MS);
                OutputStream out = s.getOutputStream();
                out.write(RESP_PING);
                out.flush();
                String line = readAsciiLineCrLf(s.getInputStream());
                if (line.startsWith("+PONG")) {
                    if (attempt > 1) {
                        LOG.infov("MemoryDB backend ready for cluster {0} after {1} probe attempt(s)", clusterName, attempt);
                    }
                    return;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("MemoryDB backend probe for cluster {0}: unexpected line {1}", clusterName, line);
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("MemoryDB backend probe for cluster {0} attempt {1}: {2}", clusterName, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for MemoryDB backend " + clusterName, ie);
            }
        }
        throw new RuntimeException(
                "MemoryDB backend for cluster " + clusterName + " did not become ready on " + host + ":" + port
                        + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }

    private static String readAsciiLineCrLf(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next != '\n') {
                    throw new IOException("Expected \\n after \\r in RESP line");
                }
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    public void stop(MemoryDbContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getClusterName());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    /**
     * Stops and removes the backend container for a cluster by name, if one exists.
     * Used by the service's provisioning rollback: {@link #start} registers the container in
     * {@code activeContainers} <em>before</em> {@link #waitForBackendReady}, so a readiness
     * timeout throws without ever returning the handle to the caller. In that case rollback
     * can't go through {@link #stop} (it has no handle), so it cleans up by name instead. Falls
     * back to the deterministic container name to catch a container that failed before it was
     * registered. Idempotent — a no-op when nothing is running for the name.
     */
    public void stopByClusterName(String clusterName) {
        MemoryDbContainerHandle handle = activeContainers.get(clusterName);
        if (handle != null) {
            stop(handle);
            return;
        }
        lifecycleManager.removeIfExists(containerName(clusterName));
    }

    private String containerName(String clusterName) {
        return ContainerStorageHelper.resourceName(config, "memorydb", null, clusterName);
    }

    public void stopAll() {
        List<MemoryDbContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} MemoryDB container(s) on shutdown", handles.size());
        }
        for (MemoryDbContainerHandle handle : handles) {
            stop(handle);
        }
    }
}
