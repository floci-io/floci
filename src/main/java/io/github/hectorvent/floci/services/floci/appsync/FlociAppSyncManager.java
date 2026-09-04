package io.github.hectorvent.floci.services.floci.appsync;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
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

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

/**
 * Lazily starts and manages the floci-app-sync sidecar container (issue #2917): the AppSync
 * GraphQL engine (schema compilation, query execution — graphql-java) lives there instead
 * of Floci's own JVM/native image, mirroring {@code FlociDuckManager}.
 *
 * On the first call to {@link #ensureReady()}, Floci pulls the image and starts a named
 * container "floci-app-sync". Subsequent calls return the cached URL immediately.
 *
 * If {@code floci.services.appsync.engine.url} is configured, container management is
 * skipped entirely and that URL is used as-is — useful in Docker Compose setups where the
 * user runs floci-app-sync as a separate service.
 */
@ApplicationScoped
public class FlociAppSyncManager {

    private static final Logger LOG = Logger.getLogger(FlociAppSyncManager.class);
    private static final String CONTAINER_NAME = "floci-app-sync";
    private static final int ENGINE_PORT = 3010;
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    private volatile String resolvedUrl;
    private volatile String containerId;

    @Inject
    public FlociAppSyncManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerDetector containerDetector,
                               EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    /**
     * Returns true if floci-app-sync is reachable without starting anything.
     * If a URL is pre-configured and healthy, that URL is cached for future ensureReady() calls.
     */
    public synchronized boolean isAvailable() {
        if (resolvedUrl != null) {
            return true;
        }
        Optional<String> configured = config.services().appsync().engine().url();
        if (configured.isPresent() && !configured.get().isBlank()) {
            String url = configured.get();
            if (probeHealth(url)) {
                resolvedUrl = url;
                LOG.infov("floci-app-sync is available at pre-configured URL: {0}", url);
                return true;
            }
        }
        return false;
    }

    private boolean probeHealth(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/q/health").toURL().openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the floci-app-sync base URL, starting the container on first call if needed.
     * Thread-safe — concurrent callers wait while the first thread does the pull+start.
     */
    public synchronized String ensureReady() {
        if (resolvedUrl != null) {
            return resolvedUrl;
        }

        Optional<String> configured = config.services().appsync().engine().url();
        if (configured.isPresent() && !configured.get().isBlank()) {
            resolvedUrl = configured.get();
            LOG.infov("Using pre-configured floci-app-sync URL: {0}", resolvedUrl);
            return resolvedUrl;
        }

        startContainer();
        return resolvedUrl;
    }

    private void startContainer() {
        String image = config.services().appsync().engine().defaultImage();
        LOG.infov("Starting floci-app-sync container using image {0}", image);

        String containerName = ContainerStorageHelper.dockerName(config, CONTAINER_NAME);
        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDynamicPort(ENGINE_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(ENGINE_PORT);
        containerId = info.containerId();

        String url = "http://" + endpoint;
        LOG.infov("floci-app-sync container started, waiting for health check at {0}", url);
        waitForHealth(url);

        resolvedUrl = url;
        LOG.infov("floci-app-sync is ready at {0}", resolvedUrl);
    }

    private void waitForHealth(String baseUrl) {
        String healthUrl = baseUrl + "/q/health";
        long deadline = System.currentTimeMillis() + HEALTH_POLL_MAX_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for floci-app-sync", e);
            }
        }
        throw new RuntimeException("floci-app-sync did not become healthy within " + HEALTH_POLL_MAX_MS + " ms");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping floci-app-sync container");
        lifecycleManager.stopAndRemove(containerId, null);
    }
}
