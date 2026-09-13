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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REST (V1) pathParameters must contain exactly the parameters declared by the matched resource.
 * AWS only emits a "proxy" key when the matched resource itself is greedy ({proxy+}); for a plain
 * parameterised resource such as /datasets/{datasetId} it sends {"datasetId": "..."} and nothing
 * else. Integrations that validate the event against a strict schema
 * (additionalProperties: false) reject the request outright when an undeclared key appears.
 */
class ProxyEventPathParametersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(
                new URI("http://localhost:4566/execute-api/api1/v1/datasets/abc123"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, MAPPER, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null);
    }

    private JsonNode pathParametersFor(String path, String resourcePath) throws Exception {
        return MAPPER.readTree(controller.buildProxyEvent(
                        "us-east-1", "api1", "GET", path, resourcePath, "res1", "v1", null,
                        headers, uriInfo, null, "req-1", null, null, null, null))
                .get("pathParameters");
    }

    private static List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        java.util.Collections.sort(keys);
        return keys;
    }

    @Test
    void nonGreedyResourceEmitsOnlyItsDeclaredPathParameters() throws Exception {
        JsonNode pp = pathParametersFor("/datasets/abc123", "/datasets/{datasetId}");

        assertEquals(List.of("datasetId"), keysOf(pp),
                "a non-greedy resource must not receive an undeclared \"proxy\" key");
        assertEquals("abc123", pp.get("datasetId").asText());
    }

    @Test
    void greedyResourceReceivesTheRemainderAfterItsLiteralPrefix() throws Exception {
        JsonNode pp = pathParametersFor("/files/a/b/c", "/files/{proxy+}");

        assertEquals(List.of("proxy"), keysOf(pp));
        assertEquals("a/b/c", pp.get("proxy").asText(),
                "the greedy value is the remainder, not the whole request path");
    }

    @Test
    void rootGreedyResourceReceivesTheWholePath() throws Exception {
        JsonNode pp = pathParametersFor("/files/a/b", "/{proxy+}");

        assertEquals(List.of("proxy"), keysOf(pp));
        assertEquals("files/a/b", pp.get("proxy").asText());
    }

    @Test
    void greedyParameterIsNamedByTheTemplate() throws Exception {
        JsonNode pp = pathParametersFor("/assets/img/logo.png", "/assets/{rest+}");

        assertEquals(List.of("rest"), keysOf(pp));
        assertEquals("img/logo.png", pp.get("rest").asText());
    }
}
