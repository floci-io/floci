package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.Base64Url;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ApiGatewayExecuteController#parseJwtClaims} flattens claim values into
 * the exact strings real API Gateway delivers at {@code requestContext.authorizer.jwt.claims}
 * (payload 2.0, measured against a Cognito-backed HTTP API 2026-08): strings as-is,
 * numbers/booleans stringified, array claims in the space-separated bracket form
 * ({@code cognito:groups} → {@code "[admin poweruser]"}, not JSON), null-valued claims omitted,
 * nested objects as JSON text — plus the scp/scope-derived scope list used for
 * {@code authorizationScopes} matching.
 */
class JwtClaimsWireFormatTest {

    private ApiGatewayExecuteController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiGatewayExecuteController(
                null, null, null, null,
                null, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null,
                null, null
        , null, null, null);
    }

    private static String unsignedToken(String claimsJson) {
        String header = Base64Url.encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = Base64Url.encode(claimsJson);
        return header + "." + payload + ".sig";
    }

    @Test
    void rendersClaimValuesInMeasuredWireFormat() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {
                  "sub": "user-123",
                  "exp": 9999999999,
                  "email_verified": true,
                  "cognito:groups": ["admin", "poweruser"],
                  "nickname": null,
                  "address": {"country": "JP"}
                }
                """));

        assertNotNull(claims);
        assertEquals("user-123", claims.raw().get("sub"));
        assertEquals("9999999999", claims.raw().get("exp"),
                "numeric claims are stringified in the 2.0 payload");
        assertEquals("true", claims.raw().get("email_verified"),
                "boolean claims are stringified in the 2.0 payload");
        assertEquals("[admin poweruser]", claims.raw().get("cognito:groups"),
                "array claims use API Gateway's space-separated bracket form, not JSON");
        assertFalse(claims.raw().containsKey("nickname"),
                "null-valued claims are omitted, not rendered as \"null\"");
        assertEquals("{\"country\":\"JP\"}", claims.raw().get("address"),
                "nested object claims fall back to JSON text");
    }

    @Test
    void parsesStringAudienceAsSingleEntry() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": "https://api.example.com"}
                """));

        assertNotNull(claims);
        assertEquals(List.of("https://api.example.com"), claims.audiences());
    }

    @Test
    void parsesArrayAudienceKeepingEveryEntry() {
        // Auth0 issues this shape whenever the openid scope is requested.
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": ["https://api.example.com", "https://tenant.auth0.com/userinfo"]}
                """));

        assertNotNull(claims);
        assertEquals(List.of("https://api.example.com", "https://tenant.auth0.com/userinfo"),
                claims.audiences());
    }

    @Test
    void audienceMatches_arrayAudContainingConfiguredAudience_matches() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": ["https://api.example.com", "https://tenant.auth0.com/userinfo"]}
                """));

        assertTrue(ApiGatewayExecuteController.audienceMatches(
                List.of("https://api.example.com"), claims));
    }

    @Test
    void audienceMatches_arrayAudWithoutConfiguredAudience_doesNotMatch() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": ["https://other.example.com", "https://tenant.auth0.com/userinfo"]}
                """));

        assertFalse(ApiGatewayExecuteController.audienceMatches(
                List.of("https://api.example.com"), claims));
    }

    @Test
    void audienceMatches_cognitoAccessTokenClientId_matches() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "client_id": "app-client-123"}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.audiences());
        assertTrue(ApiGatewayExecuteController.audienceMatches(List.of("app-client-123"), claims));
    }

    @Test
    void audienceMatches_mismatchedAudWithMatchingClientId_doesNotMatch() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": ["other"], "client_id": "expected"}
                """));

        assertFalse(ApiGatewayExecuteController.audienceMatches(List.of("expected"), claims));
    }

    @Test
    void audienceMatches_emptyAudArrayWithMatchingClientId_doesNotMatch() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "aud": [], "client_id": "expected"}
                """));

        assertFalse(ApiGatewayExecuteController.audienceMatches(List.of("expected"), claims));
    }

    @Test
    void derivesScopesFromScpArrayFirst() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "scp": ["orders/read", "orders/write"], "scope": "ignored"}
                """));

        assertNotNull(claims);
        assertEquals(List.of("orders/read", "orders/write"), claims.scopes(),
                "the scp claim wins over scope when both are present");
    }

    @Test
    void derivesScopesFromSpaceSeparatedScopeClaim() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u", "scope": "read  write"}
                """));

        assertNotNull(claims);
        assertEquals(List.of("read", "write"), claims.scopes(),
                "the Cognito access-token form: a space-separated scope string");
    }

    @Test
    void scopesAreNullWhenTokenCarriesNeitherClaim() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "u"}
                """));

        assertNotNull(claims);
        assertNull(claims.scopes(), "no scp/scope claim (the Cognito ID-token case) → null");
    }
}
