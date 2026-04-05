package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for Lambda Event Source Mapping (ESM) endpoints.
 * Requires an SQS queue and Lambda function to be created first.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EsmIntegrationTest {

    private static final String LAMBDA_BASE = "/2015-03-31";
    private static final String SQS_BASE = "/";
    private static final String FUNCTION_NAME = "esm-test-fn";
    private static final String QUEUE_NAME = "esm-test-queue";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String QUEUE_ARN =
            "arn:aws:sqs:" + REGION + ":" + ACCOUNT_ID + ":" + QUEUE_NAME;
    private static final String FUNCTION_ARN =
            "arn:aws:lambda:" + REGION + ":" + ACCOUNT_ID + ":function:" + FUNCTION_NAME;

    private static String esmUuid;

    @Test
    @Order(1)
    void setupSqsQueue() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", QUEUE_NAME)
            .formParam("Version", "2012-11-05")
        .when()
            .post(SQS_BASE)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void setupLambdaFunction() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"
                }
                """.formatted(FUNCTION_NAME))
        .when()
            .post(LAMBDA_BASE + "/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FUNCTION_NAME));
    }

    @Test
    @Order(3)
    void createEventSourceMapping() {
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 5
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("UUID", notNullValue())
            .body("FunctionArn", equalTo(FUNCTION_ARN))
            .body("EventSourceArn", equalTo(QUEUE_ARN))
            .body("BatchSize", equalTo(5))
            .body("State", equalTo("Enabled"))
        .extract()
            .path("UUID");

        EsmIntegrationTest.esmUuid = uuid;
    }

    @Test
    @Order(4)
    void createEventSourceMappingWithReportBatchItemFailures() {
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 3,
                    "FunctionResponseTypes": ["ReportBatchItemFailures"]
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("UUID", notNullValue())
            .body("FunctionResponseTypes", hasItem("ReportBatchItemFailures"))
        .extract()
            .path("UUID");

        // Verify it round-trips through GET
        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(200)
            .body("FunctionResponseTypes", hasItem("ReportBatchItemFailures"));

        // Clean up
        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuid).then().statusCode(202);
    }

    @Test
    @Order(5)
    void createEventSourceMappingForNonExistentFunction() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "does-not-exist",
                    "EventSourceArn": "%s"
                }
                """.formatted(QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(6)
    void createEventSourceMappingUnsupportedArn() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "arn:aws:sns:us-east-1:000000000000:my-topic"
                }
                """.formatted(FUNCTION_NAME))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(7)
    void getEventSourceMapping() {
        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(200)
            .body("UUID", equalTo(esmUuid))
            .body("FunctionArn", equalTo(FUNCTION_ARN))
            .body("BatchSize", equalTo(5))
            .body("State", equalTo("Enabled"));
    }

    @Test
    @Order(8)
    void listEventSourceMappings() {
        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(200)
            .body("EventSourceMappings", hasSize(greaterThanOrEqualTo(1)))
            .body("EventSourceMappings[0].UUID", notNullValue());
    }

    @Test
    @Order(9)
    void listEventSourceMappingsByFunction() {
        given()
            .queryParam("FunctionName", FUNCTION_ARN)
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(200)
            .body("EventSourceMappings", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(10)
    void updateEventSourceMapping() {
        given()
            .contentType("application/json")
            .body("{\"BatchSize\": 20, \"Enabled\": true}")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(202)
            .body("BatchSize", equalTo(20))
            .body("State", equalTo("Enabled"));
    }

    @Test
    @Order(11)
    void disableEventSourceMapping() {
        given()
            .contentType("application/json")
            .body("{\"Enabled\": false}")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(202)
            .body("State", equalTo("Disabled"));
    }

    @Test
    @Order(12)
    void getEventSourceMappingNotFound() {
        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings/non-existent-uuid")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(13)
    void deleteEventSourceMapping() {
        given()
        .when()
            .delete(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(202)
            .body("UUID", equalTo(esmUuid));
    }

    @Test
    @Order(14)
    void deleteEventSourceMappingNotFound() {
        given()
        .when()
            .delete(LAMBDA_BASE + "/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(404);
    }
}
