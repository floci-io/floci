package io.floci.aws.services.cognito;

import io.floci.aws.services.cognito.CognitoCustomDomainFixtures.SignedInUser;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.floci.aws.services.cognito.CognitoCustomDomainFixtures.createPool;
import static io.floci.aws.services.cognito.CognitoCustomDomainFixtures.customDomain;
import static io.floci.aws.services.cognito.CognitoCustomDomainFixtures.requestCertificate;
import static io.floci.aws.services.cognito.CognitoCustomDomainFixtures.signIn;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS answers CORS on the token, revoke and userInfo endpoints for any Origin, with credentials,
 * on success and error alike, so a browser app can call them with fetch. A preflight there must
 * not reach S3's OPTIONS route, which refuses it, while S3's own requests, on a bucket host or for a method Cognito does not serve, keep S3's CORS.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoOAuthCorsIntegrationTest {

    private static final String ORIGIN = "https://tklocal.example.test";
    private static final String DOMAIN = "auth-cors-" + System.nanoTime() + ".teos.localhost.floci.io";
    private static final List<String> VARY = List.of(
            "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers");

    private static SignedInUser user;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void setUpPoolUserAndCustomDomain() throws Exception {
        String poolId = createPool("CorsPool");
        user = signIn(poolId);
        cognitoJson("CreateUserPoolDomain", customDomain(DOMAIN, poolId, requestCertificate(DOMAIN)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "revoke", "userInfo"})
    @Order(2)
    void preflightEchoesTheOriginMethodAndHeaders(String endpoint) {
        assertVary(given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type , Authorization")
        .when()
                .options("/cognito-idp/oauth2/" + endpoint)
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Methods", "POST")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                .header("Access-Control-Allow-Credentials", "true")
                .header("Access-Control-Max-Age", "1800"));
    }

    @Test
    @Order(3)
    void refreshGrantEchoesTheOrigin() {
        assertVary(given()
                .header("Origin", ORIGIN)
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", user.clientId())
                .formParam("refresh_token", user.refreshToken())
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    @Order(4)
    void tokenErrorEchoesTheOrigin() {
        given()
                .header("Origin", ORIGIN)
                .formParam("client_id", user.clientId())
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"))
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Credentials", "true");
    }

    @Test
    @Order(5)
    void requestWithoutOriginGetsNoCorsHeaders() {
        given()
                .formParam("client_id", user.clientId())
        .when()
                .post("/cognito-idp/oauth2/token")
        .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Credentials", nullValue());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /oauth2/token",
            "HEAD, /oauth2/userInfo"})
    @Order(6)
    void customDomainPreflightIsAnsweredByCognito(String method, String path) {
        given()
                .header("Host", DOMAIN)
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", method)
        .when()
                .options(path)
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Methods", method)
                .header("Access-Control-Allow-Headers", nullValue())
                .header("Access-Control-Max-Age", "1800");
    }

    @Test
    @Order(7)
    void customDomainRefreshGrantEchoesTheOrigin() {
        given()
                .header("Host", DOMAIN)
                .header("Origin", ORIGIN)
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", user.clientId())
                .formParam("refresh_token", user.refreshToken())
        .when()
                .post("/oauth2/token")
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Credentials", "true");
    }

    @ParameterizedTest
    @CsvSource({
            "cors-bucket.s3.localhost.floci.io, /cognito-idp/oauth2/token, POST",
            "cognito-idp.s3.localhost.floci.io, /oauth2/token, POST",
            "localhost, /cognito-idp/oauth2/token, PUT",
            "localhost, /cognito-idp/oauth2/userInfo, HEAD"})
    @Order(8)
    void s3PreflightIsLeftToS3(String host, String path, String method) {
        given()
                .header("Host", host)
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", method)
        .when()
                .options(path)
        .then()
                .statusCode(403)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    @Order(9)
    void methodCognitoDoesNotServeIsLeftToS3() {
        given()
                .header("Origin", ORIGIN)
        .when()
                .get("/cognito-idp/oauth2/token")
        .then()
                .statusCode(404)
                .body(containsString("NoSuchBucket"))
                .header("Access-Control-Allow-Origin", nullValue());
    }

    private static void assertVary(ValidatableResponse response) {
        assertEquals(VARY, response.extract().headers().getValues("Vary"));
    }
}
