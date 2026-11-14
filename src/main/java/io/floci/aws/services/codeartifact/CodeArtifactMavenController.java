package io.floci.aws.services.codeartifact;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.StreamingResponses;
import io.floci.aws.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.floci.aws.services.codeartifact.ReposiliteSidecarClient.StreamedArtifact;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Serves the real Maven repository wire protocol behind the URL {@code GetRepositoryEndpoint}
 * returns for the {@code maven} format: plain GET/PUT/HEAD over a GAV path, not a CodeArtifact
 * API action. Backed by the shared Reposilite sidecar; a CodeArtifact repository maps to its own
 * Reposilite repository, provisioned on first use.
 *
 * <p>Requires the bearer token real CodeArtifact requires here, obtained from
 * {@code GetAuthorizationToken} and scoped to the domain being accessed. Accepted either as
 * {@code Authorization: Bearer <token>} or as HTTP Basic with the token as the password (any
 * username): AWS's own documented {@code settings.xml} configuration for {@code mvn}
 * (<a href="https://docs.aws.amazon.com/codeartifact/latest/ug/maven-mvn.html">Use CodeArtifact
 * with mvn</a>) sets a {@code <server><username>aws</username><password>...}, which Maven's HTTP
 * wagon sends as Basic, not Bearer.
 *
 * <p>A Maven request carries no SigV4 {@code Authorization} header, so there is no account or
 * Region to resolve from it the way the CodeArtifact JSON API does; the token itself supplies
 * both, since it was issued under a specific account and Region.
 */
@Path("/codeartifact/maven/{domain}/{repository}/{gav:.+}")
public class CodeArtifactMavenController {

    private final CodeArtifactService service;
    private final ReposiliteSidecarClient reposilite;
    private final CodeArtifactMavenExternalConnectionImporter externalConnectionImporter;
    private final CodeArtifactMavenUpstreamRepositoryImporter upstreamRepositoryImporter;

    @Inject
    public CodeArtifactMavenController(CodeArtifactService service, ReposiliteSidecarClient reposilite,
                                        CodeArtifactMavenExternalConnectionImporter externalConnectionImporter,
                                        CodeArtifactMavenUpstreamRepositoryImporter upstreamRepositoryImporter) {
        this.service = service;
        this.reposilite = reposilite;
        this.externalConnectionImporter = externalConnectionImporter;
        this.upstreamRepositoryImporter = upstreamRepositoryImporter;
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response deploy(@Context HttpHeaders headers, @PathParam("domain") String domain,
                            @PathParam("repository") String repository, @PathParam("gav") String gav,
                            InputStream body) {
        MavenRequestScope scope = requireScope(headers, domain, repository);
        Optional<String[]> coordinate = parseNamespaceAndPackageName(gav);
        // A package whose origin configuration sets publish:BLOCK refuses every mvn deploy, the
        // same way PublishPackageVersion refuses it for the generic format. The restriction is
        // per package, so a bare maven-metadata.xml PUT (no version) is refused too, and it runs
        // ahead of every check below, Reposilite's own byte-identical republish included.
        try {
            for (String[] checked : publishCheckedPackages(gav)) {
                service.requireContainerBackedPackagePublishAllowed(scope.region(), domain, scope.owner(),
                        repository, "maven", checked[0], checked[1]);
            }
        } catch (AwsException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
        // Raised by hectorvent's review: a real mvn deploy to a coordinate
        // updatePackageVersionsStatus/disposePackageVersions left Archived or Disposed must be
        // refused outright, the same way PublishPackageVersion already refuses it for the generic
        // format, not accepted and then have forgetContainerBackedStatusOverride below silently
        // un-block it afterward. Checked before the real write, not after; the version element is
        // null for a bare maven-metadata.xml PUT (see this coordinate's own javadoc), which never
        // represents a specific version to block either way. The returned revision (or null) is
        // held onto below: raised by Greptile's review, a concurrent status change landing between
        // this check and the clear below must not be erased by it (see
        // CodeArtifactService#forgetContainerBackedStatusOverrideIfUnchanged's own javadoc).
        String beforeRevision = null;
        if (coordinate.filter(c -> c[2] != null).isPresent()) {
            String[] c = coordinate.get();
            try {
                beforeRevision = service.requireContainerBackedVersionNotArchivedOrDisposed(scope.region(), domain,
                        scope.owner(), repository, "maven", c[0], c[1], c[2]);
            } catch (AwsException e) {
                return Response.status(409).entity(e.getMessage()).build();
            }
            // AWS's own documented behavior: "CodeArtifact will not permit publishing a package
            // version that is already present in the external repository." Checked only for a
            // well-formed, versioned GAV, same scope as the Archived/Disposed guard above; a bare
            // maven-metadata.xml PUT is never a specific version to check either way.
            if (externalConnectionImporter.existsInExternalConnection(scope.region(), domain, scope.owner(),
                    repository, gav)) {
                return Response.status(409)
                        .entity("A version of this package is already present in the repository's external "
                                + "connection and cannot be republished directly.")
                        .build();
            }
        }
        // Snapshotted before the real write, not after: see
        // CodeArtifactService#currentContainerBackedPackageFence's own javadoc for why the order
        // matters.
        Optional<Long> fenceSnapshot = coordinate.map(c -> service.currentContainerBackedPackageFence(
                scope.region(), domain, scope.owner(), repository, "maven", c[0], c[1]));

        java.nio.file.Path staged;
        long contentLength;
        try {
            AssetDigestingInputStream digesting = new AssetDigestingInputStream(body,
                    CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES);
            staged = service.stageAssetBody(digesting);
            contentLength = digesting.size();
        } catch (AssetDigestingInputStream.AssetTooLargeException e) {
            // Same 5 GB AWS quota PublishPackageVersion enforces: raised while the upload is still
            // being staged to disk, not after it has already been buffered whole, so this now
            // bounds real memory/disk use rather than only rejecting a body already paid for.
            return Response.status(413).build();
        }
        try {
            ReposiliteSidecarClient.DeployOutcome outcome = reposilite.deployArtifact(scope.repoId(), gav, staged);
            if (outcome.wroteNewContent()) {
                coordinate.ifPresent(c -> service.recordContainerBackedAssetBytes(scope.region(), domain,
                        scope.owner(), repository, "maven", c[0], c[1], contentLength, fenceSnapshot.get()));
                // Found by Qodo review: a real mvn deploy to a coordinate updatePackageVersionsStatus/
                // disposePackageVersions previously archived or disposed would still have its fresh
                // content blocked by that stale override, since nothing before this cleared it for a
                // native publish the way copyPackageVersions now does for its own overwrite path. The
                // version element is null for a bare maven-metadata.xml PUT (see this coordinate's own
                // javadoc), which never represents a specific version to clear an override for anyway.
                // By this point the guard above has already refused an Archived/Disposed coordinate,
                // so the only override left to clear here is Unlisted (or none), and only if it is
                // still exactly what the guard above saw.
                String beforeRevisionFinal = beforeRevision;
                coordinate.filter(c -> c[2] != null).ifPresent(
                        c -> service.forgetContainerBackedStatusOverrideIfUnchanged(scope.region(), domain,
                                scope.owner(), repository, "maven", c[0], c[1], c[2], beforeRevisionFinal));
            }
            return Response.status(outcome.status()).build();
        } finally {
            service.deleteStagedAssetBody(staged);
        }
    }

    /**
     * Splits a GAV path ({@code <groupId-as-slashes>/<artifactId>/<version>/<filename>}, the real
     * Maven repository layout) into CodeArtifact's Maven namespace (dot-joined groupId), package
     * name (artifactId), and version: the inverse of what {@code ReposiliteSidecarClient}'s own
     * namespace/packageName-aware methods already build. The namespace/packageName pair is used
     * for byte accounting, a side channel that must never fail a publish that would otherwise
     * succeed, so a path with too few segments to be a well-formed GAV is reported as unknown
     * rather than thrown.
     *
     * <p>{@code maven-metadata.xml} (and its {@code .sha1}/{@code .md5}/{@code .sha256} checksum
     * siblings) needs its own rule, not the ordinary one below: every real {@code mvn deploy} also
     * PUTs one straight to the group/artifact directory, with no version segment at all, e.g.
     * {@code com/example/myartifact/maven-metadata.xml}. That path has the same segment count as
     * the shortest real versioned GAV ({@code group/artifact/version/filename}), so segment count
     * alone can't tell the two apart; the filename itself can, and already does, via
     * {@link #isMavenMetadataFile}. Once that is known, the artifactId is always the segment
     * directly before it, no version segment in between, so this takes one fewer trailing segment
     * than the ordinary rule does, and the returned version is {@code null} rather than guessed.
     * The one exception is a snapshot's own version-level {@code
     * .../<artifactId>/<version>-SNAPSHOT/maven-metadata.xml}, the only version directory a
     * real {@code mvn deploy} writes metadata into: the {@code -SNAPSHOT} segment is the version,
     * not the artifactId, so it resolves exactly like a versioned GAV. Raised by Greptile and
     * pgermosen's review: the publish:BLOCK check reads this pair back by package, so reading
     * the version as the package name would let a blocked snapshot's metadata through.
     */
    static Optional<String[]> parseNamespaceAndPackageName(String gav) {
        String[] segments = gav.split("/");
        boolean metadataFile = isMavenMetadataFile(segments[segments.length - 1]);
        boolean versionLevelMetadata = metadataFile && segments.length >= 4
                && segments[segments.length - 2].endsWith("-SNAPSHOT");
        boolean bareMetadata = metadataFile && !versionLevelMetadata;
        int packageNameIndex = bareMetadata ? segments.length - 2 : segments.length - 3;
        if (packageNameIndex < 1) {
            return Optional.empty();
        }
        String packageName = segments[packageNameIndex];
        String namespace = String.join(".", Arrays.asList(segments).subList(0, packageNameIndex));
        String version = bareMetadata ? null : segments[packageNameIndex + 1];
        return Optional.of(new String[] {namespace, packageName, version});
    }

    /**
     * The (namespace, packageName) pairs the publish:BLOCK check runs against for {@code gav}:
     * {@link #parseNamespaceAndPackageName}'s own pair, plus, for a {@code maven-metadata.xml}
     * whose parent segment ends in {@code -SNAPSHOT}, the artifact-level reading as well. The
     * layout alone cannot tell {@code com/example/widget/1.0-SNAPSHOT/maven-metadata.xml}, a
     * snapshot's version metadata, from {@code com/example/widget-SNAPSHOT/maven-metadata.xml},
     * the bare metadata of an artifact named {@code widget-SNAPSHOT}, so a block on either reading
     * refuses it (Greptile and pgermosen's review).
     */
    static List<String[]> publishCheckedPackages(String gav) {
        Optional<String[]> coordinate = parseNamespaceAndPackageName(gav);
        if (coordinate.isEmpty()) {
            return List.of();
        }
        List<String[]> packages = new ArrayList<>();
        packages.add(new String[] {coordinate.get()[0], coordinate.get()[1]});
        String[] segments = gav.split("/");
        int artifactIndex = segments.length - 2;
        if (isMavenMetadataFile(segments[segments.length - 1]) && artifactIndex >= 2
                && segments[artifactIndex].endsWith("-SNAPSHOT")) {
            packages.add(new String[] {String.join(".", Arrays.asList(segments).subList(0, artifactIndex)),
                    segments[artifactIndex]});
        }
        return packages;
    }

    private static boolean isMavenMetadataFile(String filename) {
        return filename.equals("maven-metadata.xml") || filename.startsWith("maven-metadata.xml.");
    }

    @GET
    @Produces(MediaType.WILDCARD)
    public Response fetch(@Context HttpHeaders headers, @PathParam("domain") String domain,
                           @PathParam("repository") String repository, @PathParam("gav") String gav) {
        MavenRequestScope scope = requireScope(headers, domain, repository);
        if (!isDownloadable(scope, domain, repository, gav)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Optional<StreamedArtifact> artifact = reposilite.fetchArtifactStreamed(scope.repoId(), gav);
        // A repository has either an external connection or upstreams, never both: at most one
        // of these two calls ever does real work, and the other's own no-op is a single cheap
        // in-memory check (see CodeArtifactMavenUpstreamRepositoryImporter's own javadoc).
        if ((artifact.isEmpty() || isBareMavenMetadataFile(gav))
                && (externalConnectionImporter.tryImport(scope.region(), domain, scope.owner(), repository,
                        scope.repoId(), gav)
                    || upstreamRepositoryImporter.tryImport(scope.region(), domain, scope.owner(), repository,
                        scope.repoId(), gav))) {
            artifact.ifPresent(a -> StreamingResponses.closeQuietly(a.content()));
            artifact = reposilite.fetchArtifactStreamed(scope.repoId(), gav);
        }
        return artifact.map(this::toResponse).orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @HEAD
    public Response head(@Context HttpHeaders headers, @PathParam("domain") String domain,
                          @PathParam("repository") String repository, @PathParam("gav") String gav) {
        MavenRequestScope scope = requireScope(headers, domain, repository);
        if (!isDownloadable(scope, domain, repository, gav)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // A dependency resolver can probe with HEAD before ever issuing the GET that would
        // otherwise trigger retention below, so a miss here must attempt the same import a GET
        // miss would, or a HEAD-first client would see a false "not found" before the real GET
        // ever gets the chance to retain it.
        boolean exists = reposilite.artifactExists(scope.repoId(), gav);
        if ((!exists || isBareMavenMetadataFile(gav))
                && (externalConnectionImporter.tryImport(scope.region(), domain, scope.owner(), repository,
                        scope.repoId(), gav)
                    || upstreamRepositoryImporter.tryImport(scope.region(), domain, scope.owner(), repository,
                        scope.repoId(), gav))) {
            exists = reposilite.artifactExists(scope.repoId(), gav);
        }
        return exists ? Response.ok().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    /**
     * {@code true} only for the exact, bare {@code maven-metadata.xml} path, never a checksum
     * sibling: unlike a fixed version's own assets, which never change once retained, a package's
     * available-version list can keep growing on the real public repository after this
     * repository's own first merge of it, so a request for this exact file always gets a fresh
     * import attempt even when a local copy already exists, not just on the first ever miss.
     * Raised by Greptile's review: without this, {@link #fetch}/{@link #head} only ever imported
     * metadata once, the first time this exact GAV was requested, and every later request for the
     * same package's version list silently kept serving that same first snapshot forever, no
     * matter how many releases the public repository published afterward.
     */
    private static boolean isBareMavenMetadataFile(String gav) {
        return gav.endsWith("/maven-metadata.xml");
    }

    /**
     * {@code true} unless {@code gav} names an exact version this service knows is {@code
     * Archived}: {@link #parseNamespaceAndPackageName}'s own version element is {@code null} for a
     * bare {@code maven-metadata.xml} path (no specific version to block), which this treats the
     * same as "not a well-formed, versioned GAV at all": both fall through to Reposilite
     * unchecked, exactly as {@code deploy} above already does for the identical case.
     */
    private boolean isDownloadable(MavenRequestScope scope, String domain, String repository, String gav) {
        return parseNamespaceAndPackageName(gav).filter(c -> c[2] != null).map(c -> {
            try {
                service.requireContainerBackedVersionDownloadable(scope.owner(), scope.region(), domain, repository,
                        "maven", c[0], c[1], c[2], scope.repoId());
                return true;
            } catch (AwsException e) {
                return false;
            }
        }).orElse(true);
    }

    private Response toResponse(StreamedArtifact artifact) {
        Response.ResponseBuilder builder = Response.ok().type(artifact.contentType());
        if (artifact.contentLength() >= 0) {
            builder.header("Content-Length", artifact.contentLength());
        }
        return StreamingResponses.streamingResponse(artifact.content(), stream -> builder.entity(stream).build());
    }

    private record MavenRequestScope(String repoId, String region, String owner) {}

    private MavenRequestScope requireScope(HttpHeaders headers, String domain, String repository) {
        String token = CodeArtifactTokenExtractor.extractToken(headers);
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException("Basic realm=\"floci-codeartifact\""));
        String repoId;
        try {
            repoId = service.ensureFormatContainerId("maven", scope.region(), domain, scope.owner(), repository);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        reposilite.ensureRepository(repoId);
        return new MavenRequestScope(repoId, scope.region(), scope.owner());
    }
}
