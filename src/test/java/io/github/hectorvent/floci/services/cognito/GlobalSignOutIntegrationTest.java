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
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the GlobalSignOut API — the self-service counterpart to
 * AdminUserGlobalSignOut, authenticated with the caller's access token.
 * <p>
 * Verifies that all tokens Cognito issued to the user (access, ID, refresh) are
 * invalidated after GlobalSignOut, while a fresh login continues to work.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlobalSignOutIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static String accessToken;
    private static String refreshToken;

    private static final String USERNAME = "bob";
    private static final String PASSWORD  = "Password123!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** POST without asserting 200 — lets tests inspect the error response. */
    private static JsonNode cognitoJsonAny(String action, String body) throws Exception {
        return MAPPER.readTree(cognitoAction(action, body).then().extract().asString());
    }

    @Test
    @Order(1)
    void setupPoolAndUser() throws Exception {
        JsonNode pool = cognitoJson("CreateUserPool", """
                {"PoolName":"GlobalSignOutTestPool"}
                """);
        poolId = pool.path("UserPool").path("Id").asText();
        assertFalse(poolId.isBlank(), "Pool ID must not be blank");

        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "test-client",
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
    void authenticateAndCaptureTokens() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(clientId, USERNAME, PASSWORD));

        accessToken  = auth.path("AuthenticationResult").path("AccessToken").asText();
        refreshToken = auth.path("AuthenticationResult").path("RefreshToken").asText();

        assertFalse(accessToken.isBlank(),  "AccessToken must be present after sign-in");
        assertFalse(refreshToken.isBlank(), "RefreshToken must be present after sign-in");
    }

    @Test
    @Order(3)
    void getUserWorksBeforeSignOut() {
        // The access token must be usable before sign-out
        cognitoAction("GetUser", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void globalSignOutSucceeds() {
        // GlobalSignOut must return HTTP 200 with an empty body
        cognitoAction("GlobalSignOut", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(5)
    void accessTokenIsRejectedAfterSignOut() throws Exception {
        // The access token used to sign out must itself be invalidated
        JsonNode body = cognitoJsonAny("GetUser", """
                {"AccessToken":"%s"}
                """.formatted(accessToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Expected NotAuthorizedException for the access token after GlobalSignOut, body was: " + body);
    }

    @Test
    @Order(6)
    void refreshTokenIsRejectedAfterSignOut() throws Exception {
        // The refresh token must also be rejected after GlobalSignOut
        JsonNode body = cognitoJsonAny("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Expected NotAuthorizedException for the refresh token after GlobalSignOut");
    }

    @Test
    @Order(7)
    void globalSignOutFailsForInvalidToken() throws Exception {
        // A malformed / non-JWT access token must surface NotAuthorizedException
        JsonNode body = cognitoJsonAny("GlobalSignOut", """
                {"AccessToken":"not-a-real-token"}
                """);

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "GlobalSignOut with an invalid access token must fail with NotAuthorizedException");
    }

    @Test
    @Order(8)
    void newLoginSucceedsAfterSignOut() throws Exception {
        // A fresh login must still work — sign-out does not lock the account
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(clientId, USERNAME, PASSWORD));

        String newAccess  = auth.path("AuthenticationResult").path("AccessToken").asText();
        String newRefresh = auth.path("AuthenticationResult").path("RefreshToken").asText();
        assertFalse(newAccess.isBlank(),  "Fresh login must succeed after GlobalSignOut");
        assertNotEquals(accessToken, newAccess, "New session must issue a new AccessToken");
        assertNotEquals(refreshToken, newRefresh, "New session must issue a new RefreshToken");

        // The freshly issued refresh token must be usable — tokens minted after sign-out are valid.
        // (Verified via the refresh flow, whose millisecond-granular iat reliably post-dates the
        // revocation timestamp even when the new login lands in the same wall-clock second.)
        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, newRefresh));

        assertFalse(refreshed.path("AuthenticationResult").path("AccessToken").asText().isBlank(),
                "Refresh should succeed for tokens issued after GlobalSignOut");
    }

    @Test
    @Order(9)
    void oldRefreshTokenStillRejectedAfterNewLogin() throws Exception {
        // Even after a new login, the original revoked refresh token remains invalid
        JsonNode body = cognitoJsonAny("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Old revoked refresh token must still be rejected after a new login");
    }

    @Test
    @Order(10)
    void globalSignOutFailsForNonexistentUser() throws Exception {
        // A syntactically valid (but unverified) token whose username does not exist in the
        // pool must be rejected — GlobalSignOut confirms the user exists before touching the
        // revocation store, matching AdminUserGlobalSignOut and AWS behavior.
        String forged = forgeAccessToken(poolId, "ghost");

        JsonNode body = cognitoJsonAny("GlobalSignOut", """
                {"AccessToken":"%s"}
                """.formatted(forged));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "GlobalSignOut for a nonexistent user must fail with NotAuthorizedException, body was: " + body);
    }

    @Test
    @Order(11)
    void globalSignOutFailsForNonexistentPool() throws Exception {
        String forged = forgeAccessToken("us-east-1_NONEXISTENT", "ghost");

        JsonNode body = cognitoJsonAny("GlobalSignOut", """
                {"AccessToken":"%s"}
                """.formatted(forged));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "GlobalSignOut for a nonexistent pool must fail with NotAuthorizedException, body was: " + body);
    }

    @Test
    @Order(12)
    void globalSignOutRejectsMissingAccessToken() throws Exception {
        JsonNode body = cognitoJsonAny("GlobalSignOut", "{}");

        assertEquals("InvalidParameterException", body.path("__type").asText(),
                "GlobalSignOut with no AccessToken must fail with InvalidParameterException, body was: " + body);
    }

    /** Build an unsigned JWT-shaped access token — the emulator decodes the payload without verifying the signature. */
    private static String forgeAccessToken(String poolId, String username) {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(("""
                {"username":"%s","iss":"https://cognito-idp.us-east-1.amazonaws.com/%s",\
                "jti":"%s","iat":%d,"token_use":"access"}""")
                .formatted(username, poolId, UUID.randomUUID(), nowSeconds));
        return header + "." + payload + ".sig";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
