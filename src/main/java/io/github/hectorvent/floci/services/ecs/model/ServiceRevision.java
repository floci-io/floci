package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The immutable snapshot of a service's configuration at the moment a deployment was created.
 * Everything a revision carries is copied from the service, not referenced, so a revision keeps
 * reporting what the service looked like then even after the service moves on.
 */
@RegisterForReflection
public class ServiceRevision {

    private String serviceRevisionArn;
    private String serviceArn;
    private String clusterArn;
    private String taskDefinition;
    private LaunchType launchType;
    private List<CapacityProviderStrategyItem> capacityProviderStrategy;
    private String platformVersion;
    private String platformFamily;
    private List<EcsLoadBalancer> loadBalancers;
    private List<Map<String, Object>> serviceRegistries;
    private NetworkConfiguration networkConfiguration;
    private List<ContainerImage> containerImages;
    private Map<String, Object> serviceConnectConfiguration;
    private Instant createdAt;

    public List<CapacityProviderStrategyItem> getCapacityProviderStrategy() { return capacityProviderStrategy; }
    public void setCapacityProviderStrategy(List<CapacityProviderStrategyItem> capacityProviderStrategy) {
        this.capacityProviderStrategy = capacityProviderStrategy;
    }

    public String getPlatformVersion() { return platformVersion; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion; }

    public String getPlatformFamily() { return platformFamily; }
    public void setPlatformFamily(String platformFamily) { this.platformFamily = platformFamily; }

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

    public List<ContainerImage> getContainerImages() { return containerImages; }
    public void setContainerImages(List<ContainerImage> containerImages) {
        this.containerImages = containerImages;
    }

    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }

    public String getServiceRevisionArn() { return serviceRevisionArn; }
    public void setServiceRevisionArn(String serviceRevisionArn) { this.serviceRevisionArn = serviceRevisionArn; }

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
