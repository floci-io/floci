package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.s3.S3Controller;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Stops a known REST JSON service request from being handled by S3's path-style wildcard routes.
 * This runs after route matching so {@link ResourceInfo} identifies a genuine S3 endpoint match,
 * complementing the pre-matching unknown-scope guard in {@link AwsProtocolClaimFilter}.
 */
@Provider
@Priority(Priorities.ENTITY_CODER + 100)
public class AwsRestRouteScopeFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AwsRestRouteScopeFilter.class);

    @Context
    ResourceInfo resourceInfo;

    private final ResolvedServiceCatalog catalog;
    private final jakarta.inject.Provider<EmulatorConfig> configProvider;

    @Inject
    public AwsRestRouteScopeFilter(ResolvedServiceCatalog catalog,
                                   jakarta.inject.Provider<EmulatorConfig> configProvider) {
        this.catalog = catalog;
        this.configProvider = configProvider;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        Object claimValue = ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        if (!(claimValue instanceof ProtocolClaim claim) || claim.protocol() != WireProtocol.REST
                || resourceInfo == null || resourceInfo.getResourceClass() != S3Controller.class) {
            return;
        }

        SigV4CredentialScope.serviceName(ctx.getHeaderString("Authorization"))
                .flatMap(catalog::byCredentialScope)
                .filter(descriptor -> descriptor.supportsProtocol(ServiceProtocol.REST_JSON))
                .filter(descriptor -> !"s3".equals(descriptor.externalKey()))
                .ifPresent(descriptor -> rejectS3Fallthrough(ctx, descriptor));
    }

    private void rejectS3Fallthrough(ContainerRequestContext ctx, ServiceDescriptor descriptor) {
        if (!configProvider.get().protocols().rejectUnknownServiceScope()) {
            LOG.debugv("Known REST JSON service {0} matched an S3 wildcard route; rejection disabled by config",
                    descriptor.externalKey());
            return;
        }
        String method = ctx.getMethod();
        String path = ctx.getUriInfo().getPath();
        LOG.infov("Rejecting {0} request that matched an S3 wildcard route: {1} {2}",
                descriptor.externalKey(), method, path);
        ctx.abortWith(AwsProtocolClaimFilter.unknownOperationResponse(404,
                "Unknown operation: " + method + " " + path));
    }
}
