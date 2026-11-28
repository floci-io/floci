package io.floci.aws.core.common;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

@Provider
public class ServiceEnabledFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    private final ServiceConfigAccess serviceConfigAccess;
    private final ResolvedServiceCatalog catalog;

    @Inject
    public ServiceEnabledFilter(ServiceConfigAccess serviceConfigAccess, ResolvedServiceCatalog catalog) {
        this.serviceConfigAccess = serviceConfigAccess;
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        ResolvedRequest request = resolveService(ctx);
        if (request == null) {
            return;
        }
        if (!serviceConfigAccess.isEnabled(request.serviceKey())) {
            ctx.abortWith(disabledResponse(ctx, request));
        }
    }

    private ResolvedRequest resolveService(ContainerRequestContext ctx) {
        ProtocolClaim claim = (ProtocolClaim) ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        if (claim != null && claim.service() != null) {
            return new ResolvedRequest(claim.service().externalKey(), protocolFor(claim, claim.service()));
        }
        // A claimed RPC request whose service is unknown (unmatched X-Amz-Target
        // or rpcv2 service name) stays unresolved so the protocol controllers
        // keep producing their own not-found responses.
        if (claim != null && claim.protocol() != WireProtocol.REST
                && claim.protocol() != WireProtocol.AWS_QUERY) {
            return null;
        }

        Optional<ServiceDescriptor> resourceMatch = catalog.byResourceClass(resourceClass());
        if (resourceMatch.isPresent()) {
            ServiceDescriptor descriptor = resourceMatch.get();
            return new ResolvedRequest(descriptor.externalKey(), descriptor.defaultProtocol());
        }

        return SignedServiceRoute.signingService(ctx)
                .flatMap(catalog::byCredentialScope)
                .map(descriptor -> new ResolvedRequest(
                        descriptor.externalKey(), protocolFor(claim, descriptor)))
                .orElse(null);
    }

    private ServiceProtocol protocolFor(ProtocolClaim claim, ServiceDescriptor descriptor) {
        if (claim != null && claim.protocol().legacy() != null) {
            return claim.protocol().legacy();
        }
        return descriptor.defaultProtocol();
    }

    private Class<?> resourceClass() {
        return resourceInfo != null ? resourceInfo.getResourceClass() : null;
    }

    private Response disabledResponse(ContainerRequestContext ctx, ResolvedRequest request) {
        String message = "Service " + request.serviceKey() + " is not enabled.";

        if (request.protocol() == ServiceProtocol.CBOR) {
            return ProtocolErrorResponses.cbor(ctx, "ServiceNotAvailableException", message, 400);
        }

        if (request.protocol() == ServiceProtocol.JSON || request.protocol() == ServiceProtocol.REST_JSON) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "ServiceNotAvailableException")
                    .header("x-amzn-query-error", "ServiceNotAvailableException;Sender")
                    .entity(new AwsErrorResponse("ServiceNotAvailableException", message))
                    .build();
        }

        return ProtocolErrorResponses.queryXml("ServiceNotAvailableException", message, 400);
    }

    private record ResolvedRequest(String serviceKey, ServiceProtocol protocol) {
    }
}
