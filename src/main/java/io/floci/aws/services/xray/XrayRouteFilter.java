package io.floci.aws.services.xray;

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
 * Rewrites X-Ray requests to an internal prefix before JAX-RS route matching.
 *
 * <p>Every X-Ray operation is a {@code POST} on a literal path ({@code /TraceSegments},
 * {@code /Traces}, {@code /ServiceGraph}), which S3's path-style routes would read as a bucket,
 * so a request signed for {@code xray} is moved onto {@link #INTERNAL_PREFIX} and any other request
 * keeps its path. Only the paths this service serves are claimed, so an X-Ray route it does not
 * answer yet still reaches whatever else claims it.
 */
@Provider
@PreMatching
public class XrayRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_xray";

    /** The literal paths this service serves, as AWS declares them for each operation. */
    private static final Set<String> SERVED_PATHS = Set.of("/TraceSegments", "/Traces",
            "/TraceSummaries", "/ServiceGraph", "/TraceGraph", "/GetSamplingRules",
            "/CreateSamplingRule", "/UpdateSamplingRule", "/DeleteSamplingRule",
            "/SamplingTargets", "/TelemetryRecords");

    private static final Predicate<String> PUBLIC_PATHS = SERVED_PATHS::contains;

    private final SignedServiceRoute route;

    @Inject
    public XrayRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "xray", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
