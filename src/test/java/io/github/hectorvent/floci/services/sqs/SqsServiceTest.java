package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.MutableClock;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsServiceTest {

    private SqsService sqsService;
    private MutableClock clock;
    private static final String BASE_URL = "http://localhost:4566";

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        sqsService = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, clock);
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
    void getQueueAttributes_defaultsMatchAws() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("defaults-queue", null, region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("1048576", attrs.get("MaximumMessageSize"),
                "MaximumMessageSize must default to the AWS value of 1048576 bytes");
        assertEquals("true", attrs.get("SqsManagedSseEnabled"),
                "A queue without a KMS key reports SSE-SQS enabled");
        assertEquals("30", attrs.get("VisibilityTimeout"));
        assertEquals("345600", attrs.get("MessageRetentionPeriod"));
        assertEquals("0", attrs.get("DelaySeconds"));
        assertEquals("0", attrs.get("ReceiveMessageWaitTimeSeconds"));
        assertNotNull(attrs.get("QueueArn"));
        assertNotNull(attrs.get("CreatedTimestamp"));
        assertNotNull(attrs.get("LastModifiedTimestamp"));
        assertNotNull(attrs.get("ApproximateNumberOfMessages"));
        assertNotNull(attrs.get("ApproximateNumberOfMessagesNotVisible"));
        assertNotNull(attrs.get("ApproximateNumberOfMessagesDelayed"));
        assertFalse(attrs.containsKey("Policy"), "Policy is only returned once set");
        assertFalse(attrs.containsKey("RedrivePolicy"), "RedrivePolicy is only returned once set");
    }

    @Test
    void getQueueAttributes_sqsManagedSseDisabledWhenKmsKeyIsSet() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("kms-queue", null, region);
        sqsService.setQueueAttributes(queue.getQueueUrl(),
                Map.of("KmsMasterKeyId", "alias/aws/sqs"), region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("false", attrs.get("SqsManagedSseEnabled"),
                "A KMS master key takes over from SSE-SQS");
    }

    @Test
    void getQueueAttributes_sqsManagedSseDisabledWhenQueueIsCreatedWithAKmsKey() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("kms-at-create-queue",
                Map.of("KmsMasterKeyId", "alias/aws/sqs"), region);

        assertEquals("false",
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region)
                        .get("SqsManagedSseEnabled"));
    }

    @Test
    void getQueueAttributes_sqsManagedSseReturnsToTheDefaultWhenTheKmsKeyIsCleared() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("kms-cleared-queue",
                Map.of("KmsMasterKeyId", "alias/aws/sqs"), region);
        assertEquals("false",
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region)
                        .get("SqsManagedSseEnabled"));

        sqsService.setQueueAttributes(queue.getQueueUrl(), Map.of("KmsMasterKeyId", ""), region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertFalse(attrs.containsKey("KmsMasterKeyId"), "An empty value clears the attribute");
        assertEquals("true", attrs.get("SqsManagedSseEnabled"),
                "Nothing derived from the KMS key may outlive it");
    }

    @Test
    void getQueueAttributes_explicitSqsManagedSseFalseSurvivesAnUnrelatedUpdate() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("sse-off-queue",
                Map.of("SqsManagedSseEnabled", "false"), region);
        sqsService.setQueueAttributes(queue.getQueueUrl(), Map.of("DelaySeconds", "5"), region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("All"), region);
        assertEquals("false", attrs.get("SqsManagedSseEnabled"),
                "A value the user set is intent, not derived state, and has to survive");
        assertEquals("5", attrs.get("DelaySeconds"));
    }

    @Test
    void getQueueAttributes_selectsSqsManagedSseEnabledByName() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("sse-named-queue", null, region);

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("SqsManagedSseEnabled"), region);
        assertEquals(Map.of("SqsManagedSseEnabled", "true"), attrs);
    }

    @Test
    void createQueue_rejectsMaximumMessageSizeOutsideAwsRange() {
        for (String invalid : List.of("1048577", "1023", "0", "-1", "abc")) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> sqsService.createQueue("range-queue-" + invalid,
                            Map.of("MaximumMessageSize", invalid), "eu-west-1"),
                    "MaximumMessageSize " + invalid + " must be rejected");
            assertEquals("InvalidAttributeValue", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("MaximumMessageSize"), ex.getMessage());
        }
    }

    @Test
    void createQueue_acceptsMaximumMessageSizeRangeBounds() {
        String region = "eu-west-1";
        for (String valid : List.of("1024", "1048576")) {
            Queue queue = sqsService.createQueue("bounds-queue-" + valid,
                    Map.of("MaximumMessageSize", valid), region);
            assertEquals(valid,
                    sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("MaximumMessageSize"), region)
                            .get("MaximumMessageSize"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"59", "1209601", "abc", "60.5", "", "-1", " 60", "60 ", "99999999999"})
    void messageRetentionPeriodOutsideAwsRangeIsRejected(String invalid) {
        String region = "eu-west-1";
        AwsException onCreate = assertThrows(AwsException.class,
                () -> sqsService.createQueue("retention-range", Map.of("MessageRetentionPeriod", invalid), region));
        assertEquals("InvalidAttributeValue", onCreate.getErrorCode());
        assertEquals("Invalid value for the parameter MessageRetentionPeriod.", onCreate.getMessage());

        Queue queue = sqsService.createQueue("retention-range", null, region);
        AwsException onSet = assertThrows(AwsException.class,
                () -> sqsService.setQueueAttributes(queue.getQueueUrl(),
                        Map.of("MessageRetentionPeriod", invalid), region));
        assertEquals("InvalidAttributeValue", onSet.getErrorCode());
        assertEquals("Invalid value for the parameter MessageRetentionPeriod.", onSet.getMessage());
        assertEquals("345600", sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("MessageRetentionPeriod"), region).get("MessageRetentionPeriod"));
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "1209600, 1209600", "060, 60", "+60, 60"})
    void messageRetentionPeriodIsStoredInCanonicalForm(String value, String stored) {
        String region = "eu-west-1";
        Queue created = sqsService.createQueue("retention-created", Map.of("MessageRetentionPeriod", value), region);
        assertEquals(stored, sqsService.getQueueAttributes(created.getQueueUrl(),
                List.of("MessageRetentionPeriod"), region).get("MessageRetentionPeriod"));

        Queue updated = sqsService.createQueue("retention-updated", null, region);
        sqsService.setQueueAttributes(updated.getQueueUrl(), Map.of("MessageRetentionPeriod", value), region);
        assertEquals(stored, sqsService.getQueueAttributes(updated.getQueueUrl(),
                List.of("MessageRetentionPeriod"), region).get("MessageRetentionPeriod"));
    }

    @Test
    void createQueue_acceptsTheAwsCeilingWhenTheConfiguredMaximumIsLower() {
        String region = "eu-west-1";
        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 131072, BASE_URL, clock);

        Queue defaulted = service.createQueue("lowered-config-default-queue", null, region);
        assertEquals("131072",
                service.getQueueAttributes(defaulted.getQueueUrl(), List.of("MaximumMessageSize"), region)
                        .get("MaximumMessageSize"),
                "The configured maximum is what a new queue defaults to");

        Queue raised = service.createQueue("lowered-config-raised-queue",
                Map.of("MaximumMessageSize", "1048576"), region);
        assertEquals("1048576",
                service.getQueueAttributes(raised.getQueueUrl(), List.of("MaximumMessageSize"), region)
                        .get("MaximumMessageSize"),
                "Lowering the configured maximum moves the default, not the ceiling AWS accepts");
        assertDoesNotThrow(
                () -> service.sendMessage(raised.getQueueUrl(), "x".repeat(1_000_000), 0, region),
                "The accepted ceiling and the enforced ceiling have to agree");
    }

    @Test
    void getQueueAttributes_clampsAStoredMaximumMessageSizeAboveTheCeiling() {
        String region = "us-east-1";
        InMemoryStorage<String, Queue> store = new InMemoryStorage<>();
        SqsService service = new SqsService(store, 30, 1048576, BASE_URL, clock);
        Queue queue = service.createQueue("legacy-size-queue", null, region);

        // A queue persisted by a build that allowed 2 MB, which no validation path can produce.
        String storageKey = store.keys().iterator().next();
        Queue stored = store.get(storageKey).orElseThrow();
        stored.getAttributes().put("MaximumMessageSize", "2097152");
        store.put(storageKey, stored);

        assertEquals("1048576",
                service.getQueueAttributes(queue.getQueueUrl(), List.of("MaximumMessageSize"), region)
                        .get("MaximumMessageSize"),
                "A stored value above the ceiling must be reported as the size actually enforced");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.sendMessage(queue.getQueueUrl(), "x".repeat(1_200_000), 0, region),
                "The reported size and the enforced size have to agree");
        assertTrue(ex.getMessage().contains("1048576"), ex.getMessage());
    }

    @Test
    void setQueueAttributes_rejectsMaximumMessageSizeOutsideAwsRange() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("set-range-queue", null, region);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.setQueueAttributes(queue.getQueueUrl(),
                        Map.of("MaximumMessageSize", "1048577"), region));
        assertEquals("InvalidAttributeValue", ex.getErrorCode());
        assertEquals("1048576",
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("MaximumMessageSize"), region)
                        .get("MaximumMessageSize"),
                "A rejected SetQueueAttributes must leave the stored value untouched");
    }

    @Test
    void setQueueAttributes_acceptsMaximumMessageSizeWithinAwsRange() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("set-valid-range-queue", null, region);
        sqsService.setQueueAttributes(queue.getQueueUrl(),
                Map.of("MaximumMessageSize", "2048"), region);

        assertEquals("2048",
                sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("MaximumMessageSize"), region)
                        .get("MaximumMessageSize"));
        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.sendMessage(queue.getQueueUrl(), "x".repeat(3000), 0, region));
        assertTrue(ex.getMessage().contains("2048"), ex.getMessage());
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
    void fifoDeduplicationStillSuppressesMessageAfterOriginalIsDeleted() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("deleted-original.fifo", null, region);
        Message original = sqsService.sendMessage(
                queue.getQueueUrl(), "original", 0, "group-a", "dedup-a", region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertEquals(1, received.size());
        sqsService.deleteMessage(queue.getQueueUrl(), received.getFirst().getReceiptHandle(), region);

        Message duplicateResponse = sqsService.sendMessage(
                queue.getQueueUrl(), "duplicate", 0, "group-a", "dedup-a", region);

        assertNotNull(duplicateResponse.getMessageId());
        assertEquals(original.getMessageId(), duplicateResponse.getMessageId());
        assertEquals(original.getSequenceNumber(), duplicateResponse.getSequenceNumber());
        assertEquals("dedup-a", duplicateResponse.getMessageDeduplicationId());
        assertTrue(sqsService.peekMessages(queue.getQueueUrl(), region).isEmpty(),
                "A duplicate must remain suppressed after its original message is deleted");
        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region).isEmpty());
    }

    @Test
    void fifoDeduplicationIdentitySurvivesRestartAfterOriginalIsDeleted() {
        String region = "us-east-1";
        InMemoryStorage<String, Queue> queueStore = new InMemoryStorage<>();
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, Long>> dedupStore = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, Map<String, String>>> identityStore = new InMemoryStorage<>();
        RegionResolver regionResolver = new RegionResolver(region, "000000000000");
        SqsService initialService = new SqsService(queueStore, messageStore, dedupStore, identityStore,
                30, 1048576, BASE_URL, regionResolver, false, null, clock);
        Queue queue = initialService.createQueue("deleted-before-restart.fifo", null, region);
        Message original = initialService.sendMessage(
                queue.getQueueUrl(), "original", 0, "group-a", "dedup-a", region);
        List<Message> received = initialService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        initialService.deleteMessage(queue.getQueueUrl(), received.getFirst().getReceiptHandle(), region);

        SqsService restartedService = new SqsService(queueStore, messageStore, dedupStore, identityStore,
                30, 1048576, BASE_URL, regionResolver, false, null, clock);
        Message duplicateResponse = restartedService.sendMessage(
                queue.getQueueUrl(), "duplicate", 0, "group-a", "dedup-a", region);

        assertEquals(original.getMessageId(), duplicateResponse.getMessageId());
        assertEquals(original.getSequenceNumber(), duplicateResponse.getSequenceNumber());
        assertTrue(restartedService.peekMessages(queue.getQueueUrl(), region).isEmpty());
    }

    @Test
    void fifoDeduplicationExpiresAtTheFiveMinuteBoundary() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("expired-dedup.fifo", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "original", 0, "group-a", "dedup-a", region);

        clock.advance(Duration.ofMinutes(5));
        sqsService.sendMessage(queue.getQueueUrl(), "after-window", 0, "group-a", "dedup-a", region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 2, 30, 0, region);
        assertEquals(2, received.size());
        assertTrue(received.stream().anyMatch(message -> "after-window".equals(message.getBody())));
    }

    @Test
    void concurrentFifoSendsAfterExpiryKeepTheNewDeduplicationEntry() throws Exception {
        String region = "eu-west-1";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                Queue queue = sqsService.createQueue("expired-concurrent-" + round + ".fifo", null, region);
                String queueUrl = queue.getQueueUrl();
                sqsService.sendMessage(queueUrl, "original", 0, "group-a", "dedup-a", region);
                Message original = sqsService.receiveMessage(queueUrl, 1, 30, 0, region).getFirst();
                sqsService.deleteMessage(queueUrl, original.getReceiptHandle(), region);
                clock.advance(Duration.ofMinutes(5));

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Message> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sqsService.sendMessage(queueUrl, "renewed", 0, "group-a", "dedup-a", region);
                });
                Future<Message> second = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return sqsService.sendMessage(queueUrl, "renewed", 0, "group-a", "dedup-a", region);
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                Message firstResponse = first.get(5, TimeUnit.SECONDS);
                Message secondResponse = second.get(5, TimeUnit.SECONDS);
                assertEquals(firstResponse.getMessageId(), secondResponse.getMessageId());
                assertEquals(firstResponse.getSequenceNumber(), secondResponse.getSequenceNumber());
                assertEquals(1, sqsService.peekMessages(queueUrl, region).size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateInAnotherGroupDoesNotWaitForBlockedMessageWrite() throws Exception {
        String region = "us-east-1";
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>() {
            @Override
            public void put(String key, List<Message> messages) {
                if (messages.stream().anyMatch(message -> "slow".equals(message.getBody()))) {
                    writing.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release message write");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                super.put(key, messages);
            }
        };
        SqsService service = new SqsService(new InMemoryStorage<>(), messageStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), 30, 1048576, BASE_URL,
                new RegionResolver(region, "000000000000"), false, null, clock);
        Queue queue = service.createQueue("unrelated-send.fifo", null, region);
        Message original = service.sendMessage(queue.getQueueUrl(), "original", 0,
                "group-a", "dedup-a", region);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Message> slow = executor.submit(() -> service.sendMessage(queue.getQueueUrl(),
                    "slow", 0, "group-b", "dedup-b", region));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<Message> duplicate = executor.submit(() -> service.sendMessage(queue.getQueueUrl(),
                    "duplicate", 0, "group-a", "dedup-a", region));
            assertEquals(original.getMessageId(), duplicate.get(2, TimeUnit.SECONDS).getMessageId());
            release.countDown();
            slow.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentFifoSendsRetainAllDeduplicationEntriesAfterRestart() throws Exception {
        String region = "us-east-1";
        InMemoryStorage<String, Queue> queueStore = new InMemoryStorage<>();
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, Long>> dedupStore = new InMemoryStorage<>();
        InMemoryStorage<String, Map<String, Map<String, String>>> identityStore = new InMemoryStorage<>();
        RegionResolver regionResolver = new RegionResolver(region, "000000000000");
        SqsService service = new SqsService(queueStore, messageStore, dedupStore, identityStore,
                30, 1048576, BASE_URL, regionResolver, false, null, clock);
        Queue queue = service.createQueue("concurrent-persist.fifo", null, region);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Message>> sent = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                int id = i;
                sent.add(executor.submit(() -> service.sendMessage(queue.getQueueUrl(),
                        "message-" + id, 0, "group-" + id, "dedup-" + id, region)));
            }
            List<Message> originals = new ArrayList<>();
            for (Future<Message> send : sent) {
                originals.add(send.get(5, TimeUnit.SECONDS));
            }
            SqsService restarted = new SqsService(queueStore, messageStore, dedupStore, identityStore,
                    30, 1048576, BASE_URL, regionResolver, false, null, clock);
            for (int i = 0; i < originals.size(); i++) {
                Message duplicate = restarted.sendMessage(queue.getQueueUrl(),
                        "duplicate-" + i, 0, "group-" + i, "dedup-" + i, region);
                assertEquals(originals.get(i).getMessageId(), duplicate.getMessageId());
            }
            assertEquals(32, restarted.peekMessages(queue.getQueueUrl(), region).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void contentBasedFifoDeduplicationStillSuppressesMessageAfterOriginalIsDeleted() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("content-deleted-original.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "same-body", 0, "group-a", null, region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        assertEquals(1, received.size());
        sqsService.deleteMessage(queue.getQueueUrl(), received.getFirst().getReceiptHandle(), region);

        sqsService.sendMessage(queue.getQueueUrl(), "same-body", 0, "group-a", null, region);

        assertTrue(sqsService.peekMessages(queue.getQueueUrl(), region).isEmpty(),
                "Content-based duplicate IDs must remain suppressed after deletion");
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
    void fifoDelayedMessageDoesNotBlockVisibleMessageInSameGroup() {
        // Issue #2104: only in-flight messages lock a FIFO message group. A
        // newly sent, still-delayed message must not make an already-visible
        // message in the same group unreceivable.
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("delay-group-lock.fifo",
                Map.of("ContentBasedDeduplication", "true", "DelaySeconds", "1"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "A", 0, "group1", null, region);

        try { Thread.sleep(1100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        sqsService.sendMessage(queue.getQueueUrl(), "B", 0, "group1", null, region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 0, 0, region);
        assertEquals(1, received.size(),
                "The already-visible message must be returned despite a delayed message in its group");
        assertEquals("A", received.get(0).getBody());
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
        SqsService service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        Queue queue = service.createQueue("dedup-clear.fifo", Map.of("ContentBasedDeduplication", "true"), region);

        // First send — message M1 added, dedup cache populated with "dedup-1"
        Message m1 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertNotNull(m1.getMessageId());

        // Purge clears both messages and the dedup cache
        service.purgeQueue(queue.getQueueUrl(), region);
        assertTrue(service.receiveMessage(queue.getQueueUrl(), 10, 0, 0, region).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with the same dedup ID — cache was cleared so this is treated as a fresh send
        Message m2 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertNotNull(m2.getMessageId());

        List<Message> received = service.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(1, received.size(), "One message must be in the queue after re-send");

        // Third send with same dedup ID — fresh cache entry from m2 deduplicates correctly
        Message m3 = service.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        assertEquals(m2.getMessageId(), m3.getMessageId(),
                "Dedup must work with the fresh cache entry after purge");
    }

    @Test
    void purgeQueuePreservesFifoDeduplicationCacheByDefault() {
        String region = "eu-west-1";
        // Default service has clearFifoDeduplicationCacheOnPurge=false
        Queue queue = sqsService.createQueue("dedup-preserve.fifo",
                Map.of("ContentBasedDeduplication", "true"), region);

        // Send and then purge — messages are gone but dedup cache is intact
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);
        sqsService.purgeQueue(queue.getQueueUrl(), region);

        assertTrue(sqsService.receiveMessage(queue.getQueueUrl(), 10, 0, 0, region).isEmpty(),
                "Queue must be empty after purge");

        // Re-send with the same dedup ID. Purging removed the original message,
        // but does not end its five-minute deduplication interval.
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "group1", "dedup-1", region);

        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertTrue(received.isEmpty(),
                "A duplicate must remain suppressed after purge during the deduplication interval");
    }

    @Test
    void purgeQueueClearsDedupStoreWhenEnabled() {
        String region = "eu-west-1";

        InMemoryStorage<String, Map<String, Long>> dedupStore = new InMemoryStorage<>();
        SqsService service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), dedupStore,
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, null);

        Queue queue = service.createQueue("dedup-store-clear.fifo",
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

    /**
     * The DLQ redrive path resolves a queue URL from the {@code deadLetterTargetArn} a client read
     * back out of GetQueueAttributes, and SQS mints that ARN with the region's own partition. The
     * resolver used to require a literal {@code arn:aws:sqs:}, so in GovCloud or China it returned
     * null, the move block was skipped, and messages stayed on the source queue with nothing
     * logged. A silent redrive failure is the worst shape this bug takes.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:sqs:us-east-1:000000000000:",
            "us-gov-west-1,  arn:aws-us-gov:sqs:us-gov-west-1:000000000000:",
            "cn-north-1,     arn:aws-cn:sqs:cn-north-1:000000000000:"})
    void startMessageMoveTask_acceptsAQueueArnFromAnyPartition(String region, String arnPrefix) {
        sqsService.createQueue("p-dlq", null, region);
        String dlqArn = arnPrefix + "p-dlq";
        sqsService.createQueue("p-src",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), region);
        sqsService.createQueue("p-dest", null, region);

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, arnPrefix + "p-dest", 5, region);

        assertNotNull(taskHandle);
        assertEquals(dlqArn, sqsService.listMessageMoveTasks(dlqArn, region).get(0).sourceArn());
    }

    /**
     * The ARN the emulator itself hands back must be the one it accepts. This is the assertion
     * that ties the two halves together rather than trusting a hand-written prefix.
     */
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void startMessageMoveTask_acceptsTheQueueArnGetQueueAttributesReturned(String region) {
        sqsService.createQueue("rt-dlq", null, region);
        String dlqArn = sqsService.getQueueAttributes(
                sqsService.getQueueUrl("rt-dlq", region), List.of("QueueArn"), region).get("QueueArn");
        sqsService.createQueue("rt-src",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), region);
        sqsService.createQueue("rt-dest", null, region);
        String destArn = sqsService.getQueueAttributes(
                sqsService.getQueueUrl("rt-dest", region), List.of("QueueArn"), region).get("QueueArn");

        assertNotNull(sqsService.startMessageMoveTask(dlqArn, destArn, 5, region));
    }

    /**
     * Widening the partition must not turn "queue does not exist" into something softer: an ARN
     * naming a queue nobody created is still ResourceNotFound, exactly as a commercial one is.
     *
     * <p>Note the resolver takes only the account and queue name out of the ARN and looks them up
     * in the caller's region, so the ARN's own region is not enforced. That is pre-existing and
     * already applied to a cross-region commercial ARN; this change does not alter it.
     */
    @Test
    void startMessageMoveTask_foreignPartitionQueueThatDoesNotExistIsNotFound() {
        sqsService.createQueue("fp-dlq", null, "us-east-1");
        String dlqArn = queueArn("fp-dlq");
        sqsService.createQueue("fp-src",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"), "us-east-1");

        AwsException ex = assertThrows(AwsException.class, () -> sqsService.startMessageMoveTask(
                dlqArn, "arn:aws-cn:sqs:cn-north-1:000000000000:never-created", 0, "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
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
    void startMessageMoveTask_withoutDestinationKeepsMessageWithoutOriginalSource() throws Exception {
        Queue dlq = sqsService.createQueue("orphan-dlq", null, "us-east-1");
        String dlqArn = queueArn("orphan-dlq");
        sqsService.createQueue("orphan-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        sqsService.sendMessage(dlq.getQueueUrl(), "orphan", 0, null, null, "us-east-1");
        sqsService.sendMessage(dlq.getQueueUrl(), "next", 0, null, null, "us-east-1");

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, null, 0, "us-east-1");

        awaitMoveTaskStatus(dlqArn, taskHandle, "COMPLETED");
        assertEquals(List.of("orphan", "next"), bodies(sqsService.peekMessages(dlq.getQueueUrl(), "us-east-1")));
    }

    @Test
    void startMessageMoveTask_withoutDestinationKeepsMessageWhoseOriginalSourceWasDeleted() throws Exception {
        Queue dlq = sqsService.createQueue("gone-dlq", null, "us-east-1");
        String dlqArn = queueArn("gone-dlq");
        Queue source = sqsService.createQueue("gone-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        // A sibling keeps the DLQ referenced by a redrive policy after the original source is gone.
        sqsService.createQueue("gone-sibling", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        sqsService.sendMessage(source.getQueueUrl(), "stranded", 0, null, null, "us-east-1");
        // Receiving past maxReceiveCount redrives the message into the DLQ with its original source recorded.
        assertEquals(1, sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, "us-east-1").size());
        assertEquals(0, sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, "us-east-1").size());
        assertEquals(List.of("stranded"), bodies(sqsService.peekMessages(dlq.getQueueUrl(), "us-east-1")));
        sqsService.deleteQueue(source.getQueueUrl(), "us-east-1");

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, null, 0, "us-east-1");

        awaitMoveTaskStatus(dlqArn, taskHandle, "COMPLETED");
        assertEquals(List.of("stranded"), bodies(sqsService.peekMessages(dlq.getQueueUrl(), "us-east-1")));
    }

    @Test
    void startMessageMoveTask_movesEveryMessageOfANonDefaultAccountQueue() throws Exception {
        String account = "111111111111";
        RequestContext requestContext = new RequestContext();
        requestContext.setAccountId(account);
        Thread caller = Thread.currentThread();
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        // Only the calling thread has a request scope; the move worker runs outside any request.
        when(requestContextInstance.get()).thenAnswer(invocation -> {
            if (Thread.currentThread() != caller) {
                throw new ContextNotActiveException();
            }
            return requestContext;
        });
        SqsService service = new SqsService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requestContextInstance, "000000000000"),
                null, null, 30, 1048576, BASE_URL, new RegionResolver("us-east-1", account), false, null, clock);
        String dlqArn = "arn:aws:sqs:us-east-1:" + account + ":acct-dlq";
        Queue dlq = service.createQueue("acct-dlq", null, "us-east-1");
        service.createQueue("acct-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        Queue replay = service.createQueue("acct-replay", null, "us-east-1");
        for (String body : List.of("one", "two", "three")) {
            service.sendMessage(dlq.getQueueUrl(), body, 0, null, null, "us-east-1");
        }

        String taskHandle = service.startMessageMoveTask(
                dlqArn, "arn:aws:sqs:us-east-1:" + account + ":acct-replay", 0, "us-east-1");

        awaitMoveTaskStatus(service, dlqArn, taskHandle, "COMPLETED");
        assertEquals(List.of("one", "two", "three"), bodies(service.peekMessages(replay.getQueueUrl(), "us-east-1")));
        assertEquals(List.of(), bodies(service.peekMessages(dlq.getQueueUrl(), "us-east-1")));
    }

    @Test
    void startMessageMoveTask_failedDeliveryLeavesSourceQueueOrderIntact() throws Exception {
        AtomicBoolean destinationStoreDown = new AtomicBoolean();
        SqsService service = serviceWithMessageStoreFailingFor("fail-replay", destinationStoreDown);
        Queue dlq = service.createQueue("fail-dlq", null, "us-east-1");
        String dlqArn = queueArn("fail-dlq");
        service.createQueue("fail-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        service.createQueue("fail-replay", null, "us-east-1");
        for (String body : List.of("first", "second", "third")) {
            service.sendMessage(dlq.getQueueUrl(), body, 0, null, null, "us-east-1");
        }
        destinationStoreDown.set(true);

        String taskHandle = service.startMessageMoveTask(dlqArn, queueArn("fail-replay"), 0, "us-east-1");

        awaitMoveTaskStatus(service, dlqArn, taskHandle, "COMPLETED");
        assertEquals(List.of("first", "second", "third"), bodies(service.peekMessages(dlq.getQueueUrl(), "us-east-1")));
    }

    @Test
    void startMessageMoveTask_failedDeliveryRestoresTheSourceMessageUnchanged() throws Exception {
        AtomicBoolean destinationStoreDown = new AtomicBoolean();
        SqsService service = serviceWithMessageStoreFailingFor("held-replay", destinationStoreDown);
        Queue dlq = service.createQueue("held-dlq", null, "us-east-1");
        String dlqArn = queueArn("held-dlq");
        service.createQueue("held-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        service.createQueue("held-replay", null, "us-east-1");
        service.sendMessage(dlq.getQueueUrl(), "held", 0, null, null, "us-east-1");
        service.sendMessage(dlq.getQueueUrl(), "next", 0, null, null, "us-east-1");
        // A consumer holds the head message when the redrive starts.
        Message held = service.receiveMessage(dlq.getQueueUrl(), 1, 30, 0, "us-east-1").getFirst();
        String messageId = held.getMessageId();
        String receiptHandle = held.getReceiptHandle();
        Instant firstReceive = held.getFirstReceiveTimestamp();
        Instant visibleAt = held.getVisibleAt();
        assertEquals(1, held.getReceiveCount());
        destinationStoreDown.set(true);

        String taskHandle = service.startMessageMoveTask(dlqArn, queueArn("held-replay"), 0, "us-east-1");

        awaitMoveTaskStatus(service, dlqArn, taskHandle, "COMPLETED");
        List<Message> dlqMessages = service.peekMessages(dlq.getQueueUrl(), "us-east-1");
        assertEquals(List.of("held", "next"), bodies(dlqMessages));
        Message restored = dlqMessages.getFirst();
        assertEquals(messageId, restored.getMessageId());
        assertEquals(1, restored.getReceiveCount());
        assertEquals(firstReceive, restored.getFirstReceiveTimestamp());
        assertEquals(receiptHandle, restored.getReceiptHandle());
        assertEquals(visibleAt, restored.getVisibleAt());
        Map<String, String> attributes = service.getQueueAttributes(dlq.getQueueUrl(),
                List.of("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"), "us-east-1");
        assertEquals("1", attributes.get("ApproximateNumberOfMessages"));
        assertEquals("1", attributes.get("ApproximateNumberOfMessagesNotVisible"));
    }

    @Test
    void startMessageMoveTask_failedDeliveryLeavesNoCopyInTheDestination() throws Exception {
        AtomicBoolean destinationStoreDown = new AtomicBoolean();
        SqsService service = serviceWithMessageStoreFailingFor("dup-replay", destinationStoreDown);
        Queue dlq = service.createQueue("dup-dlq", null, "us-east-1");
        String dlqArn = queueArn("dup-dlq");
        service.createQueue("dup-source", Map.of("RedrivePolicy", redrivePolicy(dlqArn)), "us-east-1");
        Queue replay = service.createQueue("dup-replay", null, "us-east-1");
        for (String body : List.of("first", "second", "third")) {
            service.sendMessage(dlq.getQueueUrl(), body, 0, null, null, "us-east-1");
        }
        destinationStoreDown.set(true);

        String taskHandle = service.startMessageMoveTask(dlqArn, queueArn("dup-replay"), 0, "us-east-1");

        awaitMoveTaskStatus(service, dlqArn, taskHandle, "COMPLETED");
        assertEquals(List.of(), bodies(service.peekMessages(replay.getQueueUrl(), "us-east-1")));
        assertEquals("0", service.getQueueAttributes(replay.getQueueUrl(),
                List.of("ApproximateNumberOfMessages"), "us-east-1").get("ApproximateNumberOfMessages"));
        assertTrue(service.receiveMessage(replay.getQueueUrl(), 10, 0, 0, "us-east-1").isEmpty());
        assertEquals(List.of("first", "second", "third"), bodies(service.peekMessages(dlq.getQueueUrl(), "us-east-1")));
    }

    /** A service whose message store rejects writes for {@code queueName} while {@code down} is set. */
    private SqsService serviceWithMessageStoreFailingFor(String queueName, AtomicBoolean down) {
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>() {
            @Override
            public void put(String key, List<Message> value) {
                if (down.get() && key.endsWith("/" + queueName)) {
                    throw new IllegalStateException("destination store unavailable");
                }
                super.put(key, value);
            }
        };
        return new SqsService(new InMemoryStorage<>(), messageStore, null, 30, 1048576, BASE_URL,
                new RegionResolver("us-east-1", "000000000000"), false, null, clock);
    }

    private static String redrivePolicy(String dlqArn) {
        return "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}";
    }

    private static List<String> bodies(List<Message> messages) {
        return messages.stream().map(Message::getBody).toList();
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
        assertEquals(1, sqsService.cancelMessageMoveTask(taskHandle, "us-east-1"));

        awaitMoveTaskStatus(dlqArn, taskHandle, "CANCELLED");
    }

    @Test
    void cancelMessageMoveTask_takesEffectWithoutWaitingOutTheRateInterval() throws Exception {
        Queue dlq = sqsService.createQueue("cancel-latency-dlq", null, "us-east-1");
        String dlqArn = queueArn("cancel-latency-dlq");
        sqsService.createQueue("cancel-latency-source",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("cancel-latency-destination", null, "us-east-1");
        String destinationArn = queueArn("cancel-latency-destination");
        for (int i = 0; i < 50; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null, "us-east-1");
        }

        // At one message per second the worker sleeps a full second between moves. AWS
        // reflects a cancel immediately (CANCELLING, then CANCELLED once nothing is in
        // flight); the worker must be woken, not left to sleep out its interval.
        String taskHandle = sqsService.startMessageMoveTask(dlqArn, destinationArn, 1, "us-east-1");
        sqsService.cancelMessageMoveTask(taskHandle, "us-east-1");

        String statusRightAfterCancel = moveTaskStatus(dlqArn, taskHandle);
        assertTrue("CANCELLING".equals(statusRightAfterCancel) || "CANCELLED".equals(statusRightAfterCancel),
                "expected CANCELLING or CANCELLED right after CancelMessageMoveTask returned, was " + statusRightAfterCancel);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (!"CANCELLED".equals(moveTaskStatus(dlqArn, taskHandle))) {
            assertTrue(System.nanoTime() < deadline,
                    "move task still " + moveTaskStatus(dlqArn, taskHandle) + " 300 ms after the cancel");
            Thread.sleep(5);
        }
        assertEquals(1, sqsService.listMessageMoveTasks(dlqArn, "us-east-1").size());
    }

    @Test
    void cancelMessageMoveTask_statusNeverRegressesToRunningWhileTheWorkerRecordsProgress() throws Exception {
        Queue dlq = sqsService.createQueue("cancel-stomp-dlq", null, "us-east-1");
        String dlqArn = queueArn("cancel-stomp-dlq");
        sqsService.createQueue("cancel-stomp-source",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("cancel-stomp-destination", null, "us-east-1");
        String destinationArn = queueArn("cancel-stomp-destination");
        for (int i = 0; i < 3000; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null, "us-east-1");
        }

        // Unthrottled, so the worker records its count after every message while the cancel
        // lands: the two writers share the store's lock, and once the cancel has returned the
        // task must only ever read CANCELLING or CANCELLED.
        String taskHandle = sqsService.startMessageMoveTask(dlqArn, destinationArn, "us-east-1");
        sqsService.cancelMessageMoveTask(taskHandle, "us-east-1");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String status;
        while (!"CANCELLED".equals(status = moveTaskStatus(dlqArn, taskHandle))) {
            assertNotEquals("RUNNING", status, "a cancelled task reported RUNNING again");
            assertTrue(System.nanoTime() < deadline, "move task never reached CANCELLED, last seen " + status);
        }
    }

    @Test
    void cancelMessageMoveTask_acceptedCancelIsNotOverwrittenByTheWorkersTerminalWrite() throws Exception {
        Queue dlq = sqsService.createQueue("cancel-terminal-dlq", null, "us-east-1");
        String dlqArn = queueArn("cancel-terminal-dlq");
        sqsService.createQueue("cancel-terminal-source",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("cancel-terminal-destination", null, "us-east-1");
        String destinationArn = queueArn("cancel-terminal-destination");
        for (int i = 0; i < 50; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null, "us-east-1");
        }

        // The interleaving: the worker reads its own signal and concludes COMPLETED, the cancel is
        // accepted (the task reads CANCELLING), and only then does the worker's terminal write
        // land. Replaying that write with the stale decision must not turn CANCELLING back into
        // COMPLETED.
        String taskHandle = sqsService.startMessageMoveTask(dlqArn, destinationArn, 1, "us-east-1");
        sqsService.cancelMessageMoveTask(taskHandle, "us-east-1");
        sqsService.finishMoveTask(taskHandle, 1, false);

        assertEquals("CANCELLED", moveTaskStatus(dlqArn, taskHandle));
        awaitMoveTaskStatus(dlqArn, taskHandle, "CANCELLED");
        assertEquals("CANCELLED", moveTaskStatus(dlqArn, taskHandle));
    }

    private String moveTaskStatus(String sourceArn, String taskHandle) {
        return sqsService.listMessageMoveTasks(sourceArn, "us-east-1").stream()
                .filter(task -> task.taskHandle().equals(taskHandle))
                .map(SqsService.MoveTask::status)
                .findFirst()
                .orElse("<absent>");
    }

    @Test
    void completedMessageMoveTasks_areBoundedToRecentSummaries() throws Exception {
        sqsService.createQueue("terminal-cap-dlq", null, "us-east-1");
        String dlqArn = queueArn("terminal-cap-dlq");
        sqsService.createQueue("terminal-cap-source",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("terminal-cap-destination", null, "us-east-1");
        String destinationArn = queueArn("terminal-cap-destination");

        List<String> taskHandles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String taskHandle = sqsService.startMessageMoveTask(dlqArn, destinationArn, 0, "us-east-1");
            taskHandles.add(taskHandle);
            for (int attempt = 0; attempt < 50; attempt++) {
                if (sqsService.listMessageMoveTasks(dlqArn, "us-east-1").stream()
                        .anyMatch(task -> task.taskHandle().equals(taskHandle) && "COMPLETED".equals(task.status()))) {
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(sqsService.listMessageMoveTasks(dlqArn, "us-east-1").stream()
                    .anyMatch(task -> task.taskHandle().equals(taskHandle) && "COMPLETED".equals(task.status())));
            if (i < 10) {
                clock.advance(Duration.ofSeconds(2));
            }
        }

        List<SqsService.MoveTask> recentTasks = sqsService.listMessageMoveTasks(dlqArn, "us-east-1");
        assertEquals(10, recentTasks.size());
        assertFalse(recentTasks.stream().anyMatch(task -> task.taskHandle().equals(taskHandles.getFirst())));

        clock.advance(Duration.ofHours(1).plusMillis(1));
        assertTrue(sqsService.listMessageMoveTasks(dlqArn, "us-east-1").isEmpty());
        AwsException ex = assertThrows(AwsException.class, () ->
                sqsService.cancelMessageMoveTask(taskHandles.getLast(), "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void completedMessageMoveTasks_areBoundedPerSourceQueue() throws Exception {
        sqsService.createQueue("per-source-dlq-a", null, "us-east-1");
        String sourceArnA = queueArn("per-source-dlq-a");
        sqsService.createQueue("per-source-source-a",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + sourceArnA + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("per-source-destination-a", null, "us-east-1");
        String destinationArnA = queueArn("per-source-destination-a");

        sqsService.createQueue("per-source-dlq-b", null, "us-east-1");
        String sourceArnB = queueArn("per-source-dlq-b");
        sqsService.createQueue("per-source-source-b",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + sourceArnB + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("per-source-destination-b", null, "us-east-1");
        String destinationArnB = queueArn("per-source-destination-b");

        for (int i = 0; i < 10; i++) {
            String taskHandle = sqsService.startMessageMoveTask(sourceArnA, destinationArnA, 0, "us-east-1");
            awaitMoveTaskStatus(sourceArnA, taskHandle, "COMPLETED");
            clock.advance(Duration.ofSeconds(2));
        }
        String taskHandleB = sqsService.startMessageMoveTask(sourceArnB, destinationArnB, 0, "us-east-1");
        awaitMoveTaskStatus(sourceArnB, taskHandleB, "COMPLETED");

        assertEquals(10, sqsService.listMessageMoveTasks(sourceArnA, "us-east-1").size());
        assertEquals(1, sqsService.listMessageMoveTasks(sourceArnB, "us-east-1").size());
    }

    @Test
    void cancelledMessageMoveTasks_areBoundedAndAllowTheNextTask() throws Exception {
        Queue dlq = sqsService.createQueue("cancel-cap-dlq", null, "us-east-1");
        String dlqArn = queueArn("cancel-cap-dlq");
        sqsService.createQueue("cancel-cap-source",
                Map.of("RedrivePolicy",
                        "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                "us-east-1");
        sqsService.createQueue("cancel-cap-destination", null, "us-east-1");
        String destinationArn = queueArn("cancel-cap-destination");
        for (int i = 0; i < 50; i++) {
            sqsService.sendMessage(dlq.getQueueUrl(), "msg-" + i, 0, null, null, "us-east-1");
        }

        List<String> taskHandles = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String taskHandle = sqsService.startMessageMoveTask(dlqArn, destinationArn, 1, "us-east-1");
            taskHandles.add(taskHandle);
            sqsService.cancelMessageMoveTask(taskHandle, "us-east-1");
            awaitMoveTaskStatus(dlqArn, taskHandle, "CANCELLED");
            if (i < 10) {
                clock.advance(Duration.ofSeconds(2));
            }
        }

        List<SqsService.MoveTask> recentTasks = sqsService.listMessageMoveTasks(dlqArn, "us-east-1");
        assertEquals(10, recentTasks.size());
        assertFalse(recentTasks.stream().anyMatch(task -> task.taskHandle().equals(taskHandles.getFirst())));

        clock.advance(Duration.ofSeconds(2));
        String nextTask = sqsService.startMessageMoveTask(dlqArn, destinationArn, 1, "us-east-1");
        sqsService.cancelMessageMoveTask(nextTask, "us-east-1");
        awaitMoveTaskStatus(dlqArn, nextTask, "CANCELLED");

        clock.advance(Duration.ofHours(1).plusMillis(1));
        assertTrue(sqsService.listMessageMoveTasks(dlqArn, "us-east-1").isEmpty());
    }

    private void awaitMoveTaskStatus(String sourceArn, String taskHandle, String expectedStatus) throws Exception {
        awaitMoveTaskStatus(sqsService, sourceArn, taskHandle, expectedStatus);
    }

    private static void awaitMoveTaskStatus(SqsService service, String sourceArn, String taskHandle,
                                            String expectedStatus) throws Exception {
        for (int attempt = 0; attempt < 500; attempt++) {
            boolean reachedStatus = service.listMessageMoveTasks(sourceArn, "us-east-1").stream()
                    .anyMatch(task -> task.taskHandle().equals(taskHandle)
                            && expectedStatus.equals(task.status()));
            if (reachedStatus) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Move task did not transition to " + expectedStatus + " within timeout");
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
        List<Message> received = sqsService.receiveMessage(q.getQueueUrl(), 1, 30, 0, "us-east-1");
        assertEquals(1, received.size());
        assertEquals("Root=1-abc-def", received.get(0).getAwsTraceHeader());
    }

    @Test
    void purgeQueueWithClearFifoDelegatesToSnsForFifoDedupOnSubscribedTopics() {
        String region = "us-east-1";
        SnsService sns = mock(SnsService.class);
        SqsService service = new SqsService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"), true, sns);
        Queue queue = service.createQueue("sns-dedup-delegate.fifo", Map.of("FifoQueue", "true"), region);
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

        CountDownLatch pollerEntered = new CountDownLatch(1);
        AtomicReference<List<Message>> staleResult = new AtomicReference<>();
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
        CountDownLatch pollerEntered = new CountDownLatch(1);
        AtomicReference<List<Message>> staleResult = new AtomicReference<>();
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


    // --- ReceiveMessage falls back to the queue's ReceiveMessageWaitTimeSeconds ---

    @Test
    void receiveMessageWithoutWaitTimeUsesQueueReceiveMessageWaitTimeSeconds() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("queue-default-longpoll",
                Map.of("ReceiveMessageWaitTimeSeconds", "1"), region);

        long start = System.nanoTime();
        List<Message> result = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, null, region);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isEmpty());
        assertTrue(elapsedMs >= 900,
                "A receive that omits WaitTimeSeconds must long poll for the queue's ReceiveMessageWaitTimeSeconds, but returned after " + elapsedMs + "ms");
    }

    @Test
    void explicitWaitTimeOverridesQueueReceiveMessageWaitTimeSeconds() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("queue-longpoll-override",
                Map.of("ReceiveMessageWaitTimeSeconds", "20"), region);

        long start = System.nanoTime();
        List<Message> result = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isEmpty());
        assertTrue(elapsedMs < 1000,
                "WaitTimeSeconds=0 in the request must override the queue attribute, but returned after " + elapsedMs + "ms");
    }

    @Test
    void receiveMessageWithoutWaitTimeReturnsImmediatelyWhenQueueDoesNotLongPoll() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("queue-no-longpoll", null, region);
        assertEquals("0", queue.getAttributes().get("ReceiveMessageWaitTimeSeconds"));

        long start = System.nanoTime();
        List<Message> result = sqsService.receiveMessage(queue.getQueueUrl(), 1, 30, null, region);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.isEmpty());
        assertTrue(elapsedMs < 1000,
                "A queue without ReceiveMessageWaitTimeSeconds must short poll, but returned after " + elapsedMs + "ms");
    }

    @Test
    void receiveMessageRejectsWaitTimeOutsideAwsRange() {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("queue-longpoll-range",
                Map.of("ReceiveMessageWaitTimeSeconds", "20"), region);
        String queueUrl = queue.getQueueUrl();

        for (int invalid : new int[]{-1, 21}) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> sqsService.receiveMessage(queueUrl, 1, 30, invalid, region));
            assertEquals("InvalidParameterValue", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("WaitTimeSeconds"),
                    "The error must name the offending parameter, got: " + ex.getMessage());
        }

        sqsService.sendMessage(queueUrl, "in-range", 0, region);
        List<Message> result = sqsService.receiveMessage(queueUrl, 1, 30, 20, region);
        assertEquals(1, result.size(), "WaitTimeSeconds=20 is the AWS maximum and must be accepted");
    }

    @Test
    void queueDefaultLongPollReturnsAsSoonAsAMessageArrives() throws InterruptedException {
        String region = "eu-west-1";
        Queue queue = sqsService.createQueue("queue-longpoll-wakeup", null, region);
        String queueUrl = queue.getQueueUrl();
        sqsService.setQueueAttributes(queueUrl, Map.of("ReceiveMessageWaitTimeSeconds", "20"), region);

        CountDownLatch pollerEntered = new CountDownLatch(1);
        AtomicReference<List<Message>> result = new AtomicReference<>();
        Thread poller = new Thread(() -> {
            pollerEntered.countDown();
            result.set(sqsService.receiveMessage(queueUrl, 1, 30, null, region));
        });
        poller.start();
        assertTrue(pollerEntered.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        sqsService.sendMessage(queueUrl, "wake-up", 0, region);
        poller.join(3000);

        assertFalse(poller.isAlive(),
                "A long poll driven by the queue attribute must return as soon as a message arrives");
        assertEquals(1, result.get().size());
        assertEquals("wake-up", result.get().get(0).getBody());
    }

    @Test
    void messagePastRetentionPeriodIsNotReturned() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("retention-queue",
                Map.of("MessageRetentionPeriod", "60"), region);
        Message sent = sqsService.sendMessage(queue.getQueueUrl(), "old", 0, region);
        sqsService.sendMessage(queue.getQueueUrl(), "fresh", 0, region);
        sent.setSentTimestamp(Instant.now().minusSeconds(61));

        Map<String, String> attrs = sqsService.getQueueAttributes(queue.getQueueUrl(),
                List.of("ApproximateNumberOfMessages"), region);
        assertEquals("1", attrs.get("ApproximateNumberOfMessages"));
        List<Message> received = sqsService.receiveMessage(queue.getQueueUrl(), 10, 30, 0, region);
        assertEquals(List.of("fresh"), received.stream().map(Message::getBody).toList());
    }

    @Test
    void shorterRetentionPeriodExpiresExistingMessages() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("retention-shrink", null, region);
        Message sent = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        sent.setSentTimestamp(Instant.now().minusSeconds(120));
        assertEquals(1, sqsService.peekMessages(queue.getQueueUrl(), region).size());

        sqsService.setQueueAttributes(queue.getQueueUrl(), Map.of("MessageRetentionPeriod", "60"), region);

        assertTrue(sqsService.peekMessages(queue.getQueueUrl(), region).isEmpty());
    }

    @Test
    void standardDeadLetterQueueKeepsOriginalEnqueueTimeForRetention() {
        String region = "us-east-1";
        Queue dlq = sqsService.createQueue("retention-dlq",
                Map.of("MessageRetentionPeriod", "60"), region);
        Queue source = sqsService.createQueue("retention-src",
                Map.of("RedrivePolicy", "{\"deadLetterTargetArn\":\"" + queueArn("retention-dlq")
                        + "\",\"maxReceiveCount\":\"1\"}"), region);
        Message sent = sqsService.sendMessage(source.getQueueUrl(), "msg", 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        assertEquals(1, sqsService.peekMessages(dlq.getQueueUrl(), region).size());

        sent.setSentTimestamp(Instant.now().minusSeconds(61));

        assertTrue(sqsService.peekMessages(dlq.getQueueUrl(), region).isEmpty());
    }

    @Test
    void fifoDeadLetterQueueRestartsRetentionPeriod() {
        String region = "us-east-1";
        Queue dlq = sqsService.createQueue("retention-dlq.fifo",
                Map.of("FifoQueue", "true", "MessageRetentionPeriod", "60"), region);
        Queue source = sqsService.createQueue("retention-src.fifo",
                Map.of("FifoQueue", "true", "RedrivePolicy", "{\"deadLetterTargetArn\":\""
                        + queueArn("retention-dlq.fifo") + "\",\"maxReceiveCount\":\"1\"}"), region);
        Message sent = sqsService.sendMessage(source.getQueueUrl(), "msg", 0, "g1", "d1", region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);

        sent.setSentTimestamp(Instant.now().minusSeconds(61));

        assertEquals(1, sqsService.peekMessages(dlq.getQueueUrl(), region).size());
    }

    @Test
    void messageMoveTaskRestartsRetentionPeriod() {
        String region = "us-east-1";
        sqsService.createQueue("retention-mv-dlq", null, region);
        Queue source = sqsService.createQueue("retention-mv-src",
                Map.of("RedrivePolicy", "{\"deadLetterTargetArn\":\"" + queueArn("retention-mv-dlq")
                        + "\",\"maxReceiveCount\":\"1\"}"), region);
        Queue dest = sqsService.createQueue("retention-mv-dest",
                Map.of("MessageRetentionPeriod", "60"), region);
        Message sent = sqsService.sendMessage(source.getQueueUrl(), "msg", 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        Instant sentAt = Instant.now().minusSeconds(61);
        sent.setSentTimestamp(sentAt);

        sqsService.startMessageMoveTask(queueArn("retention-mv-dlq"), queueArn("retention-mv-dest"), region);

        List<Message> moved = sqsService.peekMessages(dest.getQueueUrl(), region);
        assertEquals(1, moved.size());
        assertEquals(sentAt, moved.get(0).getSentTimestamp());
    }

    @Test
    void rateLimitedMessageMoveTaskDropsMessagesThatExpireDuringTheMove() throws Exception {
        String region = "us-east-1";
        sqsService.createQueue("retention-rl-dlq", Map.of("MessageRetentionPeriod", "60"), region);
        Queue source = sqsService.createQueue("retention-rl-src",
                Map.of("RedrivePolicy", "{\"deadLetterTargetArn\":\"" + queueArn("retention-rl-dlq")
                        + "\",\"maxReceiveCount\":\"1\"}"), region);
        Queue dest = sqsService.createQueue("retention-rl-dest", null, region);
        sqsService.sendMessage(source.getQueueUrl(), "first", 0, region);
        Message second = sqsService.sendMessage(source.getQueueUrl(), "second", 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 10, 0, 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 10, 0, 0, region);
        String dlqArn = queueArn("retention-rl-dlq");
        // Still live when the task starts, expired when the worker reaches it a second later.
        second.setSentTimestamp(Instant.now().minusSeconds(59));

        String taskHandle = sqsService.startMessageMoveTask(dlqArn, queueArn("retention-rl-dest"), 1, region);
        awaitMoveTaskStatus(dlqArn, taskHandle, "COMPLETED");
        assertEquals(2, sqsService.listMessageMoveTasks(dlqArn, region).get(0).approximateNumberOfMessagesToMove());

        List<Message> moved = sqsService.peekMessages(dest.getQueueUrl(), region);
        assertEquals(List.of("first"), moved.stream().map(Message::getBody).toList());
    }

    @Test
    void deleteExpiredMessagesRemovesThemFromStorage() {
        String region = "us-east-1";
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>();
        SqsService service = new SqsService(new InMemoryStorage<>(), messageStore, new InMemoryStorage<>(),
                30, 1048576, BASE_URL, new RegionResolver("us-east-1", "000000000000"));
        Queue queue = service.createQueue("retention-sweep", Map.of("MessageRetentionPeriod", "60"), region);
        Message sent = service.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        sent.setSentTimestamp(Instant.now().minusSeconds(61));
        String storageKey = region + "::/000000000000/retention-sweep";
        assertEquals(1, messageStore.get(storageKey).orElseThrow().size());

        service.deleteExpiredMessages();

        assertTrue(messageStore.get(storageKey).orElseThrow().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "delete, empty", "delete, malformed", "delete, edited", "delete, otherQueue", "delete, otherSecret",
            "changeVisibility, empty", "changeVisibility, malformed", "changeVisibility, edited",
            "changeVisibility, otherQueue", "changeVisibility, otherSecret"})
    void unusableReceiptHandleIsRejected(String operation, String kind) {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-queue", null, region);
        Queue other = sqsService.createQueue("handle-other", null, region);
        Message sent = sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        sqsService.sendMessage(other.getQueueUrl(), "msg", 0, region);
        String handle = switch (kind) {
            case "empty" -> "";
            case "malformed" -> "abc";
            case "edited" -> receivedHandle(queue.getQueueUrl(), 30, region).replace(":receipt=", ":receipt=0");
            case "otherQueue" -> receivedHandle(other.getQueueUrl(), 30, region);
            case "otherSecret" -> ReceiptHandle.issue(region + "::/000000000000/handle-queue", sent.getMessageId(),
                    "another-secret").encode();
            default -> throw new IllegalArgumentException(kind);
        };
        String expectedMessage = switch (kind) {
            case "empty" -> "The request must contain the parameter ReceiptHandle.";
            case "malformed" -> "The input receipt handle is invalid.";
            default -> "The receipt handle \"" + handle + "\" is not valid for this queue.";
        };

        AwsException ex = assertThrows(AwsException.class,
                () -> useReceiptHandle(operation, queue.getQueueUrl(), handle, region));

        assertEquals("empty".equals(kind) ? "MissingParameter" : "ReceiptHandleIsInvalid", ex.getErrorCode());
        assertEquals(expectedMessage, ex.getMessage());
        assertEquals(1, sqsService.peekMessages(queue.getQueueUrl(), region).size());
    }

    @Test
    void batchEntryReportsAnEmptyReceiptHandleAsInvalid() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-batch-empty", null, region);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.deleteMessageInBatch(queue.getQueueUrl(), "", region));
        assertEquals("ReceiptHandleIsInvalid", ex.getErrorCode());
        assertEquals("The request must contain the parameter ReceiptHandle.", ex.getMessage());

        List<SqsService.BatchResultEntry> results = sqsService.changeMessageVisibilityBatch(queue.getQueueUrl(),
                List.of(new SqsService.ChangeVisibilityBatchEntry("e", "", 10)), region);
        assertEquals("ReceiptHandleIsInvalid", results.get(0).errorCode());
        assertEquals("The request must contain the parameter ReceiptHandle.", results.get(0).errorMessage());
    }

    @Test
    void deletingAMessageThatIsAlreadyGoneSucceeds() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-gone", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        String handle = receivedHandle(queue.getQueueUrl(), 30, region);
        sqsService.deleteMessage(queue.getQueueUrl(), handle, region);

        assertDoesNotThrow(() -> sqsService.deleteMessage(queue.getQueueUrl(), handle, region));
        assertDoesNotThrow(() -> sqsService.deleteMessageInBatch(queue.getQueueUrl(), handle, region));
    }

    @Test
    void changingVisibilityOfAMessageThatIsGoneIsInvalid() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-gone-visibility", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        String handle = receivedHandle(queue.getQueueUrl(), 30, region);
        sqsService.deleteMessage(queue.getQueueUrl(), handle, region);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.changeMessageVisibility(queue.getQueueUrl(), handle, 10, region));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Value " + handle + " for parameter ReceiptHandle is invalid. Reason: Message does not exist "
                + "or is not available for visibility timeout change.", ex.getMessage());

        List<SqsService.BatchResultEntry> results = sqsService.changeMessageVisibilityBatch(queue.getQueueUrl(),
                List.of(new SqsService.ChangeVisibilityBatchEntry("g", handle, 10)), region);
        assertEquals("ReceiptHandleIsInvalid", results.get(0).errorCode());
        assertEquals("Message does not exist or is not available for visibility timeout change",
                results.get(0).errorMessage());
    }

    @Test
    void standardQueueAcceptsAnOlderReceiptHandleOfTheMessage() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-stale", null, region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        String older = receivedHandle(queue.getQueueUrl(), 0, region);
        receivedHandle(queue.getQueueUrl(), 30, region);

        sqsService.changeMessageVisibility(queue.getQueueUrl(), older, 30, region);
        sqsService.deleteMessage(queue.getQueueUrl(), older, region);

        assertTrue(sqsService.peekMessages(queue.getQueueUrl(), region).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "changeVisibility"})
    void fifoQueueRejectsAnOlderReceiptHandleAsExpired(String operation) {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-stale.fifo", Map.of("FifoQueue", "true"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "g", "d", region);
        String older = receivedHandle(queue.getQueueUrl(), 0, region);
        receivedHandle(queue.getQueueUrl(), 30, region);

        AwsException ex = assertThrows(AwsException.class,
                () -> useReceiptHandle(operation, queue.getQueueUrl(), older, region));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Value " + older + " for parameter ReceiptHandle is invalid. Reason: The receipt handle has expired.",
                ex.getMessage());
        assertEquals(1, sqsService.peekMessages(queue.getQueueUrl(), region).size());
    }

    @Test
    void fifoQueueRejectsTheHandleOfAReceiveWhoseVisibilityEnded() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-visible.fifo", Map.of("FifoQueue", "true"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "g", "d", region);
        String handle = receivedHandle(queue.getQueueUrl(), 0, region);

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.deleteMessageInBatch(queue.getQueueUrl(), handle, region));

        assertEquals("ReceiptHandleIsInvalid", ex.getErrorCode());
        assertEquals("The receipt handle has expired", ex.getMessage());
    }

    @Test
    void fifoQueueDeletesWithTheCurrentReceiptHandle() {
        String region = "us-east-1";
        Queue queue = sqsService.createQueue("handle-current.fifo", Map.of("FifoQueue", "true"), region);
        sqsService.sendMessage(queue.getQueueUrl(), "msg", 0, "g", "d", region);
        String handle = receivedHandle(queue.getQueueUrl(), 30, region);

        sqsService.changeMessageVisibility(queue.getQueueUrl(), handle, 60, region);
        sqsService.deleteMessage(queue.getQueueUrl(), handle, region);

        assertTrue(sqsService.peekMessages(queue.getQueueUrl(), region).isEmpty());
    }

    @Test
    void receiptHandleFromBeforeAMoveTaskDoesNotReachTheMovedMessage() throws Exception {
        String region = "us-east-1";
        sqsService.createQueue("handle-moved-dlq", null, region);
        String dlqArn = queueArn("handle-moved-dlq");
        Queue source = sqsService.createQueue("handle-moved-src",
                Map.of("RedrivePolicy", "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"1\"}"),
                region);
        Message sent = sqsService.sendMessage(source.getQueueUrl(), "msg", 0, region);
        String handle = receivedHandle(source.getQueueUrl(), 0, region);
        sqsService.receiveMessage(source.getQueueUrl(), 1, 0, 0, region);
        String taskHandle = sqsService.startMessageMoveTask(dlqArn, null, region);
        awaitMoveTaskStatus(dlqArn, taskHandle, "COMPLETED");

        AwsException ex = assertThrows(AwsException.class,
                () -> sqsService.changeMessageVisibility(source.getQueueUrl(), handle, 10, region));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        sqsService.deleteMessage(source.getQueueUrl(), handle, region);

        List<Message> moved = sqsService.peekMessages(source.getQueueUrl(), region);
        assertEquals(1, moved.size());
        assertNotEquals(sent.getMessageId(), moved.get(0).getMessageId());
    }

    @Test
    void receiptHandleStillWorksAfterARestart() {
        String region = "us-east-1";
        InMemoryStorage<String, Queue> queueStore = new InMemoryStorage<>();
        InMemoryStorage<String, List<Message>> messageStore = new InMemoryStorage<>();
        RegionResolver regionResolver = new RegionResolver(region, "000000000000");
        SqsService before = new SqsService(queueStore, messageStore, new InMemoryStorage<>(),
                30, 1048576, BASE_URL, regionResolver);
        Queue queue = before.createQueue("handle-restart", null, region);
        before.sendMessage(queue.getQueueUrl(), "msg", 0, region);
        String handle = before.receiveMessage(queue.getQueueUrl(), 1, 30, 0, region).get(0).getReceiptHandle();

        SqsService after = new SqsService(queueStore, messageStore, new InMemoryStorage<>(),
                30, 1048576, BASE_URL, regionResolver);
        after.deleteMessage(queue.getQueueUrl(), handle, region);

        assertTrue(after.peekMessages(queue.getQueueUrl(), region).isEmpty());
    }

    private String receivedHandle(String queueUrl, int visibilityTimeout, String region) {
        return sqsService.receiveMessage(queueUrl, 1, visibilityTimeout, 0, region).get(0).getReceiptHandle();
    }

    private void useReceiptHandle(String operation, String queueUrl, String receiptHandle, String region) {
        if ("delete".equals(operation)) {
            sqsService.deleteMessage(queueUrl, receiptHandle, region);
        } else {
            sqsService.changeMessageVisibility(queueUrl, receiptHandle, 10, region);
        }
    }

    @Test
    void copyForRedrivePreservesSenderId() {
        Message message = new Message("body");
        message.setSenderId("AIDAEXAMPLE");
        Message copy = message.copyForRedrive();
        assertEquals("AIDAEXAMPLE", copy.getSenderId());
    }

    @Test
    void sendMessageStampsSenderIdFromCallerPrincipal() {
        String region = "us-east-1";
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        RequestContext requestContext = new RequestContext();
        requestContext.setAccessKeyId("AKIAIOSFODNN7EXAMPLE");
        requestContext.setAccountId("123456789012");
        when(requestContextInstance.get()).thenReturn(requestContext);

        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerUserId(eq("AKIAIOSFODNN7EXAMPLE"), any())).thenReturn(Optional.of("AIDAUSER123"));

        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, clock,
                requestContextInstance, iamService);
        Queue queue = service.createQueue("sender-id-queue", null, region);

        assertEquals("AIDAUSER123", service.resolveCallerSenderId(queue.getQueueUrl()));
    }

    @Test
    void sendMessageWithoutExplicitSenderIdIgnoresRequestScopeCaller() {
        String region = "us-east-1";
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        RequestContext requestContext = new RequestContext();
        requestContext.setAccessKeyId("AKIAIOSFODNN7EXAMPLE");
        requestContext.setAccountId("123456789012");
        when(requestContextInstance.get()).thenReturn(requestContext);

        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerUserId(eq("AKIAIOSFODNN7EXAMPLE"), any())).thenReturn(Optional.of("AIDAUSER123"));

        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, clock,
                requestContextInstance, iamService);
        Queue queue = service.createQueue("internal-producer-queue", null, region);
        Message message = service.sendMessage(queue.getQueueUrl(), "hello", 0, region);

        assertEquals(service.senderIdFor(queue.getQueueUrl()), message.getSenderId());
    }

    @Test
    void sendMessageFallsBackToAccountIdWhenNoCallerPrincipal() {
        String region = "us-east-1";
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        RequestContext requestContext = new RequestContext();
        requestContext.setAccessKeyId("AKIAOTHERKEY");
        requestContext.setAccountId("123456789012");
        when(requestContextInstance.get()).thenReturn(requestContext);

        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerUserId(eq("AKIAOTHERKEY"), any())).thenReturn(Optional.empty());

        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, clock,
                requestContextInstance, iamService);
        Queue queue = service.createQueue("sender-id-fallback-queue", null, region);

        assertEquals("123456789012", service.resolveCallerSenderId(queue.getQueueUrl()));
    }

    @Test
    void sendMessageOutsideRequestContextFallsBackToQueueOwnerAccount() {
        String region = "us-east-1";
        RegionResolver customResolver = new RegionResolver("us-east-1", "999988887777");
        SqsService service = new SqsService(new InMemoryStorage<>(), null, null, null,
                30, 1048576, BASE_URL, customResolver, false, null, clock,
                ReceiptHandle.DEFAULT_SECRET, null, null);
        Queue queue = service.createQueue("foreign-account-queue", null, region);
        Message message = service.sendMessage(queue.getQueueUrl(), "hello", 0, region);

        assertEquals("999988887777", message.getSenderId());
    }

    @Test
    void sendMessageUsesExplicitSenderIdWhenProvided() {
        String region = "us-east-1";
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        RequestContext requestContext = new RequestContext();
        requestContext.setAccessKeyId("AKIAIOSFODNN7EXAMPLE");
        requestContext.setAccountId("123456789012");
        when(requestContextInstance.get()).thenReturn(requestContext);

        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerUserId(eq("AKIAIOSFODNN7EXAMPLE"), any())).thenReturn(Optional.of("AIDAUSER123"));

        SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576, BASE_URL, clock,
                requestContextInstance, iamService);
        Queue queue = service.createQueue("sender-id-explicit-queue", null, region);
        Message message = service.sendMessage(queue.getQueueUrl(), "hello", 0, null, null, null, null,
                "sns.amazonaws.com", region);

        assertEquals("sns.amazonaws.com", message.getSenderId());
    }
}
