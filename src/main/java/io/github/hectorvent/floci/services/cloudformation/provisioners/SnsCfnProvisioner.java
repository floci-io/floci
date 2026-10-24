package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Provisions {@code AWS::SNS::Topic}, {@code AWS::SNS::Subscription} and {@code AWS::SNS::TopicPolicy}. */
@ApplicationScoped
public class SnsCfnProvisioner implements CfnResourceProvisioner {

    private static final String TOPIC = "AWS::SNS::Topic";
    private static final String SUBSCRIPTION = "AWS::SNS::Subscription";
    private static final String TOPIC_POLICY = "AWS::SNS::TopicPolicy";
    private static final int TOPIC_NAME_MAX_LENGTH = 256;
    private static final String FIFO_SUFFIX = ".fifo";
    private static final String INLINE_SUBSCRIPTION_PREFIX = "__FlociInlineSubscription:";
    private static final Set<String> MUTABLE_TOPIC_ATTRIBUTES = Set.of(
            "ContentBasedDeduplication", "FifoThroughputScope", "MaximumMessageSize");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SnsService snsService;

    public SnsCfnProvisioner(SnsService snsService) {
        this.snsService = snsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TOPIC, SUBSCRIPTION, TOPIC_POLICY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case TOPIC -> provisionTopic(r, props, ctx);
            case SUBSCRIPTION -> provisionSubscription(r, props, ctx);
            case TOPIC_POLICY -> provisionTopicPolicy(r, props, ctx);
            default -> throw new IllegalStateException(
                    "SnsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionTopic(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(CfnRollback.SNS_INLINE_SUBSCRIPTIONS_SNAPSHOT_ATTR);
        String topicName = ctx.resolveOptional(props, "TopicName");
        String contentBasedDedupFlag = ctx.resolveOptional(props, "ContentBasedDeduplication");
        String throughputScope = ctx.resolveOptional(props, "FifoThroughputScope");
        String maximumMessageSize = ctx.resolveOptional(props, "MaximumMessageSize");
        // The .fifo suffix is what makes SnsService treat a topic as FIFO, so an explicitly named
        // one is FIFO with or without the flag, which only has to steer a generated name.
        boolean fifo = "true".equalsIgnoreCase(ctx.resolveOptional(props, "FifoTopic"))
                || (topicName != null && topicName.endsWith(FIFO_SUFFIX));
        String priorName = r.getAttributes().get("TopicName");
        // FifoTopic is createOnly and carried by the name, so a flip needs a replacement. Nothing
        // cleans up the displaced topic or its subscriptions, so refuse it, as SqsCfnProvisioner
        // does for FifoQueue.
        if (ctx.isUpdate() && priorName != null && !priorName.isBlank()
                && priorName.endsWith(FIFO_SUFFIX) != fifo) {
            throw new AwsException("ValidationError",
                    "Updating FifoTopic requires resource replacement, which is not supported.", 400);
        }
        if (topicName == null || topicName.isBlank()) {
            // ctx.stablePhysicalName does not fit here: the physical id is the topic ARN, so the
            // prior name comes from the attribute recorded at create time. Reusing it keeps an
            // unnamed topic (and its subscriptions) across updates instead of orphaning it.
            if (priorName != null && !priorName.isBlank()) {
                topicName = priorName;
            } else if (fifo) {
                // Like real CloudFormation, a generated FIFO name ends in .fifo.
                topicName = ctx.generatePhysicalName(r.getLogicalId(),
                        TOPIC_NAME_MAX_LENGTH - FIFO_SUFFIX.length(), false) + FIFO_SUFFIX;
            } else {
                topicName = ctx.generatePhysicalName(r.getLogicalId(), TOPIC_NAME_MAX_LENGTH, false);
            }
        }

        Map<String, String> attributes = new HashMap<>();
        if (contentBasedDedupFlag != null && !contentBasedDedupFlag.isBlank()) {
            attributes.put("ContentBasedDeduplication", contentBasedDedupFlag);
        }
        if (throughputScope != null && !throughputScope.isBlank()) {
            attributes.put("FifoThroughputScope", throughputScope);
        }
        if (maximumMessageSize != null && !maximumMessageSize.isBlank()) {
            attributes.put("MaximumMessageSize", maximumMessageSize);
        }

        JsonNode subscriptions = props == null ? null
                : ctx.engine().resolveNodeOmittingNoValue(props.path("Subscription"));
        if (subscriptions != null && !subscriptions.isMissingNode() && !subscriptions.isNull()
                && !subscriptions.isArray()) {
            throw new AwsException("ValidationError", "Subscription must be a list.", 400);
        }
        List<SubscriptionTarget> desiredSubscriptions = new ArrayList<>();
        if (subscriptions != null && subscriptions.isArray()) {
            for (JsonNode subscription : subscriptions) {
                desiredSubscriptions.add(new SubscriptionTarget(ctx.resolveOptional(subscription, "Protocol"),
                        ctx.resolveOptional(subscription, "Endpoint")));
            }
        }

        SnsService.TopicCreation creation = snsService.createTopicWithStatus(
                topicName, attributes, Map.of(), ctx.region());
        Topic topic = creation.topic();
        boolean created = creation.created();
        List<Subscription> existing = snsService.listSubscriptionsByTopic(topic.getTopicArn(), ctx.region());
        if (ctx.isUpdate() && !created) {
            snapshotInlineSubscriptions(r, topic, ctx.region(), existing);
        }
        // createTopic leaves an existing topic untouched, so an update writes the mutable
        // attributes itself. A property the template dropped is reset, not left standing.
        if (ctx.isUpdate()) {
            Map<String, String> desired = new HashMap<>(attributes);
            if (fifo) {
                desired.putIfAbsent("ContentBasedDeduplication", "false");
                desired.putIfAbsent("FifoThroughputScope", "Topic");
            }
            // A dropped MaximumMessageSize goes back to the AWS default, but only where one was
            // set: writing the default onto a topic that never carried the attribute would make
            // GetTopicAttributes report something real SNS omits.
            if (!desired.containsKey("MaximumMessageSize")
                    && topic.getAttributes().containsKey("MaximumMessageSize")) {
                desired.put("MaximumMessageSize",
                        String.valueOf(SnsService.DEFAULT_MAX_MESSAGE_SIZE));
            }
            desired.forEach((name, value) ->
                    snsService.setTopicAttributes(topic.getTopicArn(), name, value, ctx.region()));
        }
        // Ref returns the topic ARN, which is why the physical id is the ARN and not the name.
        r.setPhysicalId(topic.getTopicArn());
        // TopicArn is the attribute aws-sns-topic.json declares read-only. Arn is kept alongside it
        // because templates written against earlier Floci releases already reference it.
        r.getAttributes().put("TopicArn", topic.getTopicArn());
        r.getAttributes().put("Arn", topic.getTopicArn());
        r.getAttributes().put("TopicName", topicName);

        Set<String> added = new HashSet<>();
        CfnRollback.guarded(r, ctx, created,
                () -> snsService.deleteTopic(topic.getTopicArn(), ctx.region()),
                () -> {
                    if (ctx.isUpdate()) {
                        restoreInlineSubscriptions(r);
                    } else {
                        added.forEach(arn -> snsService.unsubscribe(arn, ctx.region()));
                    }
                },
                () -> reconcileInlineSubscriptions(r, topic.getTopicArn(), ctx.region(),
                        desiredSubscriptions, existing, added));
    }

    private void snapshotInlineSubscriptions(StackResource r, Topic topic, String region,
                                             List<Subscription> existing) {
        List<Subscription> owned = existing.stream()
                .filter(sub -> r.getAttributes().containsKey(
                        INLINE_SUBSCRIPTION_PREFIX + sub.getSubscriptionArn()))
                .toList();
        Map<String, String> previousAttributes = new HashMap<>();
        MUTABLE_TOPIC_ATTRIBUTES.forEach(name -> {
            if (topic.getAttributes().containsKey(name)) {
                previousAttributes.put(name, topic.getAttributes().get(name));
            }
        });
        InlineSubscriptionSnapshot snapshot = new InlineSubscriptionSnapshot(
                topic.getTopicArn(), region, owned, previousAttributes);
        try {
            r.getAttributes().put(CfnRollback.SNS_INLINE_SUBSCRIPTIONS_SNAPSHOT_ATTR,
                    MAPPER.writeValueAsString(snapshot));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not snapshot inline SNS subscriptions", e);
        }
    }

    @RegisterForReflection
    private record InlineSubscriptionSnapshot(String topicArn, String region,
                                              List<Subscription> owned,
                                              Map<String, String> topicAttributes) {
    }

    private void restoreInlineSubscriptions(StackResource r) {
        String raw = r.getAttributes().get(CfnRollback.SNS_INLINE_SUBSCRIPTIONS_SNAPSHOT_ATTR);
        if (raw == null) {
            return;
        }
        InlineSubscriptionSnapshot snapshot;
        try {
            snapshot = MAPPER.readValue(raw, InlineSubscriptionSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read inline SNS subscription snapshot", e);
        }
        if (snapshot.topicAttributes() != null) {
            snsService.restoreTopicAttributes(snapshot.topicArn(), snapshot.topicAttributes(),
                    MUTABLE_TOPIC_ATTRIBUTES, snapshot.region());
        }
        Set<String> priorArns = snapshot.owned().stream()
                .map(Subscription::getSubscriptionArn).collect(Collectors.toSet());
        for (Subscription current : snsService.listSubscriptionsByTopic(snapshot.topicArn(), snapshot.region())) {
            String key = INLINE_SUBSCRIPTION_PREFIX + current.getSubscriptionArn();
            if (r.getAttributes().containsKey(key) && !priorArns.contains(current.getSubscriptionArn())) {
                snsService.unsubscribe(current.getSubscriptionArn(), snapshot.region());
            }
        }
        r.getAttributes().keySet().removeIf(key -> key.startsWith(INLINE_SUBSCRIPTION_PREFIX));
        for (Subscription prior : snapshot.owned()) {
            Subscription restored = snsService.subscribe(snapshot.topicArn(), prior.getProtocol(),
                    prior.getEndpoint(), snapshot.region(), prior.getAttributes());
            r.getAttributes().put(INLINE_SUBSCRIPTION_PREFIX + restored.getSubscriptionArn(), "true");
        }
        r.getAttributes().remove(CfnRollback.SNS_INLINE_SUBSCRIPTIONS_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!resource.getAttributes().containsKey(CfnRollback.SNS_INLINE_SUBSCRIPTIONS_SNAPSHOT_ATTR)) {
            return false;
        }
        restoreInlineSubscriptions(resource);
        return true;
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        previous.getAttributes().keySet().removeIf(key -> key.startsWith(INLINE_SUBSCRIPTION_PREFIX));
        attempted.getAttributes().forEach((key, value) -> {
            if (key.startsWith(INLINE_SUBSCRIPTION_PREFIX)) {
                previous.getAttributes().put(key, value);
            }
        });
    }

    private void reconcileInlineSubscriptions(StackResource r, String topicArn, String region,
                                              List<SubscriptionTarget> desired, List<Subscription> existing,
                                              Set<String> added) {
        for (SubscriptionTarget target : desired) {
            boolean present = existing.stream().anyMatch(sub -> target.matches(sub));
            Subscription sub = snsService.subscribe(topicArn, target.protocol(), target.endpoint(),
                    region, Map.of());
            if (!present) {
                added.add(sub.getSubscriptionArn());
                r.getAttributes().put(INLINE_SUBSCRIPTION_PREFIX + sub.getSubscriptionArn(), "true");
            }
        }
        for (Subscription sub : existing) {
            String key = INLINE_SUBSCRIPTION_PREFIX + sub.getSubscriptionArn();
            if (r.getAttributes().containsKey(key)
                    && desired.stream().noneMatch(target -> target.matches(sub))) {
                snsService.unsubscribe(sub.getSubscriptionArn(), region);
                r.getAttributes().remove(key);
            }
        }
        r.getAttributes().keySet().removeIf(key -> key.startsWith(INLINE_SUBSCRIPTION_PREFIX)
                && existing.stream().noneMatch(sub -> key.equals(INLINE_SUBSCRIPTION_PREFIX
                + sub.getSubscriptionArn())) && !added.contains(key.substring(INLINE_SUBSCRIPTION_PREFIX.length())));
    }

    private record SubscriptionTarget(String protocol, String endpoint) {
        private boolean matches(Subscription subscription) {
            return Objects.equals(protocol, subscription.getProtocol())
                    && Objects.equals(endpoint, subscription.getEndpoint());
        }
    }

    private void provisionSubscription(StackResource r, JsonNode props, ProvisionContext ctx) {
        String topicArn = ctx.engine().resolve(props.path("TopicArn"));
        String protocol = ctx.engine().resolve(props.path("Protocol"));
        String endpoint = ctx.engine().resolve(props.path("Endpoint"));

        Map<String, String> attributes = new HashMap<>();
        // FilterPolicy and RedrivePolicy are JSON documents, so they go through
        // resolveJsonAttribute, which serializes the resolved node once. Passing them through the
        // scalar resolve() instead would hand SNS a re-encoded string and break filtering.
        if (props.has("FilterPolicy") && !props.path("FilterPolicy").isNull()) {
            attributes.put("FilterPolicy", ctx.engine().resolveJsonAttribute(props.path("FilterPolicy")));
        }
        if (props.has("FilterPolicyScope")) {
            attributes.put("FilterPolicyScope", ctx.engine().resolve(props.path("FilterPolicyScope")));
        }
        if (props.has("RawMessageDelivery")) {
            attributes.put("RawMessageDelivery", ctx.engine().resolve(props.path("RawMessageDelivery")));
        }
        if (props.has("RedrivePolicy") && !props.path("RedrivePolicy").isNull()) {
            attributes.put("RedrivePolicy", ctx.engine().resolveJsonAttribute(props.path("RedrivePolicy")));
        }

        Subscription sub = snsService.subscribe(topicArn, protocol, endpoint, ctx.region(), attributes);
        r.setPhysicalId(sub.getSubscriptionArn());
        r.getAttributes().put("Arn", sub.getSubscriptionArn());
    }

    /**
     * A topic policy is not an entity of its own. It writes the Policy attribute of every topic the
     * template lists, so re-applying on UpdateStack is what an update means, and the physical id
     * only has to stay put across updates, as with {@code AWS::SQS::QueuePolicy}. {@code Ref} and
     * {@code Fn::GetAtt Id} both return that id.
     */
    private void provisionTopicPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        List<String> topics = ctx.resolveStringList(props, "Topics");
        if (topics.isEmpty()) {
            throw new AwsException("ValidationError",
                    "AWS::SNS::TopicPolicy requires at least one topic ARN in Topics.", 400);
        }
        if (props == null || !props.hasNonNull("PolicyDocument")) {
            throw new AwsException("ValidationError", "AWS::SNS::TopicPolicy requires a PolicyDocument.", 400);
        }
        // The document is JSON with intrinsics inside (topic ARNs in Resource, typically), so it
        // goes through resolveJsonAttribute like FilterPolicy above rather than the scalar resolve.
        String policy = ctx.engine().resolveJsonAttribute(props.path("PolicyDocument"));
        for (String topicArn : topics) {
            snsService.setTopicAttributes(topicArn, "Policy", policy, ctx.region());
        }
        String id = ctx.isUpdate()
                ? ctx.priorPhysicalId()
                : "topic-policy-" + UUID.randomUUID().toString().substring(0, 8);
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case TOPIC -> snsService.deleteTopic(physicalId, region);
            case SUBSCRIPTION -> snsService.unsubscribe(physicalId, region);
            // No entity to remove. The topics keep the last policy written, the same choice
            // SqsCfnProvisioner makes for AWS::SQS::QueuePolicy.
            case TOPIC_POLICY -> { }
            default -> throw new IllegalStateException(
                    "SnsCfnProvisioner cannot delete " + resourceType);
        }
    }
}
