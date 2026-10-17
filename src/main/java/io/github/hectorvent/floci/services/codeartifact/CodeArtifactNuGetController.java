package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.MultipartFormParser;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

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
    private final HttpClient httpClient;

    @Inject
    public CodeArtifactNuGetController(CodeArtifactService service, BagetterSidecarClient bagetter) {
        this(service, bagetter, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CodeArtifactNuGetController(CodeArtifactService service, BagetterSidecarClient bagetter, HttpClient httpClient) {
        this.service = service;
        this.bagetter = bagetter;
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
        String baseUrl = requireRepository(headers, domain, repository).baseUrl();
        return proxyGet(baseUrl, "/v3/package/" + SidecarUriUtils.encodeSegment(id) + "/index.json",
                MediaType.APPLICATION_JSON);
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
        String baseUrl = requireRepository(headers, domain, repository).baseUrl();
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
            return proxyGet(baseUrl, "/v3/package/" + lowerId + "/" + encodedVersion + "/"
                    + lowerId + "." + SidecarUriUtils.encodeSegment(version.toLowerCase(Locale.ROOT)) + ".nupkg",
                    MediaType.APPLICATION_OCTET_STREAM);
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
                          @PathParam("repository") String repository, byte[] body) {
        byte[] content = body != null ? body : new byte[0];
        // Same 5 GB AWS quota PublishPackageVersion enforces, and just as nominal: RESTEasy
        // Reactive has already buffered the full body into this byte[] before this method runs,
        // so this rejects anything that made it this far rather than bounding memory use upfront.
        if (content.length > CodeArtifactService.MAX_ASSET_FILE_SIZE_BYTES) {
            return Response.status(413).build();
        }
        RepositoryHandle handle = requireRepository(headers, domain, repository);

        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String boundary = MultipartFormParser.extractBoundary(contentType).orElse(null);
        if (boundary == null) {
            return Response.status(400).entity("Could not determine multipart boundary from Content-Type.").build();
        }
        MultipartFormParser.ParsedForm form = MultipartFormParser.parse(content, boundary);
        Optional<MultipartFormParser.FilePart> file = form.file();
        if (file.isEmpty()) {
            return Response.status(400).entity("Upload must include the package file.").build();
        }
        byte[] nupkgBytes = file.get().content();

        NuspecXml.Metadata nuspec;
        try {
            nuspec = NuspecXml.extractFromNupkg(nupkgBytes);
        } catch (RuntimeException e) {
            return Response.status(400).entity("Could not read the uploaded package: " + e.getMessage()).build();
        }
        String packageName = nuspec.id().toLowerCase(Locale.ROOT);
        String version = NuGetVersions.normalize(nuspec.version());

        // The exact same lock BagetterSidecarClient#deletePackage takes for this repository, not
        // a private one: without sharing it, a DeletePackage racing this push could still list
        // its versions before this push lands and miss it, the one case a private lock here
        // could never close no matter how it synchronized with itself.
        synchronized (bagetter.lockFor(handle.repoId())) {
            Optional<byte[]> existing = existingNupkgContent(handle.baseUrl(), packageName, version);
            if (existing.isPresent()) {
                if (Arrays.equals(existing.get(), nupkgBytes)) {
                    return Response.status(200).build();
                }
                return Response.status(409)
                        .entity("A package with this id and version already exists.")
                        .build();
            }

            // Snapshotted before the real write, not after: see
            // CodeArtifactService#currentContainerBackedPackageFence's own javadoc for why the
            // order matters.
            long fenceSnapshot = service.currentContainerBackedPackageFence(handle.region(), domain, handle.owner(),
                    repository, "nuget", null, packageName);
            Response response = forwardPush(handle.baseUrl(), contentType, content);
            boolean pushSucceeded = response.getStatus() == 200 || response.getStatus() == 201
                    || response.getStatus() == 202;
            if (pushSucceeded) {
                service.recordContainerBackedAssetBytes(handle.region(), domain, handle.owner(), repository,
                        "nuget", null, packageName, nupkgBytes.length, fenceSnapshot);
            }
            return response;
        }
    }

    /**
     * {@code empty()} when {@code packageName}/{@code version} isn't published on BaGetter yet, its
     * actual bytes otherwise. A connectivity failure here throws rather than returning {@code
     * empty()}, the same fail-safe reasoning as {@code CodeArtifactPypiController
     * #existingFileContent}: letting a push with different content through to BaGetter during
     * exactly the moment this check is unavailable would be the opposite of fail-safe, and BaGetter's
     * own {@code AllowPackageOverwrites: false} would reject it anyway, just with a response this
     * proxy no longer controls.
     */
    private Optional<byte[]> existingNupkgContent(String baseUrl, String packageName, String version) {
        URI uri = SidecarUriUtils.combine(URI.create(baseUrl), "/v3/package/"
                + SidecarUriUtils.encodeSegment(packageName) + "/" + SidecarUriUtils.encodeSegment(version) + "/"
                + SidecarUriUtils.encodeSegment(packageName) + "." + SidecarUriUtils.encodeSegment(version)
                + ".nupkg");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not check nuget package " + packageName + " " + version + " for an existing push", e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Could not check nuget package " + packageName + " " + version + ": upstream returned "
                            + response.statusCode());
        }
        return Optional.of(response.body());
    }

    private Response forwardPush(String baseUrl, String contentType, byte[] content) {
        HttpRequest request = HttpRequest.newBuilder(SidecarUriUtils.combine(URI.create(baseUrl), "/api/v2/package"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .PUT(BodyPublishers.ofByteArray(content))
                .build();
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

    private RepositoryHandle requireRepository(HttpHeaders headers, String domain, String repository) {
        String token = CodeArtifactTokenExtractor.extractToken(headers);
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException("Basic realm=\"floci-codeartifact\""));
        String repoId;
        try {
            repoId = service.ensureFormatContainerId("nuget", scope.region(), domain, scope.owner(), repository);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
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
