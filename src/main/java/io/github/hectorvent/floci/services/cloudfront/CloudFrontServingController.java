package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    // Instance (not static) field: a static HttpClient initialized at class-init time lands in the
    // GraalVM native-image build heap (jdk.internal.net.http.HttpClientFacade is initialized at run
    // time), which fails the native build. Creating it per-bean-instance keeps it out of the build
    // heap, matching the apigatewayv2 HttpProxyInvoker pattern.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final CloudFrontService service;
    private final S3Service s3Service;

    @Inject
    public CloudFrontServingController(CloudFrontService service, S3Service s3Service) {
        this.service = service;
        this.s3Service = s3Service;
    }

    @GET
    @Path("/{proxy:.*}")
    public Response get(@PathParam("distId") String distId, @PathParam("proxy") String proxy) {
        return serve(distId, proxy, true);
    }

    @HEAD
    @Path("/{proxy:.*}")
    public Response head(@PathParam("distId") String distId, @PathParam("proxy") String proxy) {
        return serve(distId, proxy, false);
    }

    private Response serve(String distId, String proxy, boolean includeBody) {
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
                CloudFrontRequestRouter.matchResponseHeadersPolicyId(config, normalized));

        OriginResponse origin = route(config, normalized, includeBody);

        if (origin.status() >= 400) {
            Response fallback = applyCustomError(config, origin, includeBody, directives);
            if (fallback != null) {
                return fallback;
            }
        }
        return toResponse(origin, includeBody, directives);
    }

    /** Resolves the response-headers policy for a behavior into the headers it contributes, if any. */
    private ResponseHeadersPolicyConfigCodec.Directives responseHeaderDirectives(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        try {
            return ResponseHeadersPolicyConfigCodec.directives(
                    service.getResponseHeadersPolicy(policyId).getConfig());
        } catch (AwsException e) {
            // A behavior may reference an AWS-managed policy the emulator has not seeded; treat an
            // unknown policy as no policy rather than failing the request.
            return null;
        }
    }

    /** Resolves the target origin for a normalized path and fetches the content. */
    private OriginResponse route(DistributionConfig config, String normalized, boolean includeBody) {
        String originId = CloudFrontRequestRouter.matchTargetOriginId(config, normalized);
        Origin origin = CloudFrontRequestRouter.findOrigin(config, originId);
        if (origin == null) {
            return OriginResponse.error(502, "No origin matched the request.");
        }
        if (CloudFrontRequestRouter.isS3Origin(origin)) {
            String key = CloudFrontRequestRouter.resolveOriginKey(
                    origin.getOriginPath(), normalized, config.getDefaultRootObject());
            return fetchFromS3(origin, key, includeBody);
        }
        String forwardUri = CloudFrontRequestRouter.resolveForwardUri(
                origin.getOriginPath(), normalized, config.getDefaultRootObject());
        return fetchFromCustomOrigin(origin, forwardUri, includeBody);
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
                return new OriginResponse(200, contentType(obj), data, data.length);
            }
            S3Object meta = s3Service.headObject(bucket, key);
            return new OriginResponse(200, contentType(meta), null, meta.getSize());
        } catch (AwsException e) {
            return OriginResponse.error(e.getHttpStatus(), e.getMessage());
        }
    }

    /** Fetches from a custom (non-S3) origin. {@code forwardUri} already includes the origin path. */
    private OriginResponse fetchFromCustomOrigin(Origin origin, String forwardUri, boolean includeBody) {
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

        String target = protocol + "://" + origin.getDomainName() + ":" + port + forwardUri;

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(Duration.ofSeconds(30));
            if (includeBody) {
                rb.GET();
            } else {
                rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("content-type").orElse(DEFAULT_CONTENT_TYPE);
            byte[] body = resp.body() != null ? resp.body() : new byte[0];
            return new OriginResponse(resp.statusCode(), ct, includeBody ? body : null, body.length);
        } catch (Exception e) {
            LOG.warnv("CloudFront custom origin fetch failed for {0}: {1}", target, e.getMessage());
            return OriginResponse.error(502, "Bad Gateway: " + e.getMessage());
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
                    origin.contentLength()), includeBody, directives);
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
            page = fetchFromCustomOrigin(errOrigin, forwardUri, includeBody);
        }
        if (page.status() >= 400) {
            // Custom error page unavailable → return the status received from the error-page origin
            // (AWS behavior), without recursively applying custom error handling.
            return toResponse(page, includeBody, directives);
        }
        return toResponse(new OriginResponse(responseCode, page.contentType(), page.body(),
                page.contentLength()), includeBody, directives);
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
        Set<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (origin.contentType() != null) {
            rb.type(origin.contentType());
            present.add("Content-Type");
        }
        if (includeBody && origin.body() != null) {
            rb.entity(origin.body());
        } else {
            rb.header("Content-Length", origin.contentLength());
            present.add("Content-Length");
        }
        applyResponseHeadersPolicy(rb, directives, present);
        return rb.build();
    }

    /**
     * Adds a response-headers policy's headers to the response. Each header carries an override flag:
     * when false it yields to a header the origin already set (here, only the ones Floci sets itself,
     * since it does not forward arbitrary origin headers). {@code RemoveHeadersConfig} is parsed and
     * round-tripped but has no serving effect for the same reason — there are no forwarded origin
     * headers to strip.
     */
    private void applyResponseHeadersPolicy(Response.ResponseBuilder rb,
                                            ResponseHeadersPolicyConfigCodec.Directives directives,
                                            Set<String> present) {
        if (directives == null) {
            return;
        }
        for (ResponseHeadersPolicyConfigCodec.PolicyHeader header : directives.add()) {
            if (header.override() || !present.contains(header.name())) {
                rb.header(header.name(), header.value());
                present.add(header.name());
            }
        }
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
            return dflt;
        }
    }

    /** The result of fetching from an origin: status, content type, optional body, and length. */
    private record OriginResponse(int status, String contentType, byte[] body, long contentLength) {
        static OriginResponse error(int status, String message) {
            byte[] b = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
            return new OriginResponse(status, MediaType.TEXT_PLAIN, b, b.length);
        }
    }
}
