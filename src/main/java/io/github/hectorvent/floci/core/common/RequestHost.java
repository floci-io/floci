package io.github.hectorvent.floci.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URI;

/**
 * The host a client addressed. HTTP/2 (RFC 9113 section 8.3.1) sends {@code :authority} and no
 * {@code Host} header, and curl and browsers use HTTP/2 over HTTPS.
 */
public final class RequestHost {

    private RequestHost() {
    }

    public static String of(ContainerRequestContext requestContext) {
        return of(requestContext.getHeaderString("Host"), requestContext.getUriInfo().getRequestUri());
    }

    /**
     * The {@code Host} header when present, else the request URI authority, which the container
     * builds from {@code :authority} on HTTP/2.
     */
    public static String of(String hostHeader, URI requestUri) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader.trim();
        }
        return requestUri != null ? requestUri.getAuthority() : null;
    }

    public static String of(HttpServerRequest request) {
        String hostHeader = request.getHeader("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader.trim();
        }
        HostAndPort authority = request.authority();
        return authority != null ? authority.toString() : null;
    }
}
