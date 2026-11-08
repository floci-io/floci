package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Rewrites the data plane's two-segment paths to an internal prefix before JAX-RS route
 * matching. Registered directly, {@code /mac/generate} and the rest would outrank S3's
 * {@code /{bucket}/{key}} routes, so a path-style request for a bucket of that name would
 * reach the data plane. Only a request signed for the data plane is rewritten; anything else
 * falls through to S3.
 */
@Provider
@PreMatching
public class PaymentCryptographyDataPlaneRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_paymentcryptography";

    private final SignedServiceRoute route;

    @Inject
    public PaymentCryptographyDataPlaneRouteFilter(ResolvedServiceCatalog catalog) {
        // Both planes of the service are signed for the same scope, so the
        // path says which plane a request is for, and the control plane never
        // uses these paths. A client that signs for the data plane's own scope
        // is served as well, so that either scope reaches the data plane.
        this.route = new SignedServiceRoute(catalog,
                Set.of("paymentcryptography", "payment-cryptography-data"), INTERNAL_PREFIX,
                SignedServiceRoute.under("/mac")
                        .or(SignedServiceRoute.under("/pindata"))
                        .or(SignedServiceRoute.under("/cardvalidationdata"))
                        .or(SignedServiceRoute.under("/cryptogram"))
                        .or(SignedServiceRoute.under("/keys")));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
