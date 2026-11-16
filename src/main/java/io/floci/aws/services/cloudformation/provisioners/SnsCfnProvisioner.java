package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sns.model.Subscription;
import io.floci.aws.services.sns.model.Topic;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
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
    private static final String PRIOR_INLINE_ATTR = CfnRollback.SNS_PRIOR_INLINE_SUBSCRIPTIONS_ATTR;

    private static final String ADOPTED_ATTR = CfnRollback.SNS_ADOPTED_TOPIC_UPDATE_SNAPSHOT_ATTR;
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

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_INLINE_ATTR);
        resource.getAttributes().remove(ADOPTED_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String adopted = resource.getAttributes().get(ADOPTED_ATTR);
        if (adopted != null) {
            // A topic the update adopted is not the stack's to keep changed: put its attributes back and
            // drop the subscriptions this update made, then let the replacement cleanup point back.
            try {
                JsonNode snapshot = MAPPER.readTree(adopted);
                Map<String, String> prior = new HashMap<>();
                snapshot.path("attributes").fields().forEachRemaining(e ->
                        prior.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                List<String> added = new ArrayList<>();
                snapshot.path("subscriptions").forEach(arn -> added.add(arn.asText()));
                restoreAdoptedTopic(snapshot.path("topicArn").asText(), snapshot.path("region").asText(),
                        prior, added);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not read the adopted SNS topic snapshot of "
                        + resource.getLogicalId(), e);
            }
            resource.getAttributes().remove(ADOPTED_ATTR);
        }
        if (!ReplacementCleanup.rollback(resource, this::delete)) {
            return false;
        }
        String prior = resource.getAttributes().remove(PRIOR_INLINE_ATTR);
        if (prior != null) {
            resource.getAttributes().keySet().removeIf(key -> key.startsWith(INLINE_SUBSCRIPTION_PREFIX));
            for (String arn : prior.split(",")) {
                if (!arn.isEmpty()) {
                    resource.getAttributes().put(INLINE_SUBSCRIPTION_PREFIX + arn, "true");
                }
            }
        }
        return true;
    }

    private void provisionTopic(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String topicName = ctx.resolveOptional(props, "TopicName");
        String contentBasedDedupFlag = ctx.resolveOptional(props, "ContentBasedDeduplication");
        String throughputScope = ctx.resolveOptional(props, "FifoThroughputScope");
        String maximumMessageSize = ctx.resolveOptional(props, "MaximumMessageSize");
        // The .fifo suffix is what makes SnsService treat a topic as FIFO, so an explicitly named
        // one is FIFO with or without the flag, which only has to steer a generated name.
        boolean fifo = "true".equalsIgnoreCase(ctx.resolveOptional(props, "FifoTopic"))
                || (topicName != null && topicName.endsWith(FIFO_SUFFIX));
        String priorName = r.getAttributes().get("TopicName");
        // The engine's change decides whether the update replaces the topic: TopicName and
        // FifoTopic are create-only in the registry. A topic's mode is carried by its name, so the
        // prior name's .fifo suffix tells the live mode; the engine reads an added FifoTopic as no
        // change and would apply the update in place. A named topic cannot be replaced under the
        // same name.
        boolean replaces = ctx.isUpdate() && priorName != null && !priorName.isBlank()
                && (ctx.changes(TOPIC, props).requiresReplacement() || priorName.endsWith(FIFO_SUFFIX) != fifo);
        if (replaces && topicName != null && topicName.equals(priorName)) {
            throw CfnExceptions.customNamedReplacement(topicName);
        }
        if (topicName == null || topicName.isBlank()) {
            // ctx.stablePhysicalName does not fit here: the physical id is the topic ARN, so the
            // prior name comes from the attribute recorded at create time. Reusing it keeps an
            // unnamed topic (and its subscriptions) across updates instead of orphaning it. A
            // replacing update gets a fresh generated name instead.
            if (priorName != null && !priorName.isBlank() && !replaces) {
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
        // createTopic leaves an existing topic untouched, so an update writes the mutable
        // attributes itself. A property the template dropped is reset, not left standing.
        Map<String, String> desired = new HashMap<>();
        if (ctx.isUpdate()) {
            desired.putAll(attributes);
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
        }
        // A replacing update that adopted an existing topic changes something the stack did not
        // create, so what it writes (the prior values, null for absent) is kept for the undo.
        boolean adopted = ctx.isUpdate() && !created && !ctx.priorPhysicalId().equals(topic.getTopicArn());
        Map<String, String> priorAttributes = new HashMap<>();
        if (adopted) {
            desired.keySet().forEach(name -> priorAttributes.put(name, topic.getAttributes().get(name)));
        }
        // Ref returns the topic ARN, which is why the physical id is the ARN and not the name.
        r.setPhysicalId(topic.getTopicArn());
        // TopicArn is the attribute aws-sns-topic.json declares read-only. Arn is kept alongside it
        // because templates written against earlier Floci releases already reference it.
        r.getAttributes().put("TopicArn", topic.getTopicArn());
        r.getAttributes().put("Arn", topic.getTopicArn());
        r.getAttributes().put("TopicName", topicName);

        List<Subscription> existing = snsService.listSubscriptionsByTopic(topic.getTopicArn(), ctx.region());
        Set<String> added = new HashSet<>();
        CfnRollback.guarded(r, ctx, created,
                () -> snsService.deleteTopic(topic.getTopicArn(), ctx.region()),
                () -> {
                    if (adopted) {
                        restoreAdoptedTopic(topic.getTopicArn(), ctx.region(), priorAttributes, added);
                    } else {
                        added.forEach(arn -> snsService.unsubscribe(arn, ctx.region()));
                    }
                },
                () -> {
                    desired.forEach((name, value) ->
                            snsService.setTopicAttributes(topic.getTopicArn(), name, value, ctx.region()));
                    reconcileInlineSubscriptions(r, topic.getTopicArn(), ctx.region(),
                            desiredSubscriptions, existing, added);
                });
        boolean replaced = ctx.isUpdate() && !ctx.priorPhysicalId().equals(r.getPhysicalId());
        if (replaced) {
            r.getAttributes().put(PRIOR_INLINE_ATTR, attributesBefore.keySet().stream()
                    .filter(key -> key.startsWith(INLINE_SUBSCRIPTION_PREFIX))
                    .map(key -> key.substring(INLINE_SUBSCRIPTION_PREFIX.length()))
                    .collect(Collectors.joining(",")));
        } else {
            r.getAttributes().remove(PRIOR_INLINE_ATTR);
        }
        if (adopted) {
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("topicArn", topic.getTopicArn());
            snapshot.put("region", ctx.region());
            ObjectNode attrs = snapshot.putObject("attributes");
            priorAttributes.forEach((name, value) -> {
                if (value == null) {
                    attrs.putNull(name);
                } else {
                    attrs.put(name, value);
                }
            });
            added.forEach(arn -> snapshot.withArray("subscriptions").add(arn));
            r.getAttributes().put(ADOPTED_ATTR, snapshot.toString());
        } else {
            r.getAttributes().remove(ADOPTED_ATTR);
        }
        // A topic the update only adopted, because its name already existed, is not deleted by a rollback.
        ReplacementCleanup.record(r, ctx, attributesBefore, replaced, created);
    }

    /** Puts an adopted topic back: the attributes this update wrote (null = absent) and its own subscriptions. */
    private void restoreAdoptedTopic(String topicArn, String region, Map<String, String> prior,
                                     Collection<String> addedSubscriptions) {
        addedSubscriptions.forEach(arn -> CfnDeletes.safeDelete("subscription", arn,
                () -> snsService.unsubscribe(arn, region), "NotFound"));
        if (!snsService.topicExists(topicArn, region)) {
            return;
        }
        prior.forEach((name, value) -> {
            if (value == null) {
                snsService.removeTopicAttribute(topicArn, name, region);
            } else {
                snsService.setTopicAttributes(topicArn, name, value, region);
            }
        });
    }

    private void reconcileInlineSubscriptions(StackResource r, String topicArn, String region,
                                              List<SubscriptionTarget> desired, List<Subscription> existing,
                                              Set<String> added) {
        for (SubscriptionTarget target : desired) {
            SnsService.SubscriptionCreation creation = snsService.subscribeWithStatus(
                    topicArn, target.protocol(), target.endpoint(), region, Map.of());
            Subscription sub = creation.subscription();
            if (creation.created()) {
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
        if (props.hasNonNull("SubscriptionRoleArn")) {
            attributes.put("SubscriptionRoleArn", ctx.engine().resolve(props.path("SubscriptionRoleArn")));
        }
        if (props.has("RedrivePolicy") && !props.path("RedrivePolicy").isNull()) {
            attributes.put("RedrivePolicy", ctx.engine().resolveJsonAttribute(props.path("RedrivePolicy")));
        }

        String subscriptionArn;
        if (ctx.isUpdate() && matchesPriorSubscription(topicArn, protocol, endpoint, ctx)) {
            subscriptionArn = ctx.priorPhysicalId();
            // Validate the firehose role before changing other attributes, including a removed role.
            if (attributes.containsKey("SubscriptionRoleArn") || ctx.previouslyDeclared("SubscriptionRoleArn")) {
                snsService.setSubscriptionAttribute(subscriptionArn, "SubscriptionRoleArn",
                        attributes.getOrDefault("SubscriptionRoleArn", ""), ctx.region());
            }
            Map<String, String> desired = new HashMap<>(attributes);
            desired.remove("SubscriptionRoleArn");
            for (String policy : List.of("FilterPolicy", "RedrivePolicy")) {
                if (!desired.containsKey(policy) && ctx.previouslyDeclared(policy)) {
                    desired.put(policy, "");
                }
            }
            if (!desired.containsKey("RawMessageDelivery") && ctx.previouslyDeclared("RawMessageDelivery")) {
                desired.put("RawMessageDelivery", "false");
            }
            if (!desired.containsKey("FilterPolicyScope") && ctx.previouslyDeclared("FilterPolicyScope")) {
                desired.put("FilterPolicyScope", "MessageAttributes");
            }
            desired.forEach((name, value) ->
                    snsService.setSubscriptionAttribute(subscriptionArn, name, value, ctx.region()));
        } else {
            Subscription sub = snsService.subscribe(topicArn, protocol, endpoint, ctx.region(), attributes);
            subscriptionArn = sub.getSubscriptionArn();
        }
        r.setPhysicalId(subscriptionArn);
        r.getAttributes().put("Arn", subscriptionArn);
    }

    private boolean matchesPriorSubscription(String topicArn, String protocol, String endpoint,
                                             ProvisionContext ctx) {
        Map<String, String> prior = snsService.getSubscriptionAttributes(ctx.priorPhysicalId(), ctx.region());
        return Objects.equals(topicArn, prior.get("TopicArn"))
                && Objects.equals(protocol, prior.get("Protocol"))
                && Objects.equals(endpoint, prior.get("Endpoint"));
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
            case TOPIC -> CfnDeletes.safeDelete("topic", physicalId,
                    () -> snsService.deleteTopic(physicalId, region), "NotFound");
            case SUBSCRIPTION -> snsService.unsubscribe(physicalId, region);
            // No entity to remove. The topics keep the last policy written, the same choice
            // SqsCfnProvisioner makes for AWS::SQS::QueuePolicy.
            case TOPIC_POLICY -> { }
            default -> throw new IllegalStateException(
                    "SnsCfnProvisioner cannot delete " + resourceType);
        }
    }
}
