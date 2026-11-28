package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
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
 *
 * <p>Each clock keeps one case here that runs it end to end, in real time. The budget cutting a
 * task token, a Parallel branch and a Retry backoff, and a Catch on either heartbeat error name,
 * run on a test clock in {@code AslExecutorExecutionBudgetTest}; the budget cutting a nested
 * execution does in {@code AslExecutorNestedSyncTimeoutTest}. The Map cases stay here because
 * {@code MapIterationScheduler} measures the budget on {@code System.nanoTime()} directly.
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

        String execArn = startExecution(smArn, "{}");
        Response timedOut = waitForTerminalExecution(execArn);

        assertEquals("TIMED_OUT", timedOut.jsonPath().getString("status"));
        JsonNode body = mapper.readTree(timedOut.body().asString());
        assertTrue(body.has("stopDate"), "stopDate must be set on a timed out execution");
        assertFalse(body.has("error"), "TIMED_OUT carries no error key: " + body);
        assertFalse(body.has("cause"), "TIMED_OUT carries no cause key: " + body);
        // The cut state gets no Exited event, and the terminal event carries only the error.
        JsonNode events = mapper.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        assertEquals(List.of("ExecutionStarted", "WaitStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
        JsonNode timedOutEvent = events.get(events.size() - 1);
        assertEquals(0, timedOutEvent.path("previousEventId").asLong());
        assertEquals("States.Timeout",
                timedOutEvent.path("executionTimedOutEventDetails").path("error").asText());
        assertFalse(timedOutEvent.path("executionTimedOutEventDetails").has("cause"),
                "ExecutionTimedOut carries only the error: " + timedOutEvent);
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
        // Both iterations entered their Wait and neither exited it. They run concurrently, so
        // the order their events interleave in is theirs to choose.
        List<String> types = eventTypes(events);
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted"), types.subList(0, 3),
                "history was " + events);
        assertEquals(List.of("MapIterationStarted", "MapIterationStarted", "WaitStateEntered", "WaitStateEntered"),
                types.subList(3, 7).stream().sorted().toList(), "history was " + events);
        assertEquals(List.of("ExecutionTimedOut"), types.subList(7, types.size()), "history was " + events);
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
        assertEquals(List.of("ExecutionStarted", "MapStateEntered", "MapStateStarted", "MapIterationStarted",
                        "WaitStateEntered", "ExecutionTimedOut"),
                eventTypes(events), "history was " + events);
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
