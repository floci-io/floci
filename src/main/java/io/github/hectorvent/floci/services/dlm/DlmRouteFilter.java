package io.github.hectorvent.floci.services.dlm;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Rewrites DLM policy requests to an internal prefix before JAX-RS route matching.
 * DLM and IoT expose the same {@code /policies} paths, so their public routes
 * cannot both be registered directly.
 */
@Provider
@PreMatching
public class DlmRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_dlm";
    private static final String DLM = "dlm";
    private static final String POLICIES_PATH = "/policies";

    private final ResolvedServiceCatalog catalog;

    @Inject
    public DlmRouteFilter(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        if (!normalizedPath.equals(POLICIES_PATH)
                && !normalizedPath.startsWith(POLICIES_PATH + "/")) {
            return;
        }

        boolean targetsDlm = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .flatMap(catalog::byCredentialScope)
                .map(ServiceDescriptor::externalKey)
                .filter(DLM::equals)
                .isPresent();
        if (!targetsDlm) {
            return;
        }

        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath(INTERNAL_PREFIX + normalizedPath)
                .build());
    }
}
