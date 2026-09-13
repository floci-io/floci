package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of an HTTP_PROXY integration invocation. Carries the backend's
 * response status, headers and body bytes back to the API Gateway dispatcher
 * for relay to the original caller.
 *
 * <p>Headers are multi-valued: a backend may legitimately send the same header
 * name more than once (most commonly {@code Set-Cookie}), and a proxy that
 * folds those into one comma-joined value corrupts them. Cookie values may
 * themselves contain commas (an {@code Expires} date does), so the joined form
 * cannot be split back apart by the client.
 */
public record ProxyResult(int statusCode, Map<String, List<String>> headers, byte[] body) {

    /** Convenience for a response whose headers are all single-valued. */
    public static ProxyResult withSingleValueHeaders(int statusCode, Map<String, String> headers, byte[] body) {
        Map<String, List<String>> expanded = new LinkedHashMap<>();
        headers.forEach((name, value) -> expanded.put(name, List.of(value)));
        return new ProxyResult(statusCode, expanded, body);
    }
}
