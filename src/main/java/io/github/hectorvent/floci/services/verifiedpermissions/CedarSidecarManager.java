package io.github.hectorvent.floci.services.verifiedpermissions;

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

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

/** Lazily starts and manages the Cedar 4 sidecar used by Amazon Verified Permissions. */
@ApplicationScoped
public class CedarSidecarManager {
    private static final Logger LOG = Logger.getLogger(CedarSidecarManager.class);
    private static final String CONTAINER_NAME = "floci-cedar";
    private static final int CEDAR_PORT = 8180;
    private static final int HEALTH_POLL_MAX_MS = 30_000;
    private static final int HEALTH_POLL_INTERVAL_MS = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    private volatile String resolvedUrl;
    private volatile String containerId;

    @Inject
    public CedarSidecarManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    public synchronized boolean isAvailable() {
        if (resolvedUrl != null) {
            if (containerId == null || probeHealth(resolvedUrl)) {
                return true;
            }
            discardStaleManagedEndpoint();
            return false;
        }
        Optional<String> configured = config.services().verifiedpermissions().cedarUrl();
        if (configured.isPresent() && !configured.get().isBlank()) {
            String url = trimTrailingSlash(configured.get());
            if (probeHealth(url)) {
                resolvedUrl = url;
                LOG.infov("Cedar sidecar is available at pre-configured URL: {0}", url);
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
        Optional<String> configured = config.services().verifiedpermissions().cedarUrl();
        if (configured.isPresent() && !configured.get().isBlank()) {
            resolvedUrl = trimTrailingSlash(configured.get());
            LOG.infov("Using pre-configured Cedar sidecar URL: {0}", resolvedUrl);
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
        LOG.warnv("Cedar sidecar at {0} is no longer healthy; restarting the managed container", staleUrl);
        if (staleContainerId != null) {
            try {
                lifecycleManager.stopAndRemove(staleContainerId, null);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to remove stale Cedar sidecar container {0}", staleContainerId);
            }
        }
    }

    private void startContainer() {
        String image = config.services().verifiedpermissions().cedarImage();
        LOG.infov("Starting Cedar sidecar container using image {0}", image);
        String containerName = ContainerStorageHelper.dockerName(config, CONTAINER_NAME);
        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDynamicPort(CEDAR_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(CEDAR_PORT);
        containerId = info.containerId();
        String url = "http://" + endpoint;
        waitForHealth(url);
        resolvedUrl = url;
        LOG.infov("Cedar sidecar is ready at {0}", resolvedUrl);
    }

    private boolean probeHealth(String baseUrl) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(baseUrl + "/health").toURL().openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            LOG.debugv(e, "Cedar sidecar health probe failed for {0}", baseUrl);
            return false;
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
                throw new IllegalStateException("Interrupted while waiting for Cedar sidecar", e);
            }
        }
        throw new IllegalStateException("Cedar sidecar did not become healthy within " + HEALTH_POLL_MAX_MS + " ms");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping Cedar sidecar container");
        lifecycleManager.stopAndRemove(containerId, null);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
