package io.floci.aws.services.paymentcryptography;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * The path prefixes the data plane's routes sit under.
     *
     * <p>The routes are the ones the model names, and every one of them starts with one of
     * these. A route whose prefix is not here is not rewritten, so it never reaches its
     * controller, and the request is answered by whichever controller claims it instead.
     * The test reads the generated routes and holds this list to them.
     */
    static final Set<String> CONTESTED_PATHS = Set.of(
            "/keys",
            "/mac",
            "/macemvpinchange",
            "/pindata",
            "/cardvalidationdata",
            "/cryptogram",
            "/keymaterial",
            "/as2805kekvalidation");

    private final SignedServiceRoute route;

    @Inject
    public PaymentCryptographyDataPlaneRouteFilter(ResolvedServiceCatalog catalog) {
        // Both planes of the service are signed for the same scope, so the
        // path says which plane a request is for, and the control plane never
        // uses these paths. A client that signs for the data plane's own scope
        // is served as well, so that either scope reaches the data plane.
        this.route = new SignedServiceRoute(catalog,
                Set.of("paymentcryptography", "payment-cryptography-data"), INTERNAL_PREFIX,
                under(CONTESTED_PATHS));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }

    /**
     * The predicate that says a path is one of the service's contested routes.
     *
     * @param roots the path prefixes the routes sit under
     * @return the predicate
     */
    private static Predicate<String> under(Set<String> roots) {
        return roots.stream()
                .map(SignedServiceRoute::under)
                .reduce(Predicate::or)
                .orElseThrow(() -> new IllegalStateException("the data plane has no routes"));
    }
}
