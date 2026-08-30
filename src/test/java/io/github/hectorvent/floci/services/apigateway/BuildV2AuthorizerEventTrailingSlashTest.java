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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the trailing-slash field split in the HTTP API REQUEST authorizer event, payload
 * format 1.0 ({@code buildRequestAuthorizerEventV1}).
 *
 * <p>{@code resource}, {@code path} and {@code requestContext.resourcePath} are copies of the
 * request path in this shape, because an HTTP API has no REST-style resource template, so all
 * three carry the delivered path. {@code methodArn} stays normalized: it is matched against
 * IAM-style policy resources, where a stray trailing slash silently fails an authorizer's
 * wildcards.
 */
class BuildV2AuthorizerEventTrailingSlashTest {

    private ApiGatewayExecuteController controller;
    private HttpHeaders headers;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");

        headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null,
                null
        );
    }

    private JsonNode buildEvent(String normalizedPath, String rawRequestUri) throws Exception {
        when(uriInfo.getRequestUri()).thenReturn(new URI(rawRequestUri));
        String json = controller.buildRequestAuthorizerEventV1(
                "GET", normalizedPath, "abc123", "test", "us-east-1", headers, uriInfo);
        return new ObjectMapper().readTree(json);
    }

    @Test
    void deliveredPathFieldsKeepTrailingSlash() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/test/thing/");
        assertEquals("/thing/", event.get("path").asText(),
                "path is the delivered request path");
        assertEquals("/thing/", event.get("resource").asText(),
                "an HTTP API has no resource template, so resource mirrors path");
        assertEquals("/thing/", event.get("requestContext").get("path").asText());
        assertEquals("/thing/", event.get("requestContext").get("resourcePath").asText(),
                "requestContext.resourcePath mirrors resource");
    }

    @Test
    void methodArnStaysNormalized() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/test/thing/");
        assertTrue(event.get("methodArn").asText().endsWith("abc123/test/GET/thing"),
                "methodArn is matched against IAM-style policy resources, so it keeps the "
                        + "normalized path: was " + event.get("methodArn").asText());
    }

    @Test
    void requestWithoutTrailingSlashIsUnchanged() throws Exception {
        JsonNode event = buildEvent("/thing", "http://localhost:4566/api/test/thing");
        assertEquals("/thing", event.get("path").asText());
        assertEquals("/thing", event.get("resource").asText());
        assertEquals("/thing", event.get("requestContext").get("resourcePath").asText());
    }

    @Test
    void rootPathIsUnchanged() throws Exception {
        JsonNode event = buildEvent("/", "http://localhost:4566/api/test/");
        assertEquals("/", event.get("path").asText());
        assertEquals("/", event.get("resource").asText());
    }
}
