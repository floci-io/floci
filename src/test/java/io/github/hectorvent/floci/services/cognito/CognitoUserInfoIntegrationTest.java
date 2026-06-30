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

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoUserInfoIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static String accessToken;
    private static final String USERNAME = "carol+" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "Perm1234!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPoolClientAndUser() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "UserInfoPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "userinfo-client"
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
                    { "Name": "phone_number", "Value": "+12065550100" },
                    { "Name": "phone_number_verified", "Value": "false" },
                    { "Name": "given_name", "Value": "Carol" },
                    { "Name": "family_name", "Value": "Example" },
                    { "Name": "preferred_username", "Value": "carol-pref" },
                    { "Name": "custom:tenant_id", "Value": "tenant-42" }
                  ]
                }
                """.formatted(poolId, USERNAME, USERNAME))
                .then()
                .statusCode(200);

        cognitoAction("AdminSetUserPassword", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "Password": "%s",
                  "Permanent": true
                }
                """.formatted(poolId, USERNAME, PASSWORD))
                .then()
                .statusCode(200);

        JsonNode authResp = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, USERNAME, PASSWORD));
        accessToken = authResp.path("AuthenticationResult").path("AccessToken").asText();
        assertNotNull(accessToken);
        assertFalse(accessToken.isBlank());
    }

    @Test
    @Order(2)
    void userInfoReturnsClaimsInCognitoShape() throws Exception {
        Response response = given()
                .header("Authorization", "Bearer " + accessToken)
        .when()
                .get("/cognito-idp/oauth2/userInfo");

        response.then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Cache-Control", containsString("no-store"));

        JsonNode body = OBJECT_MAPPER.readTree(response.asString());

        assertEquals(USERNAME, body.path("username").asText());
        assertTrue(body.hasNonNull("sub"));
        assertEquals(USERNAME, body.path("email").asText());
        // email_verified / phone_number_verified must be JSON strings, not booleans.
        assertTrue(body.path("email_verified").isTextual(),
                "email_verified must be a string per AWS Cognito spec");
        assertEquals("true", body.path("email_verified").asText());
        assertTrue(body.path("phone_number_verified").isTextual(),
                "phone_number_verified must be a string per AWS Cognito spec");
        assertEquals("false", body.path("phone_number_verified").asText());
        assertEquals("+12065550100", body.path("phone_number").asText());
        assertEquals("Carol", body.path("given_name").asText());
        assertEquals("Example", body.path("family_name").asText());
        assertEquals("carol-pref", body.path("preferred_username").asText());
        assertEquals("tenant-42", body.path("custom:tenant_id").asText());
    }

    @Test
    @Order(3)
    void userInfoDoesNotEmitNonOidcCamelCaseAliases() throws Exception {
        Response response = given()
                .header("Authorization", "Bearer " + accessToken)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(200)
                .extract().response();

        JsonNode body = OBJECT_MAPPER.readTree(response.asString());
        // Real AWS Cognito only returns snake_case OIDC claims.
        assertFalse(body.has("givenName"), "givenName is not part of the AWS userInfo response");
        assertFalse(body.has("familyName"), "familyName is not part of the AWS userInfo response");
        assertFalse(body.has("preferredUsername"), "preferredUsername is not part of the AWS userInfo response");
        assertFalse(body.has("enabled"), "enabled is not part of the AWS userInfo response");
    }

    @Test
    @Order(4)
    void missingAuthorizationHeaderReturns401WithBearerChallenge() {
        given()
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("error=\"invalid_token\""));
    }

    @Test
    @Order(5)
    void nonBearerAuthorizationReturns401() {
        given()
                .header("Authorization", "Basic dXNlcjpwYXNz")
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    @Order(6)
    void malformedJwtReturns401InvalidToken() {
        given()
                .header("Authorization", "Bearer not-a-real-jwt")
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    @Order(7)
    void undecodablePayloadReturns401InvalidToken() {
        given()
                .header("Authorization", "Bearer header.notbase64!!!.sig")
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    @Order(8)
    void tokenWithUnknownSubReturns401() {
        // Forge a JWT payload with a sub that does not exist in this pool.
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url(("{\"sub\":\"00000000-0000-0000-0000-000000000000\","
                + "\"iss\":\"http://localhost:4566/" + poolId + "\","
                + "\"token_use\":\"access\"}"));
        String forged = header + "." + payload + ".sig";

        given()
                .header("Authorization", "Bearer " + forged)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    @Order(9)
    void tokenWithoutIssuerOrSubReturns401() {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"foo\":\"bar\"}");
        String forged = header + "." + payload + ".sig";

        given()
                .header("Authorization", "Bearer " + forged)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    @Order(10)
    void openIdConfigurationAdvertisesUserInfoEndpoint() throws Exception {
        String openIdResponse = given()
        .when()
                .get("/" + poolId + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .extract()
                .asString();

        JsonNode document = OBJECT_MAPPER.readTree(openIdResponse);
        assertEquals(
                "http://localhost:4566/cognito-idp/oauth2/userInfo",
                document.path("userinfo_endpoint").asText());
    }

    @Test
    @Order(11)
    void userInfoFromRefreshedAccessToken() throws Exception {
        JsonNode initialAuth = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {
                    "USERNAME": "%s",
                    "PASSWORD": "%s"
                  }
                }
                """.formatted(clientId, USERNAME, PASSWORD));
        String refreshToken = initialAuth.path("AuthenticationResult").path("RefreshToken").asText();
        assertNotNull(refreshToken);

        JsonNode refreshed = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "REFRESH_TOKEN_AUTH",
                  "AuthParameters": { "REFRESH_TOKEN": "%s" }
                }
                """.formatted(clientId, refreshToken));
        String refreshedAccess = refreshed.path("AuthenticationResult").path("AccessToken").asText();
        assertNotNull(refreshedAccess);

        String refreshedResponse = given()
                .header("Authorization", "Bearer " + refreshedAccess)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(200)
                .extract()
                .asString();
        JsonNode refreshedBody = OBJECT_MAPPER.readTree(refreshedResponse);
        assertEquals(USERNAME, refreshedBody.path("username").asText());
        assertEquals("true", refreshedBody.path("email_verified").asText());
        assertEquals("tenant-42", refreshedBody.path("custom:tenant_id").asText());
    }

    @Test
    @Order(12)
    void issuerFromOtherPoolReturns401() {
        // Create a second pool with no users, forge a token claiming to come from it.
        String secondPoolId;
        try {
            JsonNode secondPool = cognitoJson("CreateUserPool", """
                    { "PoolName": "OtherPool" }
                    """);
            secondPoolId = secondPool.path("UserPool").path("Id").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url(("{\"sub\":\"unknown\","
                + "\"iss\":\"http://localhost:4566/" + secondPoolId + "\"}"));
        String forged = header + "." + payload + ".sig";

        given()
                .header("Authorization", "Bearer " + forged)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
        // Touch the pool id so the variable is meaningful in the assertion message.
        assertNotNull(secondPoolId);
    }

    @Test
    @Order(13)
    void issuerWithoutPoolIdSegmentReturns401() {
        String header = base64Url("{\"alg\":\"none\"}");
        // iss ends in a segment without `_` — must not be parsed as a pool id.
        String payload = base64Url("{\"sub\":\"x\",\"iss\":\"http://localhost:4566/no-pool-here\"}");
        String forged = header + "." + payload + ".sig";

        given()
                .header("Authorization", "Bearer " + forged)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    private static String base64Url(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
