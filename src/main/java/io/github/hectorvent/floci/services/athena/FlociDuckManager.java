package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

/**
 * Lazily starts and manages the floci-duck sidecar container.
 *
 * On the first call to {@link #ensureReady()}, Floci pulls the image and starts
 * a named container "floci-duck". Subsequent calls return the cached URL immediately.
 *
 * If {@code floci.services.athena.duck-url} is configured, container management is
 * skipped entirely and that URL is used as-is — useful in Docker Compose setups where
 * the user runs floci-duck as a separate service.
 */
@ApplicationScoped
public class FlociDuckManager {

    private static final Logger LOG = Logger.getLogger(FlociDuckManager.class);
    private static final String CONTAINER_NAME = "floci-duck";
    private static final int DUCK_PORT = 3000;
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    private volatile String resolvedUrl;
    private volatile String containerId;

    @Inject
    public FlociDuckManager(ContainerBuilder containerBuilder,
                            ContainerLifecycleManager lifecycleManager,
                            ContainerDetector containerDetector,
                            EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    /**
     * Returns the floci-duck base URL, starting the container on first call if needed.
     * Thread-safe — concurrent callers wait while the first thread does the pull+start.
     */
    public synchronized String ensureReady() {
        if (resolvedUrl != null) {
            return resolvedUrl;
        }

        Optional<String> configured = config.services().athena().duckUrl();
        if (configured.isPresent() && !configured.get().isBlank()) {
            resolvedUrl = configured.get();
            LOG.infov("Using pre-configured floci-duck URL: {0}", resolvedUrl);
            return resolvedUrl;
        }

        startContainer();
        return resolvedUrl;
    }

    private void startContainer() {
        String image = config.services().athena().defaultImage();
        LOG.infov("Starting floci-duck container using image {0}", image);

        lifecycleManager.removeIfExists(CONTAINER_NAME);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(CONTAINER_NAME)
                .withEnv("FLOCI_DUCK_S3_ACCESS_KEY", "test")
                .withEnv("FLOCI_DUCK_S3_SECRET_KEY", "test")
                .withEnv("FLOCI_DUCK_S3_REGION", config.defaultRegion())
                .withPortBinding(DUCK_PORT, DUCK_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();

        String url = containerDetector.isRunningInContainer()
                ? "http://" + CONTAINER_NAME + ":" + DUCK_PORT
                : "http://localhost:" + DUCK_PORT;

        LOG.infov("floci-duck container started, waiting for health check at {0}", url);
        waitForHealth(url);

        resolvedUrl = url;
        LOG.infov("floci-duck is ready at {0}", resolvedUrl);
    }

    private void waitForHealth(String baseUrl) {
        String healthUrl = baseUrl + "/health";
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
                throw new RuntimeException("Interrupted while waiting for floci-duck", e);
            }
        }
        throw new RuntimeException("floci-duck did not become healthy within " + HEALTH_POLL_MAX_MS + " ms");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping floci-duck container");
        lifecycleManager.stopAndRemove(containerId, null);
    }
}
