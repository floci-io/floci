package io.floci.aws.services.glue.iceberg;

import io.floci.aws.core.common.AccountContextFilter;
import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Routes AWS Glue's Iceberg REST endpoint, {@code /iceberg}, which S3's path-style routes would otherwise take for a
 * bucket named {@code iceberg}: a request signed for {@code glue} is moved onto {@link #INTERNAL_PREFIX}, where
 * {@link GlueIcebergController} serves it. So is one bearing a token {@link GlueIcebergTokens} issued, as the account
 * and in the Region the token acts as; a bearer token it did not issue is no Glue request.
 */
@Provider
@PreMatching
@Priority(5000)
public class GlueIcebergRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_glueiceberg";
    static final String ICEBERG_PATH = "/iceberg";

    private static final String BEARER = "Bearer ";

    private final SignedServiceRoute route;
    private final Predicate<String> icebergPaths = SignedServiceRoute.under(ICEBERG_PATH);
    // A @PreMatching filter is wired into the request pipeline at build time, so holding the tokens bean directly would
    // place it, and the SecureRandom it holds, in the native-image build heap. Resolve it per bearer request instead.
    private final Instance<GlueIcebergTokens> tokens;

    @Inject
    public GlueIcebergRouteFilter(ResolvedServiceCatalog catalog, Instance<GlueIcebergTokens> tokens) {
        this.route = new SignedServiceRoute(catalog, "glue", INTERNAL_PREFIX, icebergPaths);
        this.tokens = tokens;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String authorization = ctx.getHeaderString("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            String path = ctx.getUriInfo().getPath();
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            Optional<GlueIcebergTokens.Grant> grant = tokens.get()
                    .resolve(authorization.substring(BEARER.length()).trim());
            if (grant.isPresent() && icebergPaths.test(normalizedPath)) {
                ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                        .replacePath(INTERNAL_PREFIX + normalizedPath)
                        .build());
                ctx.setProperty(SignedServiceRoute.INTERNAL_PREFIX_PROPERTY, INTERNAL_PREFIX);
                ctx.setProperty(AccountContextFilter.PINNED_ACCOUNT_PROPERTY, grant.get().accountId());
                ctx.setProperty(AccountContextFilter.PINNED_REGION_PROPERTY, grant.get().region());
                return;
            }
        }
        route.apply(ctx);
    }
}
