package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final String NON_DEFAULT_ACCOUNT = "000000000001";
    private static final String MULTI_ACCOUNT_QUEUE_NAME = "esm-multi-account-queue";
    private static final String MULTI_ACCOUNT_FUNCTION_NAME = "esm-multi-account-fn";
    private static final String MULTI_ACCOUNT_QUEUE_ARN =
            "arn:aws:sqs:" + REGION + ":" + NON_DEFAULT_ACCOUNT + ":" + MULTI_ACCOUNT_QUEUE_NAME;
    private static final String MULTI_ACCOUNT_QUEUE_URL =
            "http://localhost:4566/" + NON_DEFAULT_ACCOUNT + "/" + MULTI_ACCOUNT_QUEUE_NAME;

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
    void updateEventSourceMappingReturnsFailureConfig() {
        String streamArn = "arn:aws:dynamodb:us-east-1:000000000000:table/esm-table/stream/2026-01-01T00:00:00.000";
        String destinationArn = "arn:aws:sqs:us-east-1:000000000000:esm-updated-failure-config-dlq";

        String uuid = given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "EventSourceArn": "%s"
                        }
                        """.formatted(FUNCTION_NAME, streamArn))
                .when()
                .post(LAMBDA_BASE + "/event-source-mappings/")
                .then()
                .statusCode(202)
                .body("$", not(hasKey("BisectBatchOnFunctionError")))
                .body("$", not(hasKey("DestinationConfig")))
                .extract()
                .path("UUID");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "BisectBatchOnFunctionError": true,
                          "DestinationConfig": {
                            "OnFailure": {
                              "Destination": "%s"
                            }
                          }
                        }
                        """.formatted(destinationArn))
                .when()
                .put(LAMBDA_BASE + "/event-source-mappings/" + uuid)
                .then()
                .statusCode(202)
                .body("BisectBatchOnFunctionError", equalTo(true))
                .body("DestinationConfig.OnFailure.Destination", equalTo(destinationArn));

        given()
                .when()
                .get(LAMBDA_BASE + "/event-source-mappings/" + uuid)
                .then()
                .statusCode(200)
                .body("BisectBatchOnFunctionError", equalTo(true))
                .body("DestinationConfig.OnFailure.Destination", equalTo(destinationArn));

        given()
                .delete(LAMBDA_BASE + "/event-source-mappings/" + uuid)
                .then()
                .statusCode(202);
    }

    @Test
    void responseOmitsFailureConfigWhenUnset() {
        String uuid = given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName": "%s",
                          "EventSourceArn": "%s"
                        }
                        """.formatted(FUNCTION_NAME, QUEUE_ARN))
                .when()
                .post(LAMBDA_BASE + "/event-source-mappings/")
                .then()
                .statusCode(202)
                .body("$", not(hasKey("BisectBatchOnFunctionError")))
                .body("$", not(hasKey("DestinationConfig")))
                .extract()
                .path("UUID");

        given()
                .delete(LAMBDA_BASE + "/event-source-mappings/" + uuid)
                .then()
                .statusCode(202);
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

    // ──────────────────────────── ScalingConfig ────────────────────────────

    @Test
    @Order(40)
    void createEventSourceMappingWithScalingConfigRoundTrips() {
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 5,
                    "ScalingConfig": { "MaximumConcurrency": 7 }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("ScalingConfig.MaximumConcurrency", equalTo(7))
        .extract()
            .path("UUID");

        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(200)
            .body("ScalingConfig.MaximumConcurrency", equalTo(7));

        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuid).then().statusCode(202);
    }

    @Test
    @Order(41)
    void createEventSourceMappingRejectsMaximumConcurrencyBelowMinimum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": { "MaximumConcurrency": 1 }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("between 2 and 1000"));
    }

    @Test
    @Order(42)
    void createEventSourceMappingRejectsMaximumConcurrencyAboveMaximum() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": { "MaximumConcurrency": 1001 }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("between 2 and 1000"));
    }

    @Test
    @Order(43)
    void createEventSourceMappingRejectsScalingConfigOnNonSqsSource() {
        // MaximumConcurrency is SQS-only in AWS. Kinesis uses ParallelizationFactor.
        String kinesisArn = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT_ID + ":stream/irrelevant";
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": { "MaximumConcurrency": 5 }
                }
                """.formatted(FUNCTION_NAME, kinesisArn))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("only supported for Amazon SQS"));
    }

    @Test
    @Order(44)
    void createEventSourceMappingRejectsEmptyScalingConfigOnNonSqsSource() {
        String kinesisArn = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT_ID + ":stream/irrelevant";
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": {}
                }
                """.formatted(FUNCTION_NAME, kinesisArn))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("only supported for Amazon SQS"));
    }

    @Test
    @Order(45)
    void createEventSourceMappingRejectsNonIntegerMaximumConcurrency() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": { "MaximumConcurrency": 2.5 }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("must be an integer"));
    }

    @Test
    @Order(46)
    void createEventSourceMappingRejectsStringMaximumConcurrency() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": { "MaximumConcurrency": "7" }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("numeric"));
    }

    @Test
    @Order(47)
    void createEventSourceMappingRejectsNonObjectScalingConfig() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "ScalingConfig": "not-an-object"
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(400)
            .body("message", containsString("JSON object"));
    }

    @Test
    @Order(48)
    void responseOmitsScalingConfigWhenUnset() {
        // A mapping created without ScalingConfig should not expose the key
        // in subsequent responses — AWS omits the field rather than returning
        // an empty object.
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 2
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("$", not(hasKey("ScalingConfig")))
        .extract()
            .path("UUID");

        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuid).then().statusCode(202);
    }

    @Test
    @Order(49)
    void updateEventSourceMappingRejectsInvalidScalingConfig() {
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 2
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
        .extract()
            .path("UUID");

        // Below minimum
        given()
            .contentType("application/json")
            .body("{ \"ScalingConfig\": { \"MaximumConcurrency\": 1 } }")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(400)
            .body("message", containsString("between 2 and 1000"));

        // Above maximum
        given()
            .contentType("application/json")
            .body("{ \"ScalingConfig\": { \"MaximumConcurrency\": 1001 } }")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(400)
            .body("message", containsString("between 2 and 1000"));

        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuid).then().statusCode(202);
    }

    @Test
    @Order(50)
    void listEventSourceMappingsWithMixedScalingConfig() {
        // Create one ESM with ScalingConfig and one without.
        String uuidWith = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 3,
                    "ScalingConfig": { "MaximumConcurrency": 10 }
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
        .extract()
            .path("UUID");

        String uuidWithout = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 4
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
        .extract()
            .path("UUID");

        // List should return both; one with ScalingConfig and one without.
        given()
        .when()
            .get(LAMBDA_BASE + "/event-source-mappings?FunctionName=" + FUNCTION_ARN)
        .then()
            .statusCode(200)
            .body("EventSourceMappings.find { it.UUID == '" + uuidWith + "' }.ScalingConfig.MaximumConcurrency",
                    equalTo(10))
            .body("EventSourceMappings.find { it.UUID == '" + uuidWithout + "' }.ScalingConfig",
                    nullValue());

        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuidWith).then().statusCode(202);
        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuidWithout).then().statusCode(202);
    }

    @Test
    @Order(51)
    void updateEventSourceMappingAddsAndClearsScalingConfig() {
        String uuid = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "EventSourceArn": "%s",
                    "BatchSize": 4
                }
                """.formatted(FUNCTION_NAME, QUEUE_ARN))
        .when()
            .post(LAMBDA_BASE + "/event-source-mappings")
        .then()
            .statusCode(202)
            .body("$", not(hasKey("ScalingConfig")))
        .extract()
            .path("UUID");

        // Add
        given()
            .contentType("application/json")
            .body("{ \"ScalingConfig\": { \"MaximumConcurrency\": 3 } }")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(202)
            .body("ScalingConfig.MaximumConcurrency", equalTo(3));

        // Clear by sending an empty ScalingConfig (AWS semantics)
        given()
            .contentType("application/json")
            .body("{ \"ScalingConfig\": {} }")
        .when()
            .put(LAMBDA_BASE + "/event-source-mappings/" + uuid)
        .then()
            .statusCode(202)
            .body("$", not(hasKey("ScalingConfig")));

        given().delete(LAMBDA_BASE + "/event-source-mappings/" + uuid).then().statusCode(202);
    }

    /**
     * Builds a SigV4 Authorization header whose access key ID is the given
     * 12-digit account ID. Floci's AccountResolver treats a 12-digit AKID as
     * the account ID directly (LocalStack multi-account convention). The full
     * header form (SignedHeaders + Signature) mirrors AccountIsolationIntegrationTest.
     */
    private static String authForAccount(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260101/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    /**
     * Returns the raw GetQueueAttributes (Query protocol) response body for the
     * queue, queried as the given account. Only ApproximateNumberOfMessagesNotVisible
     * is requested, so the body contains exactly one {@code <Value>} element.
     */
    private static String getQueueAttributesBody(String queueUrl, String auth) {
        return given()
                .header("Authorization", auth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "ApproximateNumberOfMessagesNotVisible")
                .formParam("Version", "2012-11-05")
            .when().post(SQS_BASE)
            .then().statusCode(200)
            .extract().asString();
    }

    /**
     * Parses the single ApproximateNumberOfMessagesNotVisible value out of a
     * GetQueueAttributes response body. Uses direct substring extraction (not
     * XmlPath) so the result is independent of XML-path library quirks.
     */
    private static int parseNotVisible(String body) {
        int start = body.indexOf("<Value>");
        int end = body.indexOf("</Value>");
        if (start < 0 || end <= start) {
            return 0;
        }
        try {
            return Integer.parseInt(body.substring(start + "<Value>".length(), end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ──────────────────────────── Multi-account ESM ────────────────────────────

    /**
     * Regression test for the SQS ESM poller account-aware lookup bug (M1/M2).
     *
     * The poller runs on a Vert.x timer thread outside CDI request scope. Before
     * the fix, doReceiveMessage() looked up the queue under the default account
     * and never found queues created under another account, so Lambda never fired.
     *
     * Creates a queue, function, and ESM under account 000000000001 (via a
     * 12-digit AKID in the SigV4 credential), sends a message, and asserts the
     * poller claims it (ApproximateNumberOfMessagesNotVisible > 0).
     */
    @Test
    @Order(60)
    void esmPollerReceivesMessagesFromNonDefaultAccount() throws Exception {
        String sqsAuth = authForAccount(NON_DEFAULT_ACCOUNT, "sqs");
        String lambdaAuth = authForAccount(NON_DEFAULT_ACCOUNT, "lambda");
        String esmUuid = null;
        try {
            // 1. Create the queue under account 000000000001
            given()
                .header("Authorization", sqsAuth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", MULTI_ACCOUNT_QUEUE_NAME)
                .formParam("Version", "2012-11-05")
            .when().post(SQS_BASE)
            .then().statusCode(200)
                .body(containsString(NON_DEFAULT_ACCOUNT + "/" + MULTI_ACCOUNT_QUEUE_NAME));

            // 2. Create the Lambda function under account 000000000001
            given()
                .header("Authorization", lambdaAuth)
                .contentType("application/json")
                .body("""
                    {
                        "FunctionName": "%s",
                        "Runtime": "nodejs20.x",
                        "Role": "arn:aws:iam::000000000001:role/lambda-role",
                        "Handler": "index.handler"
                    }
                    """.formatted(MULTI_ACCOUNT_FUNCTION_NAME))
            .when().post(LAMBDA_BASE + "/functions")
            .then().statusCode(201)
                .body("FunctionName", equalTo(MULTI_ACCOUNT_FUNCTION_NAME));

            // 3. Create the ESM linking the queue to the function (both account 000000000001)
            esmUuid = given()
                .header("Authorization", lambdaAuth)
                .contentType("application/json")
                .body("""
                    {
                        "FunctionName": "%s",
                        "EventSourceArn": "%s",
                        "BatchSize": 1
                    }
                    """.formatted(MULTI_ACCOUNT_FUNCTION_NAME, MULTI_ACCOUNT_QUEUE_ARN))
            .when().post(LAMBDA_BASE + "/event-source-mappings")
            .then().statusCode(202)
                .body("State", equalTo("Enabled"))
                .body("EventSourceArn", equalTo(MULTI_ACCOUNT_QUEUE_ARN))
            .extract().path("UUID");

            // 4. Send a message to the queue
            given()
                .header("Authorization", sqsAuth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", MULTI_ACCOUNT_QUEUE_URL)
                .formParam("MessageBody", "esm-multi-account-payload")
                .formParam("Version", "2012-11-05")
            .when().post(SQS_BASE)
            .then().statusCode(200);

            // 5. Wait for the poller to claim the message. Poll interval is 1000ms;
            //    a claimed message becomes in-flight (NotVisible > 0), proving the
            //    poller resolved the queue under account 000000000001 and received it.
            long deadline = System.currentTimeMillis() + 8_000;
            int notVisible = 0;
            String lastBody = "";
            while (System.currentTimeMillis() < deadline) {
                lastBody = getQueueAttributesBody(MULTI_ACCOUNT_QUEUE_URL, sqsAuth);
                notVisible = parseNotVisible(lastBody);
                if (notVisible > 0) {
                    break;
                }
                Thread.sleep(250);
            }
            assertTrue(notVisible > 0,
                    "ESM poller never claimed the message under account " + NON_DEFAULT_ACCOUNT
                            + " (ApproximateNumberOfMessagesNotVisible stayed 0) — "
                            + "account-aware queue lookup in doReceiveMessage is broken. "
                            + "Last GetQueueAttributes body: " + lastBody);

        } finally {
            // Best-effort cleanup so the test is re-runnable and leaks no state.
            // Each cleanup is isolated so a missing resource doesn't abort the others.
            try {
                if (esmUuid != null) {
                    given().header("Authorization", lambdaAuth)
                        .when().delete(LAMBDA_BASE + "/event-source-mappings/" + esmUuid);
                }
            } catch (Exception ignored) {
            }
            try {
                given().header("Authorization", sqsAuth)
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteQueue")
                    .formParam("QueueUrl", MULTI_ACCOUNT_QUEUE_URL)
                    .formParam("Version", "2012-11-05")
                    .when().post(SQS_BASE);
            } catch (Exception ignored) {
            }
            try {
                given().header("Authorization", lambdaAuth)
                    .contentType("application/json")
                    .when().delete(LAMBDA_BASE + "/functions/" + MULTI_ACCOUNT_FUNCTION_NAME);
            } catch (Exception ignored) {
            }
        }
    }
}
