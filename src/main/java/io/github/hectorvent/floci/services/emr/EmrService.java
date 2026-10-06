package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emr.model.EmrBlockPublicAccess;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emr.model.SecurityConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EMR management-plane business logic. Cluster and step lifecycles are simulated
 * through immediate state transitions (no Hadoop runs): clusters land in WAITING and
 * initial steps in COMPLETED. The {@code clusterStartupDelaySeconds} config exists for
 * future deferred transitions; Phase 1 advances synchronously for deterministic tests.
 */
@ApplicationScoped
public class EmrService {

    private static final String UPPER_ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, EmrCluster> clusterStore;
    private final StorageBackend<String, SecurityConfiguration> secConfigStore;
    // Block public access is an account setting per Region, so this store is keyed by Region.
    private final StorageBackend<String, EmrBlockPublicAccess> blockPublicAccessStore;
    private final RegionResolver regionResolver;

    private static final Set<String> COMPUTE_LIMITS_UNIT_TYPES = Set.of("InstanceFleetUnits", "Instances", "VCPU");
    private static final Set<String> SCALING_STRATEGIES = Set.of("DEFAULT", "ADVANCED");
    private static final long MIN_IDLE_TIMEOUT_SECONDS = 60;
    private static final long MAX_IDLE_TIMEOUT_SECONDS = 604_800;
    private static final int MAX_PORT = 65_535;
    // The AWS default: block public access enabled in every Region, with port 22 as an exception.
    private static final String DEFAULT_BLOCK_PUBLIC_ACCESS =
            "{\"BlockPublicSecurityGroupRules\":true,"
                    + "\"PermittedPublicSecurityGroupRuleRanges\":[{\"MinRange\":22,\"MaxRange\":22}]}";
    private final String defaultReleaseLabel;

    @Inject
    public EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.clusterStore = storageFactory.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        this.secConfigStore = storageFactory.create("emr", "emr-security-configs.json",
                new TypeReference<Map<String, SecurityConfiguration>>() {});
        this.blockPublicAccessStore = storageFactory.create("emr", "emr-block-public-access.json",
                new TypeReference<Map<String, EmrBlockPublicAccess>>() {});
        this.regionResolver = regionResolver;
        this.defaultReleaseLabel = config.services().emr().defaultReleaseLabel();
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    public synchronized EmrCluster runJobFlow(EmrCluster cluster, String region) {
        // Only a missing Name fails AWS's framework validation: the member is REQUIRED but its
        // constraint is len=[0..256], so an explicit empty string is accepted by real EMR.
        if (cluster.getName() == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'name' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        String id = "j-" + randomId(13);
        cluster.setId(id);
        cluster.setRegion(region);
        cluster.setClusterArn(regionResolver.buildArn("elasticmapreduce", region, "cluster/" + id));
        if (cluster.getReleaseLabel() == null) {
            cluster.setReleaseLabel(defaultReleaseLabel);
        }
        cluster.setInstanceCollectionType(cluster.getInstanceFleets().isEmpty()
                ? "INSTANCE_GROUP" : "INSTANCE_FLEET");
        cluster.setAutoTerminate(!cluster.isKeepJobFlowAliveWhenNoSteps());
        cluster.setMasterPublicDnsName(masterDnsName(region));
        cluster.setCreationDateTime(Instant.now());
        for (EmrInstanceGroup g : cluster.getInstanceGroups()) {
            g.setId("ig-" + randomId(13));
            g.setRunningInstanceCount(g.getRequestedInstanceCount());
            g.setState("RUNNING");
        }
        for (EmrInstanceFleet f : cluster.getInstanceFleets()) {
            f.setId("if-" + randomId(13));
            f.setProvisionedOnDemandCapacity(f.getTargetOnDemandCapacity());
            f.setProvisionedSpotCapacity(f.getTargetSpotCapacity());
            f.setState("RUNNING");
        }
        for (EmrStep step : cluster.getSteps()) {
            initStep(step);
            completeStep(step);
        }
        advanceToWaiting(cluster);
        clusterStore.put(id, cluster);
        return cluster;
    }

    /** The master node's private DNS name, derived from the region so a stored name never goes stale. */
    static String masterDnsName(String region) {
        return AwsRegions.ec2PrivateIpDnsName("10.0.0.1", region);
    }

    public EmrCluster describeCluster(String id) {
        return requireCluster(id);
    }

    public List<EmrCluster> listClusters(String region, List<String> states) {
        List<EmrCluster> result = new ArrayList<>();
        for (EmrCluster c : clusterStore.scan(k -> true)) {
            if (!region.equals(c.getRegion())) {
                continue;
            }
            if (states != null && !states.isEmpty() && !states.contains(c.getState())) {
                continue;
            }
            result.add(c);
        }
        return result;
    }

    public synchronized void terminateJobFlows(List<String> ids) {
        // AWS terminates the unprotected clusters in the request and then fails it with a
        // ValidationException if any were termination protected.
        boolean anyProtected = false;
        for (String id : ids) {
            EmrCluster cluster = findCluster(id).orElse(null);
            if (cluster == null) {
                continue;
            }
            if (cluster.isTerminationProtected()) {
                anyProtected = true;
                continue;
            }
            if ("TERMINATED".equals(cluster.getState())) {
                continue;
            }
            cluster.setState("TERMINATED");
            cluster.setStateChangeReasonCode("USER_REQUEST");
            cluster.setStateChangeMessage("Terminated by user request");
            cluster.setEndDateTime(Instant.now());
            clusterStore.put(id, cluster);
        }
        if (anyProtected) {
            throw new AwsException("ValidationException",
                    "Could not shut down one or more job flows since they are termination protected.", 400);
        }
    }

    public synchronized void setTerminationProtection(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setTerminationProtected(value));
    }

    public synchronized void setVisibleToAllUsers(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setVisibleToAllUsers(value));
    }

    public synchronized void setKeepJobFlowAliveWhenNoSteps(List<String> ids, boolean value) {
        mutateClusters(ids, c -> {
            c.setKeepJobFlowAliveWhenNoSteps(value);
            c.setAutoTerminate(!value);
        });
    }

    public synchronized void setUnhealthyNodeReplacement(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setUnhealthyNodeReplacement(value));
    }

    public synchronized int modifyCluster(String id, Integer stepConcurrencyLevel) {
        EmrCluster cluster = requireCluster(id);
        if (stepConcurrencyLevel != null) {
            cluster.setStepConcurrencyLevel(stepConcurrencyLevel);
            clusterStore.put(id, cluster);
        }
        return cluster.getStepConcurrencyLevel();
    }

    // ──────────────────────────── Steps ────────────────────────────

    public synchronized List<String> addJobFlowSteps(String clusterId, List<EmrStep> steps) {
        EmrCluster cluster = requireCluster(clusterId);
        List<String> ids = new ArrayList<>();
        for (EmrStep step : steps) {
            initStep(step);
            completeStep(step);
            cluster.getSteps().add(step);
            ids.add(step.getId());
        }
        clusterStore.put(clusterId, cluster);
        return ids;
    }

    public EmrStep describeStep(String clusterId, String stepId) {
        EmrCluster cluster = requireCluster(clusterId);
        return cluster.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Step " + stepId + " does not exist.", 400));
    }

    public List<EmrStep> listSteps(String clusterId, List<String> states, List<String> stepIds) {
        EmrCluster cluster = requireCluster(clusterId);
        List<EmrStep> result = new ArrayList<>();
        // AWS returns steps in reverse submission order (newest first).
        List<EmrStep> steps = cluster.getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            EmrStep step = steps.get(i);
            if (states != null && !states.isEmpty() && !states.contains(step.getState())) {
                continue;
            }
            if (stepIds != null && !stepIds.isEmpty() && !stepIds.contains(step.getId())) {
                continue;
            }
            result.add(step);
        }
        return result;
    }

    public record CancelStepInfo(String stepId, String status, String reason) {}

    public synchronized List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds) {
        EmrCluster cluster = requireCluster(clusterId);
        List<CancelStepInfo> result = new ArrayList<>();
        for (String stepId : stepIds) {
            EmrStep step = cluster.getSteps().stream()
                    .filter(s -> s.getId().equals(stepId)).findFirst().orElse(null);
            if (step == null) {
                result.add(new CancelStepInfo(stepId, "FAILED", "Step does not exist."));
            } else if ("PENDING".equals(step.getState())) {
                step.setState("CANCELLED");
                step.setEndDateTime(Instant.now());
                result.add(new CancelStepInfo(stepId, "SUBMITTED", null));
            } else {
                result.add(new CancelStepInfo(stepId, "FAILED",
                        "Step is not in a cancellable state: " + step.getState()));
            }
        }
        clusterStore.put(clusterId, cluster);
        return result;
    }

    // ──────────────────────────── Instance groups / fleets ────────────────────────────

    public synchronized List<String> addInstanceGroups(String clusterId, List<EmrInstanceGroup> groups) {
        EmrCluster cluster = requireCluster(clusterId);
        List<String> ids = new ArrayList<>();
        for (EmrInstanceGroup group : groups) {
            group.setId("ig-" + randomId(13));
            group.setRunningInstanceCount(group.getRequestedInstanceCount());
            group.setState("RUNNING");
            cluster.getInstanceGroups().add(group);
            ids.add(group.getId());
        }
        clusterStore.put(clusterId, cluster);
        return ids;
    }

    /** One InstanceGroupModifyConfig: a null count or configurations leaves that setting as it is. */
    public record InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations) {}

    /**
     * Resizes or reconfigures instance groups in place. ClusterId is optional in the request, so
     * without it each group is looked up across the account's clusters in the Region. Every modification is
     * checked before any is applied.
     */
    public synchronized void modifyInstanceGroups(String clusterId, String region,
                                                  List<InstanceGroupModification> modifications) {
        if (modifications.isEmpty()) {
            throw invalid("InstanceGroups must contain at least one instance group.");
        }
        Map<String, EmrCluster> touched = new LinkedHashMap<>();
        List<EmrInstanceGroup> groups = new ArrayList<>();
        for (InstanceGroupModification mod : modifications) {
            EmrCluster found = clusterId != null ? requireCluster(clusterId) : clusterOwning(region, mod.instanceGroupId());
            // One copy per cluster, so two groups of the same cluster are changed and stored together.
            EmrCluster cluster = touched.computeIfAbsent(found.getId(), id -> found);
            EmrInstanceGroup group = requireInstanceGroup(cluster, mod.instanceGroupId());
            if (mod.instanceCount() != null) {
                if (mod.instanceCount() < 0) {
                    throw invalid("InstanceCount must not be negative.");
                }
                if ("MASTER".equals(group.getInstanceGroupType())
                        && mod.instanceCount() != group.getRequestedInstanceCount()) {
                    throw invalid("The instance count of a master instance group cannot be modified.");
                }
            }
            groups.add(group);
        }
        for (int i = 0; i < modifications.size(); i++) {
            InstanceGroupModification mod = modifications.get(i);
            EmrInstanceGroup group = groups.get(i);
            if (mod.instanceCount() != null) {
                group.setRequestedInstanceCount(mod.instanceCount());
                group.setRunningInstanceCount(mod.instanceCount());
            }
            if (mod.configurations() != null) {
                group.setConfigurations(mod.configurations());
                group.setConfigurationsVersion(group.getConfigurationsVersion() + 1);
            }
        }
        touched.forEach(clusterStore::put);
    }

    public List<EmrInstanceGroup> listInstanceGroups(String clusterId) {
        return requireCluster(clusterId).getInstanceGroups();
    }

    public synchronized String addInstanceFleet(String clusterId, EmrInstanceFleet fleet) {
        EmrCluster cluster = requireCluster(clusterId);
        fleet.setId("if-" + randomId(13));
        fleet.setProvisionedOnDemandCapacity(fleet.getTargetOnDemandCapacity());
        fleet.setProvisionedSpotCapacity(fleet.getTargetSpotCapacity());
        fleet.setState("RUNNING");
        cluster.getInstanceFleets().add(fleet);
        clusterStore.put(clusterId, cluster);
        return fleet.getId();
    }

    /** Resizes an instance fleet; a null target leaves that capacity as it is. */
    public synchronized void modifyInstanceFleet(String clusterId, String instanceFleetId,
                                                 Integer targetOnDemandCapacity, Integer targetSpotCapacity) {
        EmrCluster cluster = requireCluster(clusterId);
        if (instanceFleetId == null) {
            throw invalid("InstanceFleetId is required.");
        }
        EmrInstanceFleet fleet = cluster.getInstanceFleets().stream()
                .filter(f -> instanceFleetId.equals(f.getId()))
                .findFirst()
                .orElseThrow(() -> invalid("Instance fleet " + instanceFleetId + " is not in cluster " + clusterId + "."));
        int onDemand = targetOnDemandCapacity != null ? targetOnDemandCapacity : fleet.getTargetOnDemandCapacity();
        int spot = targetSpotCapacity != null ? targetSpotCapacity : fleet.getTargetSpotCapacity();
        if (onDemand < 0 || spot < 0) {
            throw invalid("Target capacities must not be negative.");
        }
        if ("MASTER".equals(fleet.getInstanceFleetType())
                && (onDemand != fleet.getTargetOnDemandCapacity() || spot != fleet.getTargetSpotCapacity())) {
            throw invalid("The target capacity of a master instance fleet cannot be modified.");
        }
        fleet.setTargetOnDemandCapacity(onDemand);
        fleet.setTargetSpotCapacity(spot);
        fleet.setProvisionedOnDemandCapacity(onDemand);
        fleet.setProvisionedSpotCapacity(spot);
        clusterStore.put(clusterId, cluster);
    }

    /** The cluster's bootstrap actions as given to RunJobFlow, raw JSON; null when it had none. */
    public String listBootstrapActions(String clusterId) {
        return requireCluster(clusterId).getBootstrapActions();
    }

    public List<EmrInstanceFleet> listInstanceFleets(String clusterId) {
        return requireCluster(clusterId).getInstanceFleets();
    }

    public EmrCluster getClusterForInstances(String clusterId) {
        return requireCluster(clusterId);
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    public SecurityConfiguration createSecurityConfiguration(String name, String json) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "Name is required.", 400);
        }
        if (secConfigStore.get(name).isPresent()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration already exists: " + name, 400);
        }
        SecurityConfiguration sc = new SecurityConfiguration();
        sc.setName(name);
        sc.setSecurityConfiguration(json);
        sc.setCreationDateTime(Instant.now());
        secConfigStore.put(name, sc);
        return sc;
    }

    public SecurityConfiguration describeSecurityConfiguration(String name) {
        return secConfigStore.get(name).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Security configuration does not exist: " + name, 400));
    }

    public void deleteSecurityConfiguration(String name) {
        if (secConfigStore.get(name).isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration does not exist: " + name, 400);
        }
        secConfigStore.delete(name);
    }

    public List<SecurityConfiguration> listSecurityConfigurations() {
        return new ArrayList<>(secConfigStore.scan(k -> true));
    }

    // ──────────────────────────── Tags ────────────────────────────

    public synchronized void addTags(String resourceId, Map<String, String> tags) {
        EmrCluster cluster = requireCluster(resourceId);
        cluster.getTags().putAll(tags);
        clusterStore.put(resourceId, cluster);
    }

    public synchronized void removeTags(String resourceId, List<String> keys) {
        EmrCluster cluster = requireCluster(resourceId);
        keys.forEach(cluster.getTags()::remove);
        clusterStore.put(resourceId, cluster);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    // ──────────────────────────── Scaling and termination policies ────────────────────────────

    public synchronized void putManagedScalingPolicy(String clusterId, JsonNode policy) {
        EmrCluster cluster = requireCluster(clusterId);
        validateManagedScalingPolicy(policy);
        cluster.setManagedScalingPolicy(policy.toString());
        clusterStore.put(clusterId, cluster);
    }

    /** The cluster's managed scaling policy as raw JSON, or null when it has none. */
    public String getManagedScalingPolicy(String clusterId) {
        return requireCluster(clusterId).getManagedScalingPolicy();
    }

    public synchronized void removeManagedScalingPolicy(String clusterId) {
        EmrCluster cluster = requireCluster(clusterId);
        cluster.setManagedScalingPolicy(null);
        clusterStore.put(clusterId, cluster);
    }

    /** AutoTerminationPolicy is optional in the request; an absent one is stored as an empty policy. */
    public synchronized void putAutoTerminationPolicy(String clusterId, JsonNode policy) {
        EmrCluster cluster = requireCluster(clusterId);
        validateAutoTerminationPolicy(policy);
        cluster.setAutoTerminationPolicy(policy == null || policy.isMissingNode() || policy.isNull() ? "{}" : policy.toString());
        clusterStore.put(clusterId, cluster);
    }

    /** The cluster's auto termination policy as raw JSON, or null when it has none. */
    public String getAutoTerminationPolicy(String clusterId) {
        return requireCluster(clusterId).getAutoTerminationPolicy();
    }

    public synchronized void removeAutoTerminationPolicy(String clusterId) {
        EmrCluster cluster = requireCluster(clusterId);
        cluster.setAutoTerminationPolicy(null);
        clusterStore.put(clusterId, cluster);
    }

    /** Attaches an automatic scaling policy to a core or task instance group and returns the group. */
    public synchronized EmrInstanceGroup putAutoScalingPolicy(String clusterId, String instanceGroupId, JsonNode policy) {
        EmrCluster cluster = requireCluster(clusterId);
        EmrInstanceGroup group = requireInstanceGroup(cluster, instanceGroupId);
        validateAutoScalingPolicy(policy, group.getInstanceGroupType());
        group.setAutoScalingPolicy(policy.toString());
        clusterStore.put(clusterId, cluster);
        return group;
    }

    public synchronized void removeAutoScalingPolicy(String clusterId, String instanceGroupId) {
        EmrCluster cluster = requireCluster(clusterId);
        requireInstanceGroup(cluster, instanceGroupId).setAutoScalingPolicy(null);
        clusterStore.put(clusterId, cluster);
    }

    // ──────────────────────────── Block public access ────────────────────────────

    /**
     * The account's block public access configuration in {@code region}. Until one is put, it is the
     * AWS default: enabled for every Region, with port 22 as an exception. The default is stored the
     * first time it is read, so its metadata stays the same on later reads.
     */
    public synchronized EmrBlockPublicAccess getBlockPublicAccess(String region) {
        return blockPublicAccessStore.get(region).orElseGet(() -> {
            EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
            setting.setConfiguration(DEFAULT_BLOCK_PUBLIC_ACCESS);
            setting.setCreationDateTime(Instant.now());
            setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
            blockPublicAccessStore.put(region, setting);
            return setting;
        });
    }

    public synchronized void putBlockPublicAccess(String region, JsonNode configuration) {
        validateBlockPublicAccess(configuration);
        EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
        setting.setConfiguration(configuration.toString());
        setting.setCreationDateTime(Instant.now());
        setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
        blockPublicAccessStore.put(region, setting);
    }

    // ──────────────────────────── Policy validation (from the API model) ────────────────────────────

    /** ComputeLimits is required, with UnitType, MinimumCapacityUnits and MaximumCapacityUnits. */
    static void validateManagedScalingPolicy(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw invalid("ManagedScalingPolicy is required.");
        }
        JsonNode limits = policy.path("ComputeLimits");
        if (!limits.isObject()) {
            throw invalid("ManagedScalingPolicy.ComputeLimits is required.");
        }
        if (!COMPUTE_LIMITS_UNIT_TYPES.contains(limits.path("UnitType").asText(""))) {
            throw invalid("ComputeLimits.UnitType must be one of " + COMPUTE_LIMITS_UNIT_TYPES + ".");
        }
        if (!limits.path("MinimumCapacityUnits").isInt() || !limits.path("MaximumCapacityUnits").isInt()) {
            throw invalid("ComputeLimits.MinimumCapacityUnits and MaximumCapacityUnits are required.");
        }
        int min = limits.get("MinimumCapacityUnits").asInt();
        int max = limits.get("MaximumCapacityUnits").asInt();
        if (min < 0 || min > max) {
            throw invalid("ComputeLimits.MinimumCapacityUnits must be between 0 and MaximumCapacityUnits.");
        }
        for (String optional : List.of("MaximumOnDemandCapacityUnits", "MaximumCoreCapacityUnits")) {
            JsonNode value = limits.path(optional);
            if (!value.isMissingNode() && (!value.isInt() || value.asInt() < 0 || value.asInt() > max)) {
                throw invalid("ComputeLimits." + optional + " must be between 0 and MaximumCapacityUnits.");
            }
        }
        JsonNode strategy = policy.path("ScalingStrategy");
        if (!strategy.isMissingNode() && !SCALING_STRATEGIES.contains(strategy.asText(""))) {
            throw invalid("ScalingStrategy must be one of " + SCALING_STRATEGIES + ".");
        }
    }

    /** IdleTimeout, when given, is 60 to 604800 seconds. */
    static void validateAutoTerminationPolicy(JsonNode policy) {
        if (policy == null || policy.isMissingNode() || policy.isNull()) {
            return;
        }
        if (!policy.isObject()) {
            throw invalid("AutoTerminationPolicy must be an object.");
        }
        JsonNode idle = policy.path("IdleTimeout");
        if (!idle.isMissingNode() && (!idle.canConvertToLong()
                || idle.asLong() < MIN_IDLE_TIMEOUT_SECONDS || idle.asLong() > MAX_IDLE_TIMEOUT_SECONDS)) {
            throw invalid("AutoTerminationPolicy.IdleTimeout must be between " + MIN_IDLE_TIMEOUT_SECONDS
                    + " and " + MAX_IDLE_TIMEOUT_SECONDS + " seconds.");
        }
    }

    /**
     * Constraints (MinCapacity not above MaxCapacity) and at least one rule with Name, Action and
     * Trigger are required; only core and task instance groups scale automatically.
     */
    static void validateAutoScalingPolicy(JsonNode policy, String instanceGroupType) {
        if (policy == null || !policy.isObject()) {
            throw invalid("AutoScalingPolicy is required.");
        }
        if (!"CORE".equals(instanceGroupType) && !"TASK".equals(instanceGroupType)) {
            throw invalid("An automatic scaling policy can only be attached to a core or task instance group.");
        }
        JsonNode constraints = policy.path("Constraints");
        if (!constraints.path("MinCapacity").isInt() || !constraints.path("MaxCapacity").isInt()) {
            throw invalid("AutoScalingPolicy.Constraints.MinCapacity and MaxCapacity are required.");
        }
        if (constraints.get("MinCapacity").asInt() < 0
                || constraints.get("MinCapacity").asInt() > constraints.get("MaxCapacity").asInt()) {
            throw invalid("Constraints.MinCapacity must be between 0 and MaxCapacity.");
        }
        JsonNode rules = policy.path("Rules");
        if (!rules.isArray() || rules.isEmpty()) {
            throw invalid("AutoScalingPolicy.Rules must contain at least one rule.");
        }
        for (JsonNode rule : rules) {
            if (rule.path("Name").asText("").isEmpty() || !rule.path("Action").isObject() || !rule.path("Trigger").isObject()) {
                throw invalid("Each scaling rule needs a Name, an Action and a Trigger.");
            }
        }
    }

    /** BlockPublicSecurityGroupRules is required; each port range needs MinRange, within 0 to 65535. */
    static void validateBlockPublicAccess(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()
                || !configuration.path("BlockPublicSecurityGroupRules").isBoolean()) {
            throw invalid("BlockPublicAccessConfiguration.BlockPublicSecurityGroupRules is required.");
        }
        JsonNode ranges = configuration.path("PermittedPublicSecurityGroupRuleRanges");
        if (ranges.isMissingNode()) {
            return;
        }
        if (!ranges.isArray()) {
            throw invalid("PermittedPublicSecurityGroupRuleRanges must be a list.");
        }
        for (JsonNode range : ranges) {
            if (!range.path("MinRange").isInt()) {
                throw invalid("Each port range needs a MinRange.");
            }
            int min = range.get("MinRange").asInt();
            JsonNode maxRange = range.path("MaxRange");
            if (!maxRange.isMissingNode() && !maxRange.isInt()) {
                throw invalid("Port range MaxRange must be an integer.");
            }
            int max = maxRange.isInt() ? maxRange.asInt() : min;
            if (min < 0 || max > MAX_PORT || min > max) {
                throw invalid("Port range " + min + "-" + max + " must lie within 0-" + MAX_PORT + ", MinRange first.");
            }
        }
    }

    /** Each BootstrapActionConfig needs a Name and a ScriptBootstrapAction with a Path. */
    static void validateBootstrapActions(JsonNode actions) {
        if (!actions.isArray()) {
            throw invalid("BootstrapActions must be a list.");
        }
        for (JsonNode action : actions) {
            if (action.path("Name").asText("").isEmpty()
                    || action.path("ScriptBootstrapAction").path("Path").asText("").isEmpty()) {
                throw invalid("Each bootstrap action needs a Name and a ScriptBootstrapAction.Path.");
            }
        }
    }

    private EmrCluster clusterOwning(String region, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return clusterStore.scan(k -> true).stream()
                .filter(c -> region.equals(c.getRegion()))
                .filter(c -> c.getInstanceGroups().stream().anyMatch(g -> instanceGroupId.equals(g.getId())))
                .findFirst()
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not valid."));
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static EmrInstanceGroup requireInstanceGroup(EmrCluster cluster, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return cluster.getInstanceGroups().stream()
                .filter(group -> instanceGroupId.equals(group.getId()))
                .findFirst()
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not in cluster " + cluster.getId() + "."));
    }

    private EmrCluster requireCluster(String id) {
        if (id == null) {
            throw new AwsException("InvalidRequestException", "ClusterId is required.", 400);
        }
        return findCluster(id).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + id + "' is not valid.", 400));
    }

    /**
     * Clusters are stored by id for every region; a request sees only its own region's, and one from
     * another region reads as an unknown id. A cluster recorded without a region stays reachable.
     */
    private Optional<EmrCluster> findCluster(String id) {
        String region = regionResolver.getRegion();
        return clusterStore.get(id).filter(c -> c.getRegion() == null || c.getRegion().equals(region));
    }

    private void mutateClusters(List<String> ids, java.util.function.Consumer<EmrCluster> mutation) {
        for (String id : ids) {
            findCluster(id).ifPresent(c -> {
                mutation.accept(c);
                clusterStore.put(id, c);
            });
        }
    }

    private void advanceToWaiting(EmrCluster cluster) {
        cluster.setState("WAITING");
        cluster.setStateChangeReasonCode(cluster.getSteps().isEmpty() ? null : "ALL_STEPS_COMPLETED");
        cluster.setStateChangeMessage("Cluster ready to run steps.");
        cluster.setReadyDateTime(Instant.now());
    }

    private void initStep(EmrStep step) {
        step.setId("s-" + randomId(13));
        step.setState("PENDING");
        step.setCreationDateTime(Instant.now());
        if (step.getActionOnFailure() == null) {
            step.setActionOnFailure("TERMINATE_CLUSTER");
        }
    }

    private void completeStep(EmrStep step) {
        Instant now = Instant.now();
        step.setStartDateTime(now);
        step.setEndDateTime(now);
        step.setState("COMPLETED");
    }

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(UPPER_ALNUM.charAt(RANDOM.nextInt(UPPER_ALNUM.length())));
        }
        return sb.toString();
    }
}
