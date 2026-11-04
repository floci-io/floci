package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.jaxrs.UriInfoImpl;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

import java.net.URI;

/**
 * Rewrites a request without letting RESTEasy Reactive decode its path twice (Floci #7556).
 * The routing path and the URI exposed to raw-path consumers must retain the same encoding.
 */
public final class RequestUriRewrite {

    private RequestUriRewrite() {
    }

    /** Applies the URI and compensates only when RESTEasy stored a decoded routing path. */
    public static void apply(ContainerRequestContext requestContext, URI requestUri) {
        requestContext.setRequestUri(requestUri);
        if (!(requestContext instanceof ResteasyReactiveContainerRequestContext reactiveContext)
                || !(reactiveContext.getServerRequestContext() instanceof ResteasyReactiveRequestContext serverContext)) {
            return;
        }

        // RESTEasy 3.40.1 stores URI.getPath(), then decodes it again during routing. Escape only
        // that boundary, and expose the intended URI so backends and signers do not see extra escapes.
        if (!requestUri.getRawPath().equals(serverContext.getPath())) {
            URI routingUri = UriBuilder.fromUri(requestUri)
                    .replacePath(requestUri.getRawPath().replace("%", "%25"))
                    .buildFromEncoded();
            requestContext.setRequestUri(routingUri);
        }
        serverContext.setUriInfo(new RewrittenUriInfo(serverContext, requestUri));
    }

    private static final class RewrittenUriInfo extends UriInfoImpl {

        private final URI requestUri;

        private RewrittenUriInfo(ResteasyReactiveRequestContext requestContext, URI requestUri) {
            super(requestContext);
            this.requestUri = requestUri.normalize();
        }

        @Override
        public URI getRequestUri() {
            return requestUri;
        }

        @Override
        public URI getAbsolutePath() {
            return getRequestUriBuilder().replaceQuery(null).fragment(null).buildFromEncoded();
        }
    }
}
