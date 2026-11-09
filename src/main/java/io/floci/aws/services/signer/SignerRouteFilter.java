package io.floci.aws.services.signer;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.function.Predicate;

/**
 * Rewrites Signer requests to an internal prefix before JAX-RS route matching.
 *
 * <p>Signer's paths are literal and S3's path-style routes claim any first segment as a bucket, so a
 * request for {@code /signing-profiles} would otherwise read as bucket {@code signing-profiles}. The
 * rewrite is keyed on the SigV4 credential scope, so a request signed for S3 keeps its path and
 * reaches S3: only a request signed for {@code signer} is moved.
 */
@Provider
@PreMatching
public class SignerRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_signer";

    private static final Predicate<String> PUBLIC_PATHS = SignedServiceRoute.under("/signing-profiles")
            .or(SignedServiceRoute.under("/signing-jobs"))
            .or(SignedServiceRoute.under("/signing-platforms"))
            .or(SignedServiceRoute.under("/revocations"));

    private final SignedServiceRoute route;

    @Inject
    public SignerRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "signer", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
