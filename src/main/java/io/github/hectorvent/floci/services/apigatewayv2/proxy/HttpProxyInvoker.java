package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Invokes an HTTP_PROXY integration: builds the target URL from the integration's
 * IntegrationUri (with {placeholder} substitution from path params), seeds an
 * outgoing request with the inbound headers + query, applies RequestParameters
 * transformations, and forwards the call to the backend via java.net.http.HttpClient.
 *
 * <p>Hop-by-hop headers (per RFC 7230 §6.1) are stripped from both the outgoing
 * request and the response. Java's HttpClient also restricts certain headers
 * (Host, Content-Length, etc.) — those are skipped silently.
 *
 * <p>If the backend is unreachable or times out, returns a 502 Bad Gateway
 * ProxyResult so the controller can relay a clean error to the original client.
 */
public class HttpProxyInvoker {
    private static final Logger LOG = Logger.getLogger(HttpProxyInvoker.class);

    /** RFC 7230 hop-by-hop headers that must not be forwarded across proxies. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "content-length", "host");

    /** Headers java.net.http.HttpClient refuses to set via Builder.header(). */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");

    // Pin to HTTP/1.1: the default HTTP_2 setting attempts cleartext-HTTP/2 negotiation
    // against http:// backends, which hangs against plain HTTP/1.1 servers (notably the
    // in-JVM Vertx HttpServer used by ELBv2 listeners for HttpAlbIntegration).
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final RequestParameterMapper mapper = new RequestParameterMapper(new ContextValueResolver());

    public ProxyResult invoke(Integration integration, RequestContext ctx) {
        // 1. Resolve target URL from IntegrationUri template + captured path params
        String resolvedUrl = PathTemplateResolver.resolve(integration.getIntegrationUri(), ctx.pathParams());

        // 2. Determine HTTP method (integration.method=ANY/null means use the inbound method)
        String method = integration.getIntegrationMethod();
        if (method == null || method.isEmpty() || method.equalsIgnoreCase("ANY")) {
            method = ctx.httpMethod();
        }

        // 3. Build mutable request, seed with inbound headers/query (excluding hop-by-hop)
        ProxyRequestBuilder builder = new ProxyRequestBuilder(resolvedUrl, method);
        if (ctx.requestHeaders() != null) {
            for (Map.Entry<String, String> e : ctx.requestHeaders().entrySet()) {
                if (!HOP_BY_HOP.contains(e.getKey().toLowerCase())) {
                    builder.overwriteHeader(e.getKey(), e.getValue());
                }
            }
        }
        if (ctx.queryParams() != null) {
            for (Map.Entry<String, String> e : ctx.queryParams().entrySet()) {
                builder.overwriteQuery(e.getKey(), e.getValue());
            }
        }
        builder.setBody(ctx.body());

        // 4. Apply RequestParameters
        mapper.apply(integration.getRequestParameters(), builder, ctx);

        // 5. Build java.net.http.HttpRequest
        HttpRequest.Builder hrb;
        try {
            hrb = HttpRequest.newBuilder()
                    .uri(URI.create(buildFinalUrl(builder)))
                    .timeout(Duration.ofSeconds(30));
        } catch (IllegalArgumentException e) {
            LOG.warnv("HTTP_PROXY: invalid target URL: {0}", e.getMessage());
            return errorResult("Bad Gateway: invalid target URL: " + e.getMessage());
        }

        switch (method.toUpperCase()) {
            case "GET" -> hrb.GET();
            case "DELETE" -> hrb.DELETE();
            case "HEAD" -> hrb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> hrb.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            default -> hrb.method(method.toUpperCase(),
                    builder.body() != null
                            ? HttpRequest.BodyPublishers.ofByteArray(builder.body())
                            : HttpRequest.BodyPublishers.noBody());
        }

        for (Map.Entry<String, List<String>> e : builder.headers().entrySet()) {
            if (RESTRICTED.contains(e.getKey().toLowerCase())) continue;
            for (String v : e.getValue()) {
                hrb.header(e.getKey(), v);
            }
        }

        try {
            HttpResponse<byte[]> resp = client.send(hrb.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
                if (HOP_BY_HOP.contains(e.getKey().toLowerCase())) continue;
                respHeaders.put(e.getKey(), String.join(",", e.getValue()));
            }
            return new ProxyResult(resp.statusCode(), respHeaders, resp.body());
        } catch (Exception e) {
            LOG.warnv("HTTP_PROXY backend call failed: {0}", e.getMessage());
            return errorResult("Bad Gateway: " + e.getMessage());
        }
    }

    private static String buildFinalUrl(ProxyRequestBuilder builder) {
        if (builder.queryParams().isEmpty()) return builder.url();
        URI parsed = URI.create(builder.url());
        StringJoiner sj = new StringJoiner("&");
        if (parsed.getRawQuery() != null) sj.add(parsed.getRawQuery());
        for (Map.Entry<String, List<String>> e : builder.queryParams().entrySet()) {
            for (String v : e.getValue()) {
                sj.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                       URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        String base = builder.url().split("\\?")[0];
        return base + "?" + sj;
    }

    private static ProxyResult errorResult(String message) {
        String body = "{\"message\":\"" + message.replace("\"", "\\\"") + "\"}";
        return new ProxyResult(502,
                Map.of("Content-Type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
