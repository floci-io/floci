package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end proof that pipeline executions emit {@code aws.codepipeline} events onto the
 * default EventBridge bus: a rule with an SQS target receives the pipeline state-change
 * event for an execution started over the wire.
 */
@QuarkusTest
class CodePipelineEventsIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ISO_TIMESTAMP = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z";
    private static final String MINUTE_TIMESTAMP = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z";
    private static final String TARGET_PREFIX = "CodePipeline_20150709.";

    @Inject
    SqsService sqsService;
    @Inject
    SnsService snsService;
    @Inject
    EventBridgeService eventBridgeService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action) {
        return given().header("X-Amz-Target", TARGET_PREFIX + action).contentType(CONTENT_TYPE);
    }

    @Test
    void pipelineStateChangeEventReachesSqsThroughEventBridgeRule() throws Exception {
        Queue queue = sqsService.createQueue("cp-events-it", Map.of(), REGION);
        eventBridgeService.putRule("cp-events-it-rule", "default",
                "{\"source\":[\"aws.codepipeline\"]}", null, RuleState.ENABLED,
                null, null, Map.of(), REGION);
        eventBridgeService.putTargets("cp-events-it-rule", "default",
                List.of(new Target("cp-events-it-target",
                        "arn:aws:sqs:us-east-1:000000000000:cp-events-it", null, null)),
                REGION);

        call("CreatePipeline")
            .body("""
                {
                  "pipeline": {
                    "name": "events-it",
                    "roleArn": "arn:aws:iam::000000000000:role/cp",
                    "artifactStore": {"type": "S3", "location": "cp-artifacts"},
                    "stages": [
                      {
                        "name": "Gate",
                        "actions": [
                          {
                            "name": "HumanGate",
                            "actionTypeId": {"category": "Approval", "owner": "AWS",
                                             "provider": "Manual", "version": "1"},
                            "runOrder": 1
                          }
                        ]
                      },
                      {
                        "name": "Release",
                        "actions": [
                          {
                            "name": "ReleaseGate",
                            "actionTypeId": {"category": "Approval", "owner": "AWS",
                                             "provider": "Manual", "version": "1"},
                            "runOrder": 1
                          }
                        ]
                      }
                    ]
                  }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String executionId = call("StartPipelineExecution")
            .body("""
                { "name": "events-it" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("pipelineExecutionId");

        JsonNode started = awaitEvent("CodePipeline Pipeline Execution State Change", "STARTED");
        assertEquals("events-it", started.path("detail").path("pipeline").asText());
        assertEquals(executionId, started.path("detail").path("execution-id").asText());
        assertEquals("aws.codepipeline", started.path("source").asText());
        // Catches: start-time emitted as epoch seconds instead of an ISO-8601 timestamp.
        assertTrue(started.path("detail").path("start-time").asText().matches(ISO_TIMESTAMP));
        assertTrue(started.path("resources").get(0).asText()
                .endsWith(":codepipeline:us-east-1:000000000000:events-it"));

        call("StopPipelineExecution")
            .body("""
                { "pipelineName": "events-it", "pipelineExecutionId": "%s", "abandon": true }
                """.formatted(executionId))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void approvalNotificationOnTheTopicMatchesTheUserGuideShape() throws Exception {
        // Catches: approval message with non-ISO expires, no externalEntityLink, missing or wrong consoleLink and approvalReviewLink,
        // or a subject over 100 characters that SNS rejects and silently drops the notification.
        Topic topic = snsService.createTopic("cp-approval-it", Map.of(), Map.of(), REGION);
        sqsService.createQueue("cp-approval-it-q", Map.of(), REGION);
        snsService.subscribe(topic.getTopicArn(), "sqs",
                "arn:aws:sqs:us-east-1:000000000000:cp-approval-it-q", REGION, Map.of());
        String pipelineName = "approval-it-" + "p".repeat(70);

        call("CreatePipeline")
            .body("""
                {
                  "pipeline": {
                    "name": "%s",
                    "roleArn": "arn:aws:iam::000000000000:role/cp",
                    "artifactStore": {"type": "S3", "location": "cp-artifacts"},
                    "stages": [
                      {
                        "name": "Gate",
                        "actions": [
                          {
                            "name": "HumanGateWithALongActionName",
                            "actionTypeId": {"category": "Approval", "owner": "AWS",
                                             "provider": "Manual", "version": "1"},
                            "runOrder": 1,
                            "configuration": {
                              "NotificationArn": "%s",
                              "CustomData": "ship it",
                              "ExternalEntityLink": "http://example.com/review"
                            }
                          }
                        ]
                      },
                      {
                        "name": "Release",
                        "actions": [
                          {
                            "name": "ReleaseGate",
                            "actionTypeId": {"category": "Approval", "owner": "AWS",
                                             "provider": "Manual", "version": "1"},
                            "runOrder": 1
                          }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(pipelineName, topic.getTopicArn()))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String executionId = call("StartPipelineExecution")
            .body("{ \"name\": \"" + pipelineName + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("pipelineExecutionId");

        String queueUrl = sqsService.getQueueUrl("cp-approval-it-q", REGION);
        JsonNode envelope = null;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (envelope == null && System.currentTimeMillis() < deadline) {
            for (Message message : sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION)) {
                envelope = mapper.readTree(message.getBody());
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertNotNull(envelope, "No approval notification reached the topic");
        String subject = envelope.path("Subject").asText();
        assertEquals(100, subject.length());
        assertTrue(subject.startsWith("APPROVAL NEEDED: AWS CodePipeline approval-it-"));

        JsonNode message = mapper.readTree(envelope.path("Message").asText());
        assertEquals("us-east-1", message.path("region").asText());
        assertEquals("https://console.aws.amazon.com/codepipeline/home?region=us-east-1#/view/"
                + pipelineName, message.path("consoleLink").asText());
        JsonNode approval = message.path("approval");
        assertEquals(pipelineName, approval.path("pipelineName").asText());
        assertEquals("Gate", approval.path("stageName").asText());
        assertEquals("HumanGateWithALongActionName", approval.path("actionName").asText());
        assertEquals("ship it", approval.path("customData").asText());
        assertEquals("http://example.com/review", approval.path("externalEntityLink").asText());
        assertEquals("https://console.aws.amazon.com/codepipeline/home?region=us-east-1#/view/"
                + pipelineName + "/Gate/HumanGateWithALongActionName/approve/"
                + approval.path("token").asText(), approval.path("approvalReviewLink").asText());
        assertFalse(approval.path("token").asText().isEmpty());
        assertTrue(approval.path("expires").asText().matches(MINUTE_TIMESTAMP),
                "expires must be an ISO-8601 timestamp to the minute: " + approval.path("expires"));

        call("StopPipelineExecution")
            .body("{ \"pipelineName\": \"" + pipelineName + "\", \"pipelineExecutionId\": \""
                    + executionId + "\", \"abandon\": true }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private JsonNode awaitEvent(String detailType, String state) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        String queueUrl = sqsService.getQueueUrl("cp-events-it", REGION);
        while (System.currentTimeMillis() < deadline) {
            for (Message message : sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION)) {
                JsonNode event = mapper.readTree(message.getBody());
                if (detailType.equals(event.path("detail-type").asText())
                        && state.equals(event.path("detail").path("state").asText())) {
                    return event;
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return fail("No " + detailType + " event with state " + state + " arrived on the queue");
    }
}
