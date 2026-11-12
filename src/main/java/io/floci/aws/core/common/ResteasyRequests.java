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

    private static final String QUERY_PARAMETERS = ResteasyRequests.class.getName() + ".queryParameters";

    /** A URI rewrite gives the request a new UriInfo, which parses the query string again. */
    private record QueryParameters(UriInfo uriInfo, MultivaluedMap<String, String> map) {
    }

    private ResteasyRequests() {
    }

    /** The RESTEasy Reactive context under a JAX-RS one, or null for a synthetic context. */
    public static ResteasyReactiveRequestContext of(ContainerRequestContext ctx) {
        return ctx instanceof ResteasyReactiveContainerRequestContext container
                && container.getServerRequestContext() instanceof ResteasyReactiveRequestContext request
                ? request : null;
    }

    /**
     * {@link jakarta.ws.rs.core.UriInfo#getQueryParameters()} read once per request, since each call copies
     * the map. A request with no query string gets a shared empty map.
     */
    public static MultivaluedMap<String, String> queryParameters(ContainerRequestContext ctx) {
        ResteasyReactiveRequestContext request = of(ctx);
        return request != null ? queryParameters(request) : ctx.getUriInfo().getQueryParameters();
    }

    /** {@link #queryParameters(ContainerRequestContext)} for a {@link UriInfo} injected into a bean. */
    public static MultivaluedMap<String, String> queryParameters(UriInfo uriInfo) {
        ResteasyReactiveRequestContext request = CurrentRequestManager.get();
        return request != null ? queryParameters(request) : uriInfo.getQueryParameters();
    }

    private static MultivaluedMap<String, String> queryParameters(ResteasyReactiveRequestContext request) {
        if (request.serverRequest().query() == null) {
            return NO_QUERY;
        }
        UriInfo uriInfo = request.getUriInfo();
        if (request.getProperty(QUERY_PARAMETERS) instanceof QueryParameters read && read.uriInfo() == uriInfo) {
            return read.map();
        }
        MultivaluedMap<String, String> map = uriInfo.getQueryParameters();
        request.setProperty(QUERY_PARAMETERS, new QueryParameters(uriInfo, map));
        return map;
    }
}
