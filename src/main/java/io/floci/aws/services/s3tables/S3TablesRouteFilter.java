package io.floci.aws.services.s3tables;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.function.Predicate;

/**
 * Rewrites S3 Tables requests to an internal prefix before JAX-RS route matching.
 *
 * <p>S3 Tables' paths begin with a literal segment, and S3's path-style routes claim any first
 * segment as a bucket, so a request for object {@code k} in bucket {@code tables} would otherwise
 * reach ListTables. The rewrite is keyed on the SigV4 credential scope, so a request signed for
 * S3 keeps its path and reaches S3: only a request signed for {@code s3tables} is moved.
 */
@Provider
@PreMatching
public class S3TablesRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_s3tables";

    private static final Predicate<String> PUBLIC_PATHS = SignedServiceRoute.under("/buckets")
            .or(SignedServiceRoute.under("/get-table"))
            .or(SignedServiceRoute.under("/namespaces"))
            .or(SignedServiceRoute.under("/tables"));

    private final SignedServiceRoute route;

    @Inject
    public S3TablesRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "s3tables", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        route.apply(ctx);
    }
}
