package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.hamcrest.Matchers;
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
 * Integration tests for the {@code arn:aws:states:::aws-sdk:} task integrations of Step Functions
 * itself and of EventBridge Scheduler: {@code sfn:startExecution}, {@code sfn:sendTaskSuccess},
 * {@code sfn:sendTaskFailure}, {@code scheduler:createSchedule}, {@code scheduler:updateSchedule}
 * and {@code scheduler:deleteSchedule}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsAwsSdkTaskIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String quickChildArn;
    private static String slowChildArn;
    private static String callbackQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_createChildrenAndCallbackQueue() {
        quickChildArn = createStateMachine("aws-sdk-task-child-quick", """
                {
                  "StartAt": "Done",
                  "States": {"Done": {"Type": "Pass", "End": true}}
                }
                """);
        slowChildArn = createStateMachine("aws-sdk-task-child-slow", """
                {
                  "StartAt": "Linger",
                  "States": {
                    "Linger": {"Type": "Wait", "Seconds": 5, "Next": "Done"},
                    "Done": {"Type": "Pass", "End": true}
                  }
                }
                """);
        callbackQueueUrl = createQueue("aws-sdk-task-callback");
    }

    // ──────────────────────────── sfn:startExecution ────────────────────────────

    @Test
    @Order(1)
    void startExecutionReturnsThePascalCaseArnAndAnIsoStartDate() throws Exception {
        var smArn = createStateMachine("aws-sdk-start-execution", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Start",
                  "States": {
                    "Start": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:startExecution",
                      "Arguments": {"StateMachineArn": "CHILD_ARN", "Input": {"amount": 1200}},
                      "End": true
                    }
                  }
                }
                """.replace("CHILD_ARN", quickChildArn));

        var result = mapper.readTree(succeedingOutputOf(smArn, "{}"));
        assertTrue(result.path("ExecutionArn").asText()
                        .contains(":execution:aws-sdk-task-child-quick:"),
                "unexpected ExecutionArn: " + result.path("ExecutionArn").asText());
        assertTrue(result.path("StartDate").asText().matches(ISO_INSTANT),
                "StartDate is not the SDK's ISO-8601 rendering: " + result.path("StartDate"));
        assertEquals(2, result.size(), "StartExecution returns ExecutionArn and StartDate only");

        // The child really ran, with the Input the task passed it.
        var child = describeExecution(result.path("ExecutionArn").asText());
        assertEquals("{\"amount\":1200}", child.jsonPath().getString("input"));
    }

    @Test
    @Order(2)
    void startExecutionDoesNotWaitForTheChild() throws Exception {
        var smArn = createStateMachine("aws-sdk-start-execution-no-wait", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Start",
                  "States": {
                    "Start": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:startExecution",
                      "Arguments": {"StateMachineArn": "CHILD_ARN"},
                      "End": true
                    }
                  }
                }
                """.replace("CHILD_ARN", slowChildArn));

        var result = mapper.readTree(succeedingOutputOf(smArn, "{}"));
        var child = describeExecution(result.path("ExecutionArn").asText());
        assertEquals("RUNNING", child.jsonPath().getString("status"),
                "the parent finished before the 5s child, so the child is still running");
    }

    @Test
    @Order(3)
    void startExecutionOnAMissingStateMachineFailsWithTheSdkExceptionName() {
        var smArn = createStateMachine("aws-sdk-start-execution-missing", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Start",
                  "States": {
                    "Start": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:startExecution",
                      "Arguments": {
                        "StateMachineArn": "arn:aws:states:us-east-1:000000000000:stateMachine:nope"
                      },
                      "End": true
                    }
                  }
                }
                """);

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.StateMachineDoesNotExistException", describe.jsonPath().getString("error"));
    }

    @Test
    @Order(4)
    void startExecutionReusingAnExecutionNameFailsWithTheSdkExceptionName() {
        var smArn = createStateMachine("aws-sdk-start-execution-named", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Start",
                  "States": {
                    "Start": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:startExecution",
                      "Arguments": {"StateMachineArn": "CHILD_ARN", "Name": "taken-once"},
                      "End": true
                    }
                  }
                }
                """.replace("CHILD_ARN", quickChildArn));

        assertEquals("SUCCEEDED", waitForTerminalState(startExecution(smArn, "{}"))
                .jsonPath().getString("status"));

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.ExecutionAlreadyExistsException", describe.jsonPath().getString("error"));
    }

    // ──────────────────────────── sfn:sendTaskSuccess / sendTaskFailure ────────────────────────────

    @Test
    @Order(5)
    void sendTaskSuccessResolvesTheWaitingExecutionWithItsOutput() throws Exception {
        var waitingExecArn = startExecution(createWaiterStateMachine("aws-sdk-send-success-waiter"), "{}");
        var taskToken = receiveTaskToken();

        var senderArn = createStateMachine("aws-sdk-send-success", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Resume",
                  "States": {
                    "Resume": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:sendTaskSuccess",
                      "Arguments": {
                        "TaskToken": "{% $states.input.token %}",
                        "Output": {"approved": true}
                      },
                      "End": true
                    }
                  }
                }
                """);

        assertEquals("{}", succeedingOutputOf(senderArn, tokenInput(taskToken)),
                "SendTaskSuccess answers with an empty response");

        var waiter = waitForTerminalState(waitingExecArn);
        assertEquals("SUCCEEDED", waiter.jsonPath().getString("status"));
        assertTrue(mapper.readTree(waiter.jsonPath().getString("output")).path("approved").asBoolean());
    }

    @Test
    @Order(6)
    void sendTaskFailureFailsTheWaitingExecutionWithItsErrorAndCause() throws Exception {
        var waitingExecArn = startExecution(createWaiterStateMachine("aws-sdk-send-failure-waiter"), "{}");
        var taskToken = receiveTaskToken();

        var senderArn = createStateMachine("aws-sdk-send-failure", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Reject",
                  "States": {
                    "Reject": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:sendTaskFailure",
                      "Arguments": {
                        "TaskToken": "{% $states.input.token %}",
                        "Error": "PoolClosed",
                        "Cause": "the pool is already closed"
                      },
                      "End": true
                    }
                  }
                }
                """);

        assertEquals("{}", succeedingOutputOf(senderArn, tokenInput(taskToken)));

        var waiter = waitForTerminalState(waitingExecArn);
        assertEquals("FAILED", waiter.jsonPath().getString("status"));
        assertEquals("PoolClosed", waiter.jsonPath().getString("error"));
        assertEquals("the pool is already closed", waiter.jsonPath().getString("cause"));
    }

    @Test
    @Order(7)
    void sendTaskSuccessOnATokenNobodyIsWaitingForFailsTheTask() {
        var smArn = createStateMachine("aws-sdk-send-success-bad-token", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Resume",
                  "States": {
                    "Resume": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:sendTaskSuccess",
                      "Arguments": {"TaskToken": "not-a-real-token", "Output": {}},
                      "End": true
                    }
                  }
                }
                """);

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.InvalidTokenException", describe.jsonPath().getString("error"));
        assertEquals("Invalid Token: 'Invalid token'", describe.jsonPath().getString("cause"));
    }

    @Test
    @Order(8)
    void sendTaskFailureOnATokenNobodyIsWaitingForFailsTheTask() {
        var smArn = createStateMachine("aws-sdk-send-failure-bad-token", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Reject",
                  "States": {
                    "Reject": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:sendTaskFailure",
                      "Arguments": {"TaskToken": "not-a-real-token", "Error": "E", "Cause": "c"},
                      "End": true
                    }
                  }
                }
                """);

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.InvalidTokenException", describe.jsonPath().getString("error"));
    }

    @Test
    @Order(9)
    void sendTaskSuccessOnATokenWhoseResourceInvocationThrewFailsAsInvalid() throws Exception {
        // "Leak" registers a task token, then fails invoking its own (unsupported) resource before it
        // ever waits on that token. The registration must not outlive the failure: the token is
        // recovered from the TaskScheduled event it wrote on its way to failing, and a second
        // execution's SendTaskSuccess on that same token must find nothing pending for it.
        var leakArn = createStateMachine("aws-sdk-task-leaked-token", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Leak",
                  "States": {
                    "Leak": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::unsupported:doSomething.waitForTaskToken",
                      "Arguments": {"token": "{% $states.context.Task.Token %}"},
                      "End": true
                    }
                  }
                }
                """);

        var leaked = waitForTerminalState(startExecution(leakArn, "{}"));
        assertEquals("FAILED", leaked.jsonPath().getString("status"));
        assertEquals("States.TaskFailed", leaked.jsonPath().getString("error"));
        var taskToken = scheduledTaskToken(leaked.jsonPath().getString("executionArn"));

        var senderArn = createStateMachine("aws-sdk-send-success-leaked-token", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Resume",
                  "States": {
                    "Resume": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:sendTaskSuccess",
                      "Arguments": {"TaskToken": "{% $states.input.token %}", "Output": {}},
                      "End": true
                    }
                  }
                }
                """);

        var describe = waitForTerminalState(startExecution(senderArn, tokenInput(taskToken)));

        assertEquals("FAILED", describe.jsonPath().getString("status"),
                "Leak's token must be discarded once its resource invocation throws, not left pending");
        assertEquals("Sfn.InvalidTokenException", describe.jsonPath().getString("error"));
    }

    // ──────────────────────────── scheduler:createSchedule / updateSchedule ────────────────────────────

    @Test
    @Order(10)
    void createScheduleReturnsTheScheduleArnAndTheScheduleIsReadable() throws Exception {
        var result = mapper.readTree(succeedingOutputOf(
                createStateMachine("aws-sdk-create-schedule", scheduleTask("createSchedule",
                        "payout-nightly", "rate(1 day)")), "{}"));

        assertTrue(result.path("ScheduleArn").asText().endsWith(":schedule/default/payout-nightly"),
                "unexpected ScheduleArn: " + result.path("ScheduleArn").asText());
        assertEquals(1, result.size(), "CreateSchedule returns ScheduleArn only");

        given().when().get("/schedules/payout-nightly")
                .then().statusCode(200)
                .body("ScheduleExpression", Matchers.equalTo("rate(1 day)"));
    }

    @Test
    @Order(11)
    void updateScheduleKeepsTheArnAndChangesTheExpression() throws Exception {
        var result = mapper.readTree(succeedingOutputOf(
                createStateMachine("aws-sdk-update-schedule", scheduleTask("updateSchedule",
                        "payout-nightly", "rate(2 days)")), "{}"));

        assertTrue(result.path("ScheduleArn").asText().endsWith(":schedule/default/payout-nightly"));

        given().when().get("/schedules/payout-nightly")
                .then().statusCode(200)
                .body("ScheduleExpression", Matchers.equalTo("rate(2 days)"));
    }

    @Test
    @Order(12)
    void createScheduleOnANameAlreadyTakenFailsWithConflict() {
        var describe = waitForTerminalState(startExecution(
                createStateMachine("aws-sdk-create-schedule-twice", scheduleTask("createSchedule",
                        "payout-nightly", "rate(1 day)")), "{}"));

        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Scheduler.ConflictException", describe.jsonPath().getString("error"));
    }

    @Test
    @Order(13)
    void updateScheduleOnAScheduleThatDoesNotExistFailsWithResourceNotFound() {
        var describe = waitForTerminalState(startExecution(
                createStateMachine("aws-sdk-update-missing-schedule", scheduleTask("updateSchedule",
                        "no-such-schedule", "rate(1 day)")), "{}"));

        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Scheduler.ResourceNotFoundException", describe.jsonPath().getString("error"));
    }

    @Test
    @Order(14)
    void createScheduleWithIncompleteEventBridgeParametersIsCatchableAsAValidationException() throws Exception {
        var smArn = createStateMachine("aws-sdk-create-schedule-invalid-target", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Schedule",
                  "States": {
                    "Schedule": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:createSchedule",
                      "Arguments": {
                        "Name": "payout-invalid-target",
                        "ScheduleExpression": "rate(1 day)",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {
                          "Arn": "TARGET_ARN",
                          "RoleArn": "ROLE",
                          "EventBridgeParameters": {"DetailType": "payout.requested"}
                        }
                      },
                      "Catch": [{
                        "ErrorEquals": ["Scheduler.ValidationException"],
                        "Next": "Recovered",
                        "Output": {"caughtError": "{% $states.errorOutput.Error %}"}
                      }],
                      "End": true
                    },
                    "Recovered": {"Type": "Pass", "End": true}
                  }
                }
                """
                .replace("TARGET_ARN", quickChildArn)
                .replace("ROLE", ROLE_ARN));

        var result = mapper.readTree(succeedingOutputOf(smArn, "{}"));
        assertEquals("Scheduler.ValidationException", result.path("caughtError").asText(),
                "a target the parser rejects must reach Catch as an SDK exception, not States.Runtime");
    }

    @Test
    @Order(15)
    void createScheduleWithAMalformedListIsCatchableAsASerializationException() throws Exception {
        // A malformed EcsParameters list is refused before the conversion runs, so it reaches Catch
        // as the SDK exception AWS sends rather than as States.Runtime.
        for (String[] shape : new String[][]{
                {"scalar-strategy", "\"CapacityProviderStrategy\": [\"FARGATE\"]"},
                {"non-list-strategy", "\"CapacityProviderStrategy\": \"FARGATE\""},
                {"scalar-subnet",
                    "\"NetworkConfiguration\": {\"awsvpcConfiguration\": {\"Subnets\": [123]}}"},
                {"non-list-subnet",
                    "\"NetworkConfiguration\": {\"awsvpcConfiguration\": {\"Subnets\": \"subnet-a\"}}"}}) {
            var smArn = createStateMachine("aws-sdk-create-schedule-" + shape[0], """
                    {
                      "QueryLanguage": "JSONata",
                      "StartAt": "Schedule",
                      "States": {
                        "Schedule": {
                          "Type": "Task",
                          "Resource": "arn:aws:states:::aws-sdk:scheduler:createSchedule",
                          "Arguments": {
                            "Name": "payout-SHAPE",
                            "ScheduleExpression": "rate(1 day)",
                            "FlexibleTimeWindow": {"Mode": "OFF"},
                            "Target": {
                              "Arn": "TARGET_ARN",
                              "RoleArn": "ROLE",
                              "EcsParameters": {
                                "TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/p:1",
                                MALFORMED_FIELD
                              }
                            }
                          },
                          "Catch": [{
                            "ErrorEquals": ["Scheduler.SerializationException"],
                            "Next": "Recovered",
                            "Output": {"caughtError": "{% $states.errorOutput.Error %}"}
                          }],
                          "End": true
                        },
                        "Recovered": {"Type": "Pass", "End": true}
                      }
                    }
                    """
                    .replace("MALFORMED_FIELD", shape[1])
                    .replace("SHAPE", shape[0])
                    .replace("TARGET_ARN", quickChildArn)
                    .replace("ROLE", ROLE_ARN));

            var result = mapper.readTree(succeedingOutputOf(smArn, "{}"));
            assertEquals("Scheduler.SerializationException", result.path("caughtError").asText(),
                    shape[0] + " must reach Catch as an SDK exception, not States.Runtime");
        }
    }

    @Test
    @Order(16)
    void deleteScheduleReturnsAnEmptyObjectAndRemovesTheSchedule() throws Exception {
        JsonNode result = mapper.readTree(succeedingOutputOf(
                createStateMachine("aws-sdk-delete-schedule", deleteScheduleTask(
                        "payout-nightly", null)), "{}"));

        assertTrue(result.isObject());
        assertEquals(0, result.size(), "DeleteSchedule returns an empty object");
        given().when().get("/schedules/payout-nightly").then().statusCode(404);
    }

    @Test
    @Order(17)
    void deleteScheduleUsesTheNamedGroup() throws Exception {
        given()
                .contentType("application/json")
                .body("{}")
                .when().post("/schedule-groups/payments")
                .then().statusCode(200);
        given()
                .contentType("application/json")
                .body("""
                        {
                          "GroupName": "payments",
                          "ScheduleExpression": "rate(1 day)",
                          "FlexibleTimeWindow": {"Mode": "OFF"},
                          "Target": {"Arn": "TARGET_ARN", "RoleArn": "ROLE"}
                        }
                        """.replace("TARGET_ARN", quickChildArn).replace("ROLE", ROLE_ARN))
                .when().post("/schedules/grouped-payout")
                .then().statusCode(200);

        succeedingOutputOf(createStateMachine("aws-sdk-delete-grouped-schedule",
                deleteScheduleTask("grouped-payout", "payments")), "{}");

        given().queryParam("groupName", "payments")
                .when().get("/schedules/grouped-payout")
                .then().statusCode(404);
    }

    @Test
    @Order(18)
    void deleteMissingScheduleIsCatchableAsResourceNotFound() throws Exception {
        JsonNode result = mapper.readTree(succeedingOutputOf(
                createStateMachine("aws-sdk-delete-missing-schedule", catchingDeleteScheduleTask(
                        "no-such-schedule", "Scheduler.ResourceNotFoundException")), "{}"));

        assertEquals("Scheduler.ResourceNotFoundException", result.path("caughtError").asText());
    }

    @Test
    @Order(19)
    void deleteScheduleWithoutANameIsCatchableAsValidationException() throws Exception {
        JsonNode result = mapper.readTree(succeedingOutputOf(
                createStateMachine("aws-sdk-delete-schedule-without-name", catchingDeleteScheduleTask(
                        null, "Scheduler.ValidationException")), "{}"));

        assertEquals("Scheduler.ValidationException", result.path("caughtError").asText());
    }

    @Test
    @Order(20)
    void createScheduleAcceptsRfc3339DatesFromJsonata() throws Exception {
        String stateMachineArn = createStateMachine("aws-sdk-create-schedule-dates", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Schedule",
                  "States": {
                    "Schedule": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:createSchedule",
                      "Arguments": {
                        "Name": "payout-dated",
                        "ScheduleExpression": "rate(1 day)",
                        "StartDate": "2099-01-01T05:30:00.500+05:30",
                        "EndDate": "2099-01-02T00:00:00Z",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {"Arn": "TARGET_ARN", "RoleArn": "ROLE"}
                      },
                      "End": true
                    }
                  }
                }
                """.replace("TARGET_ARN", quickChildArn).replace("ROLE", ROLE_ARN));

        succeedingOutputOf(stateMachineArn, "{}");

        JsonNode schedule = mapper.readTree(given().when().get("/schedules/payout-dated")
                .then().statusCode(200).extract().body().asString());
        assertEquals(4070908800.5d, schedule.path("StartDate").asDouble(), 0.001d);
        assertEquals(4070995200L, Math.round(schedule.path("EndDate").asDouble()));
    }

    @Test
    @Order(21)
    void updateScheduleAcceptsRfc3339DatesFromJsonPath() throws Exception {
        String stateMachineArn = createStateMachine("aws-sdk-update-schedule-dates", """
                {
                  "StartAt": "Schedule",
                  "States": {
                    "Schedule": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:updateSchedule",
                      "Parameters": {
                        "Name": "payout-dated",
                        "ScheduleExpression": "rate(2 days)",
                        "StartDate": "2099-02-01T00:00:00Z",
                        "EndDate": "2099-02-02T00:00:00Z",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {"Arn": "TARGET_ARN", "RoleArn": "ROLE"}
                      },
                      "End": true
                    }
                  }
                }
                """.replace("TARGET_ARN", quickChildArn).replace("ROLE", ROLE_ARN));

        succeedingOutputOf(stateMachineArn, "{}");

        JsonNode schedule = mapper.readTree(given().when().get("/schedules/payout-dated")
                .then().statusCode(200).extract().body().asString());
        assertEquals(4073587200L, Math.round(schedule.path("StartDate").asDouble()));
        assertEquals(4073673600L, Math.round(schedule.path("EndDate").asDouble()));
        given().when().delete("/schedules/payout-dated").then().statusCode(200);
    }

    @Test
    @Order(22)
    void malformedScheduleDateIsCatchableAsSerializationException() throws Exception {
        String stateMachineArn = createStateMachine("aws-sdk-create-schedule-bad-date", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Schedule",
                  "States": {
                    "Schedule": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:createSchedule",
                      "Arguments": {
                        "Name": "payout-bad-date",
                        "ScheduleExpression": "rate(1 day)",
                        "StartDate": "tomorrow",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {"Arn": "TARGET_ARN", "RoleArn": "ROLE"}
                      },
                      "Catch": [{
                        "ErrorEquals": ["Scheduler.SerializationException"],
                        "Next": "Recovered",
                        "Output": {"caughtError": "{% $states.errorOutput.Error %}"}
                      }],
                      "End": true
                    },
                    "Recovered": {"Type": "Pass", "End": true}
                  }
                }
                """.replace("TARGET_ARN", quickChildArn).replace("ROLE", ROLE_ARN));

        JsonNode result = mapper.readTree(succeedingOutputOf(stateMachineArn, "{}"));
        assertEquals("Scheduler.SerializationException", result.path("caughtError").asText());
    }

    private static String deleteScheduleTask(String scheduleName, String groupName) {
        String groupArgument = groupName == null ? "" : ", \"GroupName\": \"" + groupName + "\"";
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Delete",
                  "States": {
                    "Delete": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:deleteSchedule",
                      "Arguments": {"Name": "SCHEDULE_NAME"GROUP_ARGUMENT},
                      "End": true
                    }
                  }
                }
                """
                .replace("SCHEDULE_NAME", scheduleName)
                .replace("GROUP_ARGUMENT", groupArgument);
    }

    private static String catchingDeleteScheduleTask(String scheduleName, String errorName) {
        String nameArgument = scheduleName == null ? "" : "\"Name\": \"" + scheduleName + "\"";
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Delete",
                  "States": {
                    "Delete": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:deleteSchedule",
                      "Arguments": {NAME_ARGUMENT},
                      "Catch": [{
                        "ErrorEquals": ["ERROR_NAME"],
                        "Next": "Recovered",
                        "Output": {"caughtError": "{% $states.errorOutput.Error %}"}
                      }],
                      "End": true
                    },
                    "Recovered": {"Type": "Pass", "End": true}
                  }
                }
                """
                .replace("NAME_ARGUMENT", nameArgument)
                .replace("ERROR_NAME", errorName);
    }

    private static String scheduleTask(String action, String scheduleName, String expression) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Schedule",
                  "States": {
                    "Schedule": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:scheduler:ACTION",
                      "Arguments": {
                        "Name": "SCHEDULE_NAME",
                        "ScheduleExpression": "EXPRESSION",
                        "FlexibleTimeWindow": {"Mode": "OFF"},
                        "Target": {"Arn": "TARGET_ARN", "RoleArn": "ROLE"}
                      },
                      "End": true
                    }
                  }
                }
                """
                .replace("ACTION", action)
                .replace("SCHEDULE_NAME", scheduleName)
                .replace("EXPRESSION", expression)
                .replace("TARGET_ARN", quickChildArn)
                .replace("ROLE", ROLE_ARN);
    }

    /** A state machine that parks on a task token and posts it to the callback queue. */
    private static String createWaiterStateMachine(String name) {
        return createStateMachine(name, """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Wait",
                  "States": {
                    "Wait": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::sqs:sendMessage.waitForTaskToken",
                      "Arguments": {
                        "QueueUrl": "QUEUE_URL",
                        "MessageBody": {"token": "{% $states.context.Task.Token %}"}
                      },
                      "End": true
                    }
                  }
                }
                """.replace("QUEUE_URL", callbackQueueUrl));
    }

    private String receiveTaskToken() throws Exception {
        for (var i = 0; i < 50; i++) {
            var resp = given()
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .contentType(SQS_CONTENT_TYPE)
                    .body("{\"QueueUrl\":\"%s\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":1}"
                            .formatted(callbackQueueUrl))
                    .when().post("/");
            resp.then().statusCode(200);
            var messages = mapper.readTree(resp.body().asString()).path("Messages");
            if (messages.size() == 1) {
                var message = messages.get(0);
                deleteMessage(message.path("ReceiptHandle").asText());
                var token = mapper.readTree(message.path("Body").asText()).path("token").asText();
                assertFalse(token.isBlank(), "the waiting task published a blank token");
                return token;
            }
            Thread.sleep(100);
        }
        fail("The waiting execution never published its task token");
        return null;
    }

    private static void deleteMessage(String receiptHandle) {
        given()
                .header("X-Amz-Target", "AmazonSQS.DeleteMessage")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueUrl\":\"%s\",\"ReceiptHandle\":\"%s\"}"
                        .formatted(callbackQueueUrl, receiptHandle))
                .when().post("/")
                .then().statusCode(200);
    }

    private static String tokenInput(String taskToken) throws Exception {
        return mapper.createObjectNode().put("token", taskToken).toString();
    }

    private String succeedingOutputOf(String smArn, String input) {
        var describe = waitForTerminalState(startExecution(smArn, input));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        return describe.jsonPath().getString("output");
    }

    private static String createQueue(String name) {
        var resp = given()
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueName\":\"%s\"}".formatted(name))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("QueueUrl");
    }

    private static String createStateMachine(String name, String definition) {
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s", "roleArn": "%s", "definition": %s}
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

    private static Response describeExecution(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when().post("/");
    }

    private static Response getExecutionHistory(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when().post("/");
    }

    /** The token a Task registered for itself, recovered from its own TaskScheduled event. */
    private static String scheduledTaskToken(String execArn) throws Exception {
        var events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        for (var event : events) {
            if ("TaskScheduled".equals(event.path("type").asText())) {
                var parameters = event.path("taskScheduledEventDetails").path("parameters").asText();
                return mapper.readTree(parameters).path("token").asText();
            }
        }
        fail("no TaskScheduled event found in history: " + events);
        return null;
    }

    private static Response waitForTerminalState(String execArn) {
        for (var i = 0; i < 150; i++) {
            var resp = describeExecution(execArn);
            if (!"RUNNING".equals(resp.jsonPath().getString("status"))) {
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
