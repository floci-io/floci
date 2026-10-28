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

/**
 * Starts and stops one BaGetter container per CodeArtifact repository backing the {@code nuget}
 * format, the same per-repository shape as Verdaccio (npm) and pypiserver (pypi) rather than
 * Reposilite's one shared instance. The HTTP protocol lives in {@link BagetterSidecarClient}.
 * Implements {@link ContainerTeardown} so reset, nuke, and shutdown stop every container this
 * manager started.
 *
 * <p>Configured with {@code ApiKey} left empty, matching the image's own shipped default: BaGetter
 * treats an empty configured key as "no key configured" and authenticates every request
 * unconditionally in that case (traced directly in BaGetter's own {@code ApiKeyAuthenticationService}
 * before relying on it). Real CodeArtifact authorization already happens once at Floci's proxy
 * layer, {@code CodeArtifactNuGetController}, before a request ever reaches this container, the
 * same reasoning Verdaccio's open {@code $all} access and pypiserver's {@code -a . -P .} already
 * rely on. {@code PackageDeletionBehavior} is overridden to {@code HardDelete}: the image's own
 * default, {@code Unlist}, only hides a package from listings while leaving its content in place,
 * which would leave {@code DeletePackage} unable to make a later republish of the same version
 * succeed and would leave {@code GetPackageVersionAsset} still able to fetch a "deleted" asset.
 */
@ApplicationScoped
public class BagetterSidecarManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(BagetterSidecarManager.class);
    // The pinned image's base (mcr.microsoft.com/dotnet/aspnet:9.0-alpine) sets
    // ASPNETCORE_HTTP_PORTS=8080 itself (confirmed directly against the image's own env, the
    // documented .NET 8+ convention of listening on 8080 rather than 80 in a non-root container),
    // and BagetterEndpointBuilder maps a dedicated /health route Floci uses as the readiness check.
    private static final int BAGETTER_PORT = 8080;
    private static final String HEALTH_PATH = "/health";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final PerKeyContainerPool pool;

    @Inject
    public BagetterSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
    }

    /**
     * Base URL of a ready BaGetter instance for this nuget repository, starting its container if this
     * is the first use. {@code publicUrl} is unused: BaGetter's own responses that could otherwise
     * embed a self-referential URL (its service index, registration, and search resources) are
     * never proxied verbatim by {@link CodeArtifactNuGetController}, which authors the service
     * index itself and does not implement registration or search, so there is nothing here for an
     * internal, client-unreachable address to leak into.
     */
    public String ensureReady(String nugetRepositoryId, String publicUrl) {
        return pool.ensureReady(nugetRepositoryId, () -> startContainer(nugetRepositoryId));
    }

    /** Stops and removes the container for one nuget repository, if one was ever started. */
    public void release(String nugetRepositoryId) {
        pool.stopContainer(nugetRepositoryId);
    }

    /**
     * Whether this nuget repository's own BaGetter container is already running, without starting
     * one for a repository that has never used nuget at all. Every CodeArtifact repository gets a
     * nuget sidecar id at creation regardless of whether it is ever actually used through nuget
     * (see {@code CodeArtifactService#ensureFormatContainerId}'s own callers), so a caller that
     * only wants to ask an already-running container what it holds must check this first rather
     * than provision a brand-new container just to find it has nothing.
     */
    public boolean isRunning(String nugetRepositoryId) {
        return pool.isTracked(nugetRepositoryId);
    }

    private PerKeyContainerPool.StartedContainer startContainer(String nugetRepositoryId) {
        String image = config.services().codeartifact().nugetImage();
        String containerName = ContainerStorageHelper.dockerName(config, "floci-bagetter-" + nugetRepositoryId);
        lifecycleManager.removeIfExists(containerName);

        // Loopback-only: this container authenticates nothing on its own (ApiKey left empty
        // below), so the Bearer/Basic check in CodeArtifactNuGetController is the only thing
        // standing between a client and the backing storage. Publishing this to every interface
        // would let anyone who can reach the host bypass that check entirely, the same reasoning
        // as Verdaccio's and pypiserver's own loopback binding.
        // Storage/Database are left unconfigured here: the pinned image's own Dockerfile already
        // sets Storage__Path=/data, Database__Type=Sqlite, and Database__ConnectionString to a
        // writable path under /data (confirmed directly against the image's build), so there is
        // no empty or unwritable default here to guard against the way pypiserver's "-P ." needs
        // an explicit path.
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("ApiKey", "")
                // Already the image's own shipped default; set explicitly so a future image
                // default change can't quietly start resolving packages from the real nuget.org,
                // matching the Maven, npm, and pypi proxies' own deliberate choice not to resolve
                // upstream repositories or external connections.
                .withEnv("Mirror__Enabled", "false")
                .withEnv("PackageDeletionBehavior", "HardDelete")
                .withLoopbackPortBinding(BAGETTER_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        String containerId = lifecycleManager.create(spec);
        try {
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(BAGETTER_PORT);
            String url = "http://" + endpoint;
            LOG.infov("BaGetter sidecar for nuget repository {0} is ready at {1}", nugetRepositoryId, url);
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
