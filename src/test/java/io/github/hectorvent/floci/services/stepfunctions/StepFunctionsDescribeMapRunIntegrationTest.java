package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
 * Integration tests for {@code DescribeMapRun}, reached both directly through the API and through
 * the {@code arn:aws:states:::aws-sdk:sfn:describeMapRun} Task integration.
 *
 * <p>Every asserted value was measured against us-east-1 with a distributed Map run of the same
 * shape. A Map run only becomes describable once its {@code ResultWriter} has minted the Map run
 * ARN and returned it in the Map result, which is also the only way an ASL author obtains it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsDescribeMapRunIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    /** AWS reports an unbounded Map run as Integer.MAX_VALUE, not as the ASL default of 0. */
    private static final int UNBOUNDED_CONCURRENCY = 2147483647;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String threeItemMapRunArn;
    private static String threeItemExecutionArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_runsADistributedMapOverThreeItems() throws Exception {
        createBucket("describe-map-run");
        var smArn = createStateMachine("describe-map-run-three", distributedMap(
                "[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}]", "describe-map-run", null));

        threeItemExecutionArn = startExecution(smArn, "{}");
        var describe = waitForTerminalState(threeItemExecutionArn);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        threeItemMapRunArn = mapper.readTree(describe.jsonPath().getString("output"))
                .path("MapRunArn").asText();
        assertTrue(threeItemMapRunArn.contains(":mapRun:describe-map-run-three/"),
                "unexpected MapRunArn: " + threeItemMapRunArn);
    }

    // ──────────────────────────── The API action ────────────────────────────

    @Test
    @Order(1)
    void describeMapRunReportsTheRunItsParentExecutionAndItsWindow() {
        var response = describeMapRun(threeItemMapRunArn);
        response.then().statusCode(200);

        assertEquals(threeItemMapRunArn, response.jsonPath().getString("mapRunArn"));
        assertEquals(threeItemExecutionArn, response.jsonPath().getString("executionArn"));
        assertEquals("SUCCEEDED", response.jsonPath().getString("status"));

        // The wire response carries epoch seconds, as DescribeExecution does. The same instant in
        // epoch milliseconds is three orders of magnitude larger, which the upper bound rules out.
        double startDate = response.jsonPath().getDouble("startDate");
        double stopDate = response.jsonPath().getDouble("stopDate");
        assertTrue(startDate > 1.0e9 && startDate < 1.0e11,
                "startDate is not an epoch second: " + startDate);
        assertTrue(stopDate >= startDate && stopDate < 1.0e11,
                "stopDate is not an epoch second at or after startDate: " + stopDate);
    }

    @Test
    @Order(2)
    void describeMapRunReportsAnUnboundedMapAsIntegerMaxValue() {
        var response = describeMapRun(threeItemMapRunArn);
        assertEquals(UNBOUNDED_CONCURRENCY, response.jsonPath().getInt("maxConcurrency"));
    }

    @Test
    @Order(3)
    void describeMapRunReportsZeroToleratedFailuresAndNoRedrive() {
        var response = describeMapRun(threeItemMapRunArn);
        assertEquals(0.0, response.jsonPath().getDouble("toleratedFailurePercentage"));
        assertEquals(0, response.jsonPath().getInt("toleratedFailureCount"));
        assertEquals(0, response.jsonPath().getInt("redriveCount"));
        assertFalse(response.jsonPath().getMap("$").containsKey("redriveDate"),
                "redriveDate is absent until a run is redriven");
    }

    @Test
    @Order(4)
    void describeMapRunCountsEveryItemAsSucceededAndWritten() {
        var response = describeMapRun(threeItemMapRunArn);
        assertCounters(response, "itemCounts", 3);
    }

    @Test
    @Order(5)
    void describeMapRunReportsOneChildExecutionPerItem() {
        var response = describeMapRun(threeItemMapRunArn);
        assertCounters(response, "executionCounts", 3);
    }

    @Test
    @Order(6)
    void describeMapRunCountsTheItemsOfThatRunRatherThanAFixedNumber() throws Exception {
        createBucket("describe-map-run-five");
        var smArn = createStateMachine("describe-map-run-five", distributedMap(
                "[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}, {\"n\": 4}, {\"n\": 5}]",
                "describe-map-run-five", 2));

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        var mapRunArn = mapper.readTree(describe.jsonPath().getString("output"))
                .path("MapRunArn").asText();

        var response = describeMapRun(mapRunArn);
        response.then().statusCode(200);
        assertCounters(response, "itemCounts", 5);
        assertCounters(response, "executionCounts", 5);
        assertEquals(2, response.jsonPath().getInt("maxConcurrency"),
                "an explicit MaxConcurrency is reported as declared");
    }

    @Test
    @Order(7)
    void describeMapRunOnAnUnknownArnReturnsResourceNotFound() {
        var unknown = "arn:aws:states:us-east-1:000000000000:mapRun:describe-map-run-three/"
                + "00000000-0000-0000-0000-000000000000:11111111-1111-1111-1111-111111111111";
        var response = describeMapRun(unknown);

        response.then().statusCode(400);
        assertEquals("ResourceNotFound", response.jsonPath().getString("__type"));
        assertEquals("Resource not found: '" + unknown + "'",
                response.jsonPath().getString("message"));
    }

    // ──────────────────────────── The Task integration ────────────────────────────

    @Test
    @Order(8)
    void describeMapRunTaskReturnsThePascalCaseResponseWithIsoDates() throws Exception {
        var smArn = createStateMachine("describe-map-run-task", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Describe",
                  "States": {
                    "Describe": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:describeMapRun",
                      "Arguments": {"MapRunArn": "MAP_RUN_ARN"},
                      "End": true
                    }
                  }
                }
                """.replace("MAP_RUN_ARN", threeItemMapRunArn));

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        var result = mapper.readTree(describe.jsonPath().getString("output"));

        assertEquals(threeItemMapRunArn, result.path("MapRunArn").asText());
        assertEquals(threeItemExecutionArn, result.path("ExecutionArn").asText());
        assertEquals("SUCCEEDED", result.path("Status").asText());
        assertEquals(UNBOUNDED_CONCURRENCY, result.path("MaxConcurrency").asInt());
        assertEquals(0, result.path("ToleratedFailureCount").asInt());
        assertEquals(0.0, result.path("ToleratedFailurePercentage").asDouble());
        assertEquals(0, result.path("RedriveCount").asInt());
        assertFalse(result.has("RedriveDate"), "RedriveDate is absent until a run is redriven");

        // The aws-sdk: family renders a timestamp as the SDK's ISO-8601, not as epoch seconds.
        assertTrue(result.path("StartDate").asText().matches(ISO_INSTANT),
                "StartDate is not the SDK's ISO-8601 rendering: " + result.path("StartDate"));
        assertTrue(result.path("StopDate").asText().matches(ISO_INSTANT),
                "StopDate is not the SDK's ISO-8601 rendering: " + result.path("StopDate"));

        assertTaskCounters(result.path("ItemCounts"), 3);
        assertTaskCounters(result.path("ExecutionCounts"), 3);
    }

    @Test
    @Order(9)
    void describeMapRunTaskOnAnUnknownArnFailsWithTheSdkExceptionName() {
        var unknown = "arn:aws:states:us-east-1:000000000000:mapRun:describe-map-run-three/"
                + "00000000-0000-0000-0000-000000000000:22222222-2222-2222-2222-222222222222";
        var smArn = createStateMachine("describe-map-run-task-unknown", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Describe",
                  "States": {
                    "Describe": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sfn:describeMapRun",
                      "Arguments": {"MapRunArn": "MAP_RUN_ARN"},
                      "End": true
                    }
                  }
                }
                """.replace("MAP_RUN_ARN", unknown));

        var describe = waitForTerminalState(startExecution(smArn, "{}"));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("Sfn.ResourceNotFoundException", describe.jsonPath().getString("error"));
        assertEquals("Resource not found: '" + unknown + "'",
                describe.jsonPath().getString("cause"));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /**
     * The ten counters of a Map run all of whose items succeeded, as measured on us-east-1: every
     * item is counted once under {@code succeeded}, {@code total} and {@code resultsWritten}.
     */
    private static void assertCounters(Response response, String set, int items) {
        assertEquals(0, response.jsonPath().getInt(set + ".pending"));
        assertEquals(0, response.jsonPath().getInt(set + ".running"));
        assertEquals(items, response.jsonPath().getInt(set + ".succeeded"));
        assertEquals(0, response.jsonPath().getInt(set + ".failed"));
        assertEquals(0, response.jsonPath().getInt(set + ".timedOut"));
        assertEquals(0, response.jsonPath().getInt(set + ".aborted"));
        assertEquals(items, response.jsonPath().getInt(set + ".total"));
        assertEquals(items, response.jsonPath().getInt(set + ".resultsWritten"));
        assertEquals(0, response.jsonPath().getInt(set + ".failuresNotRedrivable"));
        assertEquals(0, response.jsonPath().getInt(set + ".pendingRedrive"));
    }

    private static void assertTaskCounters(JsonNode counts, int items) {
        assertEquals(0, counts.path("Pending").asInt());
        assertEquals(0, counts.path("Running").asInt());
        assertEquals(items, counts.path("Succeeded").asInt());
        assertEquals(0, counts.path("Failed").asInt());
        assertEquals(0, counts.path("TimedOut").asInt());
        assertEquals(0, counts.path("Aborted").asInt());
        assertEquals(items, counts.path("Total").asInt());
        assertEquals(items, counts.path("ResultsWritten").asInt());
        assertEquals(0, counts.path("FailuresNotRedrivable").asInt());
        assertEquals(0, counts.path("PendingRedrive").asInt());
        assertEquals(10, counts.size(), "a counter set carries exactly ten fields");
    }

    private static String distributedMap(String items, String bucket, Integer maxConcurrency) {
        return """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Fan",
                  "States": {
                    "Fan": {
                      "Type": "Map",
                      "End": true,
                      "Items": ITEMS,
                      CONCURRENCY
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "DISTRIBUTED", "ExecutionType": "STANDARD"},
                        "StartAt": "Keep",
                        "States": {"Keep": {"Type": "Pass", "End": true}}
                      },
                      "ResultWriter": {
                        "Resource": "arn:aws:states:::s3:putObject",
                        "Arguments": {"Bucket": "BUCKET", "Prefix": "out"}
                      }
                    }
                  }
                }
                """
                .replace("ITEMS", items)
                .replace("CONCURRENCY",
                        maxConcurrency == null ? "" : "\"MaxConcurrency\": " + maxConcurrency + ",")
                .replace("BUCKET", bucket);
    }

    private static Response describeMapRun(String mapRunArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeMapRun")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"mapRunArn": "%s"}
                        """.formatted(mapRunArn))
                .when().post("/");
    }

    private static void createBucket(String bucket) {
        given().when().put("/" + bucket).then().statusCode(200);
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
