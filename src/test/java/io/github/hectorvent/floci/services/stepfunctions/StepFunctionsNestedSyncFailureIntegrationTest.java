package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A {@code states:startExecution.sync} Task whose child does not succeed fails with
 * {@code States.TaskFailed} and the child's DescribeExecution response as its cause, whatever the
 * child's own error, the way AWS reports it (measured in ap-northeast-1 on 2026-09-29).
 */
@QuarkusTest
class StepFunctionsNestedSyncFailureIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFailedChildReachesTheParentAsStatesTaskFailedWithTheChildsDescription() throws Exception {
        String childArn = createStateMachine("nested-failure-child", """
                {"StartAt": "F", "States": {"F": {"Type": "Fail", "Error": "Boom", "Cause": "custom cause"}}}
                """);
        String parentArn = createStateMachine("nested-failure-parent", """
                {
                    "StartAt": "RunChild",
                    "States": {
                        "RunChild": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync:2",
                            "Parameters": {"StateMachineArn": "%s", "Input": {"k": 1}},
                            "End": true
                        }
                    }
                }
                """.formatted(childArn));

        Response failed = waitForTerminalExecution(startExecution(parentArn, "{}"));

        assertEquals("FAILED", failed.jsonPath().getString("status"), failed.body().asString());
        assertEquals("States.TaskFailed", failed.jsonPath().getString("error"));
        JsonNode cause = mapper.readTree(failed.jsonPath().getString("cause"));
        assertEquals(List.of("Cause", "Error", "ExecutionArn", "Input", "InputDetails", "Name", "RedriveCount",
                "RedriveStatus", "StartDate", "StateMachineArn", "Status", "StopDate"), fieldNames(cause));
        assertEquals("custom cause", cause.path("Cause").asText());
        assertEquals("Boom", cause.path("Error").asText());
        assertEquals("{\"k\":1}", cause.path("Input").asText());
        assertEquals(childArn, cause.path("StateMachineArn").asText());
        assertEquals("FAILED", cause.path("Status").asText());
        assertTrue(cause.path("StartDate").isIntegralNumber(), "epoch milliseconds: " + cause);
        Response child = describeExecution(cause.path("ExecutionArn").asText());
        assertEquals("Boom", child.jsonPath().getString("error"), child.body().asString());
    }

    @Test
    void aTimedOutChildIsCaughtUnderStatesTaskFailed() throws Exception {
        String childArn = createStateMachine("nested-timeout-child", """
                {"StartAt": "W", "TimeoutSeconds": 1, "States": {"W": {"Type": "Wait", "Seconds": 5, "End": true}}}
                """);
        String parentArn = createStateMachine("nested-timeout-parent", """
                {
                    "StartAt": "RunChild",
                    "States": {
                        "RunChild": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::states:startExecution.sync",
                            "Parameters": {"StateMachineArn": "%s"},
                            "Catch": [{"ErrorEquals": ["States.TaskFailed"], "Next": "Handled"}],
                            "End": true
                        },
                        "Handled": {"Type": "Pass", "End": true}
                    }
                }
                """.formatted(childArn));

        Response done = waitForTerminalExecution(startExecution(parentArn, "{}"));

        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.body().asString());
        JsonNode output = mapper.readTree(done.jsonPath().getString("output"));
        assertEquals("States.TaskFailed", output.path("Error").asText());
        JsonNode cause = mapper.readTree(output.path("Cause").asText());
        assertEquals("TIMED_OUT", cause.path("Status").asText());
        assertFalse(cause.has("Error"), "a TIMED_OUT child has no error: " + cause);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
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

    private static Response waitForTerminalExecution(String execArn) throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            Response resp = describeExecution(execArn);
            if (!"RUNNING".equals(resp.jsonPath().getString("status"))) {
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
