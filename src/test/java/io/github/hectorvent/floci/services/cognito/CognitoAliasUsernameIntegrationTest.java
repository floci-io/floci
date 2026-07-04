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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (AWS wire protocol) coverage for a user pool created with
 * {@code UsernameAttributes = ["email"]}. Mirrors real Cognito: the canonical
 * {@code Username} is an immutable UUID equal to {@code sub}, the email is a mutable
 * sign-in alias, {@code SECRET_HASH} is validated against the sent USERNAME, and changing
 * the email rebinds sign-in without moving the username/sub.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoAliasUsernameIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String EMAIL = "alias+" + UUID.randomUUID() + "@example.com";
    private static final String NEW_EMAIL = "changed+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "Perm1234!";

    private static String poolId;
    private static String clientId;
    private static String clientSecret;
    private static String uuidUsername;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void adminCreateUserMintsUuidUsernameEqualToSub() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "AliasEmailPool",
                  "UsernameAttributes": ["email"],
                  "AutoVerifiedAttributes": ["email"]
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "alias-client",
                  "GenerateSecret": true,
                  "ExplicitAuthFlows": [
                    "ALLOW_USER_PASSWORD_AUTH",
                    "ALLOW_ADMIN_USER_PASSWORD_AUTH",
                    "ALLOW_REFRESH_TOKEN_AUTH"
                  ]
                }
                """.formatted(poolId));
        clientId = clientResponse.path("UserPoolClient").path("ClientId").asText();
        clientSecret = clientResponse.path("UserPoolClient").path("ClientSecret").asText();
        assertTrue(clientSecret != null && !clientSecret.isBlank(), "client secret must be generated");

        JsonNode createResponse = cognitoJson("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" },
                    { "Name": "email_verified", "Value": "true" }
                  ]
                }
                """.formatted(poolId, EMAIL, EMAIL));

        JsonNode user = createResponse.path("User");
        uuidUsername = user.path("Username").asText();

        assertTrue(isUuid(uuidUsername), "canonical Username must be a generated UUID, was: " + uuidUsername);
        assertNotEquals(EMAIL, uuidUsername, "Username must not be the email");
        assertEquals(uuidUsername, attribute(user.path("Attributes"), "sub"), "Username must equal sub");
        assertEquals(EMAIL, attribute(user.path("Attributes"), "email"), "email must be stored as an attribute");

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, EMAIL, PASSWORD))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void listUsersReturnsUuidUsername() throws Exception {
        JsonNode response = cognitoJson("ListUsers", """
                { "UserPoolId": "%s" }
                """.formatted(poolId));

        JsonNode users = response.path("Users");
        assertEquals(1, users.size());
        assertEquals(uuidUsername, users.get(0).path("Username").asText());
        assertEquals(EMAIL, attribute(users.get(0).path("Attributes"), "email"));
    }

    @Test
    @Order(3)
    void adminGetUserResolvesByUuidAndByEmailToUuid() throws Exception {
        JsonNode byUuid = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, uuidUsername));
        assertEquals(uuidUsername, byUuid.path("Username").asText());

        JsonNode byEmail = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, EMAIL));
        assertEquals(uuidUsername, byEmail.path("Username").asText());
    }

    @Test
    @Order(4)
    void signInByEmailAliasIssuesTokensWithUuidClaims() throws Exception {
        Response authResponse = initiateUserPasswordAuth(EMAIL);
        authResponse.then().statusCode(200);

        String accessToken = authResponse.jsonPath().getString("AuthenticationResult.AccessToken");
        String idToken = authResponse.jsonPath().getString("AuthenticationResult.IdToken");

        JsonNode access = decodeJwtPayload(accessToken);
        assertEquals(uuidUsername, access.path("sub").asText());
        assertEquals(uuidUsername, access.path("username").asText());
        assertEquals(uuidUsername, access.path("cognito:username").asText());
        assertTrue(access.path("email").isMissingNode(),
                "access token must not carry the email claim (AWS emits it only in the id token)");

        JsonNode id = decodeJwtPayload(idToken);
        assertEquals(uuidUsername, id.path("sub").asText());
        assertEquals(EMAIL, id.path("email").asText());
    }

    @Test
    @Order(5)
    void signInByUuidAlsoIssuesTokens() {
        initiateUserPasswordAuth(uuidUsername).then().statusCode(200)
                .body("AuthenticationResult.AccessToken", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @Order(6)
    void secretHashValidatedAgainstSentUsername() {
        // USERNAME=email but SECRET_HASH computed over the UUID -> must be rejected.
        cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s",
                    "SECRET_HASH": "%s"
                  }
                }
                """.formatted(clientId, EMAIL, PASSWORD, secretHash(uuidUsername)))
                .then()
                .statusCode(400)
                .body("__type", containsString("NotAuthorizedException"));
    }

    @Test
    @Order(7)
    void emailChangeRebindsSignInAndKeepsUsernameAndSub() throws Exception {
        cognitoAction("AdminUpdateUserAttributes", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [ { "Name": "email", "Value": "%s" } ]
                }
                """.formatted(poolId, uuidUsername, NEW_EMAIL))
                .then()
                .statusCode(200);

        // New email now signs in.
        initiateUserPasswordAuth(NEW_EMAIL).then().statusCode(200)
                .body("AuthenticationResult.AccessToken", org.hamcrest.Matchers.notNullValue());

        // Old email no longer resolves.
        cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s",
                    "SECRET_HASH": "%s"
                  }
                }
                """.formatted(clientId, EMAIL, PASSWORD, secretHash(EMAIL)))
                .then()
                .statusCode(400)
                .body("__type", containsString("UserNotFoundException"));

        // Immutable username / sub unchanged; email attribute updated.
        JsonNode user = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, uuidUsername));
        assertEquals(uuidUsername, user.path("Username").asText());
        assertEquals(uuidUsername, attribute(user.path("UserAttributes"), "sub"));
        assertEquals(NEW_EMAIL, attribute(user.path("UserAttributes"), "email"));
    }

    // ──────────────────────────── helpers ────────────────────────────

    private static Response initiateUserPasswordAuth(String username) {
        return cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s",
                    "SECRET_HASH": "%s"
                  }
                }
                """.formatted(clientId, username, PASSWORD, secretHash(username)));
    }

    private static String secretHash(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((username + clientId).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String attribute(JsonNode attributesArray, String name) {
        for (JsonNode attr : attributesArray) {
            if (name.equals(attr.path("Name").asText())) {
                return attr.path("Value").asText();
            }
        }
        return null;
    }

    private static JsonNode decodeJwtPayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        String payload = parts[1];
        int pad = (4 - payload.length() % 4) % 4;
        payload += "=".repeat(pad);
        return OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(payload));
    }
}
