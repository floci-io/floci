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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} preserves a trailing
 * slash in the HTTP API (V2) event path fields, the way {@code buildProxyEvent} already does
 * for REST (V1) since #1557. The JAX-RS {@code {proxy}} binding strips it, so the raw request
 * URI is the only place it survives.
 *
 * <p>Mirrors the V1 settlement: {@code rawPath} and {@code requestContext.http.path} keep the
 * slash, while {@code pathParameters} stay normalized because they come from the matcher.
 */
class BuildV2ProxyEventTrailingSlashTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(headers.getHeaderString("User-Agent")).thenReturn(null);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null,
                null, null
        );
    }

    private JsonNode buildEvent(String normalizedPath, String rawRequestUri, String routeKey) throws Exception {
        when(uriInfo.getRequestUri()).thenReturn(new URI(rawRequestUri));
        String json = controller.buildV2ProxyEvent(
                "GET", normalizedPath, routeKey,
                "abc123", "us-east-1", "$default", headers, uriInfo, null, "req-1");
        return new ObjectMapper().readTree(json);
    }

    @Test
    void rawPathKeepsTrailingSlashFromRawRequestUri() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/stage/thing/", "$default");
        assertEquals("/thing/", event.get("rawPath").asText(),
                "rawPath is by contract the raw path, so the trailing slash must survive");
    }

    @Test
    void requestContextHttpPathKeepsTrailingSlash() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/stage/thing/", "$default");
        assertEquals("/thing/", event.get("requestContext").get("http").get("path").asText(),
                "requestContext.http.path mirrors rawPath on real AWS");
    }

    @Test
    void pathParametersStayNormalized() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/stage/thing/", "ANY /{proxy+}");
        assertEquals("/thing/", event.get("rawPath").asText());
        assertEquals("thing", event.get("pathParameters").get("proxy").asText(),
                "pathParameters come from the matcher, which runs on the normalized path");
    }

    @Test
    void requestWithoutTrailingSlashIsUnchanged() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/stage/thing", "$default");
        assertEquals("/thing", event.get("rawPath").asText());
        assertEquals("/thing", event.get("requestContext").get("http").get("path").asText());
    }

    @Test
    void rootPathIsUnchanged() throws Exception {
        JsonNode event = buildEvent("/", "http://localhost:4566/api/stage/", "$default");
        assertEquals("/", event.get("rawPath").asText());
        assertEquals("/", event.get("requestContext").get("http").get("path").asText());
    }
}
