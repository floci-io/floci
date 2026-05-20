package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that SnsQueryHandler.handle("PublishBatch", ...) parses per-entry
 * MessageAttributes from the Query-protocol form parameters and propagates
 * them to subscribed SQS queues, matching the behavior of single-entry Publish.
 */
class SnsQueryHandlerPublishBatchTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    private SnsService snsService;
    private SqsService sqsService;
    private SnsQueryHandler handler;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, sqsService, null);
        handler = new SnsQueryHandler(snsService);
    }

    @Test
    void publishBatch_forwardsPerEntryMessageAttributesToSubscribedSqsQueue() {
        // Arrange — raw delivery so SQS messages carry the SNS attributes directly
        sqsService.createQueue("batch-attrs-queue", Map.of(), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":batch-attrs-queue";

        snsService.createTopic("batch-attrs-topic", Map.of(), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":batch-attrs-topic";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of("RawMessageDelivery", "true"));

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("TopicArn", topicArn);
        putBatchEntry(params, 1, "e1", "{\"id\":\"a\"}",
                attr("kind", "String", "alpha"),
                attr("id", "String", "id-a"),
                attr("count", "Number", "42"));
        putBatchEntry(params, 2, "e2", "{\"id\":\"b\"}",
                attr("kind", "String", "alpha"),
                attr("id", "String", "id-b"),
                attr("count", "Number", "43"));

        // Act
        Response response = handler.handle("PublishBatch", params, REGION);

        // Assert
        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Successful>"));

        List<Message> messages = sqsService.receiveMessage(
                BASE_URL + "/" + ACCOUNT + "/batch-attrs-queue", 10, 30, 0, REGION);
        assertEquals(2, messages.size());

        for (Message m : messages) {
            Map<String, MessageAttributeValue> attrs = m.getMessageAttributes();
            assertNotNull(attrs);
            assertEquals(3, attrs.size(), "all attributes should be forwarded, got " + attrs.keySet());

            assertEquals("alpha", attrs.get("kind").getStringValue());
            assertEquals("String", attrs.get("kind").getDataType());
            assertEquals("Number", attrs.get("count").getDataType());

            String id = attrs.get("id").getStringValue();
            assertTrue("id-a".equals(id) || "id-b".equals(id));
            assertTrue(m.getBody().contains(id.substring(id.length() - 1)));
        }
    }

    @Test
    void publishBatch_entryWithoutMessageAttributes_doesNotPolluteOtherEntries() {
        // Arrange
        sqsService.createQueue("mixed-attrs-queue", Map.of(), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":mixed-attrs-queue";

        snsService.createTopic("mixed-attrs-topic", Map.of(), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":mixed-attrs-topic";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of("RawMessageDelivery", "true"));

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("TopicArn", topicArn);
        putBatchEntry(params, 1, "with", "with-attrs", attr("marker", "String", "present"));
        putBatchEntry(params, 2, "without", "no-attrs");

        // Act
        handler.handle("PublishBatch", params, REGION);

        // Assert
        List<Message> messages = sqsService.receiveMessage(
                BASE_URL + "/" + ACCOUNT + "/mixed-attrs-queue", 10, 30, 0, REGION);
        assertEquals(2, messages.size());

        for (Message m : messages) {
            if (m.getBody().contains("with-attrs")) {
                assertEquals(1, m.getMessageAttributes().size());
                assertEquals("present", m.getMessageAttributes().get("marker").getStringValue());
            } else {
                assertTrue(m.getMessageAttributes() == null || m.getMessageAttributes().isEmpty());
            }
        }
    }

    private static void putBatchEntry(MultivaluedMap<String, String> params, int idx,
                                      String id, String message, Attr... attrs) {
        String entryPrefix = "PublishBatchRequestEntries.member." + idx;
        params.add(entryPrefix + ".Id", id);
        params.add(entryPrefix + ".Message", message);
        for (int j = 0; j < attrs.length; j++) {
            String attrPrefix = entryPrefix + ".MessageAttributes.entry." + (j + 1);
            params.add(attrPrefix + ".Name", attrs[j].name);
            params.add(attrPrefix + ".Value.DataType", attrs[j].dataType);
            params.add(attrPrefix + ".Value.StringValue", attrs[j].value);
        }
    }

    private static Attr attr(String name, String dataType, String value) {
        return new Attr(name, dataType, value);
    }

    private record Attr(String name, String dataType, String value) {}
}
