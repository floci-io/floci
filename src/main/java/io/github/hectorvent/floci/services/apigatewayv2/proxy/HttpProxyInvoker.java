package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import io.github.hectorvent.floci.services.apigatewayv2.model.Integration;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * (Content-Length, etc.) — those are skipped silently.
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
        String finalUrl = buildFinalUrl(builder);
        if (hasHeader(builder, "Host") && finalUrl.startsWith("http://")) {
            try {
                return invokeHttpWithHostOverride(finalUrl, method, builder);
            } catch (Exception e) {
                LOG.warnv("HTTP_PROXY backend call failed: {0}", e.getMessage());
                return errorResult("Bad Gateway: " + e.getMessage());
            }
        }

        HttpRequest.Builder hrb;
        try {
            hrb = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
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

    private static boolean hasHeader(ProxyRequestBuilder builder, String headerName) {
        return builder.headers().keySet().stream().anyMatch(headerName::equalsIgnoreCase);
    }

    private static String firstHeader(ProxyRequestBuilder builder, String headerName) {
        for (Map.Entry<String, List<String>> entry : builder.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static ProxyResult invokeHttpWithHostOverride(String finalUrl, String method, ProxyRequestBuilder builder)
            throws IOException {
        URI uri = URI.create(finalUrl);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), 10_000);
            socket.setSoTimeout(30_000);

            OutputStream out = socket.getOutputStream();
            byte[] body = builder.body() == null ? new byte[0] : builder.body();
            StringBuilder request = new StringBuilder()
                    .append(method.toUpperCase(Locale.ROOT)).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(firstHeader(builder, "Host")).append("\r\n")
                    .append("Connection: close\r\n");
            for (Map.Entry<String, List<String>> header : builder.headers().entrySet()) {
                String name = header.getKey();
                String lower = name.toLowerCase(Locale.ROOT);
                if (RESTRICTED.contains(lower) || lower.equals("connection")) {
                    continue;
                }
                for (String value : header.getValue()) {
                    request.append(name).append(": ").append(value).append("\r\n");
                }
            }
            if (body.length > 0) {
                request.append("Content-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();

            return readRawHttpResponse(socket.getInputStream());
        }
    }

    private static ProxyResult readRawHttpResponse(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            headerBytes.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }

        String headersText = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String[] lines = headersText.split("\r\n");
        if (lines.length == 0 || !lines[0].startsWith("HTTP/")) {
            throw new IOException("invalid HTTP response");
        }
        String[] status = lines[0].split(" ", 3);
        int statusCode = Integer.parseInt(status[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        String transferEncoding = null;
        int contentLength = -1;
        for (int i = 1; i < lines.length; i++) {
            int separator = lines[i].indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = lines[i].substring(0, separator);
            String value = lines[i].substring(separator + 1).trim();
            if (name.equalsIgnoreCase("Transfer-Encoding")) {
                transferEncoding = value;
            }
            if (name.equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(value);
            }
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, value);
            }
        }
        byte[] body = transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")
                ? readChunkedBody(input)
                : contentLength >= 0 ? input.readNBytes(contentLength) : input.readAllBytes();
        return new ProxyResult(statusCode, headers, body);
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(input);
            if (sizeLine == null) {
                throw new IOException("unexpected end of chunked response");
            }
            int extension = sizeLine.indexOf(';');
            int size = Integer.parseInt((extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim(), 16);
            if (size == 0) {
                while (true) {
                    String trailer = readAsciiLine(input);
                    if (trailer == null || trailer.isEmpty()) {
                        return body.toByteArray();
                    }
                }
            }
            body.write(input.readNBytes(size));
            expectCrlf(input);
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b == -1) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\n') {
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\r') {
                int next = input.read();
                if (next == '\n') {
                    return line.toString(StandardCharsets.ISO_8859_1);
                }
                line.write(b);
                if (next != -1) {
                    line.write(next);
                }
                continue;
            }
            line.write(b);
        }
    }

    private static void expectCrlf(InputStream input) throws IOException {
        int cr = input.read();
        int lf = input.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("invalid chunked response");
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
