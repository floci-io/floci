package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serves viewer requests routed to a CloudFront distribution (via {@link CloudFrontDistributionFilter})
 * by resolving the matching cache behavior + origin and returning the origin content, implemented to
 * the AWS CloudFront data-plane spec.
 *
 * <p>The internal path is {@code /_cloudfront/{distId}/{proxy:.*}}; {@code proxy} is the viewer's path
 * relative to the distribution. Routing (behavior/path-pattern matching, default-root-object, origin
 * path) is delegated to {@link CloudFrontRequestRouter}. This controller performs the origin I/O:
 *
 * <ul>
 *   <li>S3 origins are read in-process through {@link S3Service} (the bucket is derived from the
 *       origin domain name).</li>
 *   <li>Custom origins are fetched over HTTP(S) with {@link java.net.http.HttpClient}.</li>
 *   <li>When an origin returns an error status, a matching {@code CustomErrorResponse} is applied —
 *       most importantly the single-page-app fallback that rewrites 403/404 to 200 {@code /index.html}.
 *       If the configured error page is itself missing, the received status is returned (no loop).</li>
 * </ul>
 *
 * <p>Viewer-protocol-policy and allowed-method enforcement are intentionally out of scope for this
 * layer: the emulator is HTTP-first and serves GET/HEAD regardless of scheme.
 */
@Path("/_cloudfront/{distId}")
public class CloudFrontServingController {

    private static final Logger LOG = Logger.getLogger(CloudFrontServingController.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC);
    private static final Set<String> NON_FORWARDED_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "via",
            "content-length", "content-type");

    private final CloudFrontService service;
    private final S3Service s3Service;
    private final CurrentVertxRequest currentVertxRequest;
    private final CloudFrontOriginHttpClient httpClient;

    @Inject
    public CloudFrontServingController(CloudFrontService service, S3Service s3Service,
                                       EmulatorConfig emulatorConfig,
                                       CurrentVertxRequest currentVertxRequest) {
        this.service = service;
        this.s3Service = s3Service;
        this.currentVertxRequest = currentVertxRequest;
        this.httpClient = new CloudFrontOriginHttpClient(
                emulatorConfig.services().cloudfront().allowedPrivateOriginHosts().orElse(List.of()));
    }

    @PreDestroy
    void closeHttpClient() {
        try {
            httpClient.close();
        } catch (Exception e) {
            LOG.debugv("Could not close CloudFront origin HTTP client: {0}", e.getMessage());
        }
    }

    @GET
    @Path("/{proxy:.*}")
    public Response get(@PathParam("distId") String distId, @PathParam("proxy") String proxy,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        String rawViewerPath = rawViewerPath(currentVertxRequest.getCurrent().request().uri());
        return serve(distId, rawViewerPath, decodedViewerPath(rawViewerPath),
                currentVertxRequest.getCurrent().request().query(),
                uriInfo.getRequestUri().getScheme(), headers.getHeaderString("Host"), true);
    }

    @HEAD
    @Path("/{proxy:.*}")
    public Response head(@PathParam("distId") String distId, @PathParam("proxy") String proxy,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        String rawViewerPath = rawViewerPath(currentVertxRequest.getCurrent().request().uri());
        return serve(distId, rawViewerPath, decodedViewerPath(rawViewerPath),
                currentVertxRequest.getCurrent().request().query(),
                uriInfo.getRequestUri().getScheme(), headers.getHeaderString("Host"), false);
    }

    private Response serve(String distId, String rawViewerPath, String decodedViewerPath,
                           String rawQuery, String viewerScheme, String viewerHost,
                           boolean includeBody) {
        Distribution dist = service.findByHost(viewerHost);
        if (dist == null || !distId.equals(dist.getId())) {
            return textError(404, "Distribution not found.");
        }
        DistributionConfig config = dist.getConfig();
        if (config == null) {
            return textError(502, "Distribution has no configuration.");
        }
        if (!config.isEnabled()) {
            return textError(404, "Distribution is disabled.");
        }

        String normalized = CloudFrontRequestRouter.normalizePath(decodedViewerPath);

        OriginResponse origin = route(config, normalized, rawViewerPath, decodedViewerPath,
                rawQuery, viewerScheme, includeBody);

        if (origin.status() >= 400) {
            Response fallback = applyCustomError(config, origin, viewerScheme, includeBody);
            if (fallback != null) {
                return fallback;
            }
        }
        return toResponse(origin, includeBody);
    }

    /** Selects an origin with the normalized path, then forwards the original viewer path. */
    private OriginResponse route(DistributionConfig config, String normalized,
                                 String rawViewerPath, String decodedViewerPath,
                                 String rawQuery, String viewerScheme, boolean includeBody) {
        String originId = CloudFrontRequestRouter.matchTargetOriginId(config, normalized);
        Origin origin = CloudFrontRequestRouter.findOrigin(config, originId);
        if (origin == null) {
            return OriginResponse.error(502, "No origin matched the request.");
        }
        if (CloudFrontRequestRouter.isS3Origin(origin)) {
            String key = CloudFrontRequestRouter.resolveOriginKey(
                    origin.getOriginPath(), decodedViewerPath, config.getDefaultRootObject());
            return fetchFromS3(origin, key, includeBody);
        }
        String forwardUri = CloudFrontRequestRouter.resolveForwardUri(
                origin.getOriginPath(), rawViewerPath, config.getDefaultRootObject());
        return fetchFromCustomOrigin(
                origin, forwardUri, rawQuery, viewerScheme, includeBody);
    }

    private OriginResponse fetchFromS3(Origin origin, String key, boolean includeBody) {
        String bucket = CloudFrontRequestRouter.bucketFromS3Domain(origin.getDomainName());
        if (bucket == null) {
            return OriginResponse.error(502, "Could not determine S3 bucket for origin.");
        }
        try {
            s3Service.authorizeAnonymousGetObject(bucket, key);
            if (includeBody) {
                S3Object obj = s3Service.getObject(bucket, key);
                byte[] data = obj.getData() != null ? obj.getData() : new byte[0];
                return new OriginResponse(
                        200, contentType(obj), data, data.length, s3ObjectHeaders(obj));
            }
            S3Object meta = s3Service.headObject(bucket, key);
            return new OriginResponse(
                    200, contentType(meta), null, meta.getSize(), s3ObjectHeaders(meta));
        } catch (AwsException e) {
            return OriginResponse.error(e.getHttpStatus(), e.getMessage());
        }
    }

    /** Fetches from a custom (non-S3) origin. {@code forwardUri} already includes the origin path. */
    private OriginResponse fetchFromCustomOrigin(Origin origin, String forwardUri, String rawQuery,
                                                 String viewerScheme, boolean includeBody) {
        try {
            URI target = buildCustomOriginUri(
                    origin, viewerScheme, forwardUri, rawQuery);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(30));
            if (includeBody) {
                rb.GET();
            } else {
                rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("content-type").orElse(DEFAULT_CONTENT_TYPE);
            byte[] body = resp.body() != null ? resp.body() : new byte[0];
            long contentLength = includeBody ? body.length : responseContentLength(resp);
            return new OriginResponse(resp.statusCode(), ct, includeBody ? body : null,
                    contentLength, resp.headers().map());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv("CloudFront custom origin fetch was interrupted for host {0}",
                    safeOriginName(origin));
            return OriginResponse.error(502, "Bad Gateway.");
        } catch (Exception e) {
            // Do not log the target URI: forwarded query strings can contain credentials or tokens.
            LOG.warnv("CloudFront custom origin fetch failed for host {0}: {1}",
                    safeOriginName(origin), e.getClass().getSimpleName());
            return OriginResponse.error(502, "Bad Gateway.");
        }
    }

    static boolean isBlockedOriginAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isBlockedIpv4Address(bytes, 0);
        }
        if (bytes.length == 16) {
            if (isIpv4MappedAddress(bytes)) {
                return isBlockedIpv4Address(bytes, 12);
            }
            int first = Byte.toUnsignedInt(bytes[0]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20
                    && Byte.toUnsignedInt(bytes[1]) == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return uniqueLocal || documentation;
        }
        return true;
    }

    private static boolean isIpv4MappedAddress(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isBlockedIpv4Address(byte[] bytes, int offset) {
        int first = Byte.toUnsignedInt(bytes[offset]);
        int second = Byte.toUnsignedInt(bytes[offset + 1]);
        int third = Byte.toUnsignedInt(bytes[offset + 2]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224;
    }

    static URI buildCustomOriginUri(String protocol, String domainName, int port,
                                    String forwardUri, String rawQuery) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Custom origin port is outside the valid range");
        }
        String host = normalizeOriginHost(domainName);
        String path = forwardUri == null || forwardUri.isBlank() ? "/" : forwardUri;
        if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Custom origin path is invalid");
        }
        String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return URI.create(protocol + "://" + authorityHost + ":" + port + path
                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery));
    }

    static URI buildCustomOriginUri(Origin origin, String viewerScheme,
                                    String forwardUri, String rawQuery) {
        Map<String, Object> config = origin.getCustomOriginConfig();
        String policy = config != null ? str(config.get("OriginProtocolPolicy")) : "";
        boolean useHttps = "https-only".equalsIgnoreCase(policy)
                || ("match-viewer".equalsIgnoreCase(policy)
                        && "https".equalsIgnoreCase(viewerScheme));
        String protocol = useHttps ? "https" : "http";
        int port = useHttps
                ? intOrDefault(config, "HTTPSPort", 443)
                : intOrDefault(config, "HTTPPort", 80);
        return buildCustomOriginUri(
                protocol, origin.getDomainName(), port, forwardUri, rawQuery);
    }

    static String rawViewerPath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return "/";
        }
        int query = requestUri.indexOf('?');
        String path = query >= 0 ? requestUri.substring(0, query) : requestUri;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path = URI.create(path).getRawPath();
        }
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    static String decodedViewerPath(String rawViewerPath) {
        return URI.create("http://viewer.invalid" + rawViewerPath).getPath();
    }

    static String normalizeOriginHost(String domainName) {
        if (domainName == null || domainName.isBlank()) {
            throw new IllegalArgumentException("Custom origin domain name is empty");
        }
        URI authority = URI.create("//" + domainName.trim());
        if (authority.getRawUserInfo() != null
                || (authority.getRawPath() != null && !authority.getRawPath().isEmpty())
                || authority.getRawQuery() != null || authority.getRawFragment() != null
                || authority.getHost() == null) {
            throw new IllegalArgumentException("Custom origin domain name is not a valid authority");
        }
        return normalizeHost(authority.getHost());
    }

    static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Custom origin host is empty");
        }
        if (normalized.indexOf(':') < 0) {
            normalized = IDN.toASCII(normalized);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static long responseContentLength(HttpResponse<?> response) {
        try {
            return response.headers().firstValueAsLong("content-length").orElse(-1L);
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring invalid custom-origin Content-Length: {0}", e.getMessage());
            return -1L;
        }
    }

    private static String safeOriginName(Origin origin) {
        try {
            return normalizeOriginHost(origin.getDomainName());
        } catch (RuntimeException e) {
            return "<invalid>";
        }
    }

    /**
     * Applies a matching {@code CustomErrorResponse} to an origin error, per the AWS CloudFront spec.
     * Returns {@code null} when no custom handling applies, so the caller returns the original origin
     * response unchanged. Otherwise:
     *
     * <ul>
     *   <li>with no {@code ResponsePagePath}, the origin body is kept but the status is overridden by
     *       {@code ResponseCode};</li>
     *   <li>with a {@code ResponsePagePath}, the page is fetched through behavior/origin routing and,
     *       on success, returned with {@code ResponseCode} (the single-page-app 403/404 -&gt; 200
     *       {@code /index.html} fallback);</li>
     *   <li>if the custom error page is itself unavailable, CloudFront returns the status code it
     *       received from the origin that holds the error pages — not the original status — and does
     *       not recurse (no loop).</li>
     * </ul>
     */
    private Response applyCustomError(DistributionConfig config, OriginResponse origin,
                                      String viewerScheme, boolean includeBody) {
        Map<String, Object> cer = matchCustomError(config, origin.status());
        if (cer == null) {
            return null;
        }
        int responseCode = parseIntOr(cer.get("ResponseCode"), origin.status());
        String pagePath = str(cer.get("ResponsePagePath"));
        if (pagePath.isBlank()) {
            // ResponseCode override with no custom page: keep the origin body, change the status.
            return toResponse(new OriginResponse(responseCode, origin.contentType(), origin.body(),
                    origin.contentLength(), origin.headers()), includeBody);
        }

        String errNormalized = CloudFrontRequestRouter.normalizePath(pagePath);
        String errOriginId = CloudFrontRequestRouter.matchTargetOriginId(config, errNormalized);
        Origin errOrigin = CloudFrontRequestRouter.findOrigin(config, errOriginId);
        if (errOrigin == null) {
            return null;
        }

        OriginResponse page;
        if (CloudFrontRequestRouter.isS3Origin(errOrigin)) {
            String key = CloudFrontRequestRouter.resolveOriginKey(errOrigin.getOriginPath(), errNormalized, null);
            page = fetchFromS3(errOrigin, key, includeBody);
        } else {
            String forwardUri = CloudFrontRequestRouter.resolveForwardUri(errOrigin.getOriginPath(), errNormalized, null);
            page = fetchFromCustomOrigin(
                    errOrigin, forwardUri, null, viewerScheme, includeBody);
        }
        if (page.status() >= 400) {
            // Custom error page unavailable → return the status received from the error-page origin
            // (AWS behavior), without recursively applying custom error handling.
            return toResponse(page, includeBody);
        }
        return toResponse(new OriginResponse(responseCode, page.contentType(), page.body(),
                page.contentLength(), page.headers()), includeBody);
    }

    private Map<String, Object> matchCustomError(DistributionConfig config, int status) {
        List<Map<String, Object>> list = config.getCustomErrorResponses();
        if (list == null) {
            return null;
        }
        for (Map<String, Object> cer : list) {
            if (parseIntOr(cer.get("ErrorCode"), -1) == status) {
                return cer;
            }
        }
        return null;
    }

    private Response toResponse(OriginResponse origin, boolean includeBody) {
        Response.ResponseBuilder rb = Response.status(origin.status());
        Set<String> excludedHeaders = new HashSet<>(NON_FORWARDED_RESPONSE_HEADERS);
        origin.headers().forEach((name, values) -> {
            if ("connection".equalsIgnoreCase(name)) {
                values.forEach(value -> {
                    for (String token : value.split(",")) {
                        excludedHeaders.add(token.trim().toLowerCase(Locale.ROOT));
                    }
                });
            }
        });
        origin.headers().forEach((name, values) -> {
            if (!excludedHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> rb.header(name, value));
            }
        });
        if (origin.contentType() != null) {
            rb.type(origin.contentType());
        }
        if (includeBody && origin.body() != null) {
            rb.entity(origin.body());
        } else if (origin.contentLength() >= 0) {
            rb.header("Content-Length", origin.contentLength());
        }
        return rb.build();
    }

    private Response textError(int status, String message) {
        return Response.status(status)
                .type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message)
                .build();
    }

    private static String contentType(S3Object obj) {
        return obj.getContentType() != null ? obj.getContentType() : DEFAULT_CONTENT_TYPE;
    }

    private static Map<String, List<String>> s3ObjectHeaders(S3Object object) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        putHeader(headers, "Accept-Ranges", "bytes");
        putHeader(headers, "ETag", object.getETag());
        if (object.getLastModified() != null) {
            putHeader(headers, "Last-Modified", HTTP_DATE.format(object.getLastModified()));
        }
        putHeader(headers, "Cache-Control", object.getCacheControl());
        putHeader(headers, "Content-Encoding", object.getContentEncoding());
        putHeader(headers, "Content-Disposition", object.getContentDisposition());
        putHeader(headers, "x-amz-storage-class", object.getStorageClass());
        if (object.getMetadata() != null) {
            object.getMetadata().forEach((name, value) ->
                    putHeader(headers, "x-amz-meta-" + name, value));
        }
        return headers;
    }

    private static void putHeader(Map<String, List<String>> headers, String name, String value) {
        if (value != null) {
            headers.put(name, List.of(value));
        }
    }

    private static String str(Object value) {
        return value != null ? value.toString() : "";
    }

    private static int intOrDefault(Map<String, Object> map, String key, int dflt) {
        return map == null ? dflt : parseIntOr(map.get(key), dflt);
    }

    private static int parseIntOr(Object value, int dflt) {
        if (value == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring non-integer CloudFront configuration value {0}: {1}", value, e.getMessage());
            return dflt;
        }
    }

    /** The result of fetching from an origin, including end-to-end response headers. */
    private record OriginResponse(int status, String contentType, byte[] body, long contentLength,
                                  Map<String, List<String>> headers) {
        static OriginResponse error(int status, String message) {
            byte[] b = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
            return new OriginResponse(status, MediaType.TEXT_PLAIN, b, b.length, Map.of());
        }
    }
}
