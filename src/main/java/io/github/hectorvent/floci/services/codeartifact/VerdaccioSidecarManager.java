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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Starts and stops one Verdaccio container per CodeArtifact npm repository, unlike Reposilite's one
 * shared instance. The HTTP protocol lives in {@link VerdaccioSidecarClient}. Implements
 * {@link ContainerTeardown} so reset, nuke, and shutdown stop every container this manager started.
 */
@ApplicationScoped
public class VerdaccioSidecarManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(VerdaccioSidecarManager.class);
    private static final int VERDACCIO_PORT = 4873;
    private static final String HEALTH_PATH = "/-/ping";
    private static final String CONFIG_REMOTE_DIR = "/verdaccio/conf";
    private static final String CONFIG_FILE_NAME = "config.yaml";

    /**
     * Open access for both read and publish (real CodeArtifact auth is already enforced before a
     * request reaches this container) and no uplinks, matching the Maven proxy's own deliberate
     * choice not to resolve upstream repositories or external connections.
     */
    private static final String CONFIG_YAML = """
            storage: /verdaccio/storage/data
            plugins: /verdaccio/plugins
            auth:
              htpasswd:
                file: /verdaccio/storage/htpasswd
            uplinks: {}
            packages:
              '**':
                access: $all
                publish: $all
                unpublish: $all
            listen: 0.0.0.0:4873
            log:
              type: stdout
              format: pretty
              level: warn
            """;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final PerKeyContainerPool pool;

    @Inject
    public VerdaccioSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
    }

    /**
     * Base URL of a ready Verdaccio for this repository, starting its container on first use.
     * {@code publicUrl} becomes {@code VERDACCIO_PUBLIC_URL}, so the {@code dist.tarball} links it
     * returns point back through Floci's proxy.
     */
    public String ensureReady(String npmRepositoryId, String publicUrl) {
        return pool.ensureReady(npmRepositoryId, () -> startContainer(npmRepositoryId, publicUrl));
    }

    /** Stops and removes the container for one npm repository, if one was ever started. */
    public void release(String npmRepositoryId) {
        pool.stopContainer(npmRepositoryId);
    }

    private PerKeyContainerPool.StartedContainer startContainer(String npmRepositoryId, String publicUrl) {
        String image = config.services().codeartifact().npmImage();
        String containerName = ContainerStorageHelper.dockerName(config, "floci-verdaccio-" + npmRepositoryId);
        lifecycleManager.removeIfExists(containerName);

        // Loopback-only: this container has no auth of its own (open $all access, matching the
        // Maven proxy's own deliberate choice not to resolve upstreams), so the Bearer check in
        // CodeArtifactNpmDataPlane is the only thing standing between a client and the backing
        // storage. Publishing this to every interface would let anyone who can reach the host
        // bypass that check entirely.
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("VERDACCIO_PUBLIC_URL", publicUrl)
                .withLoopbackPortBinding(VERDACCIO_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withEmbeddedDns()
                .withLogRotation()
                .build();
        String containerId = lifecycleManager.create(spec);
        try {
            copyConfig(containerId);
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(VERDACCIO_PORT);
            String url = "http://" + endpoint;
            LOG.infov("Verdaccio sidecar for npm repository {0} is ready at {1}", npmRepositoryId, url);
            return new PerKeyContainerPool.StartedContainer(containerId, url);
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, null);
            throw e;
        }
    }

    /**
     * Copies {@code config.yaml} into the created, not yet started, container. A copy rather than
     * a bind mount for the same reason {@code ContainerLifecycleManager} copies in the CA bundle:
     * when Floci itself runs in Docker, its own persistent path is not a host path the daemon can
     * mount into a sibling container.
     */
    private void copyConfig(String containerId) {
        byte[] content = CONFIG_YAML.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 512);
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                TarArchiveEntry entry = new TarArchiveEntry(CONFIG_FILE_NAME);
                entry.setSize(content.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            lifecycleManager.getDockerClient().copyArchiveToContainerCmd(containerId)
                    .withRemotePath(CONFIG_REMOTE_DIR)
                    .withTarInputStream(new ByteArrayInputStream(archive.toByteArray()))
                    .exec();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the Verdaccio config into container " + containerId, e);
        }
    }

    @Override
    public void stopManagedContainers() {
        pool.stopAll();
    }
}
