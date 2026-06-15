package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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
import java.util.List;
import java.util.Map;

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
    private final RegionResolver regionResolver;
    private final String defaultReleaseLabel;

    @Inject
    public EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.clusterStore = storageFactory.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        this.secConfigStore = storageFactory.create("emr", "emr-security-configs.json",
                new TypeReference<Map<String, SecurityConfiguration>>() {});
        this.regionResolver = regionResolver;
        this.defaultReleaseLabel = config.services().emr().defaultReleaseLabel();
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    public EmrCluster runJobFlow(EmrCluster cluster, String region) {
        if (cluster.getName() == null || cluster.getName().isBlank()) {
            throw new AwsException("InternalServerError", "Name is required.", 500);
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
        cluster.setMasterPublicDnsName("ip-10-0-0-1." + region + ".compute.internal");
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

    public void terminateJobFlows(List<String> ids) {
        for (String id : ids) {
            EmrCluster cluster = clusterStore.get(id).orElse(null);
            if (cluster == null) {
                continue;
            }
            if (cluster.isTerminationProtected()) {
                throw new AwsException("InternalServerError",
                        "Cluster " + id + " is protected from termination.", 500);
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
    }

    public void setTerminationProtection(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setTerminationProtected(value));
    }

    public void setVisibleToAllUsers(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setVisibleToAllUsers(value));
    }

    public void setKeepJobFlowAliveWhenNoSteps(List<String> ids, boolean value) {
        mutateClusters(ids, c -> {
            c.setKeepJobFlowAliveWhenNoSteps(value);
            c.setAutoTerminate(!value);
        });
    }

    public void setUnhealthyNodeReplacement(List<String> ids, boolean value) {
        mutateClusters(ids, c -> c.setUnhealthyNodeReplacement(value));
    }

    public int modifyCluster(String id, Integer stepConcurrencyLevel) {
        EmrCluster cluster = requireCluster(id);
        if (stepConcurrencyLevel != null) {
            cluster.setStepConcurrencyLevel(stepConcurrencyLevel);
            clusterStore.put(id, cluster);
        }
        return cluster.getStepConcurrencyLevel();
    }

    // ──────────────────────────── Steps ────────────────────────────

    public List<String> addJobFlowSteps(String clusterId, List<EmrStep> steps) {
        EmrCluster cluster = clusterStore.get(clusterId).orElseThrow(() -> new AwsException(
                "InternalServerError", "Cluster " + clusterId + " does not exist.", 500));
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

    public List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds) {
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

    public List<String> addInstanceGroups(String clusterId, List<EmrInstanceGroup> groups) {
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

    public List<EmrInstanceGroup> listInstanceGroups(String clusterId) {
        return requireCluster(clusterId).getInstanceGroups();
    }

    public String addInstanceFleet(String clusterId, EmrInstanceFleet fleet) {
        EmrCluster cluster = requireCluster(clusterId);
        fleet.setId("if-" + randomId(13));
        fleet.setProvisionedOnDemandCapacity(fleet.getTargetOnDemandCapacity());
        fleet.setProvisionedSpotCapacity(fleet.getTargetSpotCapacity());
        fleet.setState("RUNNING");
        cluster.getInstanceFleets().add(fleet);
        clusterStore.put(clusterId, cluster);
        return fleet.getId();
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

    public void addTags(String resourceId, Map<String, String> tags) {
        EmrCluster cluster = requireCluster(resourceId);
        cluster.getTags().putAll(tags);
        clusterStore.put(resourceId, cluster);
    }

    public void removeTags(String resourceId, List<String> keys) {
        EmrCluster cluster = requireCluster(resourceId);
        keys.forEach(cluster.getTags()::remove);
        clusterStore.put(resourceId, cluster);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private EmrCluster requireCluster(String id) {
        if (id == null) {
            throw new AwsException("InvalidRequestException", "ClusterId is required.", 400);
        }
        return clusterStore.get(id).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + id + "' is not valid.", 400));
    }

    private void mutateClusters(List<String> ids, java.util.function.Consumer<EmrCluster> mutation) {
        for (String id : ids) {
            clusterStore.get(id).ifPresent(c -> {
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
