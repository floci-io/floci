package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.StepFunctionsExecutions;
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
 * "Step Functions Execution Status Change" events reach an EventBridge rule's SQS target for every
 * status a STANDARD execution enters. Each test routes its own state machine's events to its own
 * queue, so executions started by other test classes sharing this Quarkus app do not leak in.
 *
 * <p>Only events that should arrive are asserted here. The absences, nothing for an EXPRESS
 * execution and no second ABORTED from the worker after StopExecution, are verified without
 * waiting on an empty queue in {@code StepFunctionsServiceExecutionEventsTest}.
 */
@QuarkusTest
class StepFunctionsExecutionStatusEventsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENTS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String DETAIL_TYPE = "Step Functions Execution Status Change";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void succeededExecutionEmitsRunningThenSucceeded() throws Exception {
        String smArn = createStateMachine("status-events-pass", "STANDARD", """
                {"StartAt": "P", "States": {"P": {"Type": "Pass", "Result": {"done": true}, "End": true}}}
                """);
        String queueUrl = routeEventsTo("status-events-pass", smArn);

        String execArn = startExecution(smArn, "{\\\"job\\\":1}");
        StepFunctionsExecutions.waitForTerminalState(execArn);

        List<JsonNode> events = receiveEvents(queueUrl, 2);
        assertEquals(List.of("RUNNING", "SUCCEEDED"), statuses(events));

        JsonNode running = events.get(0);
        assertEquals("aws.states", running.path("source").asText());
        assertEquals(DETAIL_TYPE, running.path("detail-type").asText());
        assertEquals("000000000000", running.path("account").asText());
        assertEquals("us-east-1", running.path("region").asText());
        assertEquals(execArn, running.path("resources").get(0).asText());
        assertEquals(execArn, running.path("detail").path("executionArn").asText());
        assertEquals(smArn, running.path("detail").path("stateMachineArn").asText());
        assertEquals("{\"job\":1}", running.path("detail").path("input").asText());
        assertTrue(running.path("detail").path("stopDate").isNull());
        assertTrue(running.path("detail").path("output").isNull());

        JsonNode succeeded = events.get(1).path("detail");
        assertEquals("{\"done\":true}", succeeded.path("output").asText());
        assertTrue(succeeded.path("outputDetails").path("included").asBoolean());
        assertTrue(succeeded.path("stopDate").asLong() >= succeeded.path("startDate").asLong());
        // Epoch milliseconds, as AWS sends, not DescribeExecution's epoch seconds.
        assertTrue(succeeded.path("startDate").asLong() > 1_000_000_000_000L);
    }

    @Test
    void failedExecutionCarriesErrorAndCause() throws Exception {
        String smArn = createStateMachine("status-events-fail", "STANDARD", """
                {"StartAt": "F", "States": {"F": {"Type": "Fail", "Error": "Job.Broken", "Cause": "boom"}}}
                """);
        String queueUrl = routeEventsTo("status-events-fail", smArn);

        StepFunctionsExecutions.waitForTerminalState(startExecution(smArn, "{}"));

        List<JsonNode> events = receiveEvents(queueUrl, 2);
        assertEquals(List.of("RUNNING", "FAILED"), statuses(events));
        JsonNode failed = events.get(1).path("detail");
        assertEquals("Job.Broken", failed.path("error").asText());
        assertEquals("boom", failed.path("cause").asText());
    }

    @Test
    void timedOutExecutionNamesStatesTimeout() throws Exception {
        String smArn = createStateMachine("status-events-timeout", "STANDARD", """
                {"TimeoutSeconds": 1, "StartAt": "W", "States": {"W": {"Type": "Wait", "Seconds": 5, "End": true}}}
                """);
        String queueUrl = routeEventsTo("status-events-timeout", smArn);

        StepFunctionsExecutions.waitForTerminalState(startExecution(smArn, "{}"));

        List<JsonNode> events = receiveEvents(queueUrl, 2);
        assertEquals(List.of("RUNNING", "TIMED_OUT"), statuses(events));
        JsonNode timedOut = events.get(1).path("detail");
        assertEquals("States.Timeout", timedOut.path("error").asText());
        assertEquals("REDRIVABLE", timedOut.path("redriveStatus").asText());
        assertEquals("Execution is TIMED_OUT and is redrivable", timedOut.path("redriveStatusReason").asText());
    }

    @Test
    void stoppedExecutionEmitsAbortedExactlyOnce() throws Exception {
        String smArn = createStateMachine("status-events-stop", "STANDARD", """
                {"StartAt": "W", "States": {"W": {"Type": "Wait", "Seconds": 30, "End": true}}}
                """);
        String queueUrl = routeEventsTo("status-events-stop", smArn);

        String execArn = startExecution(smArn, "{}");
        given()
                .header("X-Amz-Target", "AWSStepFunctions.StopExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"executionArn": "%s", "error": "Halted", "cause": "by test"}
                        """.formatted(execArn))
                .when().post("/")
                .then().statusCode(200);

        List<JsonNode> events = receiveEvents(queueUrl, 2);
        assertEquals(List.of("RUNNING", "ABORTED"), statuses(events));
        assertEquals("Halted", events.get(1).path("detail").path("error").asText());
    }

    private static List<String> statuses(List<JsonNode> events) {
        List<String> statuses = new ArrayList<>();
        for (JsonNode event : events) {
            statuses.add(event.path("detail").path("status").asText());
        }
        return statuses;
    }

    private static String routeEventsTo(String name, String smArn) {
        String queueUrl = createQueue(name);
        String pattern = """
                {"source":["aws.states"],"detail-type":["%s"],"detail":{"stateMachineArn":["%s"]}}
                """.formatted(DETAIL_TYPE, smArn).strip();
        given()
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .contentType(EVENTS_CONTENT_TYPE)
                .body("""
                        {"Name":"%s","EventPattern":%s,"State":"ENABLED"}
                        """.formatted(name, quote(pattern)))
                .when().post("/")
                .then().statusCode(200);
        given()
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .contentType(EVENTS_CONTENT_TYPE)
                .body("""
                        {"Rule":"%s","Targets":[{"Id":"1","Arn":"%s"}]}
                        """.formatted(name, queueArn(queueUrl)))
                .when().post("/")
                .then().statusCode(200);
        return queueUrl;
    }

    private static String createQueue(String name) {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueName\":\"%s\"}".formatted(name))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("QueueUrl");
    }

    private static String queueArn(String queueUrl) {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueUrl\":\"%s\",\"AttributeNames\":[\"QueueArn\"]}".formatted(queueUrl))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("Attributes.QueueArn");
    }

    private static List<JsonNode> receiveEvents(String queueUrl, int expected) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int i = 0; i < 20 && events.size() < expected; i++) {
            events.addAll(receive(queueUrl, 1));
        }
        if (events.size() < expected) {
            fail("Expected " + expected + " events on " + queueUrl + " but received " + events);
        }
        // A standard SQS queue does not promise order, so order by the status lifecycle.
        events.sort((a, b) -> Boolean.compare(
                !"RUNNING".equals(a.path("detail").path("status").asText()),
                !"RUNNING".equals(b.path("detail").path("status").asText())));
        return events;
    }

    private static List<JsonNode> receive(String queueUrl, int waitSeconds) throws Exception {
        Response resp = given()
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueUrl\":\"%s\",\"MaxNumberOfMessages\":10,\"WaitTimeSeconds\":%d}"
                        .formatted(queueUrl, waitSeconds))
                .when().post("/");
        resp.then().statusCode(200);
        List<JsonNode> events = new ArrayList<>();
        for (JsonNode message : mapper.readTree(resp.body().asString()).path("Messages")) {
            events.add(mapper.readTree(message.path("Body").asText()));
        }
        return events;
    }

    private static String createStateMachine(String name, String type, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"name": "%s", "type": "%s", "roleArn": "%s", "definition": %s}
                        """.formatted(name, type, ROLE_ARN, quote(definition.strip())))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private static String startExecution(String smArn, String escapedInput) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": "%s"}
                        """.formatted(smArn, escapedInput))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
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
