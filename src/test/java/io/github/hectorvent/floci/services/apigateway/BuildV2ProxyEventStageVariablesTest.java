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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildV2ProxyEventStageVariablesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/echo"));

        controller = new ApiGatewayExecuteController(
                null, null, null, null,
                regionResolver, MAPPER, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null, null, null);
    }

    @Test
    void includesConfiguredStageVariables() throws Exception {
        JsonNode event = buildEvent(Map.of("mode", "production", "region", "local"));

        assertEquals("production", event.path("stageVariables").path("mode").asText());
        assertEquals("local", event.path("stageVariables").path("region").asText());
    }

    @Test
    void rendersNullWithoutConfiguredStageVariables() throws Exception {
        JsonNode event = buildEvent(Map.of());

        assertTrue(event.has("stageVariables"));
        assertTrue(event.get("stageVariables").isNull());
    }

    private JsonNode buildEvent(Map<String, String> stageVariables) throws Exception {
        return MAPPER.readTree(controller.buildV2ProxyEvent(
                "GET", "/echo", "GET /echo", "abc123", "us-east-1", "test",
                headers, uriInfo, null, "req-1", null, null, null, null, stageVariables));
    }
}
