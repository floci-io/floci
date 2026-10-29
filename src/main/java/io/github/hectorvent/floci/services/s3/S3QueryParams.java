package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.util.HashSet;
import java.util.Set;

/** The query parameters of one request, read from UriInfo once. */
final class S3QueryParams {

    private final MultivaluedMap<String, String> decoded;
    private final UriInfo uriInfo;
    private Set<String> rawNames;

    S3QueryParams(UriInfo uriInfo) {
        this.uriInfo = uriInfo;
        this.decoded = uriInfo.getQueryParameters();
    }

    MultivaluedMap<String, String> decoded() {
        return decoded;
    }

    boolean has(String name) {
        return decoded.containsKey(name) || rawNames().contains(name);
    }

    String first(String name) {
        return decoded.getFirst(name);
    }

    private Set<String> rawNames() {
        if (rawNames == null) {
            Set<String> names = new HashSet<>();
            String query = uriInfo.getRequestUri().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    names.add(eq >= 0 ? pair.substring(0, eq) : pair);
                }
            }
            rawNames = names;
        }
        return rawNames;
    }
}
