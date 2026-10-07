package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PerKeyContainerPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Starts and stops one pypiserver container per CodeArtifact repository backing the {@code pypi}
 * format, the same per-repository shape as Verdaccio (npm) rather than Reposilite's one shared
 * instance. The HTTP protocol lives in {@link PypiserverSidecarClient}. Implements
 * {@link ContainerTeardown} so reset, nuke, and shutdown stop every container this manager started.
 *
 * <p>Each container is configured with authentication disabled (real CodeArtifact authorization
 * happens once at Floci's proxy layer, {@code CodeArtifactPypiController}, before a request ever
 * reaches this container) and {@code --disable-fallback}, matching the Maven and npm proxies' own
 * deliberate choice not to resolve upstream repositories or external connections: pypiserver
 * otherwise redirects a package missing from its local index to the real, public PyPI.
 */
@ApplicationScoped
public class PypiserverSidecarManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(PypiserverSidecarManager.class);
    private static final int PYPISERVER_PORT = 8080;
    private static final String HEALTH_PATH = "/health";
    private static final String PACKAGES_DIR = "/data/packages";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final PerKeyContainerPool pool;

    @Inject
    public PypiserverSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                     EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
    }

    /**
     * Base URL of a ready pypiserver instance for this pypi repository, starting its container if
     * this is the first use. {@code publicUrl} is unused: pypiserver's simple-index responses link to
     * package files with a root-relative path, which pip and twine resolve against whatever host
     * they actually connected to, so there is nothing here for an internal, client-unreachable
     * address to leak into.
     */
    public String ensureReady(String pypiRepositoryId, String publicUrl) {
        return pool.ensureReady(pypiRepositoryId, () -> startContainer(pypiRepositoryId));
    }

    /** Stops and removes the container for one pypi repository, if one was ever started. */
    public void release(String pypiRepositoryId) {
        pool.stopContainer(pypiRepositoryId);
    }

    private PerKeyContainerPool.StartedContainer startContainer(String pypiRepositoryId) {
        String image = config.services().codeartifact().pypiImage();
        String containerName = ContainerStorageHelper.dockerName(config, "floci-pypiserver-" + pypiRepositoryId);
        lifecycleManager.removeIfExists(containerName);

        // Loopback-only: this container authenticates nothing on its own (auth disabled below), so
        // the Bearer check in CodeArtifactPypiController is the only thing standing between a
        // client and the backing storage. Publishing this to every interface would let anyone who
        // can reach the host bypass that check entirely, the same reasoning as Verdaccio's binding.
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLoopbackPortBinding(PYPISERVER_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .withCmd(List.of(
                        "run",
                        "-p", String.valueOf(PYPISERVER_PORT),
                        // Disables pypiserver's own auth entirely (real CodeArtifact authorization
                        // is already enforced before a request reaches this container) and its
                        // default redirect-to-real-PyPI for a package missing from the local index,
                        // matching the Maven and npm proxies' own choice not to resolve upstreams.
                        "-a", ".",
                        "-P", ".",
                        "--disable-fallback",
                        "--health-endpoint", HEALTH_PATH,
                        PACKAGES_DIR))
                .build();
        String containerId = lifecycleManager.create(spec);
        try {
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(PYPISERVER_PORT);
            String url = "http://" + endpoint;
            LOG.infov("pypiserver sidecar for pypi repository {0} is ready at {1}", pypiRepositoryId, url);
            return new PerKeyContainerPool.StartedContainer(containerId, url);
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, null);
            throw e;
        }
    }

    @Override
    public void stopManagedContainers() {
        pool.stopAll();
    }
}
