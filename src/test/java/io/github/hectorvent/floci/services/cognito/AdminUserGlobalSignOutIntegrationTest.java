package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the AdminUserGlobalSignOut API.
 *
 * Reproduces issue #1395: previously issued tokens must be invalidated
 * after calling AdminUserGlobalSignOut, matching AWS Cognito behavior.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminUserGlobalSignOutIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static String refreshToken;
    private static String accessToken;

    private static final String USERNAME = "alice";
    private static final String PASSWORD  = "Password123!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private static Response cognitoAction(String action, String body) {
        return given()
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + action)
                .contentType(CONTENT_TYPE)
                .body(body)
                .when()
                .post("/");
    }

    private static JsonNode cognitoJson(String action, String body) throws Exception {
        return MAPPER.readTree(
                cognitoAction(action, body).then().statusCode(200).extract().asString());
    }

    /** POST without asserting 200 — lets tests inspect the error response. */
    private static JsonNode cognitoJsonAny(String action, String body) throws Exception {
        return MAPPER.readTree(cognitoAction(action, body).then().extract().asString());
    }

    // ─── setup ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void setupPoolAndUser() throws Exception {
        JsonNode pool = cognitoJson("CreateUserPool", """
                {"PoolName":"SignOutTestPool"}
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

        refreshToken = auth.path("AuthenticationResult").path("RefreshToken").asText();
        accessToken  = auth.path("AuthenticationResult").path("AccessToken").asText();

        assertFalse(refreshToken.isBlank(), "RefreshToken must be present after sign-in");
        assertFalse(accessToken.isBlank(),  "AccessToken must be present after sign-in");
    }

    @Test
    @Order(3)
    void refreshTokenWorksBeforeSignOut() throws Exception {
        // Verify refresh token is functional before global sign-out
        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        String newAccess = refreshed.path("AuthenticationResult").path("AccessToken").asText();
        assertFalse(newAccess.isBlank(), "Refresh should succeed before global sign-out");
    }

    @Test
    @Order(4)
    void adminUserGlobalSignOutSucceeds() {
        // AdminUserGlobalSignOut must return HTTP 200 with an empty body
        cognitoAction("AdminUserGlobalSignOut", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, USERNAME))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(5)
    void refreshTokenIsRejectedAfterSignOut() throws Exception {
        // Core bug reproduction: refresh token must be rejected after global sign-out
        JsonNode body = cognitoJsonAny("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Expected NotAuthorizedException after global sign-out, body was: " + body);
    }

    @Test
    @Order(6)
    void getTokensFromRefreshTokenIsRejectedAfterSignOut() throws Exception {
        // GetTokensFromRefreshToken path must also honour revocation
        JsonNode body = cognitoJsonAny("GetTokensFromRefreshToken", """
                {"ClientId":"%s","RefreshToken":"%s"}
                """.formatted(clientId, refreshToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Expected NotAuthorizedException for GetTokensFromRefreshToken after sign-out");
    }

    @Test
    @Order(7)
    void newLoginSucceedsAfterSignOut() throws Exception {
        // A fresh password-auth login must still succeed — sign-out does not lock the account
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(clientId, USERNAME, PASSWORD));

        String newRefresh = auth.path("AuthenticationResult").path("RefreshToken").asText();
        assertFalse(newRefresh.isBlank(), "Fresh login must succeed after global sign-out");
        assertNotEquals(refreshToken, newRefresh, "New session must issue a new RefreshToken");

        // Verify the new refresh token works
        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, newRefresh));

        String newAccess = refreshed.path("AuthenticationResult").path("AccessToken").asText();
        assertFalse(newAccess.isBlank(), "Refresh should succeed for new tokens issued after global sign-out");
    }

    @Test
    @Order(8)
    void oldRefreshTokenStillRejectedAfterNewLogin() throws Exception {
        // Even after a new login, the old revoked refresh token remains invalid
        JsonNode body = cognitoJsonAny("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": {"REFRESH_TOKEN": "%s"}
                }
                """.formatted(clientId, refreshToken));

        assertEquals("NotAuthorizedException", body.path("__type").asText(),
                "Old revoked token must still be rejected after a new login");
    }

    @Test
    @Order(9)
    void adminUserGlobalSignOutFailsForUnknownUser() throws Exception {
        // Calling sign-out for a non-existent user must return UserNotFoundException
        JsonNode body = cognitoJsonAny("AdminUserGlobalSignOut", """
                {"UserPoolId":"%s","Username":"ghost"}
                """.formatted(poolId));

        assertEquals("UserNotFoundException", body.path("__type").asText());
    }
}
