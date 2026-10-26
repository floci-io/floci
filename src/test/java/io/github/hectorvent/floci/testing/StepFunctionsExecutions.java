package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** Step Functions execution calls shared by the integration tests. */
public final class StepFunctionsExecutions {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final Duration TERMINAL_STATE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepFunctionsExecutions() {}

    public static Response describeExecution(String executionArn) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(executionArn))
                .when().post("/");
        response.then().statusCode(200);
        return response;
    }

    /**
     * Polls DescribeExecution until the execution leaves RUNNING and returns that response, whatever
     * the terminal status: asserting which one is the caller's business.
     */
    public static Response waitForTerminalState(String executionArn) {
        return await("execution " + executionArn + " to leave RUNNING")
                .atMost(TERMINAL_STATE_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> describeExecution(executionArn),
                        response -> !"RUNNING".equals(response.jsonPath().getString("status")));
    }

    /**
     * Creates a state machine in {@code region} from {@code definition}, runs it on an empty input and
     * returns the terminal DescribeExecution response.
     */
    public static Response runInRegion(String region, String definition) {
        String stateMachineArn = callInRegion(region, "CreateStateMachine", MAPPER.createObjectNode()
                .put("name", "sfn-" + UUID.randomUUID())
                .put("definition", definition)
                .put("roleArn", ROLE_ARN).toString())
                .then().statusCode(200).extract().path("stateMachineArn");
        String executionArn = callInRegion(region, "StartExecution", MAPPER.createObjectNode()
                .put("stateMachineArn", stateMachineArn).put("input", "{}").toString())
                .then().statusCode(200).extract().path("executionArn");
        return waitForTerminalState(executionArn);
    }

    /** The history events of an execution that {@link #runInRegion} ran in {@code region}. */
    public static List<JsonNode> historyInRegion(String region, Response execution) throws Exception {
        String body = callInRegion(region, "GetExecutionHistory", MAPPER.createObjectNode()
                .put("executionArn", execution.jsonPath().getString("executionArn")).toString())
                .then().statusCode(200).extract().asString();
        List<JsonNode> events = new ArrayList<>();
        MAPPER.readTree(body).path("events").forEach(events::add);
        return events;
    }

    private static Response callInRegion(String region, String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/states/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "AWSStepFunctions." + action)
                .contentType(SFN_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }
}
