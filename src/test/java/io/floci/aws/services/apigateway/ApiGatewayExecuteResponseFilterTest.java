package io.floci.aws.services.apigateway;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiGatewayExecuteResponseFilterTest {

    private final MultivaluedMap<String, Object> responseHeaders = new MultivaluedHashMap<>();

    @ParameterizedTest
    @ValueSource(strings = {"/", "/bench/k/1"})
    void leavesResponsesForOtherServicesAloneWithoutTouchingTheRouteContext(String path) {
        ApiGatewayExecuteRouteContext routeContext = mock(ApiGatewayExecuteRouteContext.class);

        new ApiGatewayExecuteResponseFilter(routeContext).filter(request(path), response());

        assertThat(responseHeaders, is(anEmptyMap()));
        verifyNoInteractions(routeContext);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/execute-api/abc/prod/x",
            "/_aws/execute-api/abc/prod/x",
            "/restapis/abc/prod/_user_request_/x"
    })
    void setsRequestIdentifiersOnRestApiExecution(String path) {
        ApiGatewayExecuteRouteContext routeContext = new ApiGatewayExecuteRouteContext();
        routeContext.routeToRestApi();

        new ApiGatewayExecuteResponseFilter(routeContext).filter(request(path), response());

        assertThat(responseHeaders.getFirst("x-amzn-RequestId"), is(notNullValue()));
        assertThat(responseHeaders.getFirst("x-amz-apigw-id"), is(notNullValue()));
    }

    @Test
    void leavesResponsesAloneForApiGatewayPathsThatAreNotRestRoutes() {
        new ApiGatewayExecuteResponseFilter(new ApiGatewayExecuteRouteContext())
                .filter(request("/restapis/abc"), response());

        assertThat(responseHeaders, is(anEmptyMap()));
    }

    private static ContainerRequestContext request(String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getUriInfo()).thenReturn(uriInfo);
        return request;
    }

    private ContainerResponseContext response() {
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getHeaders()).thenReturn(responseHeaders);
        return response;
    }
}
