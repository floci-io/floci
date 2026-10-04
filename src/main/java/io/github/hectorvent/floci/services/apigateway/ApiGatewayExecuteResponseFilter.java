package io.github.hectorvent.floci.services.apigateway;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

/** Gives REST execution responses the request identifiers used by authorizers and integrations. */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class ApiGatewayExecuteResponseFilter implements ContainerResponseFilter {
    private final ApiGatewayExecuteRouteContext routeContext;

    @Inject
    public ApiGatewayExecuteResponseFilter(ApiGatewayExecuteRouteContext routeContext) {
        this.routeContext = routeContext;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!routeContext.isRestApiRoute()) {
            return;
        }
        MultivaluedMap<String, Object> headers = response.getHeaders();
        headers.keySet().removeIf(name -> name.equalsIgnoreCase("x-amzn-RequestId") || name.equalsIgnoreCase("x-amz-apigw-id"));
        headers.putSingle("x-amzn-RequestId", routeContext.requestIdFromOverride(request.getHeaderString("x-amzn-RequestId")));
        headers.putSingle("x-amz-apigw-id", routeContext.extendedRequestId());
    }
}
