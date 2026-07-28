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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} surfaces the
 * validated JWT claims handed over by dispatch at {@code requestContext.authorizer.jwt.claims},
 * matching the payload 2.0 event real API Gateway delivers to an AWS_PROXY Lambda.
 * The builder never parses the Authorization header itself — dispatch only passes a
 * claims map for routes that went through {@code enforceJwtAuthorizer}, so unvalidated
 * tokens can never surface as authorizer context.
 */
class BuildV2ProxyEventJwtAuthorizerClaimsTest {

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
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://localhost:4566/api/v1/assets"));

        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null
        );
    }

    @Test
    void validatedClaimsAreInjectedWithPayload20Stringification() throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "user-123");
        claims.put("email", "a@example.com");
        claims.put("exp", 9999999999L);
        claims.put("email_verified", true);
        claims.put("cognito:groups", List.of("admin", "poweruser"));
        claims.put("nickname", null);

        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", claims,
                "abc123", "v1", headers, uriInfo, null, "req-1");
        JsonNode authorizer = new ObjectMapper().readTree(json)
                .path("requestContext").path("authorizer");

        assertFalse(authorizer.isMissingNode(), "authorizer must be present for validated JWT routes");
        JsonNode claimsNode = authorizer.path("jwt").path("claims");
        assertEquals("user-123", claimsNode.get("sub").asText());
        assertEquals("a@example.com", claimsNode.get("email").asText());
        assertEquals("9999999999", claimsNode.get("exp").asText(),
                "numeric claims are stringified in the 2.0 payload");
        assertEquals("true", claimsNode.get("email_verified").asText(),
                "boolean claims are stringified in the 2.0 payload");
        assertEquals("[admin poweruser]", claimsNode.get("cognito:groups").asText(),
                "array claims use API Gateway's space-separated bracket form, not JSON");
        assertFalse(claimsNode.has("nickname"), "null-valued claims are omitted, not \"null\"");
        assertTrue(authorizer.path("jwt").get("scopes").isNull(),
                "scopes is null when no authorization scopes are configured");
    }

    @Test
    void nullClaimsMapOmitsAuthorizer() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", null,
                "abc123", "v1", headers, uriInfo, null, "req-2");
        JsonNode event = new ObjectMapper().readTree(json);

        assertTrue(event.path("requestContext").path("authorizer").isMissingNode(),
                "no claims handed over by dispatch → no authorizer context");
    }

    @Test
    void emptyClaimsMapOmitsAuthorizer() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "GET", "/assets", "GET /assets", Map.of(),
                "abc123", "v1", headers, uriInfo, null, "req-3");
        JsonNode event = new ObjectMapper().readTree(json);

        assertTrue(event.path("requestContext").path("authorizer").isMissingNode());
    }
}
