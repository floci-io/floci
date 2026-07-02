package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Step Functions ecs:runTask optimized service integration
 * ({@code arn:aws:states:::ecs:runTask} and {@code .sync}).
 *
 * <p>These assert the wiring that is deterministic regardless of the host: request
 * routing for both modes, PascalCase parameter parsing, PascalCase result shaping
 * ({@code Tasks}/{@code Failures}), parameter validation, and error propagation. The
 * happy-path container-execution {@code .sync} poll-to-STOPPED is exercised empirically
 * against a live emulator with Docker available (it is not asserted here, to keep the
 * unit suite independent of a container runtime / image registry).
 */
@QuarkusTest
@TestProfile(StepFunctionsEcsRunTaskIntegrationTest.EcsMockProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsEcsRunTaskIntegrationTest {

    /**
     * Runs ECS in mock mode so RunTask transitions tasks to RUNNING without a container
     * runtime. These tests assert only the Step Functions↔ECS wiring (routing, casing,
     * validation, error propagation), which is independent of real container execution.
     */
    public static final class EcsMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "true");
        }
    }

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ECS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ECS_TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String CLUSTER = "sfn-ecs-cluster";
    private static final String TASK_FAMILY = "sfn-ecs-task";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_clusterAndTaskDefinition() {
        ecs("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}").then().statusCode(200);
        ecs("RegisterTaskDefinition", """
                {
                    "family": "%s",
                    "containerDefinitions": [
                        { "name": "runner", "image": "floci-test/runner:latest", "memory": 128 }
                    ]
                }
                """.formatted(TASK_FAMILY)).then().statusCode(200);
    }

    @Test
    @Order(1)
    void runTask_returnsPascalCaseTasksEnvelope() throws Exception {
        String output = executeSfn("ecs-run", "arn:aws:states:::ecs:runTask", """
                {
                    "Cluster": "%s",
                    "TaskDefinition": "%s",
                    "LaunchType": "FARGATE"
                }
                """.formatted(CLUSTER, TASK_FAMILY));

        JsonNode result = mapper.readTree(output);
        // Optimized integration returns the RunTask response with PascalCase keys.
        assertTrue(result.has("Tasks"), "expected PascalCase Tasks: " + output);
        assertTrue(result.has("Failures"), "expected PascalCase Failures: " + output);
        assertEquals(1, result.path("Tasks").size());
        JsonNode task = result.path("Tasks").get(0);
        assertFalse(task.path("TaskArn").asText().isBlank(), "TaskArn should be populated");
        assertFalse(task.path("LastStatus").asText().isBlank(), "LastStatus should be populated");
        assertTrue(task.path("TaskDefinitionArn").asText().contains(TASK_FAMILY));
        // Casing bridge must not leak lowerCamelCase keys into the state output.
        assertTrue(task.path("taskArn").isMissingNode(), "lowerCamelCase keys must not leak");
    }

    @Test
    @Order(2)
    void runTask_missingTaskDefinition_failsState() throws Exception {
        String definition = buildDefinition("arn:aws:states:::ecs:runTask", """
                { "Cluster": "%s" }
                """.formatted(CLUSTER));
        String execArn = startExecution(createStateMachine("ecs-no-taskdef", definition), "{}");

        Response failed = waitForFailedExecution(execArn);
        assertEquals("States.TaskFailed", failed.jsonPath().getString("error"));
    }

    @Test
    @Order(3)
    void runTaskSync_unknownTaskDefinition_propagatesEcsError() throws Exception {
        String definition = buildDefinition("arn:aws:states:::ecs:runTask.sync", """
                {
                    "Cluster": "%s",
                    "TaskDefinition": "does-not-exist-family"
                }
                """.formatted(CLUSTER));
        String execArn = startExecution(createStateMachine("ecs-sync-unknown-taskdef", definition), "{}");

        // .sync dispatch reaches EcsService.runTask, which rejects the unknown task
        // definition; the error is surfaced as a State failure rather than hanging.
        Response failed = waitForFailedExecution(execArn);
        String error = failed.jsonPath().getString("error");
        assertNotNull(error);
        assertTrue(error.startsWith("ECS."), "expected an ECS.* error, got: " + error);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static Response ecs(String action, String body) {
        return given()
                .header("X-Amz-Target", ECS_TARGET_PREFIX + action)
                .contentType(ECS_CONTENT_TYPE)
                .body(body)
                .when()
                .post("/");
    }

    private String executeSfn(String nameSuffix, String resource, String parameters) throws Exception {
        String definition = buildDefinition(resource, parameters);
        String smArn = createStateMachine(nameSuffix, definition);
        String execArn = startExecution(smArn, "{}");
        return waitForExecution(execArn);
    }

    private String buildDefinition(String resource, String parameters) {
        return """
                {
                    "StartAt": "Run",
                    "States": {
                        "Run": {
                            "Type": "Task",
                            "Resource": "%s",
                            "Parameters": %s,
                            "End": true
                        }
                    }
                }
                """.formatted(resource, parameters.strip());
    }

    private String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {
                            "name": "%s",
                            "definition": %s,
                            "roleArn": "%s"
                        }
                        """.formatted(name + "-" + System.currentTimeMillis(), quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        { "stateMachineArn": "%s", "input": %s }
                        """.formatted(smArn, quote(input)))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private String waitForExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private Response waitForFailedExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if ("FAILED".equals(status)) {
                return resp;
            }
            if ("SUCCEEDED".equals(status)) {
                fail("Execution should have failed but succeeded: " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    private Response describeExecution(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
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
