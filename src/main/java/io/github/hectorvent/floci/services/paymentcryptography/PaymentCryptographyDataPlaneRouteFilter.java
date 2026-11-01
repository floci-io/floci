package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

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
        this.route = new SignedServiceRoute(catalog, "payment-cryptography-data", INTERNAL_PREFIX,
                SignedServiceRoute.under("/mac")
                        .or(SignedServiceRoute.under("/pindata"))
                        .or(SignedServiceRoute.under("/cardvalidationdata"))
                        .or(SignedServiceRoute.under("/cryptogram")));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
