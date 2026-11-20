package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.StreamingResponses;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The HTTP wire protocol for a Kellnr sidecar: Cargo's real registry API (confirmed directly
 * against a live container, not assumed from Kellnr's own thin docs site, which did not render
 * through a page fetch): the sparse HTTP index (RFC 2789), the binary publish body the Cargo
 * Book documents, and Kellnr's own admin routes for a genuine hard delete (Cargo's own protocol
 * only has "yank," which hides a version without removing it; {@code DeletePackage}/{@code
 * DeletePackageVersions} need real removal, matching AWS's own documented behavior). Container
 * lifecycle (start, readiness, stop) is {@link KellnrSidecarManager}'s job; this class asks it for
 * a ready base URL and speaks Kellnr's own protocol against it.
 *
 * <p>Unlike every other container-backed format's sidecar, Kellnr always requires a real
 * credential for publish, confirmed live: there is no "leave it fully open" switch the way
 * BaGetter's empty {@code ApiKey} provides ({@code KELLNR_REGISTRY__AUTH_REQUIRED=false}, already
 * the image's own default, only relaxes anonymous download, never publish). This class holds
 * exactly one internal credential pair per repository, minted lazily on first need and reused for
 * that repository's whole container lifetime, entirely invisible to a real CodeArtifact client
 * (whose own authorization is, and remains, enforced once at Floci's own proxy layer: this is
 * bookkeeping this client owes Kellnr, not a second authentication scheme a CodeArtifact client
 * ever sees):
 * <ul>
 *   <li>An API token (minted via {@code POST /api/v1/user/add_token} under a fresh admin login),
 *   cached in {@link #publishTokensByRepoId} and reused for every {@code PUT /api/v1/crates/new}
 *   publish call.</li>
 *   <li>A fresh admin session login per admin-route call ({@code DELETE /api/v1/ui/delete_version}/
 *   {@code delete_crate}), rather than caching the session cookie: confirmed live that the API
 *   token itself does not work against these admin routes (401), only the session cookie does,
 *   and that cookie expires after 8 hours, so logging in fresh for each (infrequent) admin call
 *   avoids expiry bookkeeping entirely rather than risk a cached cookie going stale mid-use.</li>
 * </ul>
 *
 * <p>{@code CopyPackageVersions}' own destination write ({@link #storePackageVersionAsset})
 * always publishes with an empty dependency list: extracting the real {@code [dependencies]}
 * table out of the copied {@code .crate}'s embedded {@code Cargo.toml} would need a TOML parser,
 * and this codebase has none (adding one is its own decision, not bundled into this foundational
 * sidecar work), so a copied crate's own real bytes are unaffected, but its sparse-index entry
 * under-reports its dependencies until a later pass adds that parsing. Documented here rather
 * than left silent.
 */
@ApplicationScoped
public class KellnrSidecarClient implements RepositorySidecarManager {

    private static final Logger LOG = Logger.getLogger(KellnrSidecarClient.class);

    private final KellnrSidecarManager manager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    // One minted API token per repository, reused for every publish for that repository's whole
    // container lifetime; cleared on release() so a later repository under the same id never
    // inherits a stale token pointing at a container that no longer exists. Also evicted and
    // reminted on a single 401 (see sendPublishWithRetry): PerKeyContainerPool.ensureReady can
    // silently restart an unhealthy container under the same repository id without telling this
    // client, which invalidates whatever token was minted under the previous container without
    // this client ever finding out from anything other than that 401 itself.
    private final ConcurrentHashMap<String, String> publishTokensByRepoId = new ConcurrentHashMap<>();
    // Shared with a future CodeArtifactCargoController's own push handler via lockFor(), the same
    // reason BagetterSidecarClient keeps one: without it, a native push and a
    // CopyPackageVersions/DeletePackage write for the same repository could interleave.
    private final ConcurrentHashMap<String, Object> repositoryLocks = new ConcurrentHashMap<>();

    @Inject
    public KellnrSidecarClient(KellnrSidecarManager manager, ObjectMapper mapper) {
        this(manager, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    KellnrSidecarClient(KellnrSidecarManager manager, ObjectMapper mapper, HttpClient httpClient) {
        this.manager = manager;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public String format() {
        return "cargo";
    }

    @Override
    public String ensureReady(String repositoryContainerId, String publicUrl) {
        return manager.ensureReady(repositoryContainerId, publicUrl);
    }

    @Override
    public void release(String repositoryContainerId) {
        manager.release(repositoryContainerId);
        publishTokensByRepoId.remove(repositoryContainerId);
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

    /**
     * Cargo packages have no namespace (AWS: "Python, NuGet, Ruby, and Cargo package versions do
     * not contain a corresponding component") and exactly one real asset per version, the
     * {@code .crate} file itself, deterministically named {@code {name}-{version}.crate} (the
     * real local-cache filename convention a real {@code cargo} client itself uses), so {@code
     * assetName} is checked against that exact value rather than fetched unconditionally, the
     * same convention {@code BagetterSidecarClient} already uses for NuGet's own one asset.
     */
    @Override
    public Optional<byte[]> fetchPackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        Optional<String> matchedVersion = matchVersion(repositoryContainerId, packageName, version);
        if (matchedVersion.isEmpty()) {
            return Optional.empty();
        }
        String expectedAssetName = packageName + "-" + matchedVersion.get() + ".crate";
        if (!expectedAssetName.equals(assetName)) {
            return Optional.empty();
        }
        return getBytes(repositoryContainerId, downloadPath(packageName, matchedVersion.get()));
    }

    /** {@link #fetchPackageVersionAsset}'s streamed sibling; see {@link RepositorySidecarManager} for why. */
    @Override
    public Optional<StreamedAsset> fetchPackageVersionAssetStreamed(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName, String version, String assetName) {
        Optional<String> matchedVersion = matchVersion(repositoryContainerId, packageName, version);
        if (matchedVersion.isEmpty()) {
            return Optional.empty();
        }
        String expectedAssetName = packageName + "-" + matchedVersion.get() + ".crate";
        if (!expectedAssetName.equals(assetName)) {
            return Optional.empty();
        }
        return getBytesStreamed(repositoryContainerId, downloadPath(packageName, matchedVersion.get()));
    }

    /**
     * Every crate name this sidecar holds at least one version of, confirmed live: {@code
     * GET /api/v1/ui/crates?page=N&page_size=M} needs no credential, returns {@code {"crates":
     * [{"name":...,"version":...,...}],"page_size":M,"page":N}} with one entry per crate name
     * (its latest version only, which this bridge doesn't need), and an out-of-range page returns
     * an empty array rather than erroring, the same paginated-loop shape {@code
     * BagetterSidecarClient#listPackages} already uses for BaGetter's own search endpoint.
     */
    @Override
    public List<PackageIdentifier> listPackages(String repositoryContainerId, String domain, String repository) {
        if (!manager.isRunning(repositoryContainerId)) {
            return List.of();
        }
        List<PackageIdentifier> result = new ArrayList<>();
        int page = 0;
        int pageSize = 100;
        while (true) {
            JsonNode response = getJson(repositoryContainerId, "/api/v1/ui/crates?page=" + page + "&page_size="
                    + pageSize).orElseThrow(() -> new IllegalStateException("Could not list packages on the Kellnr "
                            + "sidecar"));
            JsonNode crates = response.path("crates");
            if (!crates.isArray() || crates.isEmpty()) {
                return result;
            }
            for (JsonNode entry : crates) {
                String name = entry.path("name").asText(null);
                if (name != null) {
                    result.add(new PackageIdentifier(null, name));
                }
            }
            page++;
        }
    }

    private Optional<JsonNode> getJson(String repositoryContainerId, String encodedPath) {
        return getBytes(repositoryContainerId, encodedPath).map(bytes -> {
            try {
                return mapper.readTree(bytes);
            } catch (IOException e) {
                throw new IllegalStateException("Could not parse the Kellnr sidecar's own response at "
                        + encodedPath, e);
            }
        });
    }

    @Override
    public boolean packageExists(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        return !sparseIndexEntries(repositoryContainerId, packageName).isEmpty();
    }

    @Override
    public boolean packageVersionExists(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        return matchVersion(repositoryContainerId, packageName, version).isPresent();
    }

    /**
     * Every version this sidecar holds for a package, with its publish timestamp, from the sparse
     * index's own {@code pubtime} field (confirmed live). Does not distinguish a yanked version
     * from a live one: Cargo's real registry protocol tracks "yanked" as a flag on an otherwise
     * still-present index entry, not a separate listing, and there is no yank-aware status-override
     * layer for cargo yet (see this class's own javadoc on scope).
     */
    @Override
    public List<PackageVersionTimestamp> listPackageVersions(String repositoryContainerId, String domain,
            String repository, String namespace, String packageName) {
        List<JsonNode> entries = sparseIndexEntries(repositoryContainerId, packageName);
        List<PackageVersionTimestamp> result = new ArrayList<>();
        for (JsonNode entry : entries) {
            String version = entry.path("vers").asText(null);
            if (version == null) {
                continue;
            }
            Instant published = null;
            String publishedText = entry.path("pubtime").asText(null);
            if (publishedText != null) {
                try {
                    published = Instant.parse(publishedText);
                } catch (DateTimeParseException e) {
                    // Leave unmapped: CodeArtifactService falls back to a deterministic tie-break
                    // rather than guessing a time from text that didn't parse.
                }
            }
            result.add(new PackageVersionTimestamp(version, published));
        }
        return result;
    }

    /**
     * Cargo's own one asset per version is deterministically named (see {@link
     * #fetchPackageVersionAsset}'s own javadoc), so enumerating it for {@code CopyPackageVersions}
     * needs only confirm the version exists and name it the same way; empty when it doesn't.
     */
    @Override
    public List<String> listPackageVersionAssetNames(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version) {
        Optional<String> matchedVersion = matchVersion(repositoryContainerId, packageName, version);
        return matchedVersion.map(v -> List.of(packageName + "-" + v + ".crate")).orElse(List.of());
    }

    /**
     * Writes {@code content} (the whole {@code .crate}, Cargo's one asset per version) through the
     * real publish wire shape confirmed live against Kellnr: a 4-byte little-endian length +
     * minimal JSON metadata (name/version from this call's own parameters, no dependency list,
     * see this class's own javadoc), then a 4-byte little-endian length + the raw {@code .crate}
     * bytes, {@code PUT} to {@code /api/v1/crates/new} with this repository's own internally-held
     * token in a bare {@code Authorization} header (no {@code Bearer} prefix, confirmed both from
     * the Cargo Book's own spec text and live against Kellnr).
     *
     * <p>Runs inside {@link #lockFor}, the same lock a future native push handler is expected to
     * share, for the same reason {@code BagetterSidecarClient} already documents for its own
     * identical lock.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, byte[] content) {
        synchronized (lockFor(repositoryContainerId)) {
            publish(repositoryContainerId, packageName, version, content);
        }
    }

    /**
     * {@link #storePackageVersionAsset(String, String, String, String, String, String, String,
     * byte[])}'s own streamed sibling, for an asset too large to comfortably hold twice over in
     * memory (once as the raw crate, once again inside the combined publish body): the header is
     * written first and {@code content}'s own real bytes are then streamed straight through via
     * {@link #publishRawWireBody}, the same shape {@code CodeArtifactCargoExternalConnectionImporter
     * #buildStagedWireBody} already uses for a crate retained through an external connection,
     * rather than ever holding the whole crate resident the way the {@code byte[]} overload above
     * does for {@code CopyPackageVersions}' own always-small case.
     */
    @Override
    public void storePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName, Path content,
            long contentLength) {
        byte[] metadata = minimalPublishMetadata(packageName, version);
        Path staged;
        try {
            staged = Files.createTempFile("codeartifact-cargo-copy-wire-", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            try (OutputStream out = Files.newOutputStream(staged)) {
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
                out.write(metadata);
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) contentLength).array());
                Files.copy(content, out);
            }
            KellnrPublishResult result;
            synchronized (lockFor(repositoryContainerId)) {
                result = publishRawWireBody(repositoryContainerId, staged, Files.size(staged));
            }
            if (result.statusCode() != 200) {
                throw new IllegalStateException("Could not publish " + packageName + " " + version + " to the "
                        + "Kellnr sidecar: upstream returned " + result.statusCode());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException e) {
                LOG.warnv(e, "Could not delete a staged Kellnr copy wire-body file at {0}", staged);
            }
        }
    }

    private void publish(String repositoryContainerId, String packageName, String version, byte[] crateBytes) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        byte[] metadata = minimalPublishMetadata(packageName, version);
        byte[] body = publishWireBody(metadata, crateBytes);
        HttpResponse<String> response = sendPublishWithRetry(repositoryContainerId, baseUrl, token ->
                HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/api/v1/crates/new"))
                        .timeout(SidecarUriUtils.transferTimeout(body.length))
                        .header("Content-Type", "application/octet-stream")
                        .header("Accept", "application/json")
                        .header("Authorization", token)
                        .PUT(BodyPublishers.ofByteArray(body))
                        .build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not publish " + packageName + " " + version + " to the Kellnr "
                    + "sidecar: upstream returned " + response.statusCode());
        }
    }

    /**
     * Forwards a real {@code cargo publish}'s own already-correctly-shaped wire body (streamed
     * from disk, since a {@code .crate} can be large) to {@code PUT /api/v1/crates/new} verbatim,
     * using this repository's own internally-held token. Deliberately separate from {@link
     * #storePackageVersionAsset}, which builds its own minimal, dependency-free metadata for
     * {@code CopyPackageVersions}' sake (see this class's own javadoc): a native publish's own
     * metadata, built by the real client itself, already carries the package's real dependency
     * list, which must survive untouched, not be discarded and resynthesized.
     *
     * <p>Returns Kellnr's own status and body rather than throwing on a non-200: confirmed live
     * that Kellnr genuinely rejects a republish of an already-existing name/version with a 400 and
     * a body already shaped exactly like the Cargo Book's own documented error response ({@code
     * {"errors":[{"detail":"..."}]}}), matching the Cargo Book's own documented rule that "a
     * publish is generally permanent" and a version "can never be overwritten". That is a real
     * answer a real {@code cargo} client understands, not an internal failure to swallow into an
     * opaque 500; the caller forwards it to the client verbatim.
     *
     * <p>Not part of {@link RepositorySidecarManager}: this is Cargo's own native-publish bridge,
     * called directly by {@code CodeArtifactCargoController}, the same way {@code
     * BagetterSidecarClient#lockFor} is reused directly by {@code CodeArtifactNuGetController}
     * without going through the shared interface either.
     */
    record KellnrPublishResult(int statusCode, String body) {
    }

    KellnrPublishResult publishRawWireBody(String repositoryContainerId, Path stagedWireBody, long contentLength) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        HttpResponse<String> response = sendPublishWithRetry(repositoryContainerId, baseUrl, token -> {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(SidecarUriUtils.combine(
                            URI.create(baseUrl), "/api/v1/crates/new"))
                    .timeout(SidecarUriUtils.transferTimeout(contentLength))
                    .header("Content-Type", "application/octet-stream")
                    .header("Accept", "application/json")
                    .header("Authorization", token);
            try {
                requestBuilder.PUT(BodyPublishers.ofFile(stagedWireBody));
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
            return requestBuilder.build();
        });
        return new KellnrPublishResult(response.statusCode(), response.body());
    }

    /**
     * Sends a publish request built from whichever token is current, and, only on a {@code 401},
     * evicts that cached token, mints a fresh one, and retries exactly once: this is how a
     * container silently restarted under this repository's own id by {@code PerKeyContainerPool
     * #ensureReady} (see {@link #publishTokensByRepoId}'s own javadoc) heals on its very next
     * publish attempt instead of 401ing every one of them until Floci itself restarts. {@code
     * requestForToken} is called again for the retry, so it must build a genuinely fresh {@link
     * HttpRequest} each time rather than reuse one (an {@link HttpRequest} already carries its own
     * immutable headers, the stale {@code Authorization} included).
     */
    private HttpResponse<String> sendPublishWithRetry(String repositoryContainerId, String baseUrl,
            Function<String, HttpRequest> requestForToken) {
        String token = publishToken(repositoryContainerId, baseUrl);
        HttpResponse<String> response = sendHttp(requestForToken.apply(token));
        if (response.statusCode() == 401) {
            publishTokensByRepoId.remove(repositoryContainerId, token);
            String freshToken = publishToken(repositoryContainerId, baseUrl);
            response = sendHttp(requestForToken.apply(freshToken));
        }
        return response;
    }

    private HttpResponse<String> sendHttp(HttpRequest request) {
        try {
            return httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar to publish this crate", e);
        }
    }

    /**
     * Deletes every version of the named package via Kellnr's own admin route, confirmed live: a
     * genuine hard delete, not Cargo's own protocol-level "yank". Runs inside {@link #lockFor},
     * the same lock {@link #storePackageVersionAsset} and a future native push handler share.
     */
    @Override
    public void deletePackage(String repositoryContainerId, String domain, String repository, String namespace,
            String packageName) {
        synchronized (lockFor(repositoryContainerId)) {
            String baseUrl = ensureReady(repositoryContainerId, null);
            String cookie = adminLogin(repositoryContainerId, baseUrl);
            String query = "name=" + SidecarUriUtils.encodeSegment(packageName);
            adminDelete(baseUrl, "/api/v1/ui/delete_crate?" + query, cookie, packageName, null);
        }
    }

    /**
     * Deletes the one version this asset belongs to, via the same admin route family as {@link
     * #deletePackage}: Cargo has exactly one asset per version, so there is no finer grain to
     * delete at. Runs inside the same {@link #lockFor} lock {@link #storePackageVersionAsset} and
     * {@link #deletePackage} both take, for the same reason.
     */
    @Override
    public void deletePackageVersionAsset(String repositoryContainerId, String domain, String repository,
            String namespace, String packageName, String version, String assetName) {
        synchronized (lockFor(repositoryContainerId)) {
            String baseUrl = ensureReady(repositoryContainerId, null);
            String cookie = adminLogin(repositoryContainerId, baseUrl);
            String query = "name=" + SidecarUriUtils.encodeSegment(packageName) + "&version="
                    + SidecarUriUtils.encodeSegment(version);
            adminDelete(baseUrl, "/api/v1/ui/delete_version?" + query, cookie, packageName, version);
        }
    }

    private void adminDelete(String baseUrl, String pathAndQuery, String cookie, String packageName,
            String version) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), pathAndQuery))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", cookie)
                .DELETE()
                .build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar to delete " + packageName
                    + (version == null ? "" : " " + version), e);
        }
        // 404 means this exact coordinate is already gone, the same tolerance
        // BagetterSidecarClient's own delete calls already apply.
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw new IllegalStateException("Could not delete " + packageName + (version == null ? "" : " "
                    + version) + " from the Kellnr sidecar: upstream returned " + response.statusCode());
        }
    }

    /**
     * The lock a future native push handler and this client's own write/delete methods all share
     * for one repository, so neither can interleave with the other. Package-private so a future
     * {@code CodeArtifactCargoController} can synchronize its own push-or-conflict check and
     * forwarded write on the exact same lock, the only way either one closes the race against the
     * other, the same reason {@code BagetterSidecarClient#lockFor} is package-private.
     */
    Object lockFor(String repositoryContainerId) {
        return repositoryLocks.computeIfAbsent(repositoryContainerId, k -> new Object());
    }

    /**
     * The minimal metadata shape confirmed live to be accepted by Kellnr's real publish endpoint:
     * every optional field explicitly {@code null}/empty rather than omitted, matching the shape
     * this class's own hands-on verification used. {@code deps}/{@code features} are always empty
     * (see this class's own javadoc on why); {@code license} is a fixed placeholder since real
     * CodeArtifact has no license field of its own to source this from for a sidecar-level copy.
     */
    private byte[] minimalPublishMetadata(String packageName, String version) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("name", packageName);
        metadata.put("vers", version);
        metadata.putArray("deps");
        metadata.putObject("features");
        metadata.putArray("authors");
        metadata.put("description", (String) null);
        metadata.put("documentation", (String) null);
        metadata.put("homepage", (String) null);
        metadata.put("readme", (String) null);
        metadata.put("readme_file", (String) null);
        metadata.putArray("keywords");
        metadata.putArray("categories");
        metadata.put("license", (String) null);
        metadata.put("license_file", (String) null);
        metadata.put("repository", (String) null);
        metadata.putObject("badges");
        metadata.put("links", (String) null);
        return metadata.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The real Cargo publish wire body, confirmed directly from the Cargo Book's own spec text and
     * live against Kellnr: a 4-byte little-endian length-prefixed JSON metadata object, followed
     * by a 4-byte little-endian length-prefixed {@code .crate} tarball.
     */
    private static byte[] publishWireBody(byte[] metadataJson, byte[] crateBytes) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + metadataJson.length + 4 + crateBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(metadataJson.length);
        buffer.put(metadataJson);
        buffer.putInt(crateBytes.length);
        buffer.put(crateBytes);
        return buffer.array();
    }

    private String publishToken(String repositoryContainerId, String baseUrl) {
        return publishTokensByRepoId.computeIfAbsent(repositoryContainerId,
                id -> mintPublishToken(repositoryContainerId, baseUrl));
    }

    /**
     * Mints one fresh API token under a fresh admin login, confirmed live: {@code
     * POST /api/v1/user/add_token} with the admin session cookie returns {@code
     * {"name":...,"token":"..."}}. The token itself never expires on its own in Kellnr's own model
     * (confirmed live across repeated use within this session); the admin session cookie that
     * mints it does, which is exactly why this is minted once and cached rather than re-derived
     * from a cached cookie later.
     */
    private String mintPublishToken(String repositoryContainerId, String baseUrl) {
        String cookie = adminLogin(repositoryContainerId, baseUrl);
        byte[] body = "{\"name\":\"floci\"}".getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        "/api/v1/user/add_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar to mint a publish token", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not mint a Kellnr publish token: upstream returned "
                    + response.statusCode());
        }
        try {
            return mapper.readTree(response.body()).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Kellnr's token-mint response", e);
        }
    }

    /**
     * Logs in as Kellnr's own {@code admin} account, confirmed live: {@code POST
     * /api/v1/user/login} with {@code {"user":"admin","pwd":"<this repository's own random
     * password>"}} returns a {@code kellnr_session_id} session cookie. The password itself comes
     * from {@link KellnrSidecarManager#adminPassword}, read fresh on every call rather than
     * cached, not Kellnr's own shipped {@code admin}/{@code admin} default (see that manager's
     * own javadoc for why). This admin account is purely internal to this sidecar container
     * (loopback-bound, never reachable except through Floci itself), the same trust boundary
     * every other container-backed format's own sidecar already relies on.
     */
    private String adminLogin(String repositoryContainerId, String baseUrl) {
        ObjectNode credentials = mapper.createObjectNode();
        credentials.put("user", "admin");
        credentials.put("pwd", manager.adminPassword(repositoryContainerId));
        byte[] body = credentials.toString().getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        "/api/v1/user/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar to log in as its admin", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not log in to the Kellnr sidecar as its admin: "
                    + "upstream returned " + response.statusCode());
        }
        return response.headers().firstValue("set-cookie")
                .map(raw -> raw.split(";", 2)[0])
                .orElseThrow(() -> new IllegalStateException("Kellnr sidecar login did not return a session "
                        + "cookie"));
    }

    /**
     * {@code empty()} when {@code version} doesn't match any version Kellnr actually holds for
     * this package, the matching sparse-index entry's own {@code vers} text otherwise. Cargo
     * version strings have no leading/trailing-zero aliasing the way NuGet's do (AWS documents no
     * such normalization for Cargo), so this is a plain exact-string match against the index,
     * unlike {@code BagetterSidecarClient#matchVersion}'s own normalized comparison.
     */
    private Optional<String> matchVersion(String repositoryContainerId, String packageName, String version) {
        for (JsonNode entry : sparseIndexEntries(repositoryContainerId, packageName)) {
            String candidate = entry.path("vers").asText(null);
            if (version.equals(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The real, complete sparse-index bytes for one crate, verbatim (confirmed live:
     * newline-delimited JSON, one object per version, each carrying its own real {@code deps}/
     * {@code cksum}/{@code features}, not just what {@link #listPackageVersions}' own abstracted
     * {@code PackageVersionTimestamp} carries). For {@code CodeArtifactCargoController} to serve
     * close to verbatim: a real {@code cargo} client needs those fields to resolve dependencies
     * and verify a download's integrity, information none of this interface's own generic bridge
     * methods carry. Empty, not an error, when the package was never published or this
     * repository's sidecar was never started.
     */
    Optional<byte[]> fetchSparseIndexRaw(String repositoryContainerId, String domain, String repository,
            String packageName) {
        return getBytes(repositoryContainerId, sparseIndexPath(packageName));
    }

    /**
     * Every line of this package's own sparse-index file, each one JSON describing one published
     * version (confirmed live: newline-delimited JSON, one object per version). Empty, not an
     * error, when the package was never published or this repository's sidecar was never started.
     */
    private List<JsonNode> sparseIndexEntries(String repositoryContainerId, String packageName) {
        Optional<byte[]> raw = getBytes(repositoryContainerId, sparseIndexPath(packageName));
        if (raw.isEmpty()) {
            return List.of();
        }
        List<JsonNode> entries = new ArrayList<>();
        for (String line : new String(raw.get(), StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                entries.add(mapper.readTree(trimmed));
            } catch (Exception e) {
                throw new IllegalStateException("Could not parse the Kellnr sidecar's sparse-index entry for "
                        + packageName, e);
            }
        }
        return entries;
    }

    /**
     * Kellnr's own bucketed index path, the real crates.io bucketing rule (see {@link
     * SidecarUriUtils#cratesIoBucketPath}, shared with {@code CargoExternalConnectionClient} so
     * the rule is written once) under Kellnr's own {@code /api/v1/crates/} prefix.
     */
    private static String sparseIndexPath(String packageName) {
        return "/api/v1/crates/" + SidecarUriUtils.cratesIoBucketPath(packageName);
    }

    private static String downloadPath(String packageName, String version) {
        return "/api/v1/crates/dl/" + SidecarUriUtils.encodeSegment(packageName) + "/"
                + SidecarUriUtils.encodeSegment(version) + "/download";
    }

    private Optional<byte[]> getBytes(String repositoryContainerId, String encodedPath) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Could not fetch " + encodedPath + " from the Kellnr sidecar: upstream "
                    + "returned " + response.statusCode());
        }
        return Optional.of(response.body());
    }

    /**
     * {@link #getBytes}'s streamed sibling, for the one asset download that can be large: a flat
     * 10-second timeout, fine for {@link #getBytes}'s own small-metadata callers, would cancel a
     * legitimately large, still-in-progress download here, the same gap every other container-
     * backed sidecar client's own streamed fetch already closes.
     */
    private Optional<StreamedAsset> getBytesStreamed(String repositoryContainerId, String encodedPath) {
        String baseUrl = ensureReady(repositoryContainerId, null);
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(SidecarUriUtils.maxTransferTimeout()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the Kellnr sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            StreamingResponses.closeQuietly(response.body());
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            StreamingResponses.closeQuietly(response.body());
            throw new IllegalStateException("Could not fetch " + encodedPath + " from the Kellnr sidecar: upstream "
                    + "returned " + response.statusCode());
        }
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return Optional.of(new StreamedAsset(response.body(), contentLength));
    }
}
