package io.github.hectorvent.floci.services.ecr.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thin wrapper around the Docker Registry HTTP API v2 (the protocol implemented
 * by {@code registry:2}). Used by {@link EcrRegistryManager} to enumerate tags,
 * fetch manifests, and delete images on behalf of the ECR control plane.
 */
public class RegistryHttpClient {

    private static final Logger LOG = Logger.getLogger(RegistryHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;

    public RegistryHttpClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Returns true if the registry responds to {@code GET /v2/}. */
    public boolean ping() {
        try {
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v2/"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lists all repository names known to the backing registry, following the
     * {@code Link} header pagination that {@code GET /v2/_catalog} uses
     * (registry:2 caps each page at 100 entries by default).
     */
    public List<String> catalog() throws IOException, InterruptedException {
        try {
            return catalogStrict();
        } catch (IOException e) {
            LOG.warnv("Registry catalog returned error: {0}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Strict catalog: throws on any non-200 so callers can distinguish
     * &quot;no repos&quot; from &quot;registry failure&quot;.
     */
    public List<String> catalogStrict() throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        String url = baseUrl + "/v2/_catalog";
        for (int page = 0; page < 10_000 && url != null; page++) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Registry catalog returned " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode repos = root.get("repositories");
            if (repos != null && repos.isArray()) {
                repos.forEach(n -> out.add(n.asText()));
            }
            url = nextCatalogPage(resp.headers().firstValue("Link").orElse(null));
        }
        return out;
    }

    /** Extracts {@code </v2/_catalog?n=..&last=..>} from a registry Link header. */
    private String nextCatalogPage(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        int start = link.indexOf('<');
        int end = link.indexOf('>');
        if (start < 0 || end <= start) {
            return null;
        }
        String target = link.substring(start + 1, end);
        return target.startsWith("/") ? baseUrl + target : target;
    }

    /** Lists tags for a repository. Returns an empty list if the repo is not yet known to the registry. */
    public List<String> listTags(String name) throws IOException, InterruptedException {
        try {
            return listTagsStrict(name);
        } catch (IOException e) {
            LOG.warnv("Registry tags/list returned error: {0}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Lists tags, throwing on registry errors so callers can distinguish
     * &quot;no tags&quot; from &quot;registry failure&quot;. 404 still returns
     * empty; any other non-200 or malformed 200 throws {@link IOException}.
     */
    public List<String> listTagsStrict(String name) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/tags/list"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return Collections.emptyList();
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Registry tags/list returned " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode tags = root.get("tags");
        if (tags == null || tags.isNull()) {
            return Collections.emptyList();
        }
        if (!tags.isArray()) {
            throw new IOException("Registry tags/list returned non-array tags: " + resp.body());
        }
        List<String> out = new ArrayList<>();
        tags.forEach(n -> out.add(n.asText()));
        return out;
    }

    /**
     * HEAD a manifest by tag or digest, returning the {@code Docker-Content-Digest}
     * header value (the canonical manifest digest), or {@code null} if not found.
     */
    public String headManifestDigest(String name, String reference, List<String> acceptedMediaTypes)
            throws IOException, InterruptedException {
        try {
            return headManifestDigestStrict(name, reference, acceptedMediaTypes);
        } catch (IOException e) {
            LOG.warnv("Registry HEAD manifest {0}/{1} returned error: {2}", name, reference, e.getMessage());
            return null;
        }
    }

    /**
     * Strict HEAD: throws on registry error or missing digest so force-delete
     * can abort instead of silently skipping a tag. 404 still returns null.
     */
    public String headManifestDigestStrict(String name, String reference, List<String> acceptedMediaTypes)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/manifests/" + reference))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        applyAccept(b, acceptedMediaTypes);
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("Registry HEAD manifest " + name + "/" + reference + " returned " + resp.statusCode());
        }
        String digest = resp.headers().firstValue("Docker-Content-Digest").orElse(null);
        if (digest == null || digest.isBlank()) {
            throw new IOException("Registry HEAD manifest " + name + "/" + reference + " missing Docker-Content-Digest");
        }
        return digest;
    }

    /** Result of {@link #getManifest}: digest, body, and content media type. */
    public record ManifestResult(String digest, String body, String mediaType) {}

    /** GET a manifest by tag or digest, honoring the caller's {@code Accept} list. */
    public ManifestResult getManifest(String name, String reference, List<String> acceptedMediaTypes)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/manifests/" + reference))
                .timeout(Duration.ofSeconds(10))
                .GET();
        applyAccept(b, acceptedMediaTypes);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() >= 400) {
            LOG.warnv("Registry GET manifest {0}/{1} returned {2}: {3}", name, reference, resp.statusCode(), resp.body());
            return null;
        }
        String digest = resp.headers().firstValue("Docker-Content-Digest").orElse(null);
        String mediaType = resp.headers().firstValue("Content-Type").orElse(null);
        return new ManifestResult(digest, resp.body(), mediaType);
    }

    /** DELETE a manifest by digest. Returns true on 202/200, false on 404. */
    public boolean deleteManifest(String name, String digest) throws IOException, InterruptedException {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/manifests/" + digest))
                        .timeout(Duration.ofSeconds(10))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 404) {
            return false;
        }
        if (resp.statusCode() >= 400) {
            LOG.warnv("Registry DELETE manifest {0}/{1} returned {2}", name, digest, resp.statusCode());
            return false;
        }
        return true;
    }

    /**
     * A registry refusal, carrying the first {@code errors[].code} of the registry's error body
     * (for example {@code MANIFEST_BLOB_UNKNOWN}) so the ECR API can answer with its own error.
     */
    public static class RegistryErrorException extends IOException {
        private final int status;
        private final String code;

        public RegistryErrorException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** PUT a manifest under a tag or digest, returning the digest the registry stored it under. */
    public String putManifest(String name, String reference, String mediaType, String body)
            throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/manifests/" + reference))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", mediaType)
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw registryError("PUT manifest " + name + "/" + reference, resp);
        }
        return resp.headers().firstValue("Docker-Content-Digest").orElse(null);
    }

    /** HEAD a blob, returning its size in bytes, or {@code null} if the repository does not hold it. */
    public Long headBlob(String name, String digest) throws IOException, InterruptedException {
        HttpResponse<Void> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/blobs/" + digest))
                        .timeout(Duration.ofSeconds(10))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("Registry HEAD blob " + name + "/" + digest + " returned " + resp.statusCode());
        }
        return resp.headers().firstValueAsLong("Content-Length").orElse(0L);
    }

    /** Opens a blob upload session, returning the absolute URL the next request goes to. */
    public String startUpload(String name) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/blobs/uploads/"))
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 202) {
            throw registryError("POST blob upload " + name, resp);
        }
        return uploadLocation(resp);
    }

    /**
     * Sends one chunk of an upload, bytes {@code first} to {@code first + bytes.length - 1}, and
     * returns the URL the next request goes to.
     */
    public String patchUpload(String location, byte[] bytes, long first) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(location))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", first + "-" + (first + bytes.length - 1))
                        .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 202) {
            throw registryError("PATCH blob upload", resp);
        }
        return uploadLocation(resp);
    }

    /** Closes an upload, asking the registry to store what it received under {@code digest}. */
    public void finishUpload(String location, String digest) throws IOException, InterruptedException {
        String url = location + (location.contains("?") ? "&" : "?")
                + "digest=" + URLEncoder.encode(digest, StandardCharsets.UTF_8);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) {
            throw registryError("PUT blob upload", resp);
        }
    }

    /** Drops an upload session the registry still holds. Best effort. */
    public void cancelUpload(String location) {
        try {
            http.send(HttpRequest.newBuilder(URI.create(location))
                            .timeout(Duration.ofSeconds(10))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOG.debugv("Registry DELETE blob upload failed: {0}", e.getMessage());
        }
    }

    private String uploadLocation(HttpResponse<?> resp) throws IOException {
        String location = resp.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
            throw new IOException("Registry blob upload response has no Location");
        }
        return location.startsWith("/") ? baseUrl + location : location;
    }

    private static RegistryErrorException registryError(String what, HttpResponse<String> resp) {
        String code = null;
        try {
            JsonNode errors = MAPPER.readTree(resp.body()).path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                code = errors.get(0).path("code").asText(null);
            }
        } catch (Exception ignored) {
            // A body that is not the registry's error JSON leaves the code unknown.
        }
        return new RegistryErrorException(resp.statusCode(), code,
                "Registry " + what + " returned " + resp.statusCode() + ": " + resp.body());
    }

    /**
     * Sums {@code config.size} + each {@code layers[].size} from a manifest body.
     * Returns 0 if the body is not a recognised image manifest.
     */
    public static long sizeFromManifest(String manifestBody) {
        if (manifestBody == null || manifestBody.isBlank()) {
            return 0L;
        }
        try {
            JsonNode root = MAPPER.readTree(manifestBody);
            long total = root.path("config").path("size").asLong(0L);
            JsonNode layers = root.get("layers");
            if (layers != null && layers.isArray()) {
                for (JsonNode layer : layers) {
                    total += layer.path("size").asLong(0L);
                }
            }
            return total;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Returns the {@code config.mediaType} from a manifest body, or {@code null}. */
    public static String artifactMediaTypeFromManifest(String manifestBody) {
        if (manifestBody == null || manifestBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(manifestBody);
            String mt = root.path("config").path("mediaType").asText(null);
            return (mt == null || mt.isEmpty()) ? null : mt;
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyAccept(HttpRequest.Builder b, List<String> acceptedMediaTypes) {
        if (acceptedMediaTypes == null || acceptedMediaTypes.isEmpty()) {
            // Default to the union of OCI and Docker v2 schema 2 manifest types so the
            // registry serves whichever format the manifest is stored as.
            b.header("Accept", "application/vnd.oci.image.manifest.v1+json,"
                    + "application/vnd.oci.image.index.v1+json,"
                    + "application/vnd.docker.distribution.manifest.v2+json,"
                    + "application/vnd.docker.distribution.manifest.list.v2+json");
            return;
        }
        for (String mt : acceptedMediaTypes) {
            b.header("Accept", mt);
        }
    }

    public String baseUrl() {
        return baseUrl;
    }
}
