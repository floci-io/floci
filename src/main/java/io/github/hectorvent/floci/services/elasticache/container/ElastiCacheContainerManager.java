package io.github.hectorvent.floci.services.elasticache.container;

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
 * Manages backend Docker container lifecycle for ElastiCache replication groups.
 * In native (dev) mode, binds container port 6379 to a random host port.
 * In Docker mode, uses the container's internal network IP directly.
 */
@ApplicationScoped
public class ElastiCacheContainerManager {

    private static final Logger LOG = Logger.getLogger(ElastiCacheContainerManager.class);
    private static final int BACKEND_PORT = 6379;

    /**
     * Docker can publish a host port before Valkey inside the container is listening. Without a probe,
     * the auth proxy may connect, forward PING, and block on PONG until the client times out.
     */
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
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheContainerManager(ContainerBuilder containerBuilder,
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

    public ElastiCacheContainerHandle start(String groupId, String image) {
        LOG.infov("Starting ElastiCache backend container for group: {0}", groupId);

        String containerName = ContainerStorageHelper.resourceName(config, "valkey", null, groupId);

        // Remove any stale container with the same name
        lifecycleManager.removeIfExists(containerName);

        // Build container spec. Only publish the backend port to the host in
        // native mode — in Docker mode the JVM reaches the container via its
        // network IP, no host binding needed.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("VALKEY_EXTRA_FLAGS", "--loglevel verbose")
                .withDockerNetwork(config.services().elasticache().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(BACKEND_PORT);
        } else {
            specBuilder.withExposedPort(BACKEND_PORT);
        }

        ContainerSpec spec = specBuilder.build();

        // Create and start container
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);

        LOG.infov("ElastiCache backend for group {0}: {1}", groupId, endpoint);

        ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                info.containerId(), groupId, endpoint.host(), endpoint.port());
        activeContainers.put(groupId, handle);

        // Attach log streaming
        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/elasticache/cluster/" + groupId + "/engine-log";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "elasticache:" + groupId);
        handle.setLogStream(logHandle);

        waitForBackendReady(groupId, endpoint.host(), endpoint.port());

        return handle;
    }

    private static void waitForBackendReady(String groupId, String host, int port) {
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
                        LOG.infov("ElastiCache backend ready for group {0} after {1} probe attempt(s)", groupId, attempt);
                    }
                    return;
                }
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("ElastiCache backend probe for group {0}: unexpected line {1}", groupId, line);
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("ElastiCache backend probe for group {0} attempt {1}: {2}", groupId, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for ElastiCache backend " + groupId, ie);
            }
        }
        throw new RuntimeException(
                "ElastiCache backend for group " + groupId + " did not become ready on " + host + ":" + port
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

    public void stop(ElastiCacheContainerHandle handle) {
        if (handle == null) {
            return;
        }
        activeContainers.remove(handle.getGroupId());
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    public void stopAll() {
        List<ElastiCacheContainerHandle> handles = new ArrayList<>(activeContainers.values());
        if (!handles.isEmpty()) {
            LOG.infov("Stopping {0} ElastiCache container(s) on shutdown", handles.size());
        }
        for (ElastiCacheContainerHandle handle : handles) {
            stop(handle);
        }
    }
}
