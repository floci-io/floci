package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

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

    @Override
    public void registerRepository(String repositoryContainerId) {
        manager.registerRepository(repositoryContainerId);
    }

    @Override
    public void beginUse(String repositoryContainerId) {
        manager.beginUse(repositoryContainerId);
    }

    @Override
    public void endUse(String repositoryContainerId) {
        manager.endUse(repositoryContainerId);
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

    /** {@link #fetchPackageVersionAsset}'s streamed sibling; see {@link RepositorySidecarManager} for why. */
    @Override
    public Optional<RepositorySidecarManager.StreamedAsset> fetchPackageVersionAssetStreamed(
            String repositoryContainerId, String domain, String repository, String namespace, String packageName,
            String version, String assetName) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        if (!versionHasAsset(baseUrl, packagePath(namespace, packageName), version, assetName)) {
            return Optional.empty();
        }
        return fetchTarballStreamed(baseUrl, namespace, packageName, assetName);
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

    /** {@link #fetchTarball}'s streamed sibling. The caller owns closing the returned stream. */
    public Optional<RepositorySidecarManager.StreamedAsset> fetchTarballStreamed(String baseUrl, String namespace,
            String packageName, String assetName) {
        HttpRequest request = tarballRequest(baseUrl, namespace, packageName, assetName);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Verdaccio sidecar to fetch " + assetName, e);
        }
        if (response.statusCode() == 404) {
            StreamingResponses.closeQuietly(response.body());
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            StreamingResponses.closeQuietly(response.body());
            throw new IllegalStateException("Could not fetch " + assetName
                    + " from the Verdaccio sidecar: upstream returned " + response.statusCode());
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return Optional.of(new RepositorySidecarManager.StreamedAsset(response.body(), contentLength));
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
            MessageDigest digest = Digests.sha512Digest();
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

    /**
     * Shared by {@link #fetchTarball}, {@link #fetchTarballStreamed}, and {@link
     * #storedTarballIntegrity} — every one of the three reads the real tarball's own bytes in
     * full (the last one to compute its digest, not just to hand them back), so a flat, small
     * timeout sized for a metadata-only request would cancel a legitimately large, still-in-
     * progress read through any of them.
     */
    private HttpRequest tarballRequest(String baseUrl, String namespace, String packageName, String assetName) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/" + packagePath(namespace, packageName) + "/-/"
                + SidecarUriUtils.encodeSegment(assetName));
        return HttpRequest.newBuilder(uri).timeout(SidecarUriUtils.maxTransferTimeout()).GET().build();
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
     * carrying just the new version's metadata and its tarball as a base64 {@code _attachments}
     * entry, deliberately with no {@code dist-tags} of its own (see {@link #storeVersion}'s own
     * javadoc for why). Verdaccio merges this into whatever {@code versions} the package already
     * has rather than requiring the caller to resend them (also confirmed live), so this never
     * needs to read the existing packument first the way {@link #deletePackageVersionAsset} does.
     * Rejected outright (a real {@code 409}, "this package is already present") if {@code
     * version} already exists, even if its content is identical: the caller ({@code
     * CodeArtifactService#copyContainerBackedPackageVersions}) is expected to have already
     * deleted a conflicting existing version first for an overwrite, the same reason {@code
     * ReposiliteSidecarClient#storePackageVersionAsset} documents for Maven.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                null);
    }

    /**
     * {@link #storePackageVersionAsset(String, String, String, String, String, String, String,
     * byte[])}'s own streamed sibling, for a tarball too large to ever hold as one {@code byte[]}
     * (~2 GiB, the JVM array limit): see {@link #storeVersion(String, String, String, String,
     * String, String, String, Path, long, JsonNode)} for how the write itself streams.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, Path content,
            long contentLength) {
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                contentLength, null);
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

    /**
     * {@link #copyPackageVersionAsset(String, String, String, String, String, String, String,
     * String, String, byte[])}'s own streamed sibling, mirroring {@link
     * #storePackageVersionAsset(String, String, String, String, String, String, String, Path,
     * long)}'s own reason for existing.
     */
    @Override
    public void copyPackageVersionAsset(String sourceRepositoryContainerId, String domain, String sourceRepository,
            String repositoryContainerId, String repository, String namespace, String packageName, String version,
            String assetName, Path content, long contentLength) {
        String sourceBaseUrl = ensureReady(sourceRepositoryContainerId, publicUrl(domain, sourceRepository));
        JsonNode sourceVersionDoc = packageDocument(sourceBaseUrl, packagePath(namespace, packageName))
                .map(doc -> doc.path("versions").path(version))
                .filter(node -> !node.isMissingNode() && !node.isNull())
                .orElse(null);
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                contentLength, sourceVersionDoc);
    }

    /**
     * {@code CodeArtifactNpmExternalConnectionImporter}'s own destination write for a version
     * retained through a {@code public:npmjs} external connection, the same reuse-the-source-
     * document treatment {@link #copyPackageVersionAsset} already gives a version copied from
     * another repository here, just sourced from the real public registry's own fetched packument
     * instead of another Verdaccio-backed repository (found by Greptile's review of npm's own
     * external-connection work: a plain {@link #storePackageVersionAsset} call builds a minimal
     * document with no {@code dependencies}/{@code engines}/{@code scripts}, which a real {@code
     * npm install} of this exact version needs). {@code externalVersionDoc} is {@code null} when
     * the caller could not resolve the real registry's own version entry (should not normally
     * happen once {@link CodeArtifactNpmExternalConnectionImporter#mergedPackument} has already
     * merged this exact version in, but defended the same way {@link #copyPackageVersionAsset}
     * already is): falls back to the same minimal document {@link #storePackageVersionAsset}
     * would have built.
     */
    public void storeRetainedVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content,
            JsonNode externalVersionDoc) {
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                externalVersionDoc);
    }

    /**
     * {@link #storeRetainedVersionAsset(String, String, String, String, String, String, String,
     * byte[], JsonNode)}'s own streamed sibling, mirroring {@link #storePackageVersionAsset(String,
     * String, String, String, String, String, String, Path, long)}'s own reason for existing: the
     * real public registry's own tarballs are exactly the ones most likely to actually exceed
     * {@code byte[]}'s own ~2 GiB ceiling.
     */
    public void storeRetainedVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, Path content,
            long contentLength, JsonNode externalVersionDoc) {
        storeVersion(repositoryContainerId, domain, repository, namespace, packageName, version, assetName, content,
                contentLength, externalVersionDoc);
    }

    /**
     * The one real write every {@code storePackageVersionAsset}/{@code copyPackageVersionAsset}/
     * {@code storeRetainedVersionAsset} call goes through. Sends no {@code dist-tags} of its own
     * (found by hectorvent's review of the npm external-connection work: the unconditional {@code
     * dist-tags.latest} this used to always send clobbered a real local {@code latest} the moment
     * any other version was copied or retained). Confirmed live against the pinned image: a PUT
     * that omits {@code dist-tags} entirely leaves an existing package's own {@code dist-tags.
     * latest} untouched, while a package's genuinely first-ever version still gets it set to that
     * version by Verdaccio's own default (there is nothing yet to preserve), so omitting it here
     * is always correct, and this method never needs its own "is this the right version to call
     * latest" decision.
     */
    private void storeVersion(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName, String version, String assetName, byte[] content, JsonNode sourceVersionDoc) {
        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String path = packagePath(namespace, packageName);
        String npmName = npmPackageName(namespace, packageName);
        ObjectNode versionDoc = versionDocument(sourceVersionDoc, npmName, version, baseUrl, path, assetName,
                Digests.sha1Hex(content), Base64.getEncoder().encodeToString(Digests.sha512Digest().digest(content)));

        // No dist-tags sent: see this method's own javadoc for why.
        ObjectNode body = mapper.createObjectNode();
        body.put("_id", npmName);
        body.put("name", npmName);
        body.put("description", "");
        body.putObject("versions").set(version, versionDoc);
        ObjectNode attachment = body.putObject("_attachments").putObject(assetName);
        attachment.put("content_type", "application/octet-stream");
        attachment.put("data", Base64.getEncoder().encodeToString(content));
        attachment.put("length", content.length);

        byte[] bodyBytes = writeValue(body);
        sendVersionDocument(baseUrl, path, npmName, version, BodyPublishers.ofByteArray(bodyBytes), bodyBytes.length);
    }

    /**
     * {@link #storeVersion(String, String, String, String, String, String, String, byte[],
     * JsonNode)}'s own streamed sibling: unlike every other sidecar write in this package, a
     * plain {@code BodyPublishers.concat} around the tarball's own bytes is not enough here,
     * since the tarball is not a standalone part of a multipart body but one base64-encoded JSON
     * string value embedded inside a larger document (Verdaccio's own publish protocol, confirmed
     * live). {@link #dist} aside, {@code shasum}/{@code integrity} are computed from the file
     * once, up front (a second full pass, since this class's own byte[]-based hashing has no
     * reason to be reused across the unrelated digest set {@code AssetDigestingInputStream}
     * already computed when this content was staged/fetched), and the JSON document itself is
     * built as explicit prefix/suffix string fragments around a {@link #base64EncodingStream}
     * instead of one fully-serialized string, so the base64 payload streams through rather than
     * ever being held, encoded, whole in memory the way the {@code byte[]} overload's own {@code
     * Base64.getEncoder().encodeToString(content)} call still is.
     */
    private void storeVersion(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName, String version, String assetName, Path content, long contentLength,
            JsonNode sourceVersionDoc) {
        String sha1Hex;
        String sha512Base64;
        try (InputStream in = Files.newInputStream(content)) {
            MessageDigest sha1 = Digests.sha1Digest();
            MessageDigest sha512 = Digests.sha512Digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha1.update(buffer, 0, read);
                sha512.update(buffer, 0, read);
            }
            sha1Hex = HexFormat.of().formatHex(sha1.digest());
            sha512Base64 = Base64.getEncoder().encodeToString(sha512.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String baseUrl = ensureReady(repositoryContainerId, publicUrl(domain, repository));
        String path = packagePath(namespace, packageName);
        String npmName = npmPackageName(namespace, packageName);
        ObjectNode versionDoc = versionDocument(sourceVersionDoc, npmName, version, baseUrl, path, assetName,
                sha1Hex, sha512Base64);

        // No dist-tags sent: see storeVersion's own byte[] overload's javadoc for why.
        ObjectNode bodyWithoutAttachment = mapper.createObjectNode();
        bodyWithoutAttachment.put("_id", npmName);
        bodyWithoutAttachment.put("name", npmName);
        bodyWithoutAttachment.put("description", "");
        bodyWithoutAttachment.putObject("versions").set(version, versionDoc);
        String prefix = jsonUpTo(bodyWithoutAttachment) + ",\"_attachments\":{"
                + writeValueAsString(assetName) + ":{\"content_type\":\"application/octet-stream\",\"data\":\"";
        String suffix = "\",\"length\":" + contentLength + "}}}";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);

        // BodyPublishers.ofInputStream's supplier can be invoked more than once (the JDK may
        // retry a request before any byte of the body is sent), each time needing a genuinely
        // fresh stream, so base64EncodingStream(content) is called inside the lambda, not hoisted
        // above it. Confirmed directly (a probe against a server that accepts a connection then
        // drops it mid-body): the JDK HttpClient does NOT close a supplied InputStream when the
        // send fails or is aborted, so without tracking every stream the supplier ever actually
        // created and closing it in the finally below, base64EncodingStream's own virtual thread
        // would stay blocked forever on a full pipe, leaking the thread and the open file handle
        // it still holds, on every aborted/timed-out npm publish, not just a successful one.
        List<InputStream> encodingStreams = new CopyOnWriteArrayList<>();
        HttpRequest.BodyPublisher publisher = BodyPublishers.ofInputStream(() -> {
            InputStream encoding = base64EncodingStream(content);
            encodingStreams.add(encoding);
            return new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(prefixBytes), encoding),
                    new ByteArrayInputStream(suffixBytes));
        });
        // Base64 expands the real asset bytes by 4/3 (rounded up to a multiple of 4): the timeout
        // below is sized against what actually crosses the wire, not the smaller pre-encoding
        // contentLength, the same distinction suffix's own "length" field above does not need to
        // make (that one reports the real asset size, matching what AWS's own npm dist.tarball
        // convention expects a consumer to see, regardless of how it was transported here).
        long encodedContentLength = prefixBytes.length + ((contentLength + 2) / 3) * 4 + suffixBytes.length;
        try {
            sendVersionDocument(baseUrl, path, npmName, version, publisher, encodedContentLength);
        } finally {
            // A no-op for a stream the HTTP client already read to EOF and closed itself (closing
            // an already-closed PipedInputStream is safe); the real purpose is unblocking the
            // encoding thread of a stream the client gave up on early, the gap this fix exists for.
            encodingStreams.forEach(StreamingResponses::closeQuietly);
        }
    }

    /** {@code versionDoc}'s own {@code dist} fields, shared by both {@code storeVersion} overloads. */
    private ObjectNode versionDocument(JsonNode sourceVersionDoc, String npmName, String version, String baseUrl,
            String path, String assetName, String shasum, String integritySha512Base64) {
        ObjectNode versionDoc = sourceVersionDoc != null ? sourceVersionDoc.deepCopy() : mapper.createObjectNode();
        versionDoc.put("name", npmName);
        versionDoc.put("version", version);
        if (!versionDoc.has("description")) {
            versionDoc.put("description", "");
        }
        ObjectNode dist = versionDoc.putObject("dist");
        dist.put("shasum", shasum);
        dist.put("integrity", "sha512-" + integritySha512Base64);
        dist.put("tarball", baseUrl + "/" + path + "/-/" + assetName);
        return versionDoc;
    }

    /**
     * {@code body} serialized as JSON with its own closing {@code }} dropped, so the caller can
     * append one more field (here, {@code _attachments}) before closing the object itself: {@code
     * body} has no {@code _attachments} of its own yet, so this is always well-formed JSON up to
     * that point, never a value cut off mid-way.
     */
    private String jsonUpTo(ObjectNode body) {
        String json = new String(writeValue(body), StandardCharsets.UTF_8);
        return json.substring(0, json.length() - 1);
    }

    private String writeValueAsString(String value) {
        return new String(writeValue(new TextNode(value)), StandardCharsets.UTF_8);
    }

    /**
     * A dedicated virtual thread reads {@code content} and writes it through the JDK's own
     * {@link Base64.Encoder#wrap(java.io.OutputStream)} into a pipe, so the caller gets back a
     * plain {@link InputStream} of base64 characters without this class hand-rolling its own
     * encoder: {@link Base64.Encoder#encodeToString} has no streaming counterpart of its own, and
     * every byte this emits still has to cross an {@code OutputStream}-shaped boundary somewhere.
     *
     * <p>Raised by Qodo's review of this PR: a failure on the writing side (the staged file
     * disappearing mid-read, for instance) does <em>not</em> surface as an {@code IOException} on
     * the reading side merely by closing the pipe, the way an earlier version of this method's
     * own javadoc claimed. Closing a {@link PipedOutputStream} is indistinguishable, from the
     * read side, from the writer finishing normally: either way {@link PipedInputStream} just
     * reports a clean EOF. Left as a bare close, a truncated read would send a well-formed but
     * short base64 payload inside an otherwise valid publish document whose {@code shasum}/
     * {@code integrity}/{@code length} fields (computed from the whole file before this stream
     * was ever opened) still describe the untruncated original — a silently corrupted publish
     * that reports success. The writer now records its own failure in {@code writerFailure}
     * <em>before</em> the {@code try}'s own resources close (a plain {@code finally}, not a
     * second try-with-resources, so the ordering is guaranteed: {@link FailFastInputStream}
     * would otherwise race a reader that observes the pipe's own EOF before the failure is
     * recorded), and the returned stream checks it after every read, turning that clean EOF back
     * into a real {@code IOException} instead.
     *
     * <p>Package-private, not {@code private}, so {@code VerdaccioSidecarClientTest} can exercise
     * this exact failure path directly against a nonexistent {@code content} path, rather than
     * only through the full {@code storePackageVersionAsset} call chain, whose own earlier
     * whole-file digesting pass would otherwise already reject a missing file before this method
     * is ever reached.
     */
    static InputStream base64EncodingStream(Path content) {
        PipedOutputStream pipedOut = new PipedOutputStream();
        PipedInputStream pipedIn;
        try {
            pipedIn = new PipedInputStream(pipedOut, 64 * 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AtomicReference<IOException> writerFailure = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            InputStream fileIn = null;
            OutputStream encodingOut = null;
            try {
                encodingOut = Base64.getEncoder().wrap(pipedOut);
                fileIn = Files.newInputStream(content);
                fileIn.transferTo(encodingOut);
            } catch (IOException e) {
                writerFailure.set(e);
            } finally {
                StreamingResponses.closeQuietly(fileIn);
                closeQuietly(encodingOut);
            }
        });
        return new FailFastInputStream(pipedIn, writerFailure);
    }

    /** {@link #base64EncodingStream}'s own close helper for the {@code OutputStream} side of its pipe. */
    private static void closeQuietly(OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // Best-effort: the encoding thread is tearing down regardless, and any failure here
            // is already captured (or about to be) in writerFailure above.
        }
    }

    /**
     * Wraps {@link #base64EncodingStream}'s pipe so a writer-side failure recorded in {@code
     * writerFailure} (set before the pipe itself closes; see that method's own javadoc) is
     * thrown on the next read instead of being masked by the pipe's own ordinary EOF.
     */
    private static final class FailFastInputStream extends FilterInputStream {
        private final AtomicReference<IOException> writerFailure;

        FailFastInputStream(InputStream in, AtomicReference<IOException> writerFailure) {
            super(in);
            this.writerFailure = writerFailure;
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            throwIfFailed();
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result = super.read(b, off, len);
            throwIfFailed();
            return result;
        }

        private void throwIfFailed() throws IOException {
            IOException failure = writerFailure.get();
            if (failure != null) {
                throw new IOException("Failed to read the staged asset while base64-encoding it for the "
                        + "Verdaccio sidecar", failure);
            }
        }
    }

    private void sendVersionDocument(String baseUrl, String path, String npmName, String version,
            HttpRequest.BodyPublisher publisher, long contentLength) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/" + path))
                .timeout(SidecarUriUtils.transferTimeout(contentLength))
                .header("Content-Type", "application/json")
                .PUT(publisher)
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

    private byte[] writeValue(JsonNode value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize a Verdaccio request body", e);
        }
    }
}
