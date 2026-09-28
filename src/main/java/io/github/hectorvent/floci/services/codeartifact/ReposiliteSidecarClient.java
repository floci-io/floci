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
 * {@code maven} settings domain, which hot-reloads without a restart. Implements
 * {@link RepositorySidecarManager} so {@code CodeArtifactService} can release a deleted
 * repository's Maven storage the same generic way it releases any other format's.
 */
@ApplicationScoped
public class ReposiliteSidecarClient implements RepositorySidecarManager {

    private static final String FORMAT = "maven";
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

    @Override
    public String format() {
        return FORMAT;
    }

    /**
     * {@code publicUrl} is unused: unlike npm's package metadata, Maven's wire protocol has no
     * self-referential URLs to rewrite, so there is nothing here for it to do.
     */
    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        ensureRepository(repositoryContainerId);
        return manager.ensureReady();
    }

    @Override
    public void release(String repositoryContainerId) {
        releaseRepository(repositoryContainerId);
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

    /**
     * Deletes every file {@code repoId} holds, then removes it from the shared settings list.
     * Reposilite has no bulk-delete endpoint (checked against its documented REST API); DELETE on
     * a path recursively removes everything under it in one call, though, so this only needs one
     * DELETE per top-level entry (a repository's group-id first segments, typically a handful)
     * rather than walking the whole artifact tree. A repository never used through Maven has
     * nothing registered on the Reposilite side at all; that is a no-op here, not an error.
     *
     * <p>Order matters: the repository must still be registered while its files are deleted,
     * since Reposilite 404s every path, including DELETE, for a repository ID absent from its
     * settings (confirmed against a live instance). Removing it from settings first would leave
     * its files permanently unreachable instead of actually freeing the storage.
     *
     * <p>Checks {@link ReposiliteSidecarManager#isStarted()} first: every CodeArtifact repository
     * gets a Maven sidecar id at creation regardless of whether it is ever actually used through
     * Maven, so without this check, deleting any repository at all would start the shared
     * container just to look for content that was never there. Reposilite keeps no volume, so a
     * sidecar that was never started could not possibly hold anything for {@code repoId} to
     * release.
     */
    public void releaseRepository(String repoId) {
        if (!manager.isStarted()) {
            return;
        }
        String baseUrl = manager.ensureReady();
        synchronized (provisioningLock) {
            for (JsonNode entry : topLevelEntries(baseUrl, repoId)) {
                deleteEntry(baseUrl, repoId, entry.path("name").asText());
            }
            ArrayNode repositories = currentRepositories(baseUrl);
            ArrayNode remaining = mapper.createArrayNode();
            for (JsonNode repository : repositories) {
                if (!repoId.equals(repository.path("id").asText())) {
                    remaining.add(repository);
                }
            }
            if (remaining.size() != repositories.size()) {
                putMavenSettings(baseUrl, remaining);
                LOG.infov("Released Reposilite repository {0}", repoId);
            }
        }
    }

    private ArrayNode topLevelEntries(String baseUrl, String repoId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/maven/details/" + repoId))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", manager.basicAuthHeader())
                .GET()
                .build();
        HttpResponse<String> response = send(request, BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // Never provisioned (no Maven use before this CodeArtifact repository was deleted):
            // nothing on the Reposilite side to clean up.
            return mapper.createArrayNode();
        }
        if (response.statusCode() != 200) {
            // Anything other than a clean 404 means the repository's actual state here is
            // unknown, not "nothing to clean up": proceeding to remove the settings entry anyway
            // would orphan whatever this call never got to see, unreachable through the API
            // afterward the same way the 404-ordering bug this method exists to avoid would.
            // Propagating lets the caller's best-effort handling decide, rather than silently
            // treating an error as an empty repository.
            throw new IllegalStateException("Failed to list " + repoId + " on Reposilite before release: HTTP "
                    + response.statusCode());
        }
        JsonNode details = readTree(response.body());
        return details.path("files").isArray() ? ((ArrayNode) details.path("files")).deepCopy()
                : mapper.createArrayNode();
    }

    private void deleteEntry(String baseUrl, String repoId, String entryName) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + repoId + "/" + entryName))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", manager.basicAuthHeader())
                .DELETE()
                .build();
        HttpResponse<Void> response = send(request, BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Failed to delete " + repoId + "/" + entryName
                    + " from Reposilite: HTTP " + response.statusCode());
        }
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
