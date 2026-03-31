package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SnsService {

    private static final Logger LOG = Logger.getLogger(SnsService.class);
    private static final Duration FIFO_DEDUP_WINDOW = Duration.ofMinutes(5);
    private static final List<String> PENDING_CONFIRMATION_PROTOCOLS =
            List.of("http", "https", "email", "email-json", "sms");

    private final StorageBackend<String, Topic> topicStore;
    private final StorageBackend<String, Subscription> subscriptionStore;
    private final RegionResolver regionResolver;
    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final Map<String, Instant> fifoDeduplicationCache = new ConcurrentHashMap<>();

    @Inject
    public SnsService(StorageFactory storageFactory, EmulatorConfig config,
                      RegionResolver regionResolver, SqsService sqsService,
                      LambdaService lambdaService, ObjectMapper objectMapper) {
        this(
                storageFactory.create("sns", "sns-topics.json",
                        new TypeReference<Map<String, Topic>>() {
                        }),
                storageFactory.create("sns", "sns-subscriptions.json",
                        new TypeReference<Map<String, Subscription>>() {
                        }),
                regionResolver,
                sqsService,
                lambdaService,
                config.effectiveBaseUrl(),
                objectMapper
        );
    }

    /**
     * Package-private constructor for testing.
     */
    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService) {
        this(topicStore, subscriptionStore, regionResolver, sqsService, lambdaService, "http://localhost:4566",
                new ObjectMapper());
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, String baseUrl, ObjectMapper objectMapper) {
        this.topicStore = topicStore;
        this.subscriptionStore = subscriptionStore;
        this.regionResolver = regionResolver;
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    public Topic createTopic(String name, Map<String, String> attributes,
                             Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameter", "Topic name is required.", 400);
        }
        String topicArn = regionResolver.buildArn("sns", region, name);
        String key = topicKey(region, topicArn);

        Topic existing = topicStore.get(key).orElse(null);
        if (existing != null) return existing;

        Topic topic = new Topic(name, topicArn);
        if (attributes != null) topic.getAttributes().putAll(attributes);
        if (tags != null) topic.getTags().putAll(tags);

        if (name.endsWith(".fifo")) {
            topic.getAttributes().put("FifoTopic", "true");
            topic.getAttributes().putIfAbsent("ContentBasedDeduplication", "false");
        }

        topicStore.put(key, topic);
        LOG.infov("Created SNS topic: {0} in region {1}", name, region);
        return topic;
    }

    public void deleteTopic(String topicArn, String region) {
        String key = topicKey(region, topicArn);
        if (topicStore.get(key).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        List<String> toDelete = new ArrayList<>();
        String subPrefix = "sub::" + region + "::";
        for (String subKey : subscriptionStore.keys()) {
            if (subKey.startsWith(subPrefix)) {
                subscriptionStore.get(subKey).ifPresent(sub -> {
                    if (topicArn.equals(sub.getTopicArn())) toDelete.add(subKey);
                });
            }
        }
        toDelete.forEach(subscriptionStore::delete);
        topicStore.delete(key);
        LOG.infov("Deleted SNS topic: {0}", topicArn);
    }

    public List<Topic> listTopics(String region) {
        String prefix = "topic::" + region + "::";
        return topicStore.scan(k -> k.startsWith(prefix));
    }

    public Map<String, String> getTopicAttributes(String topicArn, String region) {
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        var attrs = new java.util.LinkedHashMap<>(topic.getAttributes());
        List<Subscription> subs = subscriptionsByTopic(topicArn, region);
        long confirmed = subs.stream()
                .filter(s -> !"true".equals(s.getAttributes().get("PendingConfirmation")))
                .count();
        long pending = subs.stream()
                .filter(s -> "true".equals(s.getAttributes().get("PendingConfirmation")))
                .count();
        attrs.put("SubscriptionsConfirmed", String.valueOf(confirmed));
        attrs.put("SubscriptionsPending", String.valueOf(pending));
        attrs.put("SubscriptionsDeleted", "0");
        attrs.put("TopicArn", topicArn);
        attrs.putIfAbsent("DisplayName", "");
        attrs.putIfAbsent("Owner", regionResolver.getAccountId());
        attrs.putIfAbsent("EffectiveDeliveryPolicy", "{\"http\":{\"defaultHealthyRetryPolicy\":{\"minDelayTarget\":20,\"maxDelayTarget\":20,\"numRetries\":3,\"numMaxDelayRetries\":0,\"numNoDelayRetries\":0,\"numMinDelayRetries\":0,\"backoffFunction\":\"linear\"},\"disableSubscriptionOverrides\":false}}");
        if (!attrs.containsKey("Policy") || attrs.get("Policy") == null || attrs.get("Policy").isBlank()) {
            String account = regionResolver.getAccountId();
            attrs.put("Policy", "{\"Version\":\"2012-10-17\",\"Id\":\"__default_policy_ID\",\"Statement\":[{\"Sid\":\"__default_statement_ID\",\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":[\"SNS:GetTopicAttributes\",\"SNS:SetTopicAttributes\",\"SNS:AddPermission\",\"SNS:RemovePermission\",\"SNS:DeleteTopic\",\"SNS:Subscribe\",\"SNS:ListSubscriptionsByTopic\",\"SNS:Publish\"],\"Resource\":\"" + topicArn + "\",\"Condition\":{\"StringEquals\":{\"AWS:SourceAccount\":\"" + account + "\"}}}]}");
        }
        return attrs;
    }

    public void setTopicAttributes(String topicArn, String attributeName,
                                   String attributeValue, String region) {
        String key = topicKey(region, topicArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        topic.getAttributes().put(attributeName, attributeValue);
        topicStore.put(key, topic);
    }

    public Subscription subscribe(String topicArn, String protocol, String endpoint, String region) {
        String topicKey = topicKey(region, topicArn);
        if (topicStore.get(topicKey).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        if (protocol == null || protocol.isBlank()) {
            throw new AwsException("InvalidParameter", "Protocol is required.", 400);
        }
        String subscriptionArn = topicArn + ":" + UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionArn, topicArn, protocol, endpoint,
                regionResolver.getAccountId());

        if (PENDING_CONFIRMATION_PROTOCOLS.contains(protocol)) {
            String token = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            subscription.getAttributes().put("PendingConfirmation", "true");
            subscription.getAttributes().put("ConfirmationToken", token);
            LOG.infov("Subscription pending confirmation for {0} ({1}) to topic {2} in {3}",
                    endpoint, protocol, topicArn, region);
        }

        subscriptionStore.put(subKey(region, subscriptionArn), subscription);
        LOG.infov("Subscribed {0} ({1}) to topic {2} in {3}", endpoint, protocol, topicArn, region);
        return subscription;
    }

    public String confirmSubscription(String topicArn, String token, String region) {
        if (topicStore.get(topicKey(region, topicArn)).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        String subPrefix = "sub::" + region + "::";
        for (String subKey : subscriptionStore.keys()) {
            if (!subKey.startsWith(subPrefix)) continue;
            Subscription sub = subscriptionStore.get(subKey).orElse(null);
            if (sub == null || !topicArn.equals(sub.getTopicArn())) continue;
            if (token.equals(sub.getAttributes().get("ConfirmationToken"))) {
                sub.getAttributes().put("PendingConfirmation", "false");
                sub.getAttributes().remove("ConfirmationToken");
                subscriptionStore.put(subKey, sub);
                LOG.infov("Confirmed subscription {0} for topic {1}", sub.getSubscriptionArn(), topicArn);
                return sub.getSubscriptionArn();
            }
        }
        throw new AwsException("AuthorizationError", "Token is invalid for this topic.", 403);
    }

    public void unsubscribe(String subscriptionArn, String region) {
        String key = subKey(region, subscriptionArn);
        if (subscriptionStore.get(key).isEmpty()) {
            throw new AwsException("NotFound", "Subscription does not exist.", 404);
        }
        subscriptionStore.delete(key);
        LOG.infov("Unsubscribed: {0}", subscriptionArn);
    }

    public List<Subscription> listSubscriptions(String region) {
        String prefix = "sub::" + region + "::";
        return subscriptionStore.scan(k -> k.startsWith(prefix));
    }

    public List<Subscription> listSubscriptionsByTopic(String topicArn, String region) {
        return subscriptionsByTopic(topicArn, region);
    }

    // Since this method is called by S3 and EventBridge, this doesn't need "phoneNumber" parameter.
    public String publish(String topicArn, String targetArn, String message,
                          String subject, String region) {
        return publish(topicArn, targetArn, null, message, subject, null, null, null, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, Map<String, String> messageAttributes, String region) {
        return publish(topicArn, targetArn, phoneNumber, message, subject, messageAttributes, null, null, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, Map<String, String> messageAttributes,
                          String messageGroupId, String messageDeduplicationId, String region) {
        // Send SMS
        if (phoneNumber != null) {
            return UUID.randomUUID().toString();
        }

        // Send a message to topic or directly to a target ARN
        String effectiveArn = topicArn != null ? topicArn : targetArn;
        if (effectiveArn == null) {
            throw new AwsException("InvalidParameter", "TopicArn or TargetArn is required.", 400);
        }
        String topicStoreKey = topicKey(region, effectiveArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        if (message == null || message.isBlank()) {
            throw new AwsException("InvalidParameter", "Message is required.", 400);
        }

        boolean isFifo = "true".equals(topic.getAttributes().get("FifoTopic"));
        if (isFifo) {
            if (messageGroupId == null || messageGroupId.isBlank()) {
                throw new AwsException("InvalidParameter",
                        "MessageGroupId is required for FIFO topics.", 400);
            }
            String dedupId = messageDeduplicationId;
            if (dedupId == null && "true".equals(topic.getAttributes().get("ContentBasedDeduplication"))) {
                dedupId = sha256(message);
            }
            if (dedupId != null && isDuplicate(effectiveArn, dedupId)) {
                LOG.debugv("FIFO dedup: skipping duplicate for topic {0}, dedupId {1}", effectiveArn, dedupId);
                return UUID.randomUUID().toString();
            }
        }

        String messageId = UUID.randomUUID().toString();
        for (Subscription sub : subscriptionsByTopic(effectiveArn, region)) {
            if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) {
                LOG.debugv("Skipping delivery to pending subscription {0}", sub.getSubscriptionArn());
                continue;
            }
            deliverMessage(sub, message, subject, messageAttributes, messageId, effectiveArn, messageGroupId);
        }
        LOG.infov("Published message {0} to topic {1}", messageId, effectiveArn);
        return messageId;
    }

    public Map<String, String> getSubscriptionAttributes(String subscriptionArn, String region) {
        String key = subKey(region, subscriptionArn);
        Subscription sub = subscriptionStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Subscription does not exist.", 404));
        var attrs = new java.util.LinkedHashMap<>(sub.getAttributes());
        attrs.put("SubscriptionArn", sub.getSubscriptionArn());
        attrs.put("TopicArn", sub.getTopicArn());
        attrs.put("Protocol", sub.getProtocol());
        attrs.put("Endpoint", sub.getEndpoint() != null ? sub.getEndpoint() : "");
        attrs.put("Owner", sub.getOwner() != null ? sub.getOwner() : "");
        attrs.put("RawMessageDelivery", attrs.getOrDefault("RawMessageDelivery", "false"));
        attrs.putIfAbsent("PendingConfirmation", "false");
        attrs.putIfAbsent("ConfirmationWasAuthenticated", "false");
        attrs.putIfAbsent("FilterPolicyScope", "MessageAttributes");
        attrs.remove("ConfirmationToken");
        return attrs;
    }

    public void setSubscriptionAttribute(String subscriptionArn, String attributeName,
                                         String attributeValue, String region) {
        String key = subKey(region, subscriptionArn);
        Subscription sub = subscriptionStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Subscription does not exist.", 404));
        sub.getAttributes().put(attributeName, attributeValue);
        subscriptionStore.put(key, sub);
    }

    public record BatchPublishResult(List<String[]> successful, List<String[]> failed) {}

    public BatchPublishResult publishBatch(String topicArn, List<Map<String, Object>> entries, String region) {
        String topicStoreKey = topicKey(region, topicArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));
        boolean isFifo = "true".equals(topic.getAttributes().get("FifoTopic"));
        List<String[]> successful = new ArrayList<>();
        List<String[]> failed = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String id = (String) entry.get("Id");
            String message = (String) entry.get("Message");
            if (message == null || message.isBlank()) {
                failed.add(new String[]{id, "InvalidParameter", "Message is required.", "true"});
                continue;
            }
            String subject = (String) entry.get("Subject");
            String messageGroupId = (String) entry.get("MessageGroupId");
            String messageDeduplicationId = (String) entry.get("MessageDeduplicationId");

            if (isFifo && (messageGroupId == null || messageGroupId.isBlank())) {
                failed.add(new String[]{id, "InvalidParameter",
                        "MessageGroupId is required for FIFO topics.", "true"});
                continue;
            }
            if (isFifo && messageDeduplicationId != null && isDuplicate(topicArn, messageDeduplicationId)) {
                successful.add(new String[]{id, UUID.randomUUID().toString()});
                continue;
            }

            String messageId = UUID.randomUUID().toString();
            @SuppressWarnings("unchecked")
            Map<String, String> attrs = (Map<String, String>) entry.get("MessageAttributes");
            for (Subscription sub : subscriptionsByTopic(topicArn, region)) {
                if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) continue;
                deliverMessage(sub, message, subject, attrs, messageId, topicArn, messageGroupId);
            }
            LOG.debugv("Batch published message {0} (id={1}) to {2}", messageId, id, topicArn);
            successful.add(new String[]{id, messageId});
        }
        return new BatchPublishResult(successful, failed);
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        if (tags != null) topic.getTags().putAll(tags);
        topicStore.put(key, topic);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        if (tagKeys != null) tagKeys.forEach(topic.getTags()::remove);
        topicStore.put(key, topic);
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        String key = topicKey(region, resourceArn);
        Topic topic = topicStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource does not exist.", 404));
        return new java.util.LinkedHashMap<>(topic.getTags());
    }

    private boolean isDuplicate(String topicArn, String deduplicationId) {
        String cacheKey = topicArn + ":" + deduplicationId;
        Instant now = Instant.now();
        Instant existing = fifoDeduplicationCache.get(cacheKey);
        if (existing != null && existing.plus(FIFO_DEDUP_WINDOW).isAfter(now)) {
            return true;
        }
        fifoDeduplicationCache.put(cacheKey, now);
        fifoDeduplicationCache.entrySet().removeIf(e -> e.getValue().plus(FIFO_DEDUP_WINDOW).isBefore(now));
        return false;
    }

    private List<Subscription> subscriptionsByTopic(String topicArn, String region) {
        List<Subscription> result = new ArrayList<>();
        String prefix = "sub::" + region + "::";
        for (String k : subscriptionStore.keys()) {
            if (k.startsWith(prefix)) {
                subscriptionStore.get(k).ifPresent(sub -> {
                    if (topicArn.equals(sub.getTopicArn())) result.add(sub);
                });
            }
        }
        return result;
    }

    private void deliverMessage(Subscription sub, String message, String subject,
                                Map<String, String> messageAttributes, String messageId,
                                String topicArn, String messageGroupId) {
        try {
            switch (sub.getProtocol()) {
                case "sqs" -> {
                    String region = extractRegionFromArn(sub.getEndpoint());
                    if (region == null) {
                        region = extractRegionFromArn(topicArn);
                    }
                    String queueUrl = sqsArnToUrl(sub.getEndpoint());
                    String envelope = buildSnsEnvelope(message, subject, messageAttributes, topicArn, messageId);
                    sqsService.sendMessage(queueUrl, envelope, 0, messageGroupId, null, region);
                    LOG.debugv("Delivered SNS message to SQS: {0} ({1}) in {2}",
                            sub.getEndpoint(), queueUrl, region);
                }
                case "lambda" -> {
                    String fnName = extractFunctionName(sub.getEndpoint());
                    String region = extractRegionFromArn(sub.getEndpoint());
                    String eventJson = buildSnsLambdaEvent(topicArn, messageId, message,
                            subject, messageAttributes, sub.getSubscriptionArn());
                    lambdaService.invoke(region, fnName, eventJson.getBytes(), InvocationType.Event);
                    LOG.debugv("Delivered SNS message to Lambda: {0}", sub.getEndpoint());
                }
                case "email", "email-json" ->
                    LOG.infov("SNS email delivery (stub): to={0}, subject={1}, message={2}",
                            sub.getEndpoint(), subject, message);
                case "sms" ->
                    LOG.infov("SNS SMS delivery (stub): to={0}, message={1}", sub.getEndpoint(), message);
                default -> LOG.debugv("Protocol {0} delivery not implemented, skipping: {1}",
                        sub.getProtocol(), sub.getEndpoint());
            }
        } catch (Exception e) {
            LOG.warnv("Failed to deliver SNS message to {0}: {1}", sub.getEndpoint(), e.getMessage());
        }
    }

    private String buildSnsLambdaEvent(String topicArn, String messageId, String message,
                                       String subject, Map<String, String> messageAttributes,
                                       String subscriptionArn) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode snsNode = objectMapper.createObjectNode();
            snsNode.put("Type", "Notification");
            snsNode.put("MessageId", messageId);
            snsNode.put("TopicArn", topicArn);
            if (subject != null) {
                snsNode.put("Subject", subject);
            } else {
                snsNode.putNull("Subject");
            }
            snsNode.put("Message", message);
            snsNode.put("Timestamp", timestamp);
            snsNode.put("SignatureVersion", "1");
            snsNode.put("Signature", "EXAMPLE");
            snsNode.put("SigningCertUrl", "EXAMPLE");
            snsNode.put("UnsubscribeUrl", "EXAMPLE");
            ObjectNode attrs = snsNode.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (var entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", "String");
                    attr.put("Value", entry.getValue());
                }
            }
            ObjectNode record = objectMapper.createObjectNode();
            record.put("EventVersion", "1.0");
            record.put("EventSubscriptionArn", subscriptionArn);
            record.put("EventSource", "aws:sns");
            record.set("Sns", snsNode);
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("Records").add(record);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"Records\":[]}";
        }
    }

    private static String extractFunctionName(String functionArn) {
        int idx = functionArn.lastIndexOf(':');
        return idx >= 0 ? functionArn.substring(idx + 1) : functionArn;
    }

    private static String extractRegionFromArn(String arn) {
        if (arn == null || !arn.startsWith("arn:aws:")) return null;
        String[] parts = arn.split(":");
        return parts.length >= 4 ? parts[3] : null;
    }

    private String buildSnsEnvelope(String message, String subject,
                                    Map<String, String> messageAttributes,
                                    String topicArn, String messageId) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "Notification");
            node.put("MessageId", messageId);
            node.put("TopicArn", topicArn);
            if (subject != null) {
                node.put("Subject", subject);
            }
            node.put("Message", message);
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (var entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", "String");
                    attr.put("Value", entry.getValue());
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sqsArnToUrl(String arn) {
        if (arn == null) return null;
        if (arn.startsWith("http")) return arn;
        if (arn.split(":").length < 6) return arn;
        return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
    }

    private static String sha256(String message) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(message.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String topicKey(String region, String arn) {
        return "topic::" + region + "::" + arn;
    }

    private static String subKey(String region, String subscriptionArn) {
        return "sub::" + region + "::" + subscriptionArn;
    }
}