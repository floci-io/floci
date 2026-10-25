package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
import io.github.hectorvent.floci.services.codeartifact.ReposiliteSidecarClient.FetchedArtifact;
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

import java.util.Arrays;
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

    @Inject
    public CodeArtifactMavenController(CodeArtifactService service, ReposiliteSidecarClient reposilite) {
        this.service = service;
        this.reposilite = reposilite;
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response deploy(@Context HttpHeaders headers, @PathParam("domain") String domain,
                            @PathParam("repository") String repository, @PathParam("gav") String gav,
                            byte[] body) {
        byte[] content = body != null ? body : new byte[0];
        // Same 5 GB AWS quota PublishPackageVersion enforces, and just as nominal: RESTEasy
        // Reactive has already buffered the full body into this byte[] before the method runs, so
        // this rejects anything that made it this far rather than bounding memory use upfront.
        if (content.length > CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES) {
            return Response.status(413).build();
        }
        MavenRequestScope scope = requireScope(headers, domain, repository);
        Optional<String[]> coordinate = parseNamespaceAndPackageName(gav);
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
        }
        // Snapshotted before the real write, not after: see
        // CodeArtifactService#currentContainerBackedPackageFence's own javadoc for why the order
        // matters.
        Optional<Long> fenceSnapshot = coordinate.map(c -> service.currentContainerBackedPackageFence(
                scope.region(), domain, scope.owner(), repository, "maven", c[0], c[1]));
        ReposiliteSidecarClient.DeployOutcome outcome = reposilite.deployArtifact(scope.repoId(), gav, content);
        if (outcome.wroteNewContent()) {
            coordinate.ifPresent(c -> service.recordContainerBackedAssetBytes(scope.region(), domain, scope.owner(),
                    repository, "maven", c[0], c[1], content.length, fenceSnapshot.get()));
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
            coordinate.filter(c -> c[2] != null).ifPresent(c -> service.forgetContainerBackedStatusOverrideIfUnchanged(
                    scope.region(), domain, scope.owner(), repository, "maven", c[0], c[1], c[2],
                    beforeRevisionFinal));
        }
        return Response.status(outcome.status()).build();
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
     * A versioned snapshot's own {@code .../<version>/maven-metadata.xml} is the one real path
     * this still gets slightly wrong for the namespace/packageName pair, reading the version
     * string itself as the package name instead of skipping over it; accepted rather than chased
     * further, since that pair is only ever used for a byte count summed at the domain level
     * (never read back by package), and this one rare case still adds its real byte count to the
     * right total under a harmless, if misnamed, bucket nothing else will ever look up by that
     * name. The version element has no such approximation to make: it is {@code null} exactly
     * when there genuinely is no version in the path.
     */
    private static Optional<String[]> parseNamespaceAndPackageName(String gav) {
        String[] segments = gav.split("/");
        boolean metadataFile = isMavenMetadataFile(segments[segments.length - 1]);
        int packageNameIndex = metadataFile ? segments.length - 2 : segments.length - 3;
        if (packageNameIndex < 1) {
            return Optional.empty();
        }
        String packageName = segments[packageNameIndex];
        String namespace = String.join(".", Arrays.asList(segments).subList(0, packageNameIndex));
        String version = metadataFile ? null : segments[packageNameIndex + 1];
        return Optional.of(new String[] {namespace, packageName, version});
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
        return reposilite.fetchArtifact(scope.repoId(), gav)
                .map(this::toResponse)
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @HEAD
    public Response head(@Context HttpHeaders headers, @PathParam("domain") String domain,
                          @PathParam("repository") String repository, @PathParam("gav") String gav) {
        MavenRequestScope scope = requireScope(headers, domain, repository);
        if (!isDownloadable(scope, domain, repository, gav)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return reposilite.artifactExists(scope.repoId(), gav)
                ? Response.ok().build()
                : Response.status(Response.Status.NOT_FOUND).build();
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

    private Response toResponse(FetchedArtifact artifact) {
        return Response.ok(artifact.content(), artifact.contentType()).build();
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
