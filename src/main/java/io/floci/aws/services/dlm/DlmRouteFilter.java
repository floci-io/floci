package io.floci.aws.services.dlm;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites DLM policy requests to an internal prefix before JAX-RS route matching.
 * DLM and IoT expose the same {@code /policies} paths, so their public routes
 * cannot both be registered directly.
 */
@Provider
@PreMatching
public class DlmRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_dlm";

    private final SignedServiceRoute route;

    @Inject
    public DlmRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "dlm", INTERNAL_PREFIX, SignedServiceRoute.under("/policies"));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
