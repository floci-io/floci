package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites EMR Serverless requests to an internal prefix before JAX-RS route matching. EMR
 * Serverless and AppConfig both serve {@code /applications}, so registered directly the two would
 * collide. Only a request signed for EMR Serverless is rewritten; anything else keeps its path and
 * reaches AppConfig.
 */
@Provider
@PreMatching
@Priority(5000)
public class EmrServerlessRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_emrserverless";
    static final String APPLICATIONS_PATH = "/applications";

    private final SignedServiceRoute route;

    @Inject
    public EmrServerlessRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "emr-serverless", INTERNAL_PREFIX,
                SignedServiceRoute.under(APPLICATIONS_PATH));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
