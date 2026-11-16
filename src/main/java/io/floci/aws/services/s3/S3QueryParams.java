package io.floci.aws.services.s3;

import io.floci.aws.core.common.ResteasyRequests;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

/** The query parameters of one request, read from UriInfo once. */
final class S3QueryParams {

    private final MultivaluedMap<String, String> decoded;
    private final UriInfo uriInfo;
    private String rawQuery;
    private boolean rawQueryRead;

    S3QueryParams(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
        this.decoded = ResteasyRequests.queryParameters(uriInfo);
    }

    MultivaluedMap<String, String> decoded() {
        return decoded;
    }

    boolean has(String name) {
        return decoded.containsKey(name) || S3RequestParser.hasQueryParamInString(rawQuery(), name);
    }

    String first(String name) {
        return decoded.getFirst(name);
    }

    private String rawQuery() {
        if (!rawQueryRead) {
            rawQuery = uriInfo.getRequestUri().getQuery();
            rawQueryRead = true;
        }
        return rawQuery;
    }
}
