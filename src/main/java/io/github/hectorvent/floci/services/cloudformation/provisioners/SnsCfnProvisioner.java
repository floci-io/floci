package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provisions {@code AWS::SNS::Topic}, {@code AWS::SNS::Subscription} and {@code AWS::SNS::TopicPolicy}. */
@ApplicationScoped
public class SnsCfnProvisioner implements CfnResourceProvisioner {

    private static final String TOPIC = "AWS::SNS::Topic";
    private static final String SUBSCRIPTION = "AWS::SNS::Subscription";
    private static final String TOPIC_POLICY = "AWS::SNS::TopicPolicy";
    private static final int TOPIC_NAME_MAX_LENGTH = 256;
    private static final String FIFO_SUFFIX = ".fifo";

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
        String topicName = ctx.resolveOptional(props, "TopicName");
        String contentBasedDedupFlag = ctx.resolveOptional(props, "ContentBasedDeduplication");
        String throughputScope = ctx.resolveOptional(props, "FifoThroughputScope");
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

        var topic = snsService.createTopic(topicName, attributes, Map.of(), ctx.region());
        // createTopic leaves an existing topic untouched, so an update writes the mutable
        // attributes itself. A property the template dropped is reset, not left standing.
        if (ctx.isUpdate()) {
            Map<String, String> desired = new HashMap<>(attributes);
            if (fifo) {
                desired.putIfAbsent("ContentBasedDeduplication", "false");
                desired.putIfAbsent("FifoThroughputScope", "Topic");
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

        var sub = snsService.subscribe(topicArn, protocol, endpoint, ctx.region(), attributes);
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
