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
    private String namespace;
    private String deploymentController;
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

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDeploymentController() { return deploymentController; }
    public void setDeploymentController(String deploymentController) { this.deploymentController = deploymentController; }

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
