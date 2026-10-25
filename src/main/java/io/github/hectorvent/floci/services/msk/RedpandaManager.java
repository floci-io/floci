package io.github.hectorvent.floci.services.msk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RedpandaManager {

    private static final Logger LOG = Logger.getLogger(RedpandaManager.class);
    private static final int KAFKA_PORT = 9092;
    // Second Kafka listener, only used when Floci runs in a container: published to a host port
    // and advertised with a host-reachable address, for clients outside the Docker network.
    static final int KAFKA_HOST_LISTENER_PORT = 9093;
    static final int ADMIN_PORT = 9644;

    // Redpanda's Admin API exposes readiness at /v1/status/ready. /ready always returns 404
    private static final String ADMIN_READY_PATH = "/v1/status/ready";
    private static final String ADMIN_SCRAM_USERS_PATH = "/v1/security/users";
    private static final String ADMIN_SCRAM_USER_PATH_TEMPLATE = "/v1/security/users/%s";

    private record StartedBroker(int hostPort, ContainerInfo info) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final PortAllocator portAllocator;
    private final ObjectMapper objectMapper;
    private final Map<String, Closeable> logStreams = new ConcurrentHashMap<>();
    // Container mode only, keyed like logStreams: the host port the host listener is published
    // on (not recoverable from the bootstrap string, which may be the Docker-network address).
    private final Map<String, Integer> hostListenerPorts = new ConcurrentHashMap<>();
    private volatile boolean dockerUnavailableLogged;

    @Inject
    public RedpandaManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           ContainerDetector containerDetector,
                           EmulatorConfig config,
                           RegionResolver regionResolver,
                           PortAllocator portAllocator,
                           ObjectMapper objectMapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.portAllocator = portAllocator;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts {@link #startContainer} and reports the broker as unavailable instead of
     * propagating the failure, when the cause is that no Docker daemon is reachable from Floci:
     * Floci running inside Docker without a mounted socket, or a stopped daemon on the host. A
     * failure raised while the daemon <em>is</em> reachable is a genuine container problem and
     * still propagates, so nothing changes for a Floci that can start Redpanda containers.
     *
     * @return {@code true} when the container started, {@code false} when no Docker daemon is
     *         reachable
     */
    public boolean tryStartContainer(MskCluster cluster) {
        try {
            startContainer(cluster);
            dockerUnavailableLogged = false;
            return true;
        } catch (RuntimeException e) {
            if (isDockerReachable()) {
                throw e;
            }
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). MSK metadata operations "
                        + "keep working and clusters still reach ACTIVE, but they have no backing "
                        + "Kafka broker until a daemon becomes reachable.", e.getMessage());
            }
            return false;
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

    public void startContainer(MskCluster cluster) {
        String image = config.services().msk().defaultImage();
        LOG.infov("Starting Redpanda container for MSK cluster: {0} using image {1}", cluster.getClusterName(), image);

        String containerName = ContainerStorageHelper.resourceName(config, "msk", cluster.getVolumeId(), cluster.getClusterName());

        // Cleanup stale container
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, containerName);

        // Redpanda embeds broker addresses in Kafka protocol responses (Metadata,
        // FindCoordinator, ...) and clients reconnect directly to whatever address
        // it advertises. Without --advertise-kafka-addr, Redpanda advertises its own
        // self-perceived loopback (127.0.0.1:9092), which no client outside the
        // container can ever reach — so we must resolve and pass the externally
        // reachable address up front. The host port has to be known before the
        // container starts (the flag is a startup arg), so in native mode we
        // pre-allocate it ourselves instead of letting Docker assign one dynamically.
        //
        // When Floci itself runs in a container, one advertised address cannot serve both
        // sibling containers and the host, so Redpanda gets two listeners. A Kafka client is
        // always handed the address of the listener it connected through:
        //   internal (9092): <containerName>:9092, resolved by sibling containers through
        //                    Docker's embedded DNS
        //   host     (9093): published to a pre-allocated host port and advertised as
        //                    <bootstrap-hostname or localhost>:<port>, for clients on the Docker host
        boolean inContainer = containerDetector.isRunningInContainer();
        // allocate() cannot see a non-Docker process holding a host port when Floci runs in a
        // container, so Docker can still refuse the port it picked. allocateAndStart then moves on
        // to the next port in the range, and releases the port when anything else fails before
        // the container starts, volume setup included. Once it returns, the running container
        // holds the port until stopContainer releases it.
        StartedBroker started = portAllocator.allocateAndStart(
                config.services().msk().kafkaHostPortBase(),
                config.services().msk().kafkaHostPortMax(),
                port -> new StartedBroker(port, lifecycleManager.createAndStart(
                        brokerSpec(cluster, image, containerName, inContainer, port))));
        int kafkaHostPort = started.hostPort();
        ContainerInfo info = started.info();
        String hostListenerAddr = hostListenerHostname() + ":" + kafkaHostPort;
        cluster.setContainerId(info.containerId());

        // Resolve endpoints
        EndpointInfo kafkaEndpoint = info.getEndpoint(KAFKA_PORT);

        if (inContainer) {
            hostListenerPorts.put(clusterIdentityKey(cluster), kafkaHostPort);
            // GetBootstrapBrokers reports the Docker-network address unless the host listener
            // was asked for explicitly, which keeps sibling containers working by default. When
            // it was, both listeners are listed: a client connects through whichever entry it can
            // reach, and the broker then advertises that listener's address. The container name
            // (not its IP) fails fast on the host, where it does not resolve, while an unroutable
            // IP would wait for a connect timeout. It goes first for callers that read only the
            // first entry.
            cluster.setBootstrapBrokers(explicitBootstrapHostname().isPresent()
                    ? containerName + ":" + KAFKA_PORT + "," + hostListenerAddr
                    : kafkaEndpoint.host() + ":" + kafkaEndpoint.port());
            LOG.infov("Redpanda host listener for MSK cluster {0} published at {1}",
                    cluster.getClusterName(), hostListenerAddr);
        } else {
            cluster.setBootstrapBrokers(kafkaEndpoint.host() + ":" + kafkaEndpoint.port());
        }
        LOG.infov("Redpanda container {0} started. Bootstrap: {1}", info.containerId(), cluster.getBootstrapBrokers());

        // Attach log streaming (new feature)
        String shortId = info.containerId().length() >= 8
                ? info.containerId().substring(0, 8)
                : info.containerId();
        String logGroup = "/aws/msk/cluster/" + cluster.getClusterName();
        String logStream = logStreamer.generateLogStreamName(shortId);
        String region = AwsArnUtils.regionOrDefault(cluster.getClusterArn(), regionResolver.getDefaultRegion());

        String account = AwsArnUtils.accountOrDefault(cluster.getClusterArn(), regionResolver.getDefaultAccountId());
        Closeable logHandle = logStreamer.attachForAccount(
                account, info.containerId(), logGroup, logStream, region, "msk:" + cluster.getClusterName());
        if (logHandle != null) {
            logStreams.put(clusterIdentityKey(cluster), logHandle);
        }
    }

    /**
     * Builds the broker's container spec for one candidate host port. The port is part of the
     * Redpanda startup args, so the spec is rebuilt for each port {@link PortAllocator#allocateAndStart}
     * tries.
     */
    private ContainerSpec brokerSpec(MskCluster cluster, String image, String containerName,
                                     boolean inContainer, int kafkaHostPort) {
        String hostListenerAddr = hostListenerHostname() + ":" + kafkaHostPort;

        // Build command
        List<String> cmd = new ArrayList<>(List.of(
                "redpanda", "start", "--overprovisioned", "--smp", "1",
                "--memory", "512M", "--reserve-memory", "0M"));
        if (inContainer) {
            cmd.addAll(List.of(
                    "--kafka-addr",
                    "internal://0.0.0.0:" + KAFKA_PORT + ",host://0.0.0.0:" + KAFKA_HOST_LISTENER_PORT,
                    "--advertise-kafka-addr",
                    "internal://" + containerName + ":" + KAFKA_PORT + ",host://" + hostListenerAddr));
        } else {
            cmd.addAll(List.of("--advertise-kafka-addr", "localhost:" + kafkaHostPort));
        }

        // Build container spec. In native mode the Kafka port itself is published. In
        // container mode the internal listener stays on the Docker network and only the
        // host listener is published.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "msk", cluster.getClusterName(),
                        AwsArnUtils.accountOrDefault(cluster.getClusterArn(), regionResolver.getAccountId()),
                        AwsArnUtils.regionOrDefault(cluster.getClusterArn(), regionResolver.getDefaultRegion())));

        if (!inContainer) {
            specBuilder.withPortBinding(KAFKA_PORT, kafkaHostPort).withDynamicPort(ADMIN_PORT);
        } else {
            specBuilder.withExposedPort(KAFKA_PORT)
                    .withPortBinding(KAFKA_HOST_LISTENER_PORT, kafkaHostPort)
                    .withExposedPort(ADMIN_PORT);
        }

        // Handle persistence mounting
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyNamedVolume(specBuilder, lifecycleManager,
                    resolveVolumeName(cluster), "/var/lib/redpanda/data");
        } else {
            // Legacy host-path mode: host-persistent-path is an absolute path
            String hostDataPath = legacyCompatibleHostPath(cluster).toAbsolutePath().toString();
            if (!containerDetector.isRunningInContainer()) {
                ContainerStorageHelper.ensureHostDir(hostDataPath);
            }
            specBuilder.withBind(hostDataPath, "/var/lib/redpanda/data");
        }

        specBuilder.withCmd(cmd);
        return specBuilder.build();
    }

    public boolean isReady(MskCluster cluster) {
        if (cluster.getBootstrapBrokers() == null) {
            return false;
        }

        try {
            String adminUrl = adminBaseUrl(cluster) + ADMIN_READY_PATH;
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
        Closeable logHandle = logStreams.remove(clusterIdentityKey(cluster));

        lifecycleManager.stopAndRemove(cluster.getContainerId(), logHandle);
        LOG.infov("Redpanda container {0} stopped and removed", cluster.getContainerId());

        releaseKafkaHostPort(cluster);
    }

    /**
     * The broker's admin base URL, resolved from the persisted container id the same way
     * container start resolves endpoints: the bound host port in native mode, the container's
     * reachable address in container mode. Nothing is kept in memory, so it still works for a
     * cluster left in CREATING by a Floci process that was killed and restarted, and an IP is
     * used rather than the container name, which Docker's default bridge network does not resolve.
     *
     * <p>The network startContainer put the broker on is preferred, as it was when createAndStart
     * resolved the Kafka endpoint: in container mode the broker publishes the host listener, so
     * it is on the default bridge as well, and the bridge address is not one Floci can reach.
     */
    String adminBaseUrl(MskCluster cluster) {
        String network = containerBuilder.resolveDockerNetwork(config.services().dockerNetwork()).orElse(null);
        EndpointInfo admin = lifecycleManager.resolveEndpoint(cluster.getContainerId(), ADMIN_PORT, network);
        return "http://" + admin.host() + ":" + admin.port();
    }

    private void releaseKafkaHostPort(MskCluster cluster) {
        if (containerDetector.isRunningInContainer()) {
            Integer hostPort = hostListenerPorts.remove(clusterIdentityKey(cluster));
            if (hostPort != null) {
                portAllocator.release(hostPort);
            }
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
        ContainerStorageHelper.removeNamedVolume(config, lifecycleManager, resolveVolumeName(cluster));
    }

    /**
     * The hostname the host listener advertises: the configured bootstrap hostname, else
     * {@code localhost}, which is where the published port lands on the Docker host.
     */
    private String hostListenerHostname() {
        return explicitBootstrapHostname().orElse("localhost");
    }

    /** The configured bootstrap hostname, treating a blank value as unset. */
    private Optional<String> explicitBootstrapHostname() {
        return config.services().msk().bootstrapHostname().filter(h -> !h.isBlank());
    }

    private String clusterIdentityKey(MskCluster cluster) {
        return cluster.getClusterArn() != null ? cluster.getClusterArn() : cluster.getClusterName();
    }

    private String clusterStorageId(MskCluster cluster) {
        String account = AwsArnUtils.accountOrDefault(cluster.getClusterArn(), regionResolver.getDefaultAccountId());
        String region = AwsArnUtils.regionOrDefault(cluster.getClusterArn(), regionResolver.getDefaultRegion());
        return ContainerStorageHelper.dockerName(config,
                "msk-" + account + "-" + region + "-" + cluster.getClusterName());
    }

    private Path legacyCompatibleHostPath(MskCluster cluster) {
        Path scopedPath = ContainerStorageHelper.hostResourcePath(config, "msk", clusterStorageId(cluster));
        Path legacyPath = ContainerStorageHelper.hostResourcePath(config, "msk", cluster.getClusterName());
        return cluster.getResourceRegion() == null && Files.exists(legacyPath)
                ? legacyPath
                : scopedPath;
    }

    /**
     * The cluster's Docker volume name, backfilled once for records written before the field existed:
     * those predate the {@code floci-aws-} migration, so their data is in the legacy-named volume
     * and must keep resolving there. Never use the live helper here, which would strand that data
     * under a freshly created volume.
     */
    private String resolveVolumeName(MskCluster cluster) {
        if (cluster.getDockerVolumeName() == null || cluster.getDockerVolumeName().isBlank()) {
            cluster.setDockerVolumeName(ContainerStorageHelper.legacyResourceName(
                    config, "msk", cluster.getVolumeId(), cluster.getClusterName()));
        }
        return cluster.getDockerVolumeName();
    }

    public void createUser(MskCluster cluster, String username, String password) {
        byte[] payload;
        try {
            // Amazon MSK supports only SCRAM-SHA-512 for SCRAM authentication
            payload = objectMapper.writeValueAsBytes(Map.of(
                "username", username, "password", password, "algorithm", "SCRAM-SHA-512"));
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalServerErrorException", "Failed to serialize SCRAM credentials.", 500);
        }
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        AdminResponse response = listUsers(cluster).contains(username)
            ? sendAdminRequest(cluster, ADMIN_SCRAM_USER_PATH_TEMPLATE.formatted(encodedUsername), "PUT", payload)
            : sendAdminRequest(cluster, ADMIN_SCRAM_USERS_PATH, "POST", payload);
        
        int status = response.status();
        if (status < 200 || status >= 300) {
            throw new AwsException("InternalServerErrorException",
                "Kafka user creation failed with HTTP status " + status + ".", 500);
        }
    }

    public void deleteUser(MskCluster cluster, String username) {
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        AdminResponse response = sendAdminRequest(cluster, ADMIN_SCRAM_USER_PATH_TEMPLATE.formatted(encodedUsername), "DELETE", null);
        int status = response.status();
        if (status != 404 && (status < 200 || status >= 300)) {
            throw new AwsException("InternalServerErrorException",
                "Kafka user deletion failed with HTTP status " + status + ".", 500);
        }
    }

    public Set<String> listUsers(MskCluster cluster) {
        AdminResponse response = sendAdminRequest(cluster, ADMIN_SCRAM_USERS_PATH, "GET", null);
        if (response.status() != 200) {
            throw new AwsException("InternalServerErrorException", "Unable to list Kafka SCRAM users.", 500);
        }
        JsonNode users;
        try {
            users = objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new AwsException("InternalServerErrorException", "Invalid Kafka user list response.", 500);
        }
        if (users == null || !users.isArray()) {
            throw new AwsException("InternalServerErrorException", "Invalid Kafka user list response.", 500);
        }
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode user : users) {
            if (user.isTextual()) {
                result.add(user.textValue());
            }
        }
        return result;
    }

    private record AdminResponse(int status, byte[] body) {
    }

    AdminResponse sendAdminRequest(MskCluster cluster, String path, String method, byte[] payload) {
        if (cluster.getContainerId() == null || cluster.getBootstrapBrokers() == null) {
            throw new AwsException("ServiceUnavailableException", "Cluster has no backing Kafka broker.", 503);
        }
        String adminUrl;
        try {
            adminUrl = adminBaseUrl(cluster);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to resolve the admin endpoint of container {0} for MSK cluster {1}: {2}",
                    cluster.getContainerId(), cluster.getClusterName(), e.getMessage());
            throw new AwsException("ServiceUnavailableException", "Cluster has no backing Kafka broker.", 503);
        }
        String url = path.startsWith("/") ? adminUrl + path : adminUrl + "/" + path;
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                if (payload != null) {
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(payload);
                    }
                }
                int status = connection.getResponseCode();
                byte[] body = new byte[0];
                InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (responseStream != null) {
                    try (InputStream input = responseStream) {
                        body = input.readAllBytes();
                    }
                }
                return new AdminResponse(status, body);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            // Do not include the broker response or exception text: either can contain credentials.
            throw new AwsException("ServiceUnavailableException", "Unable to contact the Kafka management API.", 503);
        }
    }
}
