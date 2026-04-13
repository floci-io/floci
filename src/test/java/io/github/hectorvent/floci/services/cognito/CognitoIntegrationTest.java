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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoIntegrationTest {

    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static final String username = "alice+" + UUID.randomUUID() + "@example.com";
    private static final String password = "Perm1234!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPoolClientAndUser() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "JwtPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "jwt-client"
                }
                """.formatted(poolId));
        clientId = clientResponse.path("UserPoolClient").path("ClientId").asText();

        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" }
                  ]
                }
                """.formatted(poolId, username, username))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, username, password))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void initiateAuthReturnsAuthenticationResult() {
        cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, username, password))
                .then()
                .statusCode(200)
                .body("AuthenticationResult.AccessToken", org.hamcrest.Matchers.notNullValue())
                .body("AuthenticationResult.IdToken", org.hamcrest.Matchers.notNullValue())
                .body("AuthenticationResult.RefreshToken", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @Order(3)
    void authTokensAreSignedWithPublishedRsaJwksKey() throws Exception {
        Response authResponse = cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, username, password));

        authResponse.then().statusCode(200);

        String accessToken = authResponse.jsonPath().getString("AuthenticationResult.AccessToken");
        JsonNode header = decodeJwtHeader(accessToken);
        JsonNode payload = decodeJwtPayload(accessToken);
        assertEquals("RS256", header.path("alg").asText());
        assertEquals(poolId, header.path("kid").asText());
        assertEquals("http://localhost:4566/" + poolId, payload.path("iss").asText());
        assertEquals(username, payload.path("username").asText());
        assertEquals("access", payload.path("token_use").asText());

        String jwksResponse = given()
        .when()
                .get("/" + poolId + "/.well-known/jwks.json")
        .then()
                .statusCode(200)
                .extract()
                .asString();

        JsonNode jwks = OBJECT_MAPPER.readTree(jwksResponse);
        JsonNode key = jwks.path("keys").get(0);
        assertNotNull(key);
        assertEquals("RSA", key.path("kty").asText());
        assertEquals("RS256", key.path("alg").asText());
        assertEquals("sig", key.path("use").asText());
        assertEquals(poolId, key.path("kid").asText());
        assertTrue(key.hasNonNull("n"));
        assertTrue(key.hasNonNull("e"));
        assertTrue(verifyJwtSignature(accessToken, key));
    }

    @Test
    @Order(4)
    void openIdConfigurationPublishesIssuerAndJwksUri() throws Exception {
        String openIdResponse = given()
        .when()
                .get("/" + poolId + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .extract()
                .asString();

        JsonNode document = OBJECT_MAPPER.readTree(openIdResponse);
        assertEquals("http://localhost:4566/" + poolId, document.path("issuer").asText());
        assertEquals(
                "http://localhost:4566/" + poolId + "/.well-known/jwks.json",
                document.path("jwks_uri").asText());
        assertEquals("public", document.path("subject_types_supported").get(0).asText());
        assertEquals("RS256", document.path("id_token_signing_alg_values_supported").get(0).asText());
    }

    // ── Groups ────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createGroup() throws Exception {
        JsonNode resp = cognitoJson("CreateGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin",
                  "Description": "Admin group",
                  "Precedence": 1
                }
                """.formatted(poolId));
        assertEquals("admin", resp.path("Group").path("GroupName").asText());
        assertEquals(poolId, resp.path("Group").path("UserPoolId").asText());
        assertEquals("Admin group", resp.path("Group").path("Description").asText());
        assertEquals(1, resp.path("Group").path("Precedence").asInt());
    }

    @Test
    @Order(11)
    void createGroupDuplicate() {
        cognitoAction("CreateGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin",
                  "Description": "Admin group",
                  "Precedence": 1
                }
                """.formatted(poolId))
                .then()
                .statusCode(400);
    }

    @Test
    @Order(12)
    void getGroup() throws Exception {
        JsonNode resp = cognitoJson("GetGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin"
                }
                """.formatted(poolId));
        assertEquals("admin", resp.path("Group").path("GroupName").asText());
    }

    @Test
    @Order(13)
    void listGroups() throws Exception {
        JsonNode resp = cognitoJson("ListGroups", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId));
        assertEquals(1, resp.path("Groups").size());
        assertEquals("admin", resp.path("Groups").get(0).path("GroupName").asText());
    }

    @Test
    @Order(14)
    void adminAddUserToGroup() {
        cognitoAction("AdminAddUserToGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin",
                  "Username": "%s"
                }
                """.formatted(poolId, username))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(15)
    void adminListGroupsForUser() throws Exception {
        JsonNode resp = cognitoJson("AdminListGroupsForUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, username));
        assertEquals(1, resp.path("Groups").size());
        assertEquals("admin", resp.path("Groups").get(0).path("GroupName").asText());
    }

    @Test
    @Order(16)
    void authenticateAndVerifyGroupsInToken() throws Exception {
        Response authResponse = cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, username, password));

        authResponse.then().statusCode(200);

        String accessToken = authResponse.jsonPath().getString("AuthenticationResult.AccessToken");
        JsonNode payload = decodeJwtPayload(accessToken);

        assertTrue(payload.has("cognito:groups"),
                "JWT payload should contain cognito:groups claim");
        assertTrue(payload.path("cognito:groups").toString().contains("\"admin\""),
                "JWT payload should contain admin group");
    }

    @Test
    @Order(17)
    void adminRemoveUserFromGroup() {
        cognitoAction("AdminRemoveUserFromGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin",
                  "Username": "%s"
                }
                """.formatted(poolId, username))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(18)
    void adminListGroupsForUserEmpty() throws Exception {
        JsonNode resp = cognitoJson("AdminListGroupsForUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, username));
        assertEquals(0, resp.path("Groups").size());
    }

    @Test
    @Order(19)
    void deleteGroup() {
        cognitoAction("DeleteGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(20)
    void getGroupNotFound() {
        cognitoAction("GetGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "admin"
                }
                """.formatted(poolId))
                .then()
                .statusCode(404);
    }

    // ── Issue #228: AccessToken contains client_id ─────────────────────

    @Test
    @Order(30)
    void accessTokenContainsClientId() throws Exception {
        String token = initiateAuthAndGetAccessToken();
        JsonNode payload = decodeJwtPayload(token);
        assertEquals(clientId, payload.path("client_id").asText(),
                "AccessToken must contain client_id matching the requesting client");
    }

    @Test
    @Order(31)
    void idTokenDoesNotContainClientId() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));
        String idToken = auth.path("AuthenticationResult").path("IdToken").asText();
        JsonNode payload = decodeJwtPayload(idToken);
        assertTrue(payload.path("client_id").isMissingNode(),
                "IdToken should not contain client_id");
    }

    // ── Issue #229: Password verification ──────────────────────────────

    @Test
    @Order(40)
    void initiateAuthRejectsWrongPassword() {
        cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "WrongPassword!" }
                }
                """.formatted(clientId, username))
                .then()
                .statusCode(400);
    }

    // ── Issue #220: Lookup by sub and email ─────────────────────────────

    @Test
    @Order(50)
    void adminGetUserBySubUuid() throws Exception {
        JsonNode user = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, username));
        String sub = null;
        for (JsonNode attr : user.path("UserAttributes")) {
            if ("sub".equals(attr.path("Name").asText())) {
                sub = attr.path("Value").asText();
                break;
            }
        }
        assertNotNull(sub, "User should have a sub attribute");

        JsonNode bySubUser = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, sub));
        assertEquals(username, bySubUser.path("Username").asText(),
                "AdminGetUser with sub UUID should return the same user");
    }

    @Test
    @Order(51)
    void adminGetUserByEmailAlias() throws Exception {
        JsonNode byEmail = cognitoJson("AdminGetUser", """
                { "UserPoolId": "%s", "Username": "%s" }
                """.formatted(poolId, username));
        assertEquals(username, byEmail.path("Username").asText());
    }

    // ── Issue #233: ListUsers Filter ─────────────────────────────────────

    @Test
    @Order(60)
    void listUsersFilterByEmailExactMatch() throws Exception {
        JsonNode resp = cognitoJson("ListUsers", """
                {
                  "UserPoolId": "%s",
                  "Filter": "email = \\"%s\\""
                }
                """.formatted(poolId, username));
        assertEquals(1, resp.path("Users").size(),
                "Filter by email should return exactly one matching user");
        assertEquals(username, resp.path("Users").get(0).path("Username").asText());
    }

    @Test
    @Order(61)
    void listUsersFilterByEmailPrefixStartsWith() throws Exception {
        String prefix = username.substring(0, 5);
        JsonNode resp = cognitoJson("ListUsers", """
                {
                  "UserPoolId": "%s",
                  "Filter": "email ^= \\"%s\\""
                }
                """.formatted(poolId, prefix));
        assertTrue(resp.path("Users").size() >= 1,
                "Prefix filter should return at least the test user");
    }

    @Test
    @Order(62)
    void listUsersNoFilterReturnsAll() throws Exception {
        JsonNode resp = cognitoJson("ListUsers", """
                { "UserPoolId": "%s" }
                """.formatted(poolId));
        assertTrue(resp.path("Users").size() >= 1);
    }

    // ── Issue #234: GetTokensFromRefreshToken ────────────────────────────

    @Test
    @Order(70)
    void getTokensFromRefreshTokenReturnsAccessAndIdToken() throws Exception {
        JsonNode authResp = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));
        String refreshToken = authResp.path("AuthenticationResult").path("RefreshToken").asText();
        assertNotNull(refreshToken);

        JsonNode refreshResp = cognitoJson("GetTokensFromRefreshToken", """
                {
                  "ClientId": "%s",
                  "RefreshToken": "%s"
                }
                """.formatted(clientId, refreshToken));

        assertNotNull(refreshResp.path("AuthenticationResult").path("AccessToken").asText(null));
        assertNotNull(refreshResp.path("AuthenticationResult").path("IdToken").asText(null));
        assertTrue(refreshResp.path("AuthenticationResult").path("RefreshToken").isMissingNode(),
                "GetTokensFromRefreshToken should not return a new RefreshToken");
    }

    @Test
    @Order(71)
    void refreshTokenAuthFlowReturnsNewTokens() throws Exception {
        JsonNode authResp = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));
        String refreshToken = authResp.path("AuthenticationResult").path("RefreshToken").asText();

        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": { "REFRESH_TOKEN": "%s" }
                }
                """.formatted(clientId, refreshToken));

        assertNotNull(refreshed.path("AuthenticationResult").path("AccessToken").asText(null));
        assertNotNull(refreshed.path("AuthenticationResult").path("IdToken").asText(null));
    }

    // ── Client Secrets ────────────────────────────────────────────────

    private static String clientSecretId1;
    private static String clientSecretId2;

    @Test
    @Order(80)
    void listUserPoolClientSecretsInitiallyEmpty() throws Exception {
        JsonNode resp = cognitoJson("ListUserPoolClientSecrets", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId));
        assertEquals(0, resp.path("ClientSecrets").size());
    }

    @Test
    @Order(81)
    void addUserPoolClientSecretAutoGeneratesValue() throws Exception {
        JsonNode resp = cognitoJson("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId));
        clientSecretId1 = resp.path("ClientSecretId").asText();
        assertNotNull(clientSecretId1);
        assertTrue(clientSecretId1.startsWith(clientId + "--"),
                "ClientSecretId should be prefixed with the ClientId");
        assertNotNull(resp.path("ClientSecretValue").asText(null),
                "Auto-generated secret should include ClientSecretValue in response");
        assertTrue(resp.path("ClientSecretCreateDate").asLong() > 0);
    }

    @Test
    @Order(82)
    void addUserPoolClientSecretWithExplicitValue() throws Exception {
        String clientSecretValue = UUID.randomUUID().toString().replaceAll("-", "");
        JsonNode resp = cognitoJson("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s",
                  "ClientSecret": "%s"
                }
                """.formatted(clientId, poolId, clientSecretValue));
        clientSecretId2 = resp.path("ClientSecretId").asText();
        assertNotNull(clientSecretId2);
        assertTrue(resp.path("ClientSecretValue").isMissingNode(),
                "Explicit secret should not include ClientSecretValue in response");
    }

    @Test
    @Order(83)
    void listUserPoolClientSecretsReturnsTwo() throws Exception {
        JsonNode resp = cognitoJson("ListUserPoolClientSecrets", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId));
        assertEquals(2, resp.path("ClientSecrets").size());
    }

    @Test
    @Order(84)
    void addUserPoolClientSecretExceedsLimit() {
        cognitoAction("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId))
                .then()
                .statusCode(400);
    }

    @Test
    @Order(85)
    void deleteUserPoolClientSecretNotFound() {
        cognitoAction("DeleteUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "ClientSecretId": "nonexistent",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId))
                .then()
                .statusCode(404);
    }

    @Test
    @Order(86)
    void deleteUserPoolClientSecretCannotDeleteOnlyOne() {
        cognitoAction("DeleteUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "ClientSecretId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, clientSecretId1, poolId))
                .then()
                .statusCode(200);

        cognitoAction("DeleteUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "ClientSecretId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, clientSecretId2, poolId))
                .then()
                .statusCode(400);
    }

    @Test
    @Order(87)
    void listUserPoolClientSecretsAfterDelete() throws Exception {
        JsonNode resp = cognitoJson("ListUserPoolClientSecrets", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId));
        assertEquals(1, resp.path("ClientSecrets").size());
        assertEquals(clientSecretId2, resp.path("ClientSecrets").get(0).path("ClientSecretId").asText());
    }

    @Test
    @Order(88)
    void fullRotateScenario() throws Exception {
        // Set up a resource server so the OAuth client_credentials flow has valid scopes
        cognitoJson("CreateResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Identifier": "api",
                  "Name": "API",
                  "Scopes": [
                    { "ScopeName": "read", "ScopeDescription": "Read access" }
                  ]
                }
                """.formatted(poolId));

        // Create a confidential client with client_credentials flow enabled
        JsonNode clientResp = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "rotation-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["client_credentials"],
                  "AllowedOAuthScopes": ["api/read"]
                }
                """.formatted(poolId));
        String rotClientId = clientResp.path("UserPoolClient").path("ClientId").asText();
        String secret1Value = clientResp.path("UserPoolClient").path("ClientSecret").asText();

        // client-secret-1 is still valid — authenticate with client-credentials successfully
        oauthToken(rotClientId, secret1Value).then().statusCode(200);

        // grab secret-1's ID so we can delete it later
        JsonNode secrets = cognitoJson("ListUserPoolClientSecrets", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(rotClientId, poolId));
        assertEquals(1, secrets.path("ClientSecrets").size());
        String secret1Id = secrets.path("ClientSecrets").get(0).path("ClientSecretId").asText();

        // add new client-secret (auto-generated)
        JsonNode addResp = cognitoJson("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(rotClientId, poolId));
        String secret2Value = addResp.path("ClientSecretValue").asText();
        assertNotNull(secret2Value);

        // authenticate with new client-credentials successfully
        oauthToken(rotClientId, secret2Value).then().statusCode(200);

        // delete client-credentials 1
        cognitoAction("DeleteUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "ClientSecretId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(rotClientId, secret1Id, poolId))
                .then()
                .statusCode(200);

        // authentication with client credentials 1 fails
        oauthToken(rotClientId, secret1Value).then().statusCode(400);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static Response oauthToken(String oauthClientId, String oauthClientSecret) {
        return given()
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", oauthClientId)
                .formParam("client_secret", oauthClientSecret)
        .when()
                .post("/cognito-idp/oauth2/token");
    }

    private static Response cognitoAction(String action, String body) {
        return given()
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + action)
                .contentType(COGNITO_CONTENT_TYPE)
                .body(body)
        .when()
                .post("/");
    }

    private static JsonNode cognitoJson(String action, String body) throws Exception {
        String response = cognitoAction(action, body)
                .then()
                .statusCode(200)
                .extract()
                .asString();
        return OBJECT_MAPPER.readTree(response);
    }

    private static JsonNode decodeJwtPayload(String token) throws Exception {
        return decodeJwtPart(token, 1);
    }

    private static JsonNode decodeJwtHeader(String token) throws Exception {
        return decodeJwtPart(token, 0);
    }

    private static JsonNode decodeJwtPart(String token, int partIndex) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        return OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(padBase64(parts[partIndex])));
    }

    private static boolean verifyJwtSignature(String token, JsonNode jwk) throws Exception {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(jwk.path("n").asText())));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(jwk.path("e").asText())));
        RSAPublicKeySpec keySpec = new RSAPublicKeySpec(modulus, exponent);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getUrlDecoder().decode(padBase64(parts[2])));
    }

    private static String initiateAuthAndGetAccessToken() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));
        return auth.path("AuthenticationResult").path("AccessToken").asText();
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }
}
