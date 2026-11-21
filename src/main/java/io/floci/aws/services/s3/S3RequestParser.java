package io.floci.aws.services.s3;

import io.floci.aws.core.common.ResteasyRequests;
import jakarta.ws.rs.core.UriInfo;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Package-private utility for parsing S3 request elements.
 * Methods are static and stateless for direct testability.
 *
 * <p>Created in Phase 1 (#932) with {@link #hasQueryParam(UriInfo, String)}.
 * Extended in Phase 2 (#986) with {@link #parseTaggingHeader(String)}.
 */
final class S3RequestParser {

    private S3RequestParser() {
    }

    /** Returns {@code true} if the decoded query parameters, then the raw query names, contain the given name. */
    static boolean hasQueryParam(UriInfo uriInfo, String param) {
        return ResteasyRequests.queryParameters(uriInfo).containsKey(param)
                || hasQueryParamInString(ResteasyRequests.rawQuery(uriInfo), param);
    }

    /**
     * Returns {@code true} if a name in the raw query string equals the given one after decoding.
     * The query is split before decoding, so an encoded {@code &} inside a value never starts a name.
     */
    static boolean hasQueryParamInString(String query, String param) {
        if (query == null) {
            return false;
        }
        int length = query.length();
        int start = 0;
        while (start <= length) {
            int end = query.indexOf('&', start);
            if (end < 0) {
                end = length;
            }
            int eq = query.indexOf('=', start, end);
            int nameEnd = eq >= 0 ? eq : end;
            if (isEncoded(query, start, nameEnd)) {
                if (decodedNameEquals(query.substring(start, nameEnd), param)) {
                    return true;
                }
            } else if (nameEnd - start == param.length() && query.startsWith(param, start)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private static boolean isEncoded(String query, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = query.charAt(i);
            if (c == '%' || c == '+') {
                return true;
            }
        }
        return false;
    }

    private static boolean decodedNameEquals(String name, String param) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8).equals(param);
        } catch (IllegalArgumentException ignored) {
            // A name with a malformed escape matches no parameter.
            return false;
        }
    }
}
