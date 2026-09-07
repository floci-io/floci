package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.FLOCI_URL;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.basic;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.confidentialClient;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.createPool;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.customDomain;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.expect;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.jwtPayload;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.requestCertificate;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.tokenRequest;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth contract of the token and userInfo endpoints when reached on a custom domain: the
 * same answers AWS gives on {@code https://<domain>/oauth2/...}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoCustomDomainOAuthContractIntegrationTest {

    private static final String DOMAIN = "auth-c-" + System.nanoTime() + ".teos.localhost.floci.io";

    private static String poolA;
    private static String clientA;
    private static String secretA;
    private static String clientB;
    private static String secretB;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpTwoPoolsAndOneCustomDomain() throws Exception {
        poolA = createPool("ContractPoolA");
        String poolB = createPool("ContractPoolB");

        JsonNode a = cognitoJson("CreateUserPoolClient", confidentialClient(poolA)).path("UserPoolClient");
        clientA = a.path("ClientId").asText();
        secretA = a.path("ClientSecret").asText();
        JsonNode b = cognitoJson("CreateUserPoolClient", confidentialClient(poolB)).path("UserPoolClient");
        clientB = b.path("ClientId").asText();
        secretB = b.path("ClientSecret").asText();

        cognitoJson("CreateUserPoolDomain", customDomain(DOMAIN, poolA, requestCertificate(DOMAIN)));
    }

    @Test
    @Order(2)
    void clientSecretPostIsAccepted() {
        given()
                .header("Host", DOMAIN)
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", clientA)
                .formParam("client_secret", secretA)
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo(3600));
    }

    @Test
    @Order(3)
    void wrongSecretIsInvalidClient() {
        tokenRequest(DOMAIN, clientA, "not-the-secret", "/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_client"));
    }

    @Test
    @Order(4)
    void unsupportedGrantTypeIsRefused() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "authorization_code")
                .formParam("code", "abc")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_grant_type"));
    }

    @Test
    @Order(5)
    void scopeNotAllowedForTheClientIsInvalidScope() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", basic(clientA, secretA))
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "openid")
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_scope"));
    }

    /** The issuer stays the pool's, as on AWS where iss names cognito-idp, never the custom domain. */
    @Test
    @Order(6)
    void tokenNamesThePoolIssuerAndTheClient() throws Exception {
        Response response = tokenRequest(DOMAIN, clientA, secretA, "/oauth2/token");

        response.then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .header("Cache-Control", equalTo("no-store"))
                .header("Pragma", equalTo("no-cache"));

        JsonNode claims = jwtPayload(response.path("access_token"));
        assertEquals(FLOCI_URL + "/" + poolA, claims.path("iss").asText());
        assertEquals(clientA, claims.path("client_id").asText());
        assertEquals("access", claims.path("token_use").asText());
        assertEquals("aws.cognito.signin.user.admin", claims.path("scope").asText());
    }

    /** A client-credentials token carries no user, so userInfo cannot serve it. */
    @Test
    @Order(7)
    void userInfoRefusesAMachineToken() {
        String machineToken = tokenRequest(DOMAIN, clientA, secretA, "/oauth2/token")
                .then().statusCode(200).extract().path("access_token");

        given()
                .header("Host", DOMAIN)
                .header("Authorization", "Bearer " + machineToken)
        .when()
                .get("/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\""));
    }

    @Test
    @Order(8)
    void userInfoRefusesAMalformedBearerToken() {
        given()
                .header("Host", DOMAIN)
                .header("Authorization", "Bearer not-a-jwt")
        .when()
                .get("/oauth2/userInfo")
        .then()
                .statusCode(401)
                .header("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\""));
    }

    /** Floci has no hosted UI: the other endpoints AWS serves on a domain are absent, not stubbed. */
    @Test
    @Order(9)
    void otherHostedUiPathsAreNotServed() {
        for (String path : List.of("/oauth2/authorize", "/oauth2/revoke", "/oauth2/idpresponse")) {
            given()
                    .header("Host", DOMAIN)
            .when()
                    .get(path)
            .then()
                    .statusCode(404);
        }
    }

    /** A prefix domain's hostname resolves to AWS, so Floci never routes by it. */
    @Test
    @Order(10)
    void prefixDomainHostIsNotRouted() throws Exception {
        String prefix = "routing-prefix-" + System.nanoTime();
        cognitoJson("CreateUserPoolDomain", """
                {"Domain": "%s", "UserPoolId": "%s"}
                """.formatted(prefix, poolA));

        for (String host : List.of(prefix, prefix + ".auth.us-east-1.amazoncognito.com")) {
            tokenRequest(host, clientA, secretA, "/oauth2/token")
            .then()
                    .statusCode(400)
                    .body(containsString("<Code>InvalidArgument</Code>"));
        }
    }

    /**
     * The pinned pool and account live on one request. Interleaving custom-domain and generic
     * requests on many threads must never let one request's pin decide another's answer.
     */
    @Test
    @Order(11)
    void concurrentRequestsKeepTheirOwnPinnedPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                int kind = i % 3;
                outcomes.add(pool.submit(() -> switch (kind) {
                    case 0 -> expect(200, DOMAIN, clientA, secretA, "/oauth2/token");
                    case 1 -> expect(200, null, clientB, secretB, "/cognito-idp/oauth2/token");
                    default -> expect(400, DOMAIN, clientB, secretB, "/oauth2/token");
                }));
            }
            List<String> failures = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                String failure = outcome.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
        } finally {
            pool.shutdownNow();
        }
    }
}
