package io.github.hectorvent.floci.services.ecr.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    /** Lists all repository names known to the backing registry. */
    public List<String> catalog() throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/v2/_catalog"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOG.warnv("Registry catalog returned {0}: {1}", resp.statusCode(), resp.body());
            return Collections.emptyList();
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode repos = root.get("repositories");
        if (repos == null || !repos.isArray()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        repos.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Lists tags for a repository. Returns an empty list if the repo is not yet known to the registry. */
    public List<String> listTags(String name) throws IOException, InterruptedException {
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
            LOG.warnv("Registry tags/list returned {0}: {1}", resp.statusCode(), resp.body());
            return Collections.emptyList();
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode tags = root.get("tags");
        if (tags == null || tags.isNull() || !tags.isArray()) {
            return Collections.emptyList();
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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/v2/" + name + "/manifests/" + reference))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        applyAccept(b, acceptedMediaTypes);
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() >= 400) {
            LOG.warnv("Registry HEAD manifest {0}/{1} returned {2}", name, reference, resp.statusCode());
            return null;
        }
        return resp.headers().firstValue("Docker-Content-Digest").orElse(null);
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
