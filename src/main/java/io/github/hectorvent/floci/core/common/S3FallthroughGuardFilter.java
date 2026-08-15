package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.s3.S3Controller;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Rejects requests that JAX-RS matched into S3's path-style wildcard routes even though
 * their SigV4 credential scope names a different, catalog-known service.
 * <p>
 * The pre-matching guard in {@link AwsProtocolClaimFilter} covers scopes absent from the
 * catalog. A scope Floci does implement can still fall through: a registered REST service
 * whose specific operation path has no handler yet (Backup's audit framework routes, IoT
 * authorizers, Lambda code-signing configs, ...) matches S3's {@code /{bucket}} and
 * {@code /{bucket}/{key}} wildcards and fails with a misleading S3 XML error that the
 * caller's rest-json SDK cannot deserialize. This filter runs post-matching, so it fires
 * only when S3's controller actually claimed the request.
 * <p>
 * Same positive-identification contract as the pre-matching guard: only a parseable SigV4
 * header scope that resolves to a catalog service other than S3 triggers rejection.
 * Unsigned, presigned, Bearer/Basic and SigV4a requests are untouched, and the shared
 * {@code floci.protocols.reject-unknown-service-scope} switch disables both guards.
 */
@Provider
public class S3FallthroughGuardFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(S3FallthroughGuardFilter.class);

    @Context
    ResourceInfo resourceInfo;

    private final ResolvedServiceCatalog catalog;
    // Lazily resolved: JAX-RS providers are instantiated before runtime config
    // mappings exist (same pattern as AwsProtocolClaimFilter).
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public S3FallthroughGuardFilter(ResolvedServiceCatalog catalog,
                                    jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.catalog = catalog;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (resourceInfo == null || resourceInfo.getResourceClass() != S3Controller.class) {
            return;
        }
        Optional<String> scope = SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"));
        if (scope.isEmpty()) {
            return;
        }
        Optional<ServiceDescriptor> descriptor = catalog.byCredentialScope(scope.get());
        if (descriptor.isEmpty() || "s3".equals(descriptor.get().externalKey())) {
            // Unknown scopes are the pre-matching guard's call (it also honors the off
            // switch); s3 and its s3express alias own these routes legitimately.
            return;
        }
        String method = ctx.getMethod();
        String path = "/" + ctx.getUriInfo().getPath();
        if (!configProvider.get().protocols().rejectUnknownServiceScope()) {
            LOG.debugv("S3 fallthrough from service scope {0} on {1} {2}, rejection disabled by config",
                    scope.get(), method, path);
            return;
        }
        LOG.infov("Rejecting {0}-scoped request that fell through to S3 routes: {1} {2}",
                scope.get(), method, path);
        ctx.abortWith(AwsProtocolClaimFilter.unknownOperationResponse(404,
                "Unknown operation: " + method + " " + path));
    }
}
