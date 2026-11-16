package io.floci.aws.services.emrcontainers;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.function.Predicate;

/**
 * Rewrites EMR on EKS requests to an internal prefix before JAX-RS route matching. Its paths are
 * literal ({@code /virtualclusters}, {@code /jobtemplates}, {@code /securityconfigurations}), which
 * S3's path-style routes would read as a bucket, so only a request signed for
 * {@code emr-containers} is moved and any other request keeps its path.
 */
@Provider
@PreMatching
@Priority(5000)
public class EmrContainersRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_emrcontainers";

    private static final Predicate<String> PUBLIC_PATHS = SignedServiceRoute.under("/virtualclusters")
            .or(SignedServiceRoute.under("/jobtemplates"))
            .or(SignedServiceRoute.under("/securityconfigurations"));

    private final SignedServiceRoute route;

    @Inject
    public EmrContainersRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "emr-containers", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
