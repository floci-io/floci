package io.github.hectorvent.floci.services.floci.ui;

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
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
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
    /**
     * URL the readiness probe connects to, resolved from the Docker API at start time.
     * Published host ports (e.g. {@code -p 4500:4500}) only exist on the host's network
     * namespace, so when Floci itself runs in a container it cannot reach the sidecar at
     * {@code localhost:hostPort} — it must use the sidecar's container IP on the shared
     * Docker network. {@link EndpointInfo} resolves the right address for both cases:
     * {@code localhost:hostPort} natively, {@code <containerIp>:4500} in a container.
     */
    private volatile String probeUrl;

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
        String name = ContainerStorageHelper.dockerName(config, config.services().ui().containerName());

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
            EndpointInfo endpoint = info.getEndpoint(CONTAINER_INTERNAL_PORT);
            this.containerId = info.containerId();
            this.hostPort = resolveHostPort(endpoint, chosenPort);
            this.probeUrl = resolveProbeUrl(endpoint, hostPort);
            this.started = true;
            this.lastError = null;
            LOG.infov("Started floci-ui sidecar {0} on host port {1}", name, String.valueOf(hostPort));
            attachLogStream();
        } catch (Exception e) {
            this.lastError = describeStartFailure(image, e);
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

    /**
     * The host port the browser-facing redirect ({@code /_floci/ui/status}) should target.
     * In native mode the resolved {@link EndpointInfo} reflects the actual bound host port,
     * which may differ from {@code configuredPort} when dynamic allocation ({@code port=0})
     * is used — so prefer it. In container mode the endpoint reflects the sidecar's internal
     * port (4500), not the host binding, so the configured published port is authoritative.
     */
    int resolveHostPort(EndpointInfo endpoint, int configuredPort) {
        if (!containerDetector.isRunningInContainer() && endpoint != null) {
            return endpoint.port();
        }
        return configuredPort;
    }

    /**
     * Resolves the URL the readiness probe should connect to from the sidecar's
     * resolved endpoint. {@link EndpointInfo} already returns a Floci-reachable
     * address — {@code localhost:hostPort} when Floci runs natively, or the
     * sidecar's container IP on the shared Docker network when Floci runs in a
     * container (where the published host port is not reachable from inside).
     * Falls back to {@code localhost:hostPort} if the endpoint is unavailable.
     */
    String resolveProbeUrl(EndpointInfo endpoint, int fallbackHostPort) {
        if (endpoint != null) {
            return "http://" + endpoint.host() + ":" + endpoint.port() + "/";
        }
        return "http://localhost:" + fallbackHostPort + "/";
    }

    private boolean probeReady() {
        String url = probeUrl;
        if (url == null) {
            return false;
        }
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
            EndpointInfo endpoint = info.getEndpoint(CONTAINER_INTERNAL_PORT);
            this.hostPort = resolveHostPort(endpoint, config.services().ui().port());
            this.probeUrl = resolveProbeUrl(endpoint, hostPort);
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

    /**
     * Builds the user-facing message for a failed sidecar start. Only a genuinely
     * unavailable image gets the {@code docker pull} guidance — every other failure
     * (an unreachable container runtime, a port clash, a daemon error) is reported
     * as itself, so users are not sent to pull an image that is already present.
     *
     * <p>The previous behaviour blamed a missing image for <em>every</em> failure,
     * which is especially misleading on Podman/SELinux hosts where the real cause is
     * usually the bind-mounted Docker socket being unreachable.
     */
    static String describeStartFailure(String image, Throwable e) {
        String detail = messageOf(e);
        if (isImageUnavailable(e)) {
            return "Could not start the Floci UI: image '" + image + "' is unavailable (" + detail
                    + "). Pull it with 'docker pull " + image + "', or build it from the floci-ui repo.";
        }
        if (isRuntimeUnreachable(e)) {
            return "Could not start the Floci UI: Floci could not reach the container runtime (" + detail
                    + "). Check that the Docker/Podman socket is mounted into the Floci container and "
                    + "accessible — on SELinux hosts the socket bind-mount may need relabeling "
                    + "(e.g. ':z') or '--security-opt label=disable'.";
        }
        return "Could not start the Floci UI from image '" + image + "': " + detail + ".";
    }

    /** True when the failure chain indicates the image itself is missing locally and in the registry. */
    private static boolean isImageUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NotFoundException) {
                return true;
            }
            String msg = t.getMessage();
            // docker-java's pull callback rewraps a daemon pull failure (missing image, auth)
            // as DockerClientException("Could not pull image: ...").
            if (t instanceof DockerClientException && msg != null && msg.startsWith("Could not pull image: ")) {
                return true;
            }
        }
        return false;
    }

    /** True when the failure chain indicates Floci could not reach the container runtime socket. */
    private static boolean isRuntimeUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // BindException and ConnectException both extend SocketException; the docker-java
            // Apache transport surfaces a refused/denied Unix-socket connect this way.
            if (t instanceof java.net.SocketException || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
