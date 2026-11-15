package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
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
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean fetched = fetchTarball(externalConnectionName, namespace, packageName, version,
                in -> in.transferTo(buffer));
        return fetched ? Optional.of(new FetchedAsset(buffer.toByteArray())) : Optional.empty();
    }

    /** One real tarball's size, fetched straight to {@code destination} without ever being held
     * whole in memory. */
    public record FetchedFile(long size) {}

    /**
     * {@link #fetchTarball(String, String, String, String)}'s own streamed sibling, for a
     * tarball too large to ever hold as one {@code byte[]} (~2 GiB, the JVM array limit): the
     * confirmed-live response streamed straight to {@code destination} instead of into a
     * resident array. Capped at {@code CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} during the
     * copy itself, via {@link AssetDigestingInputStream}, rather than downloaded in full and
     * checked only afterward: the real registry's own tarball size is otherwise unbounded, so
     * without this an oversized one would still be fully pulled across the network and written to
     * local disk before ever being rejected.
     */
    public Optional<FetchedFile> fetchTarball(String externalConnectionName, String namespace, String packageName,
            String version, Path destination) {
        long[] size = new long[1];
        boolean fetched = fetchTarball(externalConnectionName, namespace, packageName, version,
                in -> size[0] = AssetDigestingInputStream.copyCapped(in, destination,
                        CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES));
        return fetched ? Optional.of(new FetchedFile(size[0])) : Optional.empty();
    }

    private interface BodyConsumer {
        void accept(InputStream body) throws IOException;
    }

    private boolean fetchTarball(String externalConnectionName, String namespace, String packageName,
            String version, BodyConsumer consumer) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), tarballPath(namespace, packageName, version));
        return sendToConsumer(uri, "*/*", namespace, packageName, consumer);
    }

    private Optional<FetchedAsset> send(URI uri, String accept, String namespace, String packageName) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean fetched = sendToConsumer(uri, accept, namespace, packageName, in -> in.transferTo(buffer));
        return fetched ? Optional.of(new FetchedAsset(buffer.toByteArray())) : Optional.empty();
    }

    /** The one real fetch every public method here shares: every redirect/host decision npm's
     * fixed, single-base-URL registry needs none of, but the digest-once/stream-once discipline
     * still matters for {@link #fetchTarball(String, String, String, String, Path)}. Also shares
     * the worst-case download timeout with the small packument fetches that go through this same
     * method: the real tarball this can fetch is up to 5 GB, and its real size isn't known until
     * the response itself arrives, so a flat, small timeout sized for metadata alone would cancel
     * a legitimately large, still-in-progress download.
     */
    private boolean sendToConsumer(URI uri, String accept, String namespace, String packageName,
            BodyConsumer consumer) {
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(SidecarUriUtils.maxTransferTimeout())
                    .header("Accept", accept)
                    .GET()
                    .build();
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0} {1} from the public npm registry: {2}", namespace, packageName,
                    e.getMessage());
            return false;
        }
        if (response.statusCode() != 200) {
            StreamingResponses.closeQuietly(response.body());
            return false;
        }
        try (InputStream body = response.body()) {
            consumer.accept(body);
            return true;
        } catch (IOException e) {
            LOG.warnv("Could not read {0} {1} from the public npm registry: {2}", namespace, packageName,
                    e.getMessage());
            return false;
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
