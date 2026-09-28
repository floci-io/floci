package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class EcsCluster {

    private String clusterArn;
    private String clusterName;
    private String status;
    private int registeredContainerInstancesCount;
    private int runningTasksCount;
    private int pendingTasksCount;
    private int activeServicesCount;
    private List<ClusterSetting> settings;
    private List<String> capacityProviders;
    private List<Map<String, Object>> defaultCapacityProviderStrategy;
    private Map<String, Object> configuration;
    private Map<String, Object> serviceConnectDefaults;
    private Map<String, String> tags = new HashMap<>();

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRegisteredContainerInstancesCount() { return registeredContainerInstancesCount; }
    public void setRegisteredContainerInstancesCount(int count) { this.registeredContainerInstancesCount = count; }

    public int getRunningTasksCount() { return runningTasksCount; }
    public void setRunningTasksCount(int count) { this.runningTasksCount = count; }

    public int getPendingTasksCount() { return pendingTasksCount; }
    public void setPendingTasksCount(int count) { this.pendingTasksCount = count; }

    public int getActiveServicesCount() { return activeServicesCount; }
    public void setActiveServicesCount(int count) { this.activeServicesCount = count; }

    public List<ClusterSetting> getSettings() { return settings; }
    public void setSettings(List<ClusterSetting> settings) { this.settings = settings; }

    public List<String> getCapacityProviders() { return capacityProviders; }
    public void setCapacityProviders(List<String> capacityProviders) { this.capacityProviders = capacityProviders; }

    public List<Map<String, Object>> getDefaultCapacityProviderStrategy() { return defaultCapacityProviderStrategy; }
    public void setDefaultCapacityProviderStrategy(List<Map<String, Object>> s) { this.defaultCapacityProviderStrategy = s; }

    public Map<String, Object> getConfiguration() { return configuration; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }

    public Map<String, Object> getServiceConnectDefaults() { return serviceConnectDefaults; }
    public void setServiceConnectDefaults(Map<String, Object> defaults) { this.serviceConnectDefaults = defaults; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
