package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Cognito-native {@code jti} and {@code origin_jti} token claims.
 * <p>
 * Real AWS Cognito stamps every access and ID token with a unique {@code jti} and a per
 * refresh-token-family {@code origin_jti} whenever token revocation is enabled on the app
 * client (the default). Downstream stateless authorizers key their deny-list on
 * {@code origin_jti}, so it must stay identical across a refresh of the same session and
 * differ between independent logins.
 * <p>
 * Provisioning runs in an {@code @Order(1)} test rather than {@code @BeforeAll} because a
 * {@code @QuarkusTest} HTTP endpoint is only guaranteed reachable inside test methods. The
 * remaining ordered tests form a session lifecycle (login → refresh → revoke); each guards
 * the shared state it depends on via {@link #requireSetup()} / {@link #requireInitialAuthState()}
 * so a failure in an earlier step reports a clear cause instead of a misleading assertion.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginJtiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static String refreshToken;

    private static String originJti1;
    private static String accessJti1;

    private static final String USERNAME = "bob";
    private static final String PASSWORD = "Password123!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /** Decodes the JWT payload (second segment) into a JSON tree. */
    private static JsonNode decodeJwtPayload(String jwt) throws Exception {
        String[] segments = jwt.split("\\.");
        assertEquals(3, segments.length, "JWT must have three dot-separated segments");
        byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
        return MAPPER.readTree(payload);
    }

    /** POSTs without asserting 200 so error responses can be inspected. */
    private static JsonNode cognitoJsonAny(String action, String body) throws Exception {
        return MAPPER.readTree(cognitoAction(action, body).then().extract().asString());
    }

    private static JsonNode passwordLogin() throws Exception {
        return cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(clientId, USERNAME, PASSWORD));
    }

    /** Guards the pool/client provisioned by setupPoolAndUser so a setup failure reads clearly. */
    private static void requireSetup() {
        assertNotNull(poolId, "setupPoolAndUser (Order 1) must run first to create the user pool");
        assertNotNull(clientId, "setupPoolAndUser (Order 1) must run first to create the app client");
    }

    /** Guards the state handed forward by the initial-auth test so an earlier failure reads clearly. */
    private static void requireInitialAuthState() {
        assertNotNull(refreshToken, "initialAuthEmitsJtiAndOriginJti (Order 2) must run first to set refreshToken");
        assertFalse(refreshToken.isBlank(), "refreshToken from the initial authentication must not be blank");
        assertNotNull(originJti1, "initialAuthEmitsJtiAndOriginJti (Order 2) must run first to set origin_jti");
    }

    // ─── setup ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void setupPoolAndUser() throws Exception {
        JsonNode pool = cognitoJson("CreateUserPool", """
                {"PoolName":"OriginJtiTestPool"}
                """);
        poolId = pool.path("UserPool").path("Id").asText();
        assertFalse(poolId.isBlank(), "Pool ID must not be blank");

        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "origin-jti-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
                }
                """.formatted(poolId));
        clientId = client.path("UserPoolClient").path("ClientId").asText();
        assertFalse(clientId.isBlank(), "Client ID must not be blank");

        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, USERNAME)).then().statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, USERNAME, PASSWORD)).then().statusCode(200);
    }

    @Test
    @Order(2)
    void initialAuthEmitsJtiAndOriginJti() throws Exception {
        requireSetup();
        JsonNode auth = passwordLogin();
        JsonNode result = auth.path("AuthenticationResult");

        refreshToken = result.path("RefreshToken").asText();
        assertFalse(refreshToken.isBlank(), "RefreshToken must be present after sign-in");

        JsonNode access = decodeJwtPayload(result.path("AccessToken").asText());
        JsonNode id = decodeJwtPayload(result.path("IdToken").asText());

        assertFalse(access.path("jti").asText().isBlank(), "Access token must contain jti");
        assertFalse(access.path("origin_jti").asText().isBlank(), "Access token must contain origin_jti");
        assertFalse(id.path("jti").asText().isBlank(), "ID token must contain jti");
        assertFalse(id.path("origin_jti").asText().isBlank(), "ID token must contain origin_jti");

        assertEquals(access.path("origin_jti").asText(), id.path("origin_jti").asText(),
                "Access and ID tokens from the same authentication must share origin_jti");
        assertNotEquals(access.path("jti").asText(), id.path("jti").asText(),
                "Access and ID tokens must each carry their own unique jti");

        originJti1 = access.path("origin_jti").asText();
        accessJti1 = access.path("jti").asText();
    }

    @Test
    @Order(3)
    void describeClientReportsEnableTokenRevocation() throws Exception {
        requireSetup();
        JsonNode described = cognitoJson("DescribeUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s"}
                """.formatted(poolId, clientId));

        assertTrue(described.path("UserPoolClient").path("EnableTokenRevocation").asBoolean(),
                "EnableTokenRevocation should default to true and be reported by DescribeUserPoolClient");
    }

    @Test
    @Order(4)
    void refreshKeepsOriginJtiStableAndRotatesJti() throws Exception {
        requireSetup();
        requireInitialAuthState();
        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        JsonNode access = decodeJwtPayload(refreshed.path("AuthenticationResult").path("AccessToken").asText());

        assertEquals(originJti1, access.path("origin_jti").asText(),
                "origin_jti must stay identical across a refresh of the same session");
        assertNotEquals(accessJti1, access.path("jti").asText(),
                "jti must be freshly generated on every token issuance");
    }

    @Test
    @Order(5)
    void getTokensFromRefreshTokenKeepsOriginJti() throws Exception {
        requireSetup();
        requireInitialAuthState();
        JsonNode refreshed = cognitoJson("GetTokensFromRefreshToken", """
                {"ClientId":"%s","RefreshToken":"%s"}
                """.formatted(clientId, refreshToken));

        JsonNode access = decodeJwtPayload(refreshed.path("AuthenticationResult").path("AccessToken").asText());
        assertEquals(originJti1, access.path("origin_jti").asText(),
                "origin_jti must also stay stable through the GetTokensFromRefreshToken path");
    }

    @Test
    @Order(6)
    void secondLoginUsesDifferentOriginJti() throws Exception {
        requireSetup();
        requireInitialAuthState();
        JsonNode auth = passwordLogin();
        JsonNode access = decodeJwtPayload(auth.path("AuthenticationResult").path("AccessToken").asText());

        assertFalse(access.path("origin_jti").asText().isBlank(), "Second login must also carry origin_jti");
        assertNotEquals(originJti1, access.path("origin_jti").asText(),
                "Each independent login must start a new revocation family with a distinct origin_jti");
    }

    @Test
    @Order(7)
    void clientWithRevocationDisabledOmitsOriginJti() throws Exception {
        requireSetup();
        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "no-revocation-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                  "EnableTokenRevocation": false
                }
                """.formatted(poolId));
        String disabledClientId = client.path("UserPoolClient").path("ClientId").asText();

        assertFalse(client.path("UserPoolClient").path("EnableTokenRevocation").asBoolean(),
                "EnableTokenRevocation=false must round-trip through CreateUserPoolClient");

        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(disabledClientId, USERNAME, PASSWORD));

        JsonNode access = decodeJwtPayload(auth.path("AuthenticationResult").path("AccessToken").asText());
        assertFalse(access.path("jti").asText().isBlank(), "jti is always emitted");
        assertTrue(access.path("origin_jti").isMissingNode(),
                "origin_jti must be omitted when token revocation is disabled on the client");
    }

    @Test
    @Order(8)
    void revokeTokenInvalidatesRefreshFamily() throws Exception {
        requireSetup();
        JsonNode auth = passwordLogin();
        String freshRefresh = auth.path("AuthenticationResult").path("RefreshToken").asText();

        // Sanity: the fresh refresh token works before revocation.
        JsonNode ok = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"REFRESH_TOKEN_AUTH","AuthParameters":{"REFRESH_TOKEN":"%s"}}
                """.formatted(clientId, freshRefresh));
        assertFalse(ok.path("AuthenticationResult").path("AccessToken").asText().isBlank(),
                "Refresh must work before RevokeToken");

        cognitoAction("RevokeToken", """
                {"ClientId":"%s","Token":"%s"}
                """.formatted(clientId, freshRefresh)).then().statusCode(200);

        JsonNode rejected = cognitoJsonAny("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"REFRESH_TOKEN_AUTH","AuthParameters":{"REFRESH_TOKEN":"%s"}}
                """.formatted(clientId, freshRefresh));
        assertEquals("NotAuthorizedException", rejected.path("__type").asText(),
                "REFRESH_TOKEN_AUTH must fail after RevokeToken, body was: " + rejected);

        JsonNode rejected2 = cognitoJsonAny("GetTokensFromRefreshToken", """
                {"ClientId":"%s","RefreshToken":"%s"}
                """.formatted(clientId, freshRefresh));
        assertEquals("NotAuthorizedException", rejected2.path("__type").asText(),
                "GetTokensFromRefreshToken must also fail after RevokeToken");
    }

    @Test
    @Order(9)
    void revokeTokenRejectsNonRefreshToken() throws Exception {
        requireSetup();
        JsonNode body = cognitoJsonAny("RevokeToken", """
                {"ClientId":"%s","Token":"not-a-refresh-token"}
                """.formatted(clientId));
        assertEquals("UnsupportedTokenTypeException", body.path("__type").asText(),
                "Only refresh tokens may be revoked, body was: " + body);
    }

    @Test
    @Order(10)
    void revokeTokenUnsupportedWhenRevocationDisabled() throws Exception {
        requireSetup();
        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "revoke-disabled-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                  "EnableTokenRevocation": false
                }
                """.formatted(poolId));
        String disabledClientId = client.path("UserPoolClient").path("ClientId").asText();

        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(disabledClientId, USERNAME, PASSWORD));
        String disabledRefresh = auth.path("AuthenticationResult").path("RefreshToken").asText();

        JsonNode body = cognitoJsonAny("RevokeToken", """
                {"ClientId":"%s","Token":"%s"}
                """.formatted(disabledClientId, disabledRefresh));
        assertEquals("UnsupportedOperationException", body.path("__type").asText(),
                "RevokeToken must be rejected when the client disables token revocation, body was: " + body);
    }

    @Test
    @Order(11)
    void revokeTokenChecksClientSecretBeforeRevocationConfig() throws Exception {
        requireSetup();
        // Confidential client with revocation disabled: an invalid secret must be rejected with
        // NotAuthorizedException *before* the UnsupportedOperationException config check, so that
        // revocation state is never disclosed to an unauthenticated caller (matches AWS ordering).
        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "confidential-disabled-client",
                  "GenerateSecret": true,
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                  "EnableTokenRevocation": false
                }
                """.formatted(poolId));
        String confidentialClientId = client.path("UserPoolClient").path("ClientId").asText();
        assertFalse(client.path("UserPoolClient").path("ClientSecret").asText().isBlank(),
                "Confidential client must be issued a secret");

        JsonNode body = cognitoJsonAny("RevokeToken", """
                {"ClientId":"%s","Token":"any-token","ClientSecret":"wrong-secret"}
                """.formatted(confidentialClientId));
        assertEquals("UnauthorizedException", body.path("__type").asText(),
                "Caller identity must be validated before the revocation-enabled check, body was: " + body);
    }

    @Test
    @Order(12)
    void revokeTokenInvalidatesAccessTokens() throws Exception {
        requireSetup();
        JsonNode auth = passwordLogin();
        String freshAccess = auth.path("AuthenticationResult").path("AccessToken").asText();
        String freshRefresh = auth.path("AuthenticationResult").path("RefreshToken").asText();

        // GetUser must succeed before revocation.
        JsonNode ok = cognitoJsonAny("GetUser", """
                {"AccessToken":"%s"}
                """.formatted(freshAccess));
        assertFalse(ok.has("__type"), "GetUser must succeed before RevokeToken, body was: " + ok);

        cognitoAction("RevokeToken", """
                {"ClientId":"%s","Token":"%s"}
                """.formatted(clientId, freshRefresh)).then().statusCode(200);

        // Access token issued alongside the revoked refresh token must now be rejected.
        JsonNode rejected = cognitoJsonAny("GetUser", """
                {"AccessToken":"%s"}
                """.formatted(freshAccess));
        assertEquals("NotAuthorizedException", rejected.path("__type").asText(),
                "GetUser must fail after RevokeToken invalidates the origin_jti family, body was: " + rejected);
    }
}
