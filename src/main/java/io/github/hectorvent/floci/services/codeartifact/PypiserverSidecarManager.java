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
 * Lazily starts and manages one pypiserver container per CodeArtifact repository backing the
 * {@code pypi} format. Like Verdaccio (npm), pypiserver has no native concept of multiple named
 * indexes inside one instance, so pypi repositories each get their own container instead of
 * sharing one the way Reposilite's Maven repositories do. The per-key container lifecycle (map of
 * running containers, per-key start lock, health poll, restart-on-unhealthy, stop-all on
 * shutdown/reset) is generic and lives in {@link PerKeyContainerPool}; this class only knows how
 * to build and configure a pypiserver container specifically.
 *
 * <p>Each container is configured with authentication disabled (real CodeArtifact authorization
 * happens once at Floci's proxy layer, {@code CodeArtifactPypiController}, before a request ever
 * reaches this container) and {@code --disable-fallback}, matching the Maven and npm proxies' own
 * deliberate choice not to resolve upstream repositories or external connections: pypiserver
 * otherwise redirects a package missing from its local index to the real, public PyPI.
 *
 * <p>Unlike Verdaccio, this needs no public-URL environment variable: pypiserver's simple-index
 * responses link to package files with a root-relative path ({@code /packages/<file>}), which pip
 * and twine resolve against whatever host they actually connected to, not an address the
 * container returns itself. There is nothing here for an internal, client-unreachable address to
 * leak into. That root-relative path still needs rewriting to this repository's own
 * {@code /codeartifact/pypi/<domain>/<repository>} prefix before a client sees it, since the
 * download route lives there, not at the proxy's bare root; {@code CodeArtifactPypiController}
 * does that rewrite, not this class.
 */
@ApplicationScoped
public class PypiserverSidecarManager implements RepositorySidecarManager, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(PypiserverSidecarManager.class);
    private static final String FORMAT = "pypi";
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

    @Override
    public String format() {
        return FORMAT;
    }

    /**
     * Base URL of a ready pypiserver instance for this pypi repository, starting its container if
     * this is the first use. {@code publicUrl} is unused: see the class javadoc for why pypiserver
     * needs no self-referential URL rewritten.
     */
    @Override
    public String ensureReady(String pypiRepositoryId, String publicUrl) {
        return pool.ensureReady(pypiRepositoryId, () -> startContainer(pypiRepositoryId));
    }

    /** Stops and removes the container for one pypi repository, if one was ever started. */
    @Override
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
