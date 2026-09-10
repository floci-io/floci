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
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.BrokerInstance;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the backing RabbitMQ Docker container for an Amazon MQ broker.
 * The AMQP (5672) and management (15672) ports are each published to a host
 * port allocated from the configured ranges, in both topologies. Unlike the
 * proxy-fronted services (RDS, ElastiCache), nothing inside Floci relays AMQP
 * traffic, so when Floci itself runs in Docker the host-port binding is the only
 * way a client on the host can reach the broker (#3240), the same reasoning as
 * {@code OpenSearchDomainManager}. Sibling containers keep reaching the broker
 * over the docker network via the endpoints reported by {@code DescribeBroker}.
 *
 * <p>Unlike {@code RedpandaManager}, RabbitMQ does not advertise a self-perceived
 * address back to clients, so the host port does not need to be passed to the
 * container as a startup argument.
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
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();
    // Host ports reserved for each running broker (in-memory only, like containerIds):
    // released when the container goes away so repeated create/delete cycles do not
    // exhaust the configured ranges.
    private final Map<String, HostPorts> hostPorts = new ConcurrentHashMap<>();

    /** The pair of host ports a broker's container is published on. */
    record HostPorts(int amqp, int console) {}

    @Inject
    public RabbitMqManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           ContainerDetector containerDetector,
                           PortAllocator portAllocator,
                           EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
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
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "amazonmq", broker.getBrokerId(), regionResolver.getAccountId(),
                        regionResolver.getDefaultRegion()));

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

        // A re-provision of the same broker has just removed its old container, so any
        // reservation held for it is stale; hand the ports back before allocating anew.
        releaseHostPorts(broker.getBrokerId());
        HostPorts ports = allocateHostPorts();

        // Publish both ports on the host in every topology (see class javadoc). The
        // endpoints resolved below stay topology-aware: localhost:<hostPort> natively,
        // the container's network IP when Floci runs in Docker.
        specBuilder.withPortBinding(AMQP_PORT, ports.amqp())
                .withPortBinding(MGMT_PORT, ports.console());

        ContainerInfo info;
        try {
            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager, config,
                        "amazonmq", broker.getVolumeId(), broker.getBrokerId(),
                        "/var/lib/rabbitmq");
            } else {
                String hostDataPath = ContainerStorageHelper.hostResourcePath(config, "amazonmq", broker.getBrokerId())
                        .toAbsolutePath().toString();
                if (!containerDetector.isRunningInContainer()) {
                    ContainerStorageHelper.ensureHostDir(hostDataPath);
                }
                specBuilder.withBind(hostDataPath, "/var/lib/rabbitmq");
            }

            ContainerSpec spec = specBuilder.build();
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            // Roll back a partially-created container so a failed CreateBroker
            // does not leave an orphaned container behind, and hand the reserved
            // ports back so the failure does not leak them.
            lifecycleManager.removeIfExists(containerName);
            portAllocator.release(ports.amqp());
            portAllocator.release(ports.console());
            throw e;
        }
        broker.setContainerId(info.containerId());
        containerIds.put(broker.getBrokerId(), info.containerId());
        hostPorts.put(broker.getBrokerId(), ports);

        EndpointInfo amqp = info.getEndpoint(AMQP_PORT);
        EndpointInfo mgmt = info.getEndpoint(MGMT_PORT);
        BrokerInstance instance = new BrokerInstance(
                "http://" + mgmt.host() + ":" + mgmt.port(),
                List.of("amqp://" + amqp.host() + ":" + amqp.port()),
                amqp.host());
        broker.setBrokerInstances(new java.util.ArrayList<>(List.of(instance)));
        LOG.infov("RabbitMQ container {0} started for broker {1}: amqp={2} (host ports amqp={3}, console={4})",
                info.containerId(), broker.getBrokerName(), instance.getEndpoints().get(0),
                String.valueOf(ports.amqp()), String.valueOf(ports.console()));

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
        releaseHostPorts(broker.getBrokerId());
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
            releaseHostPorts(brokerId);
            if (containerId == null) {
                continue;
            }
            Closeable logHandle = logStreams.remove(brokerId);
            lifecycleManager.stopAndRemove(containerId, logHandle);
        }
    }

    private HostPorts allocateHostPorts() {
        EmulatorConfig.AmazonMqServiceConfig mq = config.services().amazonmq();
        int amqp = portAllocator.allocate(mq.amqpHostPortBase(), mq.amqpHostPortMax());
        int console;
        try {
            console = portAllocator.allocate(mq.consoleHostPortBase(), mq.consoleHostPortMax());
        } catch (RuntimeException e) {
            portAllocator.release(amqp);
            throw e;
        }
        return new HostPorts(amqp, console);
    }

    private void releaseHostPorts(String brokerId) {
        HostPorts ports = hostPorts.remove(brokerId);
        if (ports != null) {
            portAllocator.release(ports.amqp());
            portAllocator.release(ports.console());
        }
    }

    public void removeBrokerStorage(Broker broker) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "amazonmq", broker.getVolumeId(), broker.getBrokerId());
    }
}
