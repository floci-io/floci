package io.floci.aws.services.glue.iceberg;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes AWS Glue's Iceberg REST endpoint, {@code /iceberg}, which S3's path-style routes would otherwise take for a
 * bucket named {@code iceberg}: a request signed for {@code glue} is moved onto {@link #INTERNAL_PREFIX}, where
 * {@link GlueIcebergController} serves it.
 */
@Provider
@PreMatching
@Priority(5000)
public class GlueIcebergRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_glueiceberg";
    static final String ICEBERG_PATH = "/iceberg";

    private final SignedServiceRoute route;

    @Inject
    public GlueIcebergRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "glue", INTERNAL_PREFIX, SignedServiceRoute.under(ICEBERG_PATH));
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
