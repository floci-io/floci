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
import java.util.Map;
import java.util.Optional;

/**
 * A real outbound client for the five public Maven repositories CodeArtifact's {@code
 * public:maven-*} external connections name, confirmed live (not AWS's own hidden internal proxy
 * target, which is undocumented, but the same well-known public host any real {@code mvn}/Gradle
 * build already resolves these coordinates against):
 *
 * <ul>
 *   <li>{@code public:maven-central}: {@code https://repo.maven.apache.org/maven2}, the Super
 *   POM's and Gradle's own {@code MAVEN_CENTRAL_URL} default.</li>
 *   <li>{@code public:maven-clojars}: {@code https://repo.clojars.org}.</li>
 *   <li>{@code public:maven-commonsware}: {@code https://s3.amazonaws.com/repo.commonsware.com},
 *   confirmed against a real CWAC library's own published README.</li>
 *   <li>{@code public:maven-googleandroid}: {@code https://dl.google.com/dl/android/maven2}, the
 *   explicit alternative Android's own docs give for {@code google()}'s older, pre-4.1-Gradle
 *   form ({@code https://maven.google.com} 301-redirects a GAV request here).</li>
 *   <li>{@code public:maven-gradleplugins}: {@code https://plugins.gradle.org/m2}, what {@code
 *   gradlePluginPortal()} resolves to.</li>
 * </ul>
 *
 * <p>Every failure mode (an unknown connection name, a real non-200 response, a network error)
 * resolves to {@link Optional#empty()} rather than throwing: a caller here is always deciding
 * whether to retain a package version from an external source, and AWS's own documented behavior
 * for an unreachable external repository is to behave exactly as if the asset were not found,
 * with no special error surfaced to the client ("the only workaround is to try again later").
 */
@ApplicationScoped
public class MavenExternalConnectionClient {

    private static final Logger LOG = Logger.getLogger(MavenExternalConnectionClient.class);

    private static final Map<String, String> BASE_URLS = Map.of(
            "public:maven-central", "https://repo.maven.apache.org/maven2",
            "public:maven-clojars", "https://repo.clojars.org",
            "public:maven-commonsware", "https://s3.amazonaws.com/repo.commonsware.com", // partition-literal: CommonsWare's real repo is a commercial-partition S3 bucket, same for every partition Floci emulates
            "public:maven-googleandroid", "https://dl.google.com/dl/android/maven2",
            "public:maven-gradleplugins", "https://plugins.gradle.org/m2");

    private final HttpClient httpClient;
    private final Map<String, String> baseUrls;

    @Inject
    public MavenExternalConnectionClient() {
        // java.net.http.HttpClient's own default redirect policy is NEVER, so a real public
        // repository that redirects an artifact request (a CDN, a moved bucket path) would
        // otherwise come back as a plain non-200, read here as "the asset does not exist" rather
        // than followed. NORMAL follows redirects except from HTTPS down to HTTP, matching what a
        // real mvn/Gradle build's own HTTP client does.
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), BASE_URLS);
    }

    /** For tests: a fake base-URL map, so the wire contract can be exercised against a local
     * {@code HttpServer} fake instead of the real public repositories. */
    MavenExternalConnectionClient(HttpClient httpClient, Map<String, String> baseUrls) {
        this.httpClient = httpClient;
        this.baseUrls = baseUrls;
    }

    /** One asset's real bytes, fetched from a public Maven repository. */
    public record FetchedAsset(byte[] content) {}

    /**
     * Fetches {@code gav} (the same {@code <groupPath>/<artifactId>/<version>/<filename>} shape
     * Reposilite itself uses, since both are standard Maven2 repository layouts) from the real
     * public repository {@code externalConnectionName} names.
     */
    public Optional<FetchedAsset> fetchAsset(String externalConnectionName, String gav) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean fetched = fetchAsset(externalConnectionName, gav, in -> in.transferTo(buffer));
        return fetched ? Optional.of(new FetchedAsset(buffer.toByteArray())) : Optional.empty();
    }

    /** One asset's real size, fetched straight to {@code destination} without ever being held whole in memory. */
    public record FetchedFile(long size) {}

    /**
     * {@link #fetchAsset(String, String)}'s own streamed sibling, for a release asset too large to
     * ever hold as one {@code byte[]} (~2 GiB, the JVM array limit): the confirmed-live response
     * streamed straight to {@code destination} instead of into a resident array. Capped at {@code
     * CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES} during the copy itself, via {@link
     * AssetDigestingInputStream}, rather than downloaded in full and checked only afterward: the
     * real public repository's own file size is otherwise unbounded, so without this an oversized
     * one would still be fully pulled across the network and written to local disk before ever
     * being rejected.
     */
    public Optional<FetchedFile> fetchAsset(String externalConnectionName, String gav, Path destination) {
        long[] size = new long[1];
        boolean fetched = fetchAsset(externalConnectionName, gav,
                in -> size[0] = AssetDigestingInputStream.copyCapped(in, destination,
                        CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES));
        return fetched ? Optional.of(new FetchedFile(size[0])) : Optional.empty();
    }

    private interface BodyConsumer {
        void accept(InputStream body) throws IOException;
    }

    /** The one real fetch both {@link #fetchAsset(String, String)} and {@link #fetchAsset(String,
     * String, Path)} share, as well as the small {@code maven-metadata.xml} fetch. Uses the
     * worst-case download timeout: a release asset fetched here can be up to 5 GB, and its real
     * size isn't known until the response itself arrives, so a flat 20 seconds would cancel a
     * legitimately large, still-in-progress download (harmless for the small metadata fetch,
     * which never takes anywhere near this long anyway).
     */
    private boolean fetchAsset(String externalConnectionName, String gav, BodyConsumer consumer) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(baseUrl, gav))
                    .timeout(SidecarUriUtils.maxTransferTimeout())
                    .GET()
                    .build();
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv("Could not fetch {0} from external connection {1}: {2}", gav, externalConnectionName,
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
            LOG.warnv("Could not read {0} from external connection {1}: {2}", gav, externalConnectionName,
                    e.getMessage());
            return false;
        }
    }

    /** Whether {@code gav} exists in the real public repository, without downloading it. */
    public boolean exists(String externalConnectionName, String gav) {
        String baseUrl = baseUrls.get(externalConnectionName);
        if (baseUrl == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(requestUri(baseUrl, gav))
                    .timeout(Duration.ofSeconds(20))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            return httpClient.send(request, BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            LOG.warnv("Could not check {0} in external connection {1}: {2}", gav, externalConnectionName,
                    e.getMessage());
            return false;
        }
    }

    /**
     * {@code gav} is an untrusted asset path (ultimately a caller-supplied wire-protocol segment),
     * and {@code baseUrl} can itself carry a path prefix (the CommonsWare connection's own host is
     * really a bucket path, {@code s3.amazonaws.com/repo.commonsware.com}). Built the same safe
     * way {@code ReposiliteSidecarClient}'s own {@code requestUri} builds a request to the
     * internal sidecar: the three-argument {@link URI} constructor percent-encodes a stray
     * {@code #} or {@code ?} instead of letting it truncate the path at a fragment or reinterpret
     * the rest as a query string, and {@link SidecarUriUtils#combine} then joins that already-
     * encoded path onto {@code baseUrl}'s own scheme, authority, and path prefix without
     * re-encoding (and so without corrupting) either side.
     */
    private static URI requestUri(String baseUrl, String gav) {
        try {
            String encodedPath = new URI(null, null, "/" + gav, null, null).getRawPath();
            return SidecarUriUtils.combine(URI.create(baseUrl), encodedPath);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not build an external-connection request URI for " + gav, e);
        }
    }
}
