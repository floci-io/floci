package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code CreateService} request. */
@RegisterForReflection
public class CreateServiceRequest {

    private String cluster;
    private String serviceName;
    private String taskDefinition;
    private int desiredCount = 1;
    private LaunchType launchType;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private String platformVersion;
    private List<EcsLoadBalancer> loadBalancers;
    private List<Map<String, Object>> serviceRegistries;
    private NetworkConfiguration networkConfiguration;
    private Map<String, String> tags;
    private String schedulingStrategy;
    private String deploymentControllerType;
    private String availabilityZoneRebalancing;
    private Map<String, Object> serviceConnectConfiguration;
    private Map<String, Object> deploymentConfiguration;
    private boolean enableExecuteCommand;
    private boolean enableECSManagedTags;
    private String propagateTags;
    private Integer healthCheckGracePeriodSeconds;
    private String roleArn;
    /** Members Floci does not act on, such as placement constraints and strategies. */
    private Map<String, Object> unparsed;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public int getDesiredCount() { return desiredCount; }
    public void setDesiredCount(int desiredCount) { this.desiredCount = desiredCount; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public List<EcsLoadBalancer> getLoadBalancers() { return loadBalancers; }
    public void setLoadBalancers(List<EcsLoadBalancer> loadBalancers) { this.loadBalancers = loadBalancers; }

    public List<Map<String, Object>> getServiceRegistries() { return serviceRegistries; }
    public void setServiceRegistries(List<Map<String, Object>> serviceRegistries) {
        this.serviceRegistries = serviceRegistries;
    }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getSchedulingStrategy() { return schedulingStrategy; }
    public void setSchedulingStrategy(String schedulingStrategy) { this.schedulingStrategy = schedulingStrategy; }

    public String getDeploymentControllerType() { return deploymentControllerType; }
    public void setDeploymentControllerType(String deploymentControllerType) {
        this.deploymentControllerType = deploymentControllerType;
    }

    public String getAvailabilityZoneRebalancing() { return availabilityZoneRebalancing; }
    public void setAvailabilityZoneRebalancing(String availabilityZoneRebalancing) {
        this.availabilityZoneRebalancing = availabilityZoneRebalancing;
    }

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

    public Map<String, Object> getDeploymentConfiguration() { return deploymentConfiguration; }
    public void setDeploymentConfiguration(Map<String, Object> deploymentConfiguration) {
        this.deploymentConfiguration = deploymentConfiguration;
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

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }
}
