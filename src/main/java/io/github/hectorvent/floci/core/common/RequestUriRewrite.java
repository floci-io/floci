package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;

/**
 * Shares S3's workaround for RESTEasy Reactive's second path decode (Floci #7556).
 * Raw-path consumers must retain the intended path before applying the routing escape.
 */
public final class RequestUriRewrite {

    private RequestUriRewrite() {
    }

    /** Escapes percent signs once more so RESTEasy stores the intended raw routing path. */
    public static void apply(ContainerRequestContext requestContext, URI requestUri) {
        URI routingUri = UriBuilder.fromUri(requestUri)
                .replacePath(requestUri.getRawPath().replace("%", "%25"))
                .build();
        requestContext.setRequestUri(routingUri);
    }
}
