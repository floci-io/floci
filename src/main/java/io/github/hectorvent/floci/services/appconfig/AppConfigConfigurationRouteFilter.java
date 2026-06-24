package io.github.hectorvent.floci.services.appconfig;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Pre-matching filter that disambiguates {@code GET /configuration} between the
 * AppConfigData runtime API (GetLatestConfiguration) and an S3 bucket literally
 * named {@code configuration} (issue #1294).
 *
 * <p>A genuine AppConfigData GetLatestConfiguration request always carries a
 * {@code configuration_token} query parameter, while a path-style S3
 * ListObjectsV2 request does not. When the token is present the request is
 * rewritten onto the internal AppConfigData path so {@link AppConfigDataController}
 * handles it; otherwise the bare {@code /configuration} path is left untouched
 * and falls through to S3's {@code /{bucket}} route.
 *
 * <p>The internal path contains an underscore, which S3 bucket names forbid, so
 * it can never collide with a real bucket. This mirrors the existing routing
 * filters ({@code SqsQueueUrlRouterFilter}, {@code LambdaUrlRoutingFilter}) that
 * resolve path collisions before JAX-RS resource matching.
 */
@Provider
@PreMatching
public class AppConfigConfigurationRouteFilter implements ContainerRequestFilter {

    /** Internal-only path that {@link AppConfigDataController} binds for GetLatestConfiguration. */
    static final String INTERNAL_PATH = "/_appconfigdata_configuration";

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!"GET".equals(ctx.getMethod())) {
            return;
        }
        UriInfo uriInfo = ctx.getUriInfo();
        String path = uriInfo.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!"configuration".equals(path)) {
            return;
        }
        String token = uriInfo.getQueryParameters().getFirst("configuration_token");
        if (token == null || token.isBlank()) {
            // No token: this is an S3 path-style request to a bucket named
            // "configuration". Leave the path so it matches S3's /{bucket} route.
            return;
        }
        URI rewritten = uriInfo.getRequestUriBuilder().replacePath(INTERNAL_PATH).build();
        ctx.setRequestUri(rewritten);
    }
}
