package io.github.hectorvent.floci.services.ui;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import com.github.dockerjava.api.model.Container;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Manages the lifecycle of the {@code floci/floci-ui} sidecar container — the
 * browser-facing Floci web console. The container is started lazily on the first
 * {@code /_floci/ui} hit and reused across restarts (one per Floci instance).
 *
 * <p>Unlike other sidecars, a failed start (typically a missing/unavailable image)
 * is <em>not</em> fatal: it is recorded in {@link #status()} so the interstitial
 * page can show a friendly message instead of a 500.
 */
@ApplicationScoped
public class FlociUiManager {

    private static final Logger LOG = Logger.getLogger(FlociUiManager.class);
    private static final int CONTAINER_INTERNAL_PORT = 4500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    private volatile boolean started;
    private volatile int hostPort;
    private volatile String containerId;
    private volatile Closeable logStream;
    private volatile String lastError;

    private final ExecutorService starter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "floci-ui-starter");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean kicked = new AtomicBoolean(false);

    @Inject
    public FlociUiManager(ContainerBuilder containerBuilder,
                          ContainerLifecycleManager lifecycleManager,
                          ContainerLogStreamer logStreamer,
                          ContainerDetector containerDetector,
                          CurrentContainerNetworkResolver currentContainerNetworkResolver,
                          DockerHostResolver dockerHostResolver,
                          EmulatorConfig config,
                          RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.regionResolver = regionResolver;
    }

    /** Snapshot of the sidecar state for the interstitial page. */
    public record UiStatus(boolean started, boolean ready, int hostPort, String error) {}

    /**
     * Lazily starts (or adopts) the floci-ui container. Idempotent and thread-safe.
     * Does not throw on a failed start — the failure is captured for {@link #status()}.
     */
    public synchronized void ensureStarted() {
        if (started) {
            return;
        }
        if (!config.services().ui().enabled()) {
            this.lastError = "The Floci UI is disabled (set floci.services.ui.enabled=true to enable it).";
            return;
        }
        // Clear any error from a prior failed attempt so status() reports this retry
        // as in-progress rather than surfacing the stale failure.
        this.lastError = null;
        String name = config.services().ui().containerName();

        Optional<Container> existing = lifecycleManager.findByName(name);
        if (existing.isPresent()) {
            adoptExisting(existing.get());
            return;
        }

        String image = config.services().ui().image();
        int chosenPort = config.services().ui().port();
        try {
            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(injectedEnv())
                    .withPortBinding(CONTAINER_INTERNAL_PORT, chosenPort)
                    .withDockerNetwork(resolveDockerNetwork())
                    .withLogRotation();
            if (!containerDetector.isRunningInContainer()) {
                specBuilder.withHostDockerInternalOnLinux();
            }

            ContainerSpec spec = specBuilder.build();
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            this.containerId = info.containerId();
            this.hostPort = chosenPort;
            this.started = true;
            this.lastError = null;
            LOG.infov("Started floci-ui sidecar {0} on host port {1}", name, String.valueOf(chosenPort));
            attachLogStream();
        } catch (Exception e) {
            this.lastError = "Could not start the Floci UI from image '" + image + "': " + e.getMessage()
                    + ". Ensure the image is available (docker pull " + image
                    + ", or build it from the floci-ui repo).";
            LOG.errorv(e, "Failed to start floci-ui sidecar from image {0}", image);
        }
    }

    /**
     * Triggers {@link #ensureStarted()} on a background thread and returns immediately,
     * so the caller can serve the interstitial page while the (possibly slow) image
     * pull and boot happen. De-duplicated; re-armed after a failed start so the user
     * can fix the image and retry.
     */
    public void ensureStartedAsync() {
        if (started) {
            return;
        }
        if (kicked.compareAndSet(false, true)) {
            starter.submit(() -> {
                try {
                    ensureStarted();
                } finally {
                    if (!started) {
                        kicked.set(false);
                    }
                }
            });
        }
    }

    /** Current state, including a probe of whether the UI is accepting connections. */
    public UiStatus status() {
        if (lastError != null) {
            return new UiStatus(started, false, hostPort, lastError);
        }
        boolean ready = started && probeReady();
        return new UiStatus(started, ready, hostPort, null);
    }

    /** Host port the UI is published on. Valid once {@link #ensureStarted()} succeeds. */
    public int hostPort() {
        return hostPort;
    }

    /** Stops the container unless {@code keep-running-on-shutdown=true}. */
    public void shutdown() {
        if (!started || containerId == null) {
            return;
        }
        if (config.services().ui().keepRunningOnShutdown()) {
            LOG.infov("Leaving floci-ui sidecar {0} running for next start-up", containerId);
            return;
        }
        lifecycleManager.stopAndRemove(containerId, logStream);
    }

    private List<String> injectedEnv() {
        List<String> env = new ArrayList<>();
        env.add("FLOCI_ENDPOINT=" + resolveFlociEndpoint());
        env.add("AWS_REGION=" + regionResolver.getDefaultRegion());
        env.add("AWS_ACCESS_KEY_ID=test");
        env.add("AWS_SECRET_ACCESS_KEY=test");
        env.add("PORT=" + CONTAINER_INTERNAL_PORT);
        return env;
    }

    /**
     * The endpoint the UI's API server uses to reach Floci from inside its container.
     *
     * <p>Reuses {@link DockerHostResolver}, the same mechanism Lambda and CodeBuild use:
     * when Floci runs in a container the sibling UI reaches it directly by Floci's own
     * container IP over the shared Docker network (no {@code host.docker.internal}, no
     * manual {@code FLOCI_HOSTNAME}); when Floci runs on the host the only path from a
     * container is the host gateway ({@code host.docker.internal}). An explicitly
     * configured {@code FLOCI_HOSTNAME} still wins so name-based compose setups keep
     * working.
     */
    String resolveFlociEndpoint() {
        if (containerDetector.isRunningInContainer() && config.hostname().isPresent()) {
            return config.effectiveBaseUrl();
        }
        String scheme = config.tls().enabled() ? "https" : "http";
        return scheme + "://" + dockerHostResolver.resolve() + ":" + config.port();
    }

    private Optional<String> resolveDockerNetwork() {
        Optional<String> configured = config.services().ui().dockerNetwork();
        if (configured.isPresent() && !configured.get().isBlank()) {
            return configured;
        }
        if (containerDetector.isRunningInContainer()) {
            return currentContainerNetworkResolver.resolveNetworkName();
        }
        return Optional.empty();
    }

    private boolean probeReady() {
        String url = containerDetector.isRunningInContainer()
                ? "http://" + config.services().ui().containerName() + ":" + CONTAINER_INTERNAL_PORT + "/"
                : "http://localhost:" + hostPort + "/";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void adoptExisting(Container existing) {
        this.containerId = existing.getId();
        try {
            ContainerInfo info = lifecycleManager.adopt(containerId, List.of(CONTAINER_INTERNAL_PORT));
            var endpoint = info.getEndpoint(CONTAINER_INTERNAL_PORT);
            if (endpoint != null) {
                this.hostPort = endpoint.port();
            }
            this.started = true;
            this.lastError = null;
            LOG.infov("Adopted existing floci-ui sidecar {0} on host port {1}",
                    containerId, String.valueOf(hostPort));
            attachLogStream();
        } catch (Exception e) {
            LOG.warnv("Failed to adopt existing floci-ui sidecar: {0}", e.getMessage());
            this.containerId = null;
        }
    }

    private void attachLogStream() {
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/floci/ui";
        String logStreamName = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        this.logStream = logStreamer.attach(containerId, logGroup, logStreamName, region, "floci:ui");
    }
}
