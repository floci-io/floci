package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrHandler;
import io.floci.aws.services.emr.EmrService;
import io.floci.aws.services.emr.EmrService.InstanceGroupModification;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CloudFormation provisioning for Amazon EMR's own types over {@link EmrService}:
 * {@code AWS::EMR::SecurityConfiguration} and {@code AWS::EMR::Cluster}. No AWS handler for these types is public, so
 * each follows its schema, the template reference and the API it calls.
 *
 * <p>Both of a security configuration's properties are create-only and its schema has no update handler, so any change
 * replaces it: under a new name, generated when the template gives none, and refused, as for every custom-named
 * resource, when the template keeps its own name. Its {@code Ref} is its name, and the {@code SecurityConfiguration}
 * JSON is taken as an object or as the text of one, as the schema allows both.
 *
 * <p>A cluster's {@code Ref} is its id; {@code Fn::GetAtt} gives {@code Id} and {@code MasterPublicDNS}. It is created
 * through RunJobFlow, from the template in RunJobFlow's own members: the master, core and task groups or fleets become
 * {@code InstanceGroups} or {@code InstanceFleets} with their roles, a configuration's {@code ConfigurationProperties}
 * its {@code Properties}, and a step's {@code StepProperties} its {@code Properties}. The create completes once the
 * cluster is waiting or running, as CloudFormation's does, and fails when it terminates first.
 *
 * <p>The reference marks every cluster property but {@code Instances}, {@code AutoTerminationPolicy},
 * {@code ManagedScalingPolicy}, {@code StepConcurrencyLevel}, {@code VisibleToAllUsers} and {@code Tags} as replacing
 * the cluster, and says of its instance groups and fleets that, "After creating the cluster, you can only modify the
 * mutable properties", a group's {@code AutoScalingPolicy} and {@code InstanceCount} and a fleet's
 * {@code TargetOnDemandCapacity} and {@code TargetSpotCapacity}: "Modifying any other property results in cluster
 * replacement". The update handler's permissions add {@code TerminationProtected} and
 * {@code UnhealthyNodeReplacement}, which their own calls change, so any other change to {@code Instances} replaces the
 * cluster too. Those mutable settings are compared with the ones the stack last applied, resolved, so one a parameter
 * changes counts; the ones that changed are applied in place, a dropped one taking its default and a dropped policy
 * being removed, and only the tag keys the stack set are its to change or drop. A later resource's failure, or a
 * failure of the update itself, puts back what it had applied.
 */
@ApplicationScoped
public class EmrCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EmrCfnProvisioner.class);
    private static final String SECURITY_CONFIGURATION = "AWS::EMR::SecurityConfiguration";
    private static final String CLUSTER = "AWS::EMR::Cluster";
    private static final int NAME_MAX_LENGTH = 255;
    /** What an in-place cluster update is about to change, for {@link #rollbackUpdate} to put back. */
    static final String CLUSTER_SNAPSHOT_ATTR = "__FlociEmrClusterUpdateSnapshot";
    /** The tag keys the template that provisioned the cluster set: the next update drops the ones it omits. */
    static final String CLUSTER_TAG_KEYS_ATTR = "__FlociEmrClusterTagKeys";
    /** The resolved {@code Instances} without its mutable settings: a change to it replaces the cluster. */
    static final String CLUSTER_SHAPE_ATTR = "__FlociEmrClusterInstancesShape";
    /** The resolved mutable settings the stack last applied, which an update is compared with. */
    static final String CLUSTER_APPLIED_ATTR = "__FlociEmrClusterAppliedSettings";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CLUSTER_READ_ONLY = List.of("Id", "MasterPublicDNS");
    /** The cluster properties RunJobFlow takes as the template writes them. */
    private static final List<String> RUN_JOB_FLOW_MEMBERS = List.of("Name", "ReleaseLabel", "LogUri", "ServiceRole",
            "JobFlowRole", "AutoScalingRole", "ScaleDownBehavior", "SecurityConfiguration", "CustomAmiId",
            "VisibleToAllUsers", "StepConcurrencyLevel", "EbsRootVolumeSize", "Applications", "BootstrapActions",
            "ManagedScalingPolicy", "AutoTerminationPolicy", "SessionEnabled", "Tags");
    /** The cluster properties Floci's RunJobFlow keeps nothing of: accepted with a warning and not stored. */
    private static final List<String> CLUSTER_NOT_STORED = List.of("AdditionalInfo", "KerberosAttributes",
            "OSReleaseLabel", "EbsRootVolumeIops", "EbsRootVolumeThroughput", "PlacementGroupConfigs",
            "LogEncryptionKmsKeyId", "MonitoringConfiguration");
    /** The members of {@code Instances} Floci's RunJobFlow keeps nothing of, accepted with a warning. */
    private static final List<String> INSTANCES_NOT_STORED = List.of("Ec2SubnetIds", "AdditionalMasterSecurityGroups",
            "AdditionalSlaveSecurityGroups", "HadoopVersion");
    /** The members of an instance group, and of an instance fleet, Floci's RunJobFlow keeps nothing of. */
    private static final List<String> GROUP_NOT_STORED = List.of("EbsConfiguration", "CustomAmiId");
    private static final List<String> FLEET_NOT_STORED = List.of("LaunchSpecifications", "ResizeSpecifications");
    private static final String GROUPS = "groups";
    private static final String FLEETS = "fleets";
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(15);
    private static final Set<String> READY = Set.of("WAITING", "RUNNING");
    private static final Set<String> ENDED = Set.of("TERMINATING", "TERMINATED", "TERMINATED_WITH_ERRORS");

    private final EmrService emrService;
    private final EmrHandler emrHandler;

    @Inject
    public EmrCfnProvisioner(EmrService emrService, EmrHandler emrHandler) {
        this.emrService = emrService;
        this.emrHandler = emrHandler;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SECURITY_CONFIGURATION, CLUSTER);
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
        resource.getAttributes().remove(CLUSTER_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    /**
     * An in-place update whose own undo failed leaves its snapshot on the attempted resource, which the engine discards
     * for the previous one: carried over, it lets ContinueUpdateRollback try the restore again.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        CfnRollback.carryFailedUndoSnapshot(previous, attempted, CLUSTER_SNAPSHOT_ATTR);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CLUSTER_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement or a stub migration is undone through the cleanup record, and an in-place cluster update from the
     * snapshot taken before it, which is spent only once the restore succeeded.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        JsonNode snapshot = CfnRollback.updateSnapshot(resource, CLUSTER_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        Set<String> every = new HashSet<>();
        snapshot.path("settings").fieldNames().forEachRemaining(every::add);
        restoreCluster(snapshot, every, true);
        Ec2Tags.restoreRecord(resource, CLUSTER_TAG_KEYS_ATTR, snapshot.path("tags"));
        // What the stack applied before this update, which the next update is compared with.
        resource.getAttributes().put(CLUSTER_APPLIED_ATTR, snapshot.path("appliedBefore").asText());
        resource.getAttributes().remove(CLUSTER_SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(CLUSTER_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        boolean stub = CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore);
        switch (r.getResourceType()) {
            case SECURITY_CONFIGURATION -> provisionSecurityConfiguration(r, props, ctx, stub);
            case CLUSTER -> provisionCluster(r, props, ctx, stub);
            default -> throw new IllegalStateException("EmrCfnProvisioner cannot provision " + r.getResourceType());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void provisionSecurityConfiguration(StackResource r, JsonNode props, ProvisionContext ctx, boolean stub) {
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(SECURITY_CONFIGURATION, props);
        String explicitName = template.path("Name").textValue();
        if (ctx.isUpdate() && !stub && !ctx.changes(SECURITY_CONFIGURATION, props).requiresReplacement()) {
            r.setPhysicalId(ctx.priorPhysicalId());
            return;
        }
        if (ctx.isUpdate() && !stub && explicitName != null && explicitName.equals(ctx.priorPhysicalId())) {
            throw CfnExceptions.customNamedReplacement(explicitName);
        }
        String name = explicitName != null ? explicitName
                : ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX_LENGTH, false);
        JsonNode configuration = template.path("SecurityConfiguration");
        emrService.createSecurityConfiguration(name,
                configuration.isTextual() ? configuration.textValue() : configuration.toString(), ctx.region());
        r.setPhysicalId(name);
    }

    // ──────────────────────────── Cluster ────────────────────────────

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx, boolean stub) {
        JsonNode template = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(CLUSTER, props);
        for (String readOnly : CLUSTER_READ_ONLY) {
            if (template.has(readOnly)) {
                throw new AwsException("InvalidRequest",
                        readOnly + " is a ReadOnly property which cannot be specified in the template", 400);
            }
        }
        requireUniqueTaskNames(template.path("Instances"));
        Map<String, String> tags = new LinkedHashMap<>();
        template.path("Tags").forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        String shape = instancesShape(template.path("Instances")).toString();
        ObjectNode applied = mutableSettings(template);
        boolean inPlace = ctx.isUpdate() && !stub && !ctx.changes(CLUSTER, props).requiresReplacement()
                && shape.equals(r.getAttributes().get(CLUSTER_SHAPE_ATTR));
        if (inPlace) {
            updateClusterInPlace(r, ctx, applied, tags);
        } else {
            warnNotStored(r.getLogicalId(), template);
            EmrCluster created = emrService.runJobFlow(emrHandler.clusterFrom(runJobFlowRequest(template),
                    ctx.region()), ctx.region());
            r.setPhysicalId(created.getId());
            r.getAttributes().put("Id", created.getId());
            // A cluster that terminates, or never gets ready, before the create completes is terminated again, its
            // termination protection cleared first, as TerminateJobFlows refuses a protected cluster.
            List<String> createdIds = List.of(created.getId());
            CfnRollback.guarded(r, ctx, true, () -> {
                emrService.setTerminationProtection(createdIds, false);
                emrService.terminateJobFlows(createdIds);
            }, () -> { }, () -> publishCluster(r, awaitReady(created.getId())));
            Ec2Tags.recordKeys(r, CLUSTER_TAG_KEYS_ATTR, tags);
        }
        r.getAttributes().put(CLUSTER_SHAPE_ATTR, shape);
        r.getAttributes().put(CLUSTER_APPLIED_ATTR, applied.toString());
    }

    /**
     * The reference's rule for task groups and fleets: "Each task instance group must have a unique name specified so
     * that CloudFormation can differentiate between the task instance groups", and the same of fleets. An update
     * matches them up by it.
     */
    private static void requireUniqueTaskNames(JsonNode instances) {
        for (String member : List.of("TaskInstanceGroups", "TaskInstanceFleets")) {
            Set<String> names = new HashSet<>();
            for (JsonNode task : instances.path(member)) {
                String name = task.path("Name").asText("");
                if (name.isBlank() || !names.add(name)) {
                    String kind = member.endsWith("Groups") ? "group" : "fleet";
                    throw new AwsException("InvalidRequest", "Each task instance " + kind + " must have a unique "
                            + "name specified so that CloudFormation can differentiate between the task instance "
                            + kind + "s.", 400);
                }
            }
        }
    }

    /** Warns of each property, or member of {@code Instances}, its groups or fleets, RunJobFlow keeps nothing of. */
    private static void warnNotStored(String logicalId, JsonNode template) {
        List<String> ignored = new ArrayList<>();
        for (String name : CLUSTER_NOT_STORED) {
            if (template.has(name)) {
                ignored.add(name);
            }
        }
        JsonNode instances = template.path("Instances");
        for (String name : INSTANCES_NOT_STORED) {
            if (instances.has(name)) {
                ignored.add("Instances." + name);
            }
        }
        List<JsonNode> groups = new ArrayList<>(List.of(instances.path("MasterInstanceGroup"),
                instances.path("CoreInstanceGroup")));
        instances.path("TaskInstanceGroups").forEach(groups::add);
        List<JsonNode> fleets = new ArrayList<>(List.of(instances.path("MasterInstanceFleet"),
                instances.path("CoreInstanceFleet")));
        instances.path("TaskInstanceFleets").forEach(fleets::add);
        for (String name : GROUP_NOT_STORED) {
            if (groups.stream().anyMatch(group -> group.has(name))) {
                ignored.add("an instance group's " + name);
            }
        }
        for (String name : FLEET_NOT_STORED) {
            if (fleets.stream().anyMatch(fleet -> fleet.has(name))) {
                ignored.add("an instance fleet's " + name);
            }
        }
        if (!ignored.isEmpty()) {
            LOG.warnv("EMR cluster {0}: {1} accepted and not stored", logicalId, String.join(", ", ignored));
        }
    }

    /** The RunJobFlow request the template describes, in RunJobFlow's own members. */
    private static ObjectNode runJobFlowRequest(JsonNode template) {
        ObjectNode request = MAPPER.createObjectNode();
        for (String name : RUN_JOB_FLOW_MEMBERS) {
            if (template.has(name)) {
                request.set(name, template.get(name));
            }
        }
        if (template.has("Configurations")) {
            request.set("Configurations", configurations(template.get("Configurations")));
        }
        if (template.has("Steps")) {
            ArrayNode steps = request.putArray("Steps");
            template.get("Steps").forEach(step -> {
                ObjectNode copy = step.deepCopy();
                JsonNode jar = copy.path("HadoopJarStep");
                if (jar instanceof ObjectNode hadoop && hadoop.has("StepProperties")) {
                    hadoop.set("Properties", hadoop.remove("StepProperties"));
                }
                steps.add(copy);
            });
        }
        ObjectNode instances = template.get("Instances") instanceof ObjectNode given ? given.deepCopy()
                : MAPPER.createObjectNode();
        ArrayNode groups = MAPPER.createArrayNode();
        ArrayNode fleets = MAPPER.createArrayNode();
        addRole(groups, "InstanceRole", "MASTER", instances.remove("MasterInstanceGroup"));
        addRole(groups, "InstanceRole", "CORE", instances.remove("CoreInstanceGroup"));
        instances.path("TaskInstanceGroups").forEach(group -> addRole(groups, "InstanceRole", "TASK", group));
        instances.remove("TaskInstanceGroups");
        addRole(fleets, "InstanceFleetType", "MASTER", instances.remove("MasterInstanceFleet"));
        addRole(fleets, "InstanceFleetType", "CORE", instances.remove("CoreInstanceFleet"));
        instances.path("TaskInstanceFleets").forEach(fleet -> addRole(fleets, "InstanceFleetType", "TASK", fleet));
        instances.remove("TaskInstanceFleets");
        if (!groups.isEmpty()) {
            instances.set("InstanceGroups", groups);
        }
        if (!fleets.isEmpty()) {
            instances.set("InstanceFleets", fleets);
        }
        request.set("Instances", instances);
        return request;
    }

    private static void addRole(ArrayNode into, String roleMember, String role, JsonNode config) {
        if (config == null || !config.isObject()) {
            return;
        }
        ObjectNode copy = ((ObjectNode) config).deepCopy();
        copy.put(roleMember, role);
        if (copy.has("Configurations")) {
            copy.set("Configurations", configurations(copy.get("Configurations")));
        }
        copy.path("InstanceTypeConfigs").forEach(type -> {
            if (type instanceof ObjectNode typeConfig && typeConfig.has("Configurations")) {
                typeConfig.set("Configurations", configurations(typeConfig.get("Configurations")));
            }
        });
        into.add(copy);
    }

    /** Configurations as the API names them: the template's {@code ConfigurationProperties} are {@code Properties}. */
    private static ArrayNode configurations(JsonNode list) {
        ArrayNode out = MAPPER.createArrayNode();
        list.forEach(configuration -> {
            ObjectNode copy = MAPPER.createObjectNode();
            if (configuration.has("Classification")) {
                copy.set("Classification", configuration.get("Classification"));
            }
            if (configuration.has("ConfigurationProperties")) {
                copy.set("Properties", configuration.get("ConfigurationProperties"));
            }
            if (configuration.has("Configurations")) {
                copy.set("Configurations", configurations(configuration.get("Configurations")));
            }
            out.add(copy);
        });
        return out;
    }

    /**
     * {@code Instances} without what an update can change in place, task groups and fleets keyed by name. The master
     * group's count and the master fleet's capacities stay, as ModifyInstanceGroups and ModifyInstanceFleet refuse to
     * change them, so a change to either replaces the cluster.
     */
    private static JsonNode instancesShape(JsonNode instances) {
        ObjectNode shape = instances.isObject() ? ((ObjectNode) instances).deepCopy() : MAPPER.createObjectNode();
        shape.remove(List.of("TerminationProtected", "UnhealthyNodeReplacement"));
        if (shape.get("CoreInstanceGroup") instanceof ObjectNode core) {
            core.remove(List.of("InstanceCount", "AutoScalingPolicy"));
        }
        if (shape.get("CoreInstanceFleet") instanceof ObjectNode core) {
            core.remove(List.of("TargetOnDemandCapacity", "TargetSpotCapacity"));
        }
        shape.set("TaskInstanceGroups", byName(shape.remove("TaskInstanceGroups"), "InstanceCount",
                "AutoScalingPolicy"));
        shape.set("TaskInstanceFleets", byName(shape.remove("TaskInstanceFleets"), "TargetOnDemandCapacity",
                "TargetSpotCapacity"));
        return shape;
    }

    private static ObjectNode byName(JsonNode list, String... mutable) {
        ObjectNode byName = MAPPER.createObjectNode();
        if (list != null) {
            list.forEach(config -> {
                ObjectNode copy = ((ObjectNode) config).deepCopy();
                copy.remove(List.of(mutable));
                byName.set(config.path("Name").asText(), copy);
            });
        }
        return byName;
    }

    /**
     * The mutable settings the template gives, resolved, a dropped one at its default: protection and node replacement
     * off, visible to all users, one step at a time, and no scaling or termination policy.
     */
    private static ObjectNode mutableSettings(JsonNode template) {
        JsonNode instances = template.path("Instances");
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("TerminationProtected", instances.path("TerminationProtected").asBoolean(false));
        settings.put("UnhealthyNodeReplacement", instances.path("UnhealthyNodeReplacement").asBoolean(false));
        settings.put("VisibleToAllUsers", template.path("VisibleToAllUsers").asBoolean(true));
        settings.put("StepConcurrencyLevel", template.path("StepConcurrencyLevel").asInt(1));
        settings.set("ManagedScalingPolicy", template.get("ManagedScalingPolicy"));
        settings.set("AutoTerminationPolicy", template.get("AutoTerminationPolicy"));
        ObjectNode groups = settings.putObject(GROUPS);
        putGroup(groups, "CORE", instances.get("CoreInstanceGroup"));
        instances.path("TaskInstanceGroups").forEach(group -> putGroup(groups, "TASK:" + group.path("Name").asText(),
                group));
        ObjectNode fleets = settings.putObject(FLEETS);
        putFleet(fleets, "CORE", instances.get("CoreInstanceFleet"));
        instances.path("TaskInstanceFleets").forEach(fleet -> putFleet(fleets, "TASK:" + fleet.path("Name").asText(),
                fleet));
        return settings;
    }

    private static void putGroup(ObjectNode groups, String key, JsonNode config) {
        if (config != null && config.isObject()) {
            ObjectNode group = groups.putObject(key);
            group.put("InstanceCount", config.path("InstanceCount").asInt());
            group.set("AutoScalingPolicy", config.get("AutoScalingPolicy"));
        }
    }

    private static void putFleet(ObjectNode fleets, String key, JsonNode config) {
        if (config != null && config.isObject()) {
            ObjectNode fleet = fleets.putObject(key);
            fleet.put("TargetOnDemandCapacity", config.path("TargetOnDemandCapacity").asInt(0));
            fleet.put("TargetSpotCapacity", config.path("TargetSpotCapacity").asInt(0));
        }
    }

    /**
     * The in-place update of the cluster the prior physical id names, which fails when it has terminated or is gone.
     * The settings that differ from the ones the stack last applied are snapshotted at those values, and the owned tags
     * at the cluster's, so a later resource's failure puts them back; a failure here puts back the ones it had applied.
     */
    private void updateClusterInPlace(StackResource r, ProvisionContext ctx, ObjectNode desired,
                                      Map<String, String> tags) {
        String id = ctx.priorPhysicalId();
        EmrCluster live = liveCluster(id);
        JsonNode last = CfnRollback.updateSnapshot(r, CLUSTER_APPLIED_ATTR);
        ObjectNode prior = MAPPER.createObjectNode();
        Map<String, JsonNode> changed = changedSettings(last == null ? desired : last, desired, prior);
        Set<String> owned = Ec2Tags.ownedKeys(r, CLUSTER_TAG_KEYS_ATTR, ctx);
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("clusterId", id);
        snapshot.put("region", ctx.region());
        snapshot.set("settings", prior);
        snapshot.put("appliedBefore", last == null ? desired.toString() : last.toString());
        Ec2Tags.snapshotOwned(snapshot.putObject("tags"), owned, tags,
                live.getTags() == null ? Map.of() : live.getTags(), r.getAttributes().get(CLUSTER_TAG_KEYS_ATTR));
        publishCluster(r, live);
        r.getAttributes().put(CLUSTER_SNAPSHOT_ATTR, snapshot.toString());
        Set<String> applied = new HashSet<>();
        AtomicBoolean tagsTouched = new AtomicBoolean();
        CfnRollback.guarded(r, ctx, false, () -> { },
                () -> restoreCluster(snapshot, applied, tagsTouched.get()), () -> {
                    for (Map.Entry<String, JsonNode> setting : changed.entrySet()) {
                        apply(id, ctx.region(), setting.getKey(), setting.getValue());
                        applied.add(setting.getKey());
                    }
                    tagsTouched.set(true);
                    reconcileTags(id, Ec2Tags.touched(owned, tags), tags);
                    Ec2Tags.recordKeys(r, CLUSTER_TAG_KEYS_ATTR, tags);
                });
    }

    /**
     * The settings whose desired value differs from the last applied one, keyed as {@link #apply} takes them, with the
     * last applied value of each put in {@code prior}. A group or fleet the update names but the last did not is a
     * change to {@code Instances}' shape, which replaces the cluster, so it is not met here.
     */
    private static Map<String, JsonNode> changedSettings(JsonNode last, JsonNode desired, ObjectNode prior) {
        Map<String, JsonNode> changed = new LinkedHashMap<>();
        for (String name : List.of("TerminationProtected", "UnhealthyNodeReplacement", "VisibleToAllUsers",
                "StepConcurrencyLevel", "ManagedScalingPolicy", "AutoTerminationPolicy")) {
            compare(changed, prior, name, last.get(name), desired.get(name));
        }
        desired.path(GROUPS).properties().forEach(group -> {
            JsonNode before = last.path(GROUPS).path(group.getKey());
            compare(changed, prior, "group:" + group.getKey() + ":InstanceCount", before.get("InstanceCount"),
                    group.getValue().get("InstanceCount"));
            compare(changed, prior, "group:" + group.getKey() + ":AutoScalingPolicy", before.get("AutoScalingPolicy"),
                    group.getValue().get("AutoScalingPolicy"));
        });
        desired.path(FLEETS).properties().forEach(fleet -> compare(changed, prior, "fleet:" + fleet.getKey(),
                last.path(FLEETS).get(fleet.getKey()), fleet.getValue()));
        return changed;
    }

    private static void compare(Map<String, JsonNode> changed, ObjectNode prior, String key, JsonNode before,
                                JsonNode after) {
        JsonNode was = before == null || before.isNull() ? null : before;
        JsonNode now = after == null || after.isNull() ? null : after;
        if (!Objects.equals(was, now)) {
            changed.put(key, now);
            prior.set(key, was);
        }
    }

    /** Applies one setting, keyed as {@link #changedSettings} gives it, to the cluster; null removes a policy. */
    private void apply(String clusterId, String region, String key, JsonNode value) {
        List<String> ids = List.of(clusterId);
        switch (key) {
            case "TerminationProtected" -> emrService.setTerminationProtection(ids, value.asBoolean());
            case "UnhealthyNodeReplacement" -> emrService.setUnhealthyNodeReplacement(ids, value.asBoolean());
            case "VisibleToAllUsers" -> emrService.setVisibleToAllUsers(ids, value.asBoolean());
            case "StepConcurrencyLevel" -> emrService.modifyCluster(clusterId, value.asInt());
            case "ManagedScalingPolicy" -> {
                if (value == null) {
                    emrService.removeManagedScalingPolicy(clusterId);
                } else {
                    emrService.putManagedScalingPolicy(clusterId, value);
                }
            }
            case "AutoTerminationPolicy" -> {
                if (value == null) {
                    emrService.removeAutoTerminationPolicy(clusterId);
                } else {
                    emrService.putAutoTerminationPolicy(clusterId, value);
                }
            }
            default -> applyInstanceSetting(clusterId, region, key, value);
        }
    }

    private void applyInstanceSetting(String clusterId, String region, String key, JsonNode value) {
        if (key.startsWith("fleet:")) {
            EmrInstanceFleet fleet = fleet(clusterId, key.substring("fleet:".length()));
            emrService.modifyInstanceFleet(clusterId, fleet.getId(), value.path("TargetOnDemandCapacity").asInt(),
                    value.path("TargetSpotCapacity").asInt());
            return;
        }
        String[] parts = key.split(":", 2);
        String member = parts[1].substring(parts[1].lastIndexOf(':') + 1);
        EmrInstanceGroup group = group(clusterId, parts[1].substring(0, parts[1].lastIndexOf(':')));
        if ("InstanceCount".equals(member)) {
            emrService.modifyInstanceGroups(clusterId, region,
                    List.of(new InstanceGroupModification(group.getId(), value.asInt(), null)));
        } else if (value == null) {
            emrService.removeAutoScalingPolicy(clusterId, group.getId());
        } else {
            emrService.putAutoScalingPolicy(clusterId, group.getId(), value);
        }
    }

    /** The cluster's group a settings key names: {@code CORE}, or {@code TASK:} and its name. */
    private EmrInstanceGroup group(String clusterId, String key) {
        return emrService.listInstanceGroups(clusterId).stream().filter(group -> key.startsWith("TASK:")
                        ? "TASK".equals(group.getInstanceGroupType()) && key.substring(5).equals(group.getName())
                        : key.equals(group.getInstanceGroupType()))
                .findFirst().orElseThrow(() -> new AwsException("NotFound",
                        "Cluster " + clusterId + " has no instance group " + key + ".", 404));
    }

    private EmrInstanceFleet fleet(String clusterId, String key) {
        return emrService.listInstanceFleets(clusterId).stream().filter(fleet -> key.startsWith("TASK:")
                        ? "TASK".equals(fleet.getInstanceFleetType()) && key.substring(5).equals(fleet.getName())
                        : key.equals(fleet.getInstanceFleetType()))
                .findFirst().orElseThrow(() -> new AwsException("NotFound",
                        "Cluster " + clusterId + " has no instance fleet " + key + ".", 404));
    }

    /** Puts back the settings named in {@code keys} at their snapshotted values, and the tags once touched. */
    private void restoreCluster(JsonNode snapshot, Set<String> keys, boolean tagsTouched) {
        String id = snapshot.path("clusterId").asText();
        String region = snapshot.path("region").asText();
        snapshot.path("settings").properties().forEach(setting -> {
            if (keys.contains(setting.getKey())) {
                apply(id, region, setting.getKey(), setting.getValue().isNull() ? null : setting.getValue());
            }
        });
        if (tagsTouched) {
            JsonNode tags = snapshot.path("tags");
            List<String> touched = new ArrayList<>();
            tags.path("touched").forEach(key -> touched.add(key.asText()));
            reconcileTags(id, touched, CfnRollback.snapshotTags(tags));
        }
    }

    private void reconcileTags(String clusterId, Collection<String> managedKeys, Map<String, String> desired) {
        CfnRollback.reconcileTags(managedKeys, desired, dropped -> emrService.removeTags(clusterId, dropped),
                added -> emrService.addTags(clusterId, added));
    }

    /** The cluster {@code id}, failing as an update of one that has terminated, or is gone, fails. */
    private EmrCluster liveCluster(String id) {
        AwsException notFound = new AwsException("NotFound",
                "Resource of type '" + CLUSTER + "' with identifier '" + id + "' was not found.", 404);
        EmrCluster cluster;
        try {
            cluster = emrService.describeCluster(id);
        } catch (AwsException e) {
            if ("InvalidRequestException".equals(e.getErrorCode())) {
                throw notFound;
            }
            throw e;
        }
        if (ENDED.contains(cluster.getState())) {
            throw notFound;
        }
        return cluster;
    }

    /**
     * The cluster once it is waiting or running, or has ended because it ran its steps and was not kept alive without
     * them, which is a cluster that came up; one that terminates otherwise, or never gets there, fails the create.
     */
    private EmrCluster awaitReady(String id) {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (true) {
            EmrCluster cluster = emrService.describeCluster(id);
            if (READY.contains(cluster.getState()) || ("TERMINATED".equals(cluster.getState())
                    && "ALL_STEPS_COMPLETED".equals(cluster.getStateChangeReasonCode()))) {
                return cluster;
            }
            if (ENDED.contains(cluster.getState())) {
                throw new AwsException("NotStabilized", "Resource of type '" + CLUSTER + "' with identifier '" + id
                        + "' did not stabilize: it is " + cluster.getState() + ".", 400);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AwsException("NotStabilized", "Resource of type '" + CLUSTER + "' with identifier '" + id
                        + "' did not stabilize: it is still " + cluster.getState() + ".", 400);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for EMR cluster " + id, e);
            }
        }
    }

    private static void publishCluster(StackResource r, EmrCluster cluster) {
        r.setPhysicalId(cluster.getId());
        r.getAttributes().put("Id", cluster.getId());
        if (cluster.getMasterPublicDnsName() != null) {
            r.getAttributes().put("MasterPublicDNS", cluster.getMasterPublicDnsName());
        }
    }

    /**
     * Deletes the security configuration, unless the Region lists none of that name, it being gone, or terminates the
     * cluster, which TerminateJobFlows leaves be when it has terminated or is gone, and refuses when it is termination
     * protected.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case SECURITY_CONFIGURATION -> {
                if (emrService.listSecurityConfigurations(region).stream()
                        .anyMatch(sc -> physicalId.equals(sc.getName()))) {
                    emrService.deleteSecurityConfiguration(physicalId, region);
                }
            }
            case CLUSTER -> emrService.terminateJobFlows(List.of(physicalId));
            default -> throw new IllegalStateException("EmrCfnProvisioner cannot delete " + resourceType);
        }
    }
}
