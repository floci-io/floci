package io.github.hectorvent.floci.services.lambda.durable;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testing.MutableClock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real Python function that speaks the checkpoint protocol through the boto3 Lambda client
 * the image ships, so the whole loop is covered: Floci invokes the container, the container
 * checkpoints a step and a wait back into Floci, the sweeper fires the wait and re-invokes.
 */
@QuarkusTest
@TestProfile(LambdaDurableDockerProfile.class)
class LambdaDurableFunctionsDockerIntegrationTest {

    private static final String LAMBDA = "/2015-03-31";
    private static final String DURABLE = "/2025-12-01";
    private static final String FUNCTION = "durable-docker-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    @Inject
    DockerClient dockerClient;

    @Inject
    MutableClock clock;

    private ScheduledExecutorService clockTicker;

    /**
     * The test clock only moves when it is read, so a wait deadline would never come. Ticking it
     * once a second lets the sweeper fire waits at the pace the container experiences.
     */
    @BeforeEach
    void requireDockerAndFunction() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Lambda durable function integration tests");
        clockTicker = Executors.newSingleThreadScheduledExecutor();
        clockTicker.scheduleAtFixedRate(() -> clock.advance(Duration.ofSeconds(1)), 1, 1, TimeUnit.SECONDS);
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.14",
                    "Role": "%s",
                    "Handler": "lambda_function.handler",
                    "Timeout": 30,
                    "Publish": true,
                    "DurableConfig": {"ExecutionTimeout": 120, "RetentionPeriodInDays": 1},
                    "Code": {"ZipFile": "%s"}
                }
                """.formatted(FUNCTION, ROLE, handlerZipBase64()))
        .when()
            .post(LAMBDA + "/functions")
        .then()
            .statusCode(anyOf(is(201), is(409)));
    }

    @AfterEach
    void stopClock() {
        if (clockTicker != null) {
            clockTicker.shutdownNow();
        }
    }

    @Test
    void aDurableExecutionCheckpointsAStepWaitsAndResumes() {
        Response response = given()
                .header("X-Amz-Durable-Execution-Name", "docker-sync")
                .body("{\"wait\": 2}")
                .post(LAMBDA + "/functions/" + FUNCTION + ":1/invocations");

        assertEquals(200, response.statusCode(), response.asString());
        String arn = response.getHeader("X-Amz-Durable-Execution-Arn");
        assertTrue(arn.contains(":function:" + FUNCTION + ":1/durable-execution/docker-sync/"), arn);
        assertEquals(true, response.jsonPath().getBoolean("done"));
        assertEquals(true, response.jsonPath().getBoolean("step.validated"));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn)
        .then()
            .statusCode(200)
            .body("Status", equalTo("SUCCEEDED"))
            .body("Version", equalTo("1"));

        given()
            .urlEncodingEnabled(false)
        .when()
            .get(DURABLE + "/durable-executions/" + arn + "/history")
        .then()
            .statusCode(200)
            .body("Events.EventType", equalTo(List.of("ExecutionStarted", "StepStarted", "StepSucceeded",
                    "WaitStarted", "InvocationCompleted", "WaitSucceeded", "InvocationCompleted", "ExecutionSucceeded")));
    }

    @Test
    void aFailedExecutionIsReportedAsAFunctionError() {
        Response response = given()
                .header("X-Amz-Durable-Execution-Name", "docker-fail")
                .body("{\"fail\": true}")
                .post(LAMBDA + "/functions/" + FUNCTION + ":1/invocations");

        assertEquals(200, response.statusCode(), response.asString());
        assertEquals("Unhandled", response.getHeader("X-Amz-Function-Error"));
        assertEquals("asked to fail", response.jsonPath().getString("errorMessage"));
        assertEquals("TestFailure", response.jsonPath().getString("errorType"));

        given()
        .when()
            .get(DURABLE + "/functions/" + FUNCTION + "/durable-executions?Qualifier=1&DurableExecutionName=docker-fail")
        .then()
            .statusCode(200)
            .body("DurableExecutions", hasSize(1))
            .body("DurableExecutions[0].Status", equalTo("FAILED"));
    }

    /** Speaks the protocol with boto3 directly; the public Lambda images do not bundle the Durable Execution SDK. */
    static String handlerSource() {
        return """
            import json
            import boto3

            client = boto3.client("lambda")


            def handler(event, context):
                operations = {op["Id"]: op for op in event["InitialExecutionState"]["Operations"]}
                root = next(op for op in operations.values() if op["Type"] == "EXECUTION")
                request = json.loads(root["ExecutionDetails"].get("InputPayload") or "{}")
                if request.get("fail"):
                    return {"Status": "FAILED",
                            "Error": {"ErrorMessage": "asked to fail", "ErrorType": "TestFailure"}}
                if "step" not in operations:
                    client.checkpoint_durable_execution(
                        DurableExecutionArn=event["DurableExecutionArn"],
                        CheckpointToken=event["CheckpointToken"],
                        Updates=[
                            {"Id": "step", "Name": "validate", "Type": "STEP", "SubType": "Step", "Action": "START"},
                            {"Id": "step", "Name": "validate", "Type": "STEP", "SubType": "Step", "Action": "SUCCEED",
                             "Payload": json.dumps({"validated": True})},
                            {"Id": "wait", "Type": "WAIT", "SubType": "Wait", "Action": "START",
                             "WaitOptions": {"WaitSeconds": request.get("wait", 2)}},
                        ])
                    return {"Status": "PENDING"}
                if operations["wait"]["Status"] != "SUCCEEDED":
                    return {"Status": "PENDING"}
                step = json.loads(operations["step"]["StepDetails"]["Result"])
                return {"Status": "SUCCEEDED", "Result": json.dumps({"done": True, "step": step})}
            """;
    }

    private static String handlerZipBase64() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("lambda_function.py"));
            zip.write(handlerSource().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker is unreachable, so requireDockerAndFunction() skips the test rather than failing it.
            return false;
        }
    }
}
