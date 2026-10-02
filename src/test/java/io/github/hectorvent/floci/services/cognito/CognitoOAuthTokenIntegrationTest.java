package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.SignedInUser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
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
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.PASSWORD;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.assertInvalidClient;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.assertRevoked;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.basic;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.refresh;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.refreshGrant;
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
    private static final String INVALID_GRANT = "{\"error\":\"invalid_grant\"}";

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

    /** AWS: 400 with no error_description. */
    @Test
    @Order(15)
    void missingGrantTypeReturnsInvalidRequest() throws Exception {
        assertBody(given()
                .formParam("client_id", clientId)
        .when()
                .post("/cognito-idp/oauth2/token"), 400, "{\"error\":\"invalid_request\"}");
    }

    @Test
    @Order(16)
    void unsupportedGrantTypeReturnsUnsupportedGrantType() {
        given()
                .formParam("grant_type", "password")
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

    /**
     * AWS accepts a refresh token from a sign-in through the API at the token endpoint: a new access
     * token with the API sign-in scope, the same family and auth_time, an ID token as the sign-in had,
     * and no new refresh token.
     */
    @Test
    @Order(30)
    void refreshGrantRenewsTheTokensOfAPublicClient() throws Exception {
        SignedInUser user = signIn(poolId);

        Response response = refreshGrant(null, user.clientId(), user.refreshToken());

        response.then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Cache-Control", equalTo("no-store"))
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo(3600));
        assertEquals(Set.of("access_token", "id_token", "expires_in", "token_type"), fieldNames(response));
        JsonNode signedIn = decodeJwtPayload(user.accessToken());
        JsonNode refreshed = decodeJwtPayload(response.path("access_token"));
        assertEquals("aws.cognito.signin.user.admin", refreshed.path("scope").asText());
        assertNotEquals(signedIn.path("jti").asText(), refreshed.path("jti").asText());
        assertEquals(signedIn.path("origin_jti").asText(), refreshed.path("origin_jti").asText());
        assertEquals(signedIn.path("auth_time").asLong(), refreshed.path("auth_time").asLong());
        assertTrue(refreshed.path("iat").asLong() >= signedIn.path("iat").asLong());
        assertEquals(user.clientId(), decodeJwtPayload(response.path("id_token")).path("aud").asText());
        getUser(response.path("access_token")).then().statusCode(200);
    }

    @Test
    @Order(31)
    void refreshGrantWithAnEmptyOrAbsentRefreshTokenIsInvalidRequest() throws Exception {
        SignedInUser user = signIn(poolId);
        String expected = "{\"error\":\"invalid_request\",\"error_description\":\"invalid_refresh_token\"}";

        assertBody(given().formParam("grant_type", "refresh_token").formParam("client_id", user.clientId())
                .when().post("/cognito-idp/oauth2/token"), 400, expected);
        assertBody(refreshGrant(null, user.clientId(), ""), 400, expected);
    }

    @Test
    @Order(32)
    void refreshGrantIsInvalidGrantAfterRevokeToken() throws Exception {
        SignedInUser user = signIn(poolId);
        cognitoAction("RevokeToken", """
                {"Token": "%s", "ClientId": "%s"}
                """.formatted(user.refreshToken(), user.clientId())).then().statusCode(200);

        assertBody(refreshGrant(null, user.clientId(), user.refreshToken()), 400, INVALID_GRANT);
    }

    @Test
    @Order(33)
    void refreshGrantIsInvalidGrantAfterTheRevocationEndpoint() throws Exception {
        SignedInUser user = signIn(poolId);
        revoke(user.refreshToken(), user.clientId()).then().statusCode(200);

        assertBody(refreshGrant(null, user.clientId(), user.refreshToken()), 400, INVALID_GRANT);
    }

    @Test
    @Order(34)
    void refreshGrantRefusesATokenIssuedToAnotherClient() throws Exception {
        SignedInUser user = signIn(poolId);
        SignedInUser otherClientsUser = signIn(poolId);

        assertBody(refreshGrant(null, otherClientsUser.clientId(), user.refreshToken()), 400, INVALID_GRANT);
    }

    /** AWS: both API operations that refresh refuse another client's refresh token with the same answer. */
    @Test
    @Order(35)
    void theApiRefusesARefreshTokenIssuedToAnotherClient() throws Exception {
        SignedInUser user = signIn(poolId);
        SignedInUser otherClientsUser = signIn(poolId);
        String expected = "{\"__type\":\"NotAuthorizedException\",\"message\":\"Refresh Token has different Client\"}";

        assertBody(cognitoAction("GetTokensFromRefreshToken", """
                {"ClientId": "%s", "RefreshToken": "%s"}
                """.formatted(otherClientsUser.clientId(), user.refreshToken())), 400, expected);
        assertBody(refresh(new SignedInUser(otherClientsUser.clientId(), null, user.refreshToken())), 400, expected);
    }

    @Test
    @Order(36)
    void refreshGrantWithAGarbageOrAlteredRefreshTokenIsInvalidGrant() throws Exception {
        SignedInUser user = signIn(poolId);
        char original = user.refreshToken().charAt(10);
        String altered = user.refreshToken().substring(0, 10) + (original == 'A' ? 'B' : 'A')
                + user.refreshToken().substring(11);

        assertBody(refreshGrant(null, user.clientId(), "not-a-refresh-token"), 400, INVALID_GRANT);
        assertBody(refreshGrant(null, user.clientId(), altered), 400, INVALID_GRANT);
    }

    /** AWS checks the client before the refresh token, and answers without a description. */
    @Test
    @Order(37)
    void refreshGrantRefusesAClientItCannotIdentify() throws Exception {
        SignedInUser user = signIn(poolId);
        String expected = "{\"error\":\"invalid_client\"}";

        assertBody(refreshGrant(null, "unknown-client", "not-a-refresh-token"), 400, expected);
        assertBody(refreshGrant(null, "not a client id!", "not-a-refresh-token"), 400, expected);
        assertBody(given().formParam("grant_type", "refresh_token").formParam("refresh_token", user.refreshToken())
                .when().post("/cognito-idp/oauth2/token"), 400, expected);
        assertBody(given().header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user.clientId() + ":").getBytes(StandardCharsets.UTF_8)))
                .formParam("grant_type", "refresh_token").formParam("refresh_token", user.refreshToken())
                .when().post("/cognito-idp/oauth2/token"), 400, expected);
    }

    /**
     * AWS: a client with a secret sends it in the body or in Basic, and a Basic header names the
     * client whatever the body's client_id says. A missing or wrong secret is the same 400.
     */
    @Test
    @Order(38)
    void refreshGrantAuthenticatesAConfidentialClient() throws Exception {
        JsonNode created = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "confidential-refresh-client", "GenerateSecret": true,
                 "ExplicitAuthFlows": ["ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient");
        String confidentialId = created.path("ClientId").asText();
        String secret = created.path("ClientSecret").asText();
        String refreshToken = confidentialRefreshToken(confidentialId, secret);
        String publicClientId = signIn(poolId).clientId();
        String invalidSecret = "{\"error\":\"invalid_client\",\"error_description\":\"invalid_client_secret\"}";

        assertBody(refreshGrant(null, confidentialId, refreshToken), 400, invalidSecret);
        assertBody(refreshWithBodySecret(confidentialId, "not-the-secret", refreshToken), 400, invalidSecret);
        assertBody(refreshWithBasic(basic(confidentialId, "not-the-secret"), null, refreshToken), 400, invalidSecret);
        refreshWithBodySecret(confidentialId, secret, refreshToken).then().statusCode(200);
        refreshWithBasic(basic(confidentialId, secret), null, refreshToken).then().statusCode(200);
        refreshWithBasic(basic(confidentialId, secret), confidentialId, refreshToken).then().statusCode(200);
        Response basicWins = refreshWithBasic(basic(confidentialId, secret), publicClientId, refreshToken);
        basicWins.then().statusCode(200);
        assertEquals(confidentialId, decodeJwtPayload(basicWins.path("access_token")).path("client_id").asText());
    }

    /**
     * AWS refreshes at the token endpoint for a client without ALLOW_REFRESH_TOKEN_AUTH and without
     * any OAuth settings, unlike the refresh operations of the API.
     */
    @Test
    @Order(39)
    void refreshGrantDoesNotRequireTheRefreshFlowOrAnOAuthClient() throws Exception {
        String passwordOnlyClient = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "password-only-client",
                 "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "password-only-" + System.nanoTime();
        cognitoAction("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "%s"}
                """.formatted(poolId, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId": "%s", "Username": "%s", "Password": "%s", "Permanent": true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        String refreshToken = cognitoJson("InitiateAuth", """
                {"ClientId": "%s", "AuthFlow": "USER_PASSWORD_AUTH",
                 "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}}
                """.formatted(passwordOnlyClient, username, PASSWORD))
                .path("AuthenticationResult").path("RefreshToken").asText();

        Response response = refreshGrant(null, passwordOnlyClient, refreshToken);

        response.then().statusCode(200);
        assertEquals(Set.of("access_token", "id_token", "expires_in", "token_type"), fieldNames(response));
    }

    /**
     * AWS: a public client has no secret, so in a Basic header a secret is wrong and an empty one, or
     * none, names no client. A client_secret in the body of a public client is ignored.
     */
    @Test
    @Order(40)
    void refreshGrantRefusesBasicCredentialsOfAPublicClient() throws Exception {
        SignedInUser user = signIn(poolId);
        String bareInvalidClient = "{\"error\":\"invalid_client\"}";

        assertBody(refreshWithBasic(basic(user.clientId(), "wrongsecret"), null, user.refreshToken()), 400,
                "{\"error\":\"invalid_client\",\"error_description\":\"invalid_client_secret\"}");
        assertBody(refreshWithBasic(basic(user.clientId(), ""), user.clientId(), user.refreshToken()), 400,
                bareInvalidClient);
        assertBody(refreshWithBasic("Basic " + Base64.getEncoder().encodeToString(
                user.clientId().getBytes(StandardCharsets.UTF_8)), null, user.refreshToken()), 400, bareInvalidClient);
        refreshWithBodySecret(user.clientId(), "wrongsecret", user.refreshToken()).then().statusCode(200);
    }

    private static String confidentialRefreshToken(String confidentialId, String secret) throws Exception {
        String username = "confidential-" + System.nanoTime();
        cognitoAction("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "%s"}
                """.formatted(poolId, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId": "%s", "Username": "%s", "Password": "%s", "Permanent": true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String secretHash = Base64.getEncoder().encodeToString(
                mac.doFinal((username + confidentialId).getBytes(StandardCharsets.UTF_8)));
        return cognitoJson("AdminInitiateAuth", """
                {"UserPoolId": "%s", "ClientId": "%s", "AuthFlow": "ADMIN_USER_PASSWORD_AUTH",
                 "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s", "SECRET_HASH": "%s"}}
                """.formatted(poolId, confidentialId, username, PASSWORD, secretHash))
                .path("AuthenticationResult").path("RefreshToken").asText();
    }

    private static Response refreshWithBodySecret(String clientId, String clientSecret, String refreshToken) {
        return given()
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", clientId)
                .formParam("client_secret", clientSecret)
                .formParam("refresh_token", refreshToken)
        .when()
                .post("/cognito-idp/oauth2/token");
    }

    private static Response refreshWithBasic(String authorization, String bodyClientId, String refreshToken) {
        RequestSpecification request = given()
                .header("Authorization", authorization)
                .formParam("grant_type", "refresh_token")
                .formParam("refresh_token", refreshToken);
        if (bodyClientId != null) {
            request.formParam("client_id", bodyClientId);
        }
        return request.when().post("/cognito-idp/oauth2/token");
    }

    /** The response's status, no WWW-Authenticate challenge, and a JSON body with exactly {@code expectedBody}'s fields and values. */
    private static void assertBody(Response response, int status, String expectedBody) throws Exception {
        response.then().statusCode(status).header("WWW-Authenticate", nullValue());
        assertEquals(OBJECT_MAPPER.readTree(expectedBody), OBJECT_MAPPER.readTree(response.asString()));
    }

    private static Set<String> fieldNames(Response response) throws Exception {
        Set<String> names = new HashSet<>();
        OBJECT_MAPPER.readTree(response.asString()).fieldNames().forEachRemaining(names::add);
        return names;
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
