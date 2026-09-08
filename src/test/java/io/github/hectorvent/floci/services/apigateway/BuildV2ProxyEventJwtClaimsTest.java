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
 * Verifies that {@link ApiGatewayExecuteController#buildV2ProxyEvent} populates
 * {@code requestContext.authorizer.jwt.claims} for HTTP API (V2) routes whose JWT authorizer
 * verified successfully - previously this information was parsed and validated by
 * enforceJwtAuthorizer, then discarded rather than propagated to the Lambda event.
 */
class BuildV2ProxyEventJwtClaimsTest {

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
                regionResolver, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null, null, null
        );
    }

    @Test
    void populatesAuthorizerJwtClaimsWhenPresent() throws Exception {
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("sub", "user_01ABC");
        claims.put("iss", "https://example.authkit.app");

        String json = controller.buildV2ProxyEvent(
                "POST", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-1", claims, null);
        JsonNode event = new ObjectMapper().readTree(json);

        JsonNode jwtClaims = event.at("/requestContext/authorizer/jwt/claims");
        assertFalse(jwtClaims.isMissingNode(), "requestContext.authorizer.jwt.claims must be present");
        assertEquals("user_01ABC", jwtClaims.get("sub").asText());
        assertEquals("https://example.authkit.app", jwtClaims.get("iss").asText());
    }

    @Test
    void populatesAuthorizerJwtScopesHandedOverByDispatch() throws Exception {
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("sub", "user_01ABC");
        claims.put("scope", "read:things write:things");

        String json = controller.buildV2ProxyEvent(
                "POST", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-4", claims,
                List.of("read:things", "write:things"));
        JsonNode event = new ObjectMapper().readTree(json);

        JsonNode jwt = event.at("/requestContext/authorizer/jwt");
        JsonNode scopes = jwt.get("scopes");
        assertTrue(scopes.isArray(), "requestContext.authorizer.jwt.scopes must be an array");
        assertEquals(2, scopes.size());
        assertEquals("read:things", scopes.get(0).asText());
        assertEquals("write:things", scopes.get(1).asText());
        assertEquals("read:things write:things", jwt.at("/claims/scope").asText(),
                "the raw scope claim itself stays in claims (matches real AWS events)");
    }

    @Test
    void rendersScopesAsExplicitNullWhenDispatchHandsNone() throws Exception {
        // Measured against real API Gateway (2026-08): a route WITHOUT authorizationScopes
        // renders "scopes": null even when the token carries a scope claim, so the builder
        // must not derive scopes from the claims map on its own.
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("sub", "user_01ABC");
        claims.put("scope", "aws.cognito.signin.user.admin");

        String json = controller.buildV2ProxyEvent(
                "POST", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-5", claims, null);
        JsonNode event = new ObjectMapper().readTree(json);

        JsonNode jwt = event.at("/requestContext/authorizer/jwt");
        assertTrue(jwt.has("scopes") && jwt.get("scopes").isNull(),
                "scopes must be an explicit null for routes without authorizationScopes");
        assertEquals("aws.cognito.signin.user.admin", jwt.at("/claims/scope").asText());
    }

    @Test
    void omitsAuthorizerNodeWhenClaimsAreNull() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "GET", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-2", null, null);
        JsonNode event = new ObjectMapper().readTree(json);

        assertFalse(event.get("requestContext").has("authorizer"),
                "requestContext.authorizer must be absent for routes with no JWT claims and no Lambda authorizer context");
    }

    @Test
    void omitsAuthorizerNodeWhenClaimsAreEmpty() throws Exception {
        String json = controller.buildV2ProxyEvent(
                "GET", "/v1/things", "$default",
                "abc123", "us-east-2", "$default", headers, uriInfo, null, "req-3", Map.of(), null);
        JsonNode event = new ObjectMapper().readTree(json);

        assertFalse(event.get("requestContext").has("authorizer"),
                "requestContext.authorizer must be absent when claims map is empty");
    }
}
