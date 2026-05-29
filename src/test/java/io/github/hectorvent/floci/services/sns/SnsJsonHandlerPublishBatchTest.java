package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that SnsJsonHandler.handle("PublishBatch", ...) returns AWS-compatible
 * partial-failure responses: a malformed entry must land in the Failed list
 * (with InvalidParameterValue + SenderFault=true) instead of aborting the
 * whole batch with a top-level error.
 */
class SnsJsonHandlerPublishBatchTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SnsService snsService;
    private SqsService sqsService;
    private SnsJsonHandler handler;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, sqsService, null);
        handler = new SnsJsonHandler(snsService, objectMapper);
    }

    @Test
    void publishBatch_invalidBinaryAttribute_failsOnlyOffendingEntryAndProcessesRest() throws Exception {
        // Arrange
        sqsService.createQueue("json-partial-fail-queue", Map.of(), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":json-partial-fail-queue";

        snsService.createTopic("json-partial-fail-topic", Map.of(), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":json-partial-fail-topic";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of("RawMessageDelivery", "true"));

        ObjectNode request = objectMapper.createObjectNode();
        request.put("TopicArn", topicArn);
        ArrayNode entries = request.putArray("PublishBatchRequestEntries");

        // Entry 1 — invalid base64 in BinaryValue; must NOT abort the batch
        ObjectNode bad = entries.addObject();
        bad.put("Id", "bad");
        bad.put("Message", "ignored");
        ObjectNode badAttrs = bad.putObject("MessageAttributes");
        ObjectNode badBlob = badAttrs.putObject("blob");
        badBlob.put("DataType", "Binary");
        badBlob.put("BinaryValue", "@@@-not-base64-@@@");

        // Entry 2 — valid; must be published
        ObjectNode good = entries.addObject();
        good.put("Id", "good");
        good.put("Message", "valid-message");

        // Act
        JsonNode requestNode = objectMapper.readTree(objectMapper.writeValueAsString(request));
        Response response = handler.handle("PublishBatch", requestNode, REGION);

        // Assert — partial failure (matches real AWS SNS)
        assertEquals(200, response.getStatus(),
                "PublishBatch must return 200 with per-entry failures, not abort the whole batch");

        JsonNode body = objectMapper.valueToTree(response.getEntity());
        JsonNode failed = body.path("Failed");
        JsonNode successful = body.path("Successful");

        assertTrue(failed.isArray() && failed.size() == 1, "exactly one entry must be in Failed");
        JsonNode failedEntry = failed.get(0);
        assertEquals("bad", failedEntry.path("Id").asText());
        assertEquals("InvalidParameterValue", failedEntry.path("Code").asText());
        assertTrue(failedEntry.path("SenderFault").asBoolean(), "SenderFault must be true");

        assertTrue(successful.isArray() && successful.size() == 1, "exactly one entry must be in Successful");
        assertEquals("good", successful.get(0).path("Id").asText());

        // Only the good entry should reach SQS
        List<Message> messages = sqsService.receiveMessage(
                BASE_URL + "/" + ACCOUNT + "/json-partial-fail-queue", 10, 30, 0, REGION);
        assertEquals(1, messages.size(), "only the valid entry should be delivered");
        assertEquals("valid-message", messages.get(0).getBody());
    }
}
