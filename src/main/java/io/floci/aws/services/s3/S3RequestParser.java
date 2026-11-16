package io.floci.aws.services.s3;

import io.floci.aws.core.common.ResteasyRequests;
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

    /** Returns {@code true} if the decoded query parameters contain the given name. */
    static boolean hasQueryParam(UriInfo uriInfo, String param) {
        return ResteasyRequests.queryParameters(uriInfo).containsKey(param);
    }
}
