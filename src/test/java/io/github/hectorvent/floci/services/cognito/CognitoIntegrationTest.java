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
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(COGNITO_CONTENT_TYPE, ContentType.TEXT));
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
        assertEquals("https://cognito-idp.local/" + poolId, payload.path("iss").asText());
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
        assertEquals("https://cognito-idp.local/" + poolId, document.path("issuer").asText());
        assertEquals(
                "http://localhost:" + RestAssured.port + "/" + poolId + "/.well-known/jwks.json",
                document.path("jwks_uri").asText());
        assertEquals("public", document.path("subject_types_supported").get(0).asText());
        assertEquals("RS256", document.path("id_token_signing_alg_values_supported").get(0).asText());
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

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }
}
