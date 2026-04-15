package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
public class ServiceEnabledFilter implements ContainerRequestFilter {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final Pattern AUTH_SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

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
            ctx.abortWith(disabledResponse(request));
        }
    }

    private ResolvedRequest resolveService(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target != null) {
            return catalog.byTarget(target)
                    .map(descriptor -> new ResolvedRequest(
                            descriptor.externalKey(),
                            inferProtocol(ctx).orElse(ServiceProtocol.JSON)))
                    .orElse(null);
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth != null) {
            Matcher m = AUTH_SERVICE_PATTERN.matcher(auth);
            if (m.find()) {
                return catalog.byCredentialScope(m.group(1).toLowerCase())
                        .map(descriptor -> new ResolvedRequest(
                                descriptor.externalKey(),
                                inferProtocol(ctx).orElse(descriptor.defaultProtocol())))
                        .orElse(null);
            }
        }

        return catalog.byResourceClass(resourceClass())
                .map(descriptor -> new ResolvedRequest(descriptor.externalKey(), descriptor.defaultProtocol()))
                .orElse(null);
    }

    private Class<?> resourceClass() {
        return resourceInfo != null ? resourceInfo.getResourceClass() : null;
    }

    private java.util.Optional<ServiceProtocol> inferProtocol(ContainerRequestContext ctx) {
        String contentType = ctx.getMediaType() != null ? ctx.getMediaType().toString() : "";
        if (contentType.contains("cbor")) {
            return java.util.Optional.of(ServiceProtocol.CBOR);
        }
        if (contentType.contains("x-www-form-urlencoded")) {
            return java.util.Optional.of(ServiceProtocol.QUERY);
        }
        if (ctx.getHeaderString("X-Amz-Target") != null) {
            return java.util.Optional.of(ServiceProtocol.JSON);
        }
        String accept = ctx.getHeaderString("Accept");
        if (accept != null && accept.contains("cbor")) {
            return java.util.Optional.of(ServiceProtocol.CBOR);
        }
        return java.util.Optional.empty();
    }

    private Response disabledResponse(ResolvedRequest request) {
        String message = "Service " + request.serviceKey() + " is not enabled.";

        if (request.protocol() == ServiceProtocol.CBOR) {
            try {
                byte[] errBytes = CBOR_MAPPER.writeValueAsBytes(
                        new AwsErrorResponse("ServiceNotAvailableException", message));
                return Response.status(400)
                        .header("smithy-protocol", "rpc-v2-cbor")
                        .header(AwsHeader.HEADER_X_AMAZON_QUERY_ERROR, "ServiceNotAvailableException;Sender")
                        .type("application/cbor")
                        .entity(errBytes)
                        .build();
            } catch (Exception ignored) {
                return Response.status(400).build();
            }
        }

        if (request.protocol() == ServiceProtocol.JSON || request.protocol() == ServiceProtocol.REST_JSON) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse("ServiceNotAvailableException", message))
                    .build();
        }

        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "ServiceNotAvailableException")
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", java.util.UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(400).entity(xml).type(MediaType.APPLICATION_XML).build();
    }

    private record ResolvedRequest(String serviceKey, ServiceProtocol protocol) {
    }
}
