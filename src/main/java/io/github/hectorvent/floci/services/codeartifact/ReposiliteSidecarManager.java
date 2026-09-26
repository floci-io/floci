package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.SidecarHealthHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Lazily starts and manages the shared Reposilite container that backs CodeArtifact's Maven
 * format. Unlike the floci-io/floci-sidecars contract sidecars (Cedar, GraphQL), Reposilite is a
 * stock upstream image: there is no sidecar contract to speak, so readiness is just its own
 * {@code GET /api/status/health} and management calls authenticate with a token this manager
 * bootstraps the container with, generated fresh per process.
 *
 * <p>One repository-agnostic container backs every CodeArtifact domain/repository pair; a
 * CodeArtifact repository maps to a named Reposilite repository provisioned on first use (see
 * {@link ReposiliteSidecarClient#ensureRepository}), not to its own container.
 *
 * <p>Implements {@link ContainerTeardown} rather than observing {@code ShutdownEvent} directly:
 * {@code ContainerTeardowns.stopAll} already runs every implementation both at process shutdown
 * and on {@code /state/reset}/{@code /state/nuke}, so a reset now actually stops the managed
 * container instead of leaving it running with every {@code CodeArtifactRepository} record that
 * named it gone.
 */
@ApplicationScoped
public class ReposiliteSidecarManager implements ContainerTeardown {
    static final String IMAGE_ENV = "FLOCI_SERVICES_CODEARTIFACT_MAVEN_IMAGE";
    static final String URL_ENV = "FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL";
    static final String MANAGED_TOKEN_NAME = "floci-manager";

    private static final Logger LOG = Logger.getLogger(ReposiliteSidecarManager.class);
    private static final String CONTAINER_NAME = "floci-reposilite";
    private static final int REPOSILITE_PORT = 8080;
    private static final String HEALTH_PATH = "/api/status/health";
    private static final int GENERATED_SECRET_BYTES = 24;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final String managedSecret;

    private volatile String resolvedUrl;
    private volatile String containerId;
    private volatile String resolvedTokenName;
    private volatile String resolvedTokenSecret;

    @Inject
    public ReposiliteSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                     EmulatorConfig config) {
        this(containerBuilder, lifecycleManager, config, generateSecret());
    }

    ReposiliteSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                              EmulatorConfig config, String managedSecret) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.managedSecret = managedSecret;
    }

    /**
     * Whether there could possibly be anything on the Reposilite side to release: either
     * {@link #ensureReady()} has already resolved an endpoint in this process, or
     * {@code FLOCI_SERVICES_CODEARTIFACT_MAVEN_URL} names an external instance this process has
     * simply not talked to yet. Never starts anything itself, unlike {@link #ensureReady()}.
     *
     * <p>The two cases are not the same guarantee. A managed container keeps no volume, so one
     * that was never started this process could not hold anything, full stop. An external,
     * pre-configured instance's lifecycle is independent of this process's {@code resolvedUrl}: it
     * may already hold real data from an earlier Floci run, so "this process has not resolved it
     * yet" must not be read as "nothing is there" the way it can for the managed case.
     */
    public boolean isStarted() {
        if (resolvedUrl != null) {
            return true;
        }
        Optional<String> configuredUrl = config.services().codeartifact().mavenUrl();
        return configuredUrl.isPresent() && !configuredUrl.get().isBlank();
    }

    /** Base URL of a ready Reposilite instance, starting the managed container if needed. */
    public synchronized String ensureReady() {
        if (resolvedUrl != null) {
            if (containerId == null || SidecarHealthHelper.probeHealth(resolvedUrl, HEALTH_PATH)) {
                return resolvedUrl;
            }
            discardStaleManagedEndpoint();
        }
        Optional<String> configuredUrl = config.services().codeartifact().mavenUrl();
        if (configuredUrl.isPresent() && !configuredUrl.get().isBlank()) {
            String url = trimTrailingSlash(configuredUrl.get());
            String token = config.services().codeartifact().mavenToken().orElse("");
            int separator = token.indexOf(':');
            if (separator <= 0) {
                throw new IllegalStateException(URL_ENV + " is set but " + "the matching token property "
                        + "(name:secret) is missing or malformed.");
            }
            resolvedTokenName = token.substring(0, separator);
            resolvedTokenSecret = token.substring(separator + 1);
            SidecarHealthHelper.probeHealth(url, HEALTH_PATH);
            resolvedUrl = url;
            LOG.infov("Using pre-configured Reposilite sidecar URL: {0}", resolvedUrl);
            return resolvedUrl;
        }
        startContainer();
        return resolvedUrl;
    }

    /** {@code Authorization} header value for the token {@link #ensureReady()} authenticated with. */
    public String basicAuthHeader() {
        String credentials = resolvedTokenName + ":" + resolvedTokenSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private void discardStaleManagedEndpoint() {
        String staleUrl = resolvedUrl;
        String staleContainerId = containerId;
        resolvedUrl = null;
        containerId = null;
        LOG.warnv("Reposilite sidecar at {0} is no longer healthy; restarting the managed container", staleUrl);
        if (staleContainerId != null) {
            try {
                lifecycleManager.stopAndRemove(staleContainerId, null);
            } catch (Exception e) {
                LOG.debugv(e, "Failed to remove stale Reposilite sidecar container {0}", staleContainerId);
            }
        }
    }

    private void startContainer() {
        String image = config.services().codeartifact().mavenImage();
        LOG.infov("Starting Reposilite sidecar container using image {0}", image);
        String containerName = ContainerStorageHelper.dockerName(config, CONTAINER_NAME);
        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("REPOSILITE_OPTS", "--token " + MANAGED_TOKEN_NAME + ":" + managedSecret)
                .withDynamicPort(REPOSILITE_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        EndpointInfo endpoint = info.getEndpoint(REPOSILITE_PORT);
        containerId = info.containerId();
        resolvedTokenName = MANAGED_TOKEN_NAME;
        resolvedTokenSecret = managedSecret;
        String url = "http://" + endpoint;
        SidecarHealthHelper.waitForHealth(url, HEALTH_PATH);
        resolvedUrl = url;
        LOG.infov("Reposilite sidecar is ready at {0}", resolvedUrl);
    }

    @Override
    public void stopManagedContainers() {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping Reposilite sidecar container");
        lifecycleManager.stopAndRemove(containerId, null);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[GENERATED_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
