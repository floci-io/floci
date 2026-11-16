package io.floci.aws.services.codeartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StreamingResponses;
import io.floci.aws.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves the real Cargo (Rust) wire protocol behind the URL {@code GetRepositoryEndpoint} returns
 * for the {@code cargo} format: the sparse HTTP registry index (RFC 2789) and the publish/download
 * REST API AWS's own "Configure and use Cargo with CodeArtifact" guide documents, not a
 * CodeArtifact API action. Backed by one Kellnr container per repository, provisioned on first
 * use (see {@link KellnrSidecarManager}/{@link KellnrSidecarClient} for the sidecar itself).
 *
 * <p>Authors its own {@code config.json} rather than proxying Kellnr's own: Kellnr's own response
 * self-reports its own internal container address (confirmed live), exactly the problem {@link
 * RepositorySidecarManager#ensureReady}'s {@code publicUrl} parameter exists for, and both the
 * {@code dl}/{@code api} it needs are a single combined base identical to this repository's own
 * real endpoint, matching how AWS's own CodeArtifact serves Cargo (one endpoint for index, API,
 * and downloads alike, unlike crates.io's own split between its index and its API host). The
 * sparse-index files themselves (one per crate) *are* proxied close to verbatim: unlike a
 * version's own downloadable asset, nothing in that response is self-referential (a real client
 * derives the download URL from {@code config.json}'s own {@code dl} template, never from a field
 * inside an index entry), so there is nothing here to rewrite.
 *
 * <p>Requires the token real CodeArtifact requires here, obtained from {@code
 * GetAuthorizationToken}. AWS's own Cargo configuration doc has the client send it as a bare
 * {@code Authorization} header with no scheme prefix at all (confirmed from the Cargo Book's own
 * registry-authentication page); {@link CodeArtifactTokenExtractor} accepts that alongside the
 * Bearer/Basic schemes every other format already uses.
 *
 * <p>Only the subcommands AWS's own guide documents against CodeArtofact: {@code cargo add}/
 * {@code cargo build} (the sparse index plus the download route) and {@code cargo publish}. No
 * {@code yank}/{@code owner}/search routes: AWS's own guide never mentions any of the three, so
 * this isn't filling a real, documented gap by building them.
 */
@Path("/codeartifact/cargo/{domain}/{repository}/{path:.+}")
public class CodeArtifactCargoController {

    private static final String PUBLISH_PATH = "api/v1/crates/new";
    private static final String DOWNLOAD_PREFIX = "api/v1/crates/";
    private static final String DOWNLOAD_SUFFIX = "/download";
    private static final String CONFIG_PATH = "config.json";
    // A real publish's own metadata (name, version, deps, features, authors, etc.) is plain JSON
    // text, always well under a megabyte even for a crate with an unusually large dependency
    // list; this is a generous ceiling, not a realistic average, against the wire body's own
    // 4-byte client-declared metadata length driving an allocation far larger than the staged
    // file could ever actually need before the crate's own size is checked.
    private static final int MAX_PUBLISH_METADATA_BYTES = 10 * 1024 * 1024;

    private final CodeArtifactService service;
    private final KellnrSidecarClient kellnr;
    private final CodeArtifactCargoExternalConnectionImporter externalConnectionImporter;
    private final CodeArtifactCargoUpstreamRepositoryImporter upstreamRepositoryImporter;
    private final ObjectMapper mapper;

    @Inject
    public CodeArtifactCargoController(CodeArtifactService service, KellnrSidecarClient kellnr,
            CodeArtifactCargoExternalConnectionImporter externalConnectionImporter,
            CodeArtifactCargoUpstreamRepositoryImporter upstreamRepositoryImporter, ObjectMapper mapper) {
        this.service = service;
        this.kellnr = kellnr;
        this.externalConnectionImporter = externalConnectionImporter;
        this.upstreamRepositoryImporter = upstreamRepositoryImporter;
        this.mapper = mapper;
    }

    private record RepositoryHandle(String repoId, String region, String owner, String publicBaseUrl) {
    }

    @GET
    @Produces(MediaType.WILDCARD)
    public Response get(@Context HttpHeaders headers, @PathParam("domain") String domain,
                         @PathParam("repository") String repository, @PathParam("path") String path) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        AtomicBoolean leaseTransferred = new AtomicBoolean();
        try {
            return getWithLease(handle, domain, repository, path, leaseTransferred);
        } finally {
            if (!leaseTransferred.get()) {
                endUse(handle.repoId());
            }
        }
    }

    private Response getWithLease(RepositoryHandle handle, String domain, String repository, String path,
            AtomicBoolean leaseTransferred) {
        if (CONFIG_PATH.equals(path)) {
            return configJson(handle);
        }
        Optional<String[]> download = parseDownloadPath(path);
        if (download.isPresent()) {
            return downloadCrate(handle, domain, repository, download.get()[0], download.get()[1], leaseTransferred);
        }
        return sparseIndexLookup(handle, domain, repository, path);
    }

    /**
     * Cargo's own sparse-index discovery document, authored directly rather than proxied (see
     * this class's own javadoc for why): {@code dl}/{@code api} both rooted at this repository's
     * own real {@code GetRepositoryEndpoint} value, the same "always the real, externally-reachable
     * address, never this emulator's own internal one" rule {@code CodeArtifactNuGetController
     * .serviceIndex} already follows. {@code auth-required: true} matches real CodeArtifact's own
     * behavior: every request here, read or write, already requires a valid token.
     */
    private Response configJson(RepositoryHandle handle) {
        String body = """
                {
                  "dl": "%1$s/api/v1/crates",
                  "api": "%1$s",
                  "auth-required": true
                }
                """.formatted(handle.publicBaseUrl());
        return Response.status(200).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    private Response downloadCrate(RepositoryHandle handle, String domain, String repository, String packageName,
            String version, AtomicBoolean leaseTransferred) {
        try {
            service.requireContainerBackedVersionDownloadable(handle.owner(), handle.region(), domain, repository,
                    "cargo", null, packageName, version, handle.repoId());
        } catch (AwsException e) {
            return Response.status(404).build();
        }
        // A cache miss on this emulator's own Kellnr is exactly the trigger AWS's own docs
        // describe for an external connection: fetch the real crate from crates.io and retain it,
        // then let the stream below find it there, the same as any other request. Not attempted
        // at all when Kellnr already has it: the only extra cost on every other request is this
        // one cheap local existence check, the same pattern CodeArtifactNuGetController's own
        // .nupkg route already uses.
        if (!kellnr.packageVersionExists(handle.repoId(), domain, repository, null, packageName, version)) {
            // A repository has either an external connection or upstreams, never both: at most
            // one of these two calls ever does real work, and the other's own no-op is a single
            // cheap in-memory check, the same pattern CodeArtifactNuGetController's own .nupkg
            // route already uses.
            if (!externalConnectionImporter.tryImport(handle.region(), domain, handle.owner(), repository,
                    handle.repoId(), packageName, version)) {
                upstreamRepositoryImporter.tryImport(handle.region(), domain, handle.owner(), repository,
                        handle.repoId(), packageName, version);
            }
        }
        Optional<RepositorySidecarManager.StreamedAsset> streamed = kellnr.fetchPackageVersionAssetStreamed(
                handle.repoId(), domain, repository, null, packageName, version,
                packageName + "-" + version + ".crate");
        if (streamed.isEmpty()) {
            return Response.status(404).build();
        }
        leaseTransferred.set(true);
        InputStream body = StreamingResponses.closingWith(streamed.get().content(), () -> endUse(handle.repoId()));
        Response.ResponseBuilder builder = Response.status(200).type(MediaType.APPLICATION_OCTET_STREAM);
        if (streamed.get().contentLength() >= 0) {
            builder.header("Content-Length", streamed.get().contentLength());
        }
        return StreamingResponses.streamingResponse(body, stream -> builder.entity(stream).build());
    }

    /**
     * The real sparse-index bytes for the crate {@code path}'s own last segment names (the real
     * crates.io bucketing scheme, confirmed live in part 1: a 1/2/3-character name buckets by its
     * own length or first character, a 4+-character name by its own first two then next two
     * characters: in every case, the crate's real name is the path's own final segment), proxied
     * close to verbatim. Not filtered by Archived/Disposed status here: see this class's own
     * javadoc for why that mapping is deliberately deferred rather than guessed at.
     *
     * <p>Merged with any real upstream-only version lines the repository's own {@code
     * public:crates-io} external connection, and now its own same-domain upstream chain too, can
     * see (see {@link CodeArtifactCargoExternalConnectionImporter}'s own javadoc for why this,
     * unlike every other format's own external-connection importer, needs a listing merge and not
     * just an on-demand download, and {@link CodeArtifactCargoUpstreamRepositoryImporter}'s own
     * javadoc for the same reasoning extended across an upstream chain): a real {@code cargo}
     * client's dependency resolver only ever sees a version by first finding it here.
     */
    private Response sparseIndexLookup(RepositoryHandle handle, String domain, String repository, String path) {
        int lastSlash = path.lastIndexOf('/');
        String packageName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (packageName.isEmpty()) {
            return Response.status(404).build();
        }
        Optional<byte[]> local = kellnr.fetchSparseIndexRaw(handle.repoId(), domain, repository, packageName);
        Optional<byte[]> mergedExternal = externalConnectionImporter.mergeExternalVersions(handle.region(), domain,
                handle.owner(), repository, packageName, local);
        Optional<byte[]> merged = upstreamRepositoryImporter.mergeUpstreamRepositoryVersions(handle.region(), domain,
                handle.owner(), repository, handle.repoId(), packageName, mergedExternal);
        return merged.map(bytes -> Response.status(200).entity(bytes).type(MediaType.APPLICATION_OCTET_STREAM)
                        .build())
                .orElseGet(() -> Response.status(404).build());
    }

    /** {@code api/v1/crates/{name}/{version}/download} → {@code [name, version]}, else empty. */
    private static Optional<String[]> parseDownloadPath(String path) {
        if (!path.startsWith(DOWNLOAD_PREFIX) || !path.endsWith(DOWNLOAD_SUFFIX)) {
            return Optional.empty();
        }
        String middle = path.substring(DOWNLOAD_PREFIX.length(), path.length() - DOWNLOAD_SUFFIX.length());
        int lastSlash = middle.lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash == middle.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new String[] {middle.substring(0, lastSlash), middle.substring(lastSlash + 1)});
    }

    /**
     * Handles a real {@code cargo publish}: {@code PUT} to {@code api/v1/crates/new}, the real
     * binary wire body (4-byte little-endian metadata length + JSON metadata, then 4-byte
     * little-endian crate length + the {@code .crate} itself) confirmed directly from the Cargo
     * Book's own spec text and live against Kellnr in part 1. The whole body is staged to disk
     * before anything else touches it (never a resident array: a {@code .crate} can approach the
     * same 5 GB AWS quota any other format's asset can), then forwarded to Kellnr verbatim via
     * {@link KellnrSidecarClient#publishRawWireBody}: the real client's own metadata, dependency
     * list included, must survive untouched, the reason that method exists separately from {@link
     * KellnrSidecarClient#storePackageVersionAsset}'s own reconstructed-metadata path.
     */
    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response publish(@Context HttpHeaders headers, @PathParam("domain") String domain,
                             @PathParam("repository") String repository, @PathParam("path") String path,
                             InputStream body) {
        if (!PUBLISH_PATH.equals(path)) {
            return Response.status(404).build();
        }
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        try {
            return publishWithLease(handle, domain, repository, body);
        } finally {
            endUse(handle.repoId());
        }
    }

    private Response publishWithLease(RepositoryHandle handle, String domain, String repository, InputStream body) {
        java.nio.file.Path staged;
        try {
            staged = service.stageAssetBody(
                    new AssetDigestingInputStream(body, CodeArtifactService.MAX_MULTIPART_ENVELOPE_BYTES));
        } catch (AssetDigestingInputStream.AssetTooLargeException e) {
            return Response.status(413).build();
        }
        try {
            return publishStagedBody(handle, domain, repository, staged);
        } finally {
            service.deleteStagedAssetBody(staged);
        }
    }

    private Response publishStagedBody(RepositoryHandle handle, String domain, String repository,
            java.nio.file.Path staged) {
        CargoPublishMetadata metadata;
        long stagedSize;
        try {
            stagedSize = Files.size(staged);
            metadata = readMetadataPrefix(staged);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(e.getMessage()).build();
        }
        // The crate's own real size, not the whole wire body (metadata included): the same 5 GB
        // AWS quota PublishPackageVersion enforces, checked against the real asset bytes alone,
        // the same way CodeArtifactNuGetController#pushExtractedForm checks the real .nupkg size
        // rather than the surrounding multipart envelope.
        long crateLength = stagedSize - 4 - metadata.metadataLength() - 4;
        if (crateLength > CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES) {
            return Response.status(413).build();
        }
        try {
            service.requireContainerBackedPackagePublishAllowed(handle.region(), domain, handle.owner(), repository,
                    "cargo", null, metadata.name());
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
        String beforeRevision;
        try {
            beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(handle.region(), domain,
                    handle.owner(), repository, "cargo", null, metadata.name(), metadata.version());
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
        long fenceSnapshot = service.currentContainerBackedPackageFence(handle.region(), domain, handle.owner(),
                repository, "cargo", null, metadata.name());
        KellnrSidecarClient.KellnrPublishResult result;
        synchronized (kellnr.lockFor(handle.repoId())) {
            result = kellnr.publishRawWireBody(handle.repoId(), staged, stagedSize);
        }
        if (result.statusCode() != 200) {
            // Kellnr's own real rejection (e.g. a republish of an already-existing name/version,
            // see KellnrSidecarClient#publishRawWireBody's own javadoc) is already shaped exactly
            // like the Cargo Book's own documented error response: forward it verbatim rather than
            // translating it into something this controller invents.
            return Response.status(result.statusCode()).entity(result.body()).type(MediaType.APPLICATION_JSON)
                    .build();
        }
        service.completeContainerBackedPublishIfCurrentRepository(handle.region(), domain, handle.owner(), repository,
                handle.repoId(), "cargo", metadata.name(), metadata.version(), crateLength, fenceSnapshot,
                beforeRevision, null, 0L);
        return Response.status(200).entity("{\"warnings\":null}").type(MediaType.APPLICATION_JSON).build();
    }

    private record CargoPublishMetadata(String name, String version, int metadataLength) {
    }

    /**
     * Reads just the wire body's own leading length-prefixed JSON metadata (never the crate bytes
     * that follow it) to learn {@code name}/{@code version}, the same real binary layout {@link
     * KellnrSidecarClient#publishRawWireBody}'s own javadoc documents.
     */
    private CargoPublishMetadata readMetadataPrefix(java.nio.file.Path staged) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(staged))) {
            byte[] lengthBytes = in.readNBytes(4);
            if (lengthBytes.length != 4) {
                throw new IllegalArgumentException("Truncated publish body: missing the metadata length prefix.");
            }
            int metadataLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (metadataLength < 0 || metadataLength > MAX_PUBLISH_METADATA_BYTES) {
                throw new IllegalArgumentException("Publish body's own declared metadata length ("
                        + metadataLength + " bytes) is not a reasonable size.");
            }
            byte[] metadataBytes = in.readNBytes(metadataLength);
            if (metadataBytes.length != metadataLength) {
                throw new IllegalArgumentException("Truncated publish body: metadata shorter than its own "
                        + "declared length.");
            }
            JsonNode metadata;
            try {
                metadata = mapper.readTree(metadataBytes);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not parse the publish body's own metadata as JSON.");
            }
            String name = metadata.path("name").asText(null);
            String version = metadata.path("vers").asText(null);
            if (name == null || name.isBlank() || version == null || version.isBlank()) {
                throw new IllegalArgumentException("Publish metadata is missing a crate name or version.");
            }
            return new CargoPublishMetadata(name, version, metadataLength);
        }
    }

    private void endUse(String repoId) {
        kellnr.endUse(repoId);
    }

    private RepositoryHandle requireRepository(HttpHeaders headers, String domain, String repository) {
        String token = CodeArtifactTokenExtractor.extractToken(headers);
        // No WWW-Authenticate scheme challenge: unlike Maven/pypi/NuGet (genuinely Basic, per
        // AWS's own docs) or npm (genuinely Bearer), a real cargo client's own token has no named
        // HTTP auth scheme at all (it's sent as a bare Authorization value), so naming one here
        // would misleadingly suggest a scheme real CodeArtifact doesn't actually expect for Cargo.
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException(Response.status(401).build()));
        String repoId;
        try {
            repoId = service.resolveContainerIdAndBeginUse("cargo", scope.region(), domain, scope.owner(), repository,
                    kellnr);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        try {
            String endpoint = service.getRepositoryEndpoint(scope.region(), domain, scope.owner(), repository,
                    "cargo", null);
            String publicBaseUrl = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            return new RepositoryHandle(repoId, scope.region(), scope.owner(), publicBaseUrl);
        } catch (RuntimeException e) {
            endUse(repoId);
            throw e;
        }
    }
}
