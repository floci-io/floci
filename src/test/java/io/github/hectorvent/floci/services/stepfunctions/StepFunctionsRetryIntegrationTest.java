package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end Retry policy tests through the JSON 1.0 wire protocol.
 *
 * The fail-then-succeed case works without mocks by resolving the DynamoDB table name
 * from {@code $$.State.RetryCount}. Attempt 0 targets a table that does not exist and
 * fails. The retry re-resolves Parameters, targets the table that does exist, and
 * succeeds. Real AWS re-resolves Parameters per attempt the same way (verified in
 * us-east-1).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsRetryIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String DDB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String RETRY_TABLE = "sfn-retry-1";
    private static final String MISSING_TABLE = "sfn-retry-none";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createTargetTableForSecondAttempt() {
        given()
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                            "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                            "BillingMode": "PAY_PER_REQUEST"
                        }
                        """.formatted(RETRY_TABLE))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(1)
    void taskIsRetriedAndSucceedsOnSecondAttempt() throws Exception {
        var smArn = createStateMachine("sfn-retry-succeed-test", """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:putItem",
                      "Parameters": {
                        "TableName.$": "States.Format('sfn-retry-{}', $$.State.RetryCount)",
                        "Item": {"pk": {"S": "attempt-win"}}
                      },
                      "Retry": [{
                        "ErrorEquals": ["DynamoDB.ResourceNotFoundException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }],
                      "End": true
                    }
                  }
                }
                """);
        var execArn = startExecution(smArn, "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));

        var getItem = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {"TableName": "%s", "Key": {"pk": {"S": "attempt-win"}}}
                        """.formatted(RETRY_TABLE))
                .when().post("/");
        getItem.then().statusCode(200);
        assertEquals("attempt-win",
                mapper.readTree(getItem.body().asString()).path("Item").path("pk").path("S").asText());
    }

    @Test
    @Order(2)
    void exhaustedRetriesFallThroughToCatch() throws Exception {
        var smArn = createStateMachine("sfn-retry-catch-test", """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:putItem",
                      "Parameters": {
                        "TableName": "%s",
                        "Item": {"pk": {"S": "never"}}
                      },
                      "Retry": [{
                        "ErrorEquals": ["DynamoDB.ResourceNotFoundException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "ResultPath": "$.err",
                        "Next": "Handled"
                      }],
                      "End": true
                    },
                    "Handled": {"Type": "Pass", "End": true}
                  }
                }
                """.formatted(MISSING_TABLE));
        var execArn = startExecution(smArn, "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));
        var output = mapper.readTree(describe.jsonPath().getString("output"));
        assertEquals("DynamoDB.ResourceNotFoundException", output.path("err").path("Error").asText());
    }

    @Test
    @Order(3)
    void unmatchedErrorFailsWithoutRetry() {
        var smArn = createStateMachine("sfn-retry-unmatched-test", """
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:putItem",
                      "Parameters": {
                        "TableName": "%s",
                        "Item": {"pk": {"S": "never"}}
                      },
                      "Retry": [{
                        "ErrorEquals": ["SomeOther.Error"],
                        "IntervalSeconds": 1,
                        "MaxAttempts": 3
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(MISSING_TABLE));
        var execArn = startExecution(smArn, "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("DynamoDB.ResourceNotFoundException", describe.jsonPath().getString("error"));
    }

    private static String createStateMachine(String name, String definition) {
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            "roleArn": "%s",
                            "definition": %s
                        }
                        """.formatted(name, ROLE_ARN, quote(definition)))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private static String startExecution(String smArn, String input) {
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": %s}
                        """.formatted(smArn, quote(input)))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private static Response waitForTerminalState(String execArn) {
        for (var i = 0; i < 100; i++) {
            var resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when().post("/");
            var status = resp.jsonPath().getString("status");
            if (!"RUNNING".equals(status)) {
                return resp;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for execution " + execArn);
            }
        }
        fail("Execution did not complete within timeout: " + execArn);
        return null;
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
