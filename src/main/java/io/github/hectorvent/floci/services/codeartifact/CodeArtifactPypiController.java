package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.MultipartFormParser;
import io.github.hectorvent.floci.services.codeartifact.CodeArtifactService.AuthorizationTokenScope;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, Object> uploadLocks = new ConcurrentHashMap<>();

    @Inject
    public CodeArtifactPypiController(CodeArtifactService service, PypiserverSidecarClient pypiserver) {
        this(service, pypiserver, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CodeArtifactPypiController(CodeArtifactService service, PypiserverSidecarClient pypiserver,
                                HttpClient httpClient) {
        this.service = service;
        this.pypiserver = pypiserver;
        this.httpClient = httpClient;
    }

    private record RepositoryHandle(String repoId, String baseUrl, String pathPrefix) {
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
     */
    @GET
    @Path("simple/{package}/")
    @Produces(MediaType.TEXT_HTML)
    public Response packageIndex(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                  @PathParam("repository") String repository,
                                  @PathParam("package") String packageName) {
        RepositoryHandle handle = requireRepository(headers, domain, repository);
        Response response = proxyGet(handle.baseUrl(), SIMPLE_PATH + SidecarUriUtils.encodeSegment(packageName)
                + "/");
        if (response.getStatus() != 200) {
            return response;
        }
        String html = new String((byte[]) response.getEntity(), StandardCharsets.UTF_8);
        String rewritten = html.replace("href=\"/packages/", "href=\"" + handle.pathPrefix() + "/packages/");
        return Response.status(200).entity(rewritten.getBytes(StandardCharsets.UTF_8)).type(MediaType.TEXT_HTML)
                .build();
    }

    @GET
    @Path("packages/{filename}")
    @Produces(MediaType.WILDCARD)
    public Response downloadFile(@Context HttpHeaders headers, @PathParam("domain") String domain,
                                  @PathParam("repository") String repository,
                                  @PathParam("filename") String filename) {
        String baseUrl = requireRepository(headers, domain, repository).baseUrl();
        return proxyGet(baseUrl, "/packages/" + SidecarUriUtils.encodeSegment(filename));
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
        String packageName = form.fields().get("name");
        Optional<MultipartFormParser.FilePart> file = form.file();
        if (packageName == null || packageName.isBlank() || file.isEmpty()) {
            return Response.status(400).entity("Upload must include name and content fields.").build();
        }

        Object lock = uploadLocks.computeIfAbsent(handle.repoId(), k -> new Object());
        synchronized (lock) {
            Optional<byte[]> existing = existingFileContent(handle.baseUrl(), packageName, file.get().filename());
            if (existing.isPresent()) {
                if (Arrays.equals(existing.get(), file.get().content())) {
                    return Response.status(200).build();
                }
                return Response.status(409)
                        .entity("File already exists. See "
                                + "https://docs.aws.amazon.com/codeartifact/latest/ug/PyPI-format.html")
                        .build();
            }

            return forwardUpload(handle.baseUrl(), contentType, content);
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
    private Optional<byte[]> existingFileContent(String baseUrl, String packageName, String filename) {
        String normalized = normalizePackageName(packageName);
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
            return Optional.empty();
        }
        if (indexResponse.statusCode() != 200) {
            throw new IllegalStateException(
                    "Could not check pypi package " + packageName + " for an existing " + filename
                            + ": upstream returned " + indexResponse.statusCode());
        }
        if (!indexListsFilename(indexResponse.body(), filename)) {
            return Optional.empty();
        }

        URI fileUri = SidecarUriUtils.combine(URI.create(baseUrl), "/packages/"
                + SidecarUriUtils.encodeSegment(filename));
        HttpRequest fileRequest = HttpRequest.newBuilder(fileUri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> fileResponse;
        try {
            fileResponse = httpClient.send(fileRequest, BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not fetch the existing pypi file " + filename + " to check for a content match", e);
        }
        if (fileResponse.statusCode() != 200) {
            throw new IllegalStateException(
                    "Could not fetch the existing pypi file " + filename + " to check for a content match: "
                            + "upstream returned " + fileResponse.statusCode());
        }
        return Optional.of(fileResponse.body());
    }

    private static String normalizePackageName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /**
     * An exact entry match, not a substring search: a shorter real filename that happens to be a
     * substring of a different, longer real filename (or of surrounding markup) must not pass
     * this check.
     */
    private static boolean indexListsFilename(String indexBody, String filename) {
        return SIMPLE_INDEX_HREF.matcher(indexBody).results()
                .map(match -> {
                    String href = match.group(1);
                    String name = href.substring(href.lastIndexOf('/') + 1);
                    int fragment = name.indexOf('#');
                    return fragment < 0 ? name : name.substring(0, fragment);
                })
                .anyMatch(filename::equals);
    }

    private Response forwardUpload(String baseUrl, String contentType, byte[] content) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofByteArray(content))
                .build();
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

    private RepositoryHandle requireRepository(HttpHeaders headers, String domain, String repository) {
        String token = CodeArtifactTokenExtractor.extractToken(headers);
        AuthorizationTokenScope scope = service.resolveAuthorizationToken(token, domain)
                .orElseThrow(() -> new NotAuthorizedException("Basic realm=\"floci-codeartifact\""));
        String repoId;
        try {
            repoId = service.ensureFormatContainerId("pypi", scope.region(), domain, scope.owner(), repository);
        } catch (AwsException e) {
            throw new NotFoundException();
        }
        String baseUrl = pypiserver.ensureReady(repoId, null);
        return new RepositoryHandle(repoId, baseUrl, "/codeartifact/pypi/" + domain + "/" + repository);
    }
}
