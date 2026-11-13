package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A real outbound client for the one public NuGet repository CodeArtifact's {@code
 * public:nuget-org} external connection names: {@code https://api.nuget.org/v3-flatcontainer},
 * the NuGet V3 {@code PackageBaseAddress/3.0.0} resource real tooling resolves against for a
 * fixed-version install, confirmed against {@code docs.microsoft.com}'s own NuGet V3 API
 * reference ("Package Content" resource, {@code @type PackageBaseAddress/3.0.0}) rather than
 * inferred. Unlike Maven, AWS's own docs state NuGet packages "only have one asset per package
 * version", the {@code .nupkg} itself, at the deterministic path {@code
 * <id-lower>/<version-lower>/<id-lower>.<version-lower>.nupkg} the same reference documents.
 *
 * <p>Every failure mode (an unknown connection name, a real non-200 response, a network error)
 * resolves to {@link Optional#empty()} rather than throwing: a caller here is always deciding
 * whether to retain a package version from an external source, and AWS's own documented behavior
 * for an unreachable external repository is to behave exactly as if the asset were not found,
 * with no special error surfaced to the client.
 */
@ApplicationScoped
public class NuGetExternalConnectionClient {

    private static final Logger LOG = Logger.getLogger(NuGetExternalConnectionClient.class);

    private static final Map<String, String> BASE_URLS = Map.of(
            "public:nuget-org", "https://api.nuget.org/v3-flatcontainer");

    private final HttpClient httpClient;
    private final Map<String, String> baseUrls;

    @Inject
    public NuGetExternalConnectionClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), BASE_URLS);
    }

    /** For tests: a fake base-URL map, so the wire contract can be exercised against a local
     * {@code HttpServer} fake instead of the real public repository. */
    NuGetExternalConnectionClient(HttpClient httpClient, Map<String, String> baseUrls) {
        this.httpClient = httpClient;
        this.baseUrls = baseUrls;
    }

    /** One package version's real {@code .nupkg} bytes, fetched from the real public repository. */
    public record FetchedAsset(byte[] content) {}

    /**
     * Fetches {@code packageId}/{@code version}'s one real asset, the {@code .nupkg} itself, from
     * the real public repository {@code externalConnectionName} names.
     */
    public Optional<FetchedAsset> fetchPackage(String externalConnectionName, String packageId, String version) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean fetched = fetchPackage(externalConnectionName, packageId, version, in -> in.transferTo(buffer));
        return fetched ? Optional.of(new FetchedAsset(buffer.toByteArray())) : Optional.empty();
    }

    /** One package version's real {@code .nupkg} size, fetched straight to {@code destination}
     * without ever being held whole in memory. */
    public record FetchedFile(long size) {}

    /**
     * {@link #fetchPackage(String, String, String)}'s own streamed sibling, for a package too
     * large to ever hold as one {@code byte[]} (~2 GiB, the JVM array limit): the confirmed-live
     * response streamed straight to {@code destination} instead of into a resident array. Capped
     * at {@code CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} during the copy itself, via {@link
     * AssetDigestingInputStream}, rather than downloaded in full and checked only afterward: the
     * real registry's own {@code .nupkg} size is otherwise unbounded, so without this an
     * oversized one would still be fully pulled across the network and written to local disk
     * before ever being rejected.
     */
    public Optional<FetchedFile> fetchPackage(String externalConnectionName, String packageId, String version,
            Path destination) {
        long[] size = new long[1];
        boolean fetched = fetchPackage(externalConnectionName, packageId, version,
                in -> size[0] = AssetDigestingInputStream.copyCapped(in, destination,
                        CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES));
        return fetched ? Optional.of(new FetchedFile(size[0])) : Optional.empty();
    }

    private interface BodyConsumer {
        void accept(InputStream body) throws IOException;
    }

    /**
     * The one real fetch both {@link #fetchPackage(String, String, String)} and {@link
     * #fetchPackage(String, String, String, Path)} share. Uses the worst-case download timeout:
     * the real {@code .nupkg} this fetches can be up to 5 GB, and its real size isn't known until
     * the response itself arrives, so a flat 20 seconds would cancel a legitimately large,
     * still-in-progress download.
     */
    private boolean fetchPackage(String externalConnectionName, String packageId, String version,
            BodyConsumer consumer) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(baseUrl, packageId, version))
                    .timeout(SidecarUriUtils.maxTransferTimeout())
                    .GET()
                    .build();
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0} {1} from external connection {2}: {3}", packageId, version,
                    externalConnectionName, e.getMessage());
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
            LOG.warnv("Could not read {0} {1} from external connection {2}: {3}", packageId, version,
                    externalConnectionName, e.getMessage());
            return false;
        }
    }


    /** Whether {@code packageId}/{@code version} exists in the real public repository, without
     * downloading it. */
    public boolean exists(String externalConnectionName, String packageId, String version) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(baseUrl, packageId, version))
                    .timeout(Duration.ofSeconds(20))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            return httpClient.send(request, BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv("Could not check {0} {1} in external connection {2}: {3}", packageId, version,
                    externalConnectionName, e.getMessage());
            return false;
        }
    }

    /**
     * The real NuGet V3 flat-container path for one package version's {@code .nupkg}: {@code
     * <id-lower>/<version-lower>/<id-lower>.<version-lower>.nupkg}, confirmed against the same
     * reference this class's own javadoc cites. {@code version} is {@link NuGetVersions#normalize}d
     * here, not just lowercased (found by Greptile's review): confirmed directly against the real
     * {@code api.nuget.org/v3-flatcontainer}, which 404s on an equivalent-but-not-canonical
     * spelling (AWS's own worked example, {@code 1.00} for {@code 1.0.0}) even though it accepts
     * mixed case, since the real storage only ever holds the one canonical path NuGet's own {@code
     * NuGetVersion.ToNormalizedString()} would produce; {@link #exists}/{@link #fetchPackage}'s own
     * caller only ever validated that spelling against BaGetter's own, already-normalized listing
     * (see {@code NuGetVersions}' own javadoc), never against what the real upstream path needs.
     * The extra {@code toLowerCase} around the normalized result is deliberate: {@code
     * NuGetVersions#normalize} returns a version it cannot confidently parse completely
     * unchanged, case included, so without this, a version that used to always reach here
     * lowercased (the old behavior this replaces) could regress to a mixed-case segment for
     * exactly the inputs normalize gives up on, a no-op for every version it actually normalizes
     * since that result is already lowercase. Built the same safe way {@code
     * MavenExternalConnectionClient}'s own request builder does: the
     * three-argument {@link URI} constructor percent-encodes a stray {@code #} or {@code ?} in an
     * untrusted segment instead of letting it truncate the path at a fragment or reinterpret the
     * rest as a query string, and {@link SidecarUriUtils#combine} then joins that already-encoded
     * path onto {@code baseUrl} without re-encoding (and so without corrupting) either side.
     */
    private static URI requestUri(String baseUrl, String packageId, String version) {
        String lowerId = packageId.toLowerCase(Locale.ROOT);
        String lowerVersion = NuGetVersions.normalize(version).toLowerCase(Locale.ROOT);
        String path = lowerId + "/" + lowerVersion + "/" + lowerId + "." + lowerVersion + ".nupkg";
        try {
            String encodedPath = new URI(null, null, "/" + path, null, null).getRawPath();
            return SidecarUriUtils.combine(URI.create(baseUrl), encodedPath);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not build an external-connection request URI for " + packageId
                    + " " + version, e);
        }
    }
}
