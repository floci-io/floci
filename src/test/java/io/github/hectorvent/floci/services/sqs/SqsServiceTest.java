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
    void createFifoQueueWithContentBasedDeduplicationFalse() {
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("ContentBasedDeduplication", "false"));
        assertTrue(queue.isFifo());
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
    }

    @Test
    void createFifoQueueWithoutContentBasedDeduplication() {
        Queue queue = sqsService.createQueue("test-queue.fifo",
                Map.of("VisibilityTimeout", "60"));
        assertTrue(queue.isFifo());
        assertEquals("false", queue.getAttributes().get("ContentBasedDeduplication"));
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
    void fifoQueueReceiveReturnsMultipleMessagesPerGroupInOrder() {
        // AWS FIFO: a single ReceiveMessage call may return multiple messages
        // from the same MessageGroupId (in order), up to MaxNumberOfMessages.
        // The group lock only blocks subsequent ReceiveMessage calls.
        Queue queue = sqsService.createQueue("test.fifo", null);
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg1", 0, "group1", "d1");
        sqsService.sendMessage(queue.getQueueUrl(), "g1-msg2", 0, "group1", "d2");
        sqsService.sendMessage(queue.getQueueUrl(), "g2-msg1", 0, "group2", "d3");

        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
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
        List<Message> second = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertTrue(second.isEmpty());
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
    void sendMessage_usesQueueMaximumMessageSizeAttribute() {
        Queue queue = sqsService.createQueue("big-queue",
                Map.of("MaximumMessageSize", "524288"));
        String body = "x".repeat(300_000);

        assertDoesNotThrow(() -> sqsService.sendMessage(queue.getQueueUrl(), body, 0),
                "Body within the queue's MaximumMessageSize must be accepted");
    }

    @Test
    void sendMessage_oversize_errorReportsQueueLimit() {
        Queue queue = sqsService.createQueue("limited-queue",
                Map.of("MaximumMessageSize", "2048"));
        String oversized = "x".repeat(3000);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.sendMessage(queue.getQueueUrl(), oversized, 0));
        assertTrue(ex.getMessage().contains("2048"),
                "Error message must reference the queue's configured MaximumMessageSize, got: " + ex.getMessage());
    }

    @Test
    void sendMessage_attributesCountTowardsLimit() {
        Queue queue = sqsService.createQueue("attr-limit-queue",
                Map.of("MaximumMessageSize", "2048"));
        String body = "x".repeat(2040);
        Map<String, MessageAttributeValue> attrs = Map.of(
                "key", new MessageAttributeValue("value", "String"));

        // Body alone fits; body + attributes exceed the 2048 byte limit.
        assertThrows(AwsException.class,
                () -> sqsService.sendMessage(queue.getQueueUrl(), body, 0, null, null, attrs, "us-east-1"));
    }

    @Test
    void addPermission_appendsLabelledStatementToPolicy() {
        Queue queue = sqsService.createQueue("perm-queue", null);
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
        Queue queue = sqsService.createQueue("perm-queue", null);
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
        Queue queue = sqsService.createQueue("perm-queue", null);
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
        Queue queue = sqsService.createQueue("perm-queue", null);
        sqsService.addPermission(queue.getQueueUrl(), "only",
                List.of("111122223333"), List.of("SendMessage"), "us-east-1");
        sqsService.removePermission(queue.getQueueUrl(), "only", "us-east-1");

        String policy = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("Policy"), "us-east-1").get("Policy");
        assertNull(policy, "Policy must be removed when last statement is dropped");
    }

    @Test
    void removePermission_unknownLabel_throws() {
        Queue queue = sqsService.createQueue("perm-queue", null);
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
        Queue queue = sqsService.createQueue("group-lock.fifo", null);
        for (int i = 1; i <= 5; i++) {
            sqsService.sendMessage(queue.getQueueUrl(), "msg" + i, 0, "g1", "d" + i);
        }

        // First call drains all five from the single group.
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(5, first.size());

        // Second call returns nothing because g1 is in-flight.
        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0).isEmpty());
    }

    @Test
    void fifoQueueGroupUnlocksAfterAllInFlightMessagesDeleted() {
        Queue queue = sqsService.createQueue("unlock-test.fifo", null);
        sqsService.sendMessage(queue.getQueueUrl(), "msg1", 0, "g1", "d1");
        sqsService.sendMessage(queue.getQueueUrl(), "msg2", 0, "g1", "d2");
        sqsService.sendMessage(queue.getQueueUrl(), "msg3", 0, "g1", "d3");

        // Partial drain: MaxNumberOfMessages=2 returns msg1 + msg2
        List<Message> first = sqsService.receiveMessage(queue.getQueueUrl(), 2, 30, 0);
        assertEquals(2, first.size());
        assertEquals("msg1", first.get(0).getBody());
        assertEquals("msg2", first.get(1).getBody());

        // Group still locked — msg3 is not returned
        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0).isEmpty());

        // Delete both in-flight messages → group unlocks
        sqsService.deleteMessage(queue.getQueueUrl(), first.get(0).getReceiptHandle());
        sqsService.deleteMessage(queue.getQueueUrl(), first.get(1).getReceiptHandle());

        // Now msg3 should be available
        List<Message> third = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0);
        assertEquals(1, third.size());
        assertEquals("msg3", third.get(0).getBody());
    }

    private static String queueArn(String name) {
        return "arn:aws:sqs:us-east-1:000000000000:" + name;
    }

    @Test
    void startMessageMoveTask_storesTaskRetrievableByListMessageMoveTasks() {
        sqsService.createQueue("orders-dlq", null);
        String dlqArn = queueArn("orders-dlq");
        sqsService.createQueue("orders",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"));
        sqsService.createQueue("orders-replay", null);
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
        sqsService.createQueue("a-dlq", null);
        String dlqArn = queueArn("a-dlq");
        sqsService.createQueue("a",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"));

        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.startMessageMoveTask(dlqArn, queueArn("nope"), 0, "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void startMessageMoveTask_sourceIsNotDeadLetterQueue_throwsInvalidParameterValue() {
        sqsService.createQueue("just-a-queue", null);
        sqsService.createQueue("dest", null);

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
        sqsService.createQueue("dlq-real", null);
        String realDlqArn = queueArn("dlq-real");
        sqsService.createQueue("main-real",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + realDlqArn + "\",\"maxReceiveCount\":\"1\"}"));

        // Create a queue whose ARN is a strict prefix of realDlqArn.
        sqsService.createQueue("dlq", null);
        String prefixArn = queueArn("dlq");
        sqsService.createQueue("dest", null);

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
        sqsService.createQueue("d-concurrent", null);
        String dlqArn = queueArn("d-concurrent");
        sqsService.createQueue("p-concurrent",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"));
        sqsService.createQueue("dest-concurrent", null);
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
        Queue dlq = sqsService.createQueue("d", null);
        String dlqArn = queueArn("d");
        sqsService.createQueue("p",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"));
        sqsService.createQueue("dest", null);
        String destArn = queueArn("dest");

        // Load enough messages that, at the throttled rate, the worker can't possibly
        // drain the queue before cancel is observed.
        for (int i = 0; i < 50; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null);
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
        Queue fifo = sqsService.createQueue("fair.fifo",
                Map.of("FifoQueue", "true",
                        "DeduplicationScope", "messageGroup",
                        "FifoThroughputLimit", "perMessageGroupId"));
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "groupA", "sameDedup");
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "groupB", "sameDedup");
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0);
        assertEquals(2, received.size());
    }

    @Test
    void fifoDedup_scopedToMessageGroup_dedupKeyHasNoCollisionFromDelimiterInIds() {
        // AWS allows '|' (and other punctuation) in both MessageGroupId and
        // MessageDeduplicationId, so a naive "group|dedup" cache key could collide:
        // (group="a",   dedup="b|c") and (group="a|b", dedup="c") both yield "a|b|c".
        // Both pairs must be treated as distinct messages.
        Queue fifo = sqsService.createQueue("collision.fifo",
                Map.of("FifoQueue", "true",
                        "DeduplicationScope", "messageGroup",
                        "FifoThroughputLimit", "perMessageGroupId"));
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "a", "b|c");
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "a|b", "c");
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0);
        assertEquals(2, received.size());
    }

    @Test
    void fifoDedup_queueScope_rejectsSameDedupIdAcrossGroups() {
        Queue fifo = sqsService.createQueue("queue-scoped.fifo",
                Map.of("FifoQueue", "true"));
        sqsService.sendMessage(fifo.getQueueUrl(), "A", 0, "groupA", "sameDedup");
        sqsService.sendMessage(fifo.getQueueUrl(), "B", 0, "groupB", "sameDedup");
        List<Message> received = sqsService.receiveMessage(fifo.getQueueUrl(), 10, 30, 0);
        assertEquals(1, received.size());
    }

    @Test
    void validateBatchPayloadSize_underQueueLimit_succeeds() {
        Queue queue = sqsService.createQueue("batch-q", null);
        sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 100_000);
    }

    @Test
    void validateBatchPayloadSize_overQueueLimit_throwsBatchRequestTooLong() {
        Queue queue = sqsService.createQueue("batch-q", null);
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 300_000));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("262144"));
    }

    @Test
    void validateBatchPayloadSize_respectsCustomMaximumMessageSize() {
        Queue queue = sqsService.createQueue("batch-q",
                Map.of("MaximumMessageSize", "1048576"));
        sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 1_000_000);
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.validateBatchPayloadSize(queue.getQueueUrl(), "us-east-1", 1_048_577));
        assertEquals("BatchRequestTooLong", ex.getErrorCode());
    }

    @Test
    void sendMessage_withAwsTraceHeader_isStoredAndReturnedOnReceive() {
        Queue q = sqsService.createQueue("traced", null);
        sqsService.sendMessage(q.getQueueUrl(), "hi", 0, null, null, null,
                "Root=1-abc-def", "us-east-1");
        var received = sqsService.receiveMessage(q.getQueueUrl(), 1, 30, 0);
        assertEquals(1, received.size());
        assertEquals("Root=1-abc-def", received.get(0).getAwsTraceHeader());
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

    @Test
    void sendAndReceiveMessage_bareQueueName() {
        sqsService.createQueue("bare-name-queue", null);

        Message sent = sqsService.sendMessage("bare-name-queue", "hello", 0);
        assertNotNull(sent.getMessageId());

        List<Message> received = sqsService.receiveMessage("bare-name-queue", 1, 30, 0);
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).getBody());
    }

    @Test
    void deleteQueue_bareQueueName() {
        sqsService.createQueue("delete-bare", null);
        assertDoesNotThrow(() -> sqsService.deleteQueue("delete-bare"));
        assertThrows(AwsException.class, () -> sqsService.deleteQueue("delete-bare"));
    }

    @Test
    void getQueueAttributes_bareQueueName() {
        sqsService.createQueue("attrs-bare", Map.of("VisibilityTimeout", "45"));
        Map<String, String> attrs = sqsService.getQueueAttributes("attrs-bare", List.of("VisibilityTimeout"));
        assertEquals("45", attrs.get("VisibilityTimeout"));
    }
}
