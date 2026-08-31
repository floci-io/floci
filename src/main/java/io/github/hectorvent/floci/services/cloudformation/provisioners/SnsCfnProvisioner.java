package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::SNS::Topic} and {@code AWS::SNS::Subscription}. */
@ApplicationScoped
public class SnsCfnProvisioner implements CfnResourceProvisioner {

    private static final String TOPIC = "AWS::SNS::Topic";
    private static final String SUBSCRIPTION = "AWS::SNS::Subscription";
    private static final int TOPIC_NAME_MAX_LENGTH = 256;

    private final SnsService snsService;

    public SnsCfnProvisioner(SnsService snsService) {
        this.snsService = snsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TOPIC, SUBSCRIPTION);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case TOPIC -> provisionTopic(r, props, ctx);
            case SUBSCRIPTION -> provisionSubscription(r, props, ctx);
            default -> throw new IllegalStateException(
                    "SnsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionTopic(StackResource r, JsonNode props, ProvisionContext ctx) {
        String topicName = ctx.resolveOptional(props, "TopicName");
        String contentBasedDedupFlag = ctx.resolveOptional(props, "ContentBasedDeduplication");
        if (topicName == null || topicName.isBlank()) {
            topicName = ctx.generatePhysicalName(r.getLogicalId(), TOPIC_NAME_MAX_LENGTH, false);
        }

        Map<String, String> attributes = new HashMap<>();
        if (contentBasedDedupFlag != null && !contentBasedDedupFlag.isBlank()) {
            attributes.put("ContentBasedDeduplication", contentBasedDedupFlag);
        }

        var topic = snsService.createTopic(topicName, attributes, Map.of(), ctx.region());
        // Ref returns the topic ARN, which is why the physical id is the ARN and not the name.
        r.setPhysicalId(topic.getTopicArn());
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

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case TOPIC -> snsService.deleteTopic(physicalId, region);
            case SUBSCRIPTION -> snsService.unsubscribe(physicalId, region);
            default -> throw new IllegalStateException(
                    "SnsCfnProvisioner cannot delete " + resourceType);
        }
    }
}
