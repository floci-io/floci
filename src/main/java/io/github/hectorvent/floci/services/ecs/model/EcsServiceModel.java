package io.github.hectorvent.floci.services.ecs.model;

import io.github.hectorvent.floci.services.ecs.EcsServiceDiscoveryRegistrar;
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
    /**
     * The Service Connect configuration given on CreateService or UpdateService, kept raw.
     * DescribeServices reports it on each deployments[] entry, which is where AWS's model puts
     * it; the Service shape itself has no member for it.
     */
    private Map<String, Object> serviceConnectConfiguration;
    private String platformVersion;
    private String platformFamily;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private boolean enableExecuteCommand;
    private boolean enableECSManagedTags;
    private String propagateTags;
    private Integer healthCheckGracePeriodSeconds;
    private String roleArn;
    /** {@code deploymentConfiguration}, kept raw: Floci reports it but runs no rollout against it. */
    private Map<String, Object> deploymentConfiguration;
    /**
     * {@code serviceRegistries}, kept raw. {@link EcsServiceDiscoveryRegistrar}
     * reads the three members AWS uses to place the instance; nothing else in ECS acts on it.
     */
    private List<Map<String, Object>> serviceRegistries;
    /** Members Floci does not act on, kept verbatim so DescribeServices round-trips. */
    private Map<String, Object> unparsed;

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

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public String getPlatformFamily() { return platformFamily; }
    public void setPlatformFamily(String platformFamily) { this.platformFamily = platformFamily; }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public boolean isEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(boolean enableExecuteCommand) {
        this.enableExecuteCommand = enableExecuteCommand;
    }

    public boolean isEnableECSManagedTags() { return enableECSManagedTags; }
    public void setEnableECSManagedTags(boolean enableECSManagedTags) {
        this.enableECSManagedTags = enableECSManagedTags;
    }

    public String getPropagateTags() { return propagateTags; }
    public void setPropagateTags(String propagateTags) { this.propagateTags = propagateTags; }

    public Integer getHealthCheckGracePeriodSeconds() { return healthCheckGracePeriodSeconds; }
    public void setHealthCheckGracePeriodSeconds(Integer healthCheckGracePeriodSeconds) {
        this.healthCheckGracePeriodSeconds = healthCheckGracePeriodSeconds;
    }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public Map<String, Object> getDeploymentConfiguration() { return deploymentConfiguration; }
    public void setDeploymentConfiguration(Map<String, Object> deploymentConfiguration) {
        this.deploymentConfiguration = deploymentConfiguration;
    }

    public List<Map<String, Object>> getServiceRegistries() { return serviceRegistries; }
    public void setServiceRegistries(List<Map<String, Object>> serviceRegistries) {
        this.serviceRegistries = serviceRegistries;
    }

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }
}
