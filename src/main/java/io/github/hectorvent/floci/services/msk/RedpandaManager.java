package io.github.hectorvent.floci.services.msk;

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
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RedpandaManager {

    private static final Logger LOG = Logger.getLogger(RedpandaManager.class);
    private static final int KAFKA_PORT = 9092;
    static final int ADMIN_PORT = 9644;

    // Redpanda's Admin API exposes readiness at /v1/status/ready. /ready always returns 404
    private static final String ADMIN_READY_PATH = "/v1/status/ready";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final PortAllocator portAllocator;
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();

    @Inject
    public RedpandaManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           ContainerDetector containerDetector,
                           EmulatorConfig config,
                           RegionResolver regionResolver,
                           PortAllocator portAllocator) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.portAllocator = portAllocator;
    }

    public void startContainer(MskCluster cluster) {
        String image = config.services().msk().defaultImage();
        LOG.infov("Starting Redpanda container for MSK cluster: {0} using image {1}", cluster.getClusterName(), image);

        String containerName = ContainerStorageHelper.resourceName(config, "msk", cluster.getVolumeId(), cluster.getClusterName());

        // Cleanup stale container
        lifecycleManager.removeIfExists(containerName);

        // Redpanda embeds broker addresses in Kafka protocol responses (Metadata,
        // FindCoordinator, ...) and clients reconnect directly to whatever address
        // it advertises. Without --advertise-kafka-addr, Redpanda advertises its own
        // self-perceived loopback (127.0.0.1:9092), which no client outside the
        // container can ever reach — so we must resolve and pass the externally
        // reachable address up front. The host port has to be known before the
        // container starts (the flag is a startup arg), so in native mode we
        // pre-allocate it ourselves instead of letting Docker assign one dynamically.
        String kafkaAdvertiseAddr;
        int kafkaHostPort = 0;
        if (!containerDetector.isRunningInContainer()) {
            kafkaHostPort = portAllocator.allocate(
                    config.services().msk().kafkaHostPortBase(),
                    config.services().msk().kafkaHostPortMax());
            kafkaAdvertiseAddr = "localhost:" + kafkaHostPort;
        } else {
            // Sibling containers on the docker network resolve the broker via its
            // container name through Docker's embedded DNS.
            kafkaAdvertiseAddr = containerName + ":" + KAFKA_PORT;
        }

        // Build command
        List<String> cmd = new ArrayList<>(List.of(
                "redpanda", "start", "--overprovisioned", "--smp", "1",
                "--memory", "512M", "--reserve-memory", "0M",
                "--advertise-kafka-addr", kafkaAdvertiseAddr));

        // Build container spec. Publish Kafka/admin ports to the host only in
        // native mode; in Docker mode producers/consumers reach the broker via
        // the docker network IP resolved from container inspect.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation();

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withPortBinding(KAFKA_PORT, kafkaHostPort).withDynamicPort(ADMIN_PORT);
        } else {
            specBuilder.withExposedPort(KAFKA_PORT).withExposedPort(ADMIN_PORT);
        }

        // Handle persistence mounting
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager, config,
                    "msk", cluster.getVolumeId(), cluster.getClusterName(),
                    "/var/lib/redpanda/data");
        } else {
            // Legacy host-path mode: host-persistent-path is an absolute path
            String hostDataPath = ContainerStorageHelper.hostResourcePath(config, "msk", cluster.getClusterName())
                    .toAbsolutePath().toString();
            if (!containerDetector.isRunningInContainer()) {
                ContainerStorageHelper.ensureHostDir(hostDataPath);
            }
            specBuilder.withBind(hostDataPath, "/var/lib/redpanda/data");
        }

        specBuilder.withCmd(cmd);
        ContainerSpec spec = specBuilder.build();

        // Create and start container
        ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            if (!containerDetector.isRunningInContainer()) {
                portAllocator.release(kafkaHostPort);
            }
            throw e;
        }
        cluster.setContainerId(info.containerId());

        // Resolve endpoints
        EndpointInfo kafkaEndpoint = info.getEndpoint(KAFKA_PORT);

        cluster.setBootstrapBrokers(kafkaEndpoint.host() + ":" + kafkaEndpoint.port());
        LOG.infov("Redpanda container {0} started. Bootstrap: {1}", info.containerId(), cluster.getBootstrapBrokers());

        // Attach log streaming (new feature)
        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/msk/cluster/" + cluster.getClusterName();
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "msk:" + cluster.getClusterName());
        if (logHandle != null) {
            logStreams.put(cluster.getClusterName(), logHandle);
        }
    }

    public boolean isReady(MskCluster cluster) {
        String bootstrap = cluster.getBootstrapBrokers();
        if (bootstrap == null) {
            return false;
        }

        // Derive admin URL from the container
        String adminUrl;
        if (!containerDetector.isRunningInContainer()) {
            var dockerClient = lifecycleManager.getDockerClient();
            var inspect = dockerClient.inspectContainerCmd(cluster.getContainerId()).exec();
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(ADMIN_PORT));
            if (binding != null && binding.length > 0) {
                adminUrl = "http://localhost:" + binding[0].getHostPortSpec() + ADMIN_READY_PATH;
            } else {
                return false;
            }
        } else {
            String containerIp = bootstrap.split(":")[0];
            adminUrl = "http://" + containerIp + ":" + ADMIN_PORT + ADMIN_READY_PATH;
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(adminUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void stopContainer(MskCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }

        // Close log stream
        Closeable logHandle = logStreams.remove(cluster.getClusterName());

        lifecycleManager.stopAndRemove(cluster.getContainerId(), logHandle);
        LOG.infov("Redpanda container {0} stopped and removed", cluster.getContainerId());

        releaseKafkaHostPort(cluster);
    }

    private void releaseKafkaHostPort(MskCluster cluster) {
        if (containerDetector.isRunningInContainer()) {
            return;
        }
        String bootstrap = cluster.getBootstrapBrokers();
        if (bootstrap == null) {
            return;
        }
        int separatorIndex = bootstrap.lastIndexOf(':');
        if (separatorIndex < 0) {
            return;
        }
        try {
            portAllocator.release(Integer.parseInt(bootstrap.substring(separatorIndex + 1)));
        } catch (NumberFormatException e) {
            // bootstrap string not in host:port form - nothing to release
        }
    }

    public void removeClusterStorage(MskCluster cluster) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "msk", cluster.getVolumeId(), cluster.getClusterName());
    }
}
