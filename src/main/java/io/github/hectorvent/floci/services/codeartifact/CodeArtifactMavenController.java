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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

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
        String repoId = requireRepositoryId(headers, domain, repository);
        int status = reposilite.deployArtifact(repoId, gav, content);
        return Response.status(status).build();
    }

    @GET
    @Produces(MediaType.WILDCARD)
    public Response fetch(@Context HttpHeaders headers, @PathParam("domain") String domain,
                           @PathParam("repository") String repository, @PathParam("gav") String gav) {
        String repoId = requireRepositoryId(headers, domain, repository);
        return reposilite.fetchArtifact(repoId, gav)
                .map(this::toResponse)
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @HEAD
    public Response head(@Context HttpHeaders headers, @PathParam("domain") String domain,
                          @PathParam("repository") String repository, @PathParam("gav") String gav) {
        String repoId = requireRepositoryId(headers, domain, repository);
        return reposilite.artifactExists(repoId, gav)
                ? Response.ok().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    private Response toResponse(FetchedArtifact artifact) {
        return Response.ok(artifact.content(), artifact.contentType()).build();
    }

    private String requireRepositoryId(HttpHeaders headers, String domain, String repository) {
        String token = extractToken(headers);
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException("Basic realm=\"floci-codeartifact\""));
        String repoId;
        try {
            repoId = service.ensureMavenRepositoryId(scope.region(), domain, scope.owner(), repository);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        reposilite.ensureRepository(repoId);
        return repoId;
    }

    /**
     * {@code null} when neither scheme is present, malformed, or the Basic credentials have no
     * password field; {@link CodeArtifactService#resolveAuthorizationToken} treats a null token
     * the same as any other invalid one.
     */
    private static String extractToken(HttpHeaders headers) {
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            return null;
        }
        if (authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        if (authorization.startsWith(BASIC_PREFIX)) {
            return passwordFromBasicCredentials(authorization.substring(BASIC_PREFIX.length()));
        }
        return null;
    }

    private static String passwordFromBasicCredentials(String base64Credentials) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            return separator >= 0 ? decoded.substring(separator + 1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
