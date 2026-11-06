package io.floci.aws.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.auth.SigV4Canonicalization;
import io.floci.aws.services.appsync.model.DataSource;
import org.apache.hc.core5.http.ContentType;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The validated HTTP request produced by a resolver, before credentials or network access. */
record HttpDataSourceRequest(URI uri, String method, Map<String, String> headers, byte[] body) {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "connection", "user-agent", "expect",
            "expectation", "transfer-encoding", "transfer_encoding", "content-length", "content_length",
            "proxy-connection", "keep-alive", "te", "trailer", "upgrade");

    HttpDataSourceRequest {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    static HttpDataSourceRequest parse(DataSource source, Object request, ObjectMapper mapper) {
        if (!(request instanceof Map<?, ?> input)) {
            throw invalid("HTTP request must be an object");
        }
        Object endpoint = source.getHttpConfig() == null ? null : source.getHttpConfig().get("endpoint");
        URI base = uri(text(endpoint, "httpConfig.endpoint"));
        if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || base.getPort() < -1 || base.getPort() > 65535) {
            throw invalid("HTTP endpoint must be an HTTP(S) URL without user info, query or fragment");
        }
        String method = text(input.get("method"), "method");
        if (!METHODS.contains(method)) {
            throw invalid("Unsupported HTTP method");
        }
        if (input.containsKey("version") && !"2018-05-29".equals(input.get("version"))) {
            throw invalid("Unsupported HTTP request version");
        }
        String path = text(input.get("resourcePath"), "resourcePath");
        URI resource = uri(path);
        if (!path.startsWith("/") || path.startsWith("//") || resource.isAbsolute()
                || resource.getRawAuthority() != null || resource.getRawQuery() != null || resource.getRawFragment() != null) {
            throw invalid("resourcePath must be a path on the configured HTTP endpoint");
        }
        Map<?, ?> params = object(input.get("params"), "params");
        Map<String, String> headers = stringMap(params.get("headers"), "headers");
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || RESERVED_HEADERS.contains(name)
                    || header.getValue().chars().anyMatch(ch -> (ch < 32 && ch != '\t') || ch == 127 || ch > 255)
                    || normalized.putIfAbsent(name, header.getValue()) != null) {
                throw invalid("Invalid or reserved HTTP header");
            }
        }
        Map<String, String> query = stringMap(params.get("query"), "query");
        String encodedQuery = query.entrySet().stream()
                .map(entry -> SigV4Canonicalization.uriEncode(entry.getKey()) + "="
                        + SigV4Canonicalization.uriEncode(entry.getValue()))
                .sorted().collect(Collectors.joining("&"));
        String prefix = base.toString();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        URI target = uri(prefix + path + (encodedQuery.isEmpty() ? "" : "?" + encodedQuery));
        return new HttpDataSourceRequest(target, method, normalized, body(params.get("body"), normalized, mapper));
    }

    private static byte[] body(Object value, Map<String, String> headers, ObjectMapper mapper) {
        if (value == null) {
            return new byte[0];
        }
        try {
            Charset charset = headers.containsKey("content-type")
                    ? ContentType.parse(headers.get("content-type")).getCharset(StandardCharsets.UTF_8)
                    : StandardCharsets.UTF_8;
            String body = value instanceof String text ? text : mapper.writeValueAsString(value);
            return body.getBytes(charset);
        } catch (Exception e) {
            throw invalid("Could not encode HTTP request body");
        }
    }

    private static Map<String, String> stringMap(Object value, String name) {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<?, ?> entry : object(value, name).entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
                throw invalid(name + " must contain string keys and values");
            }
            result.put(key, text);
        }
        return result;
    }

    private static Map<?, ?> object(Object value, String name) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw invalid(name + " must be an object");
    }

    private static String text(Object value, String name) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid(name + " must be a non-empty string");
    }

    private static URI uri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid HTTP endpoint or resource path");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("MappingTemplate", message, 400);
    }
}
