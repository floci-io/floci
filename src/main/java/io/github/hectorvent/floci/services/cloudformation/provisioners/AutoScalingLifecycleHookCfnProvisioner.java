package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
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
        String existingName = r.getPhysicalId();
        String hookName = ctx.resolveOptional(props, "LifecycleHookName");
        if (hookName == null || hookName.isBlank()) {
            // An unnamed hook keeps the name its first execution generated. Minting a fresh one on
            // every update leaves the previous hook on the group with nothing referencing it.
            hookName = existingName != null && !existingName.isBlank()
                    ? existingName
                    : ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        Integer heartbeat = props != null && props.hasNonNull("HeartbeatTimeout")
                ? props.get("HeartbeatTimeout").asInt() : null;
        String asgName = ctx.resolveOptional(props, "AutoScalingGroupName");
        String existingAsg = r.getAttributes() == null ? null : r.getAttributes().get("AutoScalingGroupName");
        // LifecycleHookName and AutoScalingGroupName are both createOnly, so a change to either is a
        // replacement. Provisioning through it would put the new hook in place and overwrite the id
        // that delete uses, leaving the previous hook on its group and still firing. Reported the
        // same way EcsCapacityCfnProvisioner reports a changed Name.
        requireUnchanged("LifecycleHookName", existingName, hookName);
        requireUnchanged("AutoScalingGroupName", existingAsg, asgName);
        // Recorded so delete can scope to the owning group: hook names are unique only within one.
        if (asgName != null && !asgName.isBlank()) {
            r.getAttributes().put("AutoScalingGroupName", asgName);
        }
        autoScalingService.putLifecycleHook(ctx.region(),
                asgName,
                hookName,
                ctx.resolveOptional(props, "LifecycleTransition"),
                ctx.resolveOptional(props, "NotificationTargetARN"),
                ctx.resolveOptional(props, "RoleARN"),
                ctx.resolveOptional(props, "NotificationMetadata"),
                heartbeat,
                ctx.resolveOptional(props, "DefaultResult"));
        r.setPhysicalId(hookName);
    }

    /** Rejects a change to a createOnly property rather than stranding the resource it names. */
    private void requireUnchanged(String property, String existing, String requested) {
        if (existing == null || existing.isBlank() || requested == null || requested.isBlank()) {
            return;
        }
        if (!existing.equals(requested)) {
            throw new AwsException("ValidationError",
                    "Updating " + property + " requires resource replacement, which is not supported.", 400);
        }
    }

    /**
     * Deletes the hook on the group that owns it. Hook names are unique only within an Auto Scaling
     * group, so deleting by name alone takes out an identically named hook on an unrelated group
     * and stops its lifecycle action.
     */
    @Override
    public void delete(StackResource resource, String region) {
        String hookName = resource.getPhysicalId();
        if (hookName == null || hookName.isBlank()) {
            return;
        }
        String asgName = resource.getAttributes() == null
                ? null : resource.getAttributes().get("AutoScalingGroupName");
        if (asgName != null && !asgName.isBlank()) {
            autoScalingService.deleteLifecycleHook(region, asgName, hookName);
            return;
        }
        delete(resource.getResourceType(), hookName, region);
    }

    /**
     * Fallback for resources provisioned before the group was recorded. The hook still has to go —
     * it may sit on a group that outlives the stack — but without the owner this can only match by
     * name within the region.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        autoScalingService.deleteLifecycleHookByName(region, physicalId);
    }
}
