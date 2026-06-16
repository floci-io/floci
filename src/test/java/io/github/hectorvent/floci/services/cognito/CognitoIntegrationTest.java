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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                  "PoolName": "JwtPool",
                  "Schema": [
                    {
                      "Name": "department",
                      "AttributeDataType": "String",
                      "Mutable": true,
                      "Required": false
                    }
                  ]
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
                    { "Name": "email", "Value": "%s" },
                    { "Name": "email_verified", "Value": "true" },
                    { "Name": "phone_number_verified", "Value": "true" },
                    { "Name": "given_name", "Value": "Test" },
                    { "Name": "family_name", "Value": "User" },
                    { "Name": "custom:department", "Value": "engineering" }
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

    @Test
    @Order(5)
    void describeUserPoolReturnsAllTwentyStandardAttributes() throws Exception {
        JsonNode body = cognitoJson("DescribeUserPool", """
                { "UserPoolId": "%s" }
                """.formatted(poolId));

        JsonNode schema = body.path("UserPool").path("SchemaAttributes");
        assertTrue(schema.size() >= 20,
                "DescribeUserPool must include all 20 Cognito standard attributes");

        java.util.Set<String> names = new java.util.HashSet<>();
        schema.forEach(attr -> names.add(attr.path("Name").asText()));

        for (String expected : List.of("sub", "name", "given_name", "family_name", "middle_name",
                "nickname", "preferred_username", "profile", "picture", "website",
                "email", "email_verified", "gender", "birthdate", "zoneinfo",
                "locale", "phone_number", "phone_number_verified", "address", "updated_at")) {
            assertTrue(names.contains(expected), "Missing standard attribute in DescribeUserPool response: " + expected);
        }

        // spot-check: sub must be required and immutable
        JsonNode sub = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(schema.elements(), 0), false)
                .filter(n -> "sub".equals(n.path("Name").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(sub.path("Required").asBoolean(), "sub must be Required");
        assertFalse(sub.path("Mutable").asBoolean(), "sub must not be Mutable");
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

    // ── UpdateGroup & ListUsersInGroup ────────────────────────────────

    @Test
    @Order(21)
    void updateGroup() throws Exception {
        // Create a group to update
        cognitoJson("CreateGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors",
                  "Description": "Original description",
                  "Precedence": 10
                }
                """.formatted(poolId));

        // Update the group
        JsonNode resp = cognitoJson("UpdateGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors",
                  "Description": "Updated description",
                  "Precedence": 5,
                  "RoleArn": "arn:aws:iam::000000000000:role/editors-role"
                }
                """.formatted(poolId));

        JsonNode group = resp.path("Group");
        assertEquals("editors", group.path("GroupName").asText());
        assertEquals("Updated description", group.path("Description").asText());
        assertEquals(5, group.path("Precedence").asInt());
        assertEquals("arn:aws:iam::000000000000:role/editors-role", group.path("RoleArn").asText());

        // Verify via GetGroup
        JsonNode getResp = cognitoJson("GetGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors"
                }
                """.formatted(poolId));
        assertEquals("Updated description", getResp.path("Group").path("Description").asText());
        assertEquals(5, getResp.path("Group").path("Precedence").asInt());
    }

    @Test
    @Order(22)
    void listUsersInGroup() throws Exception {
        // Add user to editors group
        cognitoAction("AdminAddUserToGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors",
                  "Username": "%s"
                }
                """.formatted(poolId, username))
                .then().statusCode(200);

        // List users in group
        JsonNode resp = cognitoJson("ListUsersInGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors"
                }
                """.formatted(poolId));

        assertEquals(1, resp.path("Users").size());
        assertEquals(username, resp.path("Users").get(0).path("Username").asText());
    }

    @Test
    @Order(23)
    void listUsersInGroupEmpty() throws Exception {
        // Remove user and verify empty list
        cognitoAction("AdminRemoveUserFromGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors",
                  "Username": "%s"
                }
                """.formatted(poolId, username))
                .then().statusCode(200);

        JsonNode resp = cognitoJson("ListUsersInGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors"
                }
                """.formatted(poolId));
        assertEquals(0, resp.path("Users").size());

        // Cleanup
        cognitoAction("DeleteGroup", """
                {
                  "UserPoolId": "%s",
                  "GroupName": "editors"
                }
                """.formatted(poolId))
                .then().statusCode(200);
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

    // ── Issue #452: IdToken contains aud claim ─────────────────────────

    @Test
    @Order(32)
    void idTokenContainsAudClaimMatchingClientId() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));
        String idToken = auth.path("AuthenticationResult").path("IdToken").asText();
        JsonNode payload = decodeJwtPayload(idToken);
        assertEquals(clientId, payload.path("aud").asText(),
                "IdToken must contain aud claim set to the requesting client ID");
    }

    @Test
    @Order(33)
    void accessTokenDoesNotContainAudClaim() throws Exception {
        String accessToken = initiateAuthAndGetAccessToken();
        JsonNode payload = decodeJwtPayload(accessToken);
        assertTrue(payload.path("aud").isMissingNode(),
                "AccessToken should not contain aud claim");
    }

    @Test
    @Order(34)
    void idTokenFromRefreshTokenContainsAudClaim() throws Exception {
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
        String idToken = refreshed.path("AuthenticationResult").path("IdToken").asText();
        JsonNode payload = decodeJwtPayload(idToken);
        assertEquals(clientId, payload.path("aud").asText(),
                "IdToken from refresh flow must contain aud claim set to the requesting client ID");
    }

    // ── Issue #416: ListUserPoolClients response matches spec ──────────

    @Test
    @Order(35)
    void listUserPoolClientsReturnsOnlyDescriptionFields() throws Exception {
        // Create a client with a secret to ensure extra fields exist
        JsonNode secretClient = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "secret-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlows": ["code"],
                  "AllowedOAuthScopes": ["openid"]
                }
                """.formatted(poolId));
        String secretClientId = secretClient.path("UserPoolClient").path("ClientId").asText();
        assertNotNull(secretClient.path("UserPoolClient").path("ClientSecret").asText(null),
                "Created client should have a ClientSecret");

        // List clients and verify only description fields are returned
        JsonNode listResp = cognitoJson("ListUserPoolClients", """
                { "UserPoolId": "%s" }
                """.formatted(poolId));

        assertTrue(listResp.path("UserPoolClients").size() >= 2,
                "Should list at least 2 clients");

        for (JsonNode client : listResp.path("UserPoolClients")) {
            // Required fields
            assertTrue(client.has("ClientId"), "Must have ClientId");
            assertTrue(client.has("ClientName"), "Must have ClientName");
            assertTrue(client.has("UserPoolId"), "Must have UserPoolId");

            // Fields that must NOT appear per AWS spec (UserPoolClientDescription)
            assertTrue(client.path("ClientSecret").isMissingNode(),
                    "ListUserPoolClients must not include ClientSecret");
            assertTrue(client.path("GenerateSecret").isMissingNode(),
                    "ListUserPoolClients must not include GenerateSecret");
            assertTrue(client.path("AllowedOAuthFlows").isMissingNode(),
                    "ListUserPoolClients must not include AllowedOAuthFlows");
            assertTrue(client.path("AllowedOAuthScopes").isMissingNode(),
                    "ListUserPoolClients must not include AllowedOAuthScopes");
            assertTrue(client.path("AllowedOAuthFlowsUserPoolClient").isMissingNode(),
                    "ListUserPoolClients must not include AllowedOAuthFlowsUserPoolClient");
            assertTrue(client.path("CreationDate").isMissingNode(),
                    "ListUserPoolClients must not include CreationDate");
            assertTrue(client.path("LastModifiedDate").isMissingNode(),
                    "ListUserPoolClients must not include LastModifiedDate");
        }

        // Verify DescribeUserPoolClient still returns full details
        JsonNode describeResp = cognitoJson("DescribeUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, secretClientId));
        JsonNode fullClient = describeResp.path("UserPoolClient");
        assertNotNull(fullClient.path("ClientSecret").asText(null),
                "DescribeUserPoolClient must include ClientSecret");
        assertTrue(fullClient.has("GenerateSecret"),
                "DescribeUserPoolClient must include GenerateSecret");
    }

    @Test
    @Order(36)
    void updateUserPoolClient() throws Exception {
        // 1. Create a client
        JsonNode createResp = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "initial-name"
                }
                """.formatted(poolId));
        String cid = createResp.path("UserPoolClient").path("ClientId").asText();

        // 2. Update the client
        cognitoJson("UpdateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "ClientName": "updated-name",
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["code", "implicit"],
                  "AllowedOAuthScopes": ["email", "openid"]
                }
                """.formatted(poolId, cid));

        // 3. Verify the updates
        JsonNode describeResp = cognitoJson("DescribeUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, cid));
        JsonNode client = describeResp.path("UserPoolClient");

        assertEquals("updated-name", client.path("ClientName").asText());
        assertEquals(true, client.path("AllowedOAuthFlowsUserPoolClient").asBoolean());
        
        JsonNode flows = client.path("AllowedOAuthFlows");
        assertEquals(2, flows.size());
        assertTrue(flows.toString().contains("code"));
        assertTrue(flows.toString().contains("implicit"));

        JsonNode scopes = client.path("AllowedOAuthScopes");
        assertEquals(2, scopes.size());
        assertTrue(scopes.toString().contains("email"));
        assertTrue(scopes.toString().contains("openid"));
    }

    // ── Issue #984: ID token includes standard + custom user attributes ─

    @Test
    @Order(37)
    void idTokenIncludesStandardAndCustomAttributes() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));

        JsonNode payload = decodeJwtPayload(
                auth.path("AuthenticationResult").path("IdToken").asText());

        System.out.println(payload);

        assertEquals("Test", payload.path("given_name").asText(),
                "IdToken should include given_name");
        assertEquals("User", payload.path("family_name").asText(),
                "IdToken should include family_name");
        assertEquals("engineering", payload.path("custom:department").asText(),
                "IdToken should include custom:department");
        assertEquals(true, payload.path("email_verified").isBoolean(),
                "IdToken should include email_verified serialized as a JSON boolean");
        assertEquals(true, payload.path("phone_number_verified").isBoolean(),
                "IdToken should include phone_number_verified serialized as a JSON boolean");
    }

    @Test
    @Order(38)
    void accessTokenOmitsProfileAttributes() throws Exception {
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, username, password));

        JsonNode payload = decodeJwtPayload(
                auth.path("AuthenticationResult").path("AccessToken").asText());

        assertTrue(payload.path("given_name").isMissingNode(),
                "AccessToken should not include given_name");
        assertTrue(payload.path("family_name").isMissingNode(),
                "AccessToken should not include family_name");
        assertTrue(payload.path("custom:department").isMissingNode(),
                "AccessToken should not include custom:* attributes");
    }

    @Test
    @Order(39)
    void refreshedIdTokenIncludesUserAttributes() throws Exception {
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

        JsonNode payload = decodeJwtPayload(
                refreshed.path("AuthenticationResult").path("IdToken").asText());

        assertEquals("Test", payload.path("given_name").asText(),
                "Refreshed IdToken should still include given_name");
        assertEquals("engineering", payload.path("custom:department").asText(),
                "Refreshed IdToken should still include custom:* attributes");
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

    @ParameterizedTest
    @Order(81)
    @MethodSource("generateInvalidUserPoolSecret")
    void addUserPoolClientSecretInvalid(String clientSecret) {
        cognitoAction("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s",
                  "ClientSecret": "%s"
                }
                """.formatted(clientId, poolId, clientSecret))
                .then()
                .statusCode(400);
    }

    public static Stream<Arguments> generateInvalidUserPoolSecret() {
        return Stream.of(
            Arguments.of("a".repeat(23)), // too short
            Arguments.of("a".repeat(65)), // too large
            Arguments.of("$".repeat(32)) // contains invalid characters
        );
    }

    @Test
    @Order(82)
    void addUserPoolClientSecretAutoGeneratesValue() throws Exception {
        JsonNode resp = cognitoJson("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(clientId, poolId));
        JsonNode clientSecretDescriptor = resp.path("ClientSecretDescriptor");
        clientSecretId1 = clientSecretDescriptor.path("ClientSecretId").asText();
        assertNotNull(clientSecretId1);
        assertTrue(clientSecretId1.startsWith(clientId + "--"),
                "ClientSecretId should be prefixed with the ClientId");
        assertNotNull(clientSecretDescriptor.path("ClientSecretValue").asText(null),
                "Auto-generated secret should include ClientSecretValue in response");
        assertTrue(clientSecretDescriptor.path("ClientSecretCreateDate").asLong() > 0);
    }

    @Test
    @Order(83)
    void addUserPoolClientSecretWithExplicitValue() throws Exception {
        String clientSecretValue = UUID.randomUUID().toString().replaceAll("-", "");
        JsonNode resp = cognitoJson("AddUserPoolClientSecret", """
                {
                  "ClientId": "%s",
                  "UserPoolId": "%s",
                  "ClientSecret": "%s"
                }
                """.formatted(clientId, poolId, clientSecretValue));
        clientSecretId2 = resp.path("ClientSecretDescriptor").path("ClientSecretId").asText();
        assertNotNull(clientSecretId2);
        assertTrue(resp.path("ClientSecretDescriptor").path("ClientSecretValue").isMissingNode(),
                "Explicit secret should not include ClientSecretValue in response");
    }

    @Test
    @Order(84)
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
    @Order(85)
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
    @Order(86)
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
    @Order(87)
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
    @Order(88)
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
    @Order(89)
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
        String secret2Value = addResp.path("ClientSecretDescriptor")
                .path("ClientSecretValue").asText();
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

        // secret 2 still works after rotation
        oauthToken(rotClientId, secret2Value).then().statusCode(200);
    }

    @Test
    @Order(90)
    void adminResetUserPasswordBlocksAuth() throws Exception {
        // 1. Reset the user's password
        cognitoAction("AdminResetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, username))
                .then()
                .statusCode(200);

        // 2. Authentication should now fail with PasswordResetRequiredException
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
                .statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("PasswordResetRequiredException"));

        // 3. Admin sets a new password
        String newPassword = "NewPassword123!";
        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, username, newPassword))
                .then()
                .statusCode(200);

        // 4. Authentication works again with new password
        cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, username, newPassword))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(91)
    void adminConfirmSignUp() throws Exception {
        String testUser = "unconfirmed+" + UUID.randomUUID() + "@example.com";
        // SignUp user - initially UNCONFIRMED
        cognitoJson("SignUp", """
                {
                  "ClientId": "%s",
                  "Username": "%s",
                  "Password": "%s"
                }
                """.formatted(clientId, testUser, password));

        // Get user details to verify user status is UNCONFIRMED
        JsonNode userResp = cognitoJson("AdminGetUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, testUser));
        assertEquals("UNCONFIRMED", userResp.path("UserStatus").asText());

        // Admin confirms user
        cognitoAction("AdminConfirmSignUp", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, testUser))
                .then()
                .statusCode(200);

        // Get user details to verify user status is CONFIRMED
        JsonNode userRespConfirmed = cognitoJson("AdminGetUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(poolId, testUser));
        assertEquals("CONFIRMED", userRespConfirmed.path("UserStatus").asText());
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

    @Test
    @Order(91)
    void adminCreateUserWithMessageActionResendRefreshesExistingUser() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                { "PoolName": "ResendPool" }
                """);
        String resendPoolId = poolResponse.path("UserPool").path("Id").asText();
        String resendUser = "resend-" + UUID.randomUUID();

        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "TemporaryPassword": "TempPass1!",
                  "UserAttributes": [ { "Name": "email", "Value": "resend@example.com" } ]
                }
                """.formatted(resendPoolId, resendUser))
                .then()
                .statusCode(200);

        JsonNode resent = cognitoJson("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "MessageAction": "RESEND",
                  "UserAttributes": [ { "Name": "email", "Value": "resend@example.com" } ]
                }
                """.formatted(resendPoolId, resendUser));

        assertEquals("FORCE_CHANGE_PASSWORD",
                resent.path("User").path("UserStatus").asText());
    }

    @Test
    @Order(92)
    void addCustomAttributesAndSchemaIsUpdated() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "SchemaTestPool"
                }
                """);
        String customPoolId = poolResponse.path("UserPool").path("Id").asText();

        cognitoAction("AddCustomAttributes", """
                {
                  "UserPoolId": "%s",
                  "CustomAttributes": [
                    {
                      "Name": "location",
                      "AttributeDataType": "String",
                      "Mutable": true
                    },
                    {
                      "Name": "custom:hobby",
                      "AttributeDataType": "String",
                      "Mutable": true
                    }
                  ]
                }
                """.formatted(customPoolId))
                .then()
                .statusCode(200);

        JsonNode describeResponse = cognitoJson("DescribeUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(customPoolId));

        JsonNode schema = describeResponse.path("UserPool").path("SchemaAttributes");
        assertTrue(schema.isArray());

        boolean hasLocation = false;
        boolean hasHobby = false;
        for (JsonNode attr : schema) {
            String name = attr.path("Name").asText();
            if ("custom:location".equals(name)) {
                hasLocation = true;
            } else if ("custom:hobby".equals(name)) {
                hasHobby = true;
            }
        }
        assertTrue(hasLocation, "custom:location should be in the user pool schema");
        assertTrue(hasHobby, "custom:hobby should be in the user pool schema");
    }

    @Test
    @Order(93)
    void addCustomAttributesValidationAndDeveloperPrefix() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "SchemaValidationPool"
                }
                """);
        String poolId = poolResponse.path("UserPool").path("Id").asText();

        // 1. Happy path developer attribute
        cognitoAction("AddCustomAttributes", """
                {
                  "UserPoolId": "%s",
                  "CustomAttributes": [
                    {
                      "Name": "devattr",
                      "AttributeDataType": "String",
                      "DeveloperOnlyAttribute": true
                    }
                  ]
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);

        JsonNode describeResponse = cognitoJson("DescribeUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId));

        JsonNode schema = describeResponse.path("UserPool").path("SchemaAttributes");
        boolean hasDevAttr = false;
        for (JsonNode attr : schema) {
            if ("dev:devattr".equals(attr.path("Name").asText())) {
                hasDevAttr = true;
                break;
            }
        }
        assertTrue(hasDevAttr, "dev:devattr should be in the user pool schema with dev: prefix");

        // 2. Reject duplicate attribute
        cognitoAction("AddCustomAttributes", """
                {
                  "UserPoolId": "%s",
                  "CustomAttributes": [
                    {
                      "Name": "devattr",
                      "AttributeDataType": "String",
                      "DeveloperOnlyAttribute": true
                    }
                  ]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400);

        // 3. Reject name longer than 20 characters (after stripping prefix)
        cognitoAction("AddCustomAttributes", """
                {
                  "UserPoolId": "%s",
                  "CustomAttributes": [
                    {
                      "Name": "custom:thisNameIsWayTooLongForCognitoAttributeLimits",
                      "AttributeDataType": "String"
                    }
                  ]
                }
                """.formatted(poolId))
                .then()
                .statusCode(400);

        // 4. Reject empty attributes list
        cognitoAction("AddCustomAttributes", """
                {
                  "UserPoolId": "%s",
                  "CustomAttributes": []
                }
                """.formatted(poolId))
                .then()
                .statusCode(400);
    }

    @Test
    @Order(94)
    void deleteUserAttributesAndVerifyDeleted() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "DeleteAttrPool"
                }
                """);
        String delPoolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "del-client"
                }
                """.formatted(delPoolId));
        String delClientId = clientResponse.path("UserPoolClient").path("ClientId").asText();

        String delUser = "user-" + UUID.randomUUID();
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "user@example.com" },
                    { "Name": "custom:age", "Value": "30" },
                    { "Name": "custom:gender", "Value": "female" }
                  ]
                }
                """.formatted(delPoolId, delUser))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "Password123!",
                  "Permanent": true
                }
                """.formatted(delPoolId, delUser))
                .then()
                .statusCode(200);

        // Delete custom:age via AdminDeleteUserAttributes
        cognitoAction("AdminDeleteUserAttributes", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributeNames": [ "custom:age" ]
                }
                """.formatted(delPoolId, delUser))
                .then()
                .statusCode(200);

        JsonNode userResp = cognitoJson("AdminGetUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s"
                }
                """.formatted(delPoolId, delUser));

        boolean hasAge = false;
        boolean hasGender = false;
        for (JsonNode attr : userResp.path("UserAttributes")) {
            String name = attr.path("Name").asText();
            if ("custom:age".equals(name)) {
                hasAge = true;
            } else if ("custom:gender".equals(name)) {
                hasGender = true;
            }
        }
        assertFalse(hasAge, "custom:age should be deleted");
        assertTrue(hasGender, "custom:gender should still be present");

        // Authenticate to get access token
        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "Password123!"
                  }
                }
                """.formatted(delClientId, delUser));
        String accessToken = auth.path("AuthenticationResult").path("AccessToken").asText();
        assertNotNull(accessToken);

        // Delete custom:gender via DeleteUserAttributes
        cognitoAction("DeleteUserAttributes", """
                {
                  "AccessToken": "%s",
                  "UserAttributeNames": [ "custom:gender" ]
                }
                """.formatted(accessToken))
                .then()
                .statusCode(200);

        // Get user using AccessToken
        JsonNode getResponse = cognitoJson("GetUser", """
                {
                  "AccessToken": "%s"
                }
                """.formatted(accessToken));

        boolean hasGenderAfterDelete = false;
        for (JsonNode attr : getResponse.path("UserAttributes")) {
            if ("custom:gender".equals(attr.path("Name").asText())) {
                hasGenderAfterDelete = true;
            }
        }
        assertFalse(hasGenderAfterDelete, "custom:gender should be deleted");
    }

    // =========================================================================
    // Issue #1306 — User pool client extended configuration and token validity
    // =========================================================================

    @Test
    @Order(95)
    void userPoolClientPersistsExtendedConfigurationFields() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "ExtendedClientPool"
                }
                """);
        String extendedPoolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode createResp = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "extended-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["code"],
                  "AllowedOAuthScopes": ["aws.cognito.signin.user.admin", "openid"],
                  "AnalyticsConfiguration": {
                    "ApplicationId": "d70b2ba36a8c4dc5a04a0451a31a1e12",
                    "ExternalId": "my-external-id",
                    "RoleArn": "arn:aws:iam::123456789012:role/test-cognitouserpool-role",
                    "UserDataShared": true
                  },
                  "CallbackURLs": ["https://example.com", "http://localhost", "myapp://example"],
                  "DefaultRedirectURI": "https://example.com",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                  "AccessTokenValidity": 15,
                  "IdTokenValidity": 20,
                  "LogoutURLs": ["https://example.com/logout"],
                  "ReadAttributes": ["email", "address", "preferred_username"],
                  "RefreshTokenValidity": 7,
                  "TokenValidityUnits": {
                    "AccessToken": "minutes",
                    "IdToken": "minutes",
                    "RefreshToken": "days"
                  },
                  "RefreshTokenRotation": {
                    "Feature": "ENABLED",
                    "RetryGracePeriodSeconds": 30
                  },
                  "EnableTokenRevocation": true,
                  "PreventUserExistenceErrors": "ENABLED",
                  "SupportedIdentityProviders": ["COGNITO", "Google"],
                  "WriteAttributes": ["family_name", "email"]
                }
                """.formatted(extendedPoolId));

        JsonNode created = createResp.path("UserPoolClient");
        String extendedClientId = created.path("ClientId").asText();
        assertTrue(created.path("AllowedOAuthFlowsUserPoolClient").asBoolean());
        assertEquals(1, created.path("AllowedOAuthFlows").size());
        assertEquals("code", created.path("AllowedOAuthFlows").get(0).asText());
        assertEquals(2, created.path("AllowedOAuthScopes").size());
        assertEquals("d70b2ba36a8c4dc5a04a0451a31a1e12",
                created.path("AnalyticsConfiguration").path("ApplicationId").asText());
        assertEquals("my-external-id", created.path("AnalyticsConfiguration").path("ExternalId").asText());
        assertEquals("arn:aws:iam::123456789012:role/test-cognitouserpool-role",
                created.path("AnalyticsConfiguration").path("RoleArn").asText());
        assertTrue(created.path("AnalyticsConfiguration").path("UserDataShared").asBoolean());
        assertEquals(3, created.path("CallbackURLs").size());
        assertEquals("https://example.com", created.path("DefaultRedirectURI").asText());
        assertEquals(2, created.path("ExplicitAuthFlows").size());
        assertEquals(15, created.path("AccessTokenValidity").asInt());
        assertEquals(20, created.path("IdTokenValidity").asInt());
        assertEquals(1, created.path("LogoutURLs").size());
        assertEquals(3, created.path("ReadAttributes").size());
        assertEquals(7, created.path("RefreshTokenValidity").asInt());
        assertEquals("minutes", created.path("TokenValidityUnits").path("AccessToken").asText());
        assertEquals("minutes", created.path("TokenValidityUnits").path("IdToken").asText());
        assertEquals("days", created.path("TokenValidityUnits").path("RefreshToken").asText());
        assertEquals("ENABLED", created.path("RefreshTokenRotation").path("Feature").asText());
        assertEquals(30, created.path("RefreshTokenRotation").path("RetryGracePeriodSeconds").asInt());
        assertTrue(created.path("EnableTokenRevocation").asBoolean());
        assertEquals("ENABLED", created.path("PreventUserExistenceErrors").asText());
        assertEquals(2, created.path("SupportedIdentityProviders").size());
        assertEquals(2, created.path("WriteAttributes").size());

        cognitoJson("UpdateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s",
                  "AllowedOAuthFlowsUserPoolClient": false,
                  "AllowedOAuthFlows": ["implicit"],
                  "AllowedOAuthScopes": ["email"],
                  "AnalyticsConfiguration": {
                    "ApplicationId": "updated-app-id",
                    "ExternalId": "updated-external-id",
                    "RoleArn": "arn:aws:iam::123456789012:role/updated-role",
                    "UserDataShared": false
                  },
                  "CallbackURLs": ["https://updated.example.com/callback"],
                  "DefaultRedirectURI": "https://updated.example.com/callback",
                  "ExplicitAuthFlows": ["ALLOW_USER_SRP_AUTH"],
                  "AccessTokenValidity": 25,
                  "IdTokenValidity": 30,
                  "LogoutURLs": ["https://updated.example.com/logout"],
                  "ReadAttributes": ["email"],
                  "RefreshTokenValidity": 14,
                  "TokenValidityUnits": {
                    "AccessToken": "minutes",
                    "IdToken": "minutes",
                    "RefreshToken": "days"
                  },
                  "RefreshTokenRotation": {
                    "Feature": "DISABLED",
                    "RetryGracePeriodSeconds": 0
                  },
                  "EnableTokenRevocation": false,
                  "PreventUserExistenceErrors": "LEGACY",
                  "SupportedIdentityProviders": ["COGNITO"],
                  "WriteAttributes": ["email"]
                }
                """.formatted(extendedPoolId, extendedClientId));

        JsonNode describeResp = cognitoJson("DescribeUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(extendedPoolId, extendedClientId));
        JsonNode described = describeResp.path("UserPoolClient");

        assertFalse(described.path("AllowedOAuthFlowsUserPoolClient").asBoolean());
        assertEquals(1, described.path("AllowedOAuthFlows").size());
        assertEquals("implicit", described.path("AllowedOAuthFlows").get(0).asText());
        assertEquals(1, described.path("AllowedOAuthScopes").size());
        assertEquals("email", described.path("AllowedOAuthScopes").get(0).asText());
        assertEquals("updated-app-id", described.path("AnalyticsConfiguration").path("ApplicationId").asText());
        assertEquals("updated-external-id", described.path("AnalyticsConfiguration").path("ExternalId").asText());
        assertEquals("arn:aws:iam::123456789012:role/updated-role",
                described.path("AnalyticsConfiguration").path("RoleArn").asText());
        assertFalse(described.path("AnalyticsConfiguration").path("UserDataShared").asBoolean());
        assertEquals(1, described.path("CallbackURLs").size());
        assertEquals("https://updated.example.com/callback", described.path("DefaultRedirectURI").asText());
        assertEquals(1, described.path("ExplicitAuthFlows").size());
        assertEquals("ALLOW_USER_SRP_AUTH", described.path("ExplicitAuthFlows").get(0).asText());
        assertEquals(25, described.path("AccessTokenValidity").asInt());
        assertEquals(30, described.path("IdTokenValidity").asInt());
        assertEquals(1, described.path("LogoutURLs").size());
        assertEquals(1, described.path("ReadAttributes").size());
        assertEquals(14, described.path("RefreshTokenValidity").asInt());
        assertEquals("minutes", described.path("TokenValidityUnits").path("AccessToken").asText());
        assertEquals("minutes", described.path("TokenValidityUnits").path("IdToken").asText());
        assertEquals("days", described.path("TokenValidityUnits").path("RefreshToken").asText());
        assertEquals("DISABLED", described.path("RefreshTokenRotation").path("Feature").asText());
        assertEquals(0, described.path("RefreshTokenRotation").path("RetryGracePeriodSeconds").asInt());
        assertFalse(described.path("EnableTokenRevocation").asBoolean());
        assertEquals("LEGACY", described.path("PreventUserExistenceErrors").asText());
        assertEquals(1, described.path("SupportedIdentityProviders").size());
        assertEquals("COGNITO", described.path("SupportedIdentityProviders").get(0).asText());
        assertEquals(1, described.path("WriteAttributes").size());
        assertEquals("email", described.path("WriteAttributes").get(0).asText());
    }

    @Test
    @Order(96)
    void configuredTokenValidityControlsAuthResultAndJwtExpiry() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "TokenValidityPool"
                }
                """);
        String validityPoolId = poolResponse.path("UserPool").path("Id").asText();
        String validityUsername = "validity+" + UUID.randomUUID() + "@example.com";
        String validityPassword = "Perm1234!";

        JsonNode clientResp = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "token-validity-client",
                  "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                  "AccessTokenValidity": 15,
                  "IdTokenValidity": 20,
                  "RefreshTokenValidity": 7,
                  "TokenValidityUnits": {
                    "AccessToken": "minutes",
                    "IdToken": "minutes",
                    "RefreshToken": "days"
                  }
                }
                """.formatted(validityPoolId));
        String validityClientId = clientResp.path("UserPoolClient").path("ClientId").asText();

        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" },
                    { "Name": "email_verified", "Value": "true" }
                  ],
                  "MessageAction": "SUPPRESS"
                }
                """.formatted(validityPoolId, validityUsername, validityUsername))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(validityPoolId, validityUsername, validityPassword))
                .then()
                .statusCode(200);

        JsonNode auth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(validityClientId, validityUsername, validityPassword));

        JsonNode result = auth.path("AuthenticationResult");
        JsonNode accessPayload = decodeJwtPayload(result.path("AccessToken").asText());
        JsonNode idPayload = decodeJwtPayload(result.path("IdToken").asText());

        assertEquals(900, result.path("ExpiresIn").asInt());
        assertEquals(900L, accessPayload.path("exp").asLong() - accessPayload.path("iat").asLong());
        assertEquals(1200L, idPayload.path("exp").asLong() - idPayload.path("iat").asLong());

        JsonNode refreshAuth = cognitoJson("GetTokensFromRefreshToken", """
                {
                  "ClientId": "%s",
                  "RefreshToken": "%s"
                }
                """.formatted(validityClientId, result.path("RefreshToken").asText()));
        JsonNode refreshResult = refreshAuth.path("AuthenticationResult");
        JsonNode refreshedAccessPayload = decodeJwtPayload(refreshResult.path("AccessToken").asText());
        JsonNode refreshedIdPayload = decodeJwtPayload(refreshResult.path("IdToken").asText());

        assertEquals(900, refreshResult.path("ExpiresIn").asInt());
        assertEquals(900L, refreshedAccessPayload.path("exp").asLong() - refreshedAccessPayload.path("iat").asLong());
        assertEquals(1200L, refreshedIdPayload.path("exp").asLong() - refreshedIdPayload.path("iat").asLong());
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
