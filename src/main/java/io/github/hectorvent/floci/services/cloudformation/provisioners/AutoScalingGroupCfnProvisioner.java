package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Provisions {@code AWS::AutoScaling::AutoScalingGroup} and
 * {@code AWS::AutoScaling::LaunchConfiguration}.
 *
 * <p>Extracted from {@code CloudFormationResourceProvisioner}. The two types share a provisioner
 * because they share {@code AutoScalingService} and because a group names a launch configuration:
 * splitting them would put the same service behind two classes for no gain. The per-type
 * siblings {@link AutoScalingLifecycleHookCfnProvisioner} and
 * {@link AutoScalingScalingPolicyCfnProvisioner} stay separate: they attach to a group rather
 * than being one.
 *
 * <p>Both types delete by physical id alone, so the id-only delete override serves them and
 * neither is in the engine's {@code DELETE_NEEDS_STACK_RESOURCE} set.
 */
@ApplicationScoped
public class AutoScalingGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final String GROUP = "AWS::AutoScaling::AutoScalingGroup";

    private static final String LAUNCH_CONFIGURATION = "AWS::AutoScaling::LaunchConfiguration";

    private static final Logger LOG = Logger.getLogger(AutoScalingGroupCfnProvisioner.class);

    private final AutoScalingService autoScalingService;

    @Inject
    public AutoScalingGroupCfnProvisioner(AutoScalingService autoScalingService) {
        this.autoScalingService = autoScalingService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(GROUP, LAUNCH_CONFIGURATION);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case LAUNCH_CONFIGURATION -> provisionLaunchConfiguration(r, props, ctx);
            case GROUP -> provisionAutoScalingGroup(r, props, ctx);
            default -> throw unsupported(r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case LAUNCH_CONFIGURATION -> autoScalingService.deleteLaunchConfiguration(region, physicalId);
            case GROUP -> autoScalingService.deleteAutoScalingGroup(region, physicalId, true);
            default -> throw unsupported(resourceType);
        }
    }

    private static IllegalStateException unsupported(String resourceType) {
        return new IllegalStateException(
                "AutoScalingGroupCfnProvisioner received an unsupported type: " + resourceType);
    }

    private void provisionLaunchConfiguration(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "LaunchConfigurationName"),
                r.getLogicalId(), 255, false);

        // Launch configurations have no update API on real AWS at all (any property change replaces
        // the resource), so provision() being re-invoked on every UpdateStack means a same-named one
        // already on file must be left alone rather than re-created (createLaunchConfiguration throws
        // AlreadyExists).
        LaunchConfiguration lc = existingEntity(ctx, name, n -> requireLaunchConfiguration(region, n));
        if (lc == null) {
            String associatePublicIp = ctx.resolveOptional(props, "AssociatePublicIpAddress");
            lc = autoScalingService.createLaunchConfiguration(region, name,
                    ctx.resolveOptional(props, "InstanceId"),
                    ctx.resolveOptional(props, "ImageId"),
                    ctx.resolveOptional(props, "InstanceType"),
                    ctx.resolveOptional(props, "KeyName"),
                    ctx.resolveStringList(props, "SecurityGroups"),
                    ctx.resolveOptional(props, "UserData"),
                    ctx.resolveOptional(props, "IamInstanceProfile"),
                    // Absent in the template means the subnet default applies, so
                    // it stays null rather than collapsing to false.
                    associatePublicIp == null || associatePublicIp.isBlank()
                            ? null
                            : Boolean.parseBoolean(associatePublicIp));
            deleteRenamedResource(ctx, name, n -> autoScalingService.deleteLaunchConfiguration(region, n),
                    "launch configuration");
        }
        // Ref returns the launch configuration name.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", lc.getLaunchConfigurationArn());
    }

    private LaunchConfiguration requireLaunchConfiguration(String region, String name) {
        List<LaunchConfiguration> found = autoScalingService.describeLaunchConfigurations(region, List.of(name));
        if (found.isEmpty()) {
            throw new AwsException("ValidationError", "Launch configuration '" + name + "' not found.", 400);
        }
        return found.getFirst();
    }

    private void provisionAutoScalingGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "AutoScalingGroupName"),
                r.getLogicalId(), 255, false);
        String launchConfigName = ctx.resolveOptional(props, "LaunchConfigurationName");
        String launchTemplateId = null;
        String launchTemplateName = null;
        String launchTemplateVersion = null;
        if (props != null && props.has("LaunchTemplate")) {
            JsonNode lt = props.get("LaunchTemplate");
            // Id and name are distinct lookup keys in Auto Scaling: passing an lt- id in the name slot
            // never matches a stored template.
            launchTemplateId = engine.resolve(lt.path("LaunchTemplateId"));
            launchTemplateName = engine.resolve(lt.path("LaunchTemplateName"));
            launchTemplateVersion = engine.resolve(lt.path("Version"));
        }
        MixedInstancesPolicy mixedInstancesPolicy = resolveMixedInstancesPolicy(props, engine);
        int minSize = parseIntProp(props, "MinSize", ctx, 0);
        int maxSize = parseIntProp(props, "MaxSize", ctx, 0);
        int desiredCapacity = parseIntProp(props, "DesiredCapacity", ctx, 0);
        int cooldown = parseIntProp(props, "Cooldown", ctx, 0);
        List<String> availabilityZones = ctx.resolveStringList(props, "AvailabilityZones");
        List<String> subnetIds = ctx.resolveStringList(props, "VPCZoneIdentifier");
        String healthCheckType = ctx.resolveOptional(props, "HealthCheckType");
        int healthCheckGracePeriod = parseIntProp(props, "HealthCheckGracePeriod", ctx, 0);
        List<String> terminationPolicies = ctx.resolveStringList(props, "TerminationPolicies");
        AsgOptionalFields optionalFields = resolveOptionalFields(props, ctx);

        // provision() re-runs on every UpdateStack, so a same-named group already on file must be
        // reconciled via UpdateAutoScalingGroup instead of re-created (createAutoScalingGroup throws
        // AlreadyExists). TargetGroupARNs/LoadBalancerNames/Tags aren't reconciled here: they need
        // their own attach/detach and tagging APIs that updateAutoScalingGroup doesn't cover.
        AutoScalingGroup existing = existingEntity(ctx, name, n -> requireAutoScalingGroup(region, n));
        AutoScalingGroup asg;
        if (existing != null) {
            autoScalingService.updateAutoScalingGroup(region, name,
                    blankToNull(launchConfigName),
                    blankToNull(launchTemplateId), blankToNull(launchTemplateName), blankToNull(launchTemplateVersion),
                    mixedInstancesPolicy, minSize, maxSize, desiredCapacity, cooldown,
                    availabilityZones, subnetIds, healthCheckType, healthCheckGracePeriod, terminationPolicies,
                    optionalFields);
            asg = requireAutoScalingGroup(region, name);
        } else {
            asg = autoScalingService.createAutoScalingGroup(region, name,
                    blankToNull(launchConfigName),
                    blankToNull(launchTemplateId), blankToNull(launchTemplateName), blankToNull(launchTemplateVersion),
                    mixedInstancesPolicy, minSize, maxSize, desiredCapacity, cooldown,
                    availabilityZones, subnetIds,
                    ctx.resolveStringList(props, "TargetGroupARNs"),
                    ctx.resolveStringList(props, "LoadBalancerNames"),
                    healthCheckType, healthCheckGracePeriod, terminationPolicies,
                    resolveAsgTags(props, engine),
                    resolveAsgTagPropagation(props, engine),
                    optionalFields);
            deleteRenamedResource(ctx, name, n -> autoScalingService.deleteAutoScalingGroup(region, n, true),
                    "Auto Scaling group");
        }
        // Ref returns the Auto Scaling group name.
        r.setPhysicalId(name);
        // AutoScalingGroupARN is the schema's only readOnlyProperty, so it is the attribute real
        // CloudFormation resolves. Arn is kept beside it because templates and tests here already
        // read that name, and dropping it would break them for no gain.
        r.getAttributes().put("AutoScalingGroupARN", asg.getAutoScalingGroupArn());
        r.getAttributes().put("Arn", asg.getAutoScalingGroupArn());
    }

    private AutoScalingGroup requireAutoScalingGroup(String region, String name) {
        List<AutoScalingGroup> found = autoScalingService.describeAutoScalingGroups(region, List.of(name));
        if (found.isEmpty()) {
            throw new AwsException("ValidationError", "Auto Scaling group '" + name + "' not found.", 400);
        }
        return found.getFirst();
    }

    /**
     * The four group properties the schema defines that the CloudFormation path used to drop,
     * passing {@code AsgOptionalFields.none()} instead. Absent stays null so an update overwrites
     * only what the template set, which is what {@code AsgOptionalFields.applyToExistingGroup} and
     * the Query handler's {@code UpdateAutoScalingGroup} both do.
     */
    private AsgOptionalFields resolveOptionalFields(JsonNode props, ProvisionContext ctx) {
        String capacityRebalance = ctx.resolveOptional(props, "CapacityRebalance");
        return new AsgOptionalFields(
                blankToNull(ctx.resolveOptional(props, "DesiredCapacityType")),
                capacityRebalance == null || capacityRebalance.isBlank()
                        ? null
                        : Boolean.parseBoolean(capacityRebalance),
                parseOptionalInt("MaxInstanceLifetime", ctx.resolveOptional(props, "MaxInstanceLifetime")),
                parseOptionalInt("DefaultInstanceWarmup", ctx.resolveOptional(props, "DefaultInstanceWarmup")));
    }

    /**
     * Builds the {@code MixedInstancesPolicy} of an Auto Scaling group from template properties, in the
     * same shape the Query API parser produces. Returns {@code null} when the property is absent, so
     * that the group falls back to its {@code LaunchTemplate} or {@code LaunchConfigurationName}.
     */
    private MixedInstancesPolicy resolveMixedInstancesPolicy(JsonNode props,
                                                             CloudFormationTemplateEngine engine) {
        if (props == null || !props.has("MixedInstancesPolicy") || props.get("MixedInstancesPolicy").isNull()) {
            return null;
        }
        JsonNode policyNode = props.get("MixedInstancesPolicy");
        MixedInstancesPolicy policy = new MixedInstancesPolicy();

        JsonNode launchTemplateNode = policyNode.path("LaunchTemplate");
        if (launchTemplateNode.isObject()) {
            MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
            JsonNode specNode = launchTemplateNode.path("LaunchTemplateSpecification");
            if (specNode.isObject()) {
                MixedInstancesPolicy.LaunchTemplateSpecification specification =
                        new MixedInstancesPolicy.LaunchTemplateSpecification();
                specification.setLaunchTemplateId(blankToNull(engine.resolve(specNode.path("LaunchTemplateId"))));
                specification.setLaunchTemplateName(blankToNull(engine.resolve(specNode.path("LaunchTemplateName"))));
                specification.setVersion(blankToNull(engine.resolve(specNode.path("Version"))));
                launchTemplate.setLaunchTemplateSpecification(specification);
            }
            for (JsonNode overrideNode : launchTemplateNode.path("Overrides")) {
                String instanceType = engine.resolve(overrideNode.path("InstanceType"));
                if (instanceType != null && !instanceType.isBlank()) {
                    MixedInstancesPolicy.LaunchTemplateOverride override =
                            new MixedInstancesPolicy.LaunchTemplateOverride();
                    override.setInstanceType(instanceType);
                    launchTemplate.getOverrides().add(override);
                }
            }
            policy.setLaunchTemplate(launchTemplate);
        }

        JsonNode distributionNode = policyNode.path("InstancesDistribution");
        if (distributionNode.isObject()) {
            MixedInstancesPolicy.InstancesDistribution distribution =
                    new MixedInstancesPolicy.InstancesDistribution();
            distribution.setOnDemandBaseCapacity(parseOptionalInt("OnDemandBaseCapacity",
                    engine.resolve(distributionNode.path("OnDemandBaseCapacity"))));
            distribution.setOnDemandPercentageAboveBaseCapacity(
                    parseOptionalInt("OnDemandPercentageAboveBaseCapacity",
                            engine.resolve(distributionNode.path("OnDemandPercentageAboveBaseCapacity"))));
            distribution.setSpotAllocationStrategy(
                    blankToNull(engine.resolve(distributionNode.path("SpotAllocationStrategy"))));
            policy.setInstancesDistribution(distribution);
        }
        return policy;
    }

    /**
     * Reads an optional integer property. A value that is present but not a number is a template
     * error, and AWS rejects it rather than treating it as absent.
     */
    private Integer parseOptionalInt(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "Value of property " + field + " must be an integer.", 400);
        }
    }

    private int parseIntProp(JsonNode props, String name, ProvisionContext ctx, int fallback) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, String> resolveAsgTags(JsonNode props, CloudFormationTemplateEngine engine) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = props != null ? engine.resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = engine.resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, engine.resolve(tag.path("Value")));
                }
            }
        }
        return tags;
    }

    private Map<String, Boolean> resolveAsgTagPropagation(JsonNode props, CloudFormationTemplateEngine engine) {
        Map<String, Boolean> propagation = new LinkedHashMap<>();
        JsonNode tagsNode = props != null ? engine.resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = engine.resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    propagation.put(key, Boolean.parseBoolean(engine.resolve(tag.path("PropagateAtLaunch"))));
                }
            }
        }
        return propagation;
    }

    /**
     * Looks up {@code name} when this is an update re-invocation for the same physical entity,
     * returning {@code null} on a fresh create, on a rename (which the caller handles as a
     * replacement), or when the entity is missing on the backend despite the stack still
     * remembering a physical id, for instance because it was deleted out of band.
     */
    private <T> T existingEntity(ProvisionContext ctx, String name, Function<String, T> lookup) {
        if (!ctx.reusesPriorEntity(name)) {
            return null;
        }
        try {
            return lookup.apply(name);
        } catch (AwsException notFound) {
            // Expected when the resource was deleted out of band since the prior update; the
            // caller falls back to creating it fresh under the same name.
            LOG.debugv(notFound, "No existing {0} found on file, falling back to create", name);
            return null;
        }
    }

    /**
     * Best-effort cleanup of the previous physical entity after a rename forced a fresh create under
     * the new name. Failures are logged, not thrown: the new entity was already created, so
     * surfacing a delete failure here would report the update as failed despite the stack now being
     * in a usable, if slightly leaky, state.
     */
    private void deleteRenamedResource(ProvisionContext ctx, String newName, Consumer<String> delete,
                                       String resourceKind) {
        String priorPhysicalId = ctx.priorPhysicalId();
        if (priorPhysicalId == null || priorPhysicalId.equals(newName)) {
            return;
        }
        try {
            delete.accept(priorPhysicalId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Failed to delete renamed {0} {1} after replacement by {2}",
                    resourceKind, priorPhysicalId, newName);
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
