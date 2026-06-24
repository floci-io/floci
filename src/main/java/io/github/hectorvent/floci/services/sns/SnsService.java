package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.PlatformApplication;
import io.github.hectorvent.floci.services.sns.model.PlatformEndpoint;
import io.github.hectorvent.floci.services.sns.model.PushNotification;
import io.github.hectorvent.floci.services.sns.model.SentSms;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@ApplicationScoped
public class SnsService {

    private static final Logger LOG = Logger.getLogger(SnsService.class);
    private static final Duration FIFO_DEDUP_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_PUBLISH_SIZE = 262_144;
    private static final int PUSH_CAPTURE_LIMIT = 1000;
    private static final List<String> PENDING_CONFIRMATION_PROTOCOLS =
            List.of("http", "https", "email", "email-json", "sms");
    /** Mobile-push platforms Floci mocks. iOS and Android only — anything else is rejected. */
    private static final Set<String> SUPPORTED_PUSH_PLATFORMS = Set.of(
            "APNS", "APNS_SANDBOX", "GCM", "FCM");
    private static final java.util.regex.Pattern PLATFORM_APP_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_.\\-]{1,256}");

    private final StorageBackend<String, Topic> topicStore;
    private final StorageBackend<String, Subscription> subscriptionStore;
    private final StorageBackend<String, PlatformApplication> platformAppStore;
    private final StorageBackend<String, PlatformEndpoint> platformEndpointStore;
    private final Deque<PushNotification> pushCapture = new ConcurrentLinkedDeque<>();
    private final StorageBackend<String, SentSms> smsStore;
    private final RegionResolver regionResolver;
    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, Instant> fifoDeduplicationCache = new ConcurrentHashMap<>();
    private static final HexFormat HEX = HexFormat.of();
    
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
                storageFactory.create("sns", "sns-platform-applications.json",
                        new TypeReference<Map<String, PlatformApplication>>() {
                        }),
                storageFactory.create("sns", "sns-platform-endpoints.json",
                        new TypeReference<Map<String, PlatformEndpoint>>() {
                        }),
                storageFactory.create("sns", "sns-sms.json",
                        new TypeReference<Map<String, SentSms>>() {
                        }),
                regionResolver,
                sqsService,
                lambdaService,
                config.effectiveBaseUrl(),
                objectMapper
        );
    }

    /**
     * Package-private constructor for testing (no HTTP client — HTTP delivery is skipped).
     */
    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService) {
        this(topicStore, subscriptionStore,
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                regionResolver, sqsService, lambdaService);
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService) {
        this.topicStore = topicStore;
        this.subscriptionStore = subscriptionStore;
        this.platformAppStore = platformAppStore;
        this.platformEndpointStore = platformEndpointStore;
        this.smsStore = new InMemoryStorage<>();
        this.regionResolver = regionResolver;
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.baseUrl = "http://localhost:4566";
        this.objectMapper = new ObjectMapper();
        this.httpClient = null;
    }

    SnsService(StorageBackend<String, Topic> topicStore,
               StorageBackend<String, Subscription> subscriptionStore,
               StorageBackend<String, PlatformApplication> platformAppStore,
               StorageBackend<String, PlatformEndpoint> platformEndpointStore,
               StorageBackend<String, SentSms> smsStore,
               RegionResolver regionResolver, SqsService sqsService,
               LambdaService lambdaService, String baseUrl, ObjectMapper objectMapper) {
        this.topicStore = topicStore;
        this.subscriptionStore = subscriptionStore;
        this.platformAppStore = platformAppStore;
        this.platformEndpointStore = platformEndpointStore;
        this.smsStore = smsStore;
        this.regionResolver = regionResolver;
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
            if (attributes != null && attributes.containsKey("ContentBasedDeduplication") && "true".equals(attributes.get("ContentBasedDeduplication"))) {
                topic.getAttributes().putIfAbsent("ContentBasedDeduplication", "true");
            } else {
                topic.getAttributes().putIfAbsent("ContentBasedDeduplication", "false");
            }
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

    public Subscription subscribe(String topicArn, String protocol, String endpoint, String region, Map<String, String> attributes) {
        String topicKey = topicKey(region, topicArn);
        if (topicStore.get(topicKey).isEmpty()) {
            throw new AwsException("NotFound", "Topic does not exist.", 404);
        }
        if (protocol == null || protocol.isBlank()) {
            throw new AwsException("InvalidParameter", "Protocol is required.", 400);
        }
        if (("http".equals(protocol) && endpoint != null && !endpoint.startsWith("http://"))
                || ("https".equals(protocol) && endpoint != null && !endpoint.startsWith("https://"))) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Endpoint scheme does not match protocol '" + protocol + "'.", 400);
        }

        for (Subscription existing : subscriptionsByTopic(topicArn, region)) {
            if (protocol.equals(existing.getProtocol())
                    && Objects.equals(endpoint, existing.getEndpoint())) {
                return existing;
            }
        }

        String subscriptionArn = topicArn + ":" + UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionArn, topicArn, protocol, endpoint,
                regionResolver.getAccountId());
        subscription.setAccountId(regionResolver.getAccountId());
        if (attributes != null) subscription.getAttributes().putAll(attributes);

        if (PENDING_CONFIRMATION_PROTOCOLS.contains(protocol)) {
            String token = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            subscription.getAttributes().put("PendingConfirmation", "true");
            subscription.getAttributes().put("ConfirmationToken", token);
            LOG.infov("Subscription pending confirmation for {0} ({1}) to topic {2} in {3}",
                    endpoint, protocol, topicArn, region);
        }

        subscriptionStore.put(subKey(region, subscriptionArn), subscription);
        if (attributes == null || attributes.isEmpty()) {
            LOG.infov("Subscribed {0} ({1}) to topic {2} in {3}", endpoint, protocol, topicArn, region);
        } else {
            LOG.infov("Subscribed {0} ({1}) to topic {2} in {3} with attributes: {4}", endpoint, protocol, topicArn, region, attributes);
        }

        if (("http".equals(protocol) || "https".equals(protocol)) && endpoint != null) {
            sendSubscriptionConfirmation(subscription, topicArn, region);
        }

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
                          String subject, Map<String, MessageAttributeValue> messageAttributes, String region) {
        return publish(topicArn, targetArn, phoneNumber, message, subject, messageAttributes, null, null, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, Map<String, MessageAttributeValue> messageAttributes,
                          String messageGroupId, String messageDeduplicationId, String region) {
        return publish(topicArn, targetArn, phoneNumber, message, subject, null,
                messageAttributes, messageGroupId, messageDeduplicationId, region);
    }

    public String publish(String topicArn, String targetArn, String phoneNumber, String message,
                          String subject, String messageStructure,
                          Map<String, MessageAttributeValue> messageAttributes,
                          String messageGroupId, String messageDeduplicationId, String region) {
        int messageBytes = message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
        int payloadSize = computePublishSize(messageBytes, subject, messageAttributes);
        if (payloadSize > MAX_PUBLISH_SIZE) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message too long", 400);
        }

        // Send SMS
        if (phoneNumber != null) {
            String messageId = UUID.randomUUID().toString();
            String effectiveRegion = region != null ? region : "us-east-1";
            SentSms sms = new SentSms(messageId, effectiveRegion, phoneNumber,
                    message, subject, Instant.now());
            smsStore.put("sms::" + effectiveRegion + "::" + messageId, sms);
            LOG.infov("SNS SMS published: messageId={0} phone={1}", messageId, phoneNumber);
            return messageId;
        }

        // Send a message to topic or directly to a target ARN
        String effectiveArn = topicArn != null ? topicArn : targetArn;
        if (effectiveArn == null) {
            throw new AwsException("InvalidParameter", "TopicArn or TargetArn is required.", 400);
        }
        if (message == null || message.isBlank()) {
            throw new AwsException("InvalidParameter", "Message is required.", 400);
        }

        if (isEndpointArn(effectiveArn)) {
            return publishToEndpoint(effectiveArn, message, subject, messageStructure,
                    messageAttributes, region);
        }
        if (isPlatformApplicationArn(effectiveArn)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: TargetArn Reason: Cannot publish directly to a platform application ARN.",
                    400);
        }

        String topicStoreKey = topicKey(region, effectiveArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));

        boolean isFifo = "true".equals(topic.getAttributes().get("FifoTopic"));
        String dedupId = messageDeduplicationId;
        if (isFifo) {
            if (messageGroupId == null || messageGroupId.isBlank()) {
                throw new AwsException("InvalidParameter",
                        "MessageGroupId is required for FIFO topics.", 400);
            }
            if (dedupId == null && "true".equals(topic.getAttributes().get("ContentBasedDeduplication"))) {
                dedupId = sha256(message);
            }
            if (dedupId != null && isDuplicate(effectiveArn, dedupId)) {
                LOG.debugv("FIFO dedup: skipping duplicate for topic {0}, dedupId {1}", effectiveArn, dedupId);
                return UUID.randomUUID().toString();
            }
        }

        String messageId = UUID.randomUUID().toString();
        JsonNode parsedBody = null;
        boolean bodyParseAttempted = false;
        for (Subscription sub : subscriptionsByTopic(effectiveArn, region)) {
            if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) {
                LOG.debugv("Skipping delivery to pending subscription {0}", sub.getSubscriptionArn());
                continue;
            }
            if (!bodyParseAttempted && isMessageBodyScope(sub)) {
                parsedBody = tryParseBody(message);
                bodyParseAttempted = true;
            }
            if (!matchesFilterPolicy(sub, parsedBody, messageAttributes)) {
                continue;
            }
            deliverMessage(sub, message, subject, messageAttributes, messageId, effectiveArn, messageGroupId, dedupId);
        }
        LOG.infov("Published message {0} to topic {1}", messageId, effectiveArn);
        return messageId;
    }

    // --- Mobile push (iOS / Android) ---

    public PlatformApplication createPlatformApplication(String name, String platform,
                                                          Map<String, String> attributes, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameter", "Name is required.", 400);
        }
        if (!PLATFORM_APP_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Name Reason: must be made up of only uppercase and"
                            + " lowercase ASCII letters, numbers, underscores, hyphens, and"
                            + " periods, and must be between 1 and 256 characters long.",
                    400);
        }
        if (platform == null || !SUPPORTED_PUSH_PLATFORMS.contains(platform)) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Platform Reason: Floci mocks only APNS, APNS_SANDBOX, GCM, and FCM.",
                    400);
        }
        String arn = platformApplicationArn(region, platform, name);
        String key = platformAppKey(region, arn);

        PlatformApplication existing = platformAppStore.get(key).orElse(null);
        if (existing != null) return existing;

        PlatformApplication app = new PlatformApplication(name, arn, platform);
        if (attributes != null) app.getAttributes().putAll(attributes);
        platformAppStore.put(key, app);
        LOG.infov("Created SNS platform application: {0} ({1}) in {2}", name, platform, region);
        return app;
    }

    public void deletePlatformApplication(String arn, String region) {
        String key = platformAppKey(region, arn);
        if (platformAppStore.get(key).isEmpty()) {
            return; // AWS treats DeletePlatformApplication as idempotent
        }
        String endpointPrefix = "endpoint::" + region + "::";
        List<String> toDelete = new ArrayList<>();
        for (String endpointKey : platformEndpointStore.keys()) {
            if (!endpointKey.startsWith(endpointPrefix)) continue;
            platformEndpointStore.get(endpointKey).ifPresent(ep -> {
                if (arn.equals(ep.getPlatformApplicationArn())) toDelete.add(endpointKey);
            });
        }
        toDelete.forEach(platformEndpointStore::delete);
        platformAppStore.delete(key);
        LOG.infov("Deleted SNS platform application: {0}", arn);
    }

    public Map<String, String> getPlatformApplicationAttributes(String arn, String region) {
        String key = platformAppKey(region, arn);
        PlatformApplication app = platformAppStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        return new java.util.LinkedHashMap<>(app.getAttributes());
    }

    public void setPlatformApplicationAttributes(String arn, Map<String, String> attributes, String region) {
        String key = platformAppKey(region, arn);
        PlatformApplication app = platformAppStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if (attributes != null) app.getAttributes().putAll(attributes);
        platformAppStore.put(key, app);
    }

    public List<PlatformApplication> listPlatformApplications(String region) {
        String prefix = "app::" + region + "::";
        return platformAppStore.scan(k -> k.startsWith(prefix));
    }

    public PlatformEndpoint createPlatformEndpoint(String platformApplicationArn, String token,
                                                   String customUserData, Map<String, String> attributes,
                                                   String region) {
        if (token == null || token.isBlank()) {
            throw new AwsException("InvalidParameter", "Token is required.", 400);
        }
        String appKey = platformAppKey(region, platformApplicationArn);
        PlatformApplication app = platformAppStore.get(appKey)
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if ("false".equalsIgnoreCase(app.getAttributes().get("Enabled"))) {
            throw new AwsException("PlatformApplicationDisabled",
                    "Platform application is disabled.", 400);
        }

        String endpointPrefix = "endpoint::" + region + "::";
        for (String key : platformEndpointStore.keys()) {
            if (!key.startsWith(endpointPrefix)) continue;
            PlatformEndpoint existing = platformEndpointStore.get(key).orElse(null);
            if (existing == null) continue;
            if (!platformApplicationArn.equals(existing.getPlatformApplicationArn())) continue;
            if (!token.equals(existing.getToken())) continue;
            // AWS idempotency: same token + same attributes -> return existing; otherwise reject.
            String existingUserData = existing.getAttributes().getOrDefault("CustomUserData", "");
            String newUserData = customUserData != null ? customUserData : "";
            boolean sameAttrs = existingUserData.equals(newUserData)
                    && (attributes == null || matchesExistingAttributes(existing, attributes));
            if (sameAttrs) return existing;
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Token Reason: Endpoint " + existing.getArn()
                            + " already exists with the same Token, but different attributes.",
                    400);
        }

        String endpointArn = platformEndpointArn(region, app.getPlatform(), app.getName());
        PlatformEndpoint endpoint = new PlatformEndpoint(endpointArn, platformApplicationArn, token);
        if (customUserData != null) endpoint.getAttributes().put("CustomUserData", customUserData);
        if (attributes != null) endpoint.getAttributes().putAll(attributes);
        if (isExpiredSentinelToken(token)) {
            // Fast-path for tests: the token is "already expired" — AWS would normally only
            // mark Enabled=false after an async APNS/FCM failure; Floci does it on create.
            endpoint.getAttributes().put("Enabled", "false");
            LOG.infov("Endpoint {0} created with Enabled=false (token matches expired sentinel)", endpointArn);
        }
        platformEndpointStore.put(endpointKey(region, endpointArn), endpoint);
        LOG.infov("Created SNS platform endpoint: {0} for application {1}", endpointArn, platformApplicationArn);
        return endpoint;
    }

    public void deleteEndpoint(String endpointArn, String region) {
        // AWS treats DeleteEndpoint as idempotent — no error if missing.
        platformEndpointStore.delete(endpointKey(region, endpointArn));
        LOG.infov("Deleted SNS platform endpoint: {0}", endpointArn);
    }

    public Map<String, String> getEndpointAttributes(String endpointArn, String region) {
        PlatformEndpoint endpoint = platformEndpointStore.get(endpointKey(region, endpointArn))
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        return new java.util.LinkedHashMap<>(endpoint.getAttributes());
    }

    public void setEndpointAttributes(String endpointArn, Map<String, String> attributes, String region) {
        String key = endpointKey(region, endpointArn);
        PlatformEndpoint endpoint = platformEndpointStore.get(key)
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        if (attributes != null) {
            endpoint.getAttributes().putAll(attributes);
            String newToken = attributes.get("Token");
            if (newToken != null) endpoint.setToken(newToken);
        }
        platformEndpointStore.put(key, endpoint);
    }

    public List<PlatformEndpoint> listEndpointsByPlatformApplication(String platformApplicationArn, String region) {
        String prefix = "endpoint::" + region + "::";
        List<PlatformEndpoint> result = new ArrayList<>();
        for (String key : platformEndpointStore.keys()) {
            if (!key.startsWith(prefix)) continue;
            platformEndpointStore.get(key).ifPresent(ep -> {
                if (platformApplicationArn.equals(ep.getPlatformApplicationArn())) result.add(ep);
            });
        }
        return result;
    }

    /** Returns the most recent captured pushes (newest first), optionally filtered by endpoint ARN. */
    public List<PushNotification> peekPushNotifications(String endpointArn) {
        List<PushNotification> out = new ArrayList<>();
        for (PushNotification p : pushCapture) {
            if (endpointArn != null && !endpointArn.equals(p.endpointArn())) continue;
            out.add(p);
        }
        return out;
    }

    public void clearPushNotifications() {
        pushCapture.clear();
    }

    private String publishToEndpoint(String endpointArn, String message, String subject,
                                     String messageStructure,
                                     Map<String, MessageAttributeValue> messageAttributes,
                                     String region) {
        PlatformEndpoint endpoint = platformEndpointStore.get(endpointKey(region, endpointArn))
                .orElseThrow(() -> new AwsException("NotFound", "Endpoint does not exist.", 404));
        if (!"true".equalsIgnoreCase(endpoint.getAttributes().getOrDefault("Enabled", "true"))) {
            throw new AwsException("EndpointDisabled",
                    "Endpoint " + endpointArn + " disabled", 400);
        }
        PlatformApplication app = platformAppStore.get(platformAppKey(region, endpoint.getPlatformApplicationArn()))
                .orElseThrow(() -> new AwsException("NotFound",
                        "PlatformApplication does not exist.", 404));
        if ("false".equalsIgnoreCase(app.getAttributes().get("Enabled"))) {
            throw new AwsException("PlatformApplicationDisabled",
                    "Platform application is disabled.", 400);
        }
        String payload = resolvePushPayload(app.getPlatform(), message, messageStructure);
        String messageId = UUID.randomUUID().toString();
        recordPushNotification(new PushNotification(
                endpoint.getArn(), app.getArn(), app.getPlatform(), endpoint.getToken(),
                payload, subject, messageAttributes, messageId, Instant.now()));
        LOG.infov("Captured push notification {0} -> {1} ({2})", messageId, endpoint.getArn(), app.getPlatform());
        return messageId;
    }

    /**
     * Resolves the payload SNS would forward to APNS/FCM for this platform.
     * If {@code messageStructure="json"}, pick the platform-specific key ({@code APNS},
     * {@code APNS_SANDBOX}, {@code GCM}, {@code FCM}) from the JSON envelope, falling back
     * to {@code default}. Otherwise return the raw message.
     */
    private String resolvePushPayload(String platform, String message, String messageStructure) {
        if (messageStructure == null || !"json".equals(messageStructure)) {
            return message;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Reason: Message is not valid JSON.", 400);
        }
        if (!root.isObject()) {
            throw new AwsException("InvalidParameter",
                    "Invalid parameter: Message Reason: Messages must be a JSON object.",
                    400);
        }
        JsonNode platformValue = root.get(platform);
        if (platformValue != null && !platformValue.isNull()) {
            return platformValue.isTextual() ? platformValue.asText() : platformValue.toString();
        }
        JsonNode defaultValue = root.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            return defaultValue.isTextual() ? defaultValue.asText() : defaultValue.toString();
        }
        throw new AwsException("InvalidParameter",
                "Invalid parameter: Message Reason: Messages must have a '" + platform
                        + "' or 'default' key.",
                400);
    }

    private void recordPushNotification(PushNotification notification) {
        pushCapture.addFirst(notification);
        while (pushCapture.size() > PUSH_CAPTURE_LIMIT) {
            pushCapture.pollLast();
        }
    }

    private boolean matchesExistingAttributes(PlatformEndpoint existing, Map<String, String> requested) {
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String existingValue = existing.getAttributes().get(entry.getKey());
            if (existingValue == null) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) return false;
            } else if (!existingValue.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExpiredSentinelToken(String token) {
        return token != null && token.toUpperCase(java.util.Locale.ROOT).contains("EXPIRED");
    }

    private static boolean isEndpointArn(String arn) {
        return arn != null && arn.contains(":endpoint/");
    }

    private static boolean isPlatformApplicationArn(String arn) {
        return arn != null && arn.contains(":app/");
    }

    private String platformApplicationArn(String region, String platform, String name) {
        return regionResolver.buildArn("sns", region, "app/" + platform + "/" + name);
    }

    private String platformEndpointArn(String region, String platform, String appName) {
        return regionResolver.buildArn("sns", region,
                "endpoint/" + platform + "/" + appName + "/" + UUID.randomUUID());
    }

    private static String platformAppKey(String region, String arn) {
        return "app::" + region + "::" + arn;
    }

    private static String endpointKey(String region, String arn) {
        return "endpoint::" + region + "::" + arn;
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

    public record BatchPublishResult(List<String[]> successful, List<String[]> failed) {
    }

    public BatchPublishResult publishBatch(String topicArn, List<Map<String, Object>> entries, String region) {
        String topicStoreKey = topicKey(region, topicArn);
        Topic topic = topicStore.get(topicStoreKey)
                .orElseThrow(() -> new AwsException("NotFound", "Topic does not exist.", 404));

        int batchSize = 0;
        for (Map<String, Object> entry : entries) {
            String message = (String) entry.get("Message");
            String subject = (String) entry.get("Subject");
            @SuppressWarnings("unchecked")
            Map<String, MessageAttributeValue> attrs =
                    (Map<String, MessageAttributeValue>) entry.get("MessageAttributes");
            int entryMessageBytes = message == null ? 0 : message.getBytes(StandardCharsets.UTF_8).length;
            batchSize += computePublishSize(entryMessageBytes, subject, attrs);
        }
        if (batchSize > MAX_PUBLISH_SIZE) {
            throw new AwsException("BatchRequestTooLong",
                    "Batch requests cannot be longer than " + MAX_PUBLISH_SIZE + " bytes.", 400);
        }

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
            // Derive deduplication ID if ContentBasedDeduplication is enabled and not provided
            if (isFifo && messageDeduplicationId == null && "true".equals(topic.getAttributes().get("ContentBasedDeduplication"))) {
                messageDeduplicationId = sha256(message);
            }
            if (isFifo && messageDeduplicationId != null && isDuplicate(topicArn, messageDeduplicationId)) {
                successful.add(new String[]{id, UUID.randomUUID().toString()});
                continue;
            }

            String messageId = UUID.randomUUID().toString();
            @SuppressWarnings("unchecked")
            Map<String, MessageAttributeValue> attrs = (Map<String, MessageAttributeValue>) entry.get("MessageAttributes");
            JsonNode parsedBody = null;
            boolean bodyParseAttempted = false;
            for (Subscription sub : subscriptionsByTopic(topicArn, region)) {
                if ("true".equals(sub.getAttributes().get("PendingConfirmation"))) continue;
                if (!bodyParseAttempted && isMessageBodyScope(sub)) {
                    parsedBody = tryParseBody(message);
                    bodyParseAttempted = true;
                }
                if (!matchesFilterPolicy(sub, parsedBody, attrs)) continue;
                deliverMessage(sub, message, subject, attrs, messageId, topicArn, messageGroupId, messageDeduplicationId);
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

    /**
     * Evaluates whether a message satisfies the subscription's filter policy.
     * Returns {@code true} if no filter policy is set.
     * Returns {@code false} for malformed filter policies (fail closed).
     * <p>
     * {@code FilterPolicyScope=MessageAttributes} (default) evaluates the policy against
     * the message attribute map. {@code FilterPolicyScope=MessageBody} parses the message
     * body as JSON and evaluates the policy against the parsed tree (with nested-key
     * descent and type-aware matching). A body that is not valid JSON fails closed.
     * <p>
     * All keys in the policy must match (AND logic). Within each key's rule array,
     * any matching element is sufficient (OR logic).
     */
    private static boolean isMessageBodyScope(Subscription sub) {
        return "MessageBody".equals(
                sub.getAttributes().getOrDefault("FilterPolicyScope", "MessageAttributes"));
    }

    /**
     * Per AWS SNS docs, {@code exists:true} requires the key to have a non-null and
     * non-empty value. Empty strings, empty arrays, and empty objects do not count as
     * "exists". Numbers and booleans are always considered non-empty.
     */
    private static boolean isNonEmptyValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isContainerNode()) {
            return !node.isEmpty();
        }
        return true;
    }

    private JsonNode tryParseBody(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            return null;
        }
    }

    boolean matchesFilterPolicy(Subscription sub, JsonNode parsedBody,
                                Map<String, MessageAttributeValue> messageAttributes) {
        String filterPolicyJson = sub.getAttributes().get("FilterPolicy");
        if (filterPolicyJson == null || filterPolicyJson.isBlank()) {
            return true;
        }
        String scope = sub.getAttributes().getOrDefault("FilterPolicyScope", "MessageAttributes");
        try {
            JsonNode filterPolicy = objectMapper.readTree(filterPolicyJson);
            if (!filterPolicy.isObject()) {
                LOG.warnv("Invalid FilterPolicy (not a JSON object) for {0}", sub.getSubscriptionArn());
                return false;
            }
            if ("MessageBody".equals(scope)) {
                if (parsedBody == null) {
                    LOG.debugv("FilterPolicyScope=MessageBody but body is not valid JSON for {0}; not delivering",
                            sub.getSubscriptionArn());
                    return false;
                }
                return matchesBodyPolicy(filterPolicy, parsedBody);
            }
            Map<String, MessageAttributeValue> attrs = messageAttributes != null ? messageAttributes : Map.of();
            var fields = filterPolicy.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode rules = entry.getValue();
                MessageAttributeValue attr = attrs.get(key);
                String actualValue = attr != null ? attr.getStringValue() : null;
                if (!matchesAttributeRules(actualValue, rules)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOG.warnv("Failed to parse filter policy for {0}: {1}", sub.getSubscriptionArn(), e.getMessage());
            return false;
        }
    }

    /**
     * Evaluates a filter policy object against a parsed JSON body. Each policy key must
     * match (AND); a key's value is either a rule array (terminal) or an object (nested
     * descent into the body at the same key).
     */
    private boolean matchesBodyPolicy(JsonNode policy, JsonNode body) {
        if (!policy.isObject()) {
            return false;
        }
        var fields = policy.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode ruleOrNested = entry.getValue();
            JsonNode bodyValue = (body != null && body.isObject()) ? body.get(key) : null;
            if (ruleOrNested.isArray()) {
                if (!matchesBodyRules(bodyValue, ruleOrNested)) {
                    return false;
                }
            } else if (ruleOrNested.isObject()) {
                if (!matchesBodyPolicy(ruleOrNested, bodyValue)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a rule array against a body value. When the body value is itself an array,
     * the rules are applied to each element (OR over elements), with {@code exists:true}
     * additionally matching the array as a whole.
     */
    private boolean matchesBodyRules(JsonNode actual, JsonNode rules) {
        if (!rules.isArray()) {
            return false;
        }
        if (actual != null && actual.isArray()) {
            boolean nonEmpty = !actual.isEmpty();
            for (JsonNode rule : rules) {
                if (rule.isObject() && rule.has("exists")) {
                    if (rule.get("exists").asBoolean() == nonEmpty) {
                        return true;
                    }
                } else {
                    for (JsonNode element : actual) {
                        if (matchesSingleBodyRule(element, rule)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        for (JsonNode rule : rules) {
            if (matchesSingleBodyRule(actual, rule)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSingleBodyRule(JsonNode actual, JsonNode rule) {
        if (rule.isTextual()) {
            return actual != null && actual.isTextual() && rule.asText().equals(actual.asText());
        }
        if (rule.isNumber()) {
            if (actual == null || !actual.isNumber()) {
                return false;
            }
            try {
                return new BigDecimal(actual.asText()).compareTo(rule.decimalValue()) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (rule.isBoolean()) {
            return actual != null && actual.isBoolean() && rule.booleanValue() == actual.booleanValue();
        }
        if (rule.isNull()) {
            return actual != null && actual.isNull();
        }
        if (rule.isObject()) {
            return matchesObjectRuleForNode(rule, actual);
        }
        return false;
    }

    private boolean matchesObjectRuleForNode(JsonNode rule, JsonNode actual) {
        if (rule.has("exists")) {
            return rule.get("exists").asBoolean() == isNonEmptyValue(actual);
        }
        boolean present = actual != null && !actual.isMissingNode() && !actual.isNull();
        String stringValue = present && actual.isTextual() ? actual.asText() : null;
        if (rule.has("prefix")) {
            return stringValue != null && stringValue.startsWith(rule.get("prefix").asText());
        }
        if (rule.has("anything-but")) {
            return matchesAnythingButForNode(rule.get("anything-but"), actual, stringValue, present);
        }
        if (rule.has("numeric") && present && actual.isNumber()) {
            return evaluateNumericCondition(actual.decimalValue(), rule.get("numeric"));
        }
        return false;
    }

    private boolean matchesAnythingButForNode(JsonNode anythingBut, JsonNode actual,
                                              String stringValue, boolean present) {
        if (!present) {
            return false;
        }
        if (anythingBut.isArray()) {
            for (JsonNode v : anythingBut) {
                if (v.isTextual() && stringValue != null && v.asText().equals(stringValue)) {
                    return false;
                }
                if (v.isNumber() && actual.isNumber()
                        && actual.decimalValue().compareTo(v.decimalValue()) == 0) {
                    return false;
                }
            }
            return true;
        }
        if (anythingBut.isTextual()) {
            return stringValue == null || !stringValue.equals(anythingBut.asText());
        }
        return false;
    }

    /**
     * Checks if an attribute value matches a single filter policy rule set.
     * Rules must be a JSON array where ANY element matching means the rule passes (OR logic).
     * Non-array rules are treated as non-matching.
     */
    private boolean matchesAttributeRules(String actualValue, JsonNode rules) {
        if (!rules.isArray()) {
            return false;
        }
        for (JsonNode rule : rules) {
            if (rule.isTextual() && rule.asText().equals(actualValue)) {
                return true;
            }
            if (rule.isNumber() && actualValue != null) {
                try {
                    if (new BigDecimal(actualValue).compareTo(rule.decimalValue()) == 0) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (rule.isObject() && matchesObjectRule(rule, actualValue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates a single object-type filter rule (exists, prefix, anything-but, numeric)
     * against the actual attribute value.
     */
    private boolean matchesObjectRule(JsonNode rule, String actualValue) {
        if (rule.has("exists")) {
            boolean shouldExist = rule.get("exists").asBoolean();
            return shouldExist ? actualValue != null : actualValue == null;
        }
        if (rule.has("prefix") && actualValue != null) {
            return actualValue.startsWith(rule.get("prefix").asText());
        }
        if (rule.has("anything-but") && actualValue != null) {
            return !containsValue(rule.get("anything-but"), actualValue);
        }
        if (rule.has("numeric") && actualValue != null) {
            try {
                return evaluateNumericCondition(new BigDecimal(actualValue), rule.get("numeric"));
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private boolean containsValue(JsonNode node, String value) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.asText().equals(value)) return true;
            }
            return false;
        }
        LOG.warnv("FilterPolicy 'anything-but' expected an array but got a scalar; treating as single-value list");
        return node.asText().equals(value);
    }

    /**
     * Evaluates a numeric condition array against a value.
     * The conditions array contains alternating operator-target pairs (e.g. [">=", 100, "<", 200]).
     * All pairs must match for the condition to pass (AND logic).
     */
    private boolean evaluateNumericCondition(BigDecimal value, JsonNode conditions) {
        if (!conditions.isArray() || conditions.size() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < conditions.size(); i += 2) {
            String op = conditions.get(i).asText();
            BigDecimal target = conditions.get(i + 1).decimalValue();
            int cmp = value.compareTo(target);
            boolean matches = switch (op) {
                case "=" -> cmp == 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                default -> false;
            };
            if (!matches) return false;
        }
        return true;
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

    /**
     * Removes all FIFO deduplication cache entries for SNS topics that have an SQS subscription
     * whose endpoint resolves to the same queue path as {@code queueUrl} (used when purging SQS
     * with {@code clearFifoDeduplicationCacheOnPurge}).
     */
    public void clearFifoDeduplicationCacheForSqsQueueSubscriptions(String queueUrl, String region) {
        String queuePath = extractQueuePathFromUrl(queueUrl);
        if (queuePath.isEmpty()) {
            return;
        }
        String subPrefix = "sub::" + region + "::";
        subscriptionStore.keys().stream()
                .filter(key -> key.startsWith(subPrefix))
                .map(key -> subscriptionStore.get(key).orElse(null))
                .filter(Objects::nonNull)
                .filter(sub -> "sqs".equals(sub.getProtocol()))
                .filter(sub -> sqsSubscriptionEndpointMatchesQueuePath(sub.getEndpoint(), queuePath))
                .map(Subscription::getTopicArn)
                .forEach(topicArn ->
                        fifoDeduplicationCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(topicArn + ":")));
    }

    private boolean sqsSubscriptionEndpointMatchesQueuePath(String endpoint, String queuePath) {
        if (endpoint == null) {
            return false;
        }
        String asUrl = sqsArnToUrl(endpoint);
        return extractQueuePathFromUrl(asUrl).equals(queuePath);
    }

    /**
     * Same path extraction as {@code SqsService} queue URL normalization ({@code /accountId/queueName}).
     */
    private static String extractQueuePathFromUrl(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return url;
        }
        return url.substring(pathStart);
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
                                Map<String, MessageAttributeValue> messageAttributes, String messageId,
                                String topicArn, String messageGroupId, String messageDeduplicationId) {
        try {
            switch (sub.getProtocol()) {
                case "sqs" -> {
                    String region = extractRegionFromArn(sub.getEndpoint());
                    if (region == null) {
                        region = extractRegionFromArn(topicArn);
                    }
                    String queueUrl = sqsArnToUrl(sub.getEndpoint());
                    boolean rawDelivery = "true".equalsIgnoreCase(sub.getAttributes().get("RawMessageDelivery"));
                    String body = rawDelivery
                            ? message
                            : buildSnsEnvelope(message, subject, messageAttributes, topicArn, messageId);
                    Map<String, MessageAttributeValue> sqsAttributes = rawDelivery
                            ? toSqsMessageAttributes(messageAttributes)
                            : Collections.emptyMap();
                    sqsService.sendMessage(queueUrl, body, 0, messageGroupId, messageDeduplicationId, sqsAttributes, region);
                    LOG.debugv("Delivered SNS message to SQS: {0} ({1}) raw={2}", sub.getEndpoint(), queueUrl, rawDelivery);
                }
                case "lambda" -> {
                    String fnName = extractFunctionName(sub.getEndpoint());
                    String region = extractRegionFromArn(sub.getEndpoint());
                    String eventJson = buildSnsLambdaEvent(topicArn, messageId, message,
                            subject, messageAttributes, sub.getSubscriptionArn());
                    lambdaService.invoke(region, fnName, eventJson.getBytes(), InvocationType.Event);
                    LOG.debugv("Delivered SNS message to Lambda: {0}", sub.getEndpoint());
                }
                case "http", "https" -> {
                    if (httpClient == null) break;
                    boolean rawDelivery = "true".equalsIgnoreCase(sub.getAttributes().get("RawMessageDelivery"));
                    String body = rawDelivery
                            ? message
                            : buildSnsHttpNotification(message, subject, messageAttributes, topicArn, messageId, sub.getSubscriptionArn());
                    var requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(sub.getEndpoint()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "text/plain; charset=UTF-8")
                            .header("x-amz-sns-message-type", "Notification")
                            .header("x-amz-sns-message-id", messageId)
                            .header("x-amz-sns-topic-arn", topicArn)
                            .header("x-amz-sns-subscription-arn", sub.getSubscriptionArn());
                    if (rawDelivery) {
                        requestBuilder.header("x-amz-sns-rawdelivery", "true");
                    }
                    HttpRequest request = requestBuilder
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    String endpoint = sub.getEndpoint();
                    httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .thenAccept(response -> logHttpResult("Delivered SNS notification", endpoint, response.statusCode()))
                            .exceptionally(ex -> { LOG.warnv("Failed to deliver SNS message to {0}: {1}", endpoint, ex.getMessage()); return null; });
                }
                case "email", "email-json" -> LOG.infov("SNS email delivery (stub): to={0}, subject={1}, message={2}",
                        sub.getEndpoint(), subject, message);
                case "sms" -> LOG.infov("SNS SMS delivery (stub): to={0}, message={1}", sub.getEndpoint(), message);
                default -> LOG.debugv("Protocol {0} delivery not implemented, skipping: {1}",
                        sub.getProtocol(), sub.getEndpoint());
            }
        } catch (Exception e) {
            LOG.warnv("Failed to deliver SNS message to {0}: {1}", sub.getEndpoint(), e.getMessage());
        }
    }

    private String buildSnsLambdaEvent(String topicArn, String messageId, String message,
                                       String subject, Map<String, MessageAttributeValue> messageAttributes,
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
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
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
        return AwsArnUtils.regionOrDefault(arn, null);
    }

    /**
     * Forwards SNS message attributes as SQS MessageAttributeValue objects
     * when RawMessageDelivery is enabled, preserving the original DataType.
     */
    private Map<String, MessageAttributeValue> toSqsMessageAttributes(Map<String, MessageAttributeValue> snsAttributes) {
        if (snsAttributes == null || snsAttributes.isEmpty()) {
            return Collections.emptyMap();
        }
        return new java.util.HashMap<>(snsAttributes);
    }

    private String buildSnsEnvelope(String message, String subject,
                                    Map<String, MessageAttributeValue> messageAttributes,
                                    String topicArn, String messageId) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "Notification");
            node.put("MessageId", messageId);
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            if (subject != null) {
                node.put("Subject", subject);
            }
            node.put("Message", message);
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (var entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildSnsHttpNotification(String message, String subject,
                                             Map<String, MessageAttributeValue> messageAttributes,
                                             String topicArn, String messageId, String subscriptionArn) {
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "Notification");
            node.put("MessageId", messageId);
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            if (subject != null) {
                node.put("Subject", subject);
            }
            node.put("Message", message);
            node.put("SignatureVersion", "1");
            node.put("Signature", "EXAMPLE");
            node.put("SigningCertURL", "EXAMPLE");
            node.put("UnsubscribeURL", baseUrl + "/?Action=Unsubscribe&SubscriptionArn=" + subscriptionArn);
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (messageAttributes != null) {
                for (var entry : messageAttributes.entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("Type", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("Value", Base64.getEncoder()
                                .encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        attr.put("Value", entry.getValue().getStringValue());
                    }
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void logHttpResult(String action, String endpoint, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            LOG.debugv("{0} to {1}, status={2}", action, endpoint, statusCode);
        } else {
            LOG.warnv("{0} to {1} returned non-success status {2}", action, endpoint, statusCode);
        }
    }

    private void sendSubscriptionConfirmation(Subscription subscription, String topicArn, String region) {
        if (httpClient == null) return;
        try {
            String token = subscription.getAttributes().get("ConfirmationToken");
            String subscribeUrl = baseUrl + "/?Action=ConfirmSubscription&TopicArn=" + topicArn + "&Token=" + token;
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", "SubscriptionConfirmation");
            node.put("MessageId", UUID.randomUUID().toString());
            node.put("TopicArn", topicArn);
            node.put("Timestamp", timestamp);
            node.put("Message", "You have chosen to subscribe to the topic " + topicArn + ".\nTo confirm the subscription, visit the SubscribeURL included in this message.");
            node.put("SubscribeURL", subscribeUrl);
            node.put("Token", token);
            node.put("SignatureVersion", "1");
            node.put("Signature", "EXAMPLE");
            node.put("SigningCertURL", "EXAMPLE");
            String body = objectMapper.writeValueAsString(node);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getEndpoint()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .header("x-amz-sns-message-type", "SubscriptionConfirmation")
                    .header("x-amz-sns-topic-arn", topicArn)
                    .header("x-amz-sns-subscription-arn", "PendingConfirmation")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            String endpoint = subscription.getEndpoint();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> logHttpResult("Sent SubscriptionConfirmation", endpoint, response.statusCode()))
                    .exceptionally(ex -> { LOG.warnv("Failed to send SubscriptionConfirmation to {0}: {1}", endpoint, ex.getMessage()); return null; });
        } catch (Exception e) {
            LOG.warnv("Failed to send SubscriptionConfirmation to {0}: {1}", subscription.getEndpoint(), e.getMessage());
        }
    }

    private String sqsArnToUrl(String arn) {
        if (arn == null) return null;
        if (arn.startsWith("http")) return arn;
        try { AwsArnUtils.parse(arn); } catch (IllegalArgumentException e) { return arn; }
        return AwsArnUtils.arnToQueueUrl(arn, baseUrl);
    }

    private static int computePublishSize(int messageBytes, String subject,
                                          Map<String, MessageAttributeValue> attributes) {
        int total = messageBytes;
        if (subject != null) {
            total += subject.getBytes(StandardCharsets.UTF_8).length;
        }
        if (attributes != null) {
            for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
                total += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
                MessageAttributeValue value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (value.getDataType() != null) {
                    total += value.getDataType().getBytes(StandardCharsets.UTF_8).length;
                }
                if (value.getBinaryValue() != null) {
                    total += value.getBinaryValue().length;
                } else if (value.getStringValue() != null) {
                    total += value.getStringValue().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return total;
    }

    private static String sha256(String message) {
        try {
        	MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(message.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
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

    /** All SMS messages published since last clear. Used by SnsInspectionController. */
    public List<SentSms> getSentMessages() {
        return smsStore.scan(k -> k.startsWith("sms::"));
    }

    /** Drop all stored SMS records. Used by SnsInspectionController. */
    public void clearSentMessages() {
        for (String key : smsStore.keys()) {
            if (key.startsWith("sms::")) {
                smsStore.delete(key);
            }
        }
        LOG.info("Cleared all SNS SMS records");
    }
}
