package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.util.Optional;

/** Adds the modeled AWS error type header to REST JSON error responses. */
@Provider
public class AwsRestJsonErrorHeaderFilter implements ContainerResponseFilter {

    private static final String ERROR_TYPE_HEADER = "X-Amzn-Errortype";

    @Context
    ResourceInfo resourceInfo;

    private final ResolvedServiceCatalog catalog;

    @Inject
    public AwsRestJsonErrorHeaderFilter(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (response.getStatus() < 400 || response.getHeaderString(ERROR_TYPE_HEADER) != null) {
            return;
        }
        Object claimValue = request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        if (claimValue instanceof ProtocolClaim claim && claim.protocol() != WireProtocol.REST) {
            return;
        }
        if (resolveDescriptor(request)
                .filter(descriptor -> descriptor.supportsProtocol(ServiceProtocol.REST_JSON))
                .isEmpty()) {
            return;
        }

        errorType(response.getEntity()).ifPresent(type ->
                response.getHeaders().putSingle(ERROR_TYPE_HEADER, normalize(type)));
    }

    private Optional<ServiceDescriptor> resolveDescriptor(ContainerRequestContext request) {
        Object claimValue = request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY);
        if (claimValue instanceof ProtocolClaim claim && claim.service() != null) {
            return Optional.of(claim.service());
        }
        if (resourceInfo != null && resourceInfo.getResourceClass() != null) {
            Optional<ServiceDescriptor> descriptor = catalog.byResourceClass(resourceInfo.getResourceClass());
            if (descriptor.isPresent()) {
                return descriptor;
            }
        }
        return SigV4CredentialScope.serviceName(request.getHeaderString("Authorization"))
                .flatMap(catalog::byCredentialScope);
    }

    private Optional<String> errorType(Object entity) {
        if (entity instanceof AwsErrorResponse error) {
            return Optional.ofNullable(error.type());
        }
        if (entity instanceof JsonNode node && node.hasNonNull("__type")) {
            return Optional.of(node.path("__type").asText());
        }
        return Optional.empty();
    }

    private String normalize(String type) {
        int hash = type.lastIndexOf('#');
        String normalized = hash >= 0 ? type.substring(hash + 1) : type;
        int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(0, colon) : normalized;
    }
}
