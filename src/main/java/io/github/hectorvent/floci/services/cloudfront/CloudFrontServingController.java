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
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * layer: the emulator is HTTP-first and serves GET/HEAD plus CORS OPTIONS regardless of scheme.
 */
@Path("/_cloudfront/{distId}")
public class CloudFrontServingController {

    private static final Logger LOG = Logger.getLogger(CloudFrontServingController.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC);
    private static final Set<String> FORBIDDEN_POLICY_RESPONSE_HEADERS = Set.of(
            "connection", "content-length", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade", "via");
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
    public Response get(@PathParam("distId") String distId, @PathParam("proxy") String proxy) {
        var request = currentVertxRequest.getCurrent().request();
        return serve(distId, proxy, request.query(), request.getHeader("Origin"), "GET",
                null, null);
    }

    @HEAD
    @Path("/{proxy:.*}")
    public Response head(@PathParam("distId") String distId, @PathParam("proxy") String proxy) {
        var request = currentVertxRequest.getCurrent().request();
        return serve(distId, proxy, request.query(), request.getHeader("Origin"), "HEAD",
                null, null);
    }

    @OPTIONS
    @Path("/{proxy:.*}")
    public Response options(@PathParam("distId") String distId, @PathParam("proxy") String proxy) {
        var request = currentVertxRequest.getCurrent().request();
        return serve(distId, proxy, request.query(), request.getHeader("Origin"), "OPTIONS",
                request.getHeader("Access-Control-Request-Method"),
                request.getHeader("Access-Control-Request-Headers"));
    }

    private Response serve(String distId, String proxy, String rawQuery,
                           String viewerOrigin, String method,
                           String accessControlRequestMethod,
                           String accessControlRequestHeaders) {
        boolean includeBody = !"HEAD".equals(method);
        boolean preflightRequest = "OPTIONS".equals(method)
                && viewerOrigin != null && !viewerOrigin.isBlank()
                && accessControlRequestMethod != null && !accessControlRequestMethod.isBlank();
        Distribution dist;
        try {
            dist = service.getDistribution(distId);
        } catch (AwsException e) {
            return textError(e.getHttpStatus(), e.getMessage());
        }
        DistributionConfig config = dist.getConfig();
        if (config == null) {
            return textError(502, "Distribution has no configuration.");
        }

        String viewerPath = "/" + (proxy == null ? "" : proxy);
        String normalized = CloudFrontRequestRouter.normalizePath(viewerPath);

        // The response-headers policy of the behavior that matches the request applies to the final
        // response CloudFront returns to the viewer, including any custom-error page substituted below.
        ResponseHeadersPolicyConfigCodec.Directives directives = responseHeaderDirectives(
                CloudFrontRequestRouter.matchResponseHeadersPolicyId(config, normalized), viewerOrigin,
                preflightRequest);

        OriginResponse origin = route(config, normalized, rawQuery, method, viewerOrigin,
                accessControlRequestMethod, accessControlRequestHeaders);

        if (origin.status() >= 400) {
            Response fallback = applyCustomError(config, origin, includeBody, directives);
            if (fallback != null) {
                return fallback;
            }
        }
        return toResponse(origin, includeBody, directives);
    }

    /** Resolves the response-headers policy for a behavior into the headers it contributes, if any. */
    private ResponseHeadersPolicyConfigCodec.Directives responseHeaderDirectives(
            String policyId, String viewerOrigin, boolean preflightRequest) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        try {
            return ResponseHeadersPolicyConfigCodec.directives(
                    service.getResponseHeadersPolicy(policyId).getConfig(), viewerOrigin,
                    preflightRequest);
        } catch (AwsException e) {
            // New distribution writes reject dangling references. A persisted legacy record can still
            // contain one, so keep serving it without policy headers but make the corruption visible.
            LOG.warnv("Distribution references missing response headers policy {0}", policyId);
            return null;
        }
    }

    /** Resolves the target origin for a normalized path and fetches the content. */
    private OriginResponse route(DistributionConfig config, String normalized, String rawQuery,
                                 String method, String viewerOrigin,
                                 String accessControlRequestMethod,
                                 String accessControlRequestHeaders) {
        String originId = CloudFrontRequestRouter.matchTargetOriginId(config, normalized);
        Origin origin = CloudFrontRequestRouter.findOrigin(config, originId);
        if (origin == null) {
            return OriginResponse.error(502, "No origin matched the request.");
        }
        if (CloudFrontRequestRouter.isS3Origin(origin)) {
            if ("OPTIONS".equals(method)) {
                return fetchS3Preflight(origin, viewerOrigin, accessControlRequestMethod,
                        accessControlRequestHeaders);
            }
            String key = CloudFrontRequestRouter.resolveOriginKey(
                    origin.getOriginPath(), normalized, config.getDefaultRootObject());
            return fetchFromS3(origin, key, !"HEAD".equals(method));
        }
        String forwardUri = CloudFrontRequestRouter.resolveForwardUri(
                origin.getOriginPath(), normalized, config.getDefaultRootObject());
        return fetchFromCustomOrigin(origin, forwardUri, rawQuery, method, viewerOrigin,
                accessControlRequestMethod, accessControlRequestHeaders);
    }

    private OriginResponse fetchFromS3(Origin origin, String key, boolean includeBody) {
        String bucket = CloudFrontRequestRouter.bucketFromS3Domain(origin.getDomainName());
        if (bucket == null) {
            return OriginResponse.error(502, "Could not determine S3 bucket for origin.");
        }
        try {
            if (includeBody) {
                S3Object obj = s3Service.getObject(bucket, key);
                byte[] data = obj.getData() != null ? obj.getData() : new byte[0];
                return new OriginResponse(200, contentType(obj), data, data.length,
                        s3ObjectHeaders(obj));
            }
            S3Object meta = s3Service.headObject(bucket, key);
            return new OriginResponse(200, contentType(meta), null, meta.getSize(),
                    s3ObjectHeaders(meta));
        } catch (AwsException e) {
            return OriginResponse.error(e.getHttpStatus(), e.getMessage());
        }
    }

    private OriginResponse fetchS3Preflight(Origin origin, String viewerOrigin,
                                            String requestMethod, String requestHeaders) {
        String bucket = CloudFrontRequestRouter.bucketFromS3Domain(origin.getDomainName());
        if (bucket == null) {
            return OriginResponse.error(502, "Could not determine S3 bucket for origin.");
        }
        if (viewerOrigin == null || viewerOrigin.isBlank()
                || requestMethod == null || requestMethod.isBlank()) {
            return new OriginResponse(200, null, new byte[0], 0, Map.of());
        }
        List<String> requestedHeaders = splitHeaderValues(requestHeaders);
        return s3Service.evaluateCors(bucket, viewerOrigin, requestMethod, requestedHeaders)
                .map(cors -> {
                    Map<String, List<String>> headers = new LinkedHashMap<>();
                    putHeader(headers, "Access-Control-Allow-Origin", cors.allowedOrigin());
                    putHeader(headers, "Access-Control-Allow-Methods",
                            String.join(", ", cors.allowedMethods()));
                    if (cors.maxAgeSeconds() > 0) {
                        putHeader(headers, "Access-Control-Max-Age",
                                Integer.toString(cors.maxAgeSeconds()));
                    }
                    if (!cors.allowedHeaders().isEmpty()) {
                        putHeader(headers, "Access-Control-Allow-Headers",
                                String.join(", ", cors.allowedHeaders()));
                    }
                    if (!cors.exposeHeaders().isEmpty()) {
                        putHeader(headers, "Access-Control-Expose-Headers",
                                String.join(", ", cors.exposeHeaders()));
                    }
                    return new OriginResponse(200, null, new byte[0], 0, headers);
                })
                .orElseGet(() -> OriginResponse.error(403,
                        "This CORS request is not allowed."));
    }

    /** Fetches from a custom (non-S3) origin. {@code forwardUri} already includes the origin path. */
    private OriginResponse fetchFromCustomOrigin(Origin origin, String forwardUri, String rawQuery,
                                                 String method, String viewerOrigin,
                                                 String accessControlRequestMethod,
                                                 String accessControlRequestHeaders) {
        boolean includeBody = !"HEAD".equals(method);
        Map<String, Object> coc = origin.getCustomOriginConfig();
        String policy = coc != null ? str(coc.get("OriginProtocolPolicy")) : "";
        String protocol;
        int port;
        if ("https-only".equalsIgnoreCase(policy)) {
            protocol = "https";
            port = intOrDefault(coc, "HTTPSPort", 443);
        } else {
            protocol = "http";
            port = intOrDefault(coc, "HTTPPort", 80);
        }

        try {
            URI target = buildCustomOriginUri(
                    protocol, origin.getDomainName(), port, forwardUri, rawQuery);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(30));
            rb.method(method, HttpRequest.BodyPublishers.noBody());
            if ("OPTIONS".equals(method)) {
                addRequestHeader(rb, "Origin", viewerOrigin);
                addRequestHeader(rb, "Access-Control-Request-Method", accessControlRequestMethod);
                addRequestHeader(rb, "Access-Control-Request-Headers", accessControlRequestHeaders);
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
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && third == 0)
                    || (first == 192 && second == 0 && third == 2)
                    || (first == 198 && (second == 18 || second == 19))
                    || (first == 198 && second == 51 && third == 100)
                    || (first == 203 && second == 0 && third == 113)
                    || first >= 240;
        }
        if (bytes.length == 16) {
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
    private Response applyCustomError(DistributionConfig config, OriginResponse origin, boolean includeBody,
                                      ResponseHeadersPolicyConfigCodec.Directives directives) {
        Map<String, Object> cer = matchCustomError(config, origin.status());
        if (cer == null) {
            return null;
        }
        int responseCode = parseIntOr(cer.get("ResponseCode"), origin.status());
        String pagePath = str(cer.get("ResponsePagePath"));
        if (pagePath.isBlank()) {
            // ResponseCode override with no custom page: keep the origin body, change the status.
            return toResponse(new OriginResponse(responseCode, origin.contentType(), origin.body(),
                    origin.contentLength(), origin.headers()), includeBody, directives);
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
            page = fetchFromCustomOrigin(errOrigin, forwardUri, null,
                    includeBody ? "GET" : "HEAD", null, null, null);
        }
        if (page.status() >= 400) {
            // Custom error page unavailable → return the status received from the error-page origin
            // (AWS behavior), without recursively applying custom error handling.
            return toResponse(page, includeBody, directives);
        }
        return toResponse(new OriginResponse(responseCode, page.contentType(), page.body(),
                page.contentLength(), page.headers()), includeBody, directives);
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

    private Response toResponse(OriginResponse origin, boolean includeBody,
                                ResponseHeadersPolicyConfigCodec.Directives directives) {
        Response.ResponseBuilder rb = Response.status(origin.status());
        HeaderCollection collected = collectResponseHeaders(origin, includeBody);
        applyResponseHeadersPolicy(collected.headers(), directives, collected.forbiddenPolicyHeaders());
        for (HeaderValues header : collected.headers().values()) {
            header.values().forEach(value -> rb.header(header.name(), value));
        }
        if (includeBody && origin.body() != null) {
            rb.entity(origin.body());
        }
        return rb.build();
    }

    /**
     * Collects the origin's end-to-end response headers case-insensitively while preserving every
     * value (notably multiple {@code Set-Cookie} fields). Hop-by-hop fields and every field named by
     * the origin's {@code Connection} header stay out of the viewer response.
     */
    private static HeaderCollection collectResponseHeaders(OriginResponse origin, boolean includeBody) {
        Set<String> excludedOriginHeaders = new HashSet<>(NON_FORWARDED_RESPONSE_HEADERS);
        Set<String> forbiddenPolicyHeaders = new HashSet<>(FORBIDDEN_POLICY_RESPONSE_HEADERS);
        origin.headers().forEach((name, values) -> {
            if ("connection".equalsIgnoreCase(name)) {
                values.forEach(value -> {
                    for (String token : value.split(",")) {
                        String normalized = normalizeHeaderName(token);
                        if (!normalized.isEmpty()) {
                            excludedOriginHeaders.add(normalized);
                            forbiddenPolicyHeaders.add(normalized);
                        }
                    }
                });
            }
        });

        Map<String, HeaderValues> headers = new LinkedHashMap<>();
        origin.headers().forEach((name, values) -> {
            String normalized = normalizeHeaderName(name);
            if (!normalized.isEmpty() && !excludedOriginHeaders.contains(normalized)) {
                HeaderValues existing = headers.computeIfAbsent(normalized,
                        ignored -> new HeaderValues(name, new ArrayList<>()));
                existing.values().addAll(values);
            }
        });
        if (origin.contentType() != null) {
            setHeader(headers, "Content-Type", origin.contentType());
        }
        if (!includeBody && origin.contentLength() >= 0) {
            setHeader(headers, "Content-Length", Long.toString(origin.contentLength()));
        }
        return new HeaderCollection(headers, forbiddenPolicyHeaders);
    }

    /** Applies removals first, then policy additions with their origin-override semantics. */
    private static void applyResponseHeadersPolicy(
            Map<String, HeaderValues> headers,
            ResponseHeadersPolicyConfigCodec.Directives directives,
            Set<String> forbiddenPolicyHeaders) {
        if (directives == null) {
            return;
        }
        boolean replaceServer = false;
        boolean replaceDate = false;
        for (String name : directives.remove()) {
            String normalized = normalizeHeaderName(name);
            if (ResponseHeadersPolicyValidator.isForbiddenRemoval(normalized)) {
                continue;
            }
            headers.remove(normalized);
            replaceServer |= "server".equals(normalized);
            replaceDate |= "date".equals(normalized);
        }
        if (replaceServer) {
            setHeader(headers, "Server", "CloudFront");
        }
        if (replaceDate) {
            setHeader(headers, "Date", HTTP_DATE.format(Instant.now()));
        }
        boolean hasOriginCorsHeader = headers.keySet().stream()
                .anyMatch(CloudFrontServingController::isCorsHeader);
        for (ResponseHeadersPolicyConfigCodec.PolicyHeader header : directives.add()) {
            String normalized = normalizeHeaderName(header.name());
            if (normalized.isEmpty() || forbiddenPolicyHeaders.contains(normalized)) {
                continue;
            }
            // OriginOverride=false is group-wide for CORS: if the origin supplied any CORS header,
            // CloudFront keeps the origin's CORS response and adds none of the policy's CORS fields.
            if (isCorsHeader(normalized) && !header.override() && hasOriginCorsHeader) {
                continue;
            }
            if (header.override() || !headers.containsKey(normalized)) {
                setHeader(headers, header.name(), header.value());
            }
        }
    }

    private static void setHeader(Map<String, HeaderValues> headers, String name, String value) {
        headers.put(normalizeHeaderName(name),
                new HeaderValues(name, new ArrayList<>(List.of(value == null ? "" : value))));
    }

    private static String normalizeHeaderName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isCorsHeader(String name) {
        return normalizeHeaderName(name).startsWith("access-control-");
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

    private static List<String> splitHeaderValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static void addRequestHeader(HttpRequest.Builder request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.header(name, value);
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

    private record HeaderValues(String name, List<String> values) {
    }

    private record HeaderCollection(Map<String, HeaderValues> headers,
                                    Set<String> forbiddenPolicyHeaders) {
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
