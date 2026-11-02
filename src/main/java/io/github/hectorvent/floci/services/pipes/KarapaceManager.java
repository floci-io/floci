package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Digests;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
class KarapaceManager implements ContainerTeardown {

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
    private final Map<String, ManagedContainer> containersByTarget = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private int activeStarts;
    private CompletableFuture<Void> startsDrained = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> teardown;

    private record ManagedContainer(String id, int hostPort) {
    }

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
        synchronized (lifecycleLock) {
            if (teardown != null) {
                throw new IllegalStateException("Karapace containers are being stopped");
            }
            if (activeStarts++ == 0) {
                startsDrained = new CompletableFuture<>();
            }
        }
        try {
            return restBaseUriByTarget.computeIfAbsent(bootstrapServers, this::startContainer);
        } finally {
            CompletableFuture<Void> drained = null;
            synchronized (lifecycleLock) {
                if (--activeStarts == 0) {
                    drained = startsDrained;
                }
            }
            if (drained != null) {
                drained.complete(null);
            }
        }
    }

    private URI startContainer(String bootstrapServers) {
        ManagedContainer pending = containersByTarget.get(bootstrapServers);
        if (pending != null) {
            stopContainer(bootstrapServers, pending);
        }
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
                // Shared by every Pipe reading this bootstrap target, in any account or region,
                // so the identity names the service only.
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("pipes", null, null, null))
                .withLabels(Map.of(ContainerStorageHelper.COMPONENT_LABEL, "pipes-kafka-rest-bridge"));

        int hostPort = 0;
        if (!containerDetector.isRunningInContainer()) {
            hostPort = portAllocator.allocate(bridgeConfig.hostPortBase(), bridgeConfig.hostPortMax());
            specBuilder.withPortBinding(REST_PORT, hostPort);
        } else {
            specBuilder.withExposedPort(REST_PORT);
        }

        ContainerInfo info;
        try {
            ContainerSpec spec = specBuilder.build();
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            if (hostPort != 0) {
                portAllocator.release(hostPort);
            }
            throw e;
        }

        ManagedContainer managed = new ManagedContainer(info.containerId(), hostPort);
        containersByTarget.put(bootstrapServers, managed);
        try {
            EndpointInfo endpoint = info.getEndpoint(REST_PORT);
            URI restBaseUri = URI.create("http://" + endpoint.host() + ":" + endpoint.port() + "/");
            waitUntilReady(restBaseUri);
            LOG.infov("Karapace REST Proxy {0} started for bootstrap {1}", info.containerId(), bootstrapServers);
            return restBaseUri;
        } catch (RuntimeException e) {
            try {
                stopContainer(bootstrapServers, managed);
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != e) {
                    e.addSuppressed(cleanupFailure);
                }
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
        return Digests.sha256Hex(bootstrapServers).substring(0, 16);
    }

    @Override
    public void stopManagedContainers() {
        CompletableFuture<Void> drained;
        CompletableFuture<Void> finished;
        synchronized (lifecycleLock) {
            if (teardown != null) {
                finished = teardown;
                drained = null;
            } else {
                finished = new CompletableFuture<>();
                teardown = finished;
                drained = startsDrained;
            }
        }
        if (drained == null) {
            finished.join();
            return;
        }
        try {
            // Admission stays closed while existing starts finish and cleanup performs Docker I/O.
            drained.join();
            restBaseUriByTarget.clear();
            containersByTarget.forEach((target, managed) -> {
                try {
                    stopContainer(target, managed);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Failed to stop Karapace container {0}", managed.id());
                }
            });
        } finally {
            synchronized (lifecycleLock) {
                teardown = null;
            }
            finished.complete(null);
        }
    }

    private void stopContainer(String target, ManagedContainer managed) {
        lifecycleManager.stopAndRemoveStrict(managed.id(), null);
        if (managed.hostPort() != 0) {
            portAllocator.release(managed.hostPort());
        }
        containersByTarget.remove(target, managed);
    }

    @PreDestroy
    void shutdown() {
        stopManagedContainers();
    }
}
