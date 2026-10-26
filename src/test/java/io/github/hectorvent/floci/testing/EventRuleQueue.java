package io.github.hectorvent.floci.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.restassured.response.Response;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * An SQS queue that an EventBridge rule on a Region's default bus sends its matching events to, set
 * up over the AWS wire, so a test can read back the events a service published. Give the rule a
 * pattern, or a Region, of the test's own, so no other test's events reach the queue, and close it,
 * which removes the rule and the queue. The calls send AWS JSON, so the test class runs
 * {@link RestAssuredJsonUtils#configureAwsContentTypes()} first.
 */
public final class EventRuleQueue implements AutoCloseable {

    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENTS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_ID = "1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String region;
    private final String name;
    private final String url;

    private EventRuleQueue(String region, String name, String url) {
        this.region = region;
        this.name = name;
        this.url = url;
    }

    /** A queue named {@code name}, and a rule of the same name sending it the events {@code pattern} matches. */
    public static EventRuleQueue route(String region, String name, String pattern) {
        String url = call(region, "sqs", "AmazonSQS.CreateQueue", SQS_CONTENT_TYPE,
                MAPPER.createObjectNode().put("QueueName", name)).jsonPath().getString("QueueUrl");
        ObjectNode attributes = MAPPER.createObjectNode().put("QueueUrl", url);
        attributes.putArray("AttributeNames").add("QueueArn");
        String arn = call(region, "sqs", "AmazonSQS.GetQueueAttributes", SQS_CONTENT_TYPE, attributes)
                .jsonPath().getString("Attributes.QueueArn");
        call(region, "events", "AWSEvents.PutRule", EVENTS_CONTENT_TYPE,
                MAPPER.createObjectNode().put("Name", name).put("EventPattern", pattern));
        ObjectNode targets = MAPPER.createObjectNode().put("Rule", name);
        targets.putArray("Targets").addObject().put("Id", TARGET_ID).put("Arn", arn);
        call(region, "events", "AWSEvents.PutTargets", EVENTS_CONTENT_TYPE, targets);
        return new EventRuleQueue(region, name, url);
    }

    /**
     * The events on the queue, as they arrived (a standard queue does not promise their order), each
     * deleted once read. EventBridge delivers to an SQS target before the call that published the event
     * returns, so they are all there once the calls under test have returned.
     */
    public List<JsonNode> drain() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        ObjectNode receive = MAPPER.createObjectNode().put("QueueUrl", url).put("MaxNumberOfMessages", 10)
                .put("WaitTimeSeconds", 0);
        while (true) {
            Response response = call(region, "sqs", "AmazonSQS.ReceiveMessage", SQS_CONTENT_TYPE, receive);
            JsonNode messages = MAPPER.readTree(response.asString()).path("Messages");
            if (messages.isEmpty()) {
                return events;
            }
            for (JsonNode message : messages) {
                events.add(MAPPER.readTree(message.path("Body").asText()));
                call(region, "sqs", "AmazonSQS.DeleteMessage", SQS_CONTENT_TYPE, MAPPER.createObjectNode()
                        .put("QueueUrl", url).put("ReceiptHandle", message.path("ReceiptHandle").asText()));
            }
        }
    }

    /** Removes the rule's target, the rule and the queue. */
    @Override
    public void close() {
        ObjectNode targets = MAPPER.createObjectNode().put("Rule", name);
        targets.putArray("Ids").add(TARGET_ID);
        call(region, "events", "AWSEvents.RemoveTargets", EVENTS_CONTENT_TYPE, targets);
        call(region, "events", "AWSEvents.DeleteRule", EVENTS_CONTENT_TYPE,
                MAPPER.createObjectNode().put("Name", name));
        call(region, "sqs", "AmazonSQS.DeleteQueue", SQS_CONTENT_TYPE,
                MAPPER.createObjectNode().put("QueueUrl", url));
    }

    private static Response call(String region, String service, String target, String contentType, JsonNode body) {
        Response response = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service
                        + "/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", target)
                .contentType(contentType)
                .body(body.toString())
                .when().post("/");
        response.then().statusCode(200);
        return response;
    }
}
