package io.github.hectorvent.floci.services.resourceexplorer2;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Pre-matching filter that disambiguates Resource Explorer 2 endpoints from S3 Vectors
 * endpoints that share the same JAX-RS path names ({@code /CreateIndex}, {@code /GetIndex},
 * {@code /ListIndexes}, {@code /DeleteIndex}).
 *
 * <p>Both are REST-JSON services rooted at {@code /}, so JAX-RS cannot tell their identically
 * named operations apart. Floci resolves this the same way {@code AwsQueryController} resolves
 * query-protocol services: by the SigV4 credential scope, looked up in the
 * {@link ResolvedServiceCatalog}. When the scope resolves to the Resource Explorer 2 service,
 * the conflicting path is rewritten to the {@code /re2/} prefix so it reaches
 * {@link ResourceExplorer2Controller}; otherwise it falls through to the S3 Vectors controller.
 *
 * <p>All other Resource Explorer 2 paths (e.g. {@code /ListResources}, {@code /Search}) are
 * unique across controllers and need no rewriting.
 */
@Provider
@PreMatching
public class ResourceExplorer2PathRewriteFilter implements ContainerRequestFilter {

    private static final String RESOURCE_EXPLORER_2 = "resource-explorer-2";

    // Mirror of the index operation paths declared by S3VectorsController. A colliding path missing
    // from this set falls through to S3 Vectors even for Resource Explorer 2 callers.
    private static final Set<String> AMBIGUOUS_PATHS = Set.of(
            "/CreateIndex",
            "/GetIndex",
            "/ListIndexes",
            "/DeleteIndex"
    );

    private final ResolvedServiceCatalog catalog;

    @Inject
    public ResourceExplorer2PathRewriteFilter(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String normalizedPath = path.startsWith("/") ? path : "/" + path;

        if (!AMBIGUOUS_PATHS.contains(normalizedPath)) {
            return;
        }

        boolean targetsResourceExplorer2 =
                SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                        .flatMap(catalog::byCredentialScope)
                        .map(ServiceDescriptor::externalKey)
                        .filter(RESOURCE_EXPLORER_2::equals)
                        .isPresent();

        if (!targetsResourceExplorer2) {
            return;
        }

        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath("/re2" + normalizedPath)
                .build());
    }
}
