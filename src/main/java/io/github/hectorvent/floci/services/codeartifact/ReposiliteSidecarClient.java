package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client for the shared Reposilite sidecar. A CodeArtifact repository maps to a named
 * Reposilite repository ({@link #ensureRepository}), provisioned lazily via Reposilite's
 * {@code maven} settings domain, which hot-reloads without a restart.
 */
@ApplicationScoped
public class ReposiliteSidecarClient {
    private static final Logger LOG = Logger.getLogger(ReposiliteSidecarClient.class);
    private static final String MAVEN_SETTINGS_PATH = "/api/settings/domain/maven";
    private static final int REPOSITORY_READY_POLL_MAX_MS = 3_000;
    private static final int REPOSITORY_READY_POLL_INTERVAL_MS = 50;

    private final ReposiliteSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    /**
     * Every {@link #ensureRepository} call does a read-modify-write of the *same* single settings
     * document (Reposilite has no per-repository create endpoint, only a full-list replace), so a
     * per-repoId lock is not enough: two different repositories provisioned concurrently can each
     * read the list before the other's write lands, and the second PUT silently drops the first
     * repository from the list (confirmed against a real Reposilite instance). One lock serializes
     * every provisioning call regardless of which repository it is for.
     */
    private final Object provisioningLock = new Object();

    @Inject
    public ReposiliteSidecarClient(ReposiliteSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    ReposiliteSidecarClient(ReposiliteSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    /** Ensures a Reposilite repository named {@code repoId} exists, creating it if not. */
    public void ensureRepository(String repoId) {
        String baseUrl = manager.ensureReady();
        synchronized (provisioningLock) {
            ArrayNode repositories = currentRepositories(baseUrl);
            for (JsonNode repository : repositories) {
                if (repoId.equals(repository.path("id").asText())) {
                    return;
                }
            }
            ObjectNode newRepository = mapper.createObjectNode();
            newRepository.put("id", repoId);
            newRepository.put("visibility", "PUBLIC");
            newRepository.put("redeployment", false);
            repositories.add(newRepository);
            putMavenSettings(baseUrl, repositories);
            waitUntilRepositoryIsServable(baseUrl, repoId);
            LOG.infov("Provisioned Reposilite repository {0}", repoId);
        }
    }

    /**
     * Reposilite's settings PUT returning 200 only means the shared-configuration document was
     * written; it does not mean the repository actually instantiated. A repository whose settings
     * are individually valid but that {@code RepositoryFactory} rejects for a reason the settings
     * endpoint itself never validates (an id over Reposilite's own 64-character limit was one real
     * case here) is silently dropped, logged as an error inside the container, and every later
     * deploy to it 404s with a confusing "Repository not found" that gives no hint why. Polling a
     * side-effect-free read endpoint here turns that into an immediate, clear failure at
     * provisioning time instead.
     */
    private void waitUntilRepositoryIsServable(String baseUrl, String repoId) {
        long deadline = System.currentTimeMillis() + REPOSITORY_READY_POLL_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isRepositoryServable(baseUrl, repoId)) {
                return;
            }
            try {
                Thread.sleep(REPOSITORY_READY_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Reposilite repository " + repoId
                        + " to become servable", e);
            }
        }
        throw new IllegalStateException("Reposilite repository " + repoId + " did not become servable within "
                + REPOSITORY_READY_POLL_MAX_MS + " ms");
    }

    /**
     * {@code /api/maven/details/{repository}/{gav}} answers "File not found" for a path missing
     * from a repository Reposilite actually knows about, and "Repository ... not found" for one it
     * doesn't yet, which is exactly the distinction needed here; a probe path that can never be a
     * real artifact makes the "found" case impossible, so only the message text is ever compared.
     */
    private boolean isRepositoryServable(String baseUrl, String repoId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/maven/details/" + repoId + "/.floci-repository-ready-probe"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return true;
        }
        String message = readTree(response.body()).path("message").asText("");
        return !message.startsWith("Repository ");
    }

    /** Deploys {@code content} to {@code repoId}'s {@code gav} path, returning the HTTP status. */
    public int deployArtifact(String repoId, String gav, byte[] content) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = authenticated(baseUrl, repoId, gav)
                .PUT(BodyPublishers.ofByteArray(content))
                .build();
        return send(request, BodyHandlers.discarding()).statusCode();
    }

    /** Fetches {@code repoId}'s {@code gav} path, or {@link Optional#empty()} on a non-200 response. */
    public Optional<FetchedArtifact> fetchArtifact(String repoId, String gav) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = authenticated(baseUrl, repoId, gav).GET().build();
        HttpResponse<byte[]> response = send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return Optional.of(new FetchedArtifact(response.body(), contentType));
    }

    public record FetchedArtifact(byte[] content, String contentType) {}

    /** {@code true} if {@code repoId}'s {@code gav} path exists. */
    public boolean artifactExists(String repoId, String gav) {
        String baseUrl = manager.ensureReady();
        HttpRequest request = authenticated(baseUrl, repoId, gav).method("HEAD", BodyPublishers.noBody()).build();
        return send(request, BodyHandlers.discarding()).statusCode() == 200;
    }

    private ArrayNode currentRepositories(String baseUrl) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MAVEN_SETTINGS_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to read Reposilite maven settings: HTTP "
                    + response.statusCode());
        }
        JsonNode settings = readTree(response.body());
        return settings.path("repositories").isArray()
                ? ((ArrayNode) settings.path("repositories")).deepCopy()
                : mapper.createArrayNode();
    }

    private void putMavenSettings(String baseUrl, ArrayNode repositories) {
        ObjectNode settings = mapper.createObjectNode();
        settings.set("repositories", repositories);
        String payload = writeValue(settings);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MAVEN_SETTINGS_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to update Reposilite maven settings: HTTP "
                    + response.statusCode() + " " + response.body());
        }
    }

    private HttpRequest.Builder authenticated(String baseUrl, String repoId, String gav) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + repoId + "/" + gav))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Reposilite sidecar", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Reposilite sidecar: " + safeMessage(e), e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Reposilite maven settings response was not valid JSON", e);
        }
    }

    private String writeValue(ObjectNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Reposilite maven settings", e);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
