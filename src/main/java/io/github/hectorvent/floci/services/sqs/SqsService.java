package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class SqsService {

    private static final Logger LOG = Logger.getLogger(SqsService.class);
    private static final int DEDUP_WINDOW_SECONDS = 300; // 5 minutes

    private final StorageBackend<String, Queue> queueStore;
    private final StorageBackend<String, List<Message>> messageStore;
    private final StorageBackend<String, Map<String, Long>> dedupStore;
    private final ConcurrentHashMap<String, GuardedMessageQueue> messagesByQueue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> queueLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RedrivePolicy> redrivePolicyCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>> deduplicationCache = new ConcurrentHashMap<>();
    /** Move tasks keyed by opaque task handle. */
    private final ConcurrentHashMap<String, MoveTask> moveTasksByHandle = new ConcurrentHashMap<>();
    /** Per-task cancellation flag the move worker polls between iterations. */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean> moveTaskCancellation =
            new ConcurrentHashMap<>();
    /** Move tasks execute on a background thread so MaxNumberOfMessagesPerSecond can throttle
     * and CancelMessageMoveTask has something to interrupt. One thread per task is sufficient. */
    private final java.util.concurrent.ExecutorService moveTaskExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sqs-move-task");
                t.setDaemon(true);
                return t;
            });
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private static final HexFormat HEX = HexFormat.of();

    private record RedrivePolicy(int maxReceiveCount, String deadLetterTargetArn) {
    }

    public record MoveTask(String taskHandle, String sourceArn, String destinationArn,
                           int maxNumberOfMessagesPerSecond, String status,
                           long approximateNumberOfMessagesMoved,
                           long approximateNumberOfMessagesToMove,
                           long startedTimestampMillis, String failureReason) {
    }

    private final int defaultVisibilityTimeout;
    private final int maxMessageSize;
    private final String baseUrl;
    private final RegionResolver regionResolver;
    private final boolean clearFifoDeduplicationCacheOnPurge;
    private final SnsService snsService;

    @Inject
    public SqsService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                      SnsService snsService) {
        this(
                storageFactory.create("sqs", "sqs-queues.json",
                        new TypeReference<Map<String, Queue>>() {
                        }),
                storageFactory.create("sqs", "sqs-messages.json",
                        new TypeReference<Map<String, List<Message>>>() {
                        }),
                storageFactory.create("sqs", "sqs-dedup.json",
                        new TypeReference<Map<String, Map<String, Long>>>() {
                        }),
                config.services().sqs().defaultVisibilityTimeout(),
                config.services().sqs().maxMessageSize(),
                config.effectiveBaseUrl(),
                regionResolver,
                config.services().sqs().clearFifoDeduplicationCacheOnPurge(),
                snsService
        );
    }

    /**
     * Package-private constructor for testing.
     */
    SqsService(StorageBackend<String, Queue> queueStore,
               int defaultVisibilityTimeout, int maxMessageSize, String baseUrl) {
        this(queueStore, null, null, defaultVisibilityTimeout, maxMessageSize, baseUrl,
                new RegionResolver("us-east-1", "000000000000"), false, null);
    }

    SqsService(StorageBackend<String, Queue> queueStore, StorageBackend<String, List<Message>> messageStore,
               StorageBackend<String, Map<String, Long>> dedupStore,
               int defaultVisibilityTimeout, int maxMessageSize, String baseUrl,
               RegionResolver regionResolver) {
        this(queueStore, messageStore, dedupStore, defaultVisibilityTimeout, maxMessageSize, baseUrl,
                regionResolver, false, null);
    }

    SqsService(StorageBackend<String, Queue> queueStore, StorageBackend<String, List<Message>> messageStore,
               StorageBackend<String, Map<String, Long>> dedupStore,
               int defaultVisibilityTimeout, int maxMessageSize, String baseUrl,
               RegionResolver regionResolver, boolean clearFifoDeduplicationCacheOnPurge,
               SnsService snsService) {
        this.queueStore = queueStore;
        this.messageStore = messageStore;
        this.dedupStore = dedupStore;
        this.defaultVisibilityTimeout = defaultVisibilityTimeout;
        this.maxMessageSize = maxMessageSize;
        this.baseUrl = baseUrl;
        this.regionResolver = regionResolver;
        this.clearFifoDeduplicationCacheOnPurge = clearFifoDeduplicationCacheOnPurge;
        this.snsService = snsService;
        loadPersistedMessages();
        loadPersistedDedup();
    }

    private void loadPersistedMessages() {
        if (messageStore == null) {
            return;
        }
        if (messageStore instanceof AccountAwareStorageBackend<List<Message>> aware) {
            aware.scanAllAccountsAsMap().forEach((key, msgs) ->
                    messagesByQueue.put(key, new GuardedMessageQueue(msgs, messageStore, key)));
        } else {
            for (String key : messageStore.keys()) {
                messageStore.get(key).ifPresent(msgs ->
                        messagesByQueue.put(key, new GuardedMessageQueue(msgs, messageStore, key)));
            }
        }
    }

    private void loadPersistedDedup() {
        if (dedupStore == null) {
            return;
        }
        Instant now = Instant.now();
        if (dedupStore instanceof AccountAwareStorageBackend<Map<String, Long>> aware) {
            aware.scanAllAccountsAsMap().forEach((key, entries) -> loadDedupEntries(key, entries, now));
        } else {
            for (String key : dedupStore.keys()) {
                dedupStore.get(key).ifPresent(entries -> loadDedupEntries(key, entries, now));
            }
        }
    }

    private void loadDedupEntries(String key, Map<String, Long> entries, Instant now) {
        ConcurrentHashMap<String, Instant> active = new ConcurrentHashMap<>();
        entries.forEach((dedupId, expiryMs) -> {
            Instant expiry = Instant.ofEpochMilli(expiryMs);
            if (now.isBefore(expiry)) {
                active.put(dedupId, expiry);
            }
        });
        if (!active.isEmpty()) {
            deduplicationCache.put(key, active);
        }
    }

    private GuardedMessageQueue getOrCreateQueue(String storageKey) {
        return messagesByQueue.computeIfAbsent(storageKey,
                k -> new GuardedMessageQueue(messageStore, k));
    }

    private void persistDedup(String storageKey) {
        if (dedupStore == null) {
            return;
        }
        var dedupMap = deduplicationCache.get(storageKey);
        if (dedupMap != null && !dedupMap.isEmpty()) {
            Map<String, Long> serializable = new HashMap<>();
            dedupMap.forEach((id, expiry) -> serializable.put(id, expiry.toEpochMilli()));
            dedupStore.put(storageKey, serializable);
        } else {
            dedupStore.delete(storageKey);
        }
    }

    public Queue createQueue(String queueName, Map<String, String> attributes) {
        return createQueue(queueName, attributes, null, regionResolver.getDefaultRegion());
    }

    public Queue createQueue(String queueName, Map<String, String> attributes, String region) {
        return createQueue(queueName, attributes, null, region);
    }

    public Queue createQueue(String queueName, Map<String, String> attributes, Map<String, String> tags, String region) {

        boolean fifoRequested = attributes != null && "true".equalsIgnoreCase(attributes.get("FifoQueue"));
        boolean hasFifoSuffix = queueName != null && queueName.endsWith(".fifo");
        if (fifoRequested && !hasFifoSuffix) {
            throw new AwsException("InvalidParameterValue",
                    "The name of a FIFO queue can only end with '.fifo'.", 400);
        }
        if (hasFifoSuffix && !fifoRequested) {
            // Auto-set FifoQueue attribute when name ends with .fifo
            attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
            attributes.put("FifoQueue", "true");
        }

        String accountId = regionResolver.getAccountId();
        String queueUrl = baseUrl + "/" + accountId + "/" + queueName;
        String storageKey = regionKey(region, queueUrl);

        // If queue already exists with same name, check for attribute conflicts
        Queue existing = queueStore.get(storageKey).orElse(null);
        if (existing != null) {
            if (attributes != null && !attributes.isEmpty()) {
                Set<String> readOnlyAttrs = Set.of("QueueArn", "CreatedTimestamp", "LastModifiedTimestamp",
                        "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible",
                        "ApproximateNumberOfMessagesDelayed");
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    if (readOnlyAttrs.contains(entry.getKey())) {
                        continue;
                    }
                    String storedValue = existing.getAttributes().get(entry.getKey());
                    if (storedValue != null && !storedValue.equals(entry.getValue())) {
                        throw new AwsException("QueueAlreadyExists",
                                "A queue already exists with the same name but different attributes.", 400);
                    }
                }
            }
            return existing;
        }

        Queue queue = new Queue(queueName, queueUrl);
        queue.setAccountId(regionResolver.getAccountId());
        if (attributes != null) {
            queue.getAttributes().putAll(attributes);
        }
        if (tags != null) {
            queue.getTags().putAll(tags);
        }
        // Set default attributes
        queue.getAttributes().putIfAbsent("VisibilityTimeout", String.valueOf(defaultVisibilityTimeout));
        queue.getAttributes().putIfAbsent("MaximumMessageSize", String.valueOf(maxMessageSize));
        queue.getAttributes().putIfAbsent("DelaySeconds", "0");
        queue.getAttributes().putIfAbsent("MessageRetentionPeriod", "345600");
        if (queue.isFifo()) {
            queue.getAttributes().putIfAbsent("ContentBasedDeduplication", "false");
        }

        queueStore.put(storageKey, queue);
        messagesByQueue.put(storageKey, new GuardedMessageQueue(messageStore, storageKey));
        LOG.infov("Created {0} queue: {1} in region {2}", queue.isFifo() ? "FIFO" : "standard", queueName, region);
        return queue;
    }

    public void deleteQueue(String queueUrl) {
        deleteQueue(queueUrl, regionResolver.getDefaultRegion());
    }

    public void deleteQueue(String queueUrl, String region) {
        String storageKey = regionKey(region, queueUrl);
        if (queueStore.get(storageKey).isEmpty()) {
            throw new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                    "The specified queue does not exist.", 400);
        }
        queueStore.delete(storageKey);
        var removed = messagesByQueue.remove(storageKey);
        if (removed != null) {
            removed.close();
        }
        deduplicationCache.remove(storageKey);
        if (messageStore != null) {
            messageStore.delete(storageKey);
        }
        if (dedupStore != null) {
            dedupStore.delete(storageKey);
        }
        LOG.infov("Deleted queue: {0}", queueUrl);
    }

    public List<Queue> listQueues(String namePrefix) {
        return listQueues(namePrefix, regionResolver.getDefaultRegion());
    }

    public List<Queue> listQueues(String namePrefix, String region) {
        String prefix = region + "::";
        if (namePrefix == null || namePrefix.isEmpty()) {
            return queueStore.scan(key -> key.startsWith(prefix));
        }
        return queueStore.scan(key -> {
            if (!key.startsWith(prefix)) {
                return false;
            }
            String queueUrl = key.substring(prefix.length());
            String name = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
            return name.startsWith(namePrefix);
        });
    }

    public String getQueueUrl(String queueName) {
        return getQueueUrl(queueName, regionResolver.getDefaultRegion());
    }

    public String getQueueUrl(String queueName, String region) {
        String accountId = regionResolver.getAccountId();
        String queueUrl = baseUrl + "/" + accountId + "/" + queueName;
        String storageKey = regionKey(region, queueUrl);
        if (queueStore.get(storageKey).isEmpty()) {
            throw new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                    "The specified queue does not exist for this wsdl version.", 400);
        }
        return queueUrl;
    }

    public Map<String, String> getQueueAttributes(String queueUrl, List<String> attributeNames) {
        return getQueueAttributes(queueUrl, attributeNames, regionResolver.getDefaultRegion());
    }

    public Map<String, String> getQueueAttributes(String queueUrl, List<String> attributeNames, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        Map<String, String> attrs = new java.util.LinkedHashMap<>(queue.getAttributes());
        // Add computed attributes
        attrs.put("QueueArn", regionResolver.buildArn("sqs", region, queue.getQueueName()));
        attrs.put("CreatedTimestamp", String.valueOf(queue.getCreatedTimestamp().getEpochSecond()));
        attrs.put("LastModifiedTimestamp", String.valueOf(queue.getLastModifiedTimestamp().getEpochSecond()));

        var counts = getOrCreateQueue(storageKey).messageCounts();
        attrs.put("ApproximateNumberOfMessages", String.valueOf(counts.visible()));
        attrs.put("ApproximateNumberOfMessagesNotVisible", String.valueOf(counts.inFlight()));

        if (attributeNames == null || attributeNames.contains("All")) {
            return attrs;
        }
        var filtered = new java.util.LinkedHashMap<String, String>();
        for (String name : attributeNames) {
            if (attrs.containsKey(name)) {
                filtered.put(name, attrs.get(name));
            }
        }
        return filtered;
    }

    public Message sendMessage(String queueUrl, String body, int delaySeconds) {
        return sendMessage(queueUrl, body, delaySeconds, null, null);
    }

    public Message sendMessage(String queueUrl, String body, int delaySeconds, String region) {
        return sendMessage(queueUrl, body, delaySeconds, null, null, region);
    }

    public Message sendMessage(String queueUrl, String body, int delaySeconds,
                               String messageGroupId, String messageDeduplicationId) {
        return sendMessage(queueUrl, body, delaySeconds, messageGroupId, messageDeduplicationId, null,
                regionResolver.getDefaultRegion());
    }

    public Message sendMessage(String queueUrl, String body, int delaySeconds,
                               String messageGroupId, String messageDeduplicationId,
                               String region) {
        return sendMessage(queueUrl, body, delaySeconds, messageGroupId, messageDeduplicationId, null, region);
    }

    public Message sendMessage(String queueUrl, String body, int delaySeconds,
                               String messageGroupId, String messageDeduplicationId,
                               Map<String, MessageAttributeValue> messageAttributes,
                               String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = getQueueByUrl(storageKey, queueUrl)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        int queueMaxMessageSize = parseMaxMessageSize(queue.getAttributes().get("MaximumMessageSize"));
        int totalSize = computeMessageSize(body, messageAttributes);
        if (totalSize > queueMaxMessageSize) {
            throw new AwsException("InvalidParameterValue",
                    "One or more parameters are invalid. " +
                            "Reason: Message must be shorter than " + queueMaxMessageSize + " bytes.", 400);
        }

        int queueDelaySeconds = parseDelaySecondsAttribute(queue.getAttributes().get("DelaySeconds"));

        // Resolve the effective delay:
        //   - FIFO queues only support queue-level DelaySeconds per AWS SQS,
        //     so any per-message value is ignored and we always use the
        //     queue attribute. Without this, FIFO silently dropped the
        //     queue-level default (issue #475).
        //   - Standard queues honor per-message DelaySeconds when provided
        //     (> 0). Applying the queue-level default on the standard path
        //     requires distinguishing "omitted" from "explicit 0" in the
        //     handlers, which the current int-parameter API cannot express;
        //     that's left as follow-up work -- this patch only addresses
        //     the FIFO regression called out in the issue.
        int effectiveDelaySeconds = queue.isFifo() ? queueDelaySeconds : delaySeconds;

        // FIFO queue validation
        if (queue.isFifo()) {
            if (messageGroupId == null || messageGroupId.isEmpty()) {
                throw new AwsException("MissingParameter",
                        "The request must contain the parameter MessageGroupId.", 400);
            }
            // Resolve deduplication ID
            String dedupId = messageDeduplicationId;
            if (dedupId == null || dedupId.isEmpty()) {
                if ("true".equalsIgnoreCase(queue.getAttributes().get("ContentBasedDeduplication"))) {
                    dedupId = computeMd5(body);
                } else {
                    throw new AwsException("InvalidParameterValue",
                            "The queue should either have ContentBasedDeduplication enabled or " +
                                    "MessageDeduplicationId provided.", 400);
                }
            }

            // Check deduplication window — atomic putIfAbsent to avoid race condition
            cleanupDeduplicationCache(storageKey);
            var dedupMap = deduplicationCache.computeIfAbsent(storageKey, k -> new ConcurrentHashMap<>());
            Instant expiry = Instant.now().plusSeconds(DEDUP_WINDOW_SECONDS);
            Instant previous = dedupMap.putIfAbsent(dedupId, expiry);
            persistDedup(storageKey);
            if (previous != null && Instant.now().isBefore(previous)) {
                // Duplicate within window — keep the original messageId and
                // sequenceNumber but compute response MD5s from this request's
                // body and attributes, otherwise SDK clients (which validate
                // MD5 against what they sent) reject the response.
                Message existing = getOrCreateQueue(storageKey).findByDeduplicationId(dedupId);
                if (existing != null) {
                    Message response = new Message(body);
                    response.setMessageId(existing.getMessageId());
                    response.setMessageGroupId(messageGroupId);
                    response.setMessageDeduplicationId(dedupId);
                    response.setSequenceNumber(existing.getSequenceNumber());
                    if (messageAttributes != null && !messageAttributes.isEmpty()) {
                        response.getMessageAttributes().putAll(messageAttributes);
                        response.updateMd5OfMessageAttributes();
                    }
                    return response;
                }
            }

            Message message = new Message(body);
            message.setMessageGroupId(messageGroupId);
            message.setMessageDeduplicationId(dedupId);
            message.setSequenceNumber(sequenceCounter.incrementAndGet());
            if (effectiveDelaySeconds > 0) {
                message.setVisibleAt(Instant.now().plusSeconds(effectiveDelaySeconds));
            }
            if (messageAttributes != null && !messageAttributes.isEmpty()) {
                message.getMessageAttributes().putAll(messageAttributes);
                message.updateMd5OfMessageAttributes();
            }

            getOrCreateQueue(storageKey).addMessage(message);
            notifyReceivers(storageKey);
            LOG.debugv("Sent FIFO message {0} to queue {1}, group={2}, seq={3}",
                    message.getMessageId(), queueUrl, messageGroupId, message.getSequenceNumber());
            LOG.tracev("Sent message {0} to queue {1} body={2} attributes={3}",
                    message.getMessageId(), queueUrl, body, message.getMessageAttributes());
            return message;
        }

        // Standard queue
        Message message = new Message(body);
        if (effectiveDelaySeconds > 0) {
            message.setVisibleAt(Instant.now().plusSeconds(effectiveDelaySeconds));
        }
        if (messageAttributes != null && !messageAttributes.isEmpty()) {
            message.getMessageAttributes().putAll(messageAttributes);
            message.updateMd5OfMessageAttributes();
        }

        getOrCreateQueue(storageKey).addMessage(message);
        notifyReceivers(storageKey);
        LOG.debugv("Sent message {0} to queue {1}", message.getMessageId(), queueUrl);
        LOG.tracev("Sent message {0} to queue {1} body={2} attributes={3}",
                message.getMessageId(), queueUrl, body, message.getMessageAttributes());
        return message;
    }

    private int parseMaxMessageSize(String value) {
        if (value == null || value.isEmpty()) {
            return maxMessageSize;
        }
        try {
            return Math.min(1048576, Math.max(1024, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return maxMessageSize;
        }
    }

    /**
     * Total wire-level message size, in bytes, matching AWS SQS accounting:
     * UTF-8 body bytes + per-attribute (name UTF-8 + type UTF-8 + value bytes).
     */
    private static int computeMessageSize(String body, Map<String, MessageAttributeValue> attributes) {
        int total = body == null ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (attributes == null || attributes.isEmpty()) {
            return total;
        }
        for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
            total += entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            MessageAttributeValue value = entry.getValue();
            if (value.getDataType() != null) {
                total += value.getDataType().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if (value.getBinaryValue() != null) {
                total += value.getBinaryValue().length;
            } else if (value.getStringValue() != null) {
                total += value.getStringValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    /**
     * Parse the queue-level DelaySeconds attribute. Returns 0 when the
     * attribute is null, empty, non-numeric, or negative -- the queue falls
     * back to "no default delay" rather than failing the SendMessage call.
     */
    private int parseDelaySecondsAttribute(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void notifyReceivers(String storageKey) {
        Object lock = queueLocks.get(storageKey);
        if (lock != null) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private void cleanupDeduplicationCache(String queueUrl) {
        var dedupMap = deduplicationCache.get(queueUrl);
        if (dedupMap != null) {
            Instant now = Instant.now();
            dedupMap.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        }
    }

    private static String computeMd5(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    public List<Message> receiveMessage(String queueUrl, int maxMessages, int visibilityTimeout, int waitTimeSeconds) {
        return receiveMessage(queueUrl, maxMessages, visibilityTimeout, waitTimeSeconds,
                regionResolver.getDefaultRegion());
    }

    public List<Message> receiveMessage(String queueUrl, int maxMessages, int visibilityTimeout,
                                        int waitTimeSeconds, String region) {
        String storageKey = regionKey(region, queueUrl);
        getQueueByUrl(storageKey, queueUrl)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        if (maxMessages < 1 || maxMessages > 10) {
            maxMessages = 1;
        }

        long start = System.currentTimeMillis();
        long maxWait = waitTimeSeconds * 1000L;
        Object lock = queueLocks.computeIfAbsent(storageKey, k -> new Object());

        while (true) {
            List<Message> result = doReceiveMessage(storageKey, maxMessages, visibilityTimeout, region);
            if (!result.isEmpty() || maxWait <= 0) {
                if (!result.isEmpty() && LOG.isTraceEnabled()) {
                    for (Message m : result) {
                        LOG.tracev("Received message {0} from queue {1} body={2} attributes={3}",
                                m.getMessageId(), queueUrl, m.getBody(), m.getMessageAttributes());
                    }
                }
                return result;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= maxWait) {
                return result;
            }
            try {
                synchronized (lock) {
                    lock.wait(Math.min(1000, maxWait - elapsed));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }

    private RedrivePolicy getOrParseRedrivePolicy(Queue queue, String storageKey) {
        String rawPolicy = queue.getAttributes().get("RedrivePolicy");
        if (rawPolicy == null) {
            redrivePolicyCache.remove(storageKey);
            return null;
        }

        return redrivePolicyCache.computeIfAbsent(storageKey, k -> {
            try {
                var rp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawPolicy);
                return new RedrivePolicy(
                        rp.has("maxReceiveCount") ? rp.get("maxReceiveCount").asInt() : -1,
                        rp.has("deadLetterTargetArn") ? rp.get("deadLetterTargetArn").asText() : null
                );
            } catch (Exception e) {
                LOG.warnv("Failed to parse RedrivePolicy for queue {0}", queue.getQueueUrl());
                return null;
            }
        });
    }

    private List<Message> doReceiveMessage(String storageKey, int maxMessages, int visibilityTimeout, String region) {
        Queue queue = queueStore.get(storageKey).orElse(null);
        if (queue == null) {
            return Collections.emptyList();
        }

        int effectiveTimeout;
        if (visibilityTimeout >= 0) {
            effectiveTimeout = visibilityTimeout;
        } else {
            String queueVt = queue.getAttributes().get("VisibilityTimeout");
            effectiveTimeout = queueVt != null ? Integer.parseInt(queueVt) : defaultVisibilityTimeout;
        }

        RedrivePolicy rp = getOrParseRedrivePolicy(queue, storageKey);
        int maxReceiveCount = rp != null ? rp.maxReceiveCount() : -1;
        String deadLetterTargetArn = rp != null ? rp.deadLetterTargetArn() : null;

        var guardedQueue = getOrCreateQueue(storageKey);
        var claimResult = guardedQueue.claimVisibleMessages(
                maxMessages, effectiveTimeout, queue.isFifo(), maxReceiveCount, deadLetterTargetArn);

        // Route DLQ candidates to the dead-letter queue only if the destination resolves
        if (!claimResult.dlqCandidates().isEmpty() && deadLetterTargetArn != null) {
            String dlqUrl = queueUrlFromArn(deadLetterTargetArn, region);
            if (dlqUrl != null) {
                var dlqCandidates = claimResult.dlqCandidates();
                guardedQueue.removeMessages(dlqCandidates);
                for (Message msg : dlqCandidates) {
                    msg.setVisibleAt(null);
                    msg.setReceiptHandle(null);
                    // Remember where the message came from so StartMessageMoveTask
                    // with no DestinationArn can put it back.
                    msg.setOriginalSourceQueueUrl(queue.getQueueUrl());
                }
                String dlqStorageKey = regionKey(region, dlqUrl);
                getOrCreateQueue(dlqStorageKey).addAll(dlqCandidates);
                LOG.infov("Moved {0} messages to DLQ {1}", dlqCandidates.size(), dlqUrl);
            }
        }

        return claimResult.claimed();
    }

    private String queueUrlFromArn(String arn, String region) {
        if (arn == null || !arn.startsWith("arn:aws:sqs:")) {
            return null;
        }
        try {
            return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<Message> peekMessages(String queueUrl) {
        return peekMessages(queueUrl, regionResolver.getDefaultRegion());
    }

    public List<Message> peekMessages(String queueUrl, String region) {
        String storageKey = regionKey(region, queueUrl);
        ensureQueueExists(storageKey);
        return getOrCreateQueue(storageKey).peekAll();
    }

    public void deleteMessage(String queueUrl, String receiptHandle) {
        deleteMessage(queueUrl, receiptHandle, regionResolver.getDefaultRegion());
    }

    public void deleteMessage(String queueUrl, String receiptHandle, String region) {
        String storageKey = regionKey(region, queueUrl);
        if (getQueueByUrl(storageKey, queueUrl).isEmpty()) {
            throw new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                    "The specified queue does not exist.", 400);
        }

        Optional<Message> removed = getOrCreateQueue(storageKey).removeByReceiptHandle(receiptHandle);

        if (removed.isEmpty()) {
            throw new AwsException("ReceiptHandleIsInvalid",
                    "The input receipt handle is not a valid receipt handle.", 400);
        }
        LOG.debugv("Deleted message with receipt handle {0}", receiptHandle);
        if (LOG.isTraceEnabled()) {
            Message m = removed.get();
            LOG.tracev("Deleted message {0} from queue {1} body={2}",
                    m.getMessageId(), queueUrl, m.getBody());
        }
    }

    public void changeMessageVisibility(String queueUrl, String receiptHandle, int visibilityTimeout) {
        changeMessageVisibility(queueUrl, receiptHandle, visibilityTimeout, regionResolver.getDefaultRegion());
    }

    public void changeMessageVisibility(String queueUrl, String receiptHandle, int visibilityTimeout, String region) {
        String storageKey = regionKey(region, queueUrl);
        ensureQueueExists(storageKey);

        boolean found = getOrCreateQueue(storageKey).changeVisibility(receiptHandle, visibilityTimeout);
        if (!found) {
            throw new AwsException("ReceiptHandleIsInvalid",
                    "The input receipt handle is not a valid receipt handle.", 400);
        }
    }

    public void purgeQueue(String queueUrl) {
        purgeQueue(queueUrl, regionResolver.getDefaultRegion());
    }

    public void purgeQueue(String queueUrl, String region) {
        String storageKey = regionKey(region, queueUrl);
        ensureQueueExists(storageKey);
        getOrCreateQueue(storageKey).purge();
        if (clearFifoDeduplicationCacheOnPurge) {
            deduplicationCache.remove(storageKey);
            if (dedupStore != null) {
                dedupStore.delete(storageKey);
            }
            if (snsService != null) {
                snsService.clearFifoDeduplicationCacheForSqsQueueSubscriptions(queueUrl, region);
            }
        }
        LOG.infov("Purged queue{0}: {1}",
                clearFifoDeduplicationCacheOnPurge ? " (dedup cache cleared)" : "", queueUrl);
    }

    public void setQueueAttributes(String queueUrl, Map<String, String> attributes, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    queue.getAttributes().remove(entry.getKey());
                } else {
                    queue.getAttributes().put(entry.getKey(), entry.getValue());
                }
            }
        }
        queue.setLastModifiedTimestamp(Instant.now());
        queueStore.put(storageKey, queue);
        LOG.infov("Updated attributes for queue: {0}", queueUrl);
    }

    public List<String> listDeadLetterSourceQueues(String queueUrl, String region) {
        ensureQueueExists(regionKey(region, queueUrl));
        String targetArn = regionResolver.buildArn("sqs", region, queueUrl.substring(queueUrl.lastIndexOf('/') + 1));

        List<String> sourceQueues = new ArrayList<>();
        String prefix = region + "::";
        for (Queue q : queueStore.scan(k -> k.startsWith(prefix))) {
            if (targetArn.equals(redriveDlqArn(q))) {
                sourceQueues.add(q.getQueueUrl());
            }
        }
        return sourceQueues;
    }

    public String startMessageMoveTask(String sourceArn, String destinationArn, String region) {
        return startMessageMoveTask(sourceArn, destinationArn, 0, region);
    }

    public String startMessageMoveTask(String sourceArn, String destinationArn,
                                       int maxNumberOfMessagesPerSecond, String region) {
        if (sourceArn == null) {
            throw new AwsException("InvalidParameterValue", "SourceArn is required.", 400);
        }
        String sourceUrl = queueUrlFromArn(sourceArn, region);
        if (sourceUrl == null) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid source ARN: " + sourceArn, 400);
        }
        String srcKey = regionKey(region, sourceUrl);
        if (queueStore.get(srcKey).isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "The resource that you specified for the SourceArn parameter does not exist.", 404);
        }
        // Source must be referenced as a DLQ by some other queue's RedrivePolicy.
        if (!isDeadLetterQueue(sourceArn, region)) {
            throw new AwsException("InvalidParameterValue",
                    "Source queue is not configured as a dead-letter queue.", 400);
        }
        // Reject a second task that races against a still-active one for the same source.
        // The move runs on a background worker, so RUNNING is the canonical "active" check;
        // the additional 1-second window covers the small gap between StartMessageMoveTask
        // returning and the worker flipping the task to RUNNING (so back-to-back start
        // calls that AWS would reject can't slip through).
        long now = System.currentTimeMillis();
        for (MoveTask existing : moveTasksByHandle.values()) {
            if (!sourceArn.equals(existing.sourceArn())) {
                continue;
            }
            if ("RUNNING".equals(existing.status())
                    || (now - existing.startedTimestampMillis()) < 1_000) {
                throw new AwsException("InvalidParameterValue",
                        "There is already a task running. Only one active task is allowed for a source queue arn at a given time.",
                        400);
            }
        }

        String destUrl = null;
        if (destinationArn != null) {
            destUrl = queueUrlFromArn(destinationArn, region);
            if (destUrl == null) {
                throw new AwsException("ResourceNotFoundException",
                        "The resource that you specified for the DestinationArn parameter does not exist.", 404);
            }
            String destKey = regionKey(region, destUrl);
            if (queueStore.get(destKey).isEmpty()) {
                throw new AwsException("ResourceNotFoundException",
                        "The resource that you specified for the DestinationArn parameter does not exist.", 404);
            }
        }

        var srcQueueInitial = getOrCreateQueue(srcKey);
        long toMove = srcQueueInitial.messageCounts().visible()
                + srcQueueInitial.messageCounts().inFlight();

        String taskHandle = "task-" + UUID.randomUUID();
        moveTasksByHandle.put(taskHandle, new MoveTask(
                taskHandle, sourceArn, destinationArn,
                maxNumberOfMessagesPerSecond, "RUNNING",
                0L, toMove, System.currentTimeMillis(), null));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        moveTaskCancellation.put(taskHandle, cancelled);

        // Move the first message synchronously inside the request scope so callers
        // that poll the destination immediately after StartMessageMoveTask returns
        // see motion without racing against the executor's thread startup. The
        // remainder runs on the background worker at the requested rate, draining
        // one message at a time so the source queue stays observably populated.
        long initialMoved = 0;
        Message firstMsg = srcQueueInitial.drainOne();
        if (firstMsg != null && deliverMovedMessage(firstMsg, destUrl, region)) {
            initialMoved = 1;
            updateMoveTaskCounter(taskHandle, initialMoved);
        }

        final String destUrlFinal = destUrl;
        final long initialMovedFinal = initialMoved;
        moveTaskExecutor.submit(() -> runMoveTask(taskHandle, srcKey,
                destUrlFinal, maxNumberOfMessagesPerSecond, region, sourceArn,
                destinationArn, cancelled, initialMovedFinal));

        return taskHandle;
    }

    /** Place a single drained message in its destination according to the rules of
     *  StartMessageMoveTask (explicit destination, or the per-message origin
     *  recorded when it was DLQ'd). Resets per-receive state so the message
     *  starts a fresh life. Returns true when the message was placed. */
    private boolean deliverMovedMessage(Message msg, String destUrl, String region) {
        msg.setReceiveCount(0);
        msg.setFirstReceiveTimestamp(null);
        msg.setReceiptHandle(null);
        msg.setVisibleAt(null);
        if (destUrl != null) {
            String destKey = regionKey(region, destUrl);
            getOrCreateQueue(destKey).addAll(List.of(msg));
            return true;
        }
        if (msg.getOriginalSourceQueueUrl() != null) {
            // No request scope on the background worker, so queueStore.get() resolves
            // to the default account prefix and can't verify the destination. The
            // in-memory messagesByQueue map is keyed by the full URL (which carries
            // the account), so addAll on the right storage key lands the message in
            // the queue any future receive will see.
            String originKey = regionKey(region, msg.getOriginalSourceQueueUrl());
            getOrCreateQueue(originKey).addAll(List.of(msg));
            return true;
        }
        return false;
    }

    /** Background body of a move task; drains one message at a time so a
     *  positive MaxNumberOfMessagesPerSecond can throttle, CancelMessageMoveTask
     *  has a window to stop the work before it finishes, and the source queue
     *  stays observably populated for the duration of the move (rather than
     *  being drained into worker memory all at once). */
    private void runMoveTask(String taskHandle, String srcKey, String destUrl,
                             int maxRate, String region, String sourceArn,
                             String destinationArn,
                             java.util.concurrent.atomic.AtomicBoolean cancelled,
                             long initialMoved) {
        long intervalMillis = maxRate > 0 ? Math.max(1L, 1000L / maxRate) : 0L;
        long moved = initialMoved;
        try {
            var srcQueue = getOrCreateQueue(srcKey);
            while (!cancelled.get()) {
                if (intervalMillis > 0) {
                    try {
                        Thread.sleep(intervalMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (cancelled.get()) {
                        break;
                    }
                }
                Message msg = srcQueue.drainOne();
                if (msg == null) {
                    break;
                }
                if (deliverMovedMessage(msg, destUrl, region)) {
                    moved++;
                    updateMoveTaskCounter(taskHandle, moved);
                }
            }
        } finally {
            MoveTask cur = moveTasksByHandle.get(taskHandle);
            if (cur != null) {
                String status = cancelled.get() ? "CANCELLED" : "COMPLETED";
                moveTasksByHandle.put(taskHandle, new MoveTask(
                        cur.taskHandle(), cur.sourceArn(), cur.destinationArn(),
                        cur.maxNumberOfMessagesPerSecond(), status,
                        moved, cur.approximateNumberOfMessagesToMove(),
                        cur.startedTimestampMillis(), cur.failureReason()));
            }
            LOG.infov("Move task {0} {1}: moved {2} messages from {3} to {4}", taskHandle,
                    cancelled.get() ? "cancelled" : "completed", moved, sourceArn,
                    destinationArn != null ? destinationArn : "original source");
        }
    }

    private void updateMoveTaskCounter(String taskHandle, long moved) {
        MoveTask cur = moveTasksByHandle.get(taskHandle);
        if (cur == null) {
            return;
        }
        moveTasksByHandle.put(taskHandle, new MoveTask(
                cur.taskHandle(), cur.sourceArn(), cur.destinationArn(),
                cur.maxNumberOfMessagesPerSecond(), cur.status(),
                moved, cur.approximateNumberOfMessagesToMove(),
                cur.startedTimestampMillis(), cur.failureReason()));
    }

    public List<MoveTask> listMessageMoveTasks(String sourceArn, String region) {
        if (sourceArn == null) {
            return List.of();
        }
        List<MoveTask> matching = new ArrayList<>();
        for (MoveTask t : moveTasksByHandle.values()) {
            if (sourceArn.equals(t.sourceArn())) {
                matching.add(t);
            }
        }
        matching.sort(Comparator.comparingLong(MoveTask::startedTimestampMillis).reversed());
        return matching;
    }

    public long cancelMessageMoveTask(String taskHandle, String region) {
        if (taskHandle == null) {
            throw new AwsException("InvalidParameterValue", "TaskHandle is required.", 400);
        }
        MoveTask task = moveTasksByHandle.get(taskHandle);
        if (task == null) {
            throw new AwsException("ResourceNotFoundException",
                    "The task you specified does not exist.", 404);
        }
        // Signal the background worker to stop. The worker flips status to CANCELLED in
        // its finally block and updates the moved counter; read both back here.
        var flag = moveTaskCancellation.get(taskHandle);
        if (flag != null) {
            flag.set(true);
        }
        return moveTasksByHandle.getOrDefault(taskHandle, task).approximateNumberOfMessagesMoved();
    }

    private boolean isDeadLetterQueue(String queueArn, String region) {
        String prefix = region + "::";
        for (Queue q : queueStore.scan(k -> k.startsWith(prefix))) {
            if (queueArn.equals(redriveDlqArn(q))) {
                return true;
            }
        }
        return false;
    }

    private static String redriveDlqArn(Queue queue) {
        String raw = queue.getAttributes().get("RedrivePolicy");
        if (raw == null) {
            return null;
        }
        try {
            JsonNode policy = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            JsonNode dlqArn = policy.get("deadLetterTargetArn");
            return dlqArn != null && !dlqArn.isNull() ? dlqArn.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public record ChangeVisibilityBatchEntry(String id, String receiptHandle, int visibilityTimeout) {
    }

    public record BatchResultEntry(String id, boolean success, String errorCode, String errorMessage) {
    }

    public List<BatchResultEntry> changeMessageVisibilityBatch(String queueUrl,
                                                               List<ChangeVisibilityBatchEntry> entries, String region) {
        ensureQueueExists(regionKey(region, queueUrl));
        List<BatchResultEntry> results = new ArrayList<>();
        for (var entry : entries) {
            try {
                changeMessageVisibility(queueUrl, entry.receiptHandle(), entry.visibilityTimeout(), region);
                results.add(new BatchResultEntry(entry.id(), true, null, null));
            } catch (AwsException e) {
                results.add(new BatchResultEntry(entry.id(), false, e.getErrorCode(), e.getMessage()));
            }
        }
        return results;
    }

    public void tagQueue(String queueUrl, Map<String, String> tags, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        if (tags != null) {
            queue.getTags().putAll(tags);
        }
        queueStore.put(storageKey, queue);
        LOG.infov("Tagged queue: {0}", queueUrl);
    }

    public void untagQueue(String queueUrl, List<String> tagKeys, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        if (tagKeys != null) {
            for (String key : tagKeys) {
                queue.getTags().remove(key);
            }
        }
        queueStore.put(storageKey, queue);
        LOG.infov("Untagged queue: {0}", queueUrl);
    }

    private static final ObjectMapper POLICY_MAPPER = new ObjectMapper();

    public void addPermission(String queueUrl, String label, List<String> awsAccountIds,
                              List<String> actionNames, String region) {
        if (label == null || label.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Value for parameter Label is invalid.", 400);
        }
        if (awsAccountIds == null || awsAccountIds.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter AWSAccountId.", 400);
        }
        if (actionNames == null || actionNames.isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter ActionName.", 400);
        }

        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        ObjectNode policy = parsePolicyOrEmpty(queue.getAttributes().get("Policy"));
        ArrayNode statements = ensureStatementArray(policy);

        for (JsonNode stmt : statements) {
            if (label.equals(stmt.path("Sid").asText(null))) {
                throw new AwsException("InvalidParameterValue",
                        "Value " + label + " for parameter Label is invalid. " +
                                "Reason: Already exists.", 400);
            }
        }

        ObjectNode statement = POLICY_MAPPER.createObjectNode();
        statement.put("Sid", label);
        statement.put("Effect", "Allow");
        ObjectNode principal = statement.putObject("Principal");
        ArrayNode awsArns = principal.putArray("AWS");
        for (String accountId : awsAccountIds) {
            awsArns.add("arn:aws:iam::" + accountId + ":root");
        }
        ArrayNode actions = statement.putArray("Action");
        for (String action : actionNames) {
            actions.add("SQS:" + action);
        }
        statement.put("Resource", regionResolver.buildArn("sqs", region, queue.getQueueName()));
        statements.add(statement);

        queue.getAttributes().put("Policy", policy.toString());
        queue.setLastModifiedTimestamp(Instant.now());
        queueStore.put(storageKey, queue);
        LOG.infov("Added permission {0} to queue {1}", label, queueUrl);
    }

    public void removePermission(String queueUrl, String label, String region) {
        if (label == null || label.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Value for parameter Label is invalid.", 400);
        }

        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        ObjectNode policy = parsePolicyOrEmpty(queue.getAttributes().get("Policy"));
        ArrayNode statements = ensureStatementArray(policy);

        int removeIndex = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (label.equals(statements.get(i).path("Sid").asText(null))) {
                removeIndex = i;
                break;
            }
        }
        if (removeIndex < 0) {
            throw new AwsException("InvalidParameterValue",
                    "Value " + label + " for parameter Label is invalid. " +
                            "Reason: can't find label on existing policy.", 400);
        }
        statements.remove(removeIndex);

        if (statements.isEmpty()) {
            queue.getAttributes().remove("Policy");
        } else {
            queue.getAttributes().put("Policy", policy.toString());
        }
        queue.setLastModifiedTimestamp(Instant.now());
        queueStore.put(storageKey, queue);
        LOG.infov("Removed permission {0} from queue {1}", label, queueUrl);
    }

    private static ObjectNode parsePolicyOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            ObjectNode policy = POLICY_MAPPER.createObjectNode();
            policy.put("Version", "2012-10-17");
            policy.putArray("Statement");
            return policy;
        }
        JsonNode parsed;
        try {
            parsed = POLICY_MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AwsException("InvalidAttributeValue",
                    "Invalid value for the parameter Policy.", 400);
        }
        if (!(parsed instanceof ObjectNode obj)) {
            throw new AwsException("InvalidAttributeValue",
                    "Invalid value for the parameter Policy.", 400);
        }
        if (!obj.has("Version")) {
            obj.put("Version", "2012-10-17");
        }
        return obj;
    }

    private static ArrayNode ensureStatementArray(ObjectNode policy) {
        JsonNode existing = policy.get("Statement");
        if (existing instanceof ArrayNode arr) {
            return arr;
        }
        ArrayNode arr = policy.putArray("Statement");
        if (existing instanceof ObjectNode singleStatement) {
            arr.add(singleStatement);
        }
        return arr;
    }

    public Map<String, String> listQueueTags(String queueUrl, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        return new java.util.LinkedHashMap<>(queue.getTags());
    }

    /**
     * SQS reports SenderId as the principal that called SendMessage. Floci
     * has no per-call IAM context, so it falls back to the account that owns
     * the queue (parsed from the queue URL when present), otherwise the
     * account from the current request context, otherwise the configured
     * default account.
     */
    public String senderIdFor(String queueUrl) {
        String fromUrl = accountFromQueueUrl(queueUrl);
        return fromUrl != null ? fromUrl : regionResolver.getAccountId();
    }

    private static String regionKey(String region, String queueUrl) {
        return region + "::" + extractQueuePath(queueUrl);
    }

    /**
     * Extracts the path portion from a queue URL so that lookups work regardless
     * of which hostname the client used (e.g. localhost vs localhost.localstack.cloud).
     */
    private static String extractQueuePath(String queueUrl) {
        if (queueUrl == null) {
            return "";
        }
        // Find the path after host:port — e.g. http://host:4566/000000000000/my-queue -> /000000000000/my-queue
        int schemeEnd = queueUrl.indexOf("://");
        if (schemeEnd < 0) {
            return queueUrl;
        }
        int pathStart = queueUrl.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return queueUrl;
        }
        return queueUrl.substring(pathStart);
    }

    private void ensureQueueExists(String storageKey) {
        if (queueStore.get(storageKey).isEmpty()) {
            throw new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                    "The specified queue does not exist.", 400);
        }
    }

    /**
     * Looks up a queue by URL, deriving the account from the URL path rather than from request
     * context. This allows background workers (e.g. pollers) to access queues for any account
     * without a live CDI request scope.
     */
    private Optional<Queue> getQueueByUrl(String storageKey, String queueUrl) {
        if (queueStore instanceof AccountAwareStorageBackend<Queue> aware) {
            String accountId = accountFromQueueUrl(queueUrl);
            if (accountId != null) {
                return aware.getForAccount(accountId, storageKey);
            }
        }
        return queueStore.get(storageKey);
    }

    private static String accountFromQueueUrl(String queueUrl) {
        String path = extractQueuePath(queueUrl); // "/000000000001/queueName"
        if (path == null || path.isEmpty()) {
            return null;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String candidate = slash > 0 ? trimmed.substring(0, slash) : trimmed;
        return candidate.matches("\\d{12}") ? candidate : null;
    }
}
