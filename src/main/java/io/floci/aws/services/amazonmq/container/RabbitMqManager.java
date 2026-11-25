package io.floci.aws.services.amazonmq.container;

import com.github.dockerjava.api.model.Container;
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
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.BrokerInstance;
import io.floci.aws.services.amazonmq.model.MqUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
    /**
     * The node name every broker container runs as. RabbitMQ keeps its data under
     * {@code /var/lib/rabbitmq/mnesia/<node name>}, and the name defaults to the hostname, which Docker sets to the
     * container's id: without a fixed name, a new container on the same volume starts an empty node.
     */
    static final String NODE_NAME = "rabbit@localhost";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    private final Map<String, String> containerIds = new ConcurrentHashMap<>();
    // Host ports this process reserved for each running broker: released when the container
    // goes away so repeated create/delete cycles do not exhaust the configured ranges. The
    // broker records them too, so a restart can publish the broker on the same ones.
    private final Map<String, HostPorts> hostPorts = new ConcurrentHashMap<>();

    /** The pair of host ports a broker's container is published on. */
    record HostPorts(int amqp, int console) {}

    // Set by stopAll(). A start still running when shutdown removes the containers, as a restore's can be while an
    // image pulls, removes its own container rather than leave it behind after the cleanup.
    private boolean shuttingDown;

    /** Thrown by a start that shutdown overtook; its container, if any, is already removed. */
    public static final class ShuttingDownException extends IllegalStateException {
        public ShuttingDownException(String brokerId) {
            super("Floci is shutting down; not starting the RabbitMQ container of broker " + brokerId);
        }
    }

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
    private String containerName(String brokerId) {
        return ContainerStorageHelper.dockerName(config, "amazonmq-" + brokerId);
    }

    /** Starts the container of a new broker, seeding its admin user. */
    public void startContainer(Broker broker) {
        start(broker, true);
    }

    /**
     * Starts the container of a broker that ran before Floci restarted, on its volume, where its node already holds
     * its users, vhosts and messages, so nothing is seeded: the admin password is never stored. It is published on
     * the host ports it had when they are still free.
     */
    public void restoreContainer(Broker broker) {
        start(broker, false);
    }

    private void start(Broker broker, boolean seedAdmin) {
        if (isShuttingDown()) {
            throw new ShuttingDownException(broker.getBrokerId());
        }
        String image = config.services().amazonmq().defaultImage();
        String containerName = containerName(broker.getBrokerId());
        LOG.infov("Starting RabbitMQ container for broker {0} using image {1}",
                broker.getBrokerName(), image);

        // Remove any stale container with the same name (e.g. leftover from a crash).
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .withPushedLogs()
                .withEnv("RABBITMQ_NODENAME", NODE_NAME)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "amazonmq", broker.getBrokerId(), brokerAccount(broker), brokerRegion(broker)));

        // Seed the broker's admin user. RabbitMQ's built-in `guest` user is
        // loopback-only, so it cannot authenticate over the mapped host port; a user
        // created via RABBITMQ_DEFAULT_USER/PASS is not loopback-restricted and can.
        if (seedAdmin && !broker.getUsers().isEmpty()) {
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
        HostPorts ports = allocateHostPorts(broker);

        // Publish both ports on the host in every topology (see class javadoc). The
        // endpoints resolved below stay topology-aware: localhost:<hostPort> natively,
        // the container's network IP when Floci runs in Docker.
        specBuilder.withPortBinding(AMQP_PORT, ports.amqp())
                .withPortBinding(MGMT_PORT, ports.console());

        ContainerInfo info;
        try {
            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                ContainerStorageHelper.applyNamedVolume(specBuilder, lifecycleManager,
                        resolveVolumeName(broker), "/var/lib/rabbitmq");
            } else {
                String hostDataPath = ContainerStorageHelper.hostResourcePath(config, "amazonmq", broker.getBrokerId())
                        .toAbsolutePath().toString();
                ContainerStorageHelper.ensureHostDir(containerDetector, hostDataPath);
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
        synchronized (this) {
            if (shuttingDown) {
                lifecycleManager.stopAndRemove(info.containerId(), null);
                portAllocator.release(ports.amqp());
                portAllocator.release(ports.console());
                throw new ShuttingDownException(broker.getBrokerId());
            }
            containerIds.put(broker.getBrokerId(), info.containerId());
            hostPorts.put(broker.getBrokerId(), ports);
        }
        try {
            EndpointInfo amqp = info.getEndpoint(AMQP_PORT);
            EndpointInfo mgmt = info.getEndpoint(MGMT_PORT);
            BrokerInstance instance = new BrokerInstance(
                    "http://" + mgmt.host() + ":" + mgmt.port(),
                    List.of("amqp://" + amqp.host() + ":" + amqp.port()),
                    amqp.host());
            attachLogs(broker, info.containerId());
            broker.setContainerId(info.containerId());
            broker.setBrokerInstances(new ArrayList<>(List.of(instance)));
            LOG.infov("RabbitMQ container {0} started for broker {1}: amqp={2} (host ports amqp={3}, console={4})",
                    info.containerId(), broker.getBrokerName(), instance.getEndpoints().get(0),
                    String.valueOf(ports.amqp()), String.valueOf(ports.console()));
        } catch (RuntimeException e) {
            // A container that started but could not be wired up is not left running behind a failed broker.
            containerIds.remove(broker.getBrokerId());
            releaseHostPorts(broker.getBrokerId());
            lifecycleManager.stopAndRemove(info.containerId(), logStreams.remove(broker.getBrokerId()));
            throw e;
        }
        // Recorded only once the container is up and wired: a broker that has them ran under the fixed node name.
        broker.setNodeName(NODE_NAME);
        broker.setAmqpHostPort(ports.amqp());
        broker.setConsoleHostPort(ports.console());
    }

    /**
     * Keeps a broker's container that outlived Floci, as one does when Floci is killed rather than shut down,
     * when it still answers on the console its record names: whatever its node name, its node is the one holding
     * the broker's data. The container is tracked again, so shutdown removes it, and its logs are streamed again.
     * Returns whether the container was kept.
     */
    public boolean adoptRunningContainer(Broker broker) {
        String name = containerName(broker.getBrokerId());
        Optional<Container> container = lifecycleManager.findByName(name);
        String legacyName = ContainerStorageHelper.legacyDockerName(config, name);
        if (container.isEmpty() && !legacyName.equals(name)) {
            // A broker created before the floci-aws- prefix runs in a container of the old name.
            container = lifecycleManager.findByName(legacyName);
        }
        if (container.isEmpty() || !"running".equals(container.get().getState()) || !isReady(broker)) {
            return false;
        }
        String containerId = container.get().getId();
        synchronized (this) {
            if (shuttingDown) {
                return false;
            }
            containerIds.put(broker.getBrokerId(), containerId);
        }
        broker.setContainerId(containerId);
        attachLogs(broker, containerId);
        LOG.infov("Kept the running RabbitMQ container {0} of broker {1}", containerId, broker.getBrokerName());
        return true;
    }

    private void attachLogs(Broker broker, String containerId) {
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        Closeable logHandle = logStreamer.attachPolledForAccount(brokerAccount(broker), containerId,
                broker.generalLogGroup(), logStreamer.generateLogStreamName(shortId), brokerRegion(broker),
                "amazonmq:" + broker.getBrokerId());
        if (logHandle != null) {
            logStreams.put(broker.getBrokerId(), logHandle);
        }
    }

    /**
     * The account that owns the broker. A restore runs outside any request, where the resolver answers the default
     * account; a broker recorded without its account is the request's.
     */
    private String brokerAccount(Broker broker) {
        return broker.getAccountId() != null ? broker.getAccountId() : regionResolver.getAccountId();
    }

    /** The Region the broker's ARN names, and the default Region for a broker recorded without one. */
    private String brokerRegion(Broker broker) {
        return regionResolver.regionOf(broker.getBrokerArn());
    }

    /** Whether shutdown has removed the containers, after which no broker container starts. */
    public synchronized boolean isShuttingDown() {
        return shuttingDown;
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
            ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName(broker.getBrokerId()));
        }
    }

    /**
     * Stops and removes every running broker container. Wired into
     * {@code EmulatorLifecycle.onStop()} so containers are torn down on shutdown
     * alongside the other container managers.
     */
    public void stopAll() {
        synchronized (this) {
            shuttingDown = true;
        }
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

    /**
     * The broker's host ports: the ones it had, each that is still free and in its range, as a restored broker has,
     * and new ones otherwise, as a new broker, which has none, always gets.
     */
    private HostPorts allocateHostPorts(Broker broker) {
        EmulatorConfig.AmazonMqServiceConfig mq = config.services().amazonmq();
        int amqp = reuseOrAllocate(broker.getAmqpHostPort(), mq.amqpHostPortBase(), mq.amqpHostPortMax());
        int console;
        try {
            console = reuseOrAllocate(broker.getConsoleHostPort(), mq.consoleHostPortBase(), mq.consoleHostPortMax());
        } catch (RuntimeException e) {
            portAllocator.release(amqp);
            throw e;
        }
        return new HostPorts(amqp, console);
    }

    private int reuseOrAllocate(Integer previous, int base, int max) {
        if (previous != null && previous >= base && previous <= max) {
            OptionalInt reused = portAllocator.allocateIfFree(previous);
            if (reused.isPresent()) {
                return reused.getAsInt();
            }
        }
        return portAllocator.allocate(base, max);
    }

    private void releaseHostPorts(String brokerId) {
        HostPorts ports = hostPorts.remove(brokerId);
        if (ports != null) {
            portAllocator.release(ports.amqp());
            portAllocator.release(ports.console());
        }
    }

    public void removeBrokerStorage(Broker broker) {
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, resolveVolumeName(broker));
    }

    /**
     * The broker's Docker volume name, backfilled once for records written before the field existed:
     * those predate the {@code floci-aws-} migration, so their data is in the legacy-named volume
     * and must keep resolving there. Never use the live helper here, which would strand that data
     * under a freshly created volume.
     */
    private String resolveVolumeName(Broker broker) {
        if (broker.getDockerVolumeName() == null || broker.getDockerVolumeName().isBlank()) {
            broker.setDockerVolumeName(ContainerStorageHelper.legacyResourceName(
                    config, "amazonmq", broker.getVolumeId(), broker.getBrokerId()));
        }
        return broker.getDockerVolumeName();
    }

}
