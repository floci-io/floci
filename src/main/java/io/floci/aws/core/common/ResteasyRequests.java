package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.common.util.UnmodifiableMultivaluedMap;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

public final class ResteasyRequests {

    private static final MultivaluedMap<String, String> NO_QUERY =
            new UnmodifiableMultivaluedMap<>(new MultivaluedHashMap<>());

    private ResteasyRequests() {
    }

    /** The RESTEasy Reactive context under a JAX-RS one, or null for a synthetic context. */
    public static ResteasyReactiveRequestContext of(ContainerRequestContext ctx) {
        return ctx instanceof ResteasyReactiveContainerRequestContext container
                && container.getServerRequestContext() instanceof ResteasyReactiveRequestContext request
                ? request : null;
    }

    /**
     * {@link jakarta.ws.rs.core.UriInfo#getQueryParameters()}, which builds a map on each call, so a request
     * with no query string gets a shared empty one.
     */
    public static MultivaluedMap<String, String> queryParameters(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = of(ctx);
        if (request != null && request.serverRequest().query() == null) {
            return NO_QUERY;
        }
        return ctx.getUriInfo().getQueryParameters();
    }

    /** {@link #queryParameters(ContainerRequestContext)} for a {@link UriInfo} injected into a bean. */
    public static MultivaluedMap<String, String> queryParameters(UriInfo uriInfo) {
        ResteasyReactiveRequestContext request = CurrentRequestManager.get();
        if (request != null && request.serverRequest().query() == null) {
            return NO_QUERY;
        }
        return uriInfo.getQueryParameters();
    }
}
