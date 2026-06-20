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
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
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
 * Spins up one graph database container per cluster — a TinkerPop Gremlin Server
 * ({@code db-type=gremlin}) or Neo4j ({@code db-type=neo4j}, openCypher over Bolt).
 */
@ApplicationScoped
public class NeptuneContainerManager {

    private static final Logger LOG = Logger.getLogger(NeptuneContainerManager.class);
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

    public NeptuneContainerHandle start(String clusterId, String image, NeptuneDbType dbType) {
        LOG.infov("Starting Neptune backend container ({0}) for cluster: {1}", dbType, clusterId);

        int backendPort = dbType.backendPort();
        String containerName = "floci-neptune-" + clusterId;
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(backendEnv(dbType))
                .withDockerNetwork(config.services().neptune().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(backendPort);
        } else {
            specBuilder.withExposedPort(backendPort);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(backendPort);

        LOG.infov("Neptune {0} backend for cluster {1}: {2}", dbType, clusterId, endpoint);

        NeptuneContainerHandle handle = new NeptuneContainerHandle(
                info.containerId(), clusterId, endpoint.host(), endpoint.port());
        activeContainers.put(clusterId, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/neptune/cluster/" + clusterId + "/" + dbType.name().toLowerCase() + "-log";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "neptune:" + clusterId);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterId, dbType, endpoint.host(), endpoint.port());

        return handle;
    }

    /**
     * Environment for the backend container. Neo4j's Bolt endpoint requires auth to be
     * explicitly disabled so the transparent proxy can relay openCypher sessions without
     * brokering credentials — matching Neptune, which authenticates at the AWS edge (IAM),
     * not at the graph protocol.
     */
    private static List<String> backendEnv(NeptuneDbType dbType) {
        return switch (dbType) {
            case GREMLIN -> List.of();
            case NEO4J -> List.of("NEO4J_AUTH=none");
        };
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
     * Bolt handshake magic preamble {@code 0x6060B017} followed by four 4-byte version
     * proposals (highest first). Neo4j replies with the 4-byte agreed version once its Bolt
     * connector is accepting connections, which is the readiness signal we probe for.
     */
    private static final byte[] BOLT_HANDSHAKE = {
            0x60, 0x60, (byte) 0xB0, 0x17,   // magic preamble
            0x00, 0x00, 0x00, 0x05,           // propose Bolt 5.0
            0x00, 0x00, 0x00, 0x04,           // propose Bolt 4.0
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    /**
     * Waits until the backend's native data-plane protocol is answering. The probe payload and
     * expected response differ per engine:
     * <ul>
     *   <li>Gremlin Server replies to a plain HTTP {@code GET /gremlin} (HTTP 400, not a
     *       WebSocket upgrade), confirming it is listening.</li>
     *   <li>Neo4j replies to the Bolt handshake with a 4-byte agreed version.</li>
     * </ul>
     */
    private static void waitForBackendReady(String clusterId, NeptuneDbType dbType, String host, int port) {
        byte[] probe = switch (dbType) {
            case GREMLIN -> "GET /gremlin HTTP/1.1\r\nHost: floci\r\n\r\n".getBytes(StandardCharsets.UTF_8);
            case NEO4J -> BOLT_HANDSHAKE;
        };
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
                if (probeIndicatesReady(dbType, buf, n)) {
                    if (attempt > 1) {
                        LOG.infov("Neptune {0} backend ready for cluster {1} after {2} probe attempt(s)",
                                dbType, clusterId, attempt);
                    }
                    return;
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("Neptune {0} probe for cluster {1} attempt {2}: {3}",
                            dbType, clusterId, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while waiting for Neptune " + dbType + " backend " + clusterId, ie);
            }
        }
        throw new RuntimeException(
                "Neptune " + dbType + " backend for cluster " + clusterId + " did not become ready on "
                        + host + ":" + port + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }

    /**
     * Decides whether a probe response signals readiness. For Gremlin any reply confirms the
     * server is listening. For Neo4j the Bolt handshake reply is the 4-byte agreed version;
     * an all-zero version ({@code 0x00000000}) means the connector answered but rejected every
     * proposed Bolt version, so the backend is reachable but unusable — treat it as not-ready
     * and keep retrying rather than handing back a cluster whose sessions will fail.
     */
    private static boolean probeIndicatesReady(NeptuneDbType dbType, byte[] response, int n) {
        if (n <= 0) {
            return false;
        }
        return switch (dbType) {
            case GREMLIN -> true;
            case NEO4J -> n >= 4 && (response[0] | response[1] | response[2] | response[3]) != 0;
        };
    }
}
