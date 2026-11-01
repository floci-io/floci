package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationService;
import io.github.hectorvent.floci.services.cloudformation.StackSetService;
import io.github.hectorvent.floci.services.cloudformation.model.StackInstance;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
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
 * of targets it drops; deleting the resource deletes every instance and then the stack set.
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
            createInstances(stackSets, name, groups);
            removeDroppedTargets(stackSets, stackSet, groups);
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

    private static void removeDroppedTargets(StackSetService stackSets, StackSet stackSet, List<InstanceGroup> groups) {
        String name = stackSet.getStackSetName();
        List<StackInstance> instances = stackSets.listStackInstances(name, null, null);
        if (instances.isEmpty()) {
            return;
        }
        List<String> regions = instances.stream().map(StackInstance::getRegion).distinct().toList();
        if (SERVICE_MANAGED.equals(stackSet.getPermissionModel())) {
            Set<String> dropped = new LinkedHashSet<>(stackSets.organizationalUnitIds(name));
            groups.forEach(group -> group.organizationalUnits().forEach(dropped::remove));
            if (!dropped.isEmpty()) {
                requireSuccess(stackSets.deleteStackInstances(name, List.of(), regions, List.copyOf(dropped), false));
            }
            return;
        }
        Set<String> dropped = new LinkedHashSet<>();
        instances.forEach(instance -> dropped.add(instance.getAccount()));
        groups.forEach(group -> group.accounts().forEach(dropped::remove));
        if (!dropped.isEmpty()) {
            requireSuccess(stackSets.deleteStackInstances(name, List.copyOf(dropped), regions, List.of(), false));
        }
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
