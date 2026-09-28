package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A JSON POST without an X-Amz-Target header must fail with
 * UnknownOperationException (404) rather than be served as an empty success.
 * The status and error name follow the AWS Common Errors entry for an
 * unrecognized operation; Smithy requires the header but does not specify a
 * server response when it is absent, so this pins Floci's chosen behavior.
 * Both the JSON 1.0 and JSON 1.1 controllers share the null-target branch.
 */
@QuarkusTest
class UnknownOperationMissingTargetIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void json10RequestWithoutTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .header("x-amzn-query-error", equalTo("UnknownOperationException;Sender"))
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Missing X-Amz-Target header."));
    }

    @Test
    void json10RequestWithEmptyBodyAndNoTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.0")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Missing X-Amz-Target header."));
    }

    @Test
    void json11RequestWithoutTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.1")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .header("x-amzn-query-error", equalTo("UnknownOperationException;Sender"))
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Missing X-Amz-Target header."));
    }

    @Test
    void json11RequestWithEmptyBodyAndNoTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.1")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Missing X-Amz-Target header."));
    }

    @Test
    void unknownTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "Nope.Nope")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownOperationException"))
            .body("message", equalTo("Unknown operation: Nope.Nope"));
    }

    @Test
    void json10ValidTargetStillReachesTheHandler() {
        given()
            .contentType("application/x-amz-json-1.0")
            .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("__type", not(equalTo("UnknownOperationException")));
    }

    @Test
    void json11ValidTargetStillReachesTheHandler() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AmazonSSM.DescribeParameters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("__type", not(equalTo("UnknownOperationException")));
    }

    @Test
    void cborRequestWithoutTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-cbor-1.1")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .header("x-amzn-query-error", equalTo("UnknownOperationException;Sender"))
            .header("smithy-protocol", equalTo("rpc-v2-cbor"));
    }

    @Test
    void cborRequestWithUnknownTargetReturnsUnknownOperation() {
        given()
            .contentType("application/x-amz-cbor-1.1")
            .header("X-Amz-Target", "Nope.Nope")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .header("x-amzn-query-error", equalTo("UnknownOperationException;Sender"))
            .header("smithy-protocol", equalTo("rpc-v2-cbor"));
    }
}
