package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
class StepFunctionsExecutionHistoryIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getExecutionHistory_populatesExecutionSucceededEventDetails() throws Exception {
        String definition = """
                {
                  "Comment": "A Hello World example that demonstrates various state types in the Amazon States Language, and showcases data flow and transformations using variables and JSONata expressions. This example consists solely of flow control states, so no additional resources are needed to run it.",
                  "QueryLanguage": "JSONata",
                  "StartAt": "Set Variables and State Output",
                  "States": {
                    "Set Variables and State Output": {
                      "Type": "Pass",
                      "Comment": "A Pass state passes its input to its output, without performing work. They can also generate static JSON output, or transform JSON input using JSONata expressions, and pass the transformed data to the next state. Pass states are useful when constructing and debugging state machines.",
                      "End": true,
                      "Output": {
                        "ExecutionWaitTimeInSeconds": 3
                      },
                      "Assign": {
                        "CheckpointCount": 0,
                        "ExecutionWaitTimeInSeconds": 3
                      }
                    }
                  }
                }
                """;

        String stateMachineArn = createStateMachine("execution-history-test", definition);
        String executionArn = startExecution(stateMachineArn);
        waitForExecution(executionArn);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "executionArn": "%s",
                            "includeExecutionData": true
                        }
                        """, executionArn))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("events.find { it.type == 'PassStateEntered' }.type", equalTo("PassStateEntered"))
                .body("events.find { it.type == 'PassStateEntered' }.stateEnteredEventDetails", notNullValue())
                .body("events.find { it.type == 'PassStateEntered' }.stateEnteredEventDetails.name",
                        equalTo("Set Variables and State Output"))
                .body("events.find { it.type == 'PassStateEntered' }.passStateEnteredEventDetails", nullValue())
                .body("events.find { it.type == 'PassStateExited' }.type", equalTo("PassStateExited"))
                .body("events.find { it.type == 'PassStateExited' }.stateExitedEventDetails", notNullValue())
                .body("events.find { it.type == 'PassStateExited' }.stateExitedEventDetails.name",
                        equalTo("Set Variables and State Output"))
                .body("events.find { it.type == 'PassStateExited' }.passStateExitedEventDetails", nullValue())
                .body("events.find { it.type == 'ExecutionSucceeded' }.type", equalTo("ExecutionSucceeded"))
                .body("events.find { it.type == 'ExecutionSucceeded' }.executionSucceededEventDetails", notNullValue())
                .body("events.find { it.type == 'ExecutionSucceeded' }.executionSucceededEventDetails.output",
                        equalTo("{\"ExecutionWaitTimeInSeconds\":3}"));
    }

    @Test
    void getExecutionHistory_omitsExecutionSucceededEventDetailsWhenIncludeExecutionDataIsFalse() throws Exception {
        String definition = """
                {
                  "StartAt": "Set Output",
                  "States": {
                    "Set Output": {
                      "Type": "Pass",
                      "End": true,
                      "Result": {
                        "message": "hidden"
                      }
                    }
                  }
                }
                """;

        String stateMachineArn = createStateMachine("execution-history-no-data-test", definition);
        String executionArn = startExecution(stateMachineArn);
        waitForExecution(executionArn);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "executionArn": "%s",
                            "includeExecutionData": false
                        }
                        """, executionArn))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("events.find { it.type == 'PassStateEntered' }.stateEnteredEventDetails", nullValue())
                .body("events.find { it.type == 'PassStateExited' }.stateExitedEventDetails", nullValue())
                .body("events.find { it.type == 'ExecutionSucceeded' }.type", equalTo("ExecutionSucceeded"))
                .body("events.find { it.type == 'ExecutionSucceeded' }.executionSucceededEventDetails", nullValue());
    }

    private String createStateMachine(String name, String definition) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "name": "%s",
                            "definition": %s,
                            "roleArn": "%s"
                        }
                        """, name, quote(definition), ROLE_ARN))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String stateMachineArn) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "stateMachineArn": "%s"
                        }
                        """, stateMachineArn))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private void waitForExecution(String executionArn) {
        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Response response = given()
                            .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                            .contentType(SFN_CONTENT_TYPE)
                            .body(String.format("""
                                    { "executionArn": "%s" }
                                    """, executionArn))
                            .when()
                            .post("/");
                    response.then().statusCode(200);

                    String status = response.jsonPath().getString("status");
                    if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                        fail("Execution " + status + ": " + response.body().asString());
                    }
                    if (!"SUCCEEDED".equals(status)) {
                        fail("Execution status was " + status);
                    }
                });
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
