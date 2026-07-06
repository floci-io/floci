package io.github.hectorvent.floci.services.amazonmq.container;

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
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerInstance;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
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

/**
 * Manages the backing RabbitMQ Docker container for an Amazon MQ broker.
 * In native (dev) mode it publishes the AMQP (5672) and management (15672)
 * ports to dynamic host ports; in Docker mode sibling containers reach the
 * broker over the docker network.
 *
 * <p>Unlike {@code RedpandaManager}, RabbitMQ does not advertise a self-perceived
 * address back to clients, so no pre-allocated/advertised port is needed —
 * dynamic ports (the ElastiCache pattern) are enough.
 */
@ApplicationScoped
public class RabbitMqManager {

    private static final Logger LOG = Logger.getLogger(RabbitMqManager.class);
    private static final int AMQP_PORT = 5672;
    private static final int MGMT_PORT = 15672;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();

    @Inject
    public RabbitMqManager(ContainerBuilder containerBuilder,
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

    /** Deterministic container name for a broker, stable across emulator restarts. */
    private static String containerName(String brokerId) {
        return "floci-amazonmq-" + brokerId;
    }

    public void startContainer(Broker broker) {
        String image = config.services().amazonmq().defaultImage();
        String containerName = containerName(broker.getBrokerId());
        LOG.infov("Starting RabbitMQ container for broker {0} using image {1}",
                broker.getBrokerName(), image);

        // Remove any stale container with the same name (e.g. leftover from a crash).
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation();

        // Seed the broker's admin user. RabbitMQ's built-in `guest` user is
        // loopback-only, so it cannot authenticate over the mapped host port; a user
        // created via RABBITMQ_DEFAULT_USER/PASS is not loopback-restricted and can.
        if (!broker.getUsers().isEmpty()) {
            MqUser admin = broker.getUsers().get(0);
            if (admin.getPassword() == null) {
                // The password is in-memory only (a secret we do not persist). It is
                // present when CreateBroker provisions the container but null for a
                // broker reloaded from persistent storage. Fail loudly rather than
                // create a container seeded with a null credential.
                throw new IllegalStateException("Admin password unavailable for broker "
                        + broker.getBrokerId() + " (secrets are not persisted); cannot "
                        + "provision the RabbitMQ container");
            }
            specBuilder.withEnv("RABBITMQ_DEFAULT_USER", admin.getUsername());
            specBuilder.withEnv("RABBITMQ_DEFAULT_PASS", admin.getPassword());
        }

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(AMQP_PORT).withDynamicPort(MGMT_PORT);
        } else {
            specBuilder.withExposedPort(AMQP_PORT).withExposedPort(MGMT_PORT);
        }

        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager,
                    "amazonmq", broker.getVolumeId(), broker.getBrokerId(),
                    "/var/lib/rabbitmq");
        } else {
            String hostDataPath = Path.of(config.storage().hostPersistentPath(), "amazonmq", broker.getBrokerId())
                    .toAbsolutePath().toString();
            ContainerStorageHelper.ensureHostDir(hostDataPath);
            specBuilder.withBind(hostDataPath, "/var/lib/rabbitmq");
        }

        ContainerSpec spec = specBuilder.build();

        ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            // Roll back a partially-created container so a failed CreateBroker
            // does not leave an orphaned container behind.
            lifecycleManager.removeIfExists(containerName);
            throw e;
        }
        broker.setContainerId(info.containerId());
        containerIds.put(broker.getBrokerId(), info.containerId());

        EndpointInfo amqp = info.getEndpoint(AMQP_PORT);
        EndpointInfo mgmt = info.getEndpoint(MGMT_PORT);
        BrokerInstance instance = new BrokerInstance(
                "http://" + mgmt.host() + ":" + mgmt.port(),
                List.of("amqp://" + amqp.host() + ":" + amqp.port()),
                amqp.host());
        broker.setBrokerInstances(new java.util.ArrayList<>(List.of(instance)));
        LOG.infov("RabbitMQ container {0} started for broker {1}: amqp={2}",
                info.containerId(), broker.getBrokerName(), instance.getEndpoints().get(0));

        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/amazonmq/broker/" + broker.getBrokerId();
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        Closeable logHandle = logStreamer.attach(
                info.containerId(), logGroup, logStream, region, "amazonmq:" + broker.getBrokerId());
        if (logHandle != null) {
            logStreams.put(broker.getBrokerId(), logHandle);
        }
    }

    /**
     * Ready once the RabbitMQ management UI answers on its port. The management
     * plugin starts after the broker core, so a 200 here implies AMQP is also up.
     * The root path needs no auth, sidestepping RabbitMQ's loopback-only guest user.
     */
    public boolean isReady(Broker broker) {
        if (broker.getBrokerInstances().isEmpty()) {
            return false;
        }
        String consoleUrl = broker.getBrokerInstances().get(0).getConsoleURL();
        if (consoleUrl == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(consoleUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            // Expected while the broker is still booting (connection refused/timeout).
            // Logged at debug so a genuinely stuck probe is diagnosable without
            // spamming this 2s-interval hot path (AGENTS.md: no empty catch).
            LOG.debugf("Readiness probe for broker %s at %s not ready: %s",
                    broker.getBrokerId(), consoleUrl, e.toString());
            return false;
        } finally {
            // Release the socket; isReady() is polled every 2s per pending broker.
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public void stopContainer(Broker broker) {
        containerIds.remove(broker.getBrokerId());
        Closeable logHandle = logStreams.remove(broker.getBrokerId());
        String containerId = broker.getContainerId();
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, logHandle);
            LOG.infov("RabbitMQ container {0} stopped and removed", containerId);
        } else {
            // containerId is in-memory bookkeeping and is null after an emulator
            // restart (it is intentionally not persisted; see Broker). Fall back to
            // the deterministic container name so an explicit DeleteBroker still
            // removes a container left running from a previous run.
            lifecycleManager.removeIfExists(containerName(broker.getBrokerId()));
        }
    }

    /**
     * Stops and removes every running broker container. Wired into
     * {@code EmulatorLifecycle.onStop()} so containers are torn down on shutdown
     * alongside the other container managers.
     */
    public void stopAll() {
        if (!containerIds.isEmpty()) {
            LOG.infov("Stopping {0} RabbitMQ container(s) on shutdown", containerIds.size());
        }
        for (String brokerId : new ArrayList<>(containerIds.keySet())) {
            String containerId = containerIds.remove(brokerId);
            if (containerId == null) {
                continue;
            }
            Closeable logHandle = logStreams.remove(brokerId);
            lifecycleManager.stopAndRemove(containerId, logHandle);
        }
    }

    public void removeBrokerStorage(Broker broker) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "amazonmq", broker.getVolumeId(), broker.getBrokerId());
    }
}
