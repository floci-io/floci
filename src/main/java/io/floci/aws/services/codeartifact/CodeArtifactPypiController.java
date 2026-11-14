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
import jakarta.ws.rs.POST;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Serves the real PyPI simple-repository wire protocol behind the URL
 * {@code GetRepositoryEndpoint} returns for the {@code pypi} format: the same plain GET/POST
 * pip and twine speak against real PyPI, not a CodeArtifact API action. Backed by one pypiserver
 * container per repository, provisioned on first use.
 *
 * <p>Requires the bearer token real CodeArtifact requires here, obtained from
 * {@code GetAuthorizationToken} and scoped to the domain being accessed. AWS documents pip and
 * twine both authenticating with HTTP Basic ({@code username=aws, password=<token>}, verified
 * against AWS's own pip and twine configuration docs), the same convention Maven's HTTP wagon
 * uses, not npm's Bearer-only {@code _authToken}; {@link CodeArtifactTokenExtractor} accepts
 * either scheme.
 *
 * <p>A pip/twine request carries no SigV4 {@code Authorization} header, so there is no account or
 * Region to resolve from it the way the CodeArtifact JSON API does; the token itself supplies
 * both, since it was issued under a specific account and Region.
 *
 * <p>Unlike Maven and npm, this rejects a re-upload of a file pypiserver has already published
 * itself: pypiserver has no immutability check of its own (confirmed by uploading the same
 * name/version/filename twice against a real instance; the second upload silently replaces the
 * first), unlike Reposilite ({@code redeployment: false}) and Verdaccio (rejects natively,
 * matching real npm). A pre-upload check against pypiserver's own simple index and the existing
 * file's own bytes is what gives PyPI the same immutability guarantee the other two formats
 * already get for free from their sidecars, including AWS's own documented exception to it:
 * republishing an asset whose content is byte-identical to what is already there succeeds
 * idempotently rather than conflicting (verified against AWS's CodeArtifact packages-overview
 * docs, "Overwriting package assets": only a same-name upload with <i>different</i> content
 * returns 409).
 */
@Path("/codeartifact/pypi/{domain}/{repository}")
public class CodeArtifactPypiController {

    private static final String SIMPLE_PATH = "/simple/";

    /** Matches an {@code href="..."} attribute in pypiserver's own simple-index HTML. */
    private static final Pattern SIMPLE_INDEX_HREF = Pattern.compile("href=\"([^\"]*)\"");

    private final CodeArtifactService service;
    private final PypiserverSidecarClient pypiserver;
    private final CodeArtifactPypiExternalConnectionImporter externalConnectionImporter;
    private final CodeArtifactPypiUpstreamRepositoryImporter upstreamRepositoryImporter;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, Object> uploadLocks = new ConcurrentHashMap<>();

    @Inject
    public CodeArtifactPypiController(CodeArtifactService service, PypiserverSidecarClient pypiserver,
            CodeArtifactPypiExternalConnectionImporter externalConnectionImporter,
            CodeArtifactPypiUpstreamRepositoryImporter upstreamRepositoryImporter) {
        this(service, pypiserver, externalConnectionImporter, upstreamRepositoryImporter,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CodeArtifactPypiController(CodeArtifactService service, PypiserverSidecarClient pypiserver,
            CodeArtifactPypiExternalConnectionImporter externalConnectionImporter,
            CodeArtifactPypiUpstreamRepositoryImporter upstreamRepositoryImporter, HttpClient httpClient) {
        this.service = service;
        this.pypiserver = pypiserver;
        this.externalConnectionImporter = externalConnectionImporter;
        this.upstreamRepositoryImporter = upstreamRepositoryImporter;
        this.httpClient = httpClient;
    }

    private record RepositoryHandle(String repoId, String baseUrl, String pathPrefix, String region,
                                     String owner) {
    }

    // No route for the bare "/simple/" project-list endpoint: AWS's CodeArtifact User Guide
    // documents it as unsupported ("Unsupported PyPI APIs", only /simple/<project>/ is served).

    /**
     * pypiserver's own simple-index HTML links to package files with a root-relative
     * {@code href="/packages/<file>"}, resolved by pip against whatever host it actually
     * connected to: this proxy's own host, not pypiserver's. Left unrewritten, that resolves to
     * {@code /packages/<file>} at the proxy's root, not under this repository's
     * {@code /codeartifact/pypi/<domain>/<repository>/} prefix where {@link #downloadFile} is
     * actually routed, so pip would 404 trying to install anything the index lists. Verified
     * against a real pypiserver response before writing this, not assumed.
     *
     * <p>Merges in whichever files {@code repository}'s own pypi external connection names that
     * are not already listed above, the same promise-now/fetch-later split {@code
     * CodeArtifactNpmExternalConnectionImporter#mergedPackument} already documents for npm:
     * without this, a real {@code pip} client could never even learn that an unretained file
     * exists, let alone request it through {@link #downloadFile}, the one place its real bytes
     * are actually fetched and stored. Run even when pypiserver itself has never heard of this
     * package at all ({@code response.getStatus() == 404}): a package that only exists on the
     * real public registry so far must still be listable, the same as real CodeArtifact.
     */
    @GET
    @Path("simple/{package}/")
    @Produces(MediaType.TEXT_HTML)
    public Response packageIndex(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                  @PathParam("repository") String repository,
                                  @PathParam("package") String packageName) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        try {
            Response response = proxyGet(handle.baseUrl(), SIMPLE_PATH + SidecarUriUtils.encodeSegment(packageName)
                    + "/");
            String rewrittenOrNull;
            Set<String> knownFilenames;
            if (response.getStatus() == 200) {
                String html = new String((byte[]) response.getEntity(), StandardCharsets.UTF_8);
                rewrittenOrNull = html.replace("href=\"/packages/", "href=\"" + handle.pathPrefix() + "/packages/");
                knownFilenames = hrefFilenames(html);
            } else if (response.getStatus() == 404) {
                rewrittenOrNull = null;
                knownFilenames = Set.of();
            } else {
                return response;
            }
            // A repository has either an external connection or upstreams, never both: at most
            // one of these two calls ever finds anything, and the other's own no-op is cheap
            // (see CodeArtifactPypiUpstreamRepositoryImporter's own javadoc).
            List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional =
                    externalConnectionImporter.additionalExternalFilenames(handle.region(), domain, handle.owner(),
                            repository, handle.repoId(), packageName, knownFilenames);
            if (additional.isEmpty()) {
                additional = upstreamRepositoryImporter.additionalInternalFilenames(handle.region(), domain,
                        handle.owner(), repository, handle.repoId(), packageName, knownFilenames);
            }
            if (rewrittenOrNull == null && additional.isEmpty()) {
                return response;
            }
            String merged = withAdditionalFilenames(rewrittenOrNull, handle.pathPrefix(), additional);
            return Response.status(200).entity(merged.getBytes(StandardCharsets.UTF_8)).type(MediaType.TEXT_HTML)
                    .build();
        } finally {
            pypiserver.endUse(handle.repoId());
        }
    }

    /**
     * Appends an {@code <a href="...">} entry for each of {@code additional}'s filenames to
     * {@code bodyHtmlOrNull}, right before its closing {@code </body>} tag, or builds a minimal
     * document from scratch when {@code bodyHtmlOrNull} is {@code null} (pypiserver itself has no
     * local listing for this package at all yet). {@code additional} ultimately comes from the
     * real public registry's own response, and nothing here depends on it being free of
     * characters that would otherwise be read as markup: the link text is HTML-escaped, and the
     * {@code href} itself is percent-encoded via {@link SidecarUriUtils#encodeSegment} rather than
     * merely HTML-escaped, the same way every other place in this controller that places a
     * caller- or response-supplied filename into a URI path segment already does (see {@link
     * #downloadFile}); HTML-escaping alone would leave a literal {@code #} or {@code ?} free to
     * be read as a URI fragment or query string by whatever real client resolves this href,
     * silently truncating the path it actually requests.
     *
     * <p>Each entry also carries the real registry's own {@code data-yanked}/{@code
     * data-requires-python} attributes (PEP 592/PEP 700) when it has them: a real {@code pip}
     * client uses exactly these to filter out a yanked release or one its own interpreter cannot
     * satisfy, and without them here, a file this repository has not retained yet would look
     * unconditionally eligible on the very first install, even though the registry's own response
     * already said otherwise (raised in review).
     */
    private static String withAdditionalFilenames(String bodyHtmlOrNull, String pathPrefix,
            List<CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile> additional) {
        if (additional.isEmpty()) {
            return bodyHtmlOrNull;
        }
        StringBuilder extra = new StringBuilder();
        for (CodeArtifactPypiExternalConnectionImporter.ExternalIndexFile file : additional) {
            extra.append("<a href=\"").append(pathPrefix).append("/packages/")
                    .append(SidecarUriUtils.encodeSegment(file.filename())).append("\"");
            if (file.requiresPythonOrNull() != null) {
                extra.append(" data-requires-python=\"").append(escapeHtml(file.requiresPythonOrNull()))
                        .append("\"");
            }
            if (file.yanked()) {
                extra.append(" data-yanked=\"\"");
            }
            extra.append(">").append(escapeHtml(file.filename())).append("</a><br>\n");
        }
        if (bodyHtmlOrNull == null) {
            return "<!DOCTYPE html><html><body>\n" + extra + "</body></html>";
        }
        int bodyClose = bodyHtmlOrNull.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (bodyClose < 0) {
            return bodyHtmlOrNull + extra;
        }
        return bodyHtmlOrNull.substring(0, bodyClose) + extra + bodyHtmlOrNull.substring(bodyClose);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * pypi's real download route is a bare filename with no package name or version anywhere in
     * the URL, unlike Maven's GAV path or nuget's content route, so there is nothing in a download
     * request itself to resolve the coordinate {@link #archivedOrDisposedVersionFromFilename}
     * needs. Resolving it against this repository's own status overrides, the same way the upload
     * guard above already does, rather than a separate map recorded only at upload time: raised by
     * Greptile's review, an earlier version of this check used exactly that kind of map, recorded
     * only by a native twine upload, so a version {@code CopyPackageVersions} wrote straight to the
     * destination sidecar (never touching this controller's own upload handler at all) had no
     * recorded identity, and archiving it afterward never blocked the download. Resolving against
     * the override records instead needs no write-time bookkeeping at all: whichever path put the
     * real file there, an override written later by {@code UpdatePackageVersionsStatus}/{@code
     * DisposePackageVersions} is found the same way regardless.
     *
     * <p>A local miss, with no matching Archived/Disposed override, is exactly AWS's own
     * documented trigger for an external connection: a real client requesting a file this
     * emulator's own pypiserver sidecar does not have yet. Never attempted when {@code match} is
     * non-null: that means a status override already exists for the version this filename
     * belongs to, Archived or Disposed, and retention must never resurrect it, the same guard
     * {@link #upload} already enforces on the publish side.
     */
    @GET
    @Path("packages/{filename}")
    @Produces(MediaType.WILDCARD)
    public Response downloadFile(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                  @PathParam("repository") String repository,
                                  @PathParam("filename") String filename) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        // A real (200) proxyGetStreamed response's body is only drained later, when JAX-RS writes
        // the deferred StreamingOutput: this flag lets that case defer releasing the lease (via
        // StreamingResponses.closingWith) until that real drain happens, instead of this method's
        // own return letting a concurrent DeleteRepository stop the pypiserver container
        // mid-download. A 404 from either attempt below leaves this flag unset, so the outer
        // finally releases the lease immediately once this method actually returns: nothing is
        // left streaming, and the lease must stay held across the external-connection import and
        // retry that can follow a first-attempt 404 anyway.
        AtomicBoolean leaseTransferred = new AtomicBoolean();
        try {
            ArchivedOrDisposedMatch match = archivedOrDisposedVersionFromFilename(handle, domain, repository, filename);
            if (match != null) {
                try {
                    service.requireContainerBackedVersionDownloadable(handle.owner(), handle.region(), domain,
                            repository, "pypi", null, match.packageName(), match.version(), handle.repoId());
                } catch (AwsException e) {
                    return Response.status(404).build();
                }
            }
            Response response = proxyGetStreamed(handle.baseUrl(),
                    "/packages/" + SidecarUriUtils.encodeSegment(filename), leaseTransferred,
                    () -> pypiserver.endUse(handle.repoId()));
            if (match == null && response.getStatus() == 404
                    && (externalConnectionImporter.tryImportAsset(handle.region(), domain, handle.owner(),
                            repository, handle.repoId(), filename)
                        || upstreamRepositoryImporter.tryImportAsset(handle.region(), domain, handle.owner(),
                            repository, handle.repoId(), filename))) {
                return proxyGetStreamed(handle.baseUrl(), "/packages/" + SidecarUriUtils.encodeSegment(filename),
                        leaseTransferred, () -> pypiserver.endUse(handle.repoId()));
            }
            return response;
        } finally {
            if (!leaseTransferred.get()) {
                pypiserver.endUse(handle.repoId());
            }
        }
    }

    /**
     * Handles a twine upload: {@code POST} to the repository root with a
     * {@code multipart/form-data} body whose {@code content} part is the package file and whose
     * {@code name}/{@code version} fields identify it. When the exact filename being uploaded is
     * already published, compares its existing bytes to the new upload: byte-identical content
     * succeeds without forwarding anything (idempotent, matching AWS's own documented behavior for
     * a retried publish), while different content is rejected with 409 before ever reaching
     * pypiserver, since pypiserver has no immutability check of its own.
     *
     * <p>The existence check and the forwarded publish run inside a lock keyed by this
     * repository's id: unlike Reposilite ({@code redeployment: false}), pypiserver has no atomic
     * backstop of its own, so without this lock two uploads racing the same new filename could
     * both observe "not published yet" and both succeed, with pypiserver silently keeping whichever
     * write landed last instead of either one being rejected.
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    public Response upload(@Context HttpHeaders headers, @PathParam("domain") String domain,
                            @PathParam("repository") String repository, InputStream body) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        try {
            return uploadWithLease(headers, domain, repository, body, handle);
        } finally {
            pypiserver.endUse(handle.repoId());
        }
    }

    /**
     * Stages the whole raw multipart body to disk before anything else touches it (its own
     * envelope has no exact size semantics of its own, so nothing is checked against the 5 GB
     * asset quota yet): {@link #forwardUpload} can then send this exact file straight to
     * pypiserver with no multipart rebuild at all, since it already re-sends the entire inbound
     * body verbatim, not a reconstructed one. The staging copy itself is still capped, at {@code
     * CodeArtifactService.MAX_MULTIPART_ENVELOPE_BYTES}, well above the real quota to leave room
     * for the envelope's own overhead: leaving this particular copy fully uncapped would let a
     * client force an arbitrarily large file onto disk before the real per-asset check downstream
     * ever runs.
     */
    private Response uploadWithLease(HttpHeaders headers, String domain, String repository, InputStream body,
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
            return uploadStagedBody(domain, repository, contentType, boundary, stagedWholeBody, handle);
        } finally {
            service.deleteStagedAssetBody(stagedWholeBody);
        }
    }

    /**
     * {@code stagedWholeBody}'s own file part is extracted into a second, clean staged file (never
     * the whole multipart envelope nor that one part held in a single array), so everything below
     * this point (the 5 GB size cap, the existing-content comparison, the byte count recorded on
     * a successful publish) runs against real content alone, the same as every other format's
     * own asset.
     */
    private Response uploadStagedBody(String domain, String repository, String contentType, String boundary,
            java.nio.file.Path stagedWholeBody, RepositoryHandle handle) {
        java.nio.file.Path stagedAsset = stagedWholeBody.resolveSibling(stagedWholeBody.getFileName() + ".asset");
        try {
            MultipartFormParser.ExtractedForm form;
            try {
                form = MultipartFormParser.extractFilePart(stagedWholeBody, boundary, stagedAsset);
            } catch (IOException e) {
                return Response.status(400).entity("Could not parse the uploaded multipart body.").build();
            }
            return uploadExtractedForm(domain, repository, contentType, form, stagedWholeBody, stagedAsset, handle);
        } finally {
            service.deleteStagedAssetBody(stagedAsset);
        }
    }

    private Response uploadExtractedForm(String domain, String repository, String contentType,
            MultipartFormParser.ExtractedForm form, java.nio.file.Path stagedWholeBody,
            java.nio.file.Path stagedAsset, RepositoryHandle handle) {
        String packageName = form.fields().get("name");
        String version = form.fields().get("version");
        Optional<MultipartFormParser.ExtractedFile> file = form.file();
        if (packageName == null || packageName.isBlank() || file.isEmpty()) {
            return Response.status(400).entity("Upload must include name and content fields.").build();
        }
        long assetSize;
        try {
            assetSize = Files.size(stagedAsset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Same 5 GB AWS quota PublishPackageVersion enforces: checked against the real asset
        // bytes alone (never the surrounding multipart envelope), once they are known, rather
        // than before anything was read at all the way a non-multipart format's own cap can be.
        if (assetSize > CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES) {
            return Response.status(413).build();
        }
        // Raised by Greptile's review: pypiserver files an upload under the identity its own
        // filename embeds, not under whatever this form's name/version fields happen to say
        // (PypiserverSidecarClient#filenameEmbedsVersion's own javadoc explains why that
        // boundary check, not these fields, is the real source of truth). Everything below this
        // point, the Archived/Disposed guard included, runs against these fields; a mismatched
        // version field would let the guard see an unrelated, harmless coordinate while the real
        // upload still lands under whatever the filename actually names.
        if (version != null && !version.isBlank()
                && !PypiserverSidecarClient.filenameEmbedsVersion(file.get().filename(), packageName, version)) {
            return Response.status(400)
                    .entity("The upload's name and version fields do not match its filename.")
                    .build();
        }

        // A package whose origin configuration sets publish:BLOCK refuses every twine upload, the
        // same way PublishPackageVersion refuses it for the generic format, ahead of the
        // byte-identical shortcut below: a retry of bytes already stored is still a publish.
        // pypiserver files the upload under the name its own filename parser derives and never
        // reads the form's name field, so that derived name is the one checked, version field or
        // not (Greptile and pgermosen's review). A filename it cannot parse is one pypiserver
        // rejects anyway; the form's name stands in for it then.
        String publishCheckedName = PypiserverSidecarClient.guessPackageName(file.get().filename())
                .orElse(packageName);
        try {
            service.requireContainerBackedPackagePublishAllowed(handle.region(), domain, handle.owner(), repository,
                    "pypi", null, publishCheckedName);
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }

        Object lock = uploadLocks.computeIfAbsent(handle.repoId(), k -> new Object());
        synchronized (lock) {
            // Raised by hectorvent's review: a real twine upload to a coordinate
            // updatePackageVersionsStatus/disposePackageVersions left Archived or Disposed must be
            // refused outright, the same way PublishPackageVersion already refuses it for the
            // generic format, not accepted and then have forgetContainerBackedStatusOverride below
            // silently un-block it afterward. Checked ahead of the byte-identical shortcut right
            // below too, not just the real write: archiving leaves the stored bytes themselves
            // unchanged, so a byte-identical re-upload of an archived version would otherwise
            // short-circuit to 200 before ever reaching this guard (Greptile review). A
            // missing/blank version is the same case the override-clearing call below already
            // skips: no coordinate to block either way.
            String beforeRevision = null;
            // Raised by hectorvent's follow-up on this same PR: a missing/blank version field
            // must not let this guard be skipped just because there is no explicit coordinate to
            // check directly. pypiserver trusts the filename over the form fields regardless (see
            // PypiserverSidecarClient#filenameEmbedsVersion's own javadoc), so an Archived/Disposed
            // version this upload's own filename names is found by checking it against every
            // version of this package that currently carries a status override, the same guard
            // below runs against once one is found. A real version field always wins when present:
            // this fallback only matters for a client that omits it.
            String guardPackageName = packageName;
            String guardVersion = version;
            if (guardVersion == null || guardVersion.isBlank()) {
                ArchivedOrDisposedMatch match = archivedOrDisposedVersionFromFilename(handle, domain, repository,
                        file.get().filename());
                guardPackageName = match == null ? null : match.packageName();
                guardVersion = match == null ? null : match.version();
            }
            if (guardVersion != null) {
                try {
                    beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(handle.region(),
                            domain, handle.owner(), repository, "pypi", null, guardPackageName, guardVersion);
                } catch (AwsException e) {
                    return Response.status(409).entity(e.getMessage()).build();
                }
            }

            ExistingFileComparison existing = compareExistingFile(handle.baseUrl(), packageName,
                    file.get().filename(), stagedAsset);
            if (existing == ExistingFileComparison.MATCHES) {
                return Response.status(200).build();
            }
            if (existing == ExistingFileComparison.DIFFERS) {
                return Response.status(409)
                        .entity("File already exists. See "
                                + "https://docs.aws.amazon.com/codeartifact/latest/ug/PyPI-format.html")
                        .build();
            }

            // Snapshotted before the real write, not after: see
            // CodeArtifactService#currentContainerBackedPackageFence's own javadoc for why the
            // order matters.
            long fenceSnapshot = service.currentContainerBackedPackageFence(handle.region(), domain, handle.owner(),
                    repository, "pypi", null, packageName);
            // Narrower than fenceSnapshot above, for the readme capture below specifically: raised
            // by Greptile's review, a DeletePackageVersions for this exact version landing while
            // this upload is in flight must invalidate just this version's own readme capture,
            // which the package-wide fenceSnapshot alone would not catch.
            long readmeFenceSnapshot = version == null || version.isBlank() ? 0L
                    : service.currentContainerBackedVersionFence(handle.region(), domain, handle.owner(),
                            repository, "pypi", null, packageName, version);
            // The entire original raw multipart body is re-sent verbatim, boundary and all,
            // exactly as the byte[]-based version of this call always has: pypiserver parses its
            // own multipart form the same way twine built it, so there was never a need to
            // rebuild one here, only to stream the one already staged instead of a resident array.
            Response response = forwardUpload(handle.baseUrl(), contentType, stagedWholeBody);
            boolean uploadSucceeded = response.getStatus() == 200 || response.getStatus() == 201;
            if (uploadSucceeded) {
                service.completeContainerBackedPublishIfCurrentRepository(handle.region(), domain, handle.owner(),
                        repository, handle.repoId(), "pypi", packageName, version, assetSize,
                        fenceSnapshot, beforeRevision, form.fields().get("description"), readmeFenceSnapshot);
            }
            return response;
        }
    }

    /**
     * {@code empty()} when {@code filename} isn't listed in the package's simple index yet, its
     * actual bytes otherwise. pypiserver normalizes a package name for its own simple-index path
     * the same way real PyPI does (PEP 503: lowercase, runs of {@code -_.} collapsed to one
     * {@code -}); querying with the raw, as-uploaded name for a package whose normalized form
     * differs would 404 even though the package exists.
     *
     * <p>A connectivity failure here throws rather than returning {@code empty()}: treating "the
     * check couldn't complete" the same as "nothing published yet" would let an upload with
     * different content through to pypiserver (which has no conflict check of its own) during
     * exactly the moment this proxy's own protection is unavailable, the opposite of fail-safe.
     *
     * <p>{@code packageName} and {@code filename} are percent-encoded via {@link SidecarUriUtils#
     * encodeSegment} before being placed in either request path: without that, a {@code /} in
     * either one would be indistinguishable from a real path separator, and a {@code #} or
     * {@code ?} would be reinterpreted by {@link URI#create} as a fragment or query string,
     * silently fetching a different path than the one actually requested.
     */
    private enum ExistingFileComparison { NOT_LISTED, MATCHES, DIFFERS }

    /**
     * {@code existingFileContent}'s own streamed sibling: compares pypiserver's own stored file
     * against {@code localAsset} chunk by chunk from each side, rather than buffering the remote
     * file into a {@code byte[]} first (capped at ~2 GiB) just to compare it against another
     * array. Uses {@code readNBytes} so a short individual {@code read()} on either side (always
     * possible, never guaranteed to fill the buffer) can't be mistaken for the two disagreeing,
     * the same technique {@code ReposiliteSidecarClient}'s own streamed idempotent-match check
     * uses.
     */
    private ExistingFileComparison compareExistingFile(String baseUrl, String packageName, String filename,
            java.nio.file.Path localAsset) {
        String normalized = PypiserverSidecarClient.normalizePackageName(packageName);
        URI indexUri = SidecarUriUtils.combine(URI.create(baseUrl),
                SIMPLE_PATH + SidecarUriUtils.encodeSegment(normalized) + "/");
        HttpRequest indexRequest = HttpRequest.newBuilder(indexUri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> indexResponse;
        try {
            indexResponse = httpClient.send(indexRequest, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not check pypi package " + packageName + " for an existing " + filename
                            + " before publishing", e);
        }
        if (indexResponse.statusCode() == 404) {
            return ExistingFileComparison.NOT_LISTED;
        }
        if (indexResponse.statusCode() != 200) {
            throw new IllegalStateException(
                    "Could not check pypi package " + packageName + " for an existing " + filename
                            + ": upstream returned " + indexResponse.statusCode());
        }
        if (!indexListsFilename(indexResponse.body(), filename)) {
            return ExistingFileComparison.NOT_LISTED;
        }

        URI fileUri = SidecarUriUtils.combine(URI.create(baseUrl), "/packages/"
                + SidecarUriUtils.encodeSegment(filename));
        HttpRequest fileRequest = HttpRequest.newBuilder(fileUri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<InputStream> fileResponse;
        try {
            fileResponse = httpClient.send(fileRequest, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not fetch the existing pypi file " + filename + " to check for a content match", e);
        }
        if (fileResponse.statusCode() != 200) {
            try {
                fileResponse.body().close();
            } catch (IOException ignored) {
                // Closing an unread stream best-effort only; the exception thrown right below is
                // the one that actually matters here.
            }
            throw new IllegalStateException(
                    "Could not fetch the existing pypi file " + filename + " to check for a content match: "
                            + "upstream returned " + fileResponse.statusCode());
        }
        try (InputStream remote = fileResponse.body(); InputStream local = Files.newInputStream(localAsset)) {
            return StreamEquality.contentsMatch(remote, local) ? ExistingFileComparison.MATCHES
                    : ExistingFileComparison.DIFFERS;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not compare the existing pypi file " + filename + " against the uploaded content", e);
        }
    }

    private record ArchivedOrDisposedMatch(String packageName, String version) {
    }

    /**
     * The exact (package, version) pair a status override currently marks Archived or Disposed
     * that {@code filename} actually names, found by checking it against every override this
     * repository has at all, never trusting the form's own {@code name} field for the lookup
     * (Greptile's review: the form's {@code name} field can name an unrelated, un-overridden
     * package while the uploaded filename itself matches a disposed one; searching by that field
     * alone would miss it, since pypiserver indexes the real upload by the filename, never the
     * form). Returns the override's own recorded package name, not the form's, so the caller's own
     * guard call checks the coordinate the filename actually names. {@code null} when nothing
     * matches, including the overwhelming common case, a repository with no overrides to check
     * against at all.
     */
    private ArchivedOrDisposedMatch archivedOrDisposedVersionFromFilename(RepositoryHandle handle, String domain,
                                                                           String repository, String filename) {
        Map<String, Map<String, String>> overriddenStatusesByPackage =
                service.containerBackedOverriddenVersionStatusesByRepository(handle.region(), domain, handle.owner(),
                        repository, "pypi");
        for (Map.Entry<String, Map<String, String>> packageEntry : overriddenStatusesByPackage.entrySet()) {
            String overriddenPackageName = packageEntry.getKey();
            for (Map.Entry<String, String> versionEntry : packageEntry.getValue().entrySet()) {
                String status = versionEntry.getValue();
                if (("Archived".equals(status) || "Disposed".equals(status))
                        && PypiserverSidecarClient.filenameEmbedsVersion(filename, overriddenPackageName,
                                versionEntry.getKey())) {
                    return new ArchivedOrDisposedMatch(overriddenPackageName, versionEntry.getKey());
                }
            }
        }
        return null;
    }

    /**
     * An exact entry match, not a substring search: a shorter real filename that happens to be a
     * substring of a different, longer real filename (or of surrounding markup) must not pass
     * this check.
     */
    private static boolean indexListsFilename(String indexBody, String filename) {
        return hrefFilenames(indexBody).contains(filename);
    }

    /**
     * Every real filename {@code html}'s own {@code href="..."} attributes name, with any
     * trailing {@code #...} hash fragment stripped: shared by {@link #indexListsFilename}'s own
     * single-filename check and {@link #packageIndex}'s own external-connection merge, which
     * needs the whole set to know what pypiserver's local listing already has.
     */
    private static Set<String> hrefFilenames(String html) {
        Set<String> filenames = new LinkedHashSet<>();
        for (MatchResult match : SIMPLE_INDEX_HREF.matcher(html).results().toList()) {
            String href = match.group(1);
            String name = href.substring(href.lastIndexOf('/') + 1);
            int fragment = name.indexOf('#');
            filenames.add(fragment < 0 ? name : name.substring(0, fragment));
        }
        return filenames;
    }

    private Response forwardUpload(String baseUrl, String contentType, java.nio.file.Path stagedWholeBody) {
        long stagedSize;
        try {
            stagedSize = Files.size(stagedWholeBody);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(SidecarUriUtils.transferTimeout(stagedSize))
                .header("Content-Type", contentType);
        try {
            requestBuilder.POST(BodyPublishers.ofFile(stagedWholeBody));
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypi sidecar to publish this file", e);
        }
        return Response.status(response.statusCode()).build();
    }

    /**
     * {@code encodedPath} must already be fully percent-encoded, built from {@link
     * SidecarUriUtils#encodeSegment}-encoded, caller-supplied segments: see {@link
     * SidecarUriUtils#combine} for why a raw {@code URI.create(baseUrl + path)} concatenation
     * isn't safe here.
     */
    private Response proxyGet(String baseUrl, String encodedPath) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypi sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            return Response.status(404).build();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return Response.status(response.statusCode()).entity(response.body()).type(contentType).build();
    }

    /**
     * {@link #proxyGet}'s streamed sibling, for {@link #downloadFile}'s real asset downloads: that
     * method's own {@code byte[]} is fine for {@link #packageIndex}'s small HTML listing, but a
     * downloaded package file can be gigabytes.
     *
     * <p>On a real 200, {@code leaseTransferred} is set so the caller's lease release ({@code
     * afterClose}) runs only once the entity stream this method returns is actually closed (via
     * {@link StreamingResponses#closingWith}), keeping the sidecar container leased for as long
     * as the real transfer takes rather than just until this method returns. A 404 leaves it
     * unset: nothing is left streaming, so the caller releases it synchronously instead.
     *
     * <p>Uses the worst-case download timeout, not {@link #proxyGet}'s flat 10 seconds: the real
     * asset file this fetches can be gigabytes, and its real size isn't known until the response
     * itself arrives.
     */
    private Response proxyGetStreamed(String baseUrl, String encodedPath, AtomicBoolean leaseTransferred,
            Runnable afterClose) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), encodedPath))
                .timeout(SidecarUriUtils.maxTransferTimeout()).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("Could not reach the pypi sidecar at " + encodedPath, e);
        }
        if (response.statusCode() == 404) {
            StreamingResponses.closeQuietly(response.body());
            return Response.status(404).build();
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
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
            repoId = service.resolveContainerIdAndBeginUse("pypi", scope.region(), domain, scope.owner(),
                    repository, pypiserver);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        try {
            String baseUrl = pypiserver.ensureReady(repoId, null);
            return new RepositoryHandle(repoId, baseUrl, "/codeartifact/pypi/" + domain + "/" + repository,
                    scope.region(), scope.owner());
        } catch (RuntimeException e) {
            pypiserver.endUse(repoId);
            throw e;
        }
    }
}
