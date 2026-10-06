package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * The HTTP wire protocol for a Verdaccio sidecar: package metadata, tarball fetches, and stored
 * integrity checks. Container lifecycle (start, readiness, stop) is {@link VerdaccioSidecarManager}'s
 * job; this class asks it for a ready base URL and speaks Verdaccio's own protocol against it.
 *
 * <p>{@code namespace} is the npm scope without its leading {@code @}, or {@code null} for an
 * unscoped package. Every path segment is percent-encoded on its own via
 * {@link SidecarUriUtils#encodeSegment(String)} before the real {@code /} separators are added here,
 * so a caller-supplied value cannot splice in extra path segments.
 */
@ApplicationScoped
public class VerdaccioSidecarClient implements RepositorySidecarManager {

    private final VerdaccioSidecarManager manager;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public VerdaccioSidecarClient(VerdaccioSidecarManager manager, EmulatorConfig config, ObjectMapper mapper) {
        this(manager, config, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    VerdaccioSidecarClient(VerdaccioSidecarManager manager, EmulatorConfig config, ObjectMapper mapper,
            HttpClient httpClient) {
        this.manager = manager;
        this.config = config;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public String format() {
        return "npm";
    }

    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        return manager.ensureReady(repositoryContainerId, publicUrl);
    }

    @Override
    public void release(String repositoryContainerId) {
        manager.release(repositoryContainerId);
    }

    /** The public URL a Verdaccio sidecar is told to advertise for one npm repository. */
    public String publicUrl(String domain, String repository) {
        return config.effectiveBaseUrl() + "/codeartifact/npm/" + domain + "/" + repository + "/";
    }

    /**
     * Confirms {@code version} maps to {@code assetName} in the package's own metadata before fetching
     * the tarball, rather than serving whatever file that name resolves to under a version the caller
     * never published.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        if (!versionHasAsset(baseUrl, packagePath(namespace, packageName), version, assetName)) {
            return Optional.empty();
        }
        return fetchTarball(baseUrl, namespace, packageName, assetName);
    }

    /**
     * The package's metadata document as Verdaccio serves it, or empty when the package isn't there.
     * {@code baseUrl} is a backend already made ready by {@link #ensureReady}.
     */
    public Optional<JsonNode> fetchPackageDocument(String baseUrl, String namespace, String packageName) {
        return packageDocument(baseUrl, packagePath(namespace, packageName));
    }

    /** One tarball by its filename, from a backend already made ready by {@link #ensureReady}. */
    public Optional<byte[]> fetchTarball(String baseUrl, String namespace, String packageName, String assetName) {
        HttpRequest request = tarballRequest(baseUrl, namespace, packageName, assetName);
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to fetch " + assetName, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + assetName
                    + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /**
     * The SHA-512 integrity of a stored tarball, computed by streaming its bytes so the file never
     * sits in memory. Empty when the tarball is not there.
     */
    public Optional<String> storedTarballIntegrity(String baseUrl, String namespace, String packageName,
            String assetName) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(tarballRequest(baseUrl, namespace, packageName, assetName),
                    BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to hash " + assetName, e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Could not fetch " + assetName
                        + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
            }
            MessageDigest digest = sha512();
            byte[] chunk = new byte[64 * 1024];
            for (int read = body.read(chunk); read != -1; read = body.read(chunk)) {
                digest.update(chunk, 0, read);
            }
            return Optional.of("sha512-" + Base64.getEncoder().encodeToString(digest.digest()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + assetName + " from the Verdaccio sidecar", e);
        }
    }

    private static String packagePath(String namespace, String packageName) {
        return namespace == null
                ? SidecarUriUtils.encodeSegment(packageName)
                : "@" + SidecarUriUtils.encodeSegment(namespace) + "/" + SidecarUriUtils.encodeSegment(packageName);
    }

    private HttpRequest tarballRequest(String baseUrl, String namespace, String packageName, String assetName) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath(namespace, packageName) + "/-/"
                + SidecarUriUtils.encodeSegment(assetName));
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
    }

    private Optional<JsonNode> packageDocument(String baseUrl, String packagePath) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to look up " + packagePath, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not look up " + packagePath + " on the Verdaccio sidecar: "
                    + "upstream returned " + response.statusCode());
        }
        try {
            return Optional.of(mapper.readTree(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Verdaccio's package metadata for " + packagePath, e);
        }
    }

    private boolean versionHasAsset(String baseUrl, String packagePath, String version, String assetName) {
        Optional<JsonNode> document = packageDocument(baseUrl, packagePath);
        if (document.isEmpty()) {
            return false;
        }
        JsonNode versionNode = document.get().path("versions").path(version);
        if (versionNode.isMissingNode()) {
            return false;
        }
        String tarballUrl = versionNode.path("dist").path("tarball").asText("");
        String tarballFilename = tarballUrl.substring(tarballUrl.lastIndexOf('/') + 1);
        return assetName.equals(tarballFilename);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required by every Java runtime", e);
        }
    }
}
