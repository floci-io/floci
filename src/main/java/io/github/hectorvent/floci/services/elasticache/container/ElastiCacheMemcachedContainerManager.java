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
 * Manages backend Docker container lifecycle for ElastiCache Memcached clusters.
 * In native (dev) mode, binds container port 11211 to a random host port.
 * In Docker mode, uses the container's internal network IP directly.
 */
@ApplicationScoped
public class ElastiCacheMemcachedContainerManager {

    private static final Logger LOG = Logger.getLogger(ElastiCacheMemcachedContainerManager.class);
    private static final int BACKEND_PORT = 11211;

    private static final int BACKEND_READY_DEADLINE_MS = 60_000;
    private static final int BACKEND_READY_RETRY_MS = 100;
    private static final int BACKEND_PROBE_CONNECT_MS = 2_000;
    private static final byte[] TEXT_VERSION = "version\r\n".getBytes(StandardCharsets.UTF_8);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, ElastiCacheContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public ElastiCacheMemcachedContainerManager(ContainerBuilder containerBuilder,
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

    public ElastiCacheContainerHandle start(String clusterId, String image) {
        LOG.infov("Starting Memcached container for cluster: {0}", clusterId);

        String containerName = "floci-memcached-" + clusterId;
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().elasticache().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(BACKEND_PORT);
        } else {
            specBuilder.withExposedPort(BACKEND_PORT);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(BACKEND_PORT);

        LOG.infov("Memcached backend for cluster {0}: {1}", clusterId, endpoint);

        ElastiCacheContainerHandle handle = new ElastiCacheContainerHandle(
                info.containerId(), clusterId, endpoint.host(), endpoint.port());
        activeContainers.put(clusterId, handle);

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/elasticache/cluster/" + clusterId + "/engine-log";
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "elasticache-memcached:" + clusterId);
        handle.setLogStream(logHandle);

        waitForBackendReady(clusterId, endpoint.host(), endpoint.port());

        return handle;
    }

    private static void waitForBackendReady(String clusterId, String host, int port) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_DEADLINE_MS;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), BACKEND_PROBE_CONNECT_MS);
                s.setSoTimeout(BACKEND_PROBE_CONNECT_MS);
                OutputStream out = s.getOutputStream();
                out.write(TEXT_VERSION);
                out.flush();
                String line = readLine(s.getInputStream());
                if (line.startsWith("VERSION")) {
                    if (attempt > 1) {
                        LOG.infov("Memcached backend ready for cluster {0} after {1} probe attempt(s)", clusterId, attempt);
                    }
                    return;
                }
            } catch (IOException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv("Memcached probe for cluster {0} attempt {1}: {2}", clusterId, attempt, e.getMessage());
                }
            }
            try {
                Thread.sleep(BACKEND_READY_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for Memcached backend " + clusterId, ie);
            }
        }
        throw new RuntimeException(
                "Memcached backend for cluster " + clusterId + " did not become ready on " + host + ":" + port
                        + " within " + BACKEND_READY_DEADLINE_MS + "ms");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // consume \n
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
            LOG.infov("Stopping {0} Memcached container(s) on shutdown", handles.size());
        }
        for (ElastiCacheContainerHandle handle : handles) {
            stop(handle);
        }
    }
}
