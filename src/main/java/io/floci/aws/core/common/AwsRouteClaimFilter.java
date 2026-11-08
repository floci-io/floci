package io.floci.aws.core.common;

import io.floci.aws.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Claims, by its route, a request {@link AwsProtocolClaimFilter} served without a claim. With
 * {@code floci.protocols.strict-claiming} off, a request no protocol claims, such as one carrying an
 * unrecognised {@code Smithy-Protocol} value or an {@code X-Amz-Target} with a content type no
 * protocol uses, is logged and passes on to route matching, which serves it. Every check that reads
 * the claim would otherwise see a request of no protocol: the unsigned refusal let it through, and
 * IAM enforcement checked a REST request as the action its {@code X-Amz-Target} named rather than
 * its route's. A request claimed as REST also gets the check a claimed one gets before routing: one
 * signed for a service Floci does not serve is refused rather than answered by S3's routes.
 * <p>
 * Runs after route matching, so {@link ResourceInfo} names the route, and before every filter that
 * reads the claim: {@link AccountContextFilter}, which answers a request signed for an unknown Region
 * in the claim's error format, runs at {@code AUTHENTICATION - 100}, and the signature check at
 * {@link Priorities#AUTHENTICATION}.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 200)
public class AwsRouteClaimFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AwsRouteClaimFilter.class);

    @Context
    ResourceInfo resourceInfo;

    private final ProtocolClaimer claimer;
    private final ResolvedServiceCatalog catalog;
    // Lazily resolved: JAX-RS providers are instantiated before runtime config mappings exist.
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AwsRouteClaimFilter(ProtocolClaimer claimer, ResolvedServiceCatalog catalog,
                               jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.claimer = claimer;
        this.catalog = catalog;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Class<?> resourceClass = RoutedResource.resourceClass(resourceInfo);
        if (ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim || resourceClass == null) {
            return;
        }
        ClaimSignals signals = ClaimSignals.from(ctx);
        ProtocolClaim claim = claimer.routeClaim(resourceClass, signals, ctx.getUriInfo().getPathParameters());
        LOG.debugv("Claimed {0} {1} by its route: {2} service={3} operation={4}", ctx.getMethod(),
                ctx.getUriInfo().getPath(), claim.protocol(),
                claim.service() != null ? claim.service().externalKey() : null, claim.operation());
        ctx.setProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY, claim);
        if (claim.protocol() == WireProtocol.REST) {
            AwsProtocolClaimFilter.refuseUnknownServiceScope(ctx, signals, catalog, configProvider.get());
        }
    }
}
