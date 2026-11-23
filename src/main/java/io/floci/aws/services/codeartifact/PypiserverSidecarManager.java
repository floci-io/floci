package io.floci.aws.services.codeartifact;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import io.floci.aws.core.common.docker.RunLabelledContainers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

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
    private final SidecarRepositoryLease lease;
    private final RunLabelledContainers runContainers;

    @Inject
    public PypiserverSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                     EmulatorConfig config, @Named("codeartifact") RunLabelledContainers runContainers) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.runContainers = runContainers;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
        this.lease = new SidecarRepositoryLease("pypiserver", pool);
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
        lease.release(pypiRepositoryId);
    }

    public void registerRepository(String pypiRepositoryId) {
        lease.registerRepository(pypiRepositoryId);
    }

    public void beginUse(String pypiRepositoryId) {
        lease.beginUse(pypiRepositoryId);
    }

    public void endUse(String pypiRepositoryId) {
        lease.endUse(pypiRepositoryId);
    }

    /**
     * Whether this pypi repository's own pypiserver container is already running, without starting
     * one for a repository that has never used pypi at all. Every CodeArtifact repository gets a
     * pypi sidecar id at creation regardless of whether it is ever actually used through pypi (see
     * {@code CodeArtifactService#ensureFormatContainerId}'s own callers), so a caller that only
     * wants to ask an already-running container what it holds must check this first rather than
     * provision a brand-new container just to find it has nothing.
     */
    public boolean isRunning(String pypiRepositoryId) {
        return pool.isTracked(pypiRepositoryId);
    }

    private PerKeyContainerPool.StartedContainer startContainer(String pypiRepositoryId) {
        String image = config.services().codeartifact().pypiImage();
        runContainers.removeLeftovers();
        String containerName = ContainerStorageHelper.dockerName(config, "floci-pypiserver-" + pypiRepositoryId
                + "-" + UUID.randomUUID());

        // Loopback-only: this container authenticates nothing on its own (auth disabled below), so
        // the Bearer check in CodeArtifactPypiController is the only thing standing between a
        // client and the backing storage. Publishing this to every interface would let anyone who
        // can reach the host bypass that check entirely, the same reasoning as Verdaccio's binding.
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("codeartifact", pypiRepositoryId, null, null))
                .withLabels(runContainers.labels())
                .withLoopbackPortBinding(PYPISERVER_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                // The image's entrypoint execs pypi-server, which installs no SIGTERM handler. As
                // PID 1 it would ignore the stop request, and every reset, repository delete and
                // shutdown would wait out the whole stop timeout for each repository's container.
                .withInit()
                .withEnv("GUNICORN_CMD_ARGS", "--workers 2 --worker-class gevent --timeout 0")
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
                        // pypiserver's own default server selection ("auto") lands on a
                        // single-threaded synchronous fallback that cannot reliably receive a
                        // real multi-GB publish body, discovered by hand-testing one end to end
                        // (the connection was dropped mid-upload). The pinned image's plain
                        // "gevent" server (one bare gevent.pywsgi process) took that job but
                        // wedges silently under concurrent first requests against a fresh
                        // repository: every connection after the health probe hangs for the
                        // client's whole timeout, even ones sent from inside the container,
                        // and the process stays up at 0% CPU serving nothing (issue #9138).
                        // "gunicorn" runs the same gevent WSGI handler behind gunicorn's own
                        // master process, which serves concurrent first use and multi-GB
                        // bodies alike; gunicorn is already present in the pinned image.
                        //
                        // GUNICORN_CMD_ARGS above does the gunicorn-side configuration, and
                        // --backend simple-dir below keeps multi-worker consistency: with the
                        // default "auto" backend, the pinned image's CachingFileBackend keeps
                        // the package index in memory per worker process, so a publish served
                        // by one worker leaves the other worker's index stale and a simple
                        // index read randomly 404s right after a 200 publish (hand-tested
                        // against the pinned image with two workers). The stateless
                        // simple-dir backend lists the package directory per request, so both
                        // workers always agree on what has been published.
                        //
                        // Two gevent workers rather than the default one: a single gevent
                        // worker can spend its whole life inside one continuously-readable
                        // large transfer, starving every other connection it accepted,
                        // health probes included (17 of 30 probes timed out against a 1.2 GB
                        // upload at local speed; with two, and the stateless backend, all
                        // 30 pass). --worker-class gevent makes that choice explicit instead
                        // of relying on the image's default selection, and --timeout 0 keeps
                        // gunicorn's arbiter from killing a worker in the middle of a
                        // multi-GB transfer.
                        "--backend", "simple-dir",
                        "--server", "gunicorn",
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
        lease.stopManagedContainers();
    }
}
