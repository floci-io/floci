package io.github.hectorvent.floci.services.floci.ui;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Manages the lifecycle of the web-console sidecar container, the browser-facing Floci console.
 * The container is started lazily on the first {@code /_floci/ui} hit and reused across restarts
 * (one per Floci instance).
 *
 * <p>The console is not required to be {@code floci/floci-ui}. Any image implementing the Floci
 * console contract (listen on {@code PORT}, serve {@code GET /api/health}, reach Floci at
 * {@code AWS_ENDPOINT_URL}) runs here unconfigured; {@link ConsoleProfileResolver} works out the
 * rest from the image's labels, a built-in profile, or {@code floci.services.ui.*}.
 *
 * <p>Unlike other sidecars, a failed start (typically a missing/unavailable image)
 * is <em>not</em> fatal: it is recorded in {@link #status()} so the interstitial
 * page can show a friendly message instead of a 500.
 */
@ApplicationScoped
public class FlociUiManager {

    private static final Logger LOG = Logger.getLogger(FlociUiManager.class);
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * The contract's canonical endpoint variable. Every console receives the endpoint under this
     * name, so it is also the one entry adoption compares to spot a sidecar left addressing a
     * previous Floci.
     */
    static final String CANONICAL_ENDPOINT_ENV = "AWS_ENDPOINT_URL";

    /** The port the console listens on. Structural: the port binding and the readiness probe use it too. */
    static final String PORT_ENV = "PORT";

    /**
     * Environment entries {@code extra-env} must not set, because the manager has already used their
     * values structurally and setting them here would change only the console's copy.
     *
     * <p>{@code PORT} decides where the console listens, but the Docker port binding and the
     * readiness probe are both built from {@code profile.internalPort()}, so an override here
     * produces a sidecar that listens on one port while Floci publishes and polls another.
     * {@code AWS_ENDPOINT_URL} is the entry adoption compares to detect a sidecar left pointing at a
     * previous Floci, so an override here reads as permanent drift and the container is recreated on
     * every check.
     *
     * <p>Both have a supported key that feeds the structural path as well as the environment:
     * {@code floci.services.ui.internal-port} and {@code floci.services.ui.endpoint}.
     */
    private static final Map<String, String> RESERVED_EXTRA_ENV = Map.of(
            PORT_ENV, "floci.services.ui.internal-port",
            CANONICAL_ENDPOINT_ENV, "floci.services.ui.endpoint");

    /** Which emulator the console is talking to, so one console can serve several Floci flavours. */
    private static final String FLOCI_CLOUD = "aws";

    /**
     * Credentials the console signs with. Passed explicitly rather than left to the baseline's
     * fallback, which forwards Floci's <em>own</em> ambient AWS credentials into the container:
     * acceptable for a workload the user wrote, not for a third-party console image.
     */
    private static final SessionCreds PLACEHOLDER_CREDENTIALS = new SessionCreds("test", "test", "test");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private final DockerHostResolver dockerHostResolver;
    private final LaunchedContainerAwsEnv awsEnv;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    private volatile boolean started;
    private volatile int hostPort;
    private volatile String containerId;
    private volatile Closeable logStream;
    private volatile String lastError;
    /** Shape of the configured console, resolved once per start. */
    private volatile ConsoleProfile profile = ConsoleProfileResolver.contractV1();
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
                          LaunchedContainerAwsEnv awsEnv,
                          EmulatorConfig config,
                          RegionResolver regionResolver,
                          ObjectMapper objectMapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        this.dockerHostResolver = dockerHostResolver;
        this.awsEnv = awsEnv;
        this.config = config;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
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
            this.lastError = "The Floci web console is disabled "
                    + "(set floci.services.ui.enabled=true to enable it).";
            return;
        }
        // Clear any error from a prior failed attempt so status() reports this retry
        // as in-progress rather than surfacing the stale failure.
        this.lastError = null;
        String image = config.services().ui().image();
        try {
            this.profile = resolveProfile(image);
            String name = ContainerStorageHelper.dockerName(config, config.services().ui().containerName());

            Optional<Container> existing = lifecycleManager.findByName(name);
            if (existing.isPresent()
                    && !replaceIfNotRunning(existing.get())
                    && !replaceIfEndpointDrifted(existing.get())) {
                adoptExisting(existing.get());
                return;
            }

            int chosenPort = config.services().ui().port();
            int internalPort = profile.internalPort();
            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(injectedEnv(profile))
                    .withPortBinding(internalPort, chosenPort)
                    .withDockerNetwork(resolveDockerNetwork())
                    .withLogRotation();
            if (!containerDetector.isRunningInContainer()) {
                specBuilder.withHostDockerInternalOnLinux();
            }

            ContainerSpec spec = specBuilder.build();
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            EndpointInfo endpoint = info.getEndpoint(internalPort);
            this.containerId = info.containerId();
            this.hostPort = resolveHostPort(endpoint, chosenPort);
            this.probeUrl = resolveProbeUrl(profile, endpoint, hostPort);
            this.started = true;
            this.lastError = null;
            LOG.infov("Started web console sidecar {0} from {1} on host port {2}",
                    name, image, String.valueOf(hostPort));
            attachLogStream(false);
        } catch (IllegalStateException e) {
            // replaceIfEndpointDrifted() records its own specific message for a container with an
            // existing sidecar to adopt, but a fresh start (no existing container) reaches this
            // catch directly from injectedEnv()/resolveFlociEndpoint() with lastError still null --
            // only fill it in when nothing more specific was already recorded.
            if (this.lastError == null) {
                this.lastError = e.getMessage();
            }
            LOG.errorv(e, "Failed to start the web console sidecar: {0}", e.getMessage());
        } catch (Exception e) {
            this.lastError = describeStartFailure(image, e);
            LOG.errorv(e, "Failed to start the web console sidecar from image {0}", image);
        }
    }

    /**
     * Works out the shape of the configured console: its labels first, then a built-in profile,
     * then the contract defaults, with {@code floci.services.ui.*} winning over all of them.
     *
     * <p>Reading the labels needs the image present, which is why this runs on the starter thread
     * and not at injection time. A label read that fails is not an error here: the pull is
     * attempted again by {@code createAndStart}, which reports the failure properly.
     */
    ConsoleProfile resolveProfile(String image) {
        String resolvedImage = containerBuilder.resolveImage(image);
        Optional<Map<String, String>> labels = lifecycleManager.imageLabels(resolvedImage);
        return ConsoleProfileResolver.resolve(resolvedImage, labels, config.services().ui());
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
        if (!started) {
            return new UiStatus(false, false, hostPort, null);
        }
        RuntimeProbe probe = probeRuntime();
        if (!probe.ready() && shouldReArm(lifecycleManager.presenceOf(containerId))) {
            LOG.infov("The web console sidecar {0} is gone, starting it again so the console "
                    + "recovers without Floci being restarted", containerId);
            this.started = false;
            // ensureStartedAsync() only submits on kicked's false->true edge, but a prior
            // successful start never reset it (it only resets after a FAILED start) -- without
            // this, this re-arm's CAS finds kicked already true and silently no-ops.
            this.kicked.set(false);
            ensureStartedAsync();
        }
        return new UiStatus(started, probe.ready(), hostPort, probe.error());
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
            LOG.infov("Leaving the web console sidecar {0} running for next start-up", containerId);
            return;
        }
        lifecycleManager.stopAndRemove(containerId, logStream);
    }

    /**
     * The console contract's side of the bargain: what Floci hands every console it starts.
     *
     * <p>The AWS baseline is the same one Lambda, ECS and CodeBuild containers get, so a console
     * built on any AWS SDK is configured by its ordinary credential and endpoint discovery, with no
     * Floci-specific code. On top of it the console is told its listen port, which emulator it is
     * talking to, and, only for a console that reads neither of the baseline endpoint names, the
     * endpoint once more under a name of its own.
     *
     * <p>Ordered so the rendered list is stable, and keyed so a configured extra replaces the
     * injected default rather than being appended as a second entry for the same variable, which
     * runtimes resolve inconsistently.
     */
    List<String> injectedEnv(ConsoleProfile console) {
        String endpoint = resolveFlociEndpoint();

        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        for (String entry : awsEnv.sdkBaselineEnv(regionResolver.getDefaultRegion(), Optional.empty(),
                endpoint, Optional.of(PLACEHOLDER_CREDENTIALS))) {
            int split = entry.indexOf('=');
            env.put(entry.substring(0, split), entry.substring(split + 1));
        }
        if (console.hasEndpointAlias() && !env.containsKey(console.endpointEnv())) {
            env.put(console.endpointEnv(), endpoint);
        }
        env.put(PORT_ENV, String.valueOf(console.internalPort()));
        env.put("FLOCI_CLOUD", FLOCI_CLOUD);
        if (config.services().ui().insecureSkipTlsVerify()) {
            LOG.warn("floci.services.ui.insecure-skip-tls-verify=true: the web console sidecar will "
                    + "not verify Floci's TLS certificate. Intended for Floci's self-signed "
                    + "certificate, which carries no IP SAN for its own container address.");
            env.put("FLOCI_TLS_SKIP_VERIFY", "1");
            // The same instruction in the form a Node or Bun console's HTTP client already honours.
            env.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
        }
        applyExtraEnv(env, config.services().ui().extraEnv());

        List<String> rendered = new ArrayList<>(env.size());
        env.forEach((key, value) -> rendered.add(key + "=" + value));
        return rendered;
    }

    /**
     * Merges operator-supplied {@code KEY=VALUE} entries over the injected defaults.
     *
     * <p>An entry without {@code =}, or with a blank key, is dropped with a warning rather than
     * failing the start: a typo in one extra variable should not cost the whole console, and the
     * warning is what tells the operator which entry was ignored. A value may itself contain
     * {@code =} (a URL query, a base64 pad), so only the first one splits.
     */
    static void applyExtraEnv(LinkedHashMap<String, String> env, Optional<List<String>> extra) {
        if (extra.isEmpty()) {
            return;
        }
        for (String entry : extra.get()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            int split = entry.indexOf('=');
            String key = split < 0 ? "" : entry.substring(0, split).trim();
            if (key.isEmpty()) {
                LOG.warnv("Ignoring floci.services.ui.extra-env entry \"{0}\": expected KEY=VALUE.", entry);
                continue;
            }
            String supportedKey = RESERVED_EXTRA_ENV.get(key);
            if (supportedKey != null) {
                LOG.warnv("Ignoring floci.services.ui.extra-env entry \"{0}\": {1} is set from the "
                                + "console profile and is used by the port binding, the readiness probe "
                                + "and adoption. Set {2} instead.", entry, key, supportedKey);
                continue;
            }
            env.put(key, entry.substring(split + 1));
        }
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
     * True when an adoption candidate's baked-in endpoint variable no longer matches the
     * endpoint Floci would hand a freshly created sidecar.
     *
     * <p>The sidecar's endpoint is fixed at container-create time, but Floci's container IP
     * changes on every restart. A sidecar that outlives Floci therefore keeps addressing the
     * previous instance and polls a dead address forever. A missing endpoint counts as drift:
     * unknown is not the same as correct, and adopting it would strand the console just as badly.
     * That is also what recreates, exactly once, a sidecar left by a Floci old enough to have
     * injected only {@code FLOCI_ENDPOINT}.
     */
    static boolean endpointDrifted(List<String> existingEnv, String expected) {
        if (existingEnv == null) {
            return true;
        }
        String prefix = CANONICAL_ENDPOINT_ENV + "=";
        return existingEnv.stream()
                .filter(entry -> entry.startsWith(prefix))
                .map(entry -> entry.substring(prefix.length()))
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
     * Resolves the sidecar runtime-status URL that the readiness probe should query.
     * {@link EndpointInfo} already returns a Floci-reachable
     * address — {@code localhost:hostPort} when Floci runs natively, or the
     * sidecar's container IP on the shared Docker network when Floci runs in a
     * container (where the published host port is not reachable from inside).
     * Falls back to {@code localhost:hostPort} if the endpoint is unavailable.
     */
    String resolveProbeUrl(ConsoleProfile console, EndpointInfo endpoint, int fallbackHostPort) {
        String path = console.healthPath();
        if (endpoint != null) {
            return "http://" + endpoint.host() + ":" + endpoint.port() + path;
        }
        return "http://localhost:" + fallbackHostPort + path;
    }

    private record RuntimeProbe(boolean ready, String error) {
    }

    private RuntimeProbe probeRuntime() {
        String url = probeUrl;
        if (url == null) {
            return new RuntimeProbe(false, null);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return new RuntimeProbe(false, null);
            }
            ConsoleProfile console = this.profile;
            if (!console.hasReadyField()) {
                // A console whose health endpoint is a plain liveness check has nothing more to
                // say than 200: reading a field that is not there would report it as never ready.
                return new RuntimeProbe(true, null);
            }
            JsonNode health;
            try (var input = conn.getInputStream()) {
                health = objectMapper.readTree(input);
            }
            String reported = health.path(console.healthReadyField()).asText("");
            if (console.healthReadyValue().equalsIgnoreCase(reported)) {
                return new RuntimeProbe(true, null);
            }
            if (console.healthUnavailableValue().equalsIgnoreCase(reported)) {
                return new RuntimeProbe(false, unavailableMessage(console, health));
            }
            return new RuntimeProbe(false, null);
        } catch (Exception e) {
            LOG.debugv(e, "Failed to probe the web console health endpoint at {0}", url);
            return new RuntimeProbe(false, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Whether an adoption candidate must be recreated because it is not running.
     *
     * <p>Adopting an exited container leaves {@link #status()} in a loop: the probe fails, the
     * container reads as gone, the re-arm calls back into here, and the same dead container is
     * adopted again on every poll with nothing ever starting it. Recreating is the only exit, and
     * it is also the right answer: a console that exited did so for a reason its next start will
     * either clear or report.
     *
     * <p>Presence the runtime could not report is left alone, the same judgement
     * {@link #shouldReArm} makes: acting on an unknown is how a transient hiccup destroys a
     * healthy sidecar.
     */
    static boolean mustRecreate(ContainerPresence presence) {
        return presence == ContainerPresence.STOPPED || presence == ContainerPresence.ABSENT;
    }

    private boolean replaceIfNotRunning(Container existing) {
        if (!mustRecreate(lifecycleManager.presenceOf(existing.getId()))) {
            return false;
        }
        LOG.infov("Existing web console sidecar {0} is not running, recreating it rather than "
                + "adopting a container that would never answer", existing.getId());
        try {
            lifecycleManager.stopAndRemove(existing.getId(), null);
        } catch (Exception e) {
            LOG.warnv("Could not remove the stopped web console sidecar {0}: {1}",
                    existing.getId(), e.getMessage());
        }
        return true;
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
        LOG.infov("Existing web console sidecar {0} points at a stale Floci endpoint (expected {1}) "
                        + "recreating it so the console reconnects without touching Floci",
                existing.getId(), expected);
        try {
            lifecycleManager.stopAndRemove(existing.getId(), null);
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not remove the stale web console sidecar {0}, adopting it instead: {1}",
                    existing.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Turns a console's own "I cannot reach Floci" report into the message the interstitial shows.
     * The contract asks for {@code endpoint} and {@code error} alongside the status; both are
     * optional, and a console that omits them still gets a usable sentence.
     */
    private static String unavailableMessage(ConsoleProfile console, JsonNode health) {
        String endpoint = health.path("endpoint").asText("");
        String error = health.path("error").asText("");
        StringBuilder message = new StringBuilder(capitalize(console.displayName()))
                .append(" cannot reach Floci");
        if (!endpoint.isBlank()) {
            message.append(" at ").append(endpoint);
        }
        if (!error.isBlank()) {
            message.append(": ").append(error);
        }
        return message.append('.').toString();
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "The web console";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void adoptExisting(Container existing) {
        this.containerId = existing.getId();
        try {
            int internalPort = profile.internalPort();
            ContainerInfo info = lifecycleManager.adopt(containerId, List.of(internalPort));
            EndpointInfo endpoint = info.getEndpoint(internalPort);
            this.hostPort = resolveHostPort(endpoint, config.services().ui().port());
            this.probeUrl = resolveProbeUrl(profile, endpoint, hostPort);
            this.started = true;
            this.lastError = null;
            LOG.infov("Adopted existing web console sidecar {0} on host port {1}",
                    containerId, String.valueOf(hostPort));
            attachLogStream(true);
        } catch (Exception e) {
            LOG.warnv("Failed to adopt the existing web console sidecar: {0}", e.getMessage());
            this.containerId = null;
        }
    }

    // An adopted container carries history from before this process; only its new lines are wanted.
    private void attachLogStream(boolean adopted) {
        closeLogStream();
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/floci/ui";
        String logStreamName = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();
        this.logStream = adopted
                ? logStreamer.attachFromNow(containerId, logGroup, logStreamName, region, "floci:ui")
                : logStreamer.attach(containerId, logGroup, logStreamName, region, "floci:ui");
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
            LOG.debugv("Could not close the previous web console log stream: {0}", e.getMessage());
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
            return "Could not start the Floci web console: image '" + image + "' is unavailable ("
                    + detail + "). Pull it with 'docker pull " + image
                    + "', or build it from the console's repository.";
        }
        if (isRuntimeUnreachable(e)) {
            return "Could not start the Floci web console: Floci could not reach the container runtime ("
                    + detail
                    + "). Check that the Docker/Podman socket is mounted into the Floci container and "
                    + "accessible — on SELinux hosts the socket bind-mount may need relabeling "
                    + "(e.g. ':z') or '--security-opt label=disable'.";
        }
        return "Could not start the Floci web console from image '" + image + "': " + detail + ".";
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
