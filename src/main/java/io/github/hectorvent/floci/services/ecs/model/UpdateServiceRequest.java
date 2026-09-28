package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A parsed {@code UpdateService} request.
 *
 * <p>Every member is nullable: UpdateService leaves anything the request omitted exactly as it
 * was, so {@code null} means "unchanged" rather than "clear it".
 */
@RegisterForReflection
public class UpdateServiceRequest {

    private String cluster;
    private String service;
    private String taskDefinition;
    private Integer desiredCount;
    private NetworkConfiguration networkConfiguration;
    private String availabilityZoneRebalancing;
    private boolean forceNewDeployment;
    private Map<String, Object> serviceConnectConfiguration;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private String platformVersion;
    private Boolean enableExecuteCommand;
    private Boolean enableECSManagedTags;
    private String propagateTags;
    private Integer healthCheckGracePeriodSeconds;
    private Map<String, Object> deploymentConfiguration;
    private List<EcsLoadBalancer> loadBalancers;
    private List<Map<String, Object>> serviceRegistries;
    private Map<String, Object> unparsed;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public Integer getDesiredCount() { return desiredCount; }
    public void setDesiredCount(Integer desiredCount) { this.desiredCount = desiredCount; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public String getAvailabilityZoneRebalancing() { return availabilityZoneRebalancing; }
    public void setAvailabilityZoneRebalancing(String availabilityZoneRebalancing) {
        this.availabilityZoneRebalancing = availabilityZoneRebalancing;
    }

    public boolean isForceNewDeployment() { return forceNewDeployment; }
    public void setForceNewDeployment(boolean forceNewDeployment) { this.forceNewDeployment = forceNewDeployment; }

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public Boolean getEnableExecuteCommand() { return enableExecuteCommand; }
    public void setEnableExecuteCommand(Boolean enableExecuteCommand) {
        this.enableExecuteCommand = enableExecuteCommand;
    }

    public Boolean getEnableECSManagedTags() { return enableECSManagedTags; }
    public void setEnableECSManagedTags(Boolean enableECSManagedTags) {
        this.enableECSManagedTags = enableECSManagedTags;
    }

    public String getPropagateTags() { return propagateTags; }
    public void setPropagateTags(String propagateTags) { this.propagateTags = propagateTags; }

    public Integer getHealthCheckGracePeriodSeconds() { return healthCheckGracePeriodSeconds; }
    public void setHealthCheckGracePeriodSeconds(Integer healthCheckGracePeriodSeconds) {
        this.healthCheckGracePeriodSeconds = healthCheckGracePeriodSeconds;
    }

    public Map<String, Object> getDeploymentConfiguration() { return deploymentConfiguration; }
    public void setDeploymentConfiguration(Map<String, Object> deploymentConfiguration) {
        this.deploymentConfiguration = deploymentConfiguration;
    }

    public List<EcsLoadBalancer> getLoadBalancers() { return loadBalancers; }
    public void setLoadBalancers(List<EcsLoadBalancer> loadBalancers) { this.loadBalancers = loadBalancers; }

    public List<Map<String, Object>> getServiceRegistries() { return serviceRegistries; }
    public void setServiceRegistries(List<Map<String, Object>> serviceRegistries) {
        this.serviceRegistries = serviceRegistries;
    }

    public Map<String, Object> getUnparsed() { return unparsed; }
    public void setUnparsed(Map<String, Object> unparsed) { this.unparsed = unparsed; }
}
