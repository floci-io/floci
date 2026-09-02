package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The two {@code TimeoutSeconds} fields of ASL, each enforced on its own clock: the state
 * machine's total budget and a {@code Task}'s wait for its task token, the second one bounded a
 * second time by {@code HeartbeatSeconds}.
 *
 * <p>The bounds here are one to ten seconds where the report that measured them against us-east-1
 * used three and five; the shape of the terminal execution is what the assertions pin, not the
 * duration.
 */
@QuarkusTest
class StepFunctionsTimeoutSecondsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stateMachineTimeoutSecondsCutsAWaitLongerThanTheRemainingBudget() throws Exception {
        String smArn = createStateMachine("top-level-timeout-wait", """
                {
                    "StartAt": "Linger",
                    "TimeoutSeconds": 1,
                    "States": {
                        "Linger": {"Type": "Wait", "Seconds": 3, "End": true}
                    }
                }
                """);

        Response timedOut = waitForTerminalExecution(startExecution(smArn, "{}"));

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"));
        JsonNode body = mapper.readTree(timedOut.body().asString());
        assertTrue(body.has("stopDate"), "stopDate must be set on a timed out execution");
        assertFalse(body.has("error"), "TIMED_OUT carries no error key: " + body);
        assertFalse(body.has("cause"), "TIMED_OUT carries no cause key: " + body);
    }

    @Test
    void timedOutExecutionEmitsExecutionTimedOutAndNoStateExitedForTheCutState() throws Exception {
        String smArn = createStateMachine("top-level-timeout-history", """
                {
                    "StartAt": "Linger",
                    "TimeoutSeconds": 1,
                    "States": {
                        "Linger": {"Type": "Wait", "Seconds": 3, "End": true}
                    }
                }
                """);

        String execArn = startExecution(smArn, "{}");
        waitForTerminalExecution(execArn);
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");

        assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
        JsonNode timedOut = events.get(events.size() - 1);
        assertEquals(0, timedOut.path("previousEventId").asLong());
        assertEquals("States.Timeout",
                timedOut.path("executionTimedOutEventDetails").path("error").asText());
        assertFalse(timedOut.path("executionTimedOutEventDetails").has("cause"),
                "ExecutionTimedOut carries only the error: " + timedOut);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForAParallelBranch() throws Exception {
        // A branch is never cut mid-state, so its Wait sleeps out in full on its own thread. What
        // the budget cuts is the join: the execution ends while the branch is still running, and
        // the Parallel state gets no Exited event and no state after it is entered.
        String smArn = createStateMachine("top-level-timeout-loop", """
                {
                    "StartAt": "Fork",
                    "TimeoutSeconds": 1,
                    "States": {
                        "Fork": {
                            "Type": "Parallel",
                            "Branches": [{
                                "StartAt": "Linger",
                                "States": {"Linger": {"Type": "Wait", "Seconds": 3, "End": true}}
                            }],
                            "Next": "Unreached"
                        },
                        "Unreached": {"Type": "Pass", "End": true}
                    }
                }
                """);

        String execArn = startExecution(smArn, "{}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"));
        assertTrue(elapsedMillis < 3_000,
                "the branch's 3-second Wait outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "ParallelStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForATaskToken() throws Exception {
        // Measured against us-east-1: a state machine whose budget is shorter than the Task's own
        // TimeoutSeconds ends TIMED_OUT the instant the budget runs out, and writes nothing about
        // the task it cut. No ActivityTimedOut, no TaskFailed, no ExecutionFailed.
        String activityArn = createActivity("top-level-timeout-token");
        String smArn = createStateMachine("top-level-timeout-token", """
                {
                    "StartAt": "Await",
                    "TimeoutSeconds": 1,
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": 60,
                            "End": true
                        }
                    }
                }
                """.formatted(activityArn));

        String execArn = startExecution(smArn, "{}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"), timedOut.body().asString());
        assertTrue(elapsedMillis < 5_000,
                "the task's 60-second wait outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "ActivityScheduled",
                "ActivityStarted", "ExecutionTimedOut"), eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForANestedExecution() throws Exception {
        // The child sleeps far past the parent's budget. The parent ends TIMED_OUT where its budget
        // runs out; the child keeps running, since nothing in ASL propagates the parent's timeout.
        String childArn = createStateMachine("nested-child", """
                {
                    "StartAt": "Linger",
                    "States": {"Linger": {"Type": "Wait", "Seconds": 10, "End": true}}
                }
                """);
        String parentArn = createStateMachine("nested-parent", """
                {
                    "StartAt": "RunChild",
                    "TimeoutSeconds": 1,
                    "States": {
                        "RunChild": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync",
                            "Parameters": {"StateMachineArn": "%s", "Input": {}},
                            "End": true
                        }
                    }
                }
                """.formatted(childArn));

        String execArn = startExecution(parentArn, "{}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"), timedOut.body().asString());
        assertTrue(elapsedMillis < 5_000,
                "the child's 10-second Wait outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals("ExecutionTimedOut", events.get(events.size() - 1).path("type").asText(),
                "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForMapIterations() throws Exception {
        // Two iterations run concurrently and each sleeps past the budget, so what the budget cuts
        // is the Map state's wait for them rather than any single iteration.
        String smArn = createStateMachine("top-level-timeout-map", """
                {
                    "StartAt": "OverItems",
                    "TimeoutSeconds": 1,
                    "States": {
                        "OverItems": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "ItemProcessor": {
                                "StartAt": "Linger",
                                "States": {"Linger": {"Type": "Wait", "Seconds": 10, "End": true}}
                            },
                            "End": true
                        }
                    }
                }
                """);

        String execArn = startExecution(smArn, "{\"items\": [1, 2]}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"), timedOut.body().asString());
        assertTrue(elapsedMillis < 5_000,
                "the iterations' 10-second Wait outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForASingleMapIteration() throws Exception {
        // A single item runs the Map state's serial path rather than the concurrent one; the
        // budget must cut it the same way it cuts a concurrent iteration above.
        String smArn = createStateMachine("top-level-timeout-map-single-item", """
                {
                    "StartAt": "OverItems",
                    "TimeoutSeconds": 1,
                    "States": {
                        "OverItems": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "ItemProcessor": {
                                "StartAt": "Linger",
                                "States": {"Linger": {"Type": "Wait", "Seconds": 10, "End": true}}
                            },
                            "End": true
                        }
                    }
                }
                """);

        String execArn = startExecution(smArn, "{\"items\": [1]}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"), timedOut.body().asString());
        assertTrue(elapsedMillis < 5_000,
                "the single iteration's 10-second Wait outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
    }

    @Test
    void stateMachineTimeoutSecondsCutsARetryBackoffLongerThanTheRemainingBudget() throws Exception {
        // The Lambda does not exist, so every attempt fails immediately and the only thing this
        // state spends time on is the backoff between attempts. AWS ends the execution when the
        // budget runs out, mid-backoff; before this the backoff ran in full and the state failed
        // on its own error afterwards, so the execution ended FAILED past its budget.
        String smArn = createStateMachine("top-level-timeout-retry", """
                {
                    "StartAt": "Flaky",
                    "TimeoutSeconds": 1,
                    "States": {
                        "Flaky": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::lambda:invoke",
                            "Parameters": {"FunctionName": "absent-function"},
                            "Retry": [{"ErrorEquals": ["States.ALL"], "IntervalSeconds": 10, "MaxAttempts": 1}],
                            "End": true
                        }
                    }
                }
                """);

        String execArn = startExecution(smArn, "{}");
        long startedAt = System.currentTimeMillis();
        Response timedOut = waitForTerminalExecution(execArn);
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"), timedOut.body().asString());
        assertTrue(elapsedMillis < 5_000,
                "the 10-second backoff outlasted the 1-second budget: " + elapsedMillis + " ms");
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals("ExecutionTimedOut", events.get(events.size() - 1).path("type").asText(),
                "history was " + events);
    }

    @Test
    void taskTimeoutSecondsBoundsTheWholeWaitForTheTaskToken() throws Exception {
        String activityArn = createActivity("timeout-seconds-total");
        String smArn = createStateMachine("task-timeout-seconds", activityTask(activityArn, 1, 0));

        Response failed = waitForTerminalExecution(startExecution(smArn, "{}"));

        assertEquals("FAILED", failed.jsonPath().getString("status"));
        assertEquals("States.Timeout", failed.jsonPath().getString("error"));
        JsonNode body = mapper.readTree(failed.body().asString());
        assertFalse(body.has("cause"),
                "a task that ran out of time carries no cause key: " + body);
    }

    @Test
    void taskHeartbeatSecondsBoundsTheGapBetweenHeartbeats() throws Exception {
        String activityArn = createActivity("heartbeat-seconds-gap");
        String smArn = createStateMachine("task-heartbeat-gap", activityTask(activityArn, 10, 1));
        String execArn = startExecution(smArn, "{}");

        // A worker picks the task up and then goes silent, so the gap HeartbeatSeconds allows runs
        // out long before the state's TimeoutSeconds does.
        getActivityTask(activityArn);
        Response failed = waitForTerminalExecution(execArn);

        assertEquals("FAILED", failed.jsonPath().getString("status"));
        assertEquals("States.Timeout", failed.jsonPath().getString("error"));
        JsonNode body = mapper.readTree(failed.body().asString());
        assertFalse(body.has("cause"),
                "a task that missed its heartbeats carries no cause key: " + body);

        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "TaskStateEntered", "ActivityScheduled",
                "ActivityStarted", "ActivityTimedOut", "ExecutionFailed"),
                eventTypes(events), "history was " + events);
        JsonNode timedOut = events.get(events.size() - 2);
        assertEquals("States.Timeout",
                timedOut.path("activityTimedOutEventDetails").path("error").asText());
        assertFalse(timedOut.path("activityTimedOutEventDetails").has("cause"),
                "ActivityTimedOut carries only the error: " + timedOut);
    }

    @Test
    void aCatchMatchesAHeartbeatExpiryUnderEitherErrorName() throws Exception {
        for (String errorEquals : List.of("States.HeartbeatTimeout", "States.Timeout")) {
            String activityArn = createActivity("heartbeat-seconds-catch");
            String smArn = createStateMachine("task-heartbeat-catch",
                    caughtActivityTask(activityArn, errorEquals));
            String execArn = startExecution(smArn, "{}");

            getActivityTask(activityArn);
            Response caught = waitForTerminalExecution(execArn);

            assertEquals("SUCCEEDED", caught.jsonPath().getString("status"),
                    "Catch on " + errorEquals + " left the execution " + caught.body().asString());
            assertEquals("States.Timeout",
                    mapper.readTree(caught.jsonPath().getString("output")).path("Error").asText(),
                    "Catch on " + errorEquals + " reported the wrong error");
        }
    }

    @Test
    void heartbeatsKeepATaskAliveUntilItsTimeoutSecondsRunOut() throws Exception {
        String activityArn = createActivity("heartbeat-seconds-reset");
        String smArn = createStateMachine("task-heartbeat-reset", activityTask(activityArn, 12, 3));
        String execArn = startExecution(smArn, "{}");

        String taskToken = getActivityTask(activityArn);
        // Five seconds of work reported every 500 ms: nearly twice the heartbeat gap, so the task
        // survives only if each heartbeat pushes that gap's deadline forward.
        for (int beat = 0; beat < 10; beat++) {
            Thread.sleep(500);
            sendTaskHeartbeat(taskToken);
        }
        sendTaskSuccess(taskToken, "{\"answered\":true}");

        Response succeeded = waitForTerminalExecution(execArn);
        assertEquals("SUCCEEDED", succeeded.jsonPath().getString("status"),
                "execution was " + succeeded.body().asString());
        assertTrue(mapper.readTree(succeeded.jsonPath().getString("output")).path("answered").asBoolean());
    }

    private static String activityTask(String activityArn, int timeoutSeconds, int heartbeatSeconds) {
        String heartbeat = heartbeatSeconds > 0 ? ",\"HeartbeatSeconds\": " + heartbeatSeconds : "";
        return """
                {
                    "StartAt": "Await",
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": %d%s,
                            "End": true
                        }
                    }
                }
                """.formatted(activityArn, timeoutSeconds, heartbeat);
    }

    private static String caughtActivityTask(String activityArn, String errorEquals) {
        return """
                {
                    "StartAt": "Await",
                    "States": {
                        "Await": {
                            "Type": "Task",
                            "Resource": "%s",
                            "TimeoutSeconds": 10,
                            "HeartbeatSeconds": 1,
                            "Catch": [{"ErrorEquals": ["%s"], "Next": "Recovered"}],
                            "End": true
                        },
                        "Recovered": {"Type": "Pass", "End": true}
                    }
                }
                """.formatted(activityArn, errorEquals);
    }

    private static List<String> eventTypes(JsonNode events) {
        List<String> types = new ArrayList<>();
        events.forEach(event -> types.add(event.path("type").asText()));
        return types;
    }

    private static String createActivity(String name) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateActivity")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s-%d"}
                        """.formatted(name, System.currentTimeMillis()))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("activityArn");
    }

    private static String getActivityTask(String activityArn) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.GetActivityTask")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"activityArn": "%s", "workerName": "timeout-seconds-worker"}
                        """.formatted(activityArn))
                .when()
                .post("/");
        resp.then().statusCode(200);
        String taskToken = resp.jsonPath().getString("taskToken");
        assertFalse(taskToken == null || taskToken.isBlank(), "no activity task was queued");
        return taskToken;
    }

    private static void sendTaskHeartbeat(String taskToken) {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.SendTaskHeartbeat")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"taskToken": %s}
                        """.formatted(quote(taskToken)))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private static void sendTaskSuccess(String taskToken, String output) {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.SendTaskSuccess")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"taskToken": %s, "output": %s}
                        """.formatted(quote(taskToken), quote(output)))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private static String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s-%d", "definition": %s, "roleArn": "%s"}
                        """.formatted(name, System.currentTimeMillis(), quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private static String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": %s}
                        """.formatted(smArn, quote(input)))
                .when()
                .post("/");
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
                .when()
                .post("/");
    }

    private static Response getExecutionHistory(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
    }

    /**
     * Polls for 20 seconds: twice the longest bound any definition here declares, and far short of
     * the 300-second default a task that ignores TimeoutSeconds falls back on.
     */
    private static Response waitForTerminalExecution(String execArn) throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if (!"RUNNING".equals(status)) {
                return resp;
            }
            Thread.sleep(100);
        }
        fail("Execution " + execArn + " was still RUNNING after 20 seconds");
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
