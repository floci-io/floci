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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for Step Functions Local compatible mocked service integrations:
 * StartExecution against {@code <stateMachineArn>#<testCaseName>} with the mock
 * configuration file from src/test/resources/fixtures/sfn-mock-config.json.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsMockedServiceIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String DDB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String TABLE_NAME = "sfn-mock-items";
    private static final String UNSUPPORTED_RESOURCE = "arn:aws:states:::apigateway:invoke";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String mockSmArn;
    private static String ddbSmArn;
    private static String expressSmArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createStateMachinesAndTable() {
        mockSmArn = createStateMachine("sfn-mock-test", null, """
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.429"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }],
                      "Catch": [{
                        "ErrorEquals": ["ApiGateway.422"],
                        "ResultPath": "$.error",
                        "Next": "HandleError"
                      }],
                      "End": true
                    },
                    "HandleError": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE));

        ddbSmArn = createStateMachine("sfn-mock-ddb-test", null, """
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "ResultPath": "$.api",
                      "Next": "Save"
                    },
                    "Save": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::dynamodb:putItem",
                      "Parameters": {
                        "TableName": "%s",
                        "Item": {
                          "pk": {"S": "mocked-run"},
                          "statusCode": {"N.$": "States.Format('{}', $.api.StatusCode)"}
                        }
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE, TABLE_NAME));

        expressSmArn = createStateMachine("sfn-mock-express-test", "EXPRESS", """
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE));

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
                        """.formatted(TABLE_NAME))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(1)
    void mockedReturnRunsUnsupportedIntegrationToCompletion() throws Exception {
        var execArn = startExecution(mockSmArn + "#HappyPath", "{}");
        assertFalse(execArn.contains("#"));

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));
        assertEquals(mockSmArn, describe.jsonPath().getString("stateMachineArn"));

        var output = mapper.readTree(describe.jsonPath().getString("output"));
        assertEquals(200, output.path("StatusCode").asInt());
        assertEquals(1, output.path("ResponseBody").path("id").asInt());
    }

    @Test
    @Order(2)
    void mockedThrowReachesCatchWithErrorAndCauseUnchanged() throws Exception {
        var execArn = startExecution(mockSmArn + "#Throw422", "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));

        var output = mapper.readTree(describe.jsonPath().getString("output"));
        assertEquals("ApiGateway.422", output.path("error").path("Error").asText());
        assertEquals("Unprocessable", output.path("error").path("Cause").asText());
    }

    @Test
    @Order(3)
    void mockedThrowIsRetriedUsingAttemptKeyedResponses() throws Exception {
        var execArn = startExecution(mockSmArn + "#RetryOnce", "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));
        assertTrue(mapper.readTree(describe.jsonPath().getString("output"))
                .path("ResponseBody").path("retried").asBoolean());
    }

    @Test
    @Order(4)
    void mockedStateCombinesWithRealDynamoDbIntegration() throws Exception {
        var execArn = startExecution(ddbSmArn + "#MockedApiRealDdb", "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));

        var getItem = given()
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType(DDB_CONTENT_TYPE)
                .body("""
                        {
                            "TableName": "%s",
                            "Key": {"pk": {"S": "mocked-run"}}
                        }
                        """.formatted(TABLE_NAME))
                .when().post("/");
        getItem.then().statusCode(200);
        var item = mapper.readTree(getItem.body().asString()).path("Item");
        assertEquals("mocked-run", item.path("pk").path("S").asText());
        assertEquals("200", item.path("statusCode").path("N").asText());
    }

    @Test
    @Order(5)
    void syncExecutionRejectsMockTestCases() {
        // Verified against Step Functions Local 2.0.0, which rejects the suffix here with
        // UnsupportedOperation.
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartSyncExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s#HappyPath", "input": "{}"}
                        """.formatted(expressSmArn))
                .when().post("/");
        resp.then().statusCode(400);
        assertTrue(resp.body().asString().contains("not supported for the StartSyncExecution"));
    }

    @Test
    @Order(9)
    void syncExecutionDoesNotStripABareSuffix() {
        // Verified against Step Functions Local 2.0.0. StartSyncExecution looks up the raw ARN,
        // so a bare trailing '#' fails with StateMachineDoesNotExist instead of running unmocked.
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartSyncExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s#", "input": "{}"}
                        """.formatted(expressSmArn))
                .when().post("/");
        resp.then().statusCode(400);
        assertTrue(resp.body().asString().contains("does not exist"));
    }

    @Test
    @Order(6)
    void executionWithoutTestCaseSuffixStillCallsRealIntegration() {
        var execArn = startExecution(mockSmArn, "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("States.TaskFailed", describe.jsonPath().getString("error"));
        assertTrue(describe.jsonPath().getString("cause").contains("Unsupported resource"));
    }

    @Test
    @Order(7)
    void unknownTestCaseIsRejected() {
        // Intentional deviation. Step Functions Local 2.0.0 returns a plain HTTP 500 with the
        // text "No mock map found for test DoesNotExist". Floci returns a structured 400 instead.
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s#DoesNotExist", "input": "{}"}
                        """.formatted(mockSmArn))
                .when().post("/");
        resp.then().statusCode(400);
        assertTrue(resp.body().asString().contains("DoesNotExist"));
    }

    @Test
    @Order(8)
    void blankSuffixRunsWithoutMocks() {
        // Verified against Step Functions Local 2.0.0. A bare trailing '#' selects no test case
        // and the execution runs its real integrations.
        var execArn = startExecution(mockSmArn + "#", "{}");

        var describe = waitForTerminalState(execArn);
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("States.TaskFailed", describe.jsonPath().getString("error"));
        assertTrue(describe.jsonPath().getString("cause").contains("Unsupported resource"));
    }

    private static String createStateMachine(String name, String type, String definition) {
        var typeField = type != null ? "\"type\": \"%s\",".formatted(type) : "";
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            %s
                            "roleArn": "%s",
                            "definition": %s
                        }
                        """.formatted(name, typeField, ROLE_ARN, quote(definition)))
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
