package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.applicationautoscaling.ApplicationAutoScalingService;
import io.github.hectorvent.floci.services.applicationautoscaling.ScalingConfigurationParser;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.SuspendedState;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::ApplicationAutoScaling::ScalableTarget} and
 * {@code AWS::ApplicationAutoScaling::ScalingPolicy}.
 *
 * <p>A target is identified by the triple (service namespace, resource id, scalable dimension)
 * rather than by a name of its own, and CloudFormation's {@code Ref} is those three joined by
 * {@code |}. That composite is this provisioner's physical id, which is also what a policy's
 * {@code ScalingTargetId} carries when a template points one at the other with {@code Ref}.
 */
@ApplicationScoped
public class ApplicationAutoScalingCfnProvisioner implements CfnResourceProvisioner {

    static final String SCALABLE_TARGET = "AWS::ApplicationAutoScaling::ScalableTarget";
    static final String SCALING_POLICY = "AWS::ApplicationAutoScaling::ScalingPolicy";

    private static final Logger LOG = Logger.getLogger(ApplicationAutoScalingCfnProvisioner.class);

    private final ApplicationAutoScalingService service;
    private final ObjectMapper objectMapper;

    public ApplicationAutoScalingCfnProvisioner(ApplicationAutoScalingService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SCALABLE_TARGET, SCALING_POLICY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (SCALABLE_TARGET.equals(r.getResourceType())) {
            provisionTarget(r, props, ctx);
        } else {
            provisionPolicy(r, props, ctx);
        }
    }

    private void provisionTarget(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = r.getAttributes() == null
                ? new HashMap<>() : new HashMap<>(r.getAttributes());
        Triple triple = Triple.fromProperties(props, ctx);
        // Required by the resource schema, so a template missing either is refused here rather
        // than registering a target with no bounds that silently never scales.
        Integer min = requiredInteger(props, "MinCapacity", ctx);
        Integer max = requiredInteger(props, "MaxCapacity", ctx);
        String roleArn = ctx.resolveOptional(props, "RoleARN");
        if (props.hasNonNull("ScheduledActions")) {
            // Floci's Application Auto Scaling has no scheduled actions, and silently dropping them
            // would let a template that scales on a clock look provisioned while nothing is scheduled.
            LOG.warnv("ScalableTarget {0} declares ScheduledActions, which Floci does not run; "
                    + "the target itself is registered", r.getLogicalId());
        }
        service.registerScalableTarget(triple.serviceNamespace(), triple.resourceId(), triple.scalableDimension(),
                min, max, roleArn, suspendedState(props, ctx), ctx.resolveTags(props, "Tags"), ctx.region());
        r.setPhysicalId(triple.composite());
        r.getAttributes().put("Id", triple.composite());
        // The id is the triple, so changing any part of it replaces the target rather than
        // updating it, and the one it displaced has to be deregistered once the update commits.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = r.getAttributes() == null
                ? new HashMap<>() : new HashMap<>(r.getAttributes());
        // PolicyName and PolicyType are both required by the resource schema: a generated name
        // or a defaulted type would accept a template that CloudFormation itself rejects.
        String policyName = Triple.required(ctx.resolveOptional(props, "PolicyName"), "PolicyName");
        String policyType = Triple.required(ctx.resolveOptional(props, "PolicyType"), "PolicyType");
        Triple triple = Triple.forPolicy(props, ctx);
        ScalingPolicy policy = service.putScalingPolicy(policyName, policyType,
                triple.serviceNamespace(), triple.resourceId(), triple.scalableDimension(),
                // Through the engine first: these blocks are nested objects, so an intrinsic inside
                // one is still a non-null node and would read as 0, "" or false rather than failing.
                ScalingConfigurationParser.parseTargetTracking(
                        ctx.engine().resolveNode(props.path("TargetTrackingScalingPolicyConfiguration")), objectMapper),
                ScalingConfigurationParser.parseStepScaling(
                        ctx.engine().resolveNode(props.path("StepScalingPolicyConfiguration"))),
                ctx.region());
        // Ref on a scaling policy is its ARN, so the ARN is the physical id rather than the name.
        r.setPhysicalId(policy.getPolicyArn());
        r.getAttributes().put("Arn", policy.getPolicyArn());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private SuspendedState suspendedState(JsonNode props, ProvisionContext ctx) {
        JsonNode node = ctx.engine().resolveNode(props.path("SuspendedState"));
        if (node == null || !node.isObject()) {
            return null;
        }
        SuspendedState state = new SuspendedState();
        if (node.hasNonNull("DynamicScalingInSuspended")) {
            state.setDynamicScalingInSuspended(node.get("DynamicScalingInSuspended").asBoolean());
        }
        if (node.hasNonNull("DynamicScalingOutSuspended")) {
            state.setDynamicScalingOutSuspended(node.get("DynamicScalingOutSuspended").asBoolean());
        }
        if (node.hasNonNull("ScheduledScalingSuspended")) {
            state.setScheduledScalingSuspended(node.get("ScheduledScalingSuspended").asBoolean());
        }
        return state;
    }

    private Integer requiredInteger(JsonNode props, String name, ProvisionContext ctx) {
        String value = Triple.required(ctx.resolveOptional(props, name), name);
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", name + " must be an integer, got: " + value, 400);
        }
    }

    /** The (namespace, resource id, dimension) a target is keyed by, and CloudFormation's {@code |} form of it. */
    private record Triple(String serviceNamespace, String resourceId, String scalableDimension) {

        static Triple fromProperties(JsonNode props, ProvisionContext ctx) {
            return new Triple(required(ctx.resolveOptional(props, "ServiceNamespace"), "ServiceNamespace"),
                    required(ctx.resolveOptional(props, "ResourceId"), "ResourceId"),
                    required(ctx.resolveOptional(props, "ScalableDimension"), "ScalableDimension"));
        }

        /**
         * A policy names its target either by the three properties or by {@code ScalingTargetId},
         * which is a {@code Ref} to the target and therefore already the composite.
         */
        static Triple forPolicy(JsonNode props, ProvisionContext ctx) {
            String targetId = ctx.resolveOptional(props, "ScalingTargetId");
            if (targetId == null || targetId.isBlank()) {
                return fromProperties(props, ctx);
            }
            String[] parts = targetId.split("\\|", -1);
            if (parts.length != 3) {
                throw new AwsException("ValidationException",
                        "ScalingTargetId must be resourceId|scalableDimension|serviceNamespace, got: " + targetId, 400);
            }
            return new Triple(parts[2], parts[0], parts[1]);
        }

        String composite() {
            return resourceId + "|" + scalableDimension + "|" + serviceNamespace;
        }

        static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new AwsException("ValidationException", name + " is required.", 400);
            }
            return value;
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        if (SCALABLE_TARGET.equals(resourceType)) {
            String[] parts = physicalId.split("\\|", -1);
            if (parts.length == 3) {
                // Deregistering takes the target's policies and their alarms with it, as on AWS.
                CfnDeletes.safeDelete("Scalable target", physicalId,
                        () -> service.deregisterScalableTarget(parts[2], parts[0], parts[1], region),
                        "ObjectNotFoundException");
            }
            return;
        }
        deletePolicyByArn(physicalId, region);
    }

    /**
     * A policy's physical id is its ARN, and the service deletes by the triple plus the name, so the
     * policy is looked up by ARN rather than parsed out of it. A policy already gone, which
     * deregistering its target does, is not an error: the stack is being torn down either way.
     */
    private void deletePolicyByArn(String policyArn, String region) {
        service.findPolicyByArn(policyArn, region).ifPresent(policy ->
                CfnDeletes.safeDelete("Scaling policy", policyArn,
                        () -> service.deleteScalingPolicy(policy.getPolicyName(), policy.getServiceNamespace(),
                                policy.getResourceId(), policy.getScalableDimension(), region),
                        "ObjectNotFoundException"));
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
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }
}
