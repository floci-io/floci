package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.COGNITO;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.FLOCI_URL;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.awsActionAs;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.awsJsonAs;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.basic;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.confidentialClient;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.createPool;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.customDomain;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.expect;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.requestCertificate;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.requestCertificateAs;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.signInNewUser;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Cognito custom domain serves {@code https://<domain>/oauth2/token} and
 * {@code /oauth2/userInfo} on AWS. Floci resolves the request Host against the domain store and
 * routes those paths to the handlers behind {@code /cognito-idp/oauth2/...}, pinned to the pool
 * and the account that own the domain. The OAuth contract on the routed endpoints is covered by
 * {@link CognitoCustomDomainOAuthContractIntegrationTest}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoCustomDomainRoutingIntegrationTest {

    private static final String DOMAIN = "auth-" + System.nanoTime() + ".teos.localhost.floci.io";
    private static final String DOMAIN_B = "auth-b-" + System.nanoTime() + ".teos.localhost.floci.io";
    private static final String ACCOUNT_B = "111122223333";

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

        cognitoJson("CreateUserPoolDomain", customDomain(DOMAIN, poolA, requestCertificate(DOMAIN)));
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
        assertNull(expect(200, DOMAIN.toUpperCase() + ":4566", clientA, secretA, "/oauth2/token"));
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
        assertNull(expect(200, null, clientB, secretB, "/cognito-idp/oauth2/token"));
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
                .contentType(containsString("application/json"))
                .header("Cache-Control", containsString("no-store"))
                .header("Pragma", equalTo("no-cache"))
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
                .body("issuer", equalTo(FLOCI_URL + "/" + poolA))
                .body("jwks_uri", equalTo(FLOCI_URL + "/" + poolA + "/.well-known/jwks.json"))
                .body("token_endpoint", equalTo("https://" + DOMAIN + "/oauth2/token"))
                .body("userinfo_endpoint", equalTo("https://" + DOMAIN + "/oauth2/userInfo"));

        given()
        .when()
                .get("/" + poolB + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .body("token_endpoint", equalTo(FLOCI_URL + "/cognito-idp/oauth2/token"))
                .body("userinfo_endpoint", equalTo(FLOCI_URL + "/cognito-idp/oauth2/userInfo"));
    }

    /** Domain names are one global namespace on AWS, whichever account asks. */
    @Test
    @Order(12)
    void anotherAccountCannotReuseTheDomainName() throws Exception {
        String poolInB = awsJsonAs(ACCOUNT_B, COGNITO, "CreateUserPool", "{\"PoolName\": \"RoutingPoolB2\"}")
                .path("UserPool").path("Id").asText();

        awsActionAs(ACCOUNT_B, COGNITO, "CreateUserPoolDomain",
                customDomain(DOMAIN, poolInB, requestCertificateAs(ACCOUNT_B, DOMAIN)))
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    /** The domain's account, not the default one, serves a request on that domain. */
    @Test
    @Order(13)
    void tokenOnAnotherAccountsDomainRunsInThatAccount() throws Exception {
        String poolInB = awsJsonAs(ACCOUNT_B, COGNITO, "CreateUserPool", "{\"PoolName\": \"RoutingPoolB3\"}")
                .path("UserPool").path("Id").asText();
        JsonNode client = awsJsonAs(ACCOUNT_B, COGNITO, "CreateUserPoolClient", confidentialClient(poolInB))
                .path("UserPoolClient");
        String clientInB = client.path("ClientId").asText();
        String secretInB = client.path("ClientSecret").asText();
        awsJsonAs(ACCOUNT_B, COGNITO, "CreateUserPoolDomain",
                customDomain(DOMAIN_B, poolInB, requestCertificateAs(ACCOUNT_B, DOMAIN_B)));

        assertNull(expect(200, DOMAIN_B, clientInB, secretInB, "/oauth2/token"));

        given()
                .header("Authorization", basic(clientInB, secretInB))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(14)
    void replacingTheCertificateKeepsTheDomainRouted() throws Exception {
        cognitoJson("UpdateUserPoolDomain", customDomain(DOMAIN, poolA, requestCertificate(DOMAIN)));

        assertNull(expect(200, DOMAIN, clientA, secretA, "/oauth2/token"));
    }

    @Test
    @Order(15)
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
                .body("token_endpoint", equalTo(FLOCI_URL + "/cognito-idp/oauth2/token"));
    }

    /** After a delete the name is free again, and it follows its new pool. */
    @Test
    @Order(16)
    void domainNameFollowsItsNewPoolAfterReassignment() throws Exception {
        cognitoJson("CreateUserPoolDomain", customDomain(DOMAIN, poolB, requestCertificate(DOMAIN)));

        assertNull(expect(200, DOMAIN, clientB, secretB, "/oauth2/token"));
        assertNull(expect(400, DOMAIN, clientA, secretA, "/oauth2/token"));

        given()
        .when()
                .get("/" + poolB + "/.well-known/openid-configuration")
        .then()
                .statusCode(200)
                .body("token_endpoint", equalTo("https://" + DOMAIN + "/oauth2/token"));
    }

    /** Two accounts racing for one name: exactly one create succeeds, so the name never turns ambiguous. */
    @Test
    @Order(17)
    void concurrentCreatesAcrossAccountsLeaveOneOwner() throws Exception {
        String contested = "auth-race-" + System.nanoTime() + ".teos.localhost.floci.io";
        String defaultAccount = "000000000000";
        String poolInA = createPool("RacePoolA");
        String poolInB = awsJsonAs(ACCOUNT_B, COGNITO, "CreateUserPool", "{\"PoolName\": \"RacePoolB\"}")
                .path("UserPool").path("Id").asText();
        String certificateA = requestCertificate(contested);
        String certificateB = requestCertificateAs(ACCOUNT_B, contested);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> outcomes = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                boolean inA = i % 2 == 0;
                outcomes.add(executor.submit(() -> awsActionAs(inA ? defaultAccount : ACCOUNT_B, COGNITO,
                        "CreateUserPoolDomain",
                        customDomain(contested, inA ? poolInA : poolInB, inA ? certificateA : certificateB))
                        .statusCode()));
            }
            int created = 0;
            for (Future<Integer> outcome : outcomes) {
                int status = outcome.get(30, TimeUnit.SECONDS);
                assertTrue(status == 200 || status == 400, "unexpected status " + status);
                if (status == 200) {
                    created++;
                }
            }
            assertEquals(1, created);
        } finally {
            executor.shutdownNow();
        }

        assertNull(expect(400, contested, clientA, secretA, "/oauth2/token"));
        given()
                .header("Host", contested)
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "client_credentials")
        .when()
                .post("/oauth2/token")
        .then()
                .body("error", equalTo("invalid_client"));
    }
}
