package io.floci.aws.services.appsync;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Rewrites AppSync Events API management requests to an internal prefix before JAX-RS route
 * matching.
 *
 * <p>AppSync's {@code CreateApi}, {@code ListApis}, {@code GetApi}, {@code UpdateApi} and
 * {@code DeleteApi} live on {@code /v2/apis} and {@code /v2/apis/{apiId}}, the same paths API
 * Gateway v2 serves. A request signed for {@code appsync} is moved onto {@link #INTERNAL_PREFIX}
 * and reaches {@link AppSyncApiController}; any other request keeps its path and reaches API
 * Gateway. Deeper paths such as {@code /v2/apis/{apiId}/channelNamespaces} are not claimed: no
 * API Gateway route shares them, so they stay on the public path.
 */
@Provider
@PreMatching
public class AppSyncEventsRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_appsync";

    private static final Pattern API_PATHS = Pattern.compile("/v2/apis(/[^/]+)?/?");

    private static final Predicate<String> PUBLIC_PATHS = path -> API_PATHS.matcher(path).matches();

    /**
     * The methods AppSync serves on these paths. API Gateway's {@code PUT /v2/apis} and
     * {@code PUT /v2/apis/{apiId}} (ImportApi, ReimportApi) have no AppSync counterpart, so an
     * appsync-signed {@code PUT} is left on the public path, where the route-collision guard
     * answers it as an unknown operation.
     */
    private static final Set<String> SERVED_METHODS = Set.of("GET", "POST", "DELETE");

    private static final Predicate<String> INTERNAL_PATHS = SignedServiceRoute.under(INTERNAL_PREFIX);

    private final SignedServiceRoute route;

    @Inject
    public AppSyncEventsRouteFilter(ResolvedServiceCatalog catalog) {
        this.route = new SignedServiceRoute(catalog, "appsync", INTERNAL_PREFIX, PUBLIC_PATHS);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (SERVED_METHODS.contains(ctx.getMethod()) || INTERNAL_PATHS.test(normalizedPath)) {
            route.apply(ctx);
        }
    }
}
