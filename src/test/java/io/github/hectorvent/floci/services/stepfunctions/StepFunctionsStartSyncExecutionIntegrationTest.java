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
 * Integration tests for the {@code arn:aws:states:::aws-sdk:sfn:startSyncExecution} task
 * integration, the way a Standard workflow calls an EXPRESS child workflow.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsStartSyncExecutionIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String RESOURCE = "arn:aws:states:::aws-sdk:sfn:startSyncExecution";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String succeedingChildArn;
    private static String failingChildArn;
    private static String standardChildArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createChildStateMachines() {
        succeedingChildArn = createStateMachine("sync-exec-child-ok", "EXPRESS", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Approve",
                  "States": {
                    "Approve": {
                      "Type": "Pass",
                      "Output": {"approved": true, "echoedAmount": "{% $states.input.amount %}"},
                      "End": true
                    }
                  }
                }
                """);

        failingChildArn = createStateMachine("sync-exec-child-fail", "EXPRESS", """
                {
                  "StartAt": "Reject",
                  "States": {
                    "Reject": {
                      "Type": "Fail",
                      "Error": "PoolClosed",
                      "Cause": "the pool is already closed"
                    }
                  }
                }
                """);

        standardChildArn = createStateMachine("sync-exec-child-standard", null, """
                {
                  "StartAt": "Approve",
                  "States": {
                    "Approve": {"Type": "Pass", "End": true}
                  }
                }
                """);
    }

    @Test
    @Order(1)
    void succeedingChildReturnsPascalCaseEnvelopeWithOutputAsJsonString() throws Exception {
        var parentArn = createStateMachine("sync-exec-parent-ok", null, callChild("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "CallChild",
                  "States": {
                    "CallChild": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "StateMachineArn": "CHILD_ARN",
                        "Input": {"amount": 1200}
                      },
                      "Output": {
                        "status": "{% $states.result.Status %}",
                        "childOutput": "{% $states.result.Output %}",
                        "childArn": "{% $states.result.StateMachineArn %}",
                        "startDate": "{% $states.result.StartDate %}"
                      },
                      "End": true
                    }
                  }
                }
                """, succeedingChildArn));

        var describe = waitForTerminalState(startExecution(parentArn, "{}"));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));

        var output = mapper.readTree(describe.jsonPath().getString("output"));
        assertEquals("SUCCEEDED", output.path("status").asText());
        assertEquals(succeedingChildArn, output.path("childArn").asText());

        // The SDK renders a timestamp as ISO-8601, where the wire response carries epoch seconds.
        assertTrue(output.path("startDate").asText()
                        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "StartDate is not the SDK's ISO-8601 rendering: " + output.path("startDate"));

        // Output arrives as a JSON string, the way the AWS SDK integration returns it.
        var childOutput = mapper.readTree(output.path("childOutput").asText());
        assertTrue(childOutput.path("approved").asBoolean());
        assertEquals(1200, childOutput.path("echoedAmount").asInt());
    }

    @Test
    @Order(2)
    void failingChildIsReportedThroughStatusWithoutFailingTheTask() throws Exception {
        var parentArn = createStateMachine("sync-exec-parent-fail", null, callChild("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "CallChild",
                  "States": {
                    "CallChild": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "StateMachineArn": "CHILD_ARN",
                        "Input": {}
                      },
                      "Output": {
                        "status": "{% $states.result.Status %}",
                        "error": "{% $states.result.Error %}",
                        "cause": "{% $states.result.Cause %}"
                      },
                      "End": true
                    }
                  }
                }
                """, failingChildArn));

        var describe = waitForTerminalState(startExecution(parentArn, "{}"));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"));

        var output = mapper.readTree(describe.jsonPath().getString("output"));
        assertEquals("FAILED", output.path("status").asText());
        assertEquals("PoolClosed", output.path("error").asText());
        assertEquals("the pool is already closed", output.path("cause").asText());
    }

    @Test
    @Order(3)
    void standardChildFailsTheTaskWithTheServicePrefixedError() {
        var parentArn = createStateMachine("sync-exec-parent-standard-child", null, """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "CallChild",
                  "States": {
                    "CallChild": {
                      "Type": "Task",
                      "Resource": "%s",
                      "Arguments": {
                        "StateMachineArn": "%s",
                        "Input": {}
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(RESOURCE, standardChildArn));

        var describe = waitForTerminalState(startExecution(parentArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.StateMachineTypeNotSupportedException", describe.jsonPath().getString("error"));
        assertTrue(describe.jsonPath().getString("cause").contains("only supported for EXPRESS"));
    }

    @Test
    @Order(4)
    void missingStateMachineArnFailsTheTask() {
        var parentArn = createStateMachine("sync-exec-parent-no-arn", null, """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "CallChild",
                  "States": {
                    "CallChild": {
                      "Type": "Task",
                      "Resource": "%s",
                      "Arguments": {"Input": {}},
                      "End": true
                    }
                  }
                }
                """.formatted(RESOURCE));

        var describe = waitForTerminalState(startExecution(parentArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.InvalidArnException", describe.jsonPath().getString("error"));
    }

    /**
     * Substitutes the two ARNs by token instead of {@code String.formatted}, because a JSONata
     * definition contains {@code {% ... %}} delimiters that a format string reads as conversions.
     */
    private static String callChild(String definition, String childArn) {
        return definition
                .replace("RESOURCE_ARN", RESOURCE)
                .replace("CHILD_ARN", childArn);
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
