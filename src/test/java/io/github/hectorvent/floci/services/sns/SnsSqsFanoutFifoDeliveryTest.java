package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.SqsServiceFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that SNS FIFO topic delivery correctly passes messageDeduplicationId
 * through to subscribed SQS FIFO queues.
 */
class SnsSqsFanoutFifoDeliveryTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String BASE_URL = "http://localhost:4566";

    private SnsService snsService;
    private SqsService sqsService;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        sqsService = SqsServiceFactory.createInMemory(BASE_URL, regionResolver);
        snsService = new SnsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                regionResolver, sqsService, null);
    }

    @Test
    void publish_withExplicitDedupId_deliversToFifoSqsQueue() {
        // Arrange
        sqsService.createQueue("fifo-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":fifo-queue.fifo";

        snsService.createTopic("fifo-topic.fifo", Map.of("FifoTopic", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":fifo-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        // Act
        String messageId = snsService.publish(topicArn, null, null, "hello fifo",
                null, null, "group-1", "my-dedup-id", REGION);

        // Assert
        assertNotNull(messageId);
        String queueUrl = BASE_URL + "/" + ACCOUNT + "/fifo-queue.fifo";
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getBody().contains("hello fifo"));
    }

    @Test
    void publish_withTopicContentBasedDedup_deliversToFifoSqsQueue() {
        // Arrange — topic has ContentBasedDeduplication, queue does NOT
        sqsService.createQueue("fifo-cbd-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":fifo-cbd-queue.fifo";

        snsService.createTopic("fifo-cbd-topic.fifo",
                Map.of("FifoTopic", "true", "ContentBasedDeduplication", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":fifo-cbd-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        // Act — no explicit dedup ID; topic derives one from message content
        String messageId = snsService.publish(topicArn, null, null, "cbd message",
                null, null, "group-1", null, REGION);

        // Assert
        assertNotNull(messageId);
        String queueUrl = BASE_URL + "/" + ACCOUNT + "/fifo-cbd-queue.fifo";
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getBody().contains("cbd message"));
    }

    @Test
    void publishBatch_withExplicitDedupIds_deliversToFifoSqsQueue() {
        // Arrange
        sqsService.createQueue("fifo-batch-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":fifo-batch-queue.fifo";

        snsService.createTopic("fifo-batch-topic.fifo", Map.of("FifoTopic", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":fifo-batch-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        // Act
        var entries = List.<Map<String, Object>>of(
                Map.of("Id", "e1", "Message", "batch-msg-1",
                        "MessageGroupId", "group-a", "MessageDeduplicationId", "dedup-1"),
                Map.of("Id", "e2", "Message", "batch-msg-2",
                        "MessageGroupId", "group-b", "MessageDeduplicationId", "dedup-2")
        );
        var result = snsService.publishBatch(topicArn, entries, REGION);

        // Assert
        assertEquals(2, result.successful().size());
        assertEquals(0, result.failed().size());

        String queueUrl = BASE_URL + "/" + ACCOUNT + "/fifo-batch-queue.fifo";
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(2, messages.size());

        List<String> bodies = messages.stream().map(Message::getBody).toList();
        assertTrue(bodies.stream().anyMatch(b -> b.contains("batch-msg-1")));
        assertTrue(bodies.stream().anyMatch(b -> b.contains("batch-msg-2")));
    }

    @Test
    void publishBatch_withTopicContentBasedDedup_deliversToFifoSqsQueue() {
        // Arrange — topic has ContentBasedDeduplication, queue does NOT
        sqsService.createQueue("fifo-batch-cbd-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":fifo-batch-cbd-queue.fifo";

        snsService.createTopic("fifo-batch-cbd-topic.fifo",
                Map.of("FifoTopic", "true", "ContentBasedDeduplication", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":fifo-batch-cbd-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        // Act — no explicit dedup IDs; topic derives them from message content
        var entries = List.<Map<String, Object>>of(
                Map.of("Id", "e1", "Message", "cbd-batch-msg-1", "MessageGroupId", "group-a"),
                Map.of("Id", "e2", "Message", "cbd-batch-msg-2", "MessageGroupId", "group-b")
        );
        var result = snsService.publishBatch(topicArn, entries, REGION);

        // Assert
        assertEquals(2, result.successful().size());
        assertEquals(0, result.failed().size());

        String queueUrl = BASE_URL + "/" + ACCOUNT + "/fifo-batch-cbd-queue.fifo";
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(2, messages.size());

        List<String> bodies = messages.stream().map(Message::getBody).toList();
        assertTrue(bodies.stream().anyMatch(b -> b.contains("cbd-batch-msg-1")));
        assertTrue(bodies.stream().anyMatch(b -> b.contains("cbd-batch-msg-2")));
    }

    @Test
    void publish_duplicateMessage_isDeduplicatedAtTopicLevel() {
        // Arrange
        sqsService.createQueue("fifo-dedup-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":fifo-dedup-queue.fifo";

        snsService.createTopic("fifo-dedup-topic.fifo", Map.of("FifoTopic", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":fifo-dedup-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        // Act — publish same dedup ID twice
        snsService.publish(topicArn, null, null, "first",
                null, null, "group-1", "same-dedup", REGION);
        snsService.publish(topicArn, null, null, "second",
                null, null, "group-1", "same-dedup", REGION);

        // Assert — only first message should be delivered
        String queueUrl = BASE_URL + "/" + ACCOUNT + "/fifo-dedup-queue.fifo";
        List<Message> messages = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).getBody().contains("first"));
    }

    @Test
    void clearFifoDedupForSubscribedQueue_thenRepublishWithSameDedupId_deliversAgain() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        SqsService purgeSqsService = SqsServiceFactory.createInMemoryWithFifoDedupPurgeAndSns(
                BASE_URL, regionResolver, snsService);
        sqsService.createQueue("manual-sns-dedup-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        purgeSqsService.createQueue("manual-sns-dedup-queue.fifo", Map.of("FifoQueue", "true"), REGION);
        String queueArn = "arn:aws:sqs:" + REGION + ":" + ACCOUNT + ":manual-sns-dedup-queue.fifo";

        snsService.createTopic("manual-sns-dedup-topic.fifo", Map.of("FifoTopic", "true"), null, REGION);
        String topicArn = "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":manual-sns-dedup-topic.fifo";
        snsService.subscribe(topicArn, "sqs", queueArn, REGION, Map.of());

        String queueUrl = BASE_URL + "/" + ACCOUNT + "/manual-sns-dedup-queue.fifo";

        snsService.publish(topicArn, null, null, "before-clear", null, null, "group-1", "shared-dedup", REGION);
        List<Message> first = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, first.size());
        for (Message m : first) {
            sqsService.deleteMessage(queueUrl, m.getReceiptHandle(), REGION);
        }

        purgeSqsService.purgeQueue(queueUrl, REGION);

        snsService.publish(topicArn, null, null, "after-clear", null, null, "group-1", "shared-dedup", REGION);
        List<Message> after = sqsService.receiveMessage(queueUrl, 10, 30, 0, REGION);
        assertEquals(1, after.size());
        assertTrue(after.getFirst().getBody().contains("after-clear"));
    }
}
