package io.floci.aws.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeEventTimeIntegrationTest {

    private static final String ACCOUNT = "725018800073";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventBridgeClient events;
    private SqsClient sqs;
    private String name;
    private String source;
    private String queueUrl;

    @BeforeEach
    void routeASourceToAQueue() {
        events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
        sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1);
        RestAssuredJsonUtils.configureAwsContentTypes();
        name = "event-time-" + UUID.randomUUID();
        source = "repro." + name;
        queueUrl = sqs.createQueue(request -> request.queueName(name)).queueUrl();
        String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        events.putRule(request -> request.name(name).eventPattern("{\"source\":[\"" + source + "\"]}"));
        events.putTargets(request -> request.rule(name).targets(target -> target.id("q").arn(queueArn)));
    }

    @AfterEach
    void deleteTheRouteAndCloseTheClients() {
        try {
            events.removeTargets(request -> request.rule(name).ids("q"));
            events.deleteRule(request -> request.name(name));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        } finally {
            try {
                sqs.close();
            } finally {
                events.close();
            }
        }
    }

    @Test
    void deliveredEventWithoutAnEntryTimeIsStampedToTheSecond() throws Exception {
        String time = deliveredTime(PutEventsRequestEntry.builder()
                .source(source).detailType("no-time").detail("{}").build());

        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), time);
    }

    @Test
    void deliveredEventReportsTheEntryTimeToTheSecond() throws Exception {
        String time = deliveredTime(PutEventsRequestEntry.builder()
                .source(source).detailType("with-time").detail("{}")
                .time(Instant.parse("2026-01-02T03:04:05.678Z")).build());

        assertEquals("2026-01-02T03:04:05Z", time);
    }

    @Test
    void entryTimeOutsideTheTimestampRangeIsRefusedAsASerializationError() {
        putEventsWithTime("1e30");
    }

    @Test
    void entryTimeThatIsNotANumberIsRefusedAsASerializationError() {
        putEventsWithTime("\"yesterday\"");
    }

    private void putEventsWithTime(String time) {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("{\"Entries\":[{\"Source\":\"" + source
                        + "\",\"DetailType\":\"bad-time\",\"Detail\":\"{}\",\"Time\":" + time + "}]}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
    }

    private String deliveredTime(PutEventsRequestEntry entry) throws Exception {
        events.putEvents(request -> request.entries(entry));
        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                .waitTimeSeconds(5)).messages();
        assertEquals(1, messages.size());
        return MAPPER.readTree(messages.get(0).body()).path("time").asText();
    }
}
