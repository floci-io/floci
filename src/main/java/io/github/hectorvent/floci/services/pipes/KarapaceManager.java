package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Starts, on demand, a Karapace REST Proxy sidecar fronting a Kafka {@code bootstrap.servers}
 * target, so Pipes can poll a Kafka source over plain HTTP ({@link PipesKafkaRestClient}) instead
 * of embedding {@code kafka-clients} in Floci itself. See #2916.
 *
 * <p>One container is started per distinct target and reused for the life of this process:
 * {@link #ensureStarted} is safe to call concurrently, including repeatedly for the same target
 * (multiple Pipes reading the same cluster share one Karapace instance).
 */
@ApplicationScoped
class KarapaceManager {

    private static final Logger LOG = Logger.getLogger(KarapaceManager.class);
    private static final int REST_PORT = 8082;
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final Duration readyTimeout;
    private final Map<String, URI> restBaseUriByTarget = new ConcurrentHashMap<>();
    private final Map<String, String> containerIdByTarget = new ConcurrentHashMap<>();

    @Inject
    KarapaceManager(ContainerBuilder containerBuilder,
                    ContainerLifecycleManager lifecycleManager,
                    ContainerDetector containerDetector,
                    EmulatorConfig config,
                    PortAllocator portAllocator,
                    DockerHostResolver dockerHostResolver) {
        this(containerBuilder, lifecycleManager, containerDetector, config, portAllocator, dockerHostResolver,
                READY_TIMEOUT);
    }

    /** Test-only: overrides the readiness timeout so a failure case does not wait the full default. */
    KarapaceManager(ContainerBuilder containerBuilder,
                    ContainerLifecycleManager lifecycleManager,
                    ContainerDetector containerDetector,
                    EmulatorConfig config,
                    PortAllocator portAllocator,
                    DockerHostResolver dockerHostResolver,
                    Duration readyTimeout) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.readyTimeout = readyTimeout;
    }

    URI ensureStarted(String bootstrapServers) {
        return restBaseUriByTarget.computeIfAbsent(bootstrapServers, this::startContainer);
    }

    private URI startContainer(String bootstrapServers) {
        EmulatorConfig.KafkaRestBridgeConfig bridgeConfig = config.services().pipes().kafkaRestBridge();
        String targetKey = targetKey(bootstrapServers);
        String containerName = ContainerStorageHelper.resourceName(config, "pipes-kafka-rest", targetKey, targetKey);

        lifecycleManager.removeIfExists(containerName);

        List<String> env = List.of(
                "KARAPACE_HOST=0.0.0.0",
                "KARAPACE_PORT=" + REST_PORT,
                "KARAPACE_BOOTSTRAP_URI=" + resolveForContainer(bootstrapServers),
                "KARAPACE_ADVERTISED_PROTOCOL=http");

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(bridgeConfig.defaultImage())
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                // Native Linux Docker does not auto-inject host.docker.internal the way Docker
                // Desktop does, and resolveForContainer rewrites a host-local broker to exactly
                // that alias, so the sidecar needs the host-gateway mapping to resolve it.
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                // The image's default entrypoint (plain `python3`) does nothing; karapace_rest_proxy
                // is the REST Proxy's own dedicated process, config-driven entirely by KARAPACE_* env.
                .withEntrypoint(List.of("/venv/bin/karapace_rest_proxy"))
                .withEnv(env)
                .withLabels(Map.of("floci.component", "pipes-kafka-rest-bridge"));

        int hostPort = 0;
        if (!containerDetector.isRunningInContainer()) {
            hostPort = portAllocator.allocate(bridgeConfig.hostPortBase(), bridgeConfig.hostPortMax());
            specBuilder.withPortBinding(REST_PORT, hostPort);
        } else {
            specBuilder.withExposedPort(REST_PORT);
        }

        ContainerSpec spec = specBuilder.build();
        ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            if (!containerDetector.isRunningInContainer()) {
                portAllocator.release(hostPort);
            }
            throw e;
        }

        try {
            containerIdByTarget.put(bootstrapServers, info.containerId());
            EndpointInfo endpoint = info.getEndpoint(REST_PORT);
            URI restBaseUri = URI.create("http://" + endpoint.host() + ":" + endpoint.port() + "/");
            waitUntilReady(restBaseUri);
            LOG.infov("Karapace REST Proxy {0} started for bootstrap {1}", info.containerId(), bootstrapServers);
            return restBaseUri;
        } catch (RuntimeException e) {
            containerIdByTarget.remove(bootstrapServers);
            lifecycleManager.stopAndRemove(info.containerId(), null);
            if (!containerDetector.isRunningInContainer()) {
                portAllocator.release(hostPort);
            }
            throw e;
        }
    }

    /**
     * Rewrites a host-local address (localhost/127.0.0.1) so the sidecar, in its own network
     * namespace, can reach a broker on the Floci host instead of trying to reach itself. Applies
     * to both self-managed sources given as {@code smk://localhost:9092} and, in native mode, an
     * MSK-backed Redpanda cluster whose advertised address is also {@code localhost:<port>}.
     */
    private String resolveForContainer(String bootstrapServers) {
        return Arrays.stream(bootstrapServers.split(","))
                .map(this::resolveHostLocalAddress)
                .collect(Collectors.joining(","));
    }

    private String resolveHostLocalAddress(String hostPort) {
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex < 0) {
            return hostPort;
        }
        String host = hostPort.substring(0, colonIndex);
        if (host.equals("localhost") || host.equals("127.0.0.1")) {
            return dockerHostResolver.resolve() + hostPort.substring(colonIndex);
        }
        return hostPort;
    }

    private void waitUntilReady(URI restBaseUri) {
        long deadline = System.nanoTime() + readyTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isReady(restBaseUri)) {
                return;
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, readyTimeout.toMillis() / 4)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Karapace to become ready at " + restBaseUri, e);
            }
        }
        throw new IllegalStateException("Karapace REST Proxy at " + restBaseUri + " did not become ready within "
                + readyTimeout.toMillis() + "ms");
    }

    private boolean isReady(URI restBaseUri) {
        try {
            HttpURLConnection conn = (HttpURLConnection) restBaseUri.resolve("_health").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            LOG.debugv("Karapace not ready yet at {0}: {1}", restBaseUri, e.getMessage());
            return false;
        }
    }

    private static String targetKey(String bootstrapServers) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bootstrapServers.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @PreDestroy
    void shutdown() {
        containerIdByTarget.values().forEach(containerId -> {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (RuntimeException e) {
                LOG.warnv("Failed to stop Karapace container {0}: {1}", containerId, e.getMessage());
            }
        });
        containerIdByTarget.clear();
        restBaseUriByTarget.clear();
    }
}
