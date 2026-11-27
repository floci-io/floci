package io.floci.aws.services.rolesanywhere;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.function.Predicate;

/**
 * Rewrites IAM Roles Anywhere control-plane requests to an internal prefix before JAX-RS route matching.
 *
 * <p>Every Roles Anywhere path is a literal first segment, and S3's path-style routes claim any first segment as a
 * bucket, so a request for {@code /trustanchors} would otherwise read as bucket {@code trustanchors}. The rewrite is
 * keyed on the SigV4 credential scope, so a request signed for S3 keeps its path and reaches S3: only a request
 * signed for {@code rolesanywhere} is moved. {@code /sessions}, the credential exchange, is not served yet.
 */
@Provider
@PreMatching
public class RolesAnywhereRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_rolesanywhere";

    private static final Predicate<String> PUBLIC_PATHS = SignedServiceRoute.under("/trustanchors")
            .or(SignedServiceRoute.under("/trustanchor"))
            .or(SignedServiceRoute.under("/profiles"))
            .or(SignedServiceRoute.under("/profile"))
            .or(SignedServiceRoute.under("/crls"))
            .or(SignedServiceRoute.under("/crl"))
            .or(SignedServiceRoute.under("/subjects"))
            .or(SignedServiceRoute.under("/subject"))
            .or(SignedServiceRoute.under("/TagResource"))
            .or(SignedServiceRoute.under("/UntagResource"))
            .or(SignedServiceRoute.under("/ListTagsForResource"))
            .or(SignedServiceRoute.under("/put-notifications-settings"))
            .or(SignedServiceRoute.under("/reset-notifications-settings"));

    private final SignedServiceRoute route;

    @Inject
    public RolesAnywhereRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "rolesanywhere", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
