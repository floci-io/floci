package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * A Cognito custom domain serves {@code https://<domain>/oauth2/token} and
 * {@code /oauth2/userInfo} on AWS. Floci resolves the request Host against the domain store and
 * routes those paths to the handlers behind {@code /cognito-idp/oauth2/...}, pinned to the pool
 * that owns the domain.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoCustomDomainRoutingIntegrationTest {

    private static final String DOMAIN = "auth-" + System.nanoTime() + ".teos.localhost.floci.io";
    private static final String PASSWORD = "Perm1234!";

    private static String poolA;
    private static String poolB;
    private static String clientA;
    private static String secretA;
    private static String clientB;
    private static String secretB;
    private static String userTokenA;
    private static String userTokenB;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpTwoPoolsAndOneCustomDomain() throws Exception {
        poolA = createPool("RoutingPoolA");
        poolB = createPool("RoutingPoolB");

        JsonNode a = cognitoJson("CreateUserPoolClient", confidentialClient(poolA)).path("UserPoolClient");
        clientA = a.path("ClientId").asText();
        secretA = a.path("ClientSecret").asText();
        JsonNode b = cognitoJson("CreateUserPoolClient", confidentialClient(poolB)).path("UserPoolClient");
        clientB = b.path("ClientId").asText();
        secretB = b.path("ClientSecret").asText();

        userTokenA = signInNewUser(poolA);
        userTokenB = signInNewUser(poolB);

        String certificateArn = RestAssuredJsonUtils.awsActionJson("CertificateManager", "RequestCertificate", """
                {"DomainName": "%s", "ValidationMethod": "DNS"}
                """.formatted(DOMAIN)).path("CertificateArn").asText();
        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {"CertificateArn": "%s"}
                }
                """.formatted(DOMAIN, poolA, certificateArn));
    }

    @Test
    @Order(2)
    void tokenRequestOnTheCustomDomainReachesTheTokenHandler() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }

    @Test
    @Order(3)
    void hostWithPortAndMixedCaseStillMatches() {
        given()
                .header("Host", DOMAIN.toUpperCase() + ":4566")
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }

    @Test
    @Order(4)
    void clientOfAnotherPoolIsRefusedOnThisDomain() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", basic(clientB, secretB))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(5)
    void sameClientStillWorksOnTheGenericPath() {
        given()
                .header("Authorization", basic(clientB, secretB))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(6)
    void emptyPostOnTheCustomDomainAnswersInvalidRequest() {
        given()
                .header("Host", DOMAIN)
                .contentType("application/x-www-form-urlencoded")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"));
    }

    /** Without a matching Host nothing is rewritten: /oauth2/token is S3's /{bucket}/{key}. */
    @Test
    @Order(7)
    void unknownHostIsLeftUntouched() {
        given()
                .header("Host", "nobody-" + System.nanoTime() + ".example.com")
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"));
    }

    @Test
    @Order(8)
    void userInfoWithoutBearerTokenIsRoutedToo() {
        given()
                .header("Host", DOMAIN)
        .when()
                .get("/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    @Order(9)
    void userInfoOnTheCustomDomainServesThePoolsOwnUsers() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", "Bearer " + userTokenA)
        .when()
                .get("/oauth2/userInfo")
        .then()
                .statusCode(200)
                .body("email_verified", equalTo("true"));
    }

    @Test
    @Order(10)
    void userInfoOnTheCustomDomainRefusesATokenOfAnotherPool() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", "Bearer " + userTokenB)
        .when()
                .get("/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\""));

        given()
                .header("Authorization", "Bearer " + userTokenB)
        .when()
                .get("/cognito-idp/oauth2/userInfo")
        .then()
                .statusCode(200);
    }

    @Test
    @Order(11)
    void openIdConfigurationAdvertisesTheCustomDomain() {
        given()
        .when()
                .get("/" + poolA + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .body("token_endpoint", equalTo("https://" + DOMAIN + "/oauth2/token"))
                .body("userinfo_endpoint", equalTo("https://" + DOMAIN + "/oauth2/userInfo"));

        given()
        .when()
                .get("/" + poolB + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .body("token_endpoint", equalTo("http://localhost:4566/cognito-idp/oauth2/token"))
                .body("userinfo_endpoint", equalTo("http://localhost:4566/cognito-idp/oauth2/userInfo"));
    }

    @Test
    @Order(12)
    void deletedDomainIsNoLongerRouted() throws Exception {
        cognitoJson("DeleteUserPoolDomain", """
                {"Domain": "%s", "UserPoolId": "%s"}
                """.formatted(DOMAIN, poolA));

        given()
                .header("Host", DOMAIN)
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"));

        given()
        .when()
                .get("/" + poolA + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .body("token_endpoint", equalTo("http://localhost:4566/cognito-idp/oauth2/token"));
    }

    private static String createPool(String name) throws Exception {
        return cognitoJson("CreateUserPool", "{\"PoolName\": \"" + name + "\"}").path("UserPool").path("Id").asText();
    }

    private static String confidentialClient(String poolId) {
        return """
                {
                  "UserPoolId": "%s",
                  "ClientName": "routing-client",
                  "GenerateSecret": true,
                  "AllowedOAuthFlowsUserPoolClient": true,
                  "AllowedOAuthFlows": ["client_credentials"],
                  "AllowedOAuthScopes": ["aws.cognito.signin.user.admin"]
                }
                """.formatted(poolId);
    }

    /** A public client, a confirmed user and the access token USER_PASSWORD_AUTH issues for it. */
    private static String signInNewUser(String poolId) throws Exception {
        String publicClient = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "routing-user-client"}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "user-" + System.nanoTime() + "@example.com";
        cognitoAction("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "UserAttributes": [
                    {"Name": "email", "Value": "%s"},
                    {"Name": "email_verified", "Value": "true"}
                  ]
                }
                """.formatted(poolId, username, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId": "%s", "Username": "%s", "Password": "%s", "Permanent": true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        return cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": {"USERNAME": "%s", "PASSWORD": "%s"}
                }
                """.formatted(publicClient, username, PASSWORD))
                .path("AuthenticationResult").path("AccessToken").asText();
    }

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
