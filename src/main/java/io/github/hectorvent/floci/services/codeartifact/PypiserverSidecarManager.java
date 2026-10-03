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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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
    private static final Pattern SIMPLE_INDEX_HREF = Pattern.compile("href=\"([^\"]*)\"");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final PerKeyContainerPool pool;
    private final HttpClient httpClient;

    @Inject
    public PypiserverSidecarManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                     EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.pool = new PerKeyContainerPool(lifecycleManager, HEALTH_PATH);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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

    /**
     * PyPI packages have no namespace (AWS: "Python ... package versions do not have a
     * corresponding component"). pypiserver serves every file it holds flatly at
     * {@code /packages/<filename>} regardless of which package or version it belongs to,
     * confirmed against a live container, so both are verified before serving anything: that
     * {@code packageName}'s own simple index genuinely lists {@code assetName}, and that
     * {@code assetName} itself embeds {@code version} the way every real PyPI filename does
     * (PEP 427/517 naming) rather than trusting the caller's {@code version} argument on its own.
     *
     * <p>{@code assetName} is percent-encoded via {@link SidecarUriUtils#encodeSegment(String)}
     * before joining it to the fixed {@code /packages/} prefix: without that, a {@code /} inside
     * it would be indistinguishable from a real path separator and could splice in extra path
     * segments.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        if (!filenameEmbedsVersion(assetName, packageName, version)) {
            return Optional.empty();
        }
        String baseUrl = ensureReady(repositoryContainerId, null);
        if (!packageIndexListsAsset(baseUrl, packageName, assetName)) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/packages/" + SidecarUriUtils.encodeSegment(assetName));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to fetch " + assetName, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + assetName + " from the pypiserver sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /**
     * PEP 503 normalization: lowercase, runs of {@code -_.} collapsed to one {@code -}, same as
     * {@code CodeArtifactPypiController}'s own pre-upload index check. {@code -} and {@code _}
     * collapsing to the same character here is what lets this match a filename's own escaped
     * name portion (wheels, and modern sdists, replace a name's internal {@code -}/{@code .}
     * with {@code _}) against the caller-supplied, unescaped {@code packageName}.
     */
    private static String normalizePackageName(String packageName) {
        return packageName.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    private boolean packageIndexListsAsset(String baseUrl, String packageName, String assetName) {
        String normalized = normalizePackageName(packageName);
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/simple/" + SidecarUriUtils.encodeSegment(normalized)
                + "/");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypiserver sidecar to look up " + packageName, e);
        }
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not look up " + packageName + " on the pypiserver sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        // An exact entry match, not a substring search: a shorter real filename that happens to
        // be a substring of a different, longer real filename must not pass this check.
        return SIMPLE_INDEX_HREF.matcher(response.body()).results()
                .map(match -> {
                    String href = match.group(1);
                    String filename = href.substring(href.lastIndexOf('/') + 1);
                    int fragment = filename.indexOf('#');
                    return fragment < 0 ? filename : filename.substring(0, fragment);
                })
                .anyMatch(assetName::equals);
    }

    /**
     * PEP 440 multi-letter keywords that can introduce a continuation after a {@code -} or
     * {@code .}, per the {@code packaging} library's own {@code VERSION_PATTERN}: {@code
     * alpha}/{@code beta}/{@code preview}/{@code pre}/{@code rc} are all pre-release aliases,
     * {@code post}/{@code rev} are post-release aliases, {@code dev} is the dev-release keyword.
     * The matching single-letter aliases ({@code a}, {@code b}, {@code c}, {@code r}) are handled
     * separately in {@link #versionBoundaryEndsAt}, since a bare letter needs a following digit
     * to be treated as a continuation rather than an unrelated filename extension starting there.
     */
    private static final List<String> PEP440_CONTINUATION_PREFIXES =
            List.of("alpha", "beta", "preview", "pre", "post", "rev", "dev", "rc");

    /**
     * Real PyPI filenames always embed their own version right after a {@code -} (PEP 427/517
     * naming: {@code <name>-<version>.<ext>} for an sdist, {@code <name>-<version>-<tags>.whl}
     * for a wheel), so this is checked directly against the filename rather than trusted from the
     * caller's {@code version} argument alone, which a real file from a different version of the
     * same package would otherwise sail straight through.
     *
     * <p>Checks every {@code -<version>} occurrence and requires both that what follows it
     * actually ends the version field, handled by {@link #versionBoundaryEndsAt} (requesting
     * version {@code 1.0} against a real {@code pkg-1.0.1-py3-none-any.whl} contains the literal
     * text {@code -1.0.}, even though the file's actual version is {@code 1.0.1}, not {@code
     * 1.0}), and that what precedes it is exactly {@code packageName}, normalized the same way
     * the simple-index lookup normalizes it. Without that second check, a package name that
     * itself contains a run of digits could produce a false match inside the name portion of the
     * filename instead of the real version field: a package literally named {@code foo-1.0-bar}
     * at version {@code 2.0} produces {@code foo-1.0-bar-2.0.tar.gz}, and the text {@code -1.0-}
     * inside that name would otherwise look like a complete, valid version {@code 1.0} to a
     * caller who asked for the wrong one.
     */
    private static boolean filenameEmbedsVersion(String assetName, String packageName, String version) {
        String normalizedPackageName = normalizePackageName(packageName);
        String marker = "-" + version;
        for (int idx = assetName.indexOf(marker); idx >= 0; idx = assetName.indexOf(marker, idx + 1)) {
            String prefix = assetName.substring(0, idx);
            if (normalizePackageName(prefix).equals(normalizedPackageName)
                    && versionBoundaryEndsAt(assetName, idx + marker.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code after} is where a candidate version match ends; true when the version field genuinely
     * ends there rather than continuing as a longer PEP 440 version. A {@code +} never does: PEP
     * 440 gives it exactly one meaning, introducing a non-empty local-version segment, so whatever
     * immediately follows it is always part of a longer, different version (e.g. {@code 1.0+linux}
     * is not {@code 1.0}), never an unrelated filename extension starting there.
     *
     * <p>A {@code -} or a {@code .} might not end it either, and both need the same continuation
     * check: PEP 440 allows either character to separate a release segment from a following
     * {@code <release segment>} ({@code 1.0.1}), a pre/post/dev-release suffix (every keyword in
     * {@link #PEP440_CONTINUATION_PREFIXES}, plus the single-letter aliases checked below), or,
     * only after a {@code -} specifically, an implicit post-release with no keyword at all
     * ({@code 1.0-1} means {@code 1.0.post1}) <em>unless</em> {@link #isWheelBuildTag} says the
     * digits are actually a PEP 427 wheel build tag instead, which looks identical but does end
     * the version right there. All of those start right after the matched text the same way, so
     * both separators are checked for and rejected as real continuations.
     */
    private static boolean versionBoundaryEndsAt(String assetName, int after) {
        if (after == assetName.length()) {
            return true;
        }
        char next = assetName.charAt(after);
        if (next == '+' || (next != '-' && next != '.')) {
            return false;
        }
        String remainder = assetName.substring(after + 1);
        if (remainder.isEmpty()) {
            return true;
        }
        if (Character.isDigit(remainder.charAt(0))) {
            return next == '-' && isWheelBuildTag(assetName, remainder);
        }
        for (String continuation : PEP440_CONTINUATION_PREFIXES) {
            if (remainder.startsWith(continuation)) {
                return false;
            }
        }
        // Single-letter PEP 440 aliases (a/alpha, b/beta, c/rc, r/rev/post) need a following
        // digit to count as a continuation; without one, a bare letter is far more likely to be
        // the start of an unrelated filename extension than a pre/post-release marker.
        char first = remainder.charAt(0);
        boolean singleLetterAlias = first == 'a' || first == 'b' || first == 'c' || first == 'r';
        return !(singleLetterAlias && remainder.length() > 1 && Character.isDigit(remainder.charAt(1)));
    }

    /**
     * {@code remainder} is known to start with a digit, right after a {@code -}. PEP 440's
     * implicit, keyword-less post-release numeral ({@code 1.0-1} meaning {@code 1.0.post1}) and
     * PEP 427's wheel build tag ({@code digit+ letter*}, placed between the version and the
     * python/abi/platform tags, e.g. {@code 1.0-1-py3-none-any.whl}) look identical at this
     * point: both are a bare run of digits (optionally followed by letters) right after the
     * version. They're told apart by what comes after that run: a build tag is always followed
     * by another {@code -} introducing the next wheel tag, since wheels always have those three
     * trailing tags, whereas a post-release numeral is followed directly by the file extension.
     * Only a {@code .whl} filename can have a build tag at all; an sdist's {@code -<digits>} has
     * no other valid meaning than the post-release numeral.
     */
    private static boolean isWheelBuildTag(String assetName, String remainder) {
        if (!assetName.endsWith(".whl")) {
            return false;
        }
        int i = 0;
        while (i < remainder.length() && Character.isDigit(remainder.charAt(i))) {
            i++;
        }
        while (i < remainder.length() && Character.isLetter(remainder.charAt(i))) {
            i++;
        }
        return i < remainder.length() && remainder.charAt(i) == '-';
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
