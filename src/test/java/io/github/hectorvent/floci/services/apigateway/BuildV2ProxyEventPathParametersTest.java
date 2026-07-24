package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} populates
 * {@code pathParameters} in the Lambda event payload for HTTP API (V2) integrations.
 */
class BuildV2ProxyEventPathParametersTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(headers.getHeaderString("User-Agent")).thenReturn(null);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/stage/trpc/health"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, null
        );
    }

    @Test
    void greedyProxyRoutePopulatesPathParameters() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "POST", "/trpc/health", "ANY /{proxy+}",
                "abc123", "$default", headers, uriInfo, null, "req-1");
        JsonNode event = new ObjectMapper().readTree(json);
        assertTrue(event.has("pathParameters"), "pathParameters must be present");
        assertEquals("trpc/health", event.get("pathParameters").get("proxy").asText());
    }

    @Test
    void namedParamRoutePopulatesPathParameters() throws Exception {
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/stage/users/42"));
        String json = controller.buildV2ProxyEvent(
                "GET", "/users/42", "GET /users/{id}",
                "abc123", "$default", headers, uriInfo, null, "req-2");
        JsonNode event = new ObjectMapper().readTree(json);
        assertTrue(event.has("pathParameters"), "pathParameters must be present");
        assertEquals("42", event.get("pathParameters").get("id").asText());
    }

    @Test
    void defaultRouteOmitsPathParameters() throws Exception {
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/stage/anything"));
        String json = controller.buildV2ProxyEvent(
                "GET", "/anything", "$default",
                "abc123", "$default", headers, uriInfo, null, "req-3");
        JsonNode event = new ObjectMapper().readTree(json);
        assertFalse(event.has("pathParameters"), "pathParameters must be absent for $default route");
    }
}
