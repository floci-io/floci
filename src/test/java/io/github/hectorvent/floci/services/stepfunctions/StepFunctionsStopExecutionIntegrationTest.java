package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards that {@code StopExecution} stops the execution instead of relabelling it: the status,
 * error and cause it writes are what {@code DescribeExecution} returns, and the worker thread that
 * is still inside the {@code Wait} does not overwrite them when it gets there.
 */
@QuarkusTest
class StepFunctionsStopExecutionIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    /** Long enough that the stop lands mid-flight, short enough that the re-read is cheap. */
    private static final int WAIT_SECONDS = 3;

    private static final String WAITING_MACHINE = """
            {"StartAt": "W", "States": {"W": {"Type": "Wait", "Seconds": %d, "End": true}}}
            """.formatted(WAIT_SECONDS);

    @Inject
    StepFunctionsService stepFunctionsService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * The executions survive a restart in persistent storage mode; their histories do not, because
     * the history lives in memory. An execution that reloads as RUNNING with no history behind it is
     * still stoppable, and StopExecution reports the abort it performed rather than an error. The
     * same pairing happens during a reset, between the moment the in-memory state is dropped and
     * the moment the stored executions are.
     */
    @Test
    void executionWithNoHistoryInMemoryIsStillStoppable() throws Exception {
        String executionArn = startWaitingExecution("stop-without-history");
        Thread.sleep(300);

        stepFunctionsService.clear();

        stopExecution(executionArn, "Manual", "probe");

        Response described = describeExecution(executionArn);
        assertEquals("ABORTED", described.jsonPath().getString("status"));
        assertEquals("Manual", described.jsonPath().getString("error"));
        assertEquals("probe", described.jsonPath().getString("cause"));
    }

    @Test
    void stoppedExecutionReportsTheCallersErrorAndCause() throws Exception {
        String executionArn = startWaitingExecution("stop-reports-error");
        Thread.sleep(300);

        stopExecution(executionArn, "Manual", "probe");

        Response described = describeExecution(executionArn);
        assertEquals("ABORTED", described.jsonPath().getString("status"));
        assertEquals("Manual", described.jsonPath().getString("error"));
        assertEquals("probe", described.jsonPath().getString("cause"));
    }

    @Test
    void stoppedExecutionStaysAbortedAfterTheWaitElapses() throws Exception {
        String executionArn = startWaitingExecution("stop-survives-wait");
        Thread.sleep(300);

        stopExecution(executionArn, "Manual", "probe");
        double stopDate = describeExecution(executionArn).jsonPath().getDouble("stopDate");

        Thread.sleep((WAIT_SECONDS + 2) * 1000L);

        Response described = describeExecution(executionArn);
        assertEquals("ABORTED", described.jsonPath().getString("status"));
        assertEquals("Manual", described.jsonPath().getString("error"));
        assertEquals("probe", described.jsonPath().getString("cause"));
        assertEquals(stopDate, described.jsonPath().getDouble("stopDate"));
    }

    /**
     * Measured against us-east-1: an abort with no error and no cause leaves both keys out of
     * {@code DescribeExecution} and carries an empty {@code executionAbortedEventDetails}. This is
     * the terminal write the startup sweep of abandoned executions makes as well.
     */
    @Test
    void abortWithNoReasonOmitsErrorAndCause() throws Exception {
        String executionArn = startWaitingExecution("stop-without-reason");
        Thread.sleep(300);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.StopExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(executionArn))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        Map<String, Object> described = describeExecution(executionArn).jsonPath().getMap("$");
        assertEquals("ABORTED", described.get("status"));
        // Absent, not null: a null value would leave the key in place and here it is gone.
        assertFalse(described.containsKey("error"), "DescribeExecution kept error: " + described);
        assertFalse(described.containsKey("cause"), "DescribeExecution kept cause: " + described);

        List<Map<String, Object>> events =
                getExecutionHistory(executionArn).jsonPath().getList("events");
        Map<String, Object> aborted = events.get(events.size() - 1);
        assertEquals("ExecutionAborted", aborted.get("type"));
        assertEquals(Map.of(), aborted.get("executionAbortedEventDetails"));
    }

    @Test
    void abortedExecutionEndsItsHistoryAtExecutionAborted() throws Exception {
        String executionArn = startWaitingExecution("stop-ends-history");
        Thread.sleep(300);

        stopExecution(executionArn, "Manual", "probe");

        // The worker is still inside the Wait. Let it come out and try to record the state it left.
        Thread.sleep((WAIT_SECONDS + 2) * 1000L);

        List<Map<String, Object>> events = getExecutionHistory(executionArn).jsonPath().getList("events");
        List<String> types = events.stream().map(event -> (String) event.get("type")).toList();
        List<Object> ids = events.stream().map(event -> event.get("id")).toList();

        assertAll(
                () -> assertEquals("ExecutionAborted", types.get(types.size() - 1),
                        "history continued past the terminal event: " + types),
                () -> assertEquals(ids.size(), new HashSet<>(ids).size(),
                        "history repeats an event id: " + ids + " for " + types));
    }

    private String startWaitingExecution(String namePrefix) {
        String name = namePrefix + "-" + UUID.randomUUID();
        String stateMachineArn = createStateMachine(name, WAITING_MACHINE);
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": "{}"}
                        """.formatted(stateMachineArn))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private String createStateMachine(String name, String definition) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s", "definition": %s, "roleArn": "%s"}
                        """.formatted(name, quote(definition), ROLE_ARN))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("stateMachineArn");
    }

    private void stopExecution(String executionArn, String error, String cause) {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.StopExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s", "error": "%s", "cause": "%s"}
                        """.formatted(executionArn, error, cause))
                .when()
                .post("/")
                .then()
                .statusCode(200);
    }

    private Response describeExecution(String executionArn) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(executionArn))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response;
    }

    private Response getExecutionHistory(String executionArn) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s", "includeExecutionData": true}
                        """.formatted(executionArn))
                .when()
                .post("/");
        response.then().statusCode(200);
        return response;
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
