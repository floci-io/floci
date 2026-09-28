package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A parsed {@code CreateCluster} request. */
@RegisterForReflection
public class CreateClusterRequest {

    private String clusterName;
    private Map<String, String> tags;
    private List<ClusterSetting> settings;
    private Map<String, Object> configuration;
    private Map<String, Object> serviceConnectDefaults;
    private List<String> capacityProviders;
    private List<Map<String, Object>> defaultCapacityProviderStrategy;

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<ClusterSetting> getSettings() { return settings; }
    public void setSettings(List<ClusterSetting> settings) { this.settings = settings; }

    public Map<String, Object> getConfiguration() { return configuration; }
    public void setConfiguration(Map<String, Object> configuration) { this.configuration = configuration; }

    public Map<String, Object> getServiceConnectDefaults() { return serviceConnectDefaults; }
    public void setServiceConnectDefaults(Map<String, Object> defaults) { this.serviceConnectDefaults = defaults; }

    public List<String> getCapacityProviders() { return capacityProviders; }
    public void setCapacityProviders(List<String> capacityProviders) { this.capacityProviders = capacityProviders; }

    public List<Map<String, Object>> getDefaultCapacityProviderStrategy() {
        return defaultCapacityProviderStrategy;
    }

    public void setDefaultCapacityProviderStrategy(List<Map<String, Object>> strategy) {
        this.defaultCapacityProviderStrategy = strategy;
    }
}
