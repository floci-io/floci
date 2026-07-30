package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::AutoScaling::LifecycleHook}, backed by
 * {@link AutoScalingService}. The physical id is the hook name, as in AWS.
 */
@ApplicationScoped
public class AutoScalingLifecycleHookCfnProvisioner implements CfnResourceProvisioner {

    private final AutoScalingService autoScalingService;

    @Inject
    public AutoScalingLifecycleHookCfnProvisioner(AutoScalingService autoScalingService) {
        this.autoScalingService = autoScalingService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::AutoScaling::LifecycleHook");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String hookName = ctx.resolveOptional(props, "LifecycleHookName");
        if (hookName == null || hookName.isBlank()) {
            hookName = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        Integer heartbeat = props != null && props.hasNonNull("HeartbeatTimeout")
                ? props.get("HeartbeatTimeout").asInt() : null;
        autoScalingService.putLifecycleHook(ctx.region(),
                ctx.resolveOptional(props, "AutoScalingGroupName"),
                hookName,
                ctx.resolveOptional(props, "LifecycleTransition"),
                ctx.resolveOptional(props, "NotificationTargetARN"),
                ctx.resolveOptional(props, "RoleARN"),
                ctx.resolveOptional(props, "NotificationMetadata"),
                heartbeat,
                ctx.resolveOptional(props, "DefaultResult"));
        r.setPhysicalId(hookName);
    }

    /**
     * Deletes the hook by name. Hooks are stored per group, but this path only holds the physical
     * id, so the lookup is by name within the region — otherwise a hook on a group that survives
     * the stack (an adopted or externally created group) would linger after DeleteStack.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        autoScalingService.deleteLifecycleHookByName(region, physicalId);
    }
}
