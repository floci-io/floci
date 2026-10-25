package io.github.hectorvent.floci.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Digests;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
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

    @Override
    public boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        return fetchPackageDocument(baseUrl, namespace, packageName).isPresent();
    }

    /** Verdaccio's own packument already lists every version it holds, keyed by version string. */
    @Override
    public boolean packageVersionExists(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        return fetchPackageDocument(baseUrl, namespace, packageName)
                .map(document -> document.path("versions").has(version))
                .orElse(false);
    }

    /**
     * Verdaccio's own packument already lists every version it holds, each keyed in the
     * top-level {@code time} map by the same version string {@code versions} itself uses
     * (confirmed live: it auto-populates this on every publish, even though Floci's own publish
     * payload never sends one). A version present in {@code versions} but missing from {@code
     * time} (never observed live, but not assumed impossible) reports no timestamp rather than
     * guessing one; {@code time} also carries two non-version keys of its own ({@code created},
     * {@code modified}) that {@code versions} never does, so only looking a version up by that
     * exact key, never iterating {@code time}'s own fields, keeps those two out of the result.
     */
    @Override
    public List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        Optional<JsonNode> document = fetchPackageDocument(baseUrl, namespace, packageName);
        if (document.isEmpty()) {
            return List.of();
        }
        JsonNode time = document.get().path("time");
        List<PackageVersionTimestamp> result = new ArrayList<>();
        document.get().path("versions").fieldNames().forEachRemaining(version -> {
            String publishedText = time.path(version).asText(null);
            Instant published = null;
            if (publishedText != null) {
                try {
                    published = Instant.parse(publishedText);
                } catch (DateTimeParseException e) {
                    // Leave unset: CodeArtifactService falls back to a deterministic tie-break
                    // rather than guessing a time from text that didn't parse.
                }
            }
            result.add(new PackageVersionTimestamp(version, published));
        });
        return result;
    }

    /**
     * Verdaccio's own {@code /-/all} endpoint (confirmed live), scoped to this repository's own
     * backend the same way every other call here is (one {@code baseUrl} per repository): its
     * response object's keys are every package's own real path exactly as {@link #packagePath}
     * already builds it ({@code @scope/name} or a bare {@code name}), plus one non-package key,
     * {@code _updated}, filtered out here rather than treated as a package named "_updated".
     *
     * <p>Raised by hectorvent's review: every CodeArtifact repository gets an npm sidecar id at
     * creation regardless of whether it is ever actually used through npm, so without checking
     * {@link VerdaccioSidecarManager#isRunning} first, a {@code ListPackages} call with no format
     * filter would start this repository's own Verdaccio container just to find it empty, every
     * time, for a repository that has never published anything through npm.
     */
    @Override
    public List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        if (!manager.isRunning(repositoryContainerId)) {
            return List.of();
        }
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/-/all"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to list packages", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not list packages on the Verdaccio sidecar: upstream returned "
                    + response.statusCode());
        }
        JsonNode all;
        try {
            all = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Verdaccio's package listing", e);
        }
        List<PackageIdentifier> result = new ArrayList<>();
        all.fieldNames().forEachRemaining(key -> {
            if ("_updated".equals(key)) {
                return;
            }
            if (key.startsWith("@")) {
                int slash = key.indexOf('/');
                result.add(new PackageIdentifier(key.substring(1, slash), key.substring(slash + 1)));
            } else {
                result.add(new PackageIdentifier(null, key));
            }
        });
        return result;
    }

    /**
     * npm's own {@code dist-tags.latest}, AWS's documented preference for npm's {@code
     * defaultDisplayVersion} ahead of the most-recently-published fallback every other format
     * uses. {@code empty()} both when the package doesn't exist and when it exists but carries no
     * {@code latest} tag (a real npm publish always sets one, but an empty packument with no
     * versions at all, reachable only via {@code PutPackageOriginConfiguration}, has none),
     * letting {@code CodeArtifactService} fall back to the same most-recently-published rule in
     * either case.
     */
    @Override
    public Optional<String> preferredDisplayVersion(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        return fetchPackageDocument(baseUrl, namespace, packageName)
                .map(document -> document.path("dist-tags").path("latest").asText(null));
    }

    /**
     * Removes a whole package, every version at once, the same way the real npm CLI's final
     * unpublish step does: fetch the packument for its current {@code _rev}, then {@code DELETE} the
     * package path with that revision appended as {@code /-rev/<rev>}. Confirmed against a live
     * Verdaccio container: a bare {@code DELETE} with no revision is not a route at all (404
     * "Cannot DELETE"), and the revisioned form removes every version and tarball in one call.
     */
    @Override
    public void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String revision = fetchPackageDocument(baseUrl, namespace, packageName)
                .orElseThrow(() -> new IllegalStateException(packageName + " no longer exists on the Verdaccio "
                        + "sidecar"))
                .path("_rev").asText("");
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath(namespace, packageName)
                + "/-rev/" + SidecarUriUtils.encodeSegment(revision));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to delete " + packageName, e);
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("Could not delete " + packageName
                    + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
        }
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
        return assetName.equals(tarballFilenameFor(packageDocument(baseUrl, packagePath).orElse(null), version));
    }

    /**
     * The tarball filename Verdaccio's own packument records for {@code version}, derived from
     * {@code versions[version].dist.tarball}'s own last path segment the same way {@link
     * #fetchPackageVersionAsset} already trusts it, rather than assuming a fixed {@code
     * <packageName>-<version>.tgz} shape: real npm names it that way, but nothing here needs to
     * assume so when the packument already states it outright. {@code null} when the document is
     * absent, the version doesn't exist, or the version has no tarball recorded.
     */
    private static String tarballFilenameFor(JsonNode document, String version) {
        if (document == null) {
            return null;
        }
        JsonNode versionNode = document.path("versions").path(version);
        if (versionNode.isMissingNode()) {
            return null;
        }
        String tarballUrl = versionNode.path("dist").path("tarball").asText("");
        String tarballFilename = tarballUrl.substring(tarballUrl.lastIndexOf('/') + 1);
        return tarballFilename.isEmpty() ? null : tarballFilename;
    }

    /**
     * The exact version whose tarball Verdaccio's own packument records {@code filename} under,
     * found by checking every version's own {@link #tarballFilenameFor} rather than assuming
     * {@code filename} embeds its version in the {@code <packageName>-<version>.tgz} shape this
     * emulator's own publish path always writes: nothing in the real npm publish protocol
     * requires a client-chosen {@code dist.tarball} name to follow that convention, so {@code
     * CodeArtifactNpmDataPlane}'s own download-side Archived guard resolves the real coordinate
     * here instead of guessing from the filename's own text. Empty when the package doesn't
     * exist, or no version's own tarball matches.
     */
    public Optional<String> versionForTarballFilename(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName, String filename) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        JsonNode document = fetchPackageDocument(baseUrl, namespace, packageName).orElse(null);
        if (document == null) {
            return Optional.empty();
        }
        Iterator<String> versions = document.path("versions").fieldNames();
        while (versions.hasNext()) {
            String version = versions.next();
            if (filename.equals(tarballFilenameFor(document, version))) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }

    /**
     * npm has exactly one asset per version, the tarball: unlike Maven's version directory, there
     * is no separate checksum sidecar file or secondary artifact to enumerate. Empty when the
     * package or version doesn't exist, or the existing version has no tarball recorded (should
     * not happen for anything this emulator itself ever published, but a real registry can be
     * imported from or hand-edited).
     */
    @Override
    public List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String filename = tarballFilenameFor(
                packageDocument(baseUrl, packagePath(namespace, packageName)).orElse(null), version);
        return filename == null ? List.of() : List.of(filename);
    }

    /**
     * Publishes {@code version} straight to this sidecar's own packument, the real npm registry
     * wire shape confirmed against a live Verdaccio container: a {@code PUT} to the package path
     * carrying just the new version's metadata, its tarball as a base64 {@code _attachments}
     * entry, and {@code dist-tags.latest} pointed at it. Verdaccio merges this into whatever
     * {@code versions} the package already has rather than requiring the caller to resend them
     * (also confirmed live), so this never needs to read the existing packument first the way
     * {@link #deletePackageVersionAsset} does. Rejected outright (a real {@code 409}, "this
     * package is already present") if {@code version} already exists, even if its content is
     * identical: the caller ({@code CodeArtifactService#copyContainerBackedPackageVersions}) is
     * expected to have already deleted a conflicting existing version first for an overwrite, the
     * same reason {@code ReposiliteSidecarClient#storePackageVersionAsset} documents for Maven.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                null);
    }

    /**
     * Reuses {@code sourceRepositoryContainerId}'s own version entry verbatim (the exact document
     * its original publish wrote, including {@code dependencies}/{@code scripts}/{@code engines}
     * and anything else npm's own protocol carries on a version), rather than building a fresh,
     * minimal one from scratch the way {@link #storePackageVersionAsset} does on its own — only
     * {@code dist} is overwritten, to point at the copy's own tarball/shasum/integrity. Confirmed
     * live that a copy with no matching source version entry (should not happen for anything this
     * emulator itself ever published, but defends the same way {@link #listPackageVersionAssetNames}
     * already does for a hand-edited registry) falls back to the same minimal document {@link
     * #storePackageVersionAsset} would have built.
     */
    @Override
    public void copyPackageVersionAsset(String sourceRepositoryContainerId, String domain, String sourceRepository,
            String repositoryContainerId, String repository, String namespace, String packageName, String version,
            String assetName, byte[] content) {
        String sourceBaseUrl = ensureReady(sourceRepositoryContainerId, publicUrl(domain, sourceRepository));
        JsonNode sourceVersionDoc = packageDocument(sourceBaseUrl, packagePath(namespace, packageName))
                .map(doc -> doc.path("versions").path(version))
                .filter(node -> !node.isMissingNode() && !node.isNull())
                .orElse(null);
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                sourceVersionDoc);
    }

    private void storeVersion(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName, String version, String assetName, byte[] content, JsonNode sourceVersionDoc) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String path = packagePath(namespace, packageName);
        String npmName = npmPackageName(namespace, packageName);
        String tarballUrl = baseUrl + "/" + path + "/-/" + assetName;

        ObjectNode versionDoc = sourceVersionDoc != null ? sourceVersionDoc.deepCopy() : mapper.createObjectNode();
        versionDoc.put("name", npmName);
        versionDoc.put("version", version);
        if (!versionDoc.has("description")) {
            versionDoc.put("description", "");
        }
        ObjectNode dist = versionDoc.putObject("dist");
        dist.put("shasum", Digests.sha1Hex(content));
        dist.put("integrity", "sha512-" + Base64.getEncoder().encodeToString(sha512().digest(content)));
        dist.put("tarball", tarballUrl);

        ObjectNode body = mapper.createObjectNode();
        body.put("_id", npmName);
        body.put("name", npmName);
        body.put("description", "");
        body.putObject("dist-tags").put("latest", version);
        body.putObject("versions").set(version, versionDoc);
        ObjectNode attachment = body.putObject("_attachments").putObject(assetName);
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(content));
        attachment.put("length", content.length);

        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofByteArray(writeValue(body)))
                .build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to publish " + npmName + "@"
                    + version, e);
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("Could not publish " + npmName + "@" + version + " to the Verdaccio "
                    + "sidecar: upstream returned " + response.statusCode());
        }
    }

    /**
     * Removes one version from this package the same two-step way the real npm CLI's single-
     * version {@code npm unpublish <pkg>@<version>} does, both confirmed live against a Verdaccio
     * container: first a {@code PUT} of the packument with that version deleted from {@code
     * versions} (and {@code dist-tags.latest} cleared if it pointed at the removed version),
     * carrying the package's current {@code _rev} in the path for Verdaccio's own optimistic-
     * concurrency check; then, once that succeeds and the package has a fresh {@code _rev} of its
     * own, a {@code DELETE} of the tarball itself. Both steps are required: removing the version
     * from the packument alone leaves the tarball file in place on Verdaccio's own storage, which
     * then makes a same-named {@link #storePackageVersionAsset} republish fail with the same
     * {@code 409} as before, since Verdaccio checks the stored file, not just the packument.
     */
    @Override
    public void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String path = packagePath(namespace, packageName);
        ObjectNode document = (ObjectNode) packageDocument(baseUrl, path)
                .orElseThrow(() -> new IllegalStateException(packageName + " no longer exists on the Verdaccio "
                        + "sidecar"));
        String revision = document.path("_rev").asText("");
        ((ObjectNode) document.path("versions")).remove(version);
        ObjectNode distTags = (ObjectNode) document.path("dist-tags");
        if (version.equals(distTags.path("latest").asText(""))) {
            distTags.remove("latest");
        }
        putPackument(baseUrl, path, revision, document, packageName, version);

        String revisionAfterRemove = packageDocument(baseUrl, path)
                .orElseThrow(() -> new IllegalStateException(packageName + " no longer exists on the Verdaccio "
                        + "sidecar"))
                .path("_rev").asText("");
        URI tarballUri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + path + "/-/"
                + SidecarUriUtils.encodeSegment(assetName) + "/-rev/" + SidecarUriUtils.encodeSegment(
                        revisionAfterRemove));
        HttpRequest deleteRequest = HttpRequest.newBuilder(tarballUri).timeout(Duration.ofSeconds(10)).DELETE()
                .build();
        HttpResponse<Void> deleteResponse;
        try {
            deleteResponse = httpClient.send(deleteRequest, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to delete " + packageName + "@"
                    + version + "'s asset " + assetName, e);
        }
        if (deleteResponse.statusCode() != 200 && deleteResponse.statusCode() != 201) {
            throw new IllegalStateException("Could not delete " + packageName + "@" + version + "'s asset "
                    + assetName + " from the Verdaccio sidecar: upstream returned " + deleteResponse.statusCode());
        }
    }

    private void putPackument(String baseUrl, String path, String revision, ObjectNode document, String packageName,
            String version) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + path + "/-rev/"
                + SidecarUriUtils.encodeSegment(revision));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(BodyPublishers.ofByteArray(writeValue(document)))
                .build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to update " + packageName + "@"
                    + version, e);
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("Could not update " + packageName + "@" + version + " on the "
                    + "Verdaccio sidecar: upstream returned " + response.statusCode());
        }
    }

    /** The real npm package name, {@code @namespace/packageName} for a scoped package. */
    private static String npmPackageName(String namespace, String packageName) {
        return namespace == null ? packageName : "@" + namespace + "/" + packageName;
    }

    private byte[] writeValue(ObjectNode value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize a Verdaccio request body", e);
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required by every Java runtime", e);
        }
    }
}
