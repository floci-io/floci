package io.github.hectorvent.floci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;

/**
 * Verifies that {@link AwsRequestIdFilter} injects {@code x-amz-request-id} and
 * {@code x-amzn-RequestId} headers on every response, across all three AWS wire
 * protocols supported by Floci: REST XML (S3), JSON 1.0 (DynamoDB), and Query (SQS).
 *
 * <p>These headers are the source from which the AWS SDK v3 populates
 * {@code $metadata.requestId} and {@code $metadata.httpStatusCode} on every
 * command output.
 */
@QuarkusTest
class AwsRequestIdFilterIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SSM_CONTENT_TYPE, ContentType.TEXT)
                        .encodeContentTypeAs(DYNAMODB_CONTENT_TYPE, ContentType.TEXT));
    }

    // --- REST XML protocol (S3) ---

    @Test
    void s3SuccessResponseContainsRequestIdHeaders() {
        // Create a temporary bucket, verify headers, then clean it up
        String bucket = "request-id-test-bucket";

        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());

        given().delete("/" + bucket);
    }

    @Test
    void s3ErrorResponseContainsRequestIdHeaders() {
        // Requesting a non-existent bucket produces a 404 error response —
        // the headers must still be present so the SDK can surface the request ID.
        given()
        .when()
            .get("/no-such-bucket-floci-test")
        .then()
            .statusCode(404)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());
    }

    @Test
    void s3CopyObjectResponseContainsRequestIdHeaders() {
        String bucket = "request-id-copy-bucket";
        given().put("/" + bucket).then().statusCode(200);

        given()
            .contentType("text/plain")
            .body("hello")
        .when()
            .put("/" + bucket + "/src.txt")
        .then()
            .statusCode(200);

        // CopyObject is the operation the user reported as missing $metadata.requestId
        given()
            .header("x-amz-copy-source", "/" + bucket + "/src.txt")
        .when()
            .put("/" + bucket + "/dst.txt")
        .then()
            .statusCode(200)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());

        given().delete("/" + bucket + "/src.txt");
        given().delete("/" + bucket + "/dst.txt");
        given().delete("/" + bucket);
    }

    // --- JSON 1.0 protocol (DynamoDB) ---

    @Test
    void dynamoDbSuccessResponseContainsRequestIdHeaders() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());
    }

    @Test
    void dynamoDbErrorResponseContainsRequestIdHeaders() {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .contentType("application/x-amz-json-1.0")
            .body("{\"TableName\": \"NonExistentTable\", \"Key\": {\"id\": {\"S\": \"1\"}}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());
    }

    // --- Query protocol (SQS) ---

    @Test
    void sqsSuccessResponseContainsRequestIdHeaders() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListQueues")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());
    }

    // --- JSON 1.1 protocol (SSM) ---

    @Test
    void ssmSuccessResponseContainsRequestIdHeaders() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .contentType("application/x-amz-json-1.1")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .header("x-amz-request-id", notNullValue())
            .header("x-amzn-RequestId", notNullValue());
    }
}
