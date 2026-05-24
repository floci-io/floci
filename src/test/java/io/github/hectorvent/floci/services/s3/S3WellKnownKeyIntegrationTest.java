package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * S3 keys under <code>.well-known/</code> must NOT collide with the Cognito
 * well-known OIDC endpoints (<code>/{poolId}/.well-known/jwks.json</code> and
 * <code>/{poolId}/.well-known/openid-configuration</code>).
 *
 * <p>Discriminator: real Cognito UserPool IDs always contain an underscore
 * (<code>&lt;region&gt;_&lt;random&gt;</code>, e.g. <code>us-east-1_xxx</code>),
 * while AWS S3 bucket names forbid underscores. Any first path segment without
 * an underscore therefore cannot be a Cognito pool id and must be routed to
 * S3.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3WellKnownKeyIntegrationTest {

    private static final String BUCKET = "test-bucket-jwks-967";
    private static final String JWKS_KEY = ".well-known/jwks.json";
    private static final String SIBLING_KEY = ".well-known/test.json";
    private static final String JWKS_BODY = "{\"keys\":[{\"kty\":\"RSA\"}]}";
    private static final String SIBLING_BODY = "{\"hello\":\"world\"}";

    @Test
    @Order(0)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void putAndGetSiblingWellKnownObject() {
        given()
            .header("Content-Type", "application/json")
            .body(SIBLING_BODY)
        .when()
            .put("/" + BUCKET + "/" + SIBLING_KEY)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/" + SIBLING_KEY)
        .then()
            .statusCode(200)
            .body(equalTo(SIBLING_BODY));
    }

    @Test
    @Order(2)
    void putAndGetJwksObject_mustNotBeRoutedToCognito() {
        given()
            .header("Content-Type", "application/json")
            .body(JWKS_BODY)
        .when()
            .put("/" + BUCKET + "/" + JWKS_KEY)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/" + JWKS_KEY)
        .then()
            .statusCode(200)
            .body(equalTo(JWKS_BODY));
    }

    @Test
    @Order(3)
    void getJwksObject_mustNotReturnCognitoErrorBody() {
        String body = given()
        .when()
            .get("/" + BUCKET + "/" + JWKS_KEY)
        .then()
            .extract().asString();

        if (body.contains("ResourceNotFoundException") || body.contains("User pool not found")) {
            throw new AssertionError(
                "GET /" + BUCKET + "/" + JWKS_KEY
                + " was routed to Cognito instead of S3. Response: " + body);
        }
    }

    @Test
    @Order(4)
    void getOpenIdConfigurationObject_mustNotBeRoutedToCognito() {
        String openIdKey = ".well-known/openid-configuration";
        String openIdBody = "{\"issuer\":\"custom\"}";

        given()
            .header("Content-Type", "application/json")
            .body(openIdBody)
        .when()
            .put("/" + BUCKET + "/" + openIdKey)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/" + openIdKey)
        .then()
            .statusCode(200)
            .body(containsString("custom"));
    }
}
