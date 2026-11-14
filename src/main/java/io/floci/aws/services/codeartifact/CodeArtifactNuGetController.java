package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.MultipartFormParser;
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves the real NuGet V3 wire protocol behind the URL {@code GetRepositoryEndpoint} returns for
 * the {@code nuget} format: the same source a real {@code nuget}/{@code dotnet} client speaks
 * against once {@code v3/index.json} is appended to it (AWS's own documented requirement, "The
 * source URL must end in /v3/index.json"), not a CodeArtifact API action. Backed by one BaGetter
 * container per repository, provisioned on first use.
 *
 * <p>Authors its own service index rather than proxying BaGetter's own: BaGetter's real {@code
 * v3/index.json} response embeds self-referential absolute URLs for every resource (its own base
 * address, exactly the problem {@link RepositorySidecarManager#ensureReady}'s {@code publicUrl}
 * parameter exists for), and this only ever needs two of those resources (see below), so
 * authoring them directly avoids rewriting BaGetter's response the way {@code
 * CodeArtifactPypiController} has to rewrite pypiserver's. Advertises only {@code
 * PackageBaseAddress/3.0.0} (install) and {@code PackagePublish/2.0.0} (push): deliberately omits
 * {@code RegistrationsBaseUrl} and {@code SearchQueryService} (see {@code BagetterSidecarClient}'s
 * own javadoc for why neither is needed for {@code nuget install}/{@code restore} to work, and
 * AWS's own docs call {@code nuget list}, the one command that would need search, unsupported
 * against CodeArtifact regardless).
 *
 * <p>Requires the bearer token real CodeArtifact requires here, obtained from {@code
 * GetAuthorizationToken}: AWS's own NuGet CLI configuration doc has the client authenticate with
 * HTTP Basic ({@code username=aws, password=<token>}), the same convention Maven's HTTP wagon and
 * pip/twine use, not npm's Bearer-only {@code _authToken}; {@link CodeArtifactTokenExtractor}
 * accepts either scheme.
 *
 * <p>Unlike Maven and npm, and like pypi, this rejects a re-push of a package version BaGetter has
 * already indexed itself, since BaGetter's own {@code AllowPackageOverwrites: false} default rejects
 * even a byte-identical retry (confirmed directly against its push controller): a pre-upload
 * check against BaGetter's own flat container and the existing version's own bytes is what gives
 * CodeArtifact's documented idempotent-republish exception ("Overwriting package assets": a
 * same-coordinate upload with identical content succeeds) to a sidecar that otherwise has none.
 */
@Path("/codeartifact/nuget/{domain}/{repository}")
public class CodeArtifactNuGetController {

    private final CodeArtifactService service;
    private final BagetterSidecarClient bagetter;
    private final CodeArtifactNuGetExternalConnectionImporter externalConnectionImporter;
    private final CodeArtifactNuGetUpstreamRepositoryImporter upstreamRepositoryImporter;
    private final HttpClient httpClient;

    @Inject
    public CodeArtifactNuGetController(CodeArtifactService service, BagetterSidecarClient bagetter,
            CodeArtifactNuGetExternalConnectionImporter externalConnectionImporter,
            CodeArtifactNuGetUpstreamRepositoryImporter upstreamRepositoryImporter) {
        this(service, bagetter, externalConnectionImporter, upstreamRepositoryImporter,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CodeArtifactNuGetController(CodeArtifactService service, BagetterSidecarClient bagetter,
            CodeArtifactNuGetExternalConnectionImporter externalConnectionImporter,
            CodeArtifactNuGetUpstreamRepositoryImporter upstreamRepositoryImporter, HttpClient httpClient) {
        this.service = service;
        this.bagetter = bagetter;
        this.externalConnectionImporter = externalConnectionImporter;
        this.upstreamRepositoryImporter = upstreamRepositoryImporter;
        this.httpClient = httpClient;
    }

    private record RepositoryHandle(String repoId, String baseUrl, String publicBaseUrl, String region,
                                     String owner) {
    }

    @GET
    @Path("v3/index.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response serviceIndex(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                  @PathParam("repository") String repository) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        try {
            return serviceIndexWithLease(handle);
        } finally {
            endUse(handle.repoId());
        }
    }

    private Response serviceIndexWithLease(RepositoryHandle handle) {
        // domain and repository are free to embed here unescaped: requireRepository above only
        // returns once CodeArtifactService has confirmed a repository by this exact name already
        // exists, which it could only do if that name already passed CreateRepository's own
        // DOMAIN_NAME/REPOSITORY_NAME validation, a character set with no '"', '\', or control
        // character either pattern allows through.
        String body = """
                {
                  "version": "3.0.0",
                  "resources": [
                    { "@id": "%1$s/v3/package/", "@type": "PackageBaseAddress/3.0.0" },
                    { "@id": "%1$s/api/v2/package", "@type": "PackagePublish/2.0.0" }
                  ]
                }
                """.formatted(handle.publicBaseUrl());
        return Response.status(200).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("v3/package/{id}/index.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response packageVersions(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                     @PathParam("repository") String repository, @PathParam("id") String id) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        try {
            return proxyGet(handle.baseUrl(), "/v3/package/" + SidecarUriUtils.encodeSegment(id) + "/index.json",
                    MediaType.APPLICATION_JSON);
        } finally {
            endUse(handle.repoId());
        }
    }

    /**
     * One route for the nupkg, nuspec, and readme downloads: all three share the same {@code
     * {id}/{version}/<file>} shape, and BaGetter itself doesn't validate the literal file name
     * segment against the real one either (confirmed directly against its package content
     * controller, which only binds {@code id} and {@code version} from the route; the AWS NuGet
     * API spec's push side documents the same disregard, "The file name ... [is] ignored"),
     * so dispatching on {@code filename}'s suffix here is exactly as faithful as BaGetter's own
     * routing.
     */
    @GET
    @Path("v3/package/{id}/{version}/{filename}")
    @Produces(MediaType.WILDCARD)
    public Response packageContent(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                    @PathParam("repository") String repository, @PathParam("id") String id,
                                    @PathParam("version") String version, @PathParam("filename") String filename) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        // The .nupkg branch below hands back a Response whose body is only drained later, when
        // JAX-RS writes the deferred StreamingOutput: this flag lets that branch defer releasing
        // the lease until that real drain happens (via StreamingResponses.closingWith), instead of
        // this method's own return letting a concurrent DeleteRepository stop the BaGetter
        // container mid-download.
        AtomicBoolean leaseTransferred = new AtomicBoolean();
        try {
            return packageContentWithLease(handle, domain, repository, id, version, filename, leaseTransferred);
        } finally {
            if (!leaseTransferred.get()) {
                endUse(handle.repoId());
            }
        }
    }

    private Response packageContentWithLease(RepositoryHandle handle, String domain, String repository, String id,
            String version, String filename, AtomicBoolean leaseTransferred) {
        try {
            service.requireContainerBackedVersionDownloadable(handle.owner(), handle.region(), domain, repository,
                    "nuget", null, id, version, handle.repoId());
        } catch (AwsException e) {
            return Response.status(404).build();
        }
        String baseUrl = handle.baseUrl();
        String lowerId = SidecarUriUtils.encodeSegment(id.toLowerCase(Locale.ROOT));
        String encodedVersion = SidecarUriUtils.encodeSegment(version);
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        if ("readme".equals(lowerFilename)) {
            return proxyGet(baseUrl, "/v3/package/" + lowerId + "/" + encodedVersion + "/readme",
                    "text/markdown");
        }
        if (lowerFilename.endsWith(".nuspec")) {
            return proxyGet(baseUrl, "/v3/package/" + lowerId + "/" + encodedVersion + "/"
                    + lowerId + ".nuspec", "text/xml");
        }
        if (lowerFilename.endsWith(".nupkg")) {
            // A cache miss on this emulator's own BaGetter is exactly the trigger AWS's own docs
            // describe for an external connection: fetch the real asset from the public repository
            // and retain it, then let the proxy below find it there, the same as any other
            // request. Not attempted at all when BaGetter already has it: the only extra cost on
            // every other request is this one cheap local existence check.
            if (!bagetter.packageVersionExists(handle.repoId(), domain, repository, null, id, version)) {
                // A repository has either an external connection or upstreams, never both: at
                // most one of these two calls ever does real work, and the other's own no-op is a
                // single cheap in-memory check (see CodeArtifactNuGetUpstreamRepositoryImporter's
                // own javadoc).
                if (!externalConnectionImporter.tryImport(handle.region(), domain, handle.owner(), repository,
                        handle.repoId(), id, version)) {
                    upstreamRepositoryImporter.tryImport(handle.region(), domain, handle.owner(), repository,
                            handle.repoId(), id, version);
                }
            }
            return proxyGetStreamed(baseUrl, "/v3/package/" + lowerId + "/" + encodedVersion + "/"
                    + lowerId + "." + SidecarUriUtils.encodeSegment(version.toLowerCase(Locale.ROOT)) + ".nupkg",
                    MediaType.APPLICATION_OCTET_STREAM, leaseTransferred, () -> endUse(handle.repoId()));
        }
        return Response.status(404).build();
    }

    /**
     * Handles a {@code nuget push}/{@code dotnet nuget push}: {@code PUT} to the repository's
     * {@code api/v2/package}, {@code multipart/form-data} whose first part is the raw bytes of
     * the {@code .nupkg} (the real NuGet push protocol's own documented shape; unlike twine's
     * upload, there is no separate {@code name}/{@code version} form field, so those are read
     * from the uploaded package's own embedded {@code .nuspec} instead).
     *
     * <p>The existence check and the forwarded push run inside a lock keyed by this repository's
     * id, the same reason {@code CodeArtifactPypiController#upload} does: without it, two pushes
     * racing the same new id/version could both observe "not published yet" before either one's
     * write actually lands.
     */
    @PUT
    @Path("api/v2/package")
    @Consumes(MediaType.WILDCARD)
    public Response push(@Context HttpHeaders headers, @PathParam("domain") String domain,
                          @PathParam("repository") String repository, InputStream body) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);

        try {
            return pushWithLease(headers, domain, repository, body, handle);
        } finally {
            endUse(handle.repoId());
        }
    }

    /**
     * Stages the whole raw multipart body to disk before anything else touches it, the same
     * reason {@code CodeArtifactPypiController#uploadWithLease} does: {@link #forwardPush} can
     * then send this exact file straight to BaGetter with no multipart rebuild at all, since it
     * already re-sends the entire inbound body verbatim, not a reconstructed one. Capped at
     * {@code CodeArtifactService.MAX_MULTIPART_ENVELOPE_BYTES} during the staging copy itself: the
     * envelope has no exact size semantics of its own against the 5 GB asset quota yet, but
     * leaving this particular copy fully uncapped would let a client force an arbitrarily large
     * file onto disk before the real per-asset check downstream ever runs.
     */
    private Response pushWithLease(HttpHeaders headers, String domain, String repository, InputStream body,
            RepositoryHandle handle) {
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String boundary = MultipartFormParser.extractBoundary(contentType).orElse(null);
        if (boundary == null) {
            return Response.status(400).entity("Could not determine multipart boundary from Content-Type.").build();
        }
        java.nio.file.Path stagedWholeBody;
        try {
            stagedWholeBody = service.stageAssetBody(
                    new AssetDigestingInputStream(body, CodeArtifactService.MAX_MULTIPART_ENVELOPE_BYTES));
        } catch (AssetDigestingInputStream.AssetTooLargeException e) {
            return Response.status(413).build();
        }
        try {
            return pushStagedBody(domain, repository, contentType, boundary, stagedWholeBody, handle);
        } finally {
            service.deleteStagedAssetBody(stagedWholeBody);
        }
    }

    /**
     * {@code stagedWholeBody}'s own file part is extracted into a second, clean staged file
     * (never the whole multipart envelope nor that one part held in a single array), the same
     * shape {@code CodeArtifactPypiController} uses.
     */
    private Response pushStagedBody(String domain, String repository, String contentType, String boundary,
            java.nio.file.Path stagedWholeBody, RepositoryHandle handle) {
        java.nio.file.Path stagedNupkg = stagedWholeBody.resolveSibling(stagedWholeBody.getFileName() + ".nupkg");
        try {
            MultipartFormParser.ExtractedForm form;
            try {
                form = MultipartFormParser.extractFilePart(stagedWholeBody, boundary, stagedNupkg);
            } catch (IOException e) {
                return Response.status(400).entity("Could not parse the uploaded multipart body.").build();
            }
            return pushExtractedForm(domain, repository, contentType, form, stagedWholeBody, stagedNupkg, handle);
        } finally {
            service.deleteStagedAssetBody(stagedNupkg);
        }
    }

    private Response pushExtractedForm(String domain, String repository, String contentType,
            MultipartFormParser.ExtractedForm form, java.nio.file.Path stagedWholeBody,
            java.nio.file.Path stagedNupkg, RepositoryHandle handle) {
        Optional<MultipartFormParser.ExtractedFile> file = form.file();
        if (file.isEmpty()) {
            return Response.status(400).entity("Upload must include the package file.").build();
        }
        long nupkgSize;
        try {
            nupkgSize = Files.size(stagedNupkg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Same 5 GB AWS quota PublishPackageVersion enforces: checked against the real asset
        // bytes alone (never the surrounding multipart envelope), once they are known, rather
        // than before anything was read at all the way a non-multipart format's own cap can be.
        if (nupkgSize > CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES) {
            return Response.status(413).build();
        }

        NuspecXml.Metadata nuspec;
        try {
            nuspec = NuspecXml.extractFromNupkg(stagedNupkg);
        } catch (RuntimeException e) {
            return Response.status(400).entity("Could not read the uploaded package: " + e.getMessage()).build();
        }
        String packageName = nuspec.id().toLowerCase(Locale.ROOT);
        String version = NuGetVersions.normalize(nuspec.version());

        // A package whose origin configuration sets publish:BLOCK refuses every dotnet nuget push,
        // the same way PublishPackageVersion refuses it for the generic format, ahead of the
        // byte-identical shortcut below: a retry of bytes already stored is still a publish. Also
        // the default for a package first retained from nuget.org (see
        // CodeArtifactService#ensureRetainedPackageOriginDefault).
        try {
            service.requireContainerBackedPackagePublishAllowed(handle.region(), domain, handle.owner(), repository,
                    "nuget", null, packageName);
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }

        // Raised by hectorvent's review: a real dotnet nuget push to a coordinate
        // updatePackageVersionsStatus/disposePackageVersions left Archived or Disposed must be
        // refused outright, the same way PublishPackageVersion already refuses it for the generic
        // format, not accepted and then have forgetContainerBackedStatusOverride below silently
        // un-block it afterward. Checked before even the existence check below, let alone the real
        // write, the same way CodeArtifactNpmDataPlane's own guard already runs ahead of its
        // byte-identical shortcut (Greptile review: an archived coordinate re-pushed byte-identical
        // must still be refused, not quietly accepted as "nothing changed").
        String beforeRevision;
        try {
            beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(handle.region(), domain,
                    handle.owner(), repository, "nuget", null, packageName, version);
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }

        // Raised by Greptile's review: DisposePackageVersions holds CodeArtifactService's own
        // lock for its whole call, and its container-backed asset deletion (see
        // CodeArtifactService#deleteContainerBackedVersionAssets) acquires this exact same
        // per-repository BaGetter lock from inside that hold. Calling any synchronized
        // CodeArtifactService method (recordContainerBackedAssetBytes/
        // forgetContainerBackedStatusOverride below) from inside this lock, as both used to, would
        // let a push and a concurrent dispose of the same version each wait forever on the other's
        // lock. Neither call needs this lock at all: BaGetter's own content is already finalized by
        // the time forwardPush below returns, so both bookkeeping calls run after this block
        // releases it instead. currentContainerBackedPackageFence is exempt from this concern: it
        // is deliberately lock-free (see its own javadoc), not a synchronized CodeArtifactService
        // method, so taking the snapshot before entering the lock changes nothing it protects
        // against.
        long fenceSnapshot = service.currentContainerBackedPackageFence(handle.region(), domain, handle.owner(),
                repository, "nuget", null, packageName);
        Response response;
        boolean pushSucceeded;
        // The exact same lock BagetterSidecarClient#deletePackage takes for this repository, not
        // a private one: without sharing it, a DeletePackage racing this push could still list
        // its versions before this push lands and miss it, the one case a private lock here
        // could never close no matter how it synchronized with itself.
        synchronized (bagetter.lockFor(handle.repoId())) {
            ExistingNupkgComparison existing = compareExistingNupkg(handle.baseUrl(), packageName, version,
                    stagedNupkg);
            if (existing == ExistingNupkgComparison.MATCHES) {
                return Response.status(200).build();
            }
            if (existing == ExistingNupkgComparison.DIFFERS) {
                return Response.status(409)
                        .entity("A package with this id and version already exists.")
                        .build();
            }
            // The entire original raw multipart body is re-sent verbatim, boundary and all,
            // exactly as the byte[]-based version of this call always has: BaGetter parses its
            // own multipart form the same way dotnet nuget push built it, so there was never a
            // need to rebuild one here, only to stream the one already staged instead of a
            // resident array.
            response = forwardPush(handle.baseUrl(), contentType, stagedWholeBody);
            pushSucceeded = response.getStatus() == 200 || response.getStatus() == 201
                    || response.getStatus() == 202;
        }
        if (pushSucceeded) {
            service.completeContainerBackedPublishIfCurrentRepository(handle.region(), domain, handle.owner(),
                    repository, handle.repoId(), "nuget", packageName, version, nupkgSize, fenceSnapshot,
                    beforeRevision, null, 0L);
        }
        return response;
    }

    private enum ExistingNupkgComparison { NOT_LISTED, MATCHES, DIFFERS }

    /**
     * {@code existingNupkgContent}'s own streamed sibling: compares BaGetter's own stored package
     * against {@code localNupkg} chunk by chunk from each side, rather than buffering the remote
     * file into a {@code byte[]} first (capped at ~2 GiB) just to compare it against another
     * array, the same technique {@code CodeArtifactPypiController#compareExistingFile} and {@code
     * ReposiliteSidecarClient}'s own streamed idempotent-match check both use. A connectivity
     * failure here throws rather than returning {@code NOT_LISTED}, the same fail-safe reasoning
     * the original method documented: letting a push with different content through to BaGetter
     * during exactly the moment this check is unavailable would be the opposite of fail-safe, and
     * BaGetter's own {@code AllowPackageOverwrites: false} would reject it anyway, just with a
     * response this proxy no longer controls.
     */
    private ExistingNupkgComparison compareExistingNupkg(String baseUrl, String packageName, String version,
            java.nio.file.Path localNupkg) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/v3/package/"
                + SidecarUriUtils.encodeSegment(packageName) + "/" + SidecarUriUtils.encodeSegment(version) + "/"
                + SidecarUriUtils.encodeSegment(packageName) + "." + SidecarUriUtils.encodeSegment(version)
                + ".nupkg");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not check nuget package " + packageName + " " + version + " for an existing push", e);
        }
        if (response.statusCode() == 404) {
            // Raised by Copilot's review of this PR: a brand-new package (the common case for a
            // first-time push) answers 404 here every time, so leaving its body unclosed leaked
            // the underlying response/connection on every such push, not just an edge case.
            StreamingResponses.closeQuietly(response.body());
            return ExistingNupkgComparison.NOT_LISTED;
        }
        if (response.statusCode() != 200) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Closing an unread stream best-effort only; the exception thrown right below is
                // the one that actually matters here.
            }
            throw new IllegalStateException(
                    "Could not check nuget package " + packageName + " " + version + ": upstream returned "
                            + response.statusCode());
        }
        try (InputStream remote = response.body(); InputStream local = Files.newInputStream(localNupkg)) {
            return StreamEquality.contentsMatch(remote, local) ? ExistingNupkgComparison.MATCHES
                    : ExistingNupkgComparison.DIFFERS;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not compare the existing nuget package " + packageName + " " + version
                            + " against the uploaded content", e);
        }
    }

    private Response forwardPush(String baseUrl, String contentType, java.nio.file.Path stagedWholeBody) {
        long stagedSize;
        try {
            stagedSize = Files.size(stagedWholeBody);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl),
                        "/api/v2/package"))
                .timeout(SidecarUriUtils.transferTimeout(stagedSize))
                .header("Content-Type", contentType);
        try {
            requestBuilder.PUT(BodyPublishers.ofFile(stagedWholeBody));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar to push this package", e);
        }
        return Response.status(response.statusCode()).build();
    }

    /**
     * {@code encodedPath} must already be fully percent-encoded: see {@link SidecarUriUtils
     * #combine} for why a raw {@code URI.create(baseUrl + path)} concatenation isn't safe here.
     */
    private Response proxyGet(String baseUrl, String encodedPath, String fallbackContentType) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            return Response.status(404).build();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse(fallbackContentType);
        return Response.status(response.statusCode()).entity(response.body()).type(contentType).build();
    }

    /**
     * {@link #proxyGet}'s streamed sibling, for the {@code .nupkg} branch of {@link
     * #packageContentWithLease}: that method's own {@code byte[]} is fine for a readme or
     * {@code .nuspec}, but a downloaded package itself can be gigabytes.
     *
     * <p>{@code afterClose} is the caller's lease release: on an immediate failure (the sidecar
     * unreachable, or a 404) it runs right here, synchronously, since nothing is left streaming.
     * On a real 200, it runs only once the entity stream this method returns is actually closed
     * (via {@code leaseTransferred} and {@link StreamingResponses#closingWith}), so the sidecar
     * container this is downloading from stays leased for as long as the real transfer takes,
     * not just until this method itself returns.
     *
     * <p>Uses the worst-case download timeout, not {@link #proxyGet}'s flat 10 seconds: the real
     * {@code .nupkg} this fetches can be gigabytes, and its real size isn't known until the
     * response itself arrives.
     */
    private Response proxyGetStreamed(String baseUrl, String encodedPath, String fallbackContentType,
            AtomicBoolean leaseTransferred, Runnable afterClose) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(SidecarUriUtils.maxTransferTimeout()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the BaGetter sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            StreamingResponses.closeQuietly(response.body());
            return Response.status(404).build();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse(fallbackContentType);
        Response.ResponseBuilder builder = Response.status(response.statusCode()).type(contentType);
        response.headers().firstValueAsLong("Content-Length").ifPresent(len -> builder.header("Content-Length", len));
        leaseTransferred.set(true);
        InputStream body = StreamingResponses.closingWith(response.body(), afterClose);
        return StreamingResponses.streamingResponse(body, stream -> builder.entity(stream).build());
    }

    private RepositoryHandle requireRepository(HttpHeaders headers, String domain, String repository) {
        String token = CodeArtifactTokenExtractor.extractToken(headers);
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException("Basic realm=\"floci-codeartifact\""));
        String repoId;
        try {
            repoId = service.resolveContainerIdAndBeginUse("nuget", scope.region(), domain, scope.owner(),
                    repository, bagetter);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        try {
            return readyRepository(scope, domain, repository, repoId);
        } catch (RuntimeException e) {
            endUse(repoId);
            throw e;
        }
    }

    private void endUse(String repoId) {
        bagetter.endUse(repoId);
    }

    private RepositoryHandle readyRepository(AuthorizationTokenScope scope, String domain, String repository,
            String repoId) {
        String baseUrl = bagetter.ensureReady(repoId, null);
        // The real GetRepositoryEndpoint value, not just this controller's own path: a NuGet
        // client's own service-index parser only keeps a resource whose @id parses as an
        // absolute URI (confirmed directly against NuGet.Protocol's own ServiceIndexResourceV3,
        // and the official NuGet API docs' own wording, "each resource's @id must be an absolute
        // URL with an HTTP or HTTPS scheme"), so the index this controller authors has to embed
        // the same scheme/host (and any configured path prefix) real clients already reach this
        // controller through, not a bare path with neither.
        String endpoint = service.getRepositoryEndpoint(scope.region(), domain, scope.owner(), repository, "nuget",
                null);
        String publicBaseUrl = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return new RepositoryHandle(repoId, baseUrl, publicBaseUrl, scope.region(), scope.owner());
    }
}
