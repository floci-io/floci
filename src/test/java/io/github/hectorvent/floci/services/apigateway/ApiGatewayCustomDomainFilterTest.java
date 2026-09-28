package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayCustomDomainFilterTest {

    private static final String DOMAIN = "api.example.com";
    private static final String API_ID = "abc123";

    @Test
    void recordsCustomDomainPathAsTheSignedPath() {
        ApiGatewayService service = serviceWithMapping("v1", "prod");
        RecordingRequest request = new RecordingRequest(
                DOMAIN + ".regional.local:4566",
                URI.create("http://" + DOMAIN + ".regional.local:4566/v1/iam?tenant=alpha"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayCustomDomainFilter(service, routeContext).filter(request.context());

        assertEquals("/execute-api/" + API_ID + "/prod/iam", request.routedUri().getRawPath());
        assertEquals("tenant=alpha", request.routedUri().getRawQuery());
        assertEquals("/v1/iam", routeContext.signedRequestPath());
    }

    @Test
    void recordsBareDomainPathAsTheSignedPath() {
        ApiGatewayService service = serviceWithMapping("(none)", "prod");
        RecordingRequest request = new RecordingRequest(
                DOMAIN, URI.create("http://" + DOMAIN + "/orders/42"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayCustomDomainFilter(service, routeContext).filter(request.context());

        assertEquals("/execute-api/" + API_ID + "/prod/orders/42", request.routedUri().getRawPath());
        assertEquals("/orders/42", routeContext.signedRequestPath());
    }

    @Test
    void leavesSignedPathUnsetWhenHostIsNotACustomDomain() {
        ApiGatewayService service = mock(ApiGatewayService.class);
        RecordingRequest request = new RecordingRequest(
                "localhost:4566", URI.create("http://localhost:4566/v1/iam"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayCustomDomainFilter(service, routeContext).filter(request.context());

        assertNull(request.routedUri());
        assertNull(routeContext.signedRequestPath());
    }

    @Test
    void leavesSignedPathUnsetWhenNoMappingMatches() {
        ApiGatewayService service = mock(ApiGatewayService.class);
        CustomDomain domain = new CustomDomain();
        domain.setDomainName(DOMAIN);
        when(service.findDomainByName(DOMAIN)).thenReturn(domain);
        RecordingRequest request = new RecordingRequest(
                DOMAIN, URI.create("http://" + DOMAIN + "/unmapped"));
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();

        new ApiGatewayCustomDomainFilter(service, routeContext).filter(request.context());

        assertNull(request.routedUri());
        assertNull(routeContext.signedRequestPath());
    }

    private static ApiGatewayService serviceWithMapping(String basePath, String stage) {
        ApiGatewayService service = mock(ApiGatewayService.class);
        CustomDomain domain = new CustomDomain();
        domain.setDomainName(DOMAIN);
        BasePathMapping mapping = new BasePathMapping(basePath, API_ID, stage);
        when(service.findDomainByRegionalHostname(DOMAIN + ".regional.local")).thenReturn(domain);
        when(service.findDomainByName(DOMAIN)).thenReturn(domain);
        when(service.resolveBasePathMapping(anyString(), anyString())).thenReturn(mapping);
        when(service.stripBasePath(anyString(), eq(mapping)))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0);
                    String prefix = "/" + basePath;
                    return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
                });
        return service;
    }

    private static final class RecordingRequest {
        private final String host;
        private final URI requestUri;
        private URI routedUri;

        private RecordingRequest(String host, URI requestUri) {
            this.host = host;
            this.requestUri = requestUri;
        }

        private ContainerRequestContext context() {
            return (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[] { ContainerRequestContext.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getHeaderString" -> "Host".equalsIgnoreCase((String) args[0]) ? host : null;
                        case "getUriInfo" -> uriInfo();
                        case "setRequestUri" -> {
                            routedUri = (URI) args[0];
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private URI routedUri() {
            return routedUri;
        }

        private UriInfo uriInfo() {
            return (UriInfo) Proxy.newProxyInstance(
                    UriInfo.class.getClassLoader(),
                    new Class<?>[] { UriInfo.class },
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getRequestUri" -> requestUri;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
