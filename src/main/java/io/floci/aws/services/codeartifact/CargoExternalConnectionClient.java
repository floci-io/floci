package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * A real outbound client for the one public Cargo (Rust) repository CodeArtifact's {@code
 * public:crates-io} external connection names (confirmed real and documented from AWS's own
 * {@code external-connection.html}, "Rust | Crates.io | public:crates-io"): the real sparse
 * HTTP index at {@code index.crates.io} and the real {@code .crate} download host at {@code
 * static.crates.io}, both confirmed live, not assumed:
 *
 * <ul>
 *   <li>{@code https://index.crates.io/config.json} (fetched live): {@code {"dl":
 *   "https://static.crates.io/crates","api":"https://crates.io"}}, confirming the download
 *   template below via the RFC 2789 "no markers" rule.</li>
 *   <li>{@code https://index.crates.io/li/bc/libc} (fetched live): a real per-crate index file,
 *   newline-delimited JSON, one line per version.</li>
 *   <li>{@code https://static.crates.io/crates/libc/0.2.169/download} (fetched live): {@code
 *   200}, {@code application/gzip}, the real {@code .crate} bytes directly, no redirect observed
 *   for this case (this client still follows redirects defensively, the same as {@link
 *   NuGetExternalConnectionClient}).</li>
 * </ul>
 *
 * <p>Every failure mode (an unknown connection name, a real non-200 response, a network error)
 * resolves to {@link Optional#empty()} rather than throwing, the same reasoning {@link
 * NuGetExternalConnectionClient}'s own javadoc gives: a caller here is always deciding whether to
 * retain something from an external source, and the documented behavior for an unreachable
 * external repository is to behave exactly as if the content were not found.
 */
@ApplicationScoped
public class CargoExternalConnectionClient {

    private static final Logger LOG = Logger.getLogger(CargoExternalConnectionClient.class);

    private static final Map<String, String> INDEX_BASE_URLS = Map.of(
            "public:crates-io", "https://index.crates.io");
    private static final Map<String, String> DOWNLOAD_BASE_URLS = Map.of(
            "public:crates-io", "https://static.crates.io/crates");
    // A real sparse-index file is plain newline-delimited JSON, one short line per published
    // version; even crates.io's own most-published crates never approach this, but the real
    // upstream's own response size is otherwise unbounded, so this is checked during the read
    // itself rather than only after buffering an arbitrarily large response whole.
    private static final int MAX_SPARSE_INDEX_BYTES = 16 * 1024 * 1024;

    private final HttpClient httpClient;
    private final Map<String, String> indexBaseUrls;
    private final Map<String, String> downloadBaseUrls;

    @Inject
    public CargoExternalConnectionClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), INDEX_BASE_URLS, DOWNLOAD_BASE_URLS);
    }

    /** For tests: fake base-URL maps, so the wire contract can be exercised against a local
     * {@code HttpServer} fake instead of the real public registry. */
    CargoExternalConnectionClient(HttpClient httpClient, Map<String, String> indexBaseUrls,
            Map<String, String> downloadBaseUrls) {
        this.httpClient = httpClient;
        this.indexBaseUrls = indexBaseUrls;
        this.downloadBaseUrls = downloadBaseUrls;
    }

    /**
     * The real, complete sparse-index bytes for {@code packageName} from the real public
     * registry {@code externalConnectionName} names: every version line this crate's real index
     * file currently carries, confirmed to be small enough to always buffer whole (a real crate's
     * own index file, unlike a {@code .crate} itself, is plain newline-delimited JSON text, never
     * sized anywhere near the asset quota).
     */
    public Optional<byte[]> fetchSparseIndex(String externalConnectionName, String packageName) {
        String baseUrl = indexBaseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + SidecarUriUtils.cratesIoBucketPath(packageName));
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0}''s real sparse index from external connection {1}: {2}", packageName,
                    externalConnectionName, e.getMessage());
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            StreamingResponses.closeQuietly(response.body());
            return Optional.empty();
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_SPARSE_INDEX_BYTES + 1);
            if (bytes.length > MAX_SPARSE_INDEX_BYTES) {
                LOG.warnv("Skipping {0}''s real sparse index from external connection {1}: larger than {2} bytes.",
                        packageName, externalConnectionName, MAX_SPARSE_INDEX_BYTES);
                return Optional.empty();
            }
            return Optional.of(bytes);
        } catch (IOException e) {
            LOG.warnv("Could not read {0}''s real sparse index from external connection {1}: {2}", packageName,
                    externalConnectionName, e.getMessage());
            return Optional.empty();
        }
    }

    /** One crate version's real size and SHA-256, fetched straight to {@code destination} without
     * ever being held whole in memory: both come from the same streamed copy, via {@link
     * AssetDigestingInputStream}, rather than a second whole-file read just to hash it afterward. */
    public record FetchedFile(long size, String sha256) {
    }

    /**
     * Fetches {@code packageName}/{@code version}'s real {@code .crate} bytes from the real
     * public registry {@code externalConnectionName} names, streamed straight to {@code
     * destination}. Capped at {@code CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} during the
     * copy itself, via {@link AssetDigestingInputStream}, the same reasoning {@link
     * NuGetExternalConnectionClient#fetchPackage(String, String, String, Path)}'s own javadoc
     * gives: the real registry's own file size is otherwise unbounded and isn't known until the
     * download is already in progress.
     */
    public Optional<FetchedFile> fetchCrate(String externalConnectionName, String packageName, String version,
            Path destination) {
        String baseUrl = downloadBaseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return Optional.empty();
        }
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + SidecarUriUtils.encodeSegment(packageName) + "/"
                + SidecarUriUtils.encodeSegment(version) + "/download");
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(SidecarUriUtils.maxTransferTimeout()).GET()
                    .build();
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0} {1} from external connection {2}: {3}", packageName, version,
                    externalConnectionName, e.getMessage());
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            StreamingResponses.closeQuietly(response.body());
            return Optional.empty();
        }
        try (InputStream body = response.body()) {
            AssetDigestingInputStream digesting = new AssetDigestingInputStream(body,
                    CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
            Files.copy(digesting, destination, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(new FetchedFile(digesting.size(), digesting.hashesHex().get("SHA-256")));
        } catch (IOException e) {
            LOG.warnv("Could not read {0} {1} from external connection {2}: {3}", packageName, version,
                    externalConnectionName, e.getMessage());
            return Optional.empty();
        }
    }
}
