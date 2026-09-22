package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code CreateTaskSet} request. */
@RegisterForReflection
public class CreateTaskSetRequest {

    private String cluster;
    private String service;
    private String taskDefinition;
    private LaunchType launchType;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private String platformVersion;
    private Double scaleValue;
    private String scaleUnit;
    private String externalId;
    /** Set by Floci's CodeDeploy, which reports {@code CODE_DEPLOY}; an external deployment leaves it unset. */
    private String startedBy;
    private NetworkConfiguration networkConfiguration;
    private List<EcsLoadBalancer> loadBalancers;
    private List<Map<String, Object>> serviceRegistries;
    private Map<String, String> tags;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> strategy) {
        this.capacityProviderStrategy = strategy;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public Double getScaleValue() { return scaleValue; }
    public void setScaleValue(Double scaleValue) { this.scaleValue = scaleValue; }

    public String getScaleUnit() { return scaleUnit; }
    public void setScaleUnit(String scaleUnit) { this.scaleUnit = scaleUnit; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }

    public NetworkConfiguration getNetworkConfiguration() { return networkConfiguration; }
    public void setNetworkConfiguration(NetworkConfiguration networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public List<EcsLoadBalancer> getLoadBalancers() { return loadBalancers; }
    public void setLoadBalancers(List<EcsLoadBalancer> loadBalancers) { this.loadBalancers = loadBalancers; }

    public List<Map<String, Object>> getServiceRegistries() { return serviceRegistries; }
    public void setServiceRegistries(List<Map<String, Object>> serviceRegistries) {
        this.serviceRegistries = serviceRegistries;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
