package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqsServiceTest {

    private SqsService sqsService;
    private static final String BASE_URL = "http://localhost:4566";

    @BeforeEach
    void setUp() {
        sqsService = new SqsService(new InMemoryStorage<>(), 30, 262144, BASE_URL);
    }

    @Test
    void createQueue() {
        Queue queue = sqsService.createQueue("test-queue", null);
        assertEquals("test-queue", queue.getQueueName());
        assertEquals(BASE_URL + "/000000000000/test-queue", queue.getQueueUrl());
        assertNotNull(queue.getCreatedTimestamp());
    }

    @Test
    void createQueueIsIdempotent() {
        Queue q1 = sqsService.createQueue("test-queue", null);
        Queue q2 = sqsService.createQueue("test-queue", null);
        assertEquals(q1.getQueueUrl(), q2.getQueueUrl());
    }

    @Test
    void createQueueWithAttributes() {
        Queue queue = sqsService.createQueue("test-queue",
                Map.of("VisibilityTimeout", "60"));
        assertEquals("60", queue.getAttributes().get("VisibilityTimeout"));
    }

    @Test
    void deleteQueue() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.deleteQueue(queue.getQueueUrl());
        assertThrows(AwsException.class, () ->
                sqsService.getQueueUrl("test-queue"));
    }

    @Test
    void deleteQueueNotFound() {
        assertThrows(AwsException.class, () ->
                sqsService.deleteQueue(BASE_URL + "/000000000000/nonexistent"));
    }

    @Test
    void listQueues() {
        sqsService.createQueue("alpha-queue", null);
        sqsService.createQueue("beta-queue", null);
        sqsService.createQueue("alpha-other", null);

        List<Queue> all = sqsService.listQueues(null);
        assertEquals(3, all.size());

        List<Queue> alpha = sqsService.listQueues("alpha");
        assertEquals(2, alpha.size());
    }

    @Test
    void getQueueUrl() {
        sqsService.createQueue("my-queue", null);
        String url = sqsService.getQueueUrl("my-queue");
        assertEquals(BASE_URL + "/000000000000/my-queue", url);
    }

    @Test
    void getQueueUrlNotFound() {
        assertThrows(AwsException.class, () ->
                sqsService.getQueueUrl("nonexistent"));
    }

    @Test
    void sendAndReceiveMessage() {
        Queue queue = sqsService.createQueue("test-queue", null);
        Message sent = sqsService.sendMessage(queue.getQueueUrl(), "Hello World", 0);
        assertNotNull(sent.getMessageId());
        assertNotNull(sent.getMd5OfBody());

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        assertEquals(1, received.size());
        assertEquals("Hello World", received.getFirst().getBody());
        assertNotNull(received.getFirst().getReceiptHandle());
        assertEquals(1, received.getFirst().getReceiveCount());
    }

    @Test
    void receiveMessageReturnsEmptyWhenNoMessages() {
        Queue queue = sqsService.createQueue("empty-queue", null);
        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        assertTrue(received.isEmpty());
    }

    @Test
    void messageBecomesInvisibleAfterReceive() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0);

        // First receive should get the message
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        assertEquals(1, first.size());

        // Second receive should get nothing (message is invisible)
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        assertTrue(second.isEmpty());
    }

    @Test
    void deleteMessage() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "to-delete", 0);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        sqsService.deleteMessage(queue.getQueueUrl(), received.getFirst().getReceiptHandle());

        // Message should be permanently gone; even after visibility would expire
        // it shouldn't reappear
        List<Message> afterDelete = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0);
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    void deleteMessageInvalidHandle() {
        Queue queue = sqsService.createQueue("test-queue", null);
        assertThrows(AwsException.class, () ->
                sqsService.deleteMessage(queue.getQueueUrl(), "invalid-handle"));
    }

    @Test
    void sendMessageToNonExistentQueue() {
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(BASE_URL + "/000000000000/nonexistent", "msg", 0));
    }

    @Test
    void receiveMultipleMessages() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0);
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0);
        sqsService.sendMessage(queue.getQueueUrl(), "msg3", 0);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 3, 30, 0);
        assertEquals(3, received.size());
    }

    @Test
    void purgeQueue() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0);
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0);

        sqsService.purgeQueue(queue.getQueueUrl());

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertTrue(received.isEmpty());
    }

    @Test
    void changeMessageVisibility() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        String receiptHandle = received.getFirst().getReceiptHandle();

        // Set visibility to 0 — message becomes visible immediately
        sqsService.changeMessageVisibility(queue.getQueueUrl(), receiptHandle, 0);

        List<Message> reReceived = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0);
        assertEquals(1, reReceived.size());
    }

    @Test
    void getQueueAttributes() {
        Queue queue = sqsService.createQueue("test-queue", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"));
        assertNotNull(attrs.get("QueueArn"));
        assertNotNull(attrs.get("CreatedTimestamp"));
        assertEquals("1", attrs.get("ApproximateNumberOfMessages"));
    }

    // --- FIFO Queue Tests ---

    @Test
    void createFifoQueue() {
        Queue queue = sqsService.createQueue("test-queue.fifo", null);
        assertTrue(queue.isFifo());
        assertEquals("true", queue.getAttributes().get("FifoQueue"));
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithExplicitAttribute() {
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("FifoQueue", "true", "ContentBasedDeduplication", "true"));
        assertTrue(queue.isFifo());
        assertEquals("true", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithoutSuffixFails() {
        assertThrows(AwsException.class, () ->
                sqsService.createQueue("test-queue", Map.of("FifoQueue", "true")));
    }

    @Test
    void sendMessageToFifoQueueRequiresGroupId() {
        Queue queue = sqsService.createQueue("test.fifo",
                Map.of("ContentBasedDeduplication", "true"));
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, null, null));
    }

    @Test
    void sendMessageToFifoQueueWithContentBasedDedup() {
        Queue queue = sqsService.createQueue("test.fifo",
                Map.of("ContentBasedDeduplication", "true"));
        Message msg = sqsService.sendMessage(queue.getQueueUrl(), "Hello FIFO", 0, "group1", null);
        assertNotNull(msg.getMessageId());
        assertEquals("group1", msg.getMessageGroupId());
        assertTrue(msg.getSequenceNumber() > 0);
        assertNotNull(msg.getMessageDeduplicationId());
    }

    @Test
    void sendMessageToFifoQueueWithExplicitDedupId() {
        Queue queue = sqsService.createQueue("test.fifo", null);
        Message msg = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertEquals("dedup-1", msg.getMessageDeduplicationId());
    }

    @Test
    void fifoDeduplicationReturnsExistingMessage() {
        Queue queue = sqsService.createQueue("test.fifo", null);
        Message msg1 = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        Message msg2 = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertEquals(msg1.getMessageId(), msg2.getMessageId());

        // Only one message should be in the queue
        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(1, received.size());
    }

    @Test
    void fifoQueueReceiveRespectsGroupOrdering() {
        Queue queue = sqsService.createQueue("test.fifo", null);
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg1", 0, "group1", "d1");
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg2", 0, "group1", "d2");
        sqsService.sendMessage(queue.getQueueUrl(), "g2-msg1", 0, "group2", "d3");

        // First receive: should get one message per group (first from each)
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(2, first.size());
        assertEquals("g1-msg1", first.get(0).getBody());
        assertEquals("g2-msg1", first.get(1).getBody());

        // Second receive: group1 and group2 both have in-flight messages, so nothing returned
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertTrue(second.isEmpty());

        // Delete the group1 message, then receive again — should get g1-msg2
        sqsService.deleteMessage(queue.getQueueUrl(), first.get(0).getReceiptHandle());
        List<Message> third = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(1, third.size());
        assertEquals("g1-msg2", third.get(0).getBody());
    }

    @Test
    void fifoQueueRequiresDedupIdWhenContentBasedDisabled() {
        Queue queue = sqsService.createQueue("test.fifo", null);
        // ContentBasedDeduplication is false by default
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", null));
    }

    @Test
    void receiveMessageUsesQueueVisibilityTimeoutWhenNotSpecified() {
        // Create queue with a short visibility timeout (1 second)
        Queue queue = sqsService.createQueue("short-vt-queue",
                Map.of("VisibilityTimeout", "1"));
        sqsService.sendMessage(queue.getQueueUrl(), "test-msg", 0);

        // Receive without specifying visibility timeout (-1 means "use queue default")
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0);
        assertEquals(1, first.size());

        // Message should be invisible immediately after receive
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0);
        assertTrue(second.isEmpty());

        // Wait for the queue's visibility timeout (1s) to expire, not the global default (30s)
        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Message should now be visible again
        List<Message> third = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0);
        assertEquals(1, third.size(), "Message should become visible after queue's VisibilityTimeout (1s), not global default (30s)");
    }

    // --- Queue-level DelaySeconds for FIFO queues (issue #475) ---

    @Test
    void queueLevelDelaySecondsAppliesToFifoQueue() {
        Queue queue = sqsService.createQueue("delay-fifo.fifo",
                Map.of("ContentBasedDeduplication", "true", "DelaySeconds", "1"));
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", null);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0);
        assertTrue(immediate.isEmpty(),
                "FIFO queue should honor queue-level DelaySeconds (issue #475)");

        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<Message> later = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0);
        assertEquals(1, later.size(),
                "Message should become visible once DelaySeconds elapses");
    }

    @Test
    void fifoQueueIgnoresPerMessageDelaySeconds() {
        // AWS SQS FIFO queues only support queue-level DelaySeconds; any
        // per-message value is ignored. Here the queue default is 0 and the
        // caller passes a positive per-message delay -- the message must be
        // immediately visible.
        Queue queue = sqsService.createQueue("fifo-ignores-per-msg.fifo",
                Map.of("ContentBasedDeduplication", "true"));
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 60, "group1", null);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0);
        assertEquals(1, immediate.size(),
                "FIFO queues must ignore per-message DelaySeconds");
    }

    // --- clearFifoDeduplicationCacheOnPurge tests ---

    @Test
    void purgeQueueClearsFifoDeduplicationCacheWhenEnabled() {
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        final var queue = service.createQueue("dedup-clear.fifo", Map.of("ContentBasedDeduplication", "true"));

        // First send — message M1 added, dedup cache populated with "dedup-1"
        final var m1 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertNotNull(m1.getMessageId());

        // Purge clears both messages and the dedup cache
        service.purgeQueue(queue.getQueueUrl());
        assertTrue(service.receiveMessage(queue.getQueueUrl(), 10, 0, 0).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with the same dedup ID — cache was cleared so this is treated as a fresh send
        final var m2 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertNotNull(m2.getMessageId());

        final var received = service.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(1, received.size(), "One message must be in the queue after re-send");

        // Third send with same dedup ID — fresh cache entry from m2 deduplicates correctly
        final var m3 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertEquals(m2.getMessageId(), m3.getMessageId(),
                "Dedup must work with the fresh cache entry after purge");
    }

    @Test
    void purgeQueuePreservesFifoDeduplicationCacheByDefault() {
        // Default service has clearFifoDeduplicationCacheOnPurge=false
        final var queue = sqsService.createQueue("dedup-preserve.fifo",
                Map.of("ContentBasedDeduplication", "true"));

        // Send and then purge — messages are gone but dedup cache is intact
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        sqsService.purgeQueue(queue.getQueueUrl());

        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 0, 0).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with same dedup ID — dedup cache fires but finds no message (purged),
        // so it falls through and creates a new message
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");

        final var received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(1, received.size(),
                "Re-send after purge must produce exactly one message in the queue");
    }

    @Test
    void purgeQueueClearsDedupStoreWhenEnabled() {
        final var dedupStore = new InMemoryStorage<String, Map<String, Long>>();
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), dedupStore,
                30, 262144, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        final var queue = service.createQueue("dedup-store-clear.fifo",
                Map.of("ContentBasedDeduplication", "true"));

        // Send a message — dedup entry must be persisted to the store
        service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1");
        assertFalse(dedupStore.keys().isEmpty(),
                "Dedup store must have an entry after sending a FIFO message");

        // Purge with flag enabled — dedupStore entry for the queue must be removed
        service.purgeQueue(queue.getQueueUrl());
        assertTrue(dedupStore.keys().isEmpty(),
                "Dedup store must be empty after purge with clearFifoDeduplicationCacheOnPurge=true");
    }

    @Test
    void purgeQueueWithClearFifoDelegatesToSnsForFifoDedupOnSubscribedTopics() {
        final var sns = mock(SnsService.class);
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 262144, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, sns);
        final var queue = service.createQueue("sns-dedup-delegate.fifo", Map.of("FifoQueue", "true"));
        service.purgeQueue(queue.getQueueUrl());
        verify(sns).clearFifoDeduplicationCacheForSqsQueueSubscriptions(
                queue.getQueueUrl(), "us-east-1");
    }
}
