package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.UriInfo;

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

    /**
     * Returns {@code true} if the request URI contains a query parameter with the given name.
     * Checks {@link UriInfo#getQueryParameters()} first; if that does not contain the param,
     * falls back to parsing the raw query string by splitting on {@code &} and extracting
     * the parameter name (everything before the first {@code =}).
     *
     * <p>This avoids false positives from substring matches inside parameter <em>values</em>
     * (e.g. {@code X-Amz-SignedHeaders=host%3Bx-amz-tagging} would falsely match
     * {@code "tagging"} with naive {@code String.contains()}).
     */
    static boolean hasQueryParam(UriInfo uriInfo, String param) {
        if (uriInfo.getQueryParameters().containsKey(param)) return true;
        String query = uriInfo.getRequestUri().getQuery();
        return hasQueryParamInString(query, param);
    }

    /**
     * Direct string-based variant for testing without UriInfo mocking.
     * Package-private so unit tests in the same package can call it.
     */
    static boolean hasQueryParamInString(String query, String param) {
        if (query == null) return false;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (name.equals(param)) return true;
        }
        return false;
    }
}
