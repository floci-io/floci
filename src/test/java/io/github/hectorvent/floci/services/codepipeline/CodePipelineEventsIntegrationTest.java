package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String TARGET_PREFIX = "CodePipeline_20150709.";

    @Inject
    SqsService sqsService;
    @Inject
    EventBridgeService eventBridgeService;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification call(String action) {
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
