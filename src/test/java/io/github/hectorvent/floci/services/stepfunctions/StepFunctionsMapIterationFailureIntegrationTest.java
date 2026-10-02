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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * An inline {@code Map} iteration that fails cuts the other iterations, and the history records what
 * it cut the way AWS does (measured in ap-northeast-1 on 2026-09-30): a {@code MapIterationAborted}
 * and then the {@code *StateAborted} of the state each cut iteration was in, all chained to the
 * failing iteration's last event, ahead of {@code MapIterationFailed} and {@code MapStateFailed}.
 */
@QuarkusTest
class StepFunctionsMapIterationFailureIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aFailingIterationRecordsTheIterationItCutAndTheWaitItWasIn() throws Exception {
        String smArn = createStateMachine("map-iteration-cut", """
                {
                    "StartAt": "M",
                    "States": {
                        "M": {
                            "Type": "Map",
                            "End": true,
                            "ItemsPath": "$.items",
                            "ItemProcessor": {
                                "ProcessorConfig": {"Mode": "INLINE"},
                                "StartAt": "Route",
                                "States": {
                                    "Route": {"Type": "Choice", "Default": "Long",
                                              "Choices": [{"Variable": "$.kind", "StringEquals": "fail", "Next": "Pause"}]},
                                    "Long": {"Type": "Wait", "Seconds": 15, "End": true},
                                    "Pause": {"Type": "Wait", "Seconds": 1, "Next": "Boom"},
                                    "Boom": {"Type": "Fail", "Error": "Boom", "Cause": "sibling failed"}
                                }
                            }
                        }
                    }
                }
                """);
        String execArn = startExecution(smArn, "{\"items\": [{\"kind\": \"long\"}, {\"kind\": \"fail\"}]}");

        Response failed = waitForTerminalExecution(execArn, 8_000);

        assertEquals("FAILED", failed.jsonPath().getString("status"), failed.body().asString());
        assertEquals("Boom", failed.jsonPath().getString("error"));
        JsonNode events = MAPPER.readTree(getExecutionHistory(execArn).body().asString()).path("events");
        List<String> types = new ArrayList<>();
        events.forEach(event -> types.add(event.path("type").asText()));
        int failure = types.indexOf("FailStateEntered");
        assertTrue(failure > 0, types.toString());
        assertEquals(List.of("MapIterationAborted", "WaitStateAborted", "MapIterationFailed", "MapStateFailed",
                "ExecutionFailed"), types.subList(failure + 1, types.size()), types.toString());
        long failureId = events.get(failure).path("id").asLong();
        for (int i = failure + 1; i <= failure + 4; i++) {
            assertEquals(failureId, events.get(i).path("previousEventId").asLong(), types.get(i));
        }
        JsonNode aborted = events.get(failure + 1).path("mapIterationAbortedEventDetails");
        assertEquals("M", aborted.path("name").asText());
        assertEquals(0, aborted.path("index").asInt());
        assertEquals(1, events.get(failure + 3).path("mapIterationFailedEventDetails").path("index").asInt());
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
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.GetExecutionHistory")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s"}
                        """.formatted(execArn))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp;
    }

    private static Response waitForTerminalExecution(String execArn, long limitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + limitMillis;
        while (System.currentTimeMillis() < deadline) {
            Response resp = describeExecution(execArn);
            if (!"RUNNING".equals(resp.jsonPath().getString("status"))) {
                return resp;
            }
            Thread.sleep(100);
        }
        fail("Execution " + execArn + " was still RUNNING after " + limitMillis + " ms");
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
