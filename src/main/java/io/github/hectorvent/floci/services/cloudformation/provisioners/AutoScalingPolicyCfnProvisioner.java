package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::AutoScaling::ScalingPolicy} (aws-bench gap batch,
 * issue #17).
 */
@ApplicationScoped
public class AutoScalingPolicyCfnProvisioner implements CfnResourceProvisioner {

    private final AutoScalingService autoScalingService;

    @Inject
    public AutoScalingPolicyCfnProvisioner(AutoScalingService autoScalingService) {
        this.autoScalingService = autoScalingService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::AutoScaling::ScalingPolicy");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String asgName = ctx.resolveOptional(props, "AutoScalingGroupName");
        String declaredPolicyName = ctx.resolveOptional(props, "PolicyName");
        String previousPolicyName = r.getAttributes().get("PolicyName");
        String policyName;
        if (declaredPolicyName != null && !declaredPolicyName.isBlank()) {
            policyName = declaredPolicyName;
        } else if (previousPolicyName != null && !previousPolicyName.isBlank()) {
            // No PolicyName declared: AutoScalingService#putScalingPolicy is keyed by (asgName,
            // policyName), so a freshly generated name on every UpdateStack call would orphan the
            // previous policy instead of reconciling it in place.
            policyName = previousPolicyName;
        } else {
            policyName = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }

        String policyType = ctx.resolveOptional(props, "PolicyType");
        String adjustmentType = ctx.resolveOptional(props, "AdjustmentType");
        String scalingAdjustmentStr = ctx.resolveOptional(props, "ScalingAdjustment");
        int scalingAdjustment = scalingAdjustmentStr != null ? Integer.parseInt(scalingAdjustmentStr) : 0;
        String cooldownStr = ctx.resolveOptional(props, "Cooldown");
        int cooldown = cooldownStr != null ? Integer.parseInt(cooldownStr) : 0;
        String warmupStr = ctx.resolveOptional(props, "EstimatedInstanceWarmup");
        Integer estimatedInstanceWarmup = warmupStr != null ? Integer.valueOf(warmupStr) : null;
        ScalingPolicy.TargetTrackingConfiguration targetTracking = parseTargetTracking(props, ctx);

        ScalingPolicy policy = autoScalingService.putScalingPolicy(ctx.region(), asgName, policyName,
                policyType, adjustmentType, scalingAdjustment, cooldown, estimatedInstanceWarmup, targetTracking);

        r.setPhysicalId(policy.getPolicyArn());
        // AWS::AutoScaling::ScalingPolicy's only documented Fn::GetAtt attribute (Ref returns the
        // same ARN, per AWS public schema); no local registry schema is present under
        // local/aws/cfn-resource-schemas.
        r.getAttributes().put("Arn", policy.getPolicyArn());
        r.getAttributes().put("PolicyName", policyName);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // AutoScalingService#deletePolicy matches by policy name or ARN and does not actually use
        // its asgName parameter for filtering, so it can be safely omitted here (only the
        // type/physical-id pair is available on this delete path).
        autoScalingService.deletePolicy(region, null, physicalId);
    }

    private ScalingPolicy.TargetTrackingConfiguration parseTargetTracking(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("TargetTrackingConfiguration") || props.get("TargetTrackingConfiguration").isNull()) {
            return null;
        }
        JsonNode node = ctx.engine().resolveNode(props.get("TargetTrackingConfiguration"));
        ScalingPolicy.TargetTrackingConfiguration ttc = new ScalingPolicy.TargetTrackingConfiguration();
        if (node.has("TargetValue")) {
            ttc.setTargetValue(node.get("TargetValue").asDouble());
        }
        JsonNode pms = node.get("PredefinedMetricSpecification");
        if (pms != null && !pms.isNull()) {
            ScalingPolicy.PredefinedMetricSpecification spec = new ScalingPolicy.PredefinedMetricSpecification();
            spec.setPredefinedMetricType(pms.path("PredefinedMetricType").asText(null));
            ttc.setPredefinedMetricSpecification(spec);
        }
        return ttc;
    }
}
