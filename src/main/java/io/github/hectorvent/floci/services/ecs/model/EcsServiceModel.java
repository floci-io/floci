package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class EcsServiceModel {

    private String serviceArn;
    private String serviceName;
    private String clusterArn;
    private String taskDefinition;
    private LaunchType launchType;
    private int desiredCount;
    private int runningCount;
    private int pendingCount;
    private String status;
    private Instant createdAt;
    /** When the current deployment began: service creation, or the last task-definition change. */
    private Instant lastDeploymentAt;
    /** Current deployment identifier ("ecs-svc/<hex>"). Rolls on a task-definition change or forceNewDeployment. */
    private String deploymentId;
    /** The deploymentId last observed to reach steady state; guards against re-emitting COMPLETED. */
    private String lastCompletedDeploymentId;
    private String namespace;
    private String deploymentController;
    private String schedulingStrategy;
    private String availabilityZoneRebalancing;
    private Map<String, String> tags = new HashMap<>();
    private List<EcsLoadBalancer> loadBalancers = new ArrayList<>();
    private NetworkConfiguration networkConfiguration;

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public int getDesiredCount() { return desiredCount; }
    public void setDesiredCount(int desiredCount) { this.desiredCount = desiredCount; }

    public int getRunningCount() { return runningCount; }
    public void setRunningCount(int runningCount) { this.runningCount = runningCount; }

    public int getPendingCount() { return pendingCount; }
    public void setPendingCount(int pendingCount) { this.pendingCount = pendingCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastDeploymentAt() { return lastDeploymentAt; }
    public void setLastDeploymentAt(Instant lastDeploymentAt) { this.lastDeploymentAt = lastDeploymentAt; }

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }

    public String getLastCompletedDeploymentId() { return lastCompletedDeploymentId; }
    public void setLastCompletedDeploymentId(String lastCompletedDeploymentId) {
        this.lastCompletedDeploymentId = lastCompletedDeploymentId;
    }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDeploymentController() { return deploymentController; }
    public void setDeploymentController(String deploymentController) { this.deploymentController = deploymentController; }

    public String getSchedulingStrategy() { return schedulingStrategy; }
    public void setSchedulingStrategy(String schedulingStrategy) { this.schedulingStrategy = schedulingStrategy; }

    public String getAvailabilityZoneRebalancing() { return availabilityZoneRebalancing; }
    public void setAvailabilityZoneRebalancing(String availabilityZoneRebalancing) {
        this.availabilityZoneRebalancing = availabilityZoneRebalancing;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<EcsLoadBalancer> getLoadBalancers() { return loadBalancers; }
    public void setLoadBalancers(List<EcsLoadBalancer> loadBalancers) {
        this.loadBalancers = loadBalancers != null ? loadBalancers : new ArrayList<>();
    }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }
}
