package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CreateOnlyInputs CREATE_ONLY_INPUTS =
            new CreateOnlyInputs("__FlociStackSetInputs", "__FlociStackSetPriorInputs");

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
        String permissionModel = ctx.resolveOptional(props, "PermissionModel");
        if (permissionModel == null || permissionModel.isBlank()) {
            throw new AwsException("ValidationError", "PermissionModel is required for AWS::CloudFormation::StackSet", 400);
        }
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
            if (requiresUpdateRecovery(r)) {
                rollbackUpdate(r);
            }
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "StackSetName");
        String inputs = createOnlyInputs(explicitName, permissionModel);
        Runnable create = () -> {
            String name = explicitName == null ? ctx.generatePhysicalName(r.getLogicalId(), 128, false) : explicitName;
            StackSet stackSet = stackSets.createStackSet(name, templateBody, parameters, capabilities, tags, description,
                    permissionModel,
                    Boolean.TRUE.equals(optionalBoolean(autoDeployment, "Enabled", ctx)),
                    Boolean.TRUE.equals(optionalBoolean(autoDeployment, "RetainStacksOnAccountRemoval", ctx)),
                    Boolean.TRUE.equals(optionalBoolean(props.path("ManagedExecution"), "Active", ctx)));
            setIdentity(r, stackSet);
            CREATE_ONLY_INPUTS.record(r, ctx, attributesBefore, inputs);
            ReplacementCleanup.record(r, ctx, attributesBefore);
            CfnRollback.guarded(r, ctx, true, () -> delete(STACK_SET, stackSet.getStackSetId(), ctx.region()), () -> { },
                    () -> createInstances(stackSets, name, groups));
        };
        boolean updating = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        if (updating) {
            boolean updated = stackSets.mutateStackSetResource(ctx.priorPhysicalId(), stackSet -> {
                String name = stackSet.getStackSetName();
                String priorInputs = CREATE_ONLY_INPUTS.read(attributesBefore);
                if (priorInputs == null) {
                    priorInputs = createOnlyInputs(ctx.previouslyDeclared("StackSetName") ? name : null,
                            stackSet.getPermissionModel());
                }
                if (!inputs.equals(priorInputs)) {
                    try {
                        if (name.equals(explicitName)) {
                            throw CfnExceptions.customNamedReplacement(name);
                        }
                        create.run();
                    } catch (RuntimeException e) {
                        if (ctx.priorPhysicalId().equals(r.getPhysicalId())) {
                            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                        }
                        throw e;
                    }
                    return;
                }
                boolean serviceManaged = SERVICE_MANAGED.equals(stackSet.getPermissionModel());
                Map<String, Set<String>> deployed = deployedTargets(stackSets, name, serviceManaged);
                ObjectNode snapshot = MAPPER.createObjectNode().put("account", ctx.accountId()).put("region", ctx.region());
                snapshot.set("stackSet", MAPPER.valueToTree(stackSet));
                snapshot.set("targets", MAPPER.valueToTree(deployed));
                r.getAttributes().put(CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
                requireSuccess(stackSets.updateStackSetResource(name, templateBody, parameters, capabilities, tags, description,
                        optionalBoolean(autoDeployment, "Enabled", ctx),
                        optionalBoolean(autoDeployment, "RetainStacksOnAccountRemoval", ctx),
                        optionalBoolean(props.path("ManagedExecution"), "Active", ctx)));
                createInstances(stackSets, name, groups);
                removeDroppedTargets(stackSets, name, serviceManaged, deployed, groups);
                setIdentity(r, stackSet);
                CREATE_ONLY_INPUTS.record(r, ctx, attributesBefore, inputs);
                ReplacementCleanup.record(r, ctx, attributesBefore);
            });
            if (!updated) {
                throw new AwsException("StackSetNotFoundException", "The original StackSet no longer exists", 404);
            }
            return;
        }
        create.run();
    }

    private static String createOnlyInputs(String name, String permissionModel) {
        return MAPPER.createObjectNode().put("name", name).put("permissionModel", permissionModel).toString();
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
        resource.getAttributes().remove(CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR);
        CREATE_ONLY_INPUTS.clearPrior(resource);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR);
        CREATE_ONLY_INPUTS.clearPrior(resource);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        ReplacementCleanup.mergeDisplaced(previous, attempted);
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return requiresUpdateRecovery(resource);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String attemptedId = resource.getPhysicalId();
        boolean hasPriorInputs = CREATE_ONLY_INPUTS.hasPrior(resource);
        try {
            JsonNode snapshot = CfnRollback.updateSnapshot(resource, CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR);
            if (snapshot != null) {
                restoreUpdateSnapshot(snapshot);
                resource.getAttributes().remove(CfnRollback.STACK_SET_UPDATE_SNAPSHOT_ATTR);
                ReplacementCleanup.rollback(resource, this::delete);
                return true;
            }
            return ReplacementCleanup.rollback(resource, this::delete);
        } finally {
            if (hasPriorInputs && !Objects.equals(attemptedId, resource.getPhysicalId())) {
                CREATE_ONLY_INPUTS.restorePrior(resource);
            }
        }
    }

    private void restoreUpdateSnapshot(JsonNode snapshot) {
        StackSet prior = MAPPER.convertValue(snapshot.path("stackSet"), StackSet.class);
        boolean serviceManaged = SERVICE_MANAGED.equals(prior.getPermissionModel());
        List<InstanceGroup> groups = new ArrayList<>();
        snapshot.path("targets").properties().forEach(target -> {
            List<String> regions = new ArrayList<>();
            target.getValue().forEach(region -> regions.add(region.asText()));
            List<String> accounts = serviceManaged ? List.of() : List.of(target.getKey());
            List<String> organizationalUnits = serviceManaged ? List.of(target.getKey()) : List.of();
            groups.add(new InstanceGroup(accounts, organizationalUnits, regions));
        });
        boolean restored = RequestScopes.callAs(snapshot.path("account").asText(), snapshot.path("region").asText(),
                () -> {
                    StackSetService stackSets = stackSetService.get();
                    return stackSets.mutateStackSetResource(prior.getStackSetId(), current -> {
                        String name = prior.getStackSetName();
                        removeDroppedTargets(stackSets, name, serviceManaged,
                                deployedTargets(stackSets, name, serviceManaged), groups);
                        requireSuccess(stackSets.updateStackSetResource(name, prior.getTemplateBody(),
                                prior.getParameters(), prior.getCapabilities(), prior.getTags(), prior.getDescription(),
                                serviceManaged ? prior.isAutoDeploymentEnabled() : null,
                                prior.isRetainStacksOnAccountRemoval(), prior.isManagedExecutionActive()));
                        createInstances(stackSets, name, groups);
                    });
                });
        if (!restored) {
            throw new AwsException("StackSetNotFoundException", "The original StackSet no longer exists", 404);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        StackSetService stackSets = stackSetService.get();
        stackSets.mutateStackSetResource(physicalId,
                stackSet -> stackSets.deleteStackSetWithInstances(stackSet.getStackSetName()));
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

}
