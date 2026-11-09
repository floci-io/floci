package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationService;
import io.floci.aws.services.cloudformation.StackSetService;
import io.floci.aws.services.cloudformation.model.StackInstance;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.model.StackSet;
import io.floci.aws.services.cloudformation.model.StackSetAutoDeploymentTarget;
import io.floci.aws.services.cloudformation.model.StackSetOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::CloudFormation::StackSet}: the stack set, then a stack instance in every account and
 * Region its {@code StackInstancesGroup} names, through {@link StackSetService} as the StackSets API does.
 * {@code Ref} and {@code Fn::GetAtt StackSetId} return the stack set's id. An update applies the template,
 * parameters and settings to every instance, deploys to targets the template adds and removes the instances
 * of the targets and Regions it drops; deleting the resource deletes every instance and then the stack set.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-cloudformation-stackset.html">AWS::CloudFormation::StackSet</a>
 */
@ApplicationScoped
public class StackSetCfnProvisioner implements CfnResourceProvisioner {

    private static final String STACK_SET = "AWS::CloudFormation::StackSet";
    private static final String SERVICE_MANAGED = "SERVICE_MANAGED";

    private final Instance<StackSetService> stackSetService;
    private final Instance<CloudFormationService> cfnService;

    @Inject
    public StackSetCfnProvisioner(Instance<StackSetService> stackSetService, Instance<CloudFormationService> cfnService) {
        this.stackSetService = stackSetService;
        this.cfnService = cfnService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(STACK_SET);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        StackSetService stackSets = stackSetService.get();
        String templateBody = cfnService.get().resolveTemplateBody(
                ctx.resolveOptional(props, "TemplateBody"), ctx.resolveOptional(props, "TemplateURL"));
        Map<String, String> parameters = resolveParameters(props, ctx);
        List<String> capabilities = ctx.resolveStringList(props, "Capabilities");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String description = ctx.resolveOptional(props, "Description");
        JsonNode autoDeployment = props.path("AutoDeployment");
        List<InstanceGroup> groups = resolveGroups(props, ctx);

        if (ctx.isUpdate()) {
            String name = stackSetName(ctx.priorPhysicalId());
            requireSuccess(stackSets.updateStackSet(name, templateBody, parameters, capabilities, tags, description,
                    null, optionalBoolean(autoDeployment, "Enabled", ctx),
                    optionalBoolean(autoDeployment, "RetainStacksOnAccountRemoval", ctx),
                    optionalBoolean(props.path("ManagedExecution"), "Active", ctx)));
            StackSet stackSet = stackSets.describeStackSet(name);
            boolean serviceManaged = SERVICE_MANAGED.equals(stackSet.getPermissionModel());
            Map<String, Set<String>> deployed = deployedTargets(stackSets, name, serviceManaged);
            createInstances(stackSets, name, groups);
            removeDroppedTargets(stackSets, name, serviceManaged, deployed, groups);
            setIdentity(r, stackSet);
            return;
        }

        String name = ctx.resolveOptional(props, "StackSetName");
        StackSet stackSet = stackSets.createStackSet(name, templateBody, parameters, capabilities, tags, description,
                ctx.resolveOptional(props, "PermissionModel"),
                Boolean.TRUE.equals(optionalBoolean(autoDeployment, "Enabled", ctx)),
                Boolean.TRUE.equals(optionalBoolean(autoDeployment, "RetainStacksOnAccountRemoval", ctx)),
                Boolean.TRUE.equals(optionalBoolean(props.path("ManagedExecution"), "Active", ctx)));
        setIdentity(r, stackSet);
        CfnRollback.guarded(r, ctx, true, () -> stackSets.deleteStackSetWithInstances(name), () -> { },
                () -> createInstances(stackSets, name, groups));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        stackSetService.get().deleteStackSetWithInstances(stackSetName(physicalId));
    }

    /** One {@code StackInstancesGroup} entry: the accounts or the root and OUs it targets, and its Regions. */
    private record InstanceGroup(List<String> accounts, List<String> organizationalUnits, List<String> regions) {
    }

    private static void setIdentity(StackResource r, StackSet stackSet) {
        r.setPhysicalId(stackSet.getStackSetId());
        r.getAttributes().put("StackSetId", stackSet.getStackSetId());
    }

    private static void createInstances(StackSetService stackSets, String name, List<InstanceGroup> groups) {
        for (InstanceGroup group : groups) {
            requireSuccess(stackSets.createStackInstances(name, group.accounts(), group.regions(),
                    group.organizationalUnits(), null));
        }
    }

    /**
     * The targets in place and their Regions, read before an update deploys anything: the targets recorded for a
     * service-managed stack set, which also covers a target that has no instance, and the instances of a
     * self-managed one.
     */
    private static Map<String, Set<String>> deployedTargets(StackSetService stackSets, String name,
                                                            boolean serviceManaged) {
        Map<String, Set<String>> deployed = new LinkedHashMap<>();
        if (serviceManaged) {
            for (StackSetAutoDeploymentTarget target : stackSets.listStackSetAutoDeploymentTargets(name)) {
                deployed.computeIfAbsent(target.getOrganizationalUnitId(), ignored -> new LinkedHashSet<>())
                        .addAll(target.getRegions());
            }
        } else {
            for (StackInstance instance : stackSets.listStackInstances(name, null, null)) {
                deployed.computeIfAbsent(instance.getAccount(), ignored -> new LinkedHashSet<>()).add(instance.getRegion());
            }
        }
        return deployed;
    }

    /**
     * Deletes the instances of every target and Region the template no longer names: a target it dropped, or a
     * Region it dropped from a target it kept.
     */
    private static void removeDroppedTargets(StackSetService stackSets, String name, boolean serviceManaged,
                                             Map<String, Set<String>> deployed, List<InstanceGroup> groups) {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (InstanceGroup group : groups) {
            for (String target : serviceManaged ? group.organizationalUnits() : group.accounts()) {
                wanted.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).addAll(group.regions());
            }
        }
        deployed.forEach((target, regions) -> {
            Set<String> dropped = new LinkedHashSet<>(regions);
            dropped.removeAll(wanted.getOrDefault(target, Set.of()));
            if (dropped.isEmpty()) {
                return;
            }
            requireSuccess(serviceManaged
                    ? stackSets.deleteStackInstances(name, List.of(), List.copyOf(dropped), List.of(target), false)
                    : stackSets.deleteStackInstances(name, List.of(target), List.copyOf(dropped), List.of(), false));
        });
    }

    private static void requireSuccess(StackSetOperation operation) {
        if ("FAILED".equals(operation.getStatus())) {
            throw new AwsException("ValidationError", "Stack set operation " + operation.getOperationId()
                    + " was unexpectedly stopped or failed", 400);
        }
    }

    private static List<InstanceGroup> resolveGroups(JsonNode props, ProvisionContext ctx) {
        List<InstanceGroup> groups = new ArrayList<>();
        JsonNode group = props.path("StackInstancesGroup");
        JsonNode entries = group.isMissingNode() || group.isNull() ? null : ctx.engine().resolveNode(group);
        if (entries == null || !entries.isArray()) {
            return groups;
        }
        for (JsonNode entry : entries) {
            JsonNode targets = entry.path("DeploymentTargets");
            groups.add(new InstanceGroup(ctx.resolveStringList(targets, "Accounts"),
                    ctx.resolveStringList(targets, "OrganizationalUnitIds"),
                    ctx.resolveStringList(entry, "Regions")));
        }
        return groups;
    }

    private static Map<String, String> resolveParameters(JsonNode props, ProvisionContext ctx) {
        Map<String, String> parameters = new LinkedHashMap<>();
        JsonNode declared = props.path("Parameters");
        JsonNode entries = declared.isMissingNode() || declared.isNull() ? null : ctx.engine().resolveNode(declared);
        if (entries == null || !entries.isArray()) {
            return parameters;
        }
        for (JsonNode entry : entries) {
            String key = ctx.engine().resolve(entry.path("ParameterKey"));
            if (key != null && !key.isBlank()) {
                String value = ctx.engine().resolve(entry.path("ParameterValue"));
                parameters.put(key, value == null ? "" : value);
            }
        }
        return parameters;
    }

    private static Boolean optionalBoolean(JsonNode node, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(node.isObject() ? node : null, name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value);
    }

    /** A stack set id is {@code <name>:<uuid>}; the name is what the StackSets API addresses it by. */
    private static String stackSetName(String stackSetId) {
        int separator = stackSetId.indexOf(':');
        return separator < 0 ? stackSetId : stackSetId.substring(0, separator);
    }
}
