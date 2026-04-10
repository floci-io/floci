package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private record RedrivePolicy(int maxReceiveCount, String deadLetterTargetArn) {
    }

    private final int defaultVisibilityTimeout;
    private final int maxMessageSize;
    private final String baseUrl;
    private final RegionResolver regionResolver;

    @Inject
    public SqsService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
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
                regionResolver
        );
    }

    /**
     * Package-private constructor for testing.
     */
    SqsService(StorageBackend<String, Queue> queueStore,
               int defaultVisibilityTimeout, int maxMessageSize, String baseUrl) {
        this(queueStore, null, null, defaultVisibilityTimeout, maxMessageSize, baseUrl,
                new RegionResolver("us-east-1", "000000000000"));
    }

    SqsService(StorageBackend<String, Queue> queueStore, StorageBackend<String, List<Message>> messageStore,
               StorageBackend<String, Map<String, Long>> dedupStore,
               int defaultVisibilityTimeout, int maxMessageSize, String baseUrl,
               RegionResolver regionResolver) {
        this.queueStore = queueStore;
        this.messageStore = messageStore;
        this.dedupStore = dedupStore;
        this.defaultVisibilityTimeout = defaultVisibilityTimeout;
        this.maxMessageSize = maxMessageSize;
        this.baseUrl = baseUrl;
        this.regionResolver = regionResolver;
        loadPersistedMessages();
        loadPersistedDedup();
    }

    private void loadPersistedMessages() {
        if (messageStore == null) {
            return;
        }
        for (String key : messageStore.keys()) {
            messageStore.get(key).ifPresent(msgs ->
                    messagesByQueue.put(key, new GuardedMessageQueue(msgs, messageStore, key)));
        }
    }

    private void loadPersistedDedup() {
        if (dedupStore == null) {
            return;
        }
        Instant now = Instant.now();
        for (String key : dedupStore.keys()) {
            dedupStore.get(key).ifPresent(entries -> {
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
            });
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
        return createQueue(queueName, attributes, regionResolver.getDefaultRegion());
    }

    public Queue createQueue(String queueName, Map<String, String> attributes, String region) {

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
        if (attributes != null) {
            queue.getAttributes().putAll(attributes);
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
        messagesByQueue.remove(storageKey);
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
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));

        if (body.length() > maxMessageSize) {
            throw new AwsException("InvalidParameterValue",
                    "Message body must be shorter than " + maxMessageSize + " bytes.", 400);
        }

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
                // Duplicate within window — return the original message idempotently
                Message existing = getOrCreateQueue(storageKey).findByDeduplicationId(dedupId);
                if (existing != null) {
                    return existing;
                }
            }

            Message message = new Message(body);
            message.setMessageGroupId(messageGroupId);
            message.setMessageDeduplicationId(dedupId);
            message.setSequenceNumber(sequenceCounter.incrementAndGet());
            if (messageAttributes != null && !messageAttributes.isEmpty()) {
                message.getMessageAttributes().putAll(messageAttributes);
                message.updateMd5OfMessageAttributes();
            }

            getOrCreateQueue(storageKey).addMessage(message);
            notifyReceivers(storageKey);
            LOG.debugv("Sent FIFO message {0} to queue {1}, group={2}, seq={3}",
                    message.getMessageId(), queueUrl, messageGroupId, message.getSequenceNumber());
            return message;
        }

        // Standard queue
        Message message = new Message(body);
        if (delaySeconds > 0) {
            message.setVisibleAt(Instant.now().plusSeconds(delaySeconds));
        }
        if (messageAttributes != null && !messageAttributes.isEmpty()) {
            message.getMessageAttributes().putAll(messageAttributes);
            message.updateMd5OfMessageAttributes();
        }

        getOrCreateQueue(storageKey).addMessage(message);
        notifyReceivers(storageKey);
        LOG.debugv("Sent message {0} to queue {1}", message.getMessageId(), queueUrl);
        return message;
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
            var sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
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
        queueStore.get(storageKey)
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

        int effectiveTimeout = visibilityTimeout >= 0 ? visibilityTimeout : defaultVisibilityTimeout;

        RedrivePolicy rp = getOrParseRedrivePolicy(queue, storageKey);
        int maxReceiveCount = rp != null ? rp.maxReceiveCount() : -1;
        String deadLetterTargetArn = rp != null ? rp.deadLetterTargetArn() : null;

        var guardedQueue = getOrCreateQueue(storageKey);
        var claimResult = guardedQueue.claimVisibleMessages(
                maxMessages, effectiveTimeout, queue.isFifo(), maxReceiveCount, deadLetterTargetArn);

        // Route DLQ candidates to the dead-letter queue
        if (!claimResult.dlqCandidates().isEmpty() && deadLetterTargetArn != null) {
            String dlqUrl = queueUrlFromArn(deadLetterTargetArn, region);
            if (dlqUrl != null) {
                String dlqStorageKey = regionKey(region, dlqUrl);
                getOrCreateQueue(dlqStorageKey).addAll(claimResult.dlqCandidates());
                LOG.infov("Moved {0} messages to DLQ {1}", claimResult.dlqCandidates().size(), dlqUrl);
            }
        }

        return claimResult.claimed();
    }

    private String queueUrlFromArn(String arn, String region) {
        if (arn == null || !arn.startsWith("arn:aws:sqs:")) {
            return null;
        }
        String[] parts = arn.split(":");
        if (parts.length < 6) {
            return null;
        }
        String accountId = parts[4];
        String queueName = parts[5];
        return baseUrl + "/" + accountId + "/" + queueName;
    }

    public void deleteMessage(String queueUrl, String receiptHandle) {
        deleteMessage(queueUrl, receiptHandle, regionResolver.getDefaultRegion());
    }

    public void deleteMessage(String queueUrl, String receiptHandle, String region) {
        String storageKey = regionKey(region, queueUrl);
        ensureQueueExists(storageKey);

        boolean removed = getOrCreateQueue(storageKey).removeByReceiptHandle(receiptHandle);

        if (!removed) {
            throw new AwsException("ReceiptHandleIsInvalid",
                    "The input receipt handle is not a valid receipt handle.", 400);
        }
        LOG.debugv("Deleted message with receipt handle {0}", receiptHandle);
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
        LOG.infov("Purged queue: {0}", queueUrl);
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
            String redrive = q.getAttributes().get("RedrivePolicy");
            if (redrive != null && redrive.contains(targetArn)) {
                sourceQueues.add(q.getQueueUrl());
            }
        }
        return sourceQueues;
    }

    public String startMessageMoveTask(String sourceArn, String destinationArn, String region) {
        String sourceUrl = queueUrlFromArn(sourceArn, region);
        String destUrl = destinationArn != null ? queueUrlFromArn(destinationArn, region) : null;
        if (sourceUrl == null) {
            throw new AwsException("InvalidParameterValue", "Invalid source ARN", 400);
        }

        String srcKey = regionKey(region, sourceUrl);
        ensureQueueExists(srcKey);

        var srcQueue = getOrCreateQueue(srcKey);
        List<Message> drained = srcQueue.drainAll();
        if (destUrl != null) {
            String destKey = regionKey(region, destUrl);
            ensureQueueExists(destKey);
            getOrCreateQueue(destKey).addAll(drained);
        }

        LOG.infov("Moved messages from {0} to {1}", sourceArn, destinationArn != null ? destinationArn : "original source");
        return "task-" + UUID.randomUUID().toString();
    }

    public List<Map<String, Object>> listMessageMoveTasks(String sourceArn, String region) {
        return Collections.emptyList();
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

    public Map<String, String> listQueueTags(String queueUrl, String region) {
        String storageKey = regionKey(region, queueUrl);
        Queue queue = queueStore.get(storageKey)
                .orElseThrow(() -> new AwsException("AWS.SimpleQueueService.NonExistentQueue",
                        "The specified queue does not exist.", 400));
        return new java.util.LinkedHashMap<>(queue.getTags());
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
}
