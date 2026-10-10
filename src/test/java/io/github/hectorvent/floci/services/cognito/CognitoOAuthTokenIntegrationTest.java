package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.SignedInUser;
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

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.PASSWORD;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.assertInvalidClient;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.assertRevoked;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.basic;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.refresh;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.signIn;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoOAuthTokenIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REVOKE = "/cognito-idp/oauth2/revoke";

    private static String poolId;
    private static String clientId;
    private static String limitedClientId;
    private static String confidentialClientId;
    private static String confidentialClientSecret;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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

    /**
     * The sign-out of an app: the refresh token, and every access token minted from it, before or after
     * a refresh, stop working. Another sign-in of the same user is another family, and keeps working.
     */
    @Test
    @Order(22)
    void revokeEndsTheSessionOfAPublicClient() throws Exception {
        SignedInUser user = signIn(poolId);
        String refreshedAccessToken = refresh(user)
        .then()
                .statusCode(200)
                .extract()
                .path("AuthenticationResult.AccessToken");
        getUser(user.accessToken()).then().statusCode(200);
        getUser(refreshedAccessToken).then().statusCode(200);
        SignedInUser otherSession = signInAgain(user);

        assertRevoked(revoke(user.refreshToken(), user.clientId()));

        refresh(user)
        .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Refresh Token has been revoked"));
        cognitoAction("GetTokensFromRefreshToken", """
                {"ClientId": "%s", "RefreshToken": "%s"}
                """.formatted(user.clientId(), user.refreshToken()))
        .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Refresh Token has been revoked"));
        for (String revokedAccessToken : new String[] {user.accessToken(), refreshedAccessToken}) {
            getUser(revokedAccessToken)
            .then()
                    .statusCode(400)
                    .body("__type", equalTo("NotAuthorizedException"))
                    .body("message", equalTo("Access Token has been revoked"));
        }

        getUser(otherSession.accessToken()).then().statusCode(200);
        refresh(otherSession).then().statusCode(200);
    }

    @Test
    @Order(23)
    void revokeAnswersOkForATokenAlreadyRevokedOrNotValid() throws Exception {
        SignedInUser user = signIn(poolId);
        revoke(user.refreshToken(), user.clientId()).then().statusCode(200);

        assertRevoked(revoke(user.refreshToken(), user.clientId()));
        assertRevoked(revoke("not-a-token", user.clientId()));
    }

    @Test
    @Order(24)
    void revokeRefusesAnAccessTokenAndLeavesItsFamilyAlone() throws Exception {
        SignedInUser user = signIn(poolId);

        revoke(user.accessToken(), user.clientId())
        .then()
                .statusCode(400)
                .contentType(containsString("application/json"))
                .body(equalTo("{\"error\":\"unsupported_token_type\"}"));
        refresh(user).then().statusCode(200);
    }

    @Test
    @Order(25)
    void revokeRefusesATokenIssuedToAnotherClient() throws Exception {
        SignedInUser user = signIn(poolId);
        String otherClientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "other-public-client"}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        assertInvalidClient(revoke(user.refreshToken(), otherClientId));
        refresh(user).then().statusCode(200);
    }

    /** No client_id, an unknown one, or a Basic header with an empty secret. */
    @Test
    @Order(26)
    void revokeRefusesAPublicClientThatDoesNotAuthenticate() throws Exception {
        SignedInUser user = signIn(poolId);

        assertInvalidClient(given().formParam("token", user.refreshToken()).when().post(REVOKE));
        assertInvalidClient(revoke(user.refreshToken(), "no-such-client"));
        assertInvalidClient(given()
                .header("Authorization", basic(user.clientId(), ""))
                .formParam("token", user.refreshToken())
        .when()
                .post(REVOKE));
        refresh(user).then().statusCode(200);
    }

    /** Unlike the token endpoint, revocation takes a secret only from the Basic header. */
    @Test
    @Order(27)
    void revokeAuthenticatesAConfidentialClientOnlyWithBasicCredentials() {
        assertInvalidClient(revoke("not-a-token", confidentialClientId));
        assertInvalidClient(given()
                .formParam("token", "not-a-token")
                .formParam("client_id", confidentialClientId)
                .formParam("client_secret", confidentialClientSecret)
        .when()
                .post(REVOKE));
        assertInvalidClient(given()
                .header("Authorization", basic(confidentialClientId, "wrong-secret"))
                .formParam("token", "not-a-token")
        .when()
                .post(REVOKE));

        assertRevoked(given()
                .header("Authorization", basic(confidentialClientId, confidentialClientSecret))
                .formParam("token", "not-a-token")
        .when()
                .post(REVOKE));
        assertRevoked(given()
                .header("Authorization", basic(confidentialClientId, confidentialClientSecret))
                .formParam("token", "not-a-token")
                .formParam("client_id", confidentialClientId)
        .when()
                .post(REVOKE));
    }

    /** The token is checked first, so a request with no parameters at all is invalid_request too. */
    @Test
    @Order(28)
    void revokeWithoutATokenIsInvalidRequest() {
        assertInvalidRequest(given().formParam("client_id", clientId).when().post(REVOKE),
                "Invalid parameter in request");
        assertInvalidRequest(revoke("", clientId), "Invalid parameter in request");
        assertInvalidRequest(given().contentType("application/x-www-form-urlencoded").when().post(REVOKE),
                "Invalid parameter in request");
        assertInvalidRequest(given().when().post(REVOKE), "Invalid parameter in request");
    }

    @Test
    @Order(29)
    void revokeIsInvalidRequestWhenTheClientDisablesRevocation() throws Exception {
        String disabledClientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "revocation-disabled-client", "EnableTokenRevocation": false}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();

        assertInvalidRequest(revoke("any-token", disabledClientId), "Unsupported operation");
    }

    /** The same user signed in again through the same client, which starts a new token family. */
    private static SignedInUser signInAgain(SignedInUser user) throws Exception {
        String username = cognitoJson("GetUser", """
                {"AccessToken": "%s"}
                """.formatted(user.accessToken())).path("Username").asText();
        JsonNode tokens = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(user.clientId(), username, PASSWORD)).path("AuthenticationResult");
        return new SignedInUser(user.clientId(), tokens.path("AccessToken").asText(),
                tokens.path("RefreshToken").asText());
    }

    private static Response revoke(String token, String clientId) {
        return given()
                .formParam("token", token)
                .formParam("client_id", clientId)
        .when()
                .post(REVOKE);
    }

    private static void assertInvalidRequest(Response response, String description) {
        response.then()
                .statusCode(400)
                .contentType(containsString("application/json"))
                .header("WWW-Authenticate", nullValue())
                .body("error", equalTo("invalid_request"))
                .body("error_description", equalTo(description));
    }

    private static Response getUser(String accessToken) {
        return cognitoAction("GetUser", """
                {"AccessToken": "%s"}
                """.formatted(accessToken));
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
