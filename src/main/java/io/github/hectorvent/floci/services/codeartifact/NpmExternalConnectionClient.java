package io.github.hectorvent.floci.services.codeartifact;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A real outbound client for the one public npm repository CodeArtifact's {@code public:npmjs}
 * external connection names: {@code https://registry.npmjs.org}, confirmed live, not inferred.
 *
 * <p>Unlike Maven's GAV layout and NuGet's {@code {id}/{version}/{filename}} content route,
 * npm's own real wire protocol has no request shape that names a single package version: {@code
 * npm install <pkg>@<version>} always fetches the bare package's full packument first (confirmed
 * against npm's own {@code pacote} client source, {@code RegistryFetcher#packumentUrl}, which is
 * always {@code <registry>/<escapedName>}, never a version-specific path) and only picks the
 * requested version out of its {@code versions} map afterward. {@link
 * CodeArtifactNpmExternalConnectionImporter#mergedPackument} is what actually needs a version's
 * metadata before any asset is ever fetched; {@link #fetchPackument} serves that, using the same
 * {@code corgi} abbreviated format {@code pacote} itself requests by default, since every field
 * this emulator's own merge needs ({@code versions[v].dist}, {@code dist-tags}) survives that
 * trimming and a full packument can run to several megabytes for a popular package.
 *
 * <p>The real tarball path ({@link #fetchTarball}) is fetched by exact coordinate, not guessed:
 * confirmed live that the real registry serves it at a deterministic {@code
 * <packagePath>/-/<bareName>-<version>.tgz}, scope included in {@code packagePath} with a literal
 * {@code /} (unlike the packument path below, which needs the scope separator percent-encoded as
 * {@code %2F} to reach the single-package resource rather than a sub-path of it, also confirmed
 * live). {@link CodeArtifactNpmExternalConnectionImporter} always synthesizes its own rewritten
 * {@code dist.tarball} using this exact {@code <bareName>-<version>.tgz} shape, so this never
 * needs to trust whatever filename the real packument itself declared.
 *
 * <p>Every failure mode (an unknown connection name, a real non-200 response, a network error)
 * resolves to {@link Optional#empty()} rather than throwing: a caller here is always deciding
 * whether to retain a package or a package version from an external source, and AWS's own
 * documented behavior for an unreachable external repository is to behave exactly as if the
 * asset were not found, with no special error surfaced to the client.
 */
@ApplicationScoped
public class NpmExternalConnectionClient {

    private static final Logger LOG = Logger.getLogger(NpmExternalConnectionClient.class);

    private static final String CORGI_ACCEPT = "application/vnd.npm.install-v1+json; q=1.0, "
            + "application/json; q=0.8, */*";
    private static final String FULL_ACCEPT = "application/json";

    private static final Map<String, String> BASE_URLS = Map.of(
            "public:npmjs", "https://registry.npmjs.org");

    private final HttpClient httpClient;
    private final Map<String, String> baseUrls;

    @Inject
    public NpmExternalConnectionClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), BASE_URLS);
    }

    /** For tests: a fake base-URL map, so the wire contract can be exercised against a local
     * {@code HttpServer} fake instead of the real public registry. */
    NpmExternalConnectionClient(HttpClient httpClient, Map<String, String> baseUrls) {
        this.httpClient = httpClient;
        this.baseUrls = baseUrls;
    }

    /** One package's real packument, or one real tarball's bytes, fetched from the real registry. */
    public record FetchedAsset(byte[] content) {}

    /**
     * Fetches {@code namespace}/{@code packageName}'s own real, abbreviated packument from the
     * real public registry {@code externalConnectionName} names.
     */
    public Optional<FetchedAsset> fetchPackument(String externalConnectionName, String namespace,
            String packageName) {
        return fetchPackument(externalConnectionName, namespace, packageName, CORGI_ACCEPT);
    }

    /**
     * The real registry's full, uncompressed packument for {@code namespace}/{@code packageName}.
     * Confirmed live against the same package and version: the abbreviated form {@link
     * #fetchPackument} uses already carries a version's {@code dependencies}/{@code engines}, but
     * drops its {@code scripts}, and carries no {@code readme} anywhere at all (not per version,
     * and not even on the full form's own per-version entry: the real registry only ever keeps
     * one {@code readme}, at the top of the full document). Used by {@link
     * CodeArtifactNpmExternalConnectionImporter#tryImportAsset} once, per version ever actually
     * retained, not on every packument read the way {@link #fetchPackument} is: the extra cost of
     * the full form over the abbreviated one is paid at most once per version.
     */
    public Optional<FetchedAsset> fetchFullPackument(String externalConnectionName, String namespace,
            String packageName) {
        return fetchPackument(externalConnectionName, namespace, packageName, FULL_ACCEPT);
    }

    private Optional<FetchedAsset> fetchPackument(String externalConnectionName, String namespace,
            String packageName, String accept) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + packumentPathSegment(namespace, packageName));
        return send(uri, accept, namespace, packageName);
    }

    /** Fetches {@code namespace}/{@code packageName}/{@code version}'s one real tarball. */
    public Optional<FetchedAsset> fetchTarball(String externalConnectionName, String namespace, String packageName,
            String version) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), tarballPath(namespace, packageName, version));
        return send(uri, "*/*", namespace, packageName);
    }

    private Optional<FetchedAsset> send(URI uri, String accept, String namespace, String packageName) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? Optional.of(new FetchedAsset(response.body())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0} {1} from the public npm registry: {2}", namespace, packageName,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The real registry's single-package resource path: a bare, percent-encoded package name, or
     * for a scoped package, {@code @<namespace>%2F<packageName>} as one opaque segment with the
     * scope separator escaped (confirmed live: an unescaped {@code /} there 404s the single-
     * package endpoint instead of reaching it).
     */
    private static String packumentPathSegment(String namespace, String packageName) {
        String encodedName = SidecarUriUtils.encodeSegment(packageName);
        return namespace == null ? encodedName : "@" + SidecarUriUtils.encodeSegment(namespace) + "%2F" + encodedName;
    }

    private static String tarballPath(String namespace, String packageName, String version) {
        String encodedName = SidecarUriUtils.encodeSegment(packageName);
        String packagePath = namespace == null ? encodedName
                : "@" + SidecarUriUtils.encodeSegment(namespace) + "/" + encodedName;
        String tarballFilename = SidecarUriUtils.encodeSegment(packageName + "-" + version + ".tgz");
        return "/" + packagePath + "/-/" + tarballFilename;
    }
}
