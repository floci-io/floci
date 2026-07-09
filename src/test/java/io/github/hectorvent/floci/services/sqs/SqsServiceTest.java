package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqsServiceTest {

    private SqsService sqsService;
    private static final String BASE_URL = "http://localhost:4566";

    @BeforeEach
    void setUp() {
        sqsService = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL);
    }

    @Test
    void createQueue() {
        Queue queue = sqsService.createQueue("test-queue", null, "eu-west-1");
        assertEquals("test-queue", queue.getQueueName());
        assertEquals(BASE_URL + "/000000000000/test-queue", queue.getQueueUrl());
        assertNotNull(queue.getCreatedTimestamp());
    }

    @Test
    void createQueueIsIdempotent() {
        String region = "eu-west-1";
        Queue q1 = sqsService.createQueue("test-queue", null, region);
        Queue q2 = sqsService.createQueue("test-queue", null, region);
        assertEquals(q1.getQueueUrl(), q2.getQueueUrl());
    }

    @Test
    void createQueueWithAttributes() {
        Queue queue = sqsService.createQueue("test-queue",
                Map.of("VisibilityTimeout", "60"), "eu-west-1");
        assertEquals("60", queue.getAttributes().get("VisibilityTimeout"));
    }

    @Test
    void createQueueWithTags_tagsReturnedByListQueueTags() {
        // Regression test for https://github.com/floci-io/floci/issues/699
        // Tags supplied at CreateQueue time must be visible via ListQueueTags.
        Map<String, String> tags = Map.of("k1", "v1", "k2", "v2");
        Queue queue = sqsService.createQueue("tagged-queue", null, tags, "us-east-1");
        String queueUrl = queue.getQueueUrl();

        Map<String, String> returned = sqsService.listQueueTags(queueUrl, "us-east-1");
        assertEquals(2, returned.size(), "ListQueueTags must return all tags set during CreateQueue");
        assertEquals("v1", returned.get("k1"));
        assertEquals("v2", returned.get("k2"));
    }

    @Test
    void deleteQueue() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.deleteQueue(queue.getQueueUrl(), region);
        assertThrows(AwsException.class, () ->
                sqsService.getQueueUrl("test-queue", region));
    }

    @Test
    void deleteQueueNotFound() {
        assertThrows(AwsException.class, () ->
                sqsService.deleteQueue(BASE_URL + "/000000000000/nonexistent", "eu-west-1"));
    }

    @Test
    void listQueues() {
        String region = "eu-west-1";
        sqsService.createQueue("alpha-queue", null, region);
        sqsService.createQueue("beta-queue", null, region);
        sqsService.createQueue("alpha-other", null, region);

        List<Queue> all = sqsService.listQueues(null, region);
        assertEquals(3, all.size());

        List<Queue> alpha = sqsService.listQueues("alpha", region);
        assertEquals(2, alpha.size());
    }

    @Test
    void getQueueUrl() {
        String region = "eu-west-1";
        sqsService.createQueue("my-queue", null, region);
        String url = sqsService.getQueueUrl("my-queue", region);
        assertEquals(BASE_URL + "/000000000000/my-queue", url);
    }

    @Test
    void getQueueUrlNotFound() {
        assertThrows(AwsException.class, () ->
                sqsService.getQueueUrl("nonexistent", "eu-west-1"));
    }

    @Test
    void sendAndReceiveMessage() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        Message sent = sqsService.sendMessage(queue.getQueueUrl(), "Hello World", 0, region);
        assertNotNull(sent.getMessageId());
        assertNotNull(sent.getMd5OfBody());

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, "eu-west-1");
        assertEquals(1, received.size());
        assertEquals("Hello World", received.getFirst().getBody());
        assertNotNull(received.getFirst().getReceiptHandle());
        assertEquals(1, received.getFirst().getReceiveCount());
    }

    @Test
    void receiveMessageReturnsEmptyWhenNoMessages() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("empty-queue", null, region);
        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertTrue(received.isEmpty());
    }

    @Test
    void messageBecomesInvisibleAfterReceive() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, region);

        // First receive should get the message
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertEquals(1, first.size());

        // Second receive should get nothing (message is invisible)
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertTrue(second.isEmpty());
    }

    @Test
    void deleteMessage() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "to-delete", 0, region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        sqsService.deleteMessage(queue.getQueueUrl(), received.getFirst().getReceiptHandle(), region);

        // Message should be permanently gone; even after visibility would expire
        // it shouldn't reappear
        List<Message> afterDelete = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertTrue(afterDelete.isEmpty());
    }

    @Test
    void deleteMessageInvalidHandle() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, "eu-west-1");
        assertThrows(AwsException.class, () ->
                sqsService.deleteMessage(queue.getQueueUrl(), "invalid-handle", region));
    }

    @Test
    void sendMessageToNonExistentQueue() {
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(BASE_URL + "/000000000000/nonexistent", "msg", 0, "eu-west-1"));
    }

    @Test
    void receiveMultipleMessages() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg3", 0, region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 3, 30, 0, region);
        assertEquals(3, received.size());
    }

    @Test
    void purgeQueue() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0, region);

        sqsService.purgeQueue(queue.getQueueUrl(), region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertTrue(received.isEmpty());
    }

    @Test
    void changeMessageVisibility() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        String receiptHandle = received.getFirst().getReceiptHandle();

        // Set visibility to 0 — message becomes visible immediately
        sqsService.changeMessageVisibility(queue.getQueueUrl(), receiptHandle, 0, region);

        List<Message> reReceived = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertEquals(1, reReceived.size());
    }

    @Test
    void getQueueAttributes() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertNotNull(attrs.get("QueueArn"));
        assertNotNull(attrs.get("CreatedTimestamp"));
        assertEquals("1", attrs.get("ApproximateNumberOfMessages"));
        assertEquals("0", attrs.get("ApproximateNumberOfMessagesNotVisible"));
        assertEquals("0", attrs.get("ApproximateNumberOfMessagesDelayed"),
                "The Delayed counter must always be present, \"0\" when nothing is delayed");
    }

    @Test
    void getQueueAttributesReportsDelayedMessages() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("delayed-attr-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 1, region);

        Map<String, String> whileDelayed =
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("0", whileDelayed.get("ApproximateNumberOfMessages"));
        assertEquals("0", whileDelayed.get("ApproximateNumberOfMessagesNotVisible"),
                "A delayed message is not in flight");
        assertEquals("1", whileDelayed.get("ApproximateNumberOfMessagesDelayed"));

        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Map<String, String> afterDelay =
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("1", afterDelay.get("ApproximateNumberOfMessages"));
        assertEquals("0", afterDelay.get("ApproximateNumberOfMessagesDelayed"),
                "The Delayed counter must drop back to 0 once the message becomes visible");
    }

    @Test
    void getQueueAttributesReportsInFlightSeparatelyFromDelayed() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("inflight-attr-queue", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        assertEquals(1, sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region).size());

        Map<String, String> attrs =
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("0", attrs.get("ApproximateNumberOfMessages"));
        assertEquals("1", attrs.get("ApproximateNumberOfMessagesNotVisible"));
        assertEquals("0", attrs.get("ApproximateNumberOfMessagesDelayed"),
                "An in-flight (received) message must not count as delayed");
    }

    @Test
    void getQueueAttributesReturnsDelayedWhenExplicitlyRequested() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("explicit-delayed-queue", null, region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("ApproximateNumberOfMessagesDelayed"), region);
        assertEquals(Map.of("ApproximateNumberOfMessagesDelayed", "0"), attrs);
    }

    // --- FIFO Queue Tests ---

    @Test
    void createFifoQueue() {
        Queue queue = sqsService.createQueue("test-queue.fifo", null, "eu-west-1");
        assertTrue(queue.isFifo());
        assertEquals("true", queue.getAttributes().get("FifoQueue"));
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithExplicitAttribute() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("FifoQueue", "true", "ContentBasedDeduplication", "true"), "eu-west-1");
        assertTrue(queue.isFifo());
        assertEquals("true", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithContentBasedDeduplicationFalse() {
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("ContentBasedDeduplication", "false"), "eu-west-1");
        assertTrue(queue.isFifo());
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithoutContentBasedDeduplication() {
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("VisibilityTimeout", "60"), "eu-west-1");
        assertTrue(queue.isFifo());
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithoutSuffixFails() {
        assertThrows(AwsException.class, () ->
                sqsService.createQueue("test-queue", Map.of("FifoQueue", "true"), "eu-west-1"));
    }

    @Test
    void sendMessageToFifoQueueRequiresGroupId() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, null, null, region));
    }

    @Test
    void sendMessageToFifoQueueWithContentBasedDedup() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);
        Message msg = sqsService.sendMessage(queue.getQueueUrl(), "Hello FIFO", 0, "group1", null, region);
        assertNotNull(msg.getMessageId());
        assertEquals("group1", msg.getMessageGroupId());
        assertTrue(msg.getSequenceNumber() > 0);
        assertNotNull(msg.getMessageDeduplicationId());
    }

    @Test
    void sendMessageToFifoQueueWithExplicitDedupId() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo", null, region);
        Message msg = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertEquals("dedup-1", msg.getMessageDeduplicationId());
    }

    @Test
    void fifoDeduplicationReturnsExistingMessage() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo", null, region);
        Message msg1 = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        Message msg2 = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertEquals(msg1.getMessageId(), msg2.getMessageId());

        // Only one message should be in the queue
        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, received.size());
    }

    @Test
    void fifoQueueReceiveReturnsMultipleMessagesPerGroupInOrder() {
        // AWS FIFO: a single ReceiveMessage call may return multiple messages
        // from the same MessageGroupId (in order), up to MaxNumberOfMessages.
        // The group lock only blocks subsequent ReceiveMessage calls.
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg1", 0, "group1", "d1", region);
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg2", 0, "group1", "d2", region);
        sqsService.sendMessage(queue.getQueueUrl(), "g2-msg1", 0, "group2", "d3", region);

        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(3, first.size(),
                "Single FIFO ReceiveMessage should drain all visible messages up to MaxNumberOfMessages");
        List<String> bodies = first.stream().map(Message::getBody).toList();
        // Inter-group ordering is not guaranteed by FIFO; only within-group order is.
        assertTrue(bodies.contains("g2-msg1"), "batch must contain group2 message");
        int g1m1Idx = bodies.indexOf("g1-msg1");
        int g1m2Idx = bodies.indexOf("g1-msg2");
        assertTrue(g1m1Idx >= 0 && g1m2Idx >= 0, "batch must contain both group1 messages");
        assertTrue(g1m1Idx < g1m2Idx, "group1 messages must be in insertion order");

        // Both groups are now in-flight; second call returns empty.
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertTrue(second.isEmpty());
    }

    @Test
    void fifoQueueRequiresDedupIdWhenContentBasedDisabled() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("test.fifo", null, region);
        // ContentBasedDeduplication is false by default
        assertThrows(AwsException.class, () ->
                sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", null, region));
    }

    @Test
    void receiveMessageUsesQueueVisibilityTimeoutWhenNotSpecified() {
        String region = "eu-west-1";

        // Create queue with a short visibility timeout (1 second)
        Queue queue = sqsService.createQueue("short-vt-queue",
                Map.of("VisibilityTimeout", "1"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "test-msg", 0, region);

        // Receive without specifying visibility timeout (-1 means "use queue default")
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0, region);
        assertEquals(1, first.size());

        // Message should be invisible immediately after receive
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0, region);
        assertTrue(second.isEmpty());

        // Wait for the queue's visibility timeout (1s) to expire, not the global default (30s)
        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Message should now be visible again
        List<Message> third = sqsService.receiveMessage(queue.getQueueUrl(), 1, -1, 0, region);
        assertEquals(1, third.size(), "Message should become visible after queue's VisibilityTimeout (1s), not global default (30s)");
    }

    // --- Queue-level DelaySeconds for FIFO queues (issue #475) ---

    @Test
    void queueLevelDelaySecondsAppliesToFifoQueue() {
        String region = "eu-west-1";

        Queue queue = sqsService.createQueue("delay-fifo.fifo",
                Map.of("ContentBasedDeduplication", "true", "DelaySeconds", "1"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", null, region);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertTrue(immediate.isEmpty(),
                "FIFO queue should honor queue-level DelaySeconds (issue #475)");

        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<Message> later = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertEquals(1, later.size(),
                "Message should become visible once DelaySeconds elapses");
    }

    @Test
    void fifoQueueIgnoresPerMessageDelaySeconds() {
        // AWS SQS FIFO queues only support queue-level DelaySeconds; any
        // per-message value is ignored. Here the queue default is 0 and the
        // caller passes a positive per-message delay -- the message must be
        // immediately visible.
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("fifo-ignores-per-msg.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 60, "group1", null, region);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertEquals(1, immediate.size(),
                "FIFO queues must ignore per-message DelaySeconds");
    }

    // --- Queue-level DelaySeconds for standard queues ---

    @Test
    void queueLevelDelaySecondsAppliesToStandardQueue() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("delay-standard",
                Map.of("DelaySeconds", "1"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", null, region);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertTrue(immediate.isEmpty(),
                "Standard queue should honor queue-level DelaySeconds");

        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<Message> later = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertEquals(1, later.size(),
                "Message should become visible once DelaySeconds elapses");
    }

    @Test
    void explicitZeroDelayOverridesQueueDefault() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("delay-override",
                Map.of("DelaySeconds", "10"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);

        List<Message> immediate = sqsService.receiveMessage(queue.getQueueUrl(), 1, 0, 0, region);
        assertEquals(1, immediate.size(),
                "Explicit DelaySeconds=0 must override queue-level default");
    }

    // --- clearFifoDeduplicationCacheOnPurge tests ---

    @Test
    void purgeQueueClearsFifoDeduplicationCacheWhenEnabled() {
        String region = "eu-west-1";
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        final var queue = service.createQueue("dedup-clear.fifo", Map.of("ContentBasedDeduplication", "true"), region);

        // First send — message M1 added, dedup cache populated with "dedup-1"
        final var m1 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertNotNull(m1.getMessageId());

        // Purge clears both messages and the dedup cache
        service.purgeQueue(queue.getQueueUrl(), region);
        assertTrue(service.receiveMessage(queue.getQueueUrl(), 10, 0, 0, region).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with the same dedup ID — cache was cleared so this is treated as a fresh send
        final var m2 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertNotNull(m2.getMessageId());

        final var received = service.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, received.size(), "One message must be in the queue after re-send");

        // Third send with same dedup ID — fresh cache entry from m2 deduplicates correctly
        final var m3 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertEquals(m2.getMessageId(), m3.getMessageId(),
                "Dedup must work with the fresh cache entry after purge");
    }

    @Test
    void purgeQueuePreservesFifoDeduplicationCacheByDefault() {
        String region = "eu-west-1";
        // Default service has clearFifoDeduplicationCacheOnPurge=false
        final var queue = sqsService.createQueue("dedup-preserve.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);

        // Send and then purge — messages are gone but dedup cache is intact
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        sqsService.purgeQueue(queue.getQueueUrl(), region);

        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 0, 0, region).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with same dedup ID — dedup cache fires but finds no message (purged),
        // so it falls through and creates a new message
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);

        final var received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, received.size(),
                "Re-send after purge must produce exactly one message in the queue");
    }

    @Test
    void purgeQueueClearsDedupStoreWhenEnabled() {
        String region = "eu-west-1";

        final var dedupStore = new InMemoryStorage<String, Map<String, Long>>();
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), dedupStore,
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        final var queue = service.createQueue("dedup-store-clear.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);

        // Send a message — dedup entry must be persisted to the store
        service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertFalse(dedupStore.keys().isEmpty(),
                "Dedup store must have an entry after sending a FIFO message");

        // Purge with flag enabled — dedupStore entry for the queue must be removed
        service.purgeQueue(queue.getQueueUrl(), region);
        assertTrue(dedupStore.keys().isEmpty(),
                "Dedup store must be empty after purge with clearFifoDeduplicationCacheOnPurge=true");
    }

    @Test
    void sendMessage_usesQueueMaximumMessageSizeAttribute() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("big-queue",
                Map.of("MaximumMessageSize", "524288"), region);
        String body = "x".repeat(300_000);

        assertDoesNotThrow(() -> sqsService.sendMessage(queue.getQueueUrl(), body, 0, region),
                "Body within the queue's MaximumMessageSize must be accepted");
    }

    @Test
    void sendMessage_oversize_errorReportsQueueLimit() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("limited-queue",
                Map.of("MaximumMessageSize", "2048"), region);
        String oversized = "x".repeat(3000);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.sendMessage(queue.getQueueUrl(), oversized, 0, region));
        assertTrue(ex.getMessage().contains("2048"),
                "Error message must reference the queue's configured MaximumMessageSize, got: " + ex.getMessage());
    }

    @Test
    void sendMessage_attributesCountTowardsLimit() {
        Queue queue = sqsService.createQueue("attr-limit-queue",
                Map.of("MaximumMessageSize", "2048"), "us-west-1");
        String body = "x".repeat(2040);
        Map<String, MessageAttributeValue> attrs = Map.of(
                "key", new MessageAttributeValue("value", "String"));

        // Body alone fits; body + attributes exceed the 2048 byte limit.
        assertThrows(AwsException.class,
                () -> sqsService.sendMessage(queue.getQueueUrl(), body, 0, null, null, attrs, "us-east-1"));
    }

    @Test
    void addPermission_appendsLabelledStatementToPolicy() {
        Queue queue = sqsService.createQueue("perm-queue", null, "us-east-1");
        sqsService.addPermission(queue.getQueueUrl(), "share",
                List.of("111122223333"), List.of("SendMessage", "ReceiveMessage"), "us-east-1");

        String policy = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("Policy"), "us-east-1").get("Policy");
        assertNotNull(policy, "Policy attribute must be set after AddPermission");
        assertTrue(policy.contains("\"Sid\":\"share\""));
        assertTrue(policy.contains("arn:aws:iam::111122223333:root"));
        assertTrue(policy.contains("SQS:SendMessage"));
        assertTrue(policy.contains("SQS:ReceiveMessage"));
    }

    @Test
    void addPermission_duplicateLabel_throws() {
        Queue queue = sqsService.createQueue("perm-queue", null, "us-east-1");
        sqsService.addPermission(queue.getQueueUrl(), "share",
                List.of("111122223333"), List.of("SendMessage"), "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.addPermission(queue.getQueueUrl(), "share",
                        List.of("444455556666"), List.of("ReceiveMessage"), "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void addPermission_queueDoesNotExist_throws() {
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.addPermission(BASE_URL + "/000000000000/missing", "share",
                        List.of("111122223333"), List.of("SendMessage"), "us-east-1"));
        assertEquals("AWS.SimpleQueueService.NonExistentQueue", ex.getErrorCode());
    }

    @Test
    void removePermission_removesLabelledStatement() {
        Queue queue = sqsService.createQueue("perm-queue", null, "us-east-1");
        sqsService.addPermission(queue.getQueueUrl(), "a",
                List.of("111122223333"), List.of("SendMessage"), "us-east-1");
        sqsService.addPermission(queue.getQueueUrl(), "b",
                List.of("444455556666"), List.of("ReceiveMessage"), "us-east-1");

        sqsService.removePermission(queue.getQueueUrl(), "a", "us-east-1");

        String policy = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("Policy"), "us-east-1").get("Policy");
        assertNotNull(policy);
        assertFalse(policy.contains("\"Sid\":\"a\""));
        assertTrue(policy.contains("\"Sid\":\"b\""));
    }

    @Test
    void removePermission_lastStatement_removesPolicyAttribute() {
        Queue queue = sqsService.createQueue("perm-queue", null, "us-east-1");
        sqsService.addPermission(queue.getQueueUrl(), "only",
                List.of("111122223333"), List.of("SendMessage"), "us-east-1");
        sqsService.removePermission(queue.getQueueUrl(), "only", "us-east-1");

        String policy = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("Policy"), "us-east-1").get("Policy");
        assertNull(policy, "Policy must be removed when last statement is dropped");
    }

    @Test
    void removePermission_unknownLabel_throws() {
        Queue queue = sqsService.createQueue("perm-queue", null, "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.removePermission(queue.getQueueUrl(), "ghost", "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void removePermission_queueDoesNotExist_throws() {
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.removePermission(BASE_URL + "/000000000000/missing", "share", "us-east-1"));
        assertEquals("AWS.SimpleQueueService.NonExistentQueue", ex.getErrorCode());
    }

    @Test
    void fifoQueueGroupLockBlocksAcrossCallsButNotWithin() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("group-lock.fifo", null, region);
        for (int i = 1; i <= 5; i++) {
            sqsService.sendMessage(queue.getQueueUrl(), "msg" + i, 0, "g1", "d" + i, region);
        }

        // First call drains all five from the single group.
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(5, first.size());

        // Second call returns nothing because g1 is in-flight.
        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region).isEmpty());
    }

    @Test
    void fifoQueueGroupUnlocksAfterAllInFlightMessagesDeleted() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("unlock-test.fifo", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, "g1", "d1", region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0, "g1", "d2", region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg3", 0, "g1", "d3", region);

        // Partial drain: MaxNumberOfMessages=2 returns msg1 + msg2
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 2, 30, 0, region);
        assertEquals(2, first.size());
        assertEquals("msg1", first.get(0).getBody());
        assertEquals("msg2", first.get(1).getBody());

        // Group still locked — msg3 is not returned
        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region).isEmpty());

        // Delete both in-flight messages → group unlocks
        sqsService.deleteMessage(queue.getQueueUrl(), first.get(0).getReceiptHandle(), region);
        sqsService.deleteMessage(queue.getQueueUrl(), first.get(1).getReceiptHandle(), region);

        // Now msg3 should be available
        List<Message> third = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, third.size());
        assertEquals("msg3", third.get(0).getBody());
    }

    private static String queueArn(String name) {
        return "arn:aws:sqs:us-east-1:000000000000:" + name;
    }

    @Test
    void startMessageMoveTask_storesTaskRetrievableByListMessageMoveTasks() {
        sqsService.createQueue("orders-dlq", null, "us-east-1");
        String dlqArn = queueArn("orders-dlq");
        sqsService.createQueue("orders",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");
        sqsService.createQueue("orders-replay", null, "us-east-1");
        String destArn = queueArn("orders-replay");

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, destArn, 25, "us-east-1");

        assertNotNull(taskHandle);
        List<SqsService.MoveTask> tasks = sqsService.listMessageMoveTasks(dlqArn, "us-east-1");
        SqsService.MoveTask task = tasks.get(0);
        assertEquals(taskHandle, task.taskHandle());
        assertEquals(dlqArn, task.sourceArn());
        assertEquals(destArn, task.destinationArn());
        assertEquals(25, task.maxNumberOfMessagesPerSecond());
    }

    @Test
    void startMessageMoveTask_destinationDoesNotExist_throwsResourceNotFound() {
        sqsService.createQueue("a-dlq", null, "us-east-1");
        String dlqArn = queueArn("a-dlq");
        sqsService.createQueue("a",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.startMessageMoveTask(dlqArn, queueArn("nope"), 0, "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void startMessageMoveTask_sourceIsNotDeadLetterQueue_throwsInvalidParameterValue() {
        sqsService.createQueue("just-a-queue", null, "us-east-1");
        sqsService.createQueue("dest", null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.startMessageMoveTask(queueArn("just-a-queue"),
                        queueArn("dest"), 0, "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void startMessageMoveTask_doesNotMatchSubstringOfAnotherQueueArn() {
        // Regression: isDeadLetterQueue / listDeadLetterSourceQueues used to
        // substring-match the raw RedrivePolicy JSON, so a queue named "foo"
        // would be falsely treated as a DLQ when another queue's redrive policy
        // referenced ":foo-bar". Now both look at the parsed deadLetterTargetArn.
        sqsService.createQueue("dlq-real", null, "us-east-1");
        String realDlqArn = queueArn("dlq-real");
        sqsService.createQueue("main-real",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + realDlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");

        // Create a queue whose ARN is a strict prefix of realDlqArn.
        sqsService.createQueue("dlq", null, "us-east-1");
        String prefixArn = queueArn("dlq");
        sqsService.createQueue("dest", null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.startMessageMoveTask(prefixArn, queueArn("dest"), 0, "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());

        // listDeadLetterSourceQueues should also only return source queues whose
        // redrive policy references the exact ARN, not a substring match.
        assertEquals(0, sqsService.listDeadLetterSourceQueues(
                sqsService.getQueueUrl("dlq", "us-east-1"), "us-east-1").size());
        assertEquals(1, sqsService.listDeadLetterSourceQueues(
                sqsService.getQueueUrl("dlq-real", "us-east-1"), "us-east-1").size());
    }

    @Test
    void startMessageMoveTask_concurrentSecondTaskOnSameSource_throwsInvalidParameterValue() {
        sqsService.createQueue("d-concurrent", null, "us-east-1");
        String dlqArn = queueArn("d-concurrent");
        sqsService.createQueue("p-concurrent",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");
        sqsService.createQueue("dest-concurrent", null, "us-east-1");
        String destArn = queueArn("dest-concurrent");

        sqsService.startMessageMoveTask(dlqArn, destArn, 0, "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.startMessageMoveTask(dlqArn, destArn, 0, "us-east-1"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertTrue(ex.getMessage().toLowerCase().contains("already a task running"));
    }

    @Test
    void cancelMessageMoveTask_unknownHandle_throwsResourceNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.cancelMessageMoveTask("task-does-not-exist", "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void cancelMessageMoveTask_knownHandle_stopsBackgroundWorker() throws Exception {
        Queue dlq = sqsService.createQueue("d", null, "us-east-1");
        String dlqArn = queueArn("d");
        sqsService.createQueue("p",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");
        sqsService.createQueue("dest", null, "us-east-1");
        String destArn = queueArn("dest");

        // Load enough messages that, at the throttled rate, the worker can't possibly
        // drain the queue before cancel is observed.
        for (int i = 0; i < 50; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null, "us-east-1");
        }

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, destArn, 1, "us-east-1");
        sqsService.cancelMessageMoveTask(taskHandle, "us-east-1");

        // Give the worker a moment to observe the cancel and write the terminal status.
        for (int i = 0; i < 50; i++) {
            var status = sqsService.listMessageMoveTasks(dlqArn, "us-east-1").get(0).status();
            if ("CANCELLED".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Move task did not transition to CANCELLED within timeout");
    }

    @Test
    void fifoDedup_scopedToMessageGroup_acceptsSameDedupIdAcrossGroups() {
        String region = "eu-west-1";
        Queue fifo = sqsService.createQueue("fair.fifo",
                Map.of("FifoQueue", "true",
                        "DeduplicationScope", "messageGroup",
                        "FifoThroughputLimit", "perMessageGroupId"), region);
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "groupA", "sameDedup", region);
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "groupB", "sameDedup", region);
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0, region);
        assertEquals(2, received.size());
    }

    @Test
    void fifoDedup_scopedToMessageGroup_dedupKeyHasNoCollisionFromDelimiterInIds() {
        // AWS allows '|' (and other punctuation) in both MessageGroupId and
        // MessageDeduplicationId, so a naive "group|dedup" cache key could collide:
        // (group="a",   dedup="b|c") and (group="a|b", dedup="c") both yield "a|b|c".
        // Both pairs must be treated as distinct messages.
        String region = "eu-west-1";
        Queue fifo = sqsService.createQueue("collision.fifo",
                Map.of("FifoQueue", "true",
                        "DeduplicationScope", "messageGroup",
                        "FifoThroughputLimit", "perMessageGroupId"), region);
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "a", "b|c", region);
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "a|b", "c", region);
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0, region);
        assertEquals(2, received.size());
    }

    @Test
    void fifoDedup_queueScope_rejectsSameDedupIdAcrossGroups() {
        String region = "eu-west-1";
        Queue fifo = sqsService.createQueue("queue-scoped.fifo",
                Map.of("FifoQueue", "true"), region);
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "groupA", "sameDedup", region);
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "groupB", "sameDedup", region);
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, received.size());
    }

    @Test
    void validateBatchPayloadSize_underQueueLimit_succeeds() {
        Queue queue = sqsService.createQueue("batch-q", null, "us-east-1");
        sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 100_000);
    }

    @Test
    void validateBatchPayloadSize_overQueueLimit_throwsBatchRequestTooLong() {
        Queue queue = sqsService.createQueue("batch-q", null, "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 1_100_000));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("1048576"));
    }

    @Test
    void validateBatchPayloadSize_respectsCustomMaximumMessageSize() {
        Queue queue = sqsService.createQueue("batch-q",
                Map.of("MaximumMessageSize", "1048576"), "us-east-1");
        sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 1_000_000);
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 1_048_577));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
    }

    @Test
    void sendMessage_withAwsTraceHeader_isStoredAndReturnedOnReceive() {
        Queue q = sqsService.createQueue("traced", null, "us-east-1");
        sqsService.sendMessage(q.getQueueUrl(), "hi", 0, null, null, null,
                "Root=1-abc-def", "us-east-1");
        var received = sqsService.receiveMessage(q.getQueueUrl(), 1, 30, 0, "us-east-1");
        assertEquals(1, received.size());
        assertEquals("Root=1-abc-def", received.get(0).getAwsTraceHeader());
    }

    @Test
    void purgeQueueWithClearFifoDelegatesToSnsForFifoDedupOnSubscribedTopics() {
        String region = "us-east-1";
        final var sns = mock(SnsService.class);
        final var service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, sns);
        final var queue = service.createQueue("sns-dedup-delegate.fifo", Map.of("FifoQueue", "true"),region);
        service.purgeQueue(queue.getQueueUrl(), region);
        verify(sns).clearFifoDeduplicationCacheForSqsQueueSubscriptions(
                queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void sendAndReceiveMessage_bareQueueName() {
        String region = "eu-west-1";
        sqsService.createQueue("bare-name-queue", null, region);

        Message sent = sqsService.sendMessage("bare-name-queue", "hello", 0, region);
        assertNotNull(sent.getMessageId());

        List<Message> received = sqsService.receiveMessage("bare-name-queue", 1, 30, 0, region);
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).getBody());
    }

    @Test
    void deleteQueue_bareQueueName() {
        String region = "eu-west-1";
        sqsService.createQueue("delete-bare", null, region);
        assertDoesNotThrow(() -> sqsService.deleteQueue("delete-bare", region));
        assertThrows(AwsException.class, () -> sqsService.deleteQueue("delete-bare", region));
    }

    @Test
    void getQueueAttributes_bareQueueName() {
        String region = "eu-west-1";
        sqsService.createQueue("attrs-bare", Map.of("VisibilityTimeout", "45"), region);
        Map<String, String> attrs = sqsService.getQueueAttributes("attrs-bare", List.of("VisibilityTimeout"), region);
        assertEquals("45", attrs.get("VisibilityTimeout"));
    }

    // --- DeleteQueue releases in-flight ReceiveMessage long polls ---

    @Test
    void deleteQueueReleasesParkedLongPoll() throws InterruptedException {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("longpoll-release-queue", null, region);
        String queueUrl = queue.getQueueUrl();

        final var pollerEntered = new CountDownLatch(1);
        final var staleResult = new AtomicReference<List<Message>>();
        Thread stalePoller = new Thread(() -> {
            pollerEntered.countDown();
            staleResult.set(sqsService.receiveMessage(queueUrl, 1, 30, 8, region));
        });
        stalePoller.start();
        assertTrue(pollerEntered.await(5, TimeUnit.SECONDS));
        Thread.sleep(300); // let the poller park inside its long-poll wait

        sqsService.deleteQueue(queueUrl, region);
        stalePoller.join(2000);

        assertFalse(stalePoller.isAlive(),
                "DeleteQueue must release a parked long poll promptly, not leave it to wait out its full WaitTimeSeconds");
        assertTrue(staleResult.get().isEmpty(),
                "A long poll released by DeleteQueue must complete without messages");
    }

    @Test
    void staleLongPollMustNotConsumeMessagesFromARecreatedQueue() throws InterruptedException {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("recreate-longpoll-queue", null, region);
        String queueUrl = queue.getQueueUrl();

        // A long poll opened before the delete, e.g. a listener generation
        // that is being torn down while its queue is recreated.
        final var pollerEntered = new CountDownLatch(1);
        final var staleResult = new AtomicReference<List<Message>>();
        Thread stalePoller = new Thread(() -> {
            pollerEntered.countDown();
            staleResult.set(sqsService.receiveMessage(queueUrl, 1, 30, 8, region));
        });
        stalePoller.start();
        assertTrue(pollerEntered.await(5, TimeUnit.SECONDS));
        Thread.sleep(300); // let the poller park inside its long-poll wait

        sqsService.deleteQueue(queueUrl, region);
        Queue recreated = sqsService.createQueue("recreate-longpoll-queue", null, region);
        sqsService.sendMessage(recreated.getQueueUrl(), "for-the-new-queue", 0, region);
        // Give a surviving stale poll every chance to (incorrectly) grab the
        // message before the legitimate receive arrives.
        Thread.sleep(200);

        List<Message> fresh = sqsService.receiveMessage(recreated.getQueueUrl(), 1, 30, 1, region);
        stalePoller.join(9000);
        assertFalse(stalePoller.isAlive());

        assertEquals(1, fresh.size(),
                "A receive opened after the recreate must get the new queue's message");
        assertEquals("for-the-new-queue", fresh.get(0).getBody());
        assertEquals(1, fresh.get(0).getReceiveCount(),
                "The delivery to the live receiver must be the first receive (ApproximateReceiveCount = 1)");
        assertTrue(staleResult.get().isEmpty(),
                "The pre-delete long poll must not consume the recreated queue's delivery");
    }
}
