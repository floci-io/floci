package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Lazily starts and manages one Verdaccio container per CodeArtifact repository backing the
 * {@code npm} format. Unlike Reposilite (Maven), Verdaccio has no native concept of multiple
 * named repositories inside one instance, so npm repositories each get their own container
 * instead of sharing one the way {@link ReposiliteSidecarManager} does. The actual per-key
 * container lifecycle (map of running containers, per-key start lock, health poll,
 * restart-on-unhealthy, stop-all on shutdown) is generic and lives in
 * {@link PerKeyContainerPool}; this class only knows how to build and configure a Verdaccio
 * container specifically.
 *
 * <p>Each container is configured with open access (no auth, no upstream proxying) since the
 * real CodeArtifact authorization check happens once at Floci's proxy layer
 * ({@code CodeArtifactNpmDataPlane}), before a request ever reaches the container; the container
 * itself is never reachable directly by a client.
 *
 * <p>Implements {@link ContainerTeardown} rather than observing {@code ShutdownEvent} directly:
 * {@code ContainerTeardowns.stopAll} already runs every implementation both at process shutdown
 * and on {@code /state/reset}/{@code /state/nuke}, so this is what actually stops every
 * repository's container on reset, driven by what {@link #pool} is really tracking rather than by
 * which repository records still exist in storage.
 */
@ApplicationScoped
public class VerdaccioSidecarManager implements RepositorySidecarManager, ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(VerdaccioSidecarManager.class);
    private static final String FORMAT = "npm";
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
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @Inject
    public VerdaccioSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                    EmulatorConfig config, ObjectMapper mapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.mapper = mapper;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    /**
     * Base URL of a ready Verdaccio instance for this npm repository, starting its container if
     * this is the first use. Each repository's container is independent, so concurrent first-use
     * of two different repositories never blocks on each other (unlike Reposilite's one shared
     * instance, which does need a single lock around every provisioning call).
     *
     * @param publicUrl the externally reachable proxy URL for this specific repository (e.g.
     *                  {@code http://localhost:4566/codeartifact/npm/<domain>/<repository>/}),
     *                  passed to the container as {@code VERDACCIO_PUBLIC_URL} so package metadata
     *                  it returns (notably {@code dist.tarball}) points back through Floci's proxy
     *                  instead of the container's own internal, unreachable-by-the-client address.
     */
    @Override
    public String ensureReady(String npmRepositoryId, String publicUrl) {
        return pool.ensureReady(npmRepositoryId, () -> startContainer(npmRepositoryId, publicUrl));
    }

    /** Stops and removes the container for one npm repository, if one was ever started. */
    @Override
    public void release(String npmRepositoryId) {
        pool.stopContainer(npmRepositoryId);
    }

    /**
     * {@code namespace} is the npm scope without its leading {@code @}, or {@code null} for an
     * unscoped package; real npm (and Verdaccio, confirmed against a live container) serves every
     * version's tarball at {@code <scoped-or-plain-name>/-/<filename>}, with the scope, if any,
     * still carrying its {@code @} in that path even though the filename itself never includes it.
     *
     * <p>The tarball path alone is keyed by filename, not version: a package's own metadata is
     * checked first (its {@code versions.<version>.dist.tarball} entry) to confirm {@code version}
     * genuinely maps to {@code assetName} before fetching it, rather than serving whatever file
     * that name happens to resolve to under a version the caller never actually published.
     *
     * <p>{@code namespace}, {@code packageName}, and {@code assetName} are each their own
     * independently caller-supplied value, only meant to be one path segment; {@code
     * packagePath} and the final {@code /-/<assetName>} suffix supply the real {@code /}
     * separators themselves, around each value already percent-encoded on its own via
     * {@link SidecarUriUtils#encodeSegment(String)}. Without that, a {@code /} inside, say,
     * {@code assetName} would be indistinguishable from one of those real separators and could
     * splice in extra path segments.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        if (!versionHasAsset(baseUrl, packagePath(namespace, packageName), version, assetName)) {
            return Optional.empty();
        }
        return fetchTarball(baseUrl, namespace, packageName, assetName);
    }

    /** The public URL a Verdaccio sidecar is told to advertise for one npm repository. */
    public String publicUrl(String domain, String repository) {
        return config.effectiveBaseUrl() + "/codeartifact/npm/" + domain + "/" + repository + "/";
    }

    private static String packagePath(String namespace, String packageName) {
        return namespace == null
                ? SidecarUriUtils.encodeSegment(packageName)
                : "@" + SidecarUriUtils.encodeSegment(namespace) + "/" + SidecarUriUtils.encodeSegment(packageName);
    }

    /**
     * The package's metadata document as Verdaccio serves it, or empty when the package isn't
     * there. {@code baseUrl} is a backend already made ready by {@link #ensureReady}, so repeated
     * calls for one publish don't each pay another readiness probe.
     */
    public Optional<JsonNode> fetchPackageDocument(String baseUrl, String namespace, String packageName) {
        return packageDocument(baseUrl, packagePath(namespace, packageName));
    }

    /** One tarball by its filename, from a backend already made ready by {@link #ensureReady}. */
    public Optional<byte[]> fetchTarball(String baseUrl, String namespace, String packageName, String assetName) {
        HttpRequest request = tarballRequest(baseUrl, namespace, packageName, assetName);
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to fetch " + assetName, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + assetName
                    + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /**
     * The SHA-512 integrity of a stored tarball, computed by streaming its bytes so the file never
     * sits in memory. Empty when the tarball is not there.
     */
    public Optional<String> storedTarballIntegrity(String baseUrl, String namespace, String packageName,
            String assetName) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(tarballRequest(baseUrl, namespace, packageName, assetName),
                    BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to hash " + assetName, e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Could not fetch " + assetName
                        + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
            }
            MessageDigest digest = sha512();
            byte[] chunk = new byte[64 * 1024];
            for (int read = body.read(chunk); read != -1; read = body.read(chunk)) {
                digest.update(chunk, 0, read);
            }
            return Optional.of("sha512-" + Base64.getEncoder().encodeToString(digest.digest()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + assetName + " from the Verdaccio sidecar", e);
        }
    }

    private HttpRequest tarballRequest(String baseUrl, String namespace, String packageName, String assetName) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath(namespace, packageName) + "/-/"
                + SidecarUriUtils.encodeSegment(assetName));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required by every Java runtime", e);
        }
    }

    private Optional<JsonNode> packageDocument(String baseUrl, String packagePath) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to look up " + packagePath, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not look up " + packagePath + " on the Verdaccio sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        try {
            return Optional.of(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Verdaccio's package metadata for " + packagePath, e);
        }
    }

    private boolean versionHasAsset(String baseUrl, String packagePath, String version, String assetName) {
        Optional<JsonNode> document = packageDocument(baseUrl, packagePath);
        if (document.isEmpty()) {
            return false;
        }
        JsonNode versionNode = document.get().path("versions").path(version);
        if (versionNode.isMissingNode()) {
            return false;
        }
        String tarballUrl = versionNode.path("dist").path("tarball").asText("");
        String tarballFilename = tarballUrl.substring(tarballUrl.lastIndexOf('/') + 1);
        return assetName.equals(tarballFilename);
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
