package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private void assertS3ControlErrorResponse(Response response) {
        String body = response.getBody().asString();
        List<String> requestIds = XmlParser.extractAll(body, "RequestId");

        assertEquals(400, response.statusCode());
        assertThat(response.getContentType(), containsString("xml"));
        assertThat(body, containsString("<ErrorResponse xmlns=\"http://awss3control.amazonaws.com/doc/2018-08-20/\">"));
        assertThat(body, containsString("<Error>"));
        assertThat(body, containsString("<Code>InvalidRequest</Code>"));
        assertTrue(body.contains("</Error><RequestId>"),
                "expected top-level RequestId sibling after the Error block");
        assertEquals(2, requestIds.size(), "expected inner and top-level RequestId elements");
        assertEquals(requestIds.get(0), requestIds.get(1),
                "expected inner and top-level RequestId values to match");
        assertEquals(requestIds.get(0), response.getHeader("x-amz-request-id"));
        assertEquals(requestIds.get(0), response.getHeader("x-amzn-RequestId"));
        assertEquals(requestIds.get(0), response.getHeader("x-amz-id-2"));
    }

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
    @DisplayName("Malformed ARN returns S3 Control ErrorResponse wrapper (#435, #557)")
    void malformedArnReturnsXmlError() {
        // Path param must not contain a literal ':bucket/' segment after decoding.
        Response response = given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/arn%3Aaws%3As3%3A%3A%3Abogus%2F" + BUCKET);

        assertS3ControlErrorResponse(response);
    }

    @Test
    @Order(7)
    @DisplayName("Malformed percent-encoding returns S3 Control ErrorResponse wrapper (#435, #557)")
    void malformedPercentEncodingReturnsXmlError() {
        // %ZZ is not a valid percent-encoding sequence; URLDecoder throws IAE.
        Response response = given()
            .header("x-amz-account-id", ACCOUNT)
        .when()
            .get("/v20180820/tags/arn%3Aaws%ZZbucket%2F" + BUCKET);

        assertS3ControlErrorResponse(response);
    }
}
