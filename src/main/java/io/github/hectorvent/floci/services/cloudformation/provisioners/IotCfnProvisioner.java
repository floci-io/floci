package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotService;
import io.github.hectorvent.floci.services.iot.model.IotPolicy;
import io.github.hectorvent.floci.services.iot.model.IotTopicRule;
import io.github.hectorvent.floci.services.iot.model.Thing;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions the IoT Core registry and rules engine types: {@code AWS::IoT::Thing},
 * {@code AWS::IoT::Policy} and {@code AWS::IoT::TopicRule}. The physical id is the resource name,
 * as it is on AWS, so a rename is a replacement: the new entity is created first and the prior
 * one removed afterwards, since there is no generic replacement flow.
 */
@ApplicationScoped
public class IotCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IotCfnProvisioner.class);
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final int NAME_MAX_LENGTH = 128;

    private final IotService iotService;

    public IotCfnProvisioner(IotService iotService) {
        this.iotService = iotService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::IoT::Thing", "AWS::IoT::Policy", "AWS::IoT::TopicRule");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::IoT::Thing" -> provisionThing(r, props, ctx);
            case "AWS::IoT::Policy" -> provisionPolicy(r, props, ctx);
            case "AWS::IoT::TopicRule" -> provisionTopicRule(r, props, ctx);
            default -> throw new IllegalStateException("IotCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::IoT::Thing" -> CfnDeletes.safeDelete("thing", physicalId,
                    () -> iotService.deleteThing(physicalId, region), NOT_FOUND);
            case "AWS::IoT::Policy" -> CfnDeletes.safeDelete("policy", physicalId,
                    () -> detachAndDeletePolicy(physicalId, region), NOT_FOUND);
            case "AWS::IoT::TopicRule" -> CfnDeletes.safeDelete("topic rule", physicalId,
                    () -> iotService.deleteTopicRule(physicalId, region), NOT_FOUND);
            default -> throw new IllegalStateException("IotCfnProvisioner cannot handle " + resourceType);
        }
    }

    /**
     * {@code AttributePayload.Attributes} is the whole desired attribute set: an update replaces the
     * stored attributes rather than merging into them, as the CloudFormation handler does.
     */
    private void provisionThing(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "ThingName"),
                r.getLogicalId(), NAME_MAX_LENGTH, false);
        Map<String, String> attributes = thingAttributes(props, ctx);
        String region = ctx.region();
        boolean sameThing = ctx.reusesPriorEntity(name);
        Thing thing = sameThing
                ? iotService.updateThing(name, attributes, null, region)
                : iotService.createThing(name, attributes, region);
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", thing.getThingArn());
        r.getAttributes().put("Id", thing.getThingId());
        if (!sameThing) {
            deletePrior(r, ctx);
        }
    }

    /**
     * A changed document becomes a new default version, as the CloudFormation handler does; the
     * document is compared as the string the policy stores, which is what this provisioner wrote
     * on the previous execution. {@code Id} is the policy name, as on AWS.
     */
    private void provisionPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "PolicyName"),
                r.getLogicalId(), NAME_MAX_LENGTH, false);
        String document = props == null ? null : ctx.engine().resolveJsonAttribute(props.path("PolicyDocument"));
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("AWS::IoT::Policy requires PolicyDocument");
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String region = ctx.region();
        if (ctx.reusesPriorEntity(name)) {
            IotPolicy policy = iotService.getPolicy(name, region);
            if (!document.equals(policy.getPolicyDocument())) {
                createDefaultVersion(name, document, region);
            }
            reconcileTags(policy.getPolicyArn(), tags);
            recordPolicy(r, name, policy.getPolicyArn());
            return;
        }
        IotPolicy policy = iotService.createPolicy(name, document, region);
        recordPolicy(r, name, policy.getPolicyArn());
        finishCreate(r, ctx, () -> applyTags(policy.getPolicyArn(), tags));
        deletePrior(r, ctx);
    }

    /**
     * A policy holds at most five versions. When the service refuses a sixth, this does what the
     * AWS handler does: the oldest version goes, in one step on the service's side, and the new one
     * is created again. Unlike that handler it tries more than once, because a version created by
     * another client between the delete and the retry would otherwise fail the stack update; it
     * gives up after as many attempts as a policy has versions.
     */
    private void createDefaultVersion(String name, String document, String region) {
        for (int attempt = 1; ; attempt++) {
            try {
                iotService.createPolicyVersion(name, document, true, region);
                return;
            } catch (AwsException e) {
                if (!"VersionsLimitExceededException".equals(e.getErrorCode())
                        || attempt >= IotService.MAX_POLICY_VERSIONS) {
                    throw e;
                }
                LOG.debugv("Policy {0} is at its version limit, deleting the oldest version before the new default: {1}",
                        name, e.getMessage());
            }
            iotService.makeRoomForPolicyVersion(name, region);
        }
    }

    /**
     * The template payload is handed to the service in the IoT API's own shape: the same members
     * with a lowercase first letter, all the way down into the actions. A generated name keeps only
     * the characters a rule name allows, since AWS rejects the hyphens
     * {@link ProvisionContext#generatePhysicalName} emits.
     */
    private void provisionTopicRule(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "RuleName");
        if (name == null || name.isBlank()) {
            name = ctx.stablePhysicalName(null, r.getLogicalId(), NAME_MAX_LENGTH, false)
                    .replaceAll("[^A-Za-z0-9_]", "");
        }
        if (props == null || !props.hasNonNull("TopicRulePayload")) {
            throw new IllegalArgumentException("AWS::IoT::TopicRule requires TopicRulePayload");
        }
        JsonNode payload = toApiShape(ctx.engine().resolveNode(props.get("TopicRulePayload")));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String region = ctx.region();
        if (ctx.reusesPriorEntity(name)) {
            IotTopicRule rule = iotService.replaceTopicRule(name, payload, region);
            reconcileTags(rule.getRuleArn(), tags);
            recordTopicRule(r, name, rule.getRuleArn());
            return;
        }
        IotTopicRule rule = iotService.createTopicRule(name, payload, region);
        recordTopicRule(r, name, rule.getRuleArn());
        finishCreate(r, ctx, () -> applyTags(rule.getRuleArn(), tags));
        deletePrior(r, ctx);
    }

    /**
     * The service refuses to delete a policy that is still attached to a principal, and on AWS the
     * stack would fail with a DeleteConflictException. Here the stack that created the policy also
     * takes its attachments with it, so a local teardown never gets stuck on a device that was
     * connected while the stack existed.
     */
    private void detachAndDeletePolicy(String policyName, String region) {
        for (String target : iotService.listTargetsForPolicy(policyName, region)) {
            iotService.detachPolicy(policyName, target, region);
        }
        iotService.deletePolicy(policyName, region);
    }

    private static void recordPolicy(StackResource r, String name, String arn) {
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", arn);
        r.getAttributes().put("Id", name);
    }

    private static void recordTopicRule(StackResource r, String name, String arn) {
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", arn);
    }

    private static Map<String, String> thingAttributes(JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (props == null || !props.has("AttributePayload")) {
            return attributes;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("AttributePayload"));
        JsonNode values = resolved == null ? null : resolved.path("Attributes");
        if (values != null && values.isObject()) {
            values.fields().forEachRemaining(field ->
                    attributes.put(field.getKey(), ctx.engine().resolve(field.getValue())));
        }
        return attributes;
    }

    /** Recursively renames the members of a resolved node to the API's camelCase, dropping nulls. */
    private static JsonNode toApiShape(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> {
                if (!field.getValue().isNull()) {
                    out.set(decapitalize(field.getKey()), toApiShape(field.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(toApiShape(item)));
            return out;
        }
        return node;
    }

    private static String decapitalize(String key) {
        return key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }

    /**
     * Runs the calls that follow a create with the resource marked as owned by this stack, so a
     * failure among them still lets the create rollback delete the entity that now exists. When the
     * create was a replacement, the entity is removed here instead: CloudFormationService restores
     * the prior StackResource and never learns the new name, so nothing else would.
     */
    private void finishCreate(StackResource r, ProvisionContext ctx, Runnable finish) {
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        try {
            finish.run();
        } catch (RuntimeException failure) {
            if (ctx.isUpdate()) {
                unwindReplacement(r, ctx.region(), failure);
            }
            throw failure;
        }
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private void unwindReplacement(StackResource r, String region, RuntimeException failure) {
        try {
            delete(r.getResourceType(), r.getPhysicalId(), region);
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            String reason = "Could not remove " + r.getPhysicalId() + ", the replacement created for "
                    + r.getLogicalId() + " by a failed update: " + cleanupFailure.getMessage();
            LOG.warn(reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    /**
     * Removes the entity a replacement supersedes. As in CloudFormation's update cleanup, a failure
     * does not fail the update: the stack already points at the new entity, so the prior one is
     * left behind with a warning in the log.
     */
    private void deletePrior(StackResource r, ProvisionContext ctx) {
        if (!ctx.isUpdate()) {
            return;
        }
        try {
            delete(r.getResourceType(), ctx.priorPhysicalId(), ctx.region());
        } catch (AwsException e) {
            LOG.warnv("Could not delete {0} {1} replaced by {2}: {3}",
                    r.getResourceType(), ctx.priorPhysicalId(), r.getPhysicalId(), e.getMessage());
        }
    }

    private void applyTags(String arn, Map<String, String> tags) {
        if (!tags.isEmpty()) {
            iotService.tagResource(arn, tags);
        }
    }

    private void reconcileTags(String arn, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(iotService.listTagsForResource(arn), desired);
        if (!stale.isEmpty()) {
            iotService.untagResource(arn, stale);
        }
        applyTags(arn, desired);
    }
}
