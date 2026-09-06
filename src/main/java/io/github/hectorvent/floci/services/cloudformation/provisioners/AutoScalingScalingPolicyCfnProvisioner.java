package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::AutoScaling::ScalingPolicy}, backed by
 * {@link AutoScalingService}. {@code Ref} and {@code Fn::GetAtt Arn} are the policy ARN and
 * {@code Fn::GetAtt PolicyName} the generated name, matching AWS, which offers no PolicyName
 * property and names the policy after the stack and logical id.
 *
 * <p>PutScalingPolicy is an upsert keyed by group and name, so an update reuses the name recorded
 * at create time and rewrites the policy in place. AutoScalingGroupName is createOnly: moving the
 * policy to another group is refused as a replacement rather than left behind on the old group.
 * StepAdjustments, MinAdjustmentMagnitude, MetricAggregationType, PredictiveScalingConfiguration
 * and the customized-metric and DisableScaleIn parts of TargetTrackingConfiguration have no
 * counterpart in {@link ScalingPolicy} and are accepted without effect.
 */
@ApplicationScoped
public class AutoScalingScalingPolicyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::AutoScaling::ScalingPolicy";
    /** The Auto Scaling API's limit on a policy name. */
    private static final int POLICY_NAME_MAX = 255;
    /** The defaults the Query handler applies when PutScalingPolicy omits these, kept in step with it. */
    private static final int DEFAULT_SCALING_ADJUSTMENT = 0;
    private static final int DEFAULT_COOLDOWN = 300;

    private final AutoScalingService autoScalingService;

    @Inject
    public AutoScalingScalingPolicyCfnProvisioner(AutoScalingService autoScalingService) {
        this.autoScalingService = autoScalingService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String asgName = ctx.resolveOptional(props, "AutoScalingGroupName");
        String policyName = priorPolicyName(r, ctx);
        if (policyName != null) {
            // Policy names are unique only within a group, so the lookup is scoped to the group the
            // template names. Only when the policy is not there does a same-named policy elsewhere
            // in the region mean the template moved it, which AWS treats as a replacement.
            boolean onThisGroup = !autoScalingService.describePolicies(ctx.region(), asgName, List.of(policyName))
                    .isEmpty();
            boolean onAnotherGroup = !onThisGroup
                    && !autoScalingService.describePolicies(ctx.region(), null, List.of(policyName)).isEmpty();
            if (onAnotherGroup) {
                throw new AwsException("ValidationError",
                        "Updating AutoScalingGroupName requires resource replacement, which is not supported.", 400);
            }
        } else {
            policyName = ctx.generatePhysicalName(r.getLogicalId(), POLICY_NAME_MAX, false);
        }

        ScalingPolicy policy = autoScalingService.putScalingPolicy(ctx.region(), asgName, policyName,
                ctx.resolveOptional(props, "PolicyType"),
                ctx.resolveOptional(props, "AdjustmentType"),
                intOrDefault(ctx.resolveOptional(props, "ScalingAdjustment"), DEFAULT_SCALING_ADJUSTMENT),
                intOrDefault(ctx.resolveOptional(props, "Cooldown"), DEFAULT_COOLDOWN),
                nullableInt(ctx.resolveOptional(props, "EstimatedInstanceWarmup")),
                targetTracking(props, ctx));

        r.setPhysicalId(policy.getPolicyArn());
        r.getAttributes().put("Arn", policy.getPolicyArn());
        r.getAttributes().put("PolicyName", policyName);
    }

    /**
     * The name this resource's policy already carries, or null on a first create. Read from the
     * attribute recorded at create time rather than parsed out of the ARN, so the ARN's shape stays
     * the service's business. Generating a fresh name on every UpdateStack would put a second policy
     * on the group and orphan the first, which then outlives the stack.
     */
    private static String priorPolicyName(StackResource r, ProvisionContext ctx) {
        if (!ctx.isUpdate() || r.getAttributes() == null) {
            return null;
        }
        String prior = r.getAttributes().get("PolicyName");
        return prior == null || prior.isBlank() ? null : prior;
    }

    private static ScalingPolicy.TargetTrackingConfiguration targetTracking(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("TargetTrackingConfiguration")) {
            return null;
        }
        JsonNode node = ctx.engine().resolveNode(props.get("TargetTrackingConfiguration"));
        if (node == null || node.isNull()) {
            return null;
        }
        ScalingPolicy.TargetTrackingConfiguration configuration = new ScalingPolicy.TargetTrackingConfiguration();
        String targetValue = ctx.engine().resolve(node.path("TargetValue"));
        if (targetValue != null && !targetValue.isBlank()) {
            configuration.setTargetValue(Double.parseDouble(targetValue));
        }
        JsonNode predefined = node.get("PredefinedMetricSpecification");
        if (predefined != null && !predefined.isNull()) {
            ScalingPolicy.PredefinedMetricSpecification specification = new ScalingPolicy.PredefinedMetricSpecification();
            specification.setPredefinedMetricType(ctx.engine().resolve(predefined.path("PredefinedMetricType")));
            configuration.setPredefinedMetricSpecification(specification);
        }
        return configuration;
    }

    private static int intOrDefault(String value, int fallback) {
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static Integer nullableInt(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    /**
     * Without this the policy outlives its stack and keeps showing up in DescribePolicies. The
     * service matches by name or ARN and already treats a missing policy as nothing to do.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        autoScalingService.deletePolicy(region, null, physicalId);
    }
}
