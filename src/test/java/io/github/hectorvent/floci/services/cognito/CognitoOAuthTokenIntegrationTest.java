package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoOAuthTokenIntegrationTest {

    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String poolId;
    private static String clientId;
    private static String limitedClientId;
    private static String confidentialClientId;
    private static String confidentialClientSecret;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(COGNITO_CONTENT_TYPE, ContentType.TEXT));
    }

    @Test
    @Order(1)
    void createPoolAndClients() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "OAuthPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();

        JsonNode clientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "oauth-client"
                }
                """.formatted(poolId));
        clientId = clientResponse.path("UserPoolClient").path("ClientId").asText();

        JsonNode confidentialClientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "confidential-oauth-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["client_credentials"],
                  "AllowedOAuthScopes": ["notes/read", "notes/write"]
                }
                """.formatted(poolId));
        confidentialClientId = confidentialClientResponse.path("UserPoolClient").path("ClientId").asText();
        confidentialClientSecret = confidentialClientResponse.path("UserPoolClient").path("ClientSecret").asText();

        JsonNode limitedClientResponse = cognitoJson("CreateUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientName": "limited-oauth-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["client_credentials"],
                  "AllowedOAuthScopes": ["notes/read"]
                }
                """.formatted(poolId));
        limitedClientId = limitedClientResponse.path("UserPoolClient").path("ClientId").asText();

        JsonNode resourceServerResponse = cognitoJson("CreateResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Identifier": "notes",
                  "Name": "Notes API",
                  "Scopes": [
                    {
                      "ScopeName": "read",
                      "ScopeDescription": "Read notes"
                    },
                    {
                      "ScopeName": "write",
                      "ScopeDescription": "Write notes"
                    }
                  ]
                }
                """.formatted(poolId));
        assertTrue(resourceServerResponse.path("ResourceServer").path("CreationDate").asLong() > 0);
        assertTrue(resourceServerResponse.path("ResourceServer").path("LastModifiedDate").asLong() > 0);
    }

    @Test
    @Order(2)
    void describeUserPoolClientReturnsGeneratedSecret() throws Exception {
        JsonNode response = cognitoJson("DescribeUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, confidentialClientId));

        assertEquals(confidentialClientId, response.path("UserPoolClient").path("ClientId").asText());
        assertEquals(confidentialClientSecret, response.path("UserPoolClient").path("ClientSecret").asText());
        assertTrue(response.path("UserPoolClient").path("GenerateSecret").asBoolean());
        assertTrue(response.path("UserPoolClient").path("AllowedOAuthFlowsUserPoolClient").asBoolean());
        assertEquals("client_credentials",
                response.path("UserPoolClient").path("AllowedOAuthFlows").get(0).asText());
    }

    @Test
    @Order(3)
    void updateResourceServerReplacesNameAndScopes() throws Exception {
        JsonNode before = cognitoJson("DescribeResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Identifier": "notes"
                }
                """.formatted(poolId));
        long creationDate = before.path("ResourceServer").path("CreationDate").asLong();
        long previousLastModifiedDate = before.path("ResourceServer").path("LastModifiedDate").asLong();

        JsonNode updateResponse = cognitoJson("UpdateResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Identifier": "notes",
                  "Name": "Notes API v2",
                  "Scopes": [
                    {
                      "ScopeName": "read",
                      "ScopeDescription": "Read notes v2"
                    },
                    {
                      "ScopeName": "write",
                      "ScopeDescription": "Write notes v2"
                    }
                  ]
                }
                """.formatted(poolId));

        JsonNode resourceServer = updateResponse.path("ResourceServer");
        assertEquals("notes", resourceServer.path("Identifier").asText());
        assertEquals("Notes API v2", resourceServer.path("Name").asText());
        assertEquals(creationDate, resourceServer.path("CreationDate").asLong());
        assertTrue(resourceServer.path("LastModifiedDate").asLong() >= previousLastModifiedDate);
        assertEquals("read", resourceServer.path("Scopes").get(0).path("ScopeName").asText());
        assertEquals("Read notes v2", resourceServer.path("Scopes").get(0).path("ScopeDescription").asText());
        assertEquals("write", resourceServer.path("Scopes").get(1).path("ScopeName").asText());
        assertEquals("Write notes v2", resourceServer.path("Scopes").get(1).path("ScopeDescription").asText());

        JsonNode described = cognitoJson("DescribeResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Identifier": "notes"
                }
                """.formatted(poolId));
        assertEquals("Notes API v2", described.path("ResourceServer").path("Name").asText());
        assertEquals("write", described.path("ResourceServer").path("Scopes").get(1).path("ScopeName").asText());
    }

    @Test
    @Order(4)
    void updateResourceServerRequiresUserPoolId() {
        cognitoAction("UpdateResourceServer", """
                {
                  "Identifier": "notes",
                  "Name": "Missing pool"
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("UserPoolId is required"));
    }

    @Test
    @Order(5)
    void updateResourceServerRequiresIdentifier() {
        cognitoAction("UpdateResourceServer", """
                {
                  "UserPoolId": "%s",
                  "Name": "Missing identifier"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo("Identifier is required"));
    }

    @Test
    @Order(6)
    void publicClientCannotUseClientCredentialsGrant() {
        given()
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", clientId)
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("unauthorized_client"));
    }

    @Test
    @Order(7)
    void tokenEndpointReturnsAccessTokenFromBasicAuth() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((confidentialClientId + ":" + confidentialClientSecret).getBytes(StandardCharsets.UTF_8));

        Response response = given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token");

        response.then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));

        JsonNode payload = decodeJwtPayload(response.jsonPath().getString("access_token"));
        assertEquals(confidentialClientId, payload.path("client_id").asText());
        assertEquals("http://localhost:4566/" + poolId, payload.path("iss").asText());
    }

    @Test
    @Order(8)
    void tokenEndpointReturnsScopedAccessTokenForConfidentialClient() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((confidentialClientId + ":" + confidentialClientSecret).getBytes(StandardCharsets.UTF_8));

        Response response = given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "notes/read notes/write")
        .when()
                .post("/cognito-idp/oauth2/token");

        response.then().statusCode(200);

        JsonNode payload = decodeJwtPayload(response.jsonPath().getString("access_token"));
        assertEquals("notes/read notes/write", payload.path("scope").asText());
        assertEquals(confidentialClientId, payload.path("client_id").asText());
    }

    @Test
    @Order(9)
    void tokenEndpointReturnsAllAllowedScopesWhenScopeOmitted() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((confidentialClientId + ":" + confidentialClientSecret).getBytes(StandardCharsets.UTF_8));

        Response response = given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token");

        response.then().statusCode(200);

        JsonNode payload = decodeJwtPayload(response.jsonPath().getString("access_token"));
        assertEquals("notes/read notes/write", payload.path("scope").asText());
    }

    @Test
    @Order(10)
    void tokenEndpointAllowsClientSecretPostForConfidentialClient() {
        given()
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", confidentialClientId)
                .formParam("client_secret", confidentialClientSecret)
                .formParam("scope", "notes/read")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }

    @Test
    @Order(11)
    void missingSecretForConfidentialClientReturnsInvalidClient() {
        given()
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", confidentialClientId)
                .formParam("scope", "notes/read")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(12)
    void invalidSecretForConfidentialClientReturnsInvalidClient() {
        String basic = Base64.getEncoder()
                .encodeToString((confidentialClientId + ":wrong-secret").getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "notes/read")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(13)
    void unknownScopeReturnsInvalidScope() {
        String basic = Base64.getEncoder()
                .encodeToString((confidentialClientId + ":" + confidentialClientSecret).getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "notes/delete")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_scope"));
    }

    @Test
    @Order(14)
    void clientCannotRequestScopeThatIsNotAllowedForIt() {
        String limitedClientSecret = cognitoDescribeClientSecret(limitedClientId);
        String basic = Base64.getEncoder()
                .encodeToString((limitedClientId + ":" + limitedClientSecret).getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "notes/write")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_scope"));
    }

    @Test
    @Order(15)
    void missingGrantTypeReturnsInvalidRequest() {
        given()
                .formParam("client_id", clientId)
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    @Order(16)
    void unsupportedGrantTypeReturnsUnsupportedGrantType() {
        given()
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", clientId)
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_grant_type"));
    }

    @Test
    @Order(17)
    void missingClientIdReturnsInvalidRequest() {
        given()
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    @Order(18)
    void unknownClientIdReturnsInvalidClient() {
        given()
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "missing-client")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(19)
    void mismatchedClientIdsReturnInvalidRequest() {
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":ignored-secret").getBytes(StandardCharsets.UTF_8));

        given()
                .header("Authorization", "Basic " + basic)
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "different-client-id")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    @Test
    @Order(20)
    void oauthTokensAreSignedWithPublishedRsaJwksKey() throws Exception {
        Response tokenResponse = given()
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((confidentialClientId + ":" + confidentialClientSecret)
                                .getBytes(StandardCharsets.UTF_8)))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token");

        tokenResponse.then().statusCode(200);

        String accessToken = tokenResponse.jsonPath().getString("access_token");
        JsonNode header = decodeJwtHeader(accessToken);
        assertEquals("RS256", header.path("alg").asText());
        assertEquals(poolId, header.path("kid").asText());

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
    @Order(21)
    void openIdConfigurationIncludesTokenEndpointMetadata() throws Exception {
        String openIdResponse = given()
        .when()
                .get("/" + poolId + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .extract()
                .asString();

        JsonNode document = OBJECT_MAPPER.readTree(openIdResponse);
        assertEquals(
                "http://localhost:4566/cognito-idp/oauth2/token",
                document.path("token_endpoint").asText());
        assertEquals("client_credentials", document.path("grant_types_supported").get(0).asText());
        assertEquals("client_secret_basic", document.path("token_endpoint_auth_methods_supported").get(0).asText());
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

    private static String cognitoDescribeClientSecret(String clientId) {
        return cognitoAction("DescribeUserPoolClient", """
                {
                  "UserPoolId": "%s",
                  "ClientId": "%s"
                }
                """.formatted(poolId, clientId))
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("UserPoolClient.ClientSecret");
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

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }
}
