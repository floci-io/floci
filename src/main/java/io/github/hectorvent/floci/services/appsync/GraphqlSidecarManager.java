package io.github.hectorvent.floci.services.appsync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazily starts and manages the GraphQL sidecar used by AppSync (issue #2917).
 *
 * <p>The sidecar is the {@code floci/floci-sidecar-graphql} image from floci-io/floci-sidecars.
 * Its {@code GET /health} answers the sidecar contract: {@code {"status","name","version","contract"}}.
 * A sidecar speaking a different contract major fails fast with a message naming the fix, rather
 * than surfacing later as an unexplained request error. A sidecar whose health body is not JSON
 * predates the contract and is accepted with a warning.
 */
@ApplicationScoped
public class GraphqlSidecarManager {
    /** The sidecar contract major this manager speaks; see docs/contract.md in floci-io/floci-sidecars. */
    static final String REQUIRED_CONTRACT = "1";
    static final String IMAGE_ENV = "FLOCI_SERVICES_APPSYNC_GRAPHQL_IMAGE";
    static final String URL_ENV = "FLOCI_SERVICES_APPSYNC_GRAPHQL_URL";

    private static final Logger LOG = Logger.getLogger(GraphqlSidecarManager.class);
    private static final String CONTAINER_NAME = "floci-graphql";
    private static final int GRAPHQL_PORT = 8181;
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean contractReported = new AtomicBoolean();

    private volatile String resolvedUrl;
    private volatile String containerId;

    @Inject
    public GraphqlSidecarManager(ContainerBuilder containerBuilder,
                                 ContainerLifecycleManager lifecycleManager,
                                 EmulatorConfig config,
                                 ObjectMapper objectMapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public synchronized boolean isAvailable() {
        if (resolvedUrl != null) {
            if (containerId == null || probeHealth(resolvedUrl)) {
                return true;
            }
            discardStaleManagedEndpoint();
            return false;
        }
        Optional<String> configured = config.services().appsync().graphqlUrl();
        if (configured.isPresent() && !configured.get().isBlank()) {
            String url = trimTrailingSlash(configured.get());
            if (probeHealth(url)) {
                resolvedUrl = url;
                LOG.infov("GraphQL sidecar is available at pre-configured URL: {0}", url);
                return true;
            }
        }
        return false;
    }

    public synchronized String ensureReady() {
        if (resolvedUrl != null) {
            if (containerId == null || probeHealth(resolvedUrl)) {
                return resolvedUrl;
            }
            discardStaleManagedEndpoint();
        }
        Optional<String> configured = config.services().appsync().graphqlUrl();
        if (configured.isPresent() && !configured.get().isBlank()) {
            String url = trimTrailingSlash(configured.get());
            // Best effort: a sidecar that is not up yet is reported by the first real call,
            // but a sidecar speaking the wrong contract is refused here, once, with the fix.
            probeHealth(url);
            resolvedUrl = url;
            LOG.infov("Using pre-configured GraphQL sidecar URL: {0}", resolvedUrl);
            return resolvedUrl;
        }
        startContainer();
        return resolvedUrl;
    }

    private void discardStaleManagedEndpoint() {
        String staleUrl = resolvedUrl;
        String staleContainerId = containerId;
        resolvedUrl = null;
        containerId = null;
        LOG.warnv("GraphQL sidecar at {0} is no longer healthy; restarting the managed container", staleUrl);
        if (staleContainerId != null) {
            try {
                lifecycleManager.stopAndRemove(staleContainerId, null);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to remove stale GraphQL sidecar container {0}", staleContainerId);
            }
        }
    }

    private void startContainer() {
        String image = config.services().appsync().graphqlImage();
        LOG.infov("Starting GraphQL sidecar container using image {0}", image);
        String containerName = ContainerStorageHelper.dockerName(config, CONTAINER_NAME);
        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDynamicPort(GRAPHQL_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(GRAPHQL_PORT);
        containerId = info.containerId();
        String url = "http://" + endpoint;
        waitForHealth(url);
        resolvedUrl = url;
        LOG.infov("GraphQL sidecar is ready at {0}", resolvedUrl);
    }

    /**
     * {@code false} means not ready yet (connection refused, non-200, timeout). A sidecar that
     * answers but speaks another contract major throws instead, so callers never retry it.
     */
    private boolean probeHealth(String baseUrl) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + "/health").toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            checkContract(baseUrl, body);
            return true;
        } catch (IOException e) {
            LOG.debugv(e, "GraphQL sidecar health probe failed for {0}", baseUrl);
            return false;
        }
    }

    private void checkContract(String baseUrl, String body) {
        JsonNode health = parseHealth(body);
        if (health == null || !health.hasNonNull("contract")) {
            if (contractReported.compareAndSet(false, true)) {
                LOG.warnv("GraphQL sidecar at {0} predates the sidecar contract (health body is not contract JSON); "
                        + "it still works, but move to floci/floci-sidecar-graphql to get version checks", baseUrl);
            }
            return;
        }
        String contract = health.path("contract").asText();
        String name = health.path("name").asText("unknown");
        String version = health.path("version").asText("unknown");
        if (!REQUIRED_CONTRACT.equals(contract)) {
            throw new IllegalStateException("GraphQL sidecar at " + baseUrl + " (" + name + " " + version
                    + ") speaks sidecar contract " + contract + " but this Floci requires contract "
                    + REQUIRED_CONTRACT + ". Set " + IMAGE_ENV + " (currently "
                    + config.services().appsync().graphqlImage() + ") or " + URL_ENV
                    + " to a compatible sidecar.");
        }
        if (contractReported.compareAndSet(false, true)) {
            LOG.infov("GraphQL sidecar {0} {1} (contract {2}) at {3}", name, version, contract, baseUrl);
        }
    }

    private JsonNode parseHealth(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            LOG.debugv(e, "GraphQL sidecar health body is not JSON: {0}", body);
            return null;
        }
    }

    private void waitForHealth(String baseUrl) {
        long deadline = System.currentTimeMillis() + HEALTH_POLL_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            if (probeHealth(baseUrl)) {
                return;
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for GraphQL sidecar", e);
            }
        }
        throw new IllegalStateException("GraphQL sidecar did not become healthy within " + HEALTH_POLL_MAX_MS + " ms");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping GraphQL sidecar container");
        lifecycleManager.stopAndRemove(containerId, null);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
