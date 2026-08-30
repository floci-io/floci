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
import java.util.regex.Pattern;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerPresence;
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
    private static final String FLOCI_ENDPOINT_ENV = "FLOCI_ENDPOINT";
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

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
        String image = config.services().ui().image();
        try {
            String name = ContainerStorageHelper.dockerName(config, config.services().ui().containerName());

            Optional<Container> existing = lifecycleManager.findByName(name);
            if (existing.isPresent() && !replaceIfEndpointDrifted(existing.get())) {
                adoptExisting(existing.get());
                return;
            }

            int chosenPort = config.services().ui().port();
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
        } catch (IllegalStateException e) {
            // replaceIfEndpointDrifted() records its own specific message for a container with an
            // existing sidecar to adopt, but a fresh start (no existing container) reaches this
            // catch directly from injectedEnv()/resolveFlociEndpoint() with lastError still null --
            // only fill it in when nothing more specific was already recorded.
            if (this.lastError == null) {
                this.lastError = e.getMessage();
            }
            LOG.errorv(e, "Failed to start floci-ui sidecar: {0}", e.getMessage());
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

    /**
     * Current state, including a probe of whether the UI is accepting connections.
     *
     * <p>A sidecar that has gone away is un-started here so the next call re-runs
     * {@link #ensureStarted()}. {@code started} is otherwise a one-way latch: it is set on a
     * successful start or adoption and never cleared, so a sidecar that is removed or exits
     * would leave Floci with no path back — the dashboard would stay "Not connected" until
     * someone intervened by hand. The UI polls this endpoint continuously, which supplies the
     * retry cadence; no extra backoff machinery is needed.
     *
     * <p>The failed probe alone is deliberately not the trigger. It also fails throughout a cold
     * boot, and re-arming on it would re-adopt the container on every poll. Recovery is driven by
     * the container being gone, which is the condition that actually needs a restart.
     */
    public UiStatus status() {
        if (lastError != null) {
            return new UiStatus(started, false, hostPort, lastError);
        }
        boolean ready = started && probeReady();
        if (started && !ready && shouldReArm(lifecycleManager.presenceOf(containerId))) {
            LOG.infov("floci-ui sidecar {0} is gone — starting it again so the dashboard "
                    + "recovers without Floci being restarted", containerId);
            this.started = false;
            // ensureStartedAsync() only submits on kicked's false->true edge, but a prior
            // successful start never reset it (it only resets after a FAILED start) -- without
            // this, this re-arm's CAS finds kicked already true and silently no-ops.
            this.kicked.set(false);
            ensureStartedAsync();
        }
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

    List<String> injectedEnv() {
        List<String> env = new ArrayList<>();
        env.add(FLOCI_ENDPOINT_ENV + "=" + resolveFlociEndpoint());
        if (config.services().ui().insecureSkipTlsVerify()) {
            LOG.warn("floci.services.ui.insecure-skip-tls-verify=true — the Floci UI sidecar will "
                    + "not verify Floci's TLS certificate. Intended for Floci's self-signed "
                    + "certificate, which carries no IP SAN for its own container address.");
            env.add("NODE_TLS_REJECT_UNAUTHORIZED=0");
        }
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
        Optional<String> override = config.services().ui().endpoint();
        if (override.isPresent()) {
            return validateEndpointOverride(override.get());
        }
        if (containerDetector.isRunningInContainer() && config.hostname().isPresent()) {
            return config.effectiveBaseUrl();
        }
        String host = dockerHostResolver.resolve();
        String scheme = derivedScheme(
                config.tls().enabled(), config.services().ui().insecureSkipTlsVerify(), host);
        return scheme + "://" + authorityHost(host) + ":" + config.port();
    }

    /**
     * A host as it must appear in a URL authority.
     *
     * <p>An IPv6 literal has to be bracketed or the port separator is indistinguishable from the
     * address's own colons and the result is unparseable. Names and IPv4 literals pass through.
     */
    static String authorityHost(String host) {
        if (host == null || host.indexOf(':') < 0 || host.startsWith("[")) {
            return host;
        }
        return "[" + host + "]";
    }

    /**
     * Scheme for the derived endpoint.
     *
     * <p>With TLS enabled the honest answer is usually {@code https}, but not when the resolver
     * hands back a bare IP address. Containerized Floci reaches itself by its own Docker-network
     * IP, which the self-signed certificate cannot carry as a SAN: the address is assigned at
     * container-create time and changes on every recreate, so baking it into the certificate
     * would mean regenerating on every boot, and the certificate is generated by a
     * {@code ConfigSource} that runs before CDI and has no Docker client to ask. An
     * {@code https://} URL to that address therefore fails altname verification every time, and
     * the sidecar sits at "Not connected" with no path back.
     *
     * <p>Floci's listener does HTTP/HTTPS protocol detection on the same port, so {@code http://}
     * reaches it with TLS left enabled — this downgrades one hop on an internal Docker network,
     * not the emulator. A named host such as {@code host.docker.internal} is a DNS SAN on the
     * certificate and keeps {@code https}. So does an IP literal when the operator has opted into
     * {@code insecure-skip-tls-verify}, since they have said explicitly that they want TLS on this
     * hop and verification is no longer the obstacle.
     */
    static String derivedScheme(boolean tlsEnabled, boolean insecureSkipTlsVerify, String host) {
        if (!tlsEnabled) {
            return "http";
        }
        if (insecureSkipTlsVerify) {
            return "https";
        }
        return isIpLiteral(host) ? "http" : "https";
    }

    /**
     * Whether a host is a bare IP address rather than a name.
     *
     * <p>Mirrors the same distinction {@code CertificateGenerator} draws when it types a SAN as
     * {@code iPAddress} or {@code dNSName} — kept local rather than shared so this package does
     * not take a dependency on the ACM service for one predicate.
     */
    static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return IPV4_LITERAL.matcher(host).matches() || host.indexOf(':') >= 0;
    }

    /**
     * Validates an operator-supplied endpoint override. A blank or malformed value is a
     * configuration error, not a reason to quietly derive an endpoint instead: silently
     * substituting a different address is exactly the failure mode that leaves the dashboard
     * reporting "Not connected" with no indication of why.
     */
    static String validateEndpointOverride(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "floci.services.ui.endpoint is set but blank — remove it to derive the "
                            + "endpoint automatically, or give it an absolute http:// or https:// URL.");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalStateException(
                    "floci.services.ui.endpoint must be an absolute http:// or https:// URL, was: " + value);
        }
        String host;
        try {
            host = URI.create(value).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "floci.services.ui.endpoint must be an absolute http:// or https:// URL, was: " + value);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "floci.services.ui.endpoint must be an absolute http:// or https:// URL, was: " + value);
        }
        return value;
    }

    /**
     * Whether an adoption candidate must be destroyed and recreated rather than adopted.
     *
     * <p>Separate from {@link #endpointDrifted} because the two "no endpoint here" cases are not
     * the same decision. A container that declares no {@code FLOCI_ENDPOINT} is drifted and must
     * go. A container whose environment could not be read tells us nothing, and destroying it on
     * that basis would turn one failed inspect into a deleted, possibly perfectly healthy sidecar.
     * The unreadable case is therefore adopted, and the endpoint is re-checked on the next start.
     *
     * @param existingEnv the candidate's environment, empty if it could not be read
     * @param expected the endpoint a freshly created sidecar would be given
     */
    static boolean shouldReplace(Optional<List<String>> existingEnv, String expected) {
        return existingEnv.map(env -> endpointDrifted(env, expected)).orElse(false);
    }

    /**
     * Whether a sidecar that is not answering has actually gone, and so must be started again.
     *
     * <p>Only a container that has vanished or exited is re-armed. A running container that is
     * not yet answering is a cold boot, not a failure — re-arming there would re-adopt the same
     * container on every poll of {@link #status()} for as long as it took to come up. Presence
     * the runtime could not report is treated as "leave it alone" for the same reason: acting on
     * an unknown is how a transient runtime hiccup turns into a restart loop.
     */
    static boolean shouldReArm(ContainerPresence presence) {
        return presence == ContainerPresence.ABSENT || presence == ContainerPresence.STOPPED;
    }

    /**
     * True when an adoption candidate's baked-in {@code FLOCI_ENDPOINT} no longer matches the
     * endpoint Floci would hand a freshly created sidecar.
     *
     * <p>The sidecar's endpoint is fixed at container-create time, but Floci's container IP
     * changes on every restart. A sidecar that outlives Floci therefore keeps addressing the
     * previous instance and polls a dead address forever. A missing endpoint counts as drift:
     * unknown is not the same as correct, and adopting it would strand the UI just as badly.
     */
    static boolean endpointDrifted(List<String> existingEnv, String expected) {
        if (existingEnv == null) {
            return true;
        }
        return existingEnv.stream()
                .filter(entry -> entry.startsWith(FLOCI_ENDPOINT_ENV + "="))
                .map(entry -> entry.substring(FLOCI_ENDPOINT_ENV.length() + 1))
                .findFirst()
                .map(current -> !current.equals(expected))
                .orElse(true);
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

    /**
     * Removes an existing sidecar whose baked-in endpoint no longer addresses this Floci, so the
     * caller recreates it instead of adopting it.
     *
     * <p>Without this, a sidecar that outlives Floci is adopted verbatim and keeps polling the
     * previous instance's container IP indefinitely — the dashboard shows "Not connected" and the
     * only way out is to restart the sidecar by hand. Recreating it here keeps recovery entirely
     * within the sidecar's lifecycle, so nothing about the emulator has to be disturbed.
     *
     * @return true when the container was removed and must be recreated
     */
    private boolean replaceIfEndpointDrifted(Container existing) {
        String expected;
        try {
            expected = resolveFlociEndpoint();
        } catch (IllegalStateException e) {
            // A misconfigured override must surface as the start error, not as a silent adoption
            // of whatever the previous run happened to leave behind.
            this.lastError = e.getMessage();
            throw e;
        }
        if (!shouldReplace(lifecycleManager.containerEnv(existing.getId()), expected)) {
            return false;
        }
        LOG.infov("Existing floci-ui sidecar {0} points at a stale Floci endpoint (expected {1}) "
                        + "— recreating it so the UI reconnects without touching Floci",
                existing.getId(), expected);
        try {
            lifecycleManager.stopAndRemove(existing.getId(), null);
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not remove stale floci-ui sidecar {0}, adopting it instead: {1}",
                    existing.getId(), e.getMessage());
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
        closeLogStream();
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/floci/ui";
        String logStreamName = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        this.logStream = logStreamer.attach(containerId, logGroup, logStreamName, region, "floci:ui");
    }

    /** Releases the previous follower, so a restarted sidecar does not leave one behind. */
    private void closeLogStream() {
        Closeable previous = this.logStream;
        this.logStream = null;
        if (previous == null) {
            return;
        }
        try {
            previous.close();
        } catch (Exception e) {
            LOG.debugv("Could not close the previous floci-ui log stream: {0}", e.getMessage());
        }
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
