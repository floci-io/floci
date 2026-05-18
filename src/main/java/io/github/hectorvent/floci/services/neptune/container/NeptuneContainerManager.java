package io.github.hectorvent.floci.services.neptune.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages backend Docker container lifecycle for Neptune DB clusters.
 * Spins up a TinkerPop Gremlin Server container per cluster.
 */
@ApplicationScoped
public class NeptuneContainerManager {

    private static final Logger LOG = Logger.getLogger(NeptuneContainerManager.class);
    private static final int GREMLIN_PORT = 8182;
    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 200;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, NeptuneContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public NeptuneContainerManager(ContainerBuilder containerBuilder,
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

    public NeptuneContainerHandle start(String clusterId, String image) {
        LOG.infov("Starting Neptune Gremlin Server container for cluster: {0}", clusterId);

        String containerName = "floci-neptune-" + clusterId;
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().neptune().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(GREMLIN_PORT);
        } else {
            specBuilder.withExposedPort(GREMLIN_PORT);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(GREMLIN_PORT);

        LOG.infov("Neptune Gremlin Server for cluster {0}: {1}", clusterId, endpoint);

        NeptuneContainerHandle handle = new NeptuneContainerHandle(
                info.containerId(), clusterId, endpoint.host(), endpoint.port());
        activeContainers.put(clusterId, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/neptune/cluster/" + clusterId + "/gremlin-log";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "neptune:" + clusterId);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterId, endpoint.host(), endpoint.port());

        return handle;
    }

    public void stop(NeptuneContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getClusterId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopAll() {
        List<NeptuneContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} Neptune container(s) on shutdown", handles.size());
        }
        for (NeptuneContainerHandle handle : handles) {
            stop(handle);
        }
    }

    /**
     * Probes the Gremlin Server HTTP endpoint. Gremlin Server responds to a plain HTTP GET
     * on /gremlin with HTTP 400 (not a WebSocket upgrade), confirming the server is listening.
     */
    private static void waitForBackendReady(String clusterId, String host, int port) {
        byte[] probe = "GET /gremlin HTTP/1.1\r\nHost: floci\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                s.setSoTimeout(BACKEND_PROBE_CONNECT_MS);
                OutputStream out = s.getOutputStream();
                out.write(probe);
                out.flush();
                byte[] buf = new byte[32];
                int n = s.getInputStream().read(buf);
                if (n > 0) {
                    if (attempt > 1) {
                        LOG.infov("Gremlin backend ready for cluster {0} after {1} probe attempt(s)",
                                clusterId, attempt);
                    }
                    return;
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("Gremlin probe for cluster {0} attempt {1}: {2}",
                            clusterId, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while waiting for Gremlin backend " + clusterId, ie);
            }
        }
        throw new RuntimeException(
                "Gremlin backend for cluster " + clusterId + " did not become ready on "
                        + host + ":" + port + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }
}
