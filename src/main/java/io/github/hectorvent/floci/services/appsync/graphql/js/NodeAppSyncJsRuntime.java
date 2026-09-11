package io.github.hectorvent.floci.services.appsync.graphql.js;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
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
import com.github.dockerjava.api.model.Container;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs {@code APPSYNC_JS} resolver code in a Node sidecar container.
 *
 * <p>The sidecar is one long-lived container per Floci instance, started lazily on the first JS
 * resolver and reused for every evaluation after that, so the Node boot is paid once rather than
 * per request. Its server is {@code appsync/appsync-js-runtime.mjs} on the classpath, handed to the
 * container base64-encoded in its command: no bind mount, which keeps this working when Floci
 * itself runs in a container and the host path would not resolve inside the Docker daemon.
 *
 * <p>The image is a stock {@code node:22-alpine} with no {@code npm install} step, so a resolver
 * call never depends on registry access. {@code @aws-appsync/utils} is a shim the server writes
 * into a {@code node_modules} directory at boot.
 */
@ApplicationScoped
public class NodeAppSyncJsRuntime implements AppSyncJsRuntime {

    private static final Logger LOG = Logger.getLogger(NodeAppSyncJsRuntime.class);
    private static final String SERVER_RESOURCE = "appsync/appsync-js-runtime.mjs";
    private static final int CONTAINER_INTERNAL_PORT = 4600;
    private static final String SERVER_PATH = "/tmp/floci-appsync-js-runtime.mjs";
    /** How long an existing container gets to answer before it is treated as unusable. */
    private static final int ADOPT_PROBE_SECONDS = 5;
    /**
     * Label carrying a digest of the server script the container was started with. The script is
     * baked into the container's command, so a sidecar left over from an older Floci serves that
     * older script forever: a fixed shim would never reach a developer who had one running.
     * Adoption compares this and replaces the container when it differs.
     */
    private static final String SCRIPT_LABEL = "io.floci.appsync.js-runtime.script";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile boolean started;
    private volatile String containerId;
    private volatile String baseUrl;
    private volatile Closeable logStream;
    private volatile String lastError;

    @Inject
    public NodeAppSyncJsRuntime(ContainerBuilder containerBuilder,
                                ContainerLifecycleManager lifecycleManager,
                                ContainerLogStreamer logStreamer,
                                ContainerDetector containerDetector,
                                EmulatorConfig config,
                                RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.config = config;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public boolean isAvailable() {
        return jsRuntimeConfig().enabled();
    }

    @Override
    public JsEvaluation evaluate(String code, String handler, Map<String, Object> context) {
        if (!jsRuntimeConfig().enabled()) {
            throw new AwsException("InternalFailureException",
                    "APPSYNC_JS resolvers need the Node sidecar, which is disabled. Set "
                            + "floci.services.appsync.js-runtime.enabled=true "
                            + "(FLOCI_SERVICES_APPSYNC_JS_RUNTIME_ENABLED) to run them.", 500);
        }
        ensureStarted();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("handler", handler);
        body.put("context", context);
        JsonNode response = post("/evaluate", body);
        return toEvaluation(response);
    }

    /** Starts, or adopts, the sidecar. Idempotent; a failed start is retried by the next call. */
    synchronized void ensureStarted() {
        if (started && baseUrl != null) {
            return;
        }
        String image = jsRuntimeConfig().image();
        String name = ContainerStorageHelper.dockerName(config, jsRuntimeConfig().containerName());
        try {
            Optional<Container> existing = lifecycleManager.findByName(name);
            if (existing.isPresent()) {
                // A sidecar left over from a previous run (keep-running-on-shutdown, or a crash)
                // serves the same server script, so adopting it skips the Node boot entirely.
                if (tryAdopt(existing.get())) {
                    return;
                }
                // findByName also answers with stopped and exited containers, so a dead leftover
                // must be cleared rather than adopted again on every call, and its name freed
                // before the create below asks for it.
                LOG.infov("Replacing unusable AppSync JS runtime sidecar {0}", name);
                lifecycleManager.stopAndRemove(existing.get().getId(), null);
            }

            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(List.of("FLOCI_JS_RUNTIME_PORT=" + CONTAINER_INTERNAL_PORT))
                    // sh -c so the encoded server can be decoded and run in one command. busybox
                    // in the alpine image supplies both sh and base64.
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of(startCommand()))
                    .withLabel(SCRIPT_LABEL, scriptDigest())
                    .withDockerNetwork(jsRuntimeConfig().dockerNetwork())
                    .withLogRotation();
            int configuredPort = jsRuntimeConfig().port();
            if (configuredPort > 0) {
                specBuilder.withPortBinding(CONTAINER_INTERNAL_PORT, configuredPort);
            } else {
                specBuilder.withDynamicPort(CONTAINER_INTERNAL_PORT);
            }
            if (!containerDetector.isRunningInContainer()) {
                specBuilder.withHostDockerInternalOnLinux();
            }

            ContainerSpec spec = specBuilder.build();
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            this.containerId = info.containerId();
            this.baseUrl = endpointUrl(info.getEndpoint(CONTAINER_INTERNAL_PORT));
            attachLogStream();
            awaitHealthy();
            this.started = true;
            this.lastError = null;
            LOG.infov("AppSync JS runtime sidecar {0} ready at {1}", name, baseUrl);
        } catch (AwsException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            this.lastError = e.getMessage();
            throw new AwsException("InternalFailureException",
                    "Could not start the AppSync JS runtime sidecar from image " + image + ": "
                            + e.getMessage() + ". Resolver JavaScript needs Docker; set "
                            + "floci.services.appsync.js-runtime.enabled=false to disable it.", 500);
        }
    }

    /**
     * Takes over an existing sidecar, or answers false when it cannot serve: stopped, exited, or
     * failing its health check. Bounded by a short probe rather than the full start timeout: a dead
     * container should be replaced promptly, not waited on.
     */
    private boolean tryAdopt(Container existing) {
        Map<String, String> labels = existing.getLabels();
        String ranWith = labels == null ? null : labels.get(SCRIPT_LABEL);
        if (!scriptDigest().equals(ranWith)) {
            LOG.infov("Existing AppSync JS runtime sidecar was started from a different server "
                    + "script ({0}); replacing it", ranWith == null ? "unlabelled" : ranWith);
            return false;
        }
        try {
            this.containerId = existing.getId();
            ContainerInfo info = lifecycleManager.adopt(containerId, List.of(CONTAINER_INTERNAL_PORT));
            this.baseUrl = endpointUrl(info.getEndpoint(CONTAINER_INTERNAL_PORT));
            awaitHealthy(ADOPT_PROBE_SECONDS);
            this.started = true;
            this.lastError = null;
            LOG.infov("Adopted AppSync JS runtime sidecar {0} at {1}", containerId, baseUrl);
            return true;
        } catch (RuntimeException e) {
            LOG.debugv("Could not adopt the existing AppSync JS runtime sidecar: {0}", e.getMessage());
            reset();
            this.containerId = null;
            return false;
        }
    }

    /**
     * The command that materialises the server inside the container. The script is base64-encoded
     * rather than quoted into the shell: it contains the quotes, backticks and newlines of the
     * {@code @aws-appsync/utils} shim it writes, none of which survive shell interpolation.
     */
    private String startCommand() {
        String encoded = Base64.getEncoder().encodeToString(serverScript().getBytes(StandardCharsets.UTF_8));
        return "echo " + encoded + " | base64 -d > " + SERVER_PATH + " && exec node " + SERVER_PATH;
    }

    /** Digest of the server script this build would start the sidecar with. */
    private String scriptDigest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(serverScript().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String serverScript() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(SERVER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SERVER_RESOURCE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SERVER_RESOURCE, e);
        }
    }

    private void awaitHealthy() {
        awaitHealthy(jsRuntimeConfig().startTimeoutSeconds());
    }

    private void awaitHealthy(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalFailureException",
                        "Interrupted while waiting for the AppSync JS runtime sidecar", 500);
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalFailureException",
                        "Interrupted while waiting for the AppSync JS runtime sidecar", 500);
            }
        }
        throw new AwsException("InternalFailureException",
                "The AppSync JS runtime sidecar did not become healthy within "
                        + timeoutSeconds + "s"
                        + (last == null ? "" : ": " + last.getMessage()), 500);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(body);
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(jsRuntimeConfig().evaluationTimeoutSeconds()))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AwsException("InternalFailureException",
                        "The AppSync JS runtime sidecar answered " + response.statusCode(), 500);
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("InternalFailureException",
                    "Interrupted while evaluating resolver code", 500);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            // The sidecar may have been removed under us; drop the state so the next call restarts it.
            reset();
            throw new AwsException("InternalFailureException",
                    "Could not reach the AppSync JS runtime sidecar: " + e.getMessage(), 500);
        }
    }

    private JsEvaluation toEvaluation(JsonNode response) {
        List<JsEvaluation.JsError> appended = new ArrayList<>();
        for (JsonNode error : response.path("errors")) {
            appended.add(toError(error));
        }
        Map<String, Object> stash = objectMapper.convertValue(
                response.has("stash") && response.get("stash").isObject()
                        ? response.get("stash")
                        : objectMapper.createObjectNode(),
                Map.class);
        if (response.path("ok").asBoolean(false)) {
            return new JsEvaluation(unwrap(response.get("result")), stash,
                    response.path("earlyReturn").asBoolean(false), appended, null, false);
        }
        return new JsEvaluation(null, stash, false, appended, toError(response.path("error")),
                response.path("missingHandler").asBoolean(false));
    }

    private JsEvaluation.JsError toError(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new JsEvaluation.JsError(
                node.path("message").asText(null),
                node.path("type").isNull() ? null : node.path("type").asText(null),
                unwrap(node.get("data")),
                unwrap(node.get("errorInfo")));
    }

    private Object unwrap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private String endpointUrl(EndpointInfo endpoint) {
        return "http://" + endpoint.host() + ":" + endpoint.port();
    }

    /**
     * Follows the sidecar's stdout into CloudWatch Logs, as the other sidecars do: a resolver
     * that fails to compile says so on Node's stderr and nowhere else.
     */
    private void attachLogStream() {
        try {
            String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
            this.logStream = logStreamer.attach(containerId, "/floci/appsync-js",
                    logStreamer.generateLogStreamName(shortId),
                    regionResolver.getDefaultRegion(), "appsync:js");
        } catch (RuntimeException e) {
            LOG.debugv("Could not attach to AppSync JS runtime logs: {0}", e.getMessage());
        }
    }

    private void reset() {
        this.started = false;
        this.baseUrl = null;
    }

    private EmulatorConfig.JsRuntimeConfig jsRuntimeConfig() {
        return config.services().appsync().jsRuntime();
    }

    /** For diagnostics: the sidecar's last start failure, or null. */
    public String lastError() {
        return lastError;
    }

    @PreDestroy
    void shutdown() {
        if (containerId == null) {
            return;
        }
        if (jsRuntimeConfig().keepRunningOnShutdown()) {
            LOG.infov("Leaving AppSync JS runtime sidecar {0} running for next start-up", containerId);
            return;
        }
        lifecycleManager.stopAndRemove(containerId, logStream);
        reset();
    }
}
