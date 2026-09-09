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

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for the optimized {@code arn:aws:states:::events:putEvents} task integration.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsPutEventsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENTS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String RESOURCE = "arn:aws:states:::events:putEvents";
    private static final String SOURCE = "floci.putevents.task";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static String sinkQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(0)
    void setup_routeTheSourceToASqsQueue() {
        sinkQueueUrl = createQueue("put-events-task-sink");
        putRule("put-events-task-rule", "{\\\"source\\\":[\\\"" + SOURCE + "\\\"]}");
        putTarget("put-events-task-rule", queueArn(sinkQueueUrl));
    }

    @Test
    @Order(1)
    void publishedEventReachesTheRuleTargetAndTheResultCarriesItsEventId() throws Exception {
        var smArn = createStateMachine("put-events-delivers", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Publish",
                  "States": {
                    "Publish": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "Entries": [{
                          "Source": "SOURCE_NAME",
                          "DetailType": "payout requested",
                          "Detail": "{\\"amount\\": 1200}"
                        }]
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE_ARN", RESOURCE).replace("SOURCE_NAME", SOURCE));

        var result = mapper.readTree(succeedingOutputOf(smArn));
        assertEquals(0, result.path("FailedEntryCount").asInt());
        var eventId = result.path("Entries").get(0).path("EventId").asText();
        assertFalse(eventId.isBlank());

        var delivered = mapper.readTree(receiveSingleMessage(sinkQueueUrl).path("Body").asText());
        assertEquals(eventId, delivered.path("id").asText());
        assertEquals("payout requested", delivered.path("detail-type").asText());
        assertEquals(1200, delivered.path("detail").path("amount").asInt());
    }

    @Test
    @Order(2)
    void taskResultCarriesTheSameFieldsThePutEventsApiReturns() throws Exception {
        var smArn = createStateMachine("put-events-same-envelope", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Publish",
                  "States": {
                    "Publish": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "Entries": [{"Source": "floci.unrouted", "DetailType": "t", "Detail": "{}"}]
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE_ARN", RESOURCE));

        var taskResult = mapper.readTree(succeedingOutputOf(smArn));
        var apiResult = mapper.readTree(given()
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .contentType(EVENTS_CONTENT_TYPE)
                .body("""
                        {"Entries":[{"Source":"floci.unrouted","DetailType":"t","Detail":"{}"}]}
                        """)
                .when().post("/")
                .then().statusCode(200)
                .extract().body().asString());

        assertEquals(fieldNames(apiResult), fieldNames(taskResult));
        assertEquals(fieldNames(apiResult.path("Entries").get(0)),
                fieldNames(taskResult.path("Entries").get(0)));
        assertEquals(apiResult.path("FailedEntryCount").asInt(), taskResult.path("FailedEntryCount").asInt());
    }

    @Test
    @Order(3)
    void oneRejectedEntryFailsTheTaskWithFailedEntryAndTheWholeResponseAsCause() throws Exception {
        var smArn = createStateMachine("put-events-failed-entry", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Publish",
                  "States": {
                    "Publish": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "Entries": [
                          {"Source": "floci.unrouted", "DetailType": "t", "Detail": "{}"},
                          {"EventBusName": "no-such-bus", "Source": "floci.unrouted",
                           "DetailType": "t", "Detail": "{}"}
                        ]
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE_ARN", RESOURCE));

        var describe = waitForTerminalState(startExecution(smArn));
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("EventBridge.FailedEntry", describe.jsonPath().getString("error"));

        // The cause is the PutEvents response itself, so the caller can tell which entry failed.
        var cause = mapper.readTree(describe.jsonPath().getString("cause"));
        assertEquals(1, cause.path("FailedEntryCount").asInt());
        assertFalse(cause.path("Entries").get(0).path("EventId").asText().isBlank());
        assertEquals("InvalidArgument", cause.path("Entries").get(1).path("ErrorCode").asText());
    }

    @Test
    @Order(4)
    void aFailedEntryIsCatchableByItsErrorName() throws Exception {
        var smArn = createStateMachine("put-events-catch", """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Publish",
                  "States": {
                    "Publish": {
                      "Type": "Task",
                      "Resource": "RESOURCE_ARN",
                      "Arguments": {
                        "Entries": [{"EventBusName": "no-such-bus", "Source": "floci.unrouted",
                                     "DetailType": "t", "Detail": "{}"}]
                      },
                      "Catch": [{"ErrorEquals": ["EventBridge.FailedEntry"], "Next": "Recovered"}],
                      "End": true
                    },
                    "Recovered": {
                      "Type": "Pass",
                      "Output": {"recovered": true},
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE_ARN", RESOURCE));

        assertTrue(mapper.readTree(succeedingOutputOf(smArn)).path("recovered").asBoolean());
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String succeedingOutputOf(String smArn) {
        var describe = waitForTerminalState(startExecution(smArn));
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        return describe.jsonPath().getString("output");
    }

    private static String createQueue(String name) {
        var resp = given()
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueName\":\"%s\"}".formatted(name))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("QueueUrl");
    }

    private static String queueArn(String queueUrl) {
        var resp = given()
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .contentType(SQS_CONTENT_TYPE)
                .body("{\"QueueUrl\":\"%s\",\"AttributeNames\":[\"QueueArn\"]}".formatted(queueUrl))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("Attributes.QueueArn");
    }

    private static void putRule(String name, String eventPattern) {
        given()
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .contentType(EVENTS_CONTENT_TYPE)
                .body("""
                        {"Name":"%s","EventPattern":"%s","State":"ENABLED"}
                        """.formatted(name, eventPattern))
                .when().post("/")
                .then().statusCode(200);
    }

    private static void putTarget(String rule, String targetArn) {
        given()
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .contentType(EVENTS_CONTENT_TYPE)
                .body("""
                        {"Rule":"%s","Targets":[{"Id":"1","Arn":"%s"}]}
                        """.formatted(rule, targetArn))
                .when().post("/")
                .then().statusCode(200);
    }

    private JsonNode receiveSingleMessage(String queueUrl) throws Exception {
        for (var i = 0; i < 20; i++) {
            var resp = given()
                    .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .contentType(SQS_CONTENT_TYPE)
                    .body("{\"QueueUrl\":\"%s\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":1}"
                            .formatted(queueUrl))
                    .when().post("/");
            resp.then().statusCode(200);
            var messages = mapper.readTree(resp.body().asString()).path("Messages");
            if (messages.size() == 1) {
                return messages.get(0);
            }
            Thread.sleep(100);
        }
        fail("No event was delivered to " + queueUrl);
        return null;
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

    private static String startExecution(String smArn) {
        var resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": "{}"}
                        """.formatted(smArn))
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private static Response waitForTerminalState(String execArn) {
        for (var i = 0; i < 100; i++) {
            var resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body("""
                            {"executionArn": "%s"}
                            """.formatted(execArn))
                    .when().post("/");
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
