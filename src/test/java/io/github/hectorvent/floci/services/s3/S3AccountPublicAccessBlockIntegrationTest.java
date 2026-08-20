package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for lex00/floci#73: S3 Control's account-level Block Public Access API
 * ({@code PutPublicAccessBlock}/{@code GetPublicAccessBlock}/{@code DeletePublicAccessBlock}
 * at {@code /v20180820/configuration/publicAccessBlock}, account-scoped, no bucket in the
 * path) had no route in {@link S3ControlController} at all, so
 * {@code aws_s3_account_public_access_block} 404'd on create. Distinct from the bucket-level
 * {@code PutBucketPublicAccessBlock}/{@code GetBucketPublicAccessBlock} covered elsewhere,
 * which already worked before this fix.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AccountPublicAccessBlockIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CONFIG_PATH = "/v20180820/configuration/publicAccessBlock";

    @Test
    @Order(1)
    void getBeforeAnyPutIsRejected() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get(CONFIG_PATH)
        .then()
            .statusCode(404)
            .body(containsString("NoSuchPublicAccessBlockConfiguration"));
    }

    @Test
    @Order(2)
    void putAccountPublicAccessBlock() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<PublicAccessBlockConfiguration xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">" +
                "<BlockPublicAcls>true</BlockPublicAcls>" +
                "<IgnorePublicAcls>true</IgnorePublicAcls>" +
                "<BlockPublicPolicy>true</BlockPublicPolicy>" +
                "<RestrictPublicBuckets>true</RestrictPublicBuckets>" +
                "</PublicAccessBlockConfiguration>";

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(body)
        .when()
            .put(CONFIG_PATH)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void getAccountPublicAccessBlockReturnsWhatWasSet() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get(CONFIG_PATH)
        .then()
            .statusCode(200)
            .body(containsString("<BlockPublicAcls>true</BlockPublicAcls>"))
            .body(containsString("<RestrictPublicBuckets>true</RestrictPublicBuckets>"));
    }

    @Test
    @Order(4)
    void deleteAccountPublicAccessBlock() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete(CONFIG_PATH)
        .then()
            .statusCode(204);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get(CONFIG_PATH)
        .then()
            .statusCode(404)
            .body(containsString("NoSuchPublicAccessBlockConfiguration"));
    }

    @Test
    @Order(5)
    void deleteIsIdempotentWhenNoConfigurationIsSet() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete(CONFIG_PATH)
        .then()
            .statusCode(204);
    }
}
