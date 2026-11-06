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

    @Test
    void arrayAudParsesIntoAudienceList() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {
                  "sub": "user-123",
                  "aud": ["https://api.example.com", "https://tenant.auth0.com/userinfo"]
                }
                """));

        assertNotNull(claims);
        assertEquals(List.of("https://api.example.com", "https://tenant.auth0.com/userinfo"),
                claims.aud(), "RFC 7519 section 4.1.3: aud may be an array, every entry counts");
    }

    @Test
    void stringAudParsesIntoSingleAudience() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": "https://api.example.com"}
                """));

        assertNotNull(claims);
        assertEquals(List.of("https://api.example.com"), claims.aud(),
                "the single-string aud form still parses to one audience");
    }

    @Test
    void audIsNullWhenTokenCarriesNoAudClaim() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "client_id": "expected"}
                """));

        assertNotNull(claims);
        assertNull(claims.aud(), "no aud claim (the Cognito access-token case) → null");
    }

    @Test
    void emptyArrayAudParsesIntoEmptyList() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": []}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.aud(),
                "an aud that is present but empty stays present, so it cannot fall back to client_id");
    }

    @Test
    void nullAudParsesIntoEmptyList() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": null, "client_id": "expected"}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.aud(),
                "an aud that is present but null stays present, so it cannot fall back to client_id");
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), claims.aud(), "expected"),
                "a null aud is not an absent aud: a matching client_id must not rescue it");
    }

    @Test
    void objectAudParsesIntoEmptyList() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": {}, "client_id": "expected"}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.aud(),
                "an aud that is present but an object stays present, so it cannot fall back to client_id");
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), claims.aud(), "expected"),
                "an object aud is not an absent aud: a matching client_id must not rescue it");
    }

    @Test
    void numericArrayAudEntriesDoNotCoerceToStrings() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": [123], "client_id": "expected"}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.aud(),
                "only textual aud entries count: a numeric entry must not coerce to \"123\"");
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("123"), claims.aud(), "expected"),
                "a numeric aud entry must not match a configured audience of the same digits");
    }

    @Test
    void textualArrayAudEntriesStillCountAlongsideNumericOnes() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": ["expected", 123]}
                """));

        assertNotNull(claims);
        assertEquals(List.of("expected"), claims.aud(),
                "textual entries still count when a numeric entry sits beside them");
        assertTrue(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), claims.aud(), null),
                "the textual entry still authorizes the token");
    }

    @Test
    void numericScalarAudParsesIntoEmptyList() {
        ApiGatewayExecuteController.JwtClaims claims = controller.parseJwtClaims(unsignedToken("""
                {"sub": "user-123", "aud": 123, "client_id": "expected"}
                """));

        assertNotNull(claims);
        assertEquals(List.of(), claims.aud(),
                "a scalar aud that is not a string is present but invalid, so it cannot fall back to client_id");
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("123"), claims.aud(), "expected"),
                "a numeric scalar aud must not coerce to \"123\" or fall back to client_id");
    }

    @Test
    void audienceMatchesWhenAnyArrayEntryMatches() {
        assertTrue(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("https://api.example.com"),
                        List.of("https://api.example.com", "https://tenant.auth0.com/userinfo"), null),
                "API Gateway accepts the token when any aud entry matches the authorizer audience");
    }

    @Test
    void audienceRejectsWhenAudPresentAndOnlyClientIdMatches() {
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), List.of("other"), "expected"),
                "API Gateway checks client_id only when aud is absent; a present aud that "
                        + "does not match cannot be rescued by a matching client_id");
    }

    @Test
    void audienceFallsBackToClientIdWhenAudAbsent() {
        assertTrue(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), null, "expected"),
                "Cognito access tokens omit aud, so a matching client_id authorizes them");
    }

    @Test
    void audienceRejectsWhenNeitherAudNorClientIdMatches() {
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), List.of("other"), "also-other"));
    }

    @Test
    void audienceRejectsEmptyAudList() {
        assertFalse(ApiGatewayExecuteController.jwtAudienceMatches(
                        List.of("expected"), List.of(), "expected"),
                "an aud that is present but empty is rejected, with no client_id fallback");
    }
}
