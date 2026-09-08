package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} populates
 * {@code requestContext.authorizer.lambda} for HTTP API (V2) routes behind a Lambda REQUEST
 * authorizer.
 *
 * <p>The end-to-end path is covered by HttpApiRequestAuthorizerTest; these assertions pin the
 * rendered shape without needing a Lambda container.
 */
class BuildV2ProxyEventLambdaAuthorizerTest {

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
        when(headers.getHeaderString("User-Agent")).thenReturn(null);

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/stage/v1/things"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, MAPPER, null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null
        );
    }

    @Test
    void populatesAuthorizerLambdaContext() throws Exception {
        ObjectNode context = MAPPER.createObjectNode();
        context.put("userId", "user123");
        context.put("role", "admin");

        String json = controller.buildV2ProxyEvent(
                "POST", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-1", null, null,
                context);
        JsonNode event = MAPPER.readTree(json);

        JsonNode lambda = event.at("/requestContext/authorizer/lambda");
        assertFalse(lambda.isMissingNode(), "requestContext.authorizer.lambda must be present");
        assertEquals("user123", lambda.get("userId").asText());
        assertEquals("admin", lambda.get("role").asText());
        assertFalse(event.at("/requestContext/authorizer").has("jwt"),
                "a Lambda authorizer must not render the JWT-authorizer node");
    }

    @Test
    void keepsNestedObjectsAndScalarTypes() throws Exception {
        // The developer guide's simple-response example returns a string, a number, a boolean,
        // an array and a map, so all five have to survive.
        ObjectNode context = MAPPER.createObjectNode();
        ObjectNode claims = context.putObject("jwt").putObject("claims");
        claims.put("sub", "user-abc");
        context.put("tenantId", "CONDO_1");
        context.put("requestCount", 3);
        context.put("active", true);
        context.putArray("groups").add("admin").add("auditor");

        String json = controller.buildV2ProxyEvent(
                "GET", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-2", null, null,
                context);
        JsonNode lambda = MAPPER.readTree(json).at("/requestContext/authorizer/lambda");

        assertEquals("user-abc", lambda.at("/jwt/claims/sub").asText(),
                "nested objects must survive rather than being stringified");
        assertEquals("CONDO_1", lambda.get("tenantId").asText());
        assertTrue(lambda.get("requestCount").isNumber(), "a number must stay a number");
        assertTrue(lambda.get("active").isBoolean(), "a boolean must stay a boolean");
        assertTrue(lambda.get("groups").isArray(), "an array must stay an array");
        assertEquals("auditor", lambda.get("groups").get(1).asText());
    }

    @Test
    void omitsAuthorizerNodeWhenNoContextWasReturned() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "GET", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-3", null, null,
                null);
        JsonNode event = MAPPER.readTree(json);

        assertFalse(event.get("requestContext").has("authorizer"),
                "an authorizer that allows without a context leaves the node absent");
    }

    @Test
    void jwtClaimsWinOverLambdaContext() throws Exception {
        // dispatchV2 can never hand over both; pinned so the event never grows two authorizer
        // nodes if that changes.
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("sub", "user_01ABC");
        ObjectNode context = MAPPER.createObjectNode();
        context.put("userId", "user123");

        String json = controller.buildV2ProxyEvent(
                "GET", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-4", claims, null,
                context);
        JsonNode authorizer = MAPPER.readTree(json).at("/requestContext/authorizer");

        assertEquals("user_01ABC", authorizer.at("/jwt/claims/sub").asText());
        assertFalse(authorizer.has("lambda"), "the two authorizer shapes are mutually exclusive");
    }
}
