package io.floci.aws.services.neptune.container;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RetryingTarCopier;
import io.floci.aws.services.neptune.model.NeptuneDbType;
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
    /**
     * Seconds a container has to stop before Docker kills it. Gremlin Server saves its graph only once its network
     * threads have shut down gracefully, a few seconds after the stop signal, and Neo4j flushes its store on the way
     * down, so they get longer than the usual grace.
     */
    private static final int STOP_TIMEOUT_SECONDS = 30;
    /** Neo4j's data directory, which its entrypoint gives to the neo4j user. */
    private static final String NEO4J_DATA_PATH = "/data";
    /** The Gremlin Server image's data directory, owned by the user it runs as, so a fresh volume there is too. */
    private static final String GREMLIN_DATA_PATH = "/opt/gremlin-server/data";
    private static final String GREMLIN_CONF_PATH = "/opt/gremlin-server/conf";
    /** The graph properties the image's {@code gremlin-server.yaml} opens its one graph with. */
    private static final String GREMLIN_GRAPH_PROPERTIES = "tinkergraph-empty.properties";
    /**
     * The image's TinkerGraph properties, saving the graph to the data directory when the graph closes, which
     * Gremlin Server's shutdown hook does on the stop signal, and loading it from there when it opens.
     */
    private static final String PERSISTENT_TINKERGRAPH = String.join("\n",
            "gremlin.graph=org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph",
            "gremlin.tinkergraph.vertexIdManager=LONG",
            "gremlin.tinkergraph.graphLocation=" + GREMLIN_DATA_PATH + "/floci-graph.kryo",
            "gremlin.tinkergraph.graphFormat=gryo",
            "");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, NeptuneContainerHandle> activeContainers = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

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

    /**
     * Attempts {@link #start} and reports the backend as unavailable instead of propagating the
     * failure, when the cause is that no Docker daemon is reachable from Floci — Floci running
     * inside Docker without a mounted socket, or a stopped daemon on the host. A failure raised
     * while the daemon <em>is</em> reachable is a genuine container problem and still propagates,
     * so nothing changes for a Floci that can start Neptune containers.
     *
     * @return the container handle, or {@code null} when no Docker daemon is reachable
     */
    public NeptuneContainerHandle tryStart(String clusterId, String image, NeptuneDbType dbType,
                                           String accountId, String region, String volumeId) {
        try {
            NeptuneContainerHandle handle = start(clusterId, image, dbType, accountId, region, volumeId);
            dockerUnavailableLogged = false;
            return handle;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). Neptune metadata "
                        + "operations keep working and clusters still reach ''available'', but they "
                        + "have no backing graph database container until a daemon becomes "
                        + "reachable.", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Probes the configured Docker endpoint, which is how a missing daemon is told apart from a
     * container that failed for its own reasons.
     */
    public boolean isDockerReachable() {
        return lifecycleManager.isDockerReachable();
    }

    /**
     * Starts the cluster's graph database container. With a {@code volumeId} its data lives in the cluster's own
     * volume, or its directory under the host persistent path, which outlives the container: Neo4j's data directory,
     * or, for Gremlin Server, the file its TinkerGraph is saved to when the server stops and loaded from when it
     * starts. Without one the graph goes when the container does.
     */
    public NeptuneContainerHandle start(String clusterId, String image, NeptuneDbType dbType,
                                        String accountId, String region, String volumeId) {
        LOG.infov("Starting Neptune backend container ({0}) for cluster: {1}", dbType, clusterId);

        int backendPort = dbType.backendPort();
        String identity = resourceIdentity(accountId, region, clusterId);
        String containerName = containerName(identity);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(backendEnv(dbType))
                .withDockerNetwork(config.services().neptune().dockerNetwork())
                .withLogRotation()
                .withPushedLogs()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "neptune", clusterId, accountId, region));

        if (volumeId != null) {
            String dataPath = dbType == NeptuneDbType.NEO4J ? NEO4J_DATA_PATH : GREMLIN_DATA_PATH;
            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                ContainerStorageHelper.applyNamedVolume(specBuilder, lifecycleManager, volumeName(volumeId), dataPath);
            } else {
                String path = ContainerStorageHelper.hostResourcePath(config, "neptune", volumeId).toString();
                ContainerStorageHelper.ensureHostDir(containerDetector, path);
                specBuilder.withBind(path, dataPath);
                if (dbType == NeptuneDbType.GREMLIN) {
                    // A host directory belongs to its creator, not to the image's gremlin user, so the server runs
                    // as root to write the graph there; Neo4j's entrypoint hands its data directory over itself.
                    specBuilder.withUser("0");
                }
            }
        }

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(backendPort);
        } else {
            specBuilder.withExposedPort(backendPort);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = volumeId != null && dbType == NeptuneDbType.GREMLIN
                ? lifecycleManager.createAndStart(spec, containerId -> persistGraph(containerId, clusterId))
                : lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(backendPort);

        LOG.infov("Neptune {0} backend for cluster {1}: {2}", dbType, clusterId, endpoint);

        NeptuneContainerHandle handle = new NeptuneContainerHandle(
                info.containerId(), identity, endpoint.host(), endpoint.port());
        activeContainers.put(identity, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/neptune/cluster/" + identity + "/" + dbType.name().toLowerCase() + "-log";
        String logStream = logStreamer.generateLogStreamName(shortId);

        Closeable logHandle = logStreamer.attachPolled(
                info.containerId(), logGroup, logStream, region, "neptune:" + identity);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterId, dbType, endpoint.host(), endpoint.port());

        return handle;
    }

    /**
     * Gives the created container's TinkerGraph the properties that keep it in the data directory. An image laid
     * out otherwise than the default one keeps its graph in memory, as it did before, rather than failing to start.
     */
    private void persistGraph(String containerId, String clusterId) {
        try {
            RetryingTarCopier.copyBytes(lifecycleManager.getDockerClient(), containerId, GREMLIN_CONF_PATH,
                    GREMLIN_GRAPH_PROPERTIES, PERSISTENT_TINKERGRAPH.getBytes(StandardCharsets.UTF_8), 0644);
        } catch (RuntimeException e) {
            LOG.warnv("Neptune cluster {0} keeps its graph in memory only: its image has no {1} to configure ({2})",
                    clusterId, GREMLIN_CONF_PATH + "/" + GREMLIN_GRAPH_PROPERTIES, e.getMessage());
        }
    }

    /**
     * Removes a deleted cluster's data volume, unless the storage policy keeps volumes on delete.
     * A directory under the host persistent path is left in place.
     */
    public void removeStorage(String volumeId) {
        if (volumeId != null && ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, volumeName(volumeId),
                    storageMode(config));
        }
    }

    /** The storage mode Neptune's records are kept under: its own, or the global one when it names none. */
    public static String storageMode(EmulatorConfig config) {
        return config.storage().services().neptune().mode().orElse(config.storage().mode());
    }

    /** Removes the data volume of a cluster that never reached its caller, whatever the storage policy. */
    public void discardStorage(String volumeId) {
        if (volumeId != null && ContainerStorageHelper.isNamedVolumeMode(config)) {
            lifecycleManager.removeVolume(volumeName(volumeId));
        }
    }

    private String volumeName(String volumeId) {
        return ContainerStorageHelper.dockerName(config, "neptune-" + volumeId);
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
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream(), STOP_TIMEOUT_SECONDS);
    }

    /**
     * Stops and removes the backend container for a cluster by id, if one exists.
     * Used by the service's provisioning rollback: {@link #start} registers the container in
     * {@code activeContainers} <em>before</em> {@link #waitForBackendReady}, so a readiness
     * timeout throws without ever returning the handle to the caller. In that case rollback
     * can't go through {@link #stop} (it has no handle), so it cleans up by id instead. Falls
     * back to the deterministic container name to catch a container that failed before it was
     * registered. Idempotent — a no-op when nothing is running for the id.
     */
    public void stopByClusterId(String clusterId) {
        NeptuneContainerHandle handle = activeContainers.get(clusterId);
        if (handle != null) {
            stop(handle);
            return;
        }
        lifecycleManager.removeIfExists(containerName(clusterId));
    }

    private static String resourceIdentity(String accountId, String region, String clusterId) {
        return accountId + "-" + region + "-" + clusterId;
    }

    private String containerName(String clusterId) {
        return ContainerStorageHelper.resourceName(config, "neptune", null, clusterId);
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
