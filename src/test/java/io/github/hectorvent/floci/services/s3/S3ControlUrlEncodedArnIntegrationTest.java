package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for S3 Control API handling of URL-encoded ARN path
 * parameters, which is what the Go AWS SDK v2 (and thus the Terraform AWS
 * provider v6.x) sends.
 *
 * <p>Tracked by upstream issue #435 (regression of fix #363).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ControlUrlEncodedArnIntegrationTest {

    private static final String BUCKET = "go-sdk-control-bucket";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private static final String DECODED_ARN =
            "arn:aws:s3:" + REGION + ":" + ACCOUNT + ":bucket/" + BUCKET;

    // What the Go SDK actually puts on the wire: colons AND slashes URL-encoded.
    private static final String ENCODED_ARN =
            "arn%3Aaws%3As3%3A" + REGION + "%3A" + ACCOUNT + "%3Abucket%2F" + BUCKET;

    @Test
    @Order(1)
    @DisplayName("setup: create bucket and tag it via the standard S3 tagging API")
    void setupBucketWithTags() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        String tagBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Tagging><TagSet>" +
                "<Tag><Key>Env</Key><Value>dev</Value></Tag>" +
                "</TagSet></Tagging>";

        given().contentType("application/xml").body(tagBody)
                .when().put("/" + BUCKET + "?tagging")
                .then().statusCode(204);
    }

    @Test
    @Order(2)
    @DisplayName("ListTagsForResource accepts URL-encoded ARN from Go AWS SDK v2 (#435)")
    void listTagsForResourceWithUrlEncodedArn() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/" + ENCODED_ARN)
        .then()
            .statusCode(200)
            .contentType(containsString("xml"))
            .body(containsString("<ListTagsForResourceResult"))
            .body(containsString("<Key>Env</Key>"))
            .body(containsString("<Value>dev</Value>"));
    }

    @Test
    @Order(3)
    @DisplayName("ListTagsForResource still accepts non-encoded ARN (from Java SDK)")
    void listTagsForResourceWithPlainArn() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/" + DECODED_ARN)
        .then()
            .statusCode(200)
            .body(containsString("<Key>Env</Key>"));
    }

    @Test
    @Order(4)
    @DisplayName("TagResource accepts URL-encoded ARN from Go AWS SDK v2 (#435)")
    void tagResourceWithUrlEncodedArn() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Tagging xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">" +
                "<Tags>" +
                "<Tag><Key>Owner</Key><Value>team-a</Value></Tag>" +
                "<Tag><Key>CostCenter</Key><Value>cc-42</Value></Tag>" +
                "</Tags></Tagging>";

        given()
            .header("x-amz-account-id", ACCOUNT)
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/v20180820/tags/" + ENCODED_ARN)
        .then()
            .statusCode(204);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/" + ENCODED_ARN)
        .then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(containsString("<Key>CostCenter</Key>"));
    }

    @Test
    @Order(5)
    @DisplayName("UntagResource accepts URL-encoded ARN from Go AWS SDK v2 (#435)")
    void untagResourceWithUrlEncodedArn() {
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .delete("/v20180820/tags/" + ENCODED_ARN + "?tagKeys=CostCenter")
        .then()
            .statusCode(204);

        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/" + ENCODED_ARN)
        .then()
            .statusCode(200)
            .body(containsString("<Key>Owner</Key>"))
            .body(not(containsString("<Key>CostCenter</Key>")));
    }

    @Test
    @Order(6)
    @DisplayName("Malformed ARN returns valid S3-style XML error body (#435)")
    void malformedArnReturnsXmlError() {
        // Path param must not contain a literal ':bucket/' segment after decoding.
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/arn%3Aaws%3As3%3A%3A%3Abogus%2F" + BUCKET)
        .then()
            .statusCode(400)
            .contentType(containsString("xml"))
            .body(containsString("<Error>"))
            .body(containsString("<Code>InvalidRequest</Code>"));
    }

    @Test
    @Order(7)
    @DisplayName("Malformed percent-encoding returns XML error, not JSON 500 (#435)")
    void malformedPercentEncodingReturnsXmlError() {
        // %ZZ is not a valid percent-encoding sequence; URLDecoder throws IAE.
        given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/arn%3Aaws%ZZbucket%2F" + BUCKET)
        .then()
            .statusCode(400)
            .contentType(containsString("xml"))
            .body(containsString("<Error>"))
            .body(containsString("<Code>InvalidRequest</Code>"));
    }
}
