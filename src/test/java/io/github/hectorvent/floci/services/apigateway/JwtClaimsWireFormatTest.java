package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
                null, null, null,
                null, new ObjectMapper(), null,
                null, null, null, null, new ApiGatewayExecuteRouteContext(), null,
                null
        );
    }

    private static String unsignedToken(String claimsJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
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
