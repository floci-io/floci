package io.floci.aws.services.guardduty;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites GuardDuty Malware Protection plan requests to an internal prefix before JAX-RS route
 * matching. Registered directly, {@code /malware-protection-plan} would outrank S3's
 * {@code /{bucket}} routes, so a path-style request for a bucket of that name would reach GuardDuty.
 * Only a request signed for GuardDuty is rewritten; anything else falls through to S3.
 */
@Provider
@PreMatching
public class GuardDutyRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_guardduty";
    static final String PLAN_PATH = "/malware-protection-plan";

    private final SignedServiceRoute route;

    @Inject
    public GuardDutyRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "guardduty", INTERNAL_PREFIX, SignedServiceRoute.under(PLAN_PATH));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
