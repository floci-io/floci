package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code cookies} array of a payload format 2.0 Lambda event, which HTTP API
 * integrations, HTTP API Lambda authorizers and function URLs all send, from the request's
 * {@code Cookie} headers.
 */
public final class CookieHeaders {

    private CookieHeaders() {
    }

    /**
     * Every cookie-pair from every {@code Cookie} header, in request order. The header name
     * matches case-insensitively, each value is split on {@code ;}, pairs are trimmed and empty
     * segments are dropped.
     */
    public static List<String> cookiePairs(Map<String, List<String>> requestHeaders) {
        List<String> cookies = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : requestHeaders.entrySet()) {
            if (!HttpHeaders.COOKIE.equalsIgnoreCase(e.getKey())) {
                continue;
            }
            for (String header : e.getValue()) {
                for (String cookie : header.split(";")) {
                    String trimmed = cookie.trim();
                    if (!trimmed.isEmpty()) {
                        cookies.add(trimmed);
                    }
                }
            }
        }
        return cookies;
    }
}
