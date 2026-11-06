package io.floci.aws.services.codedeploy.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeploymentConfig {
    public DeploymentConfig() {}

    private String deploymentConfigId;
    private String deploymentConfigName;
    private Map<String, Object> minimumHealthyHosts;
    private Double createTime;
    private String computePlatform;
    private Map<String, Object> trafficRoutingConfig;
    private Map<String, Object> zonalConfig;

    public String getDeploymentConfigId() { return deploymentConfigId; }
    public void setDeploymentConfigId(String deploymentConfigId) { this.deploymentConfigId = deploymentConfigId; }

    public String getDeploymentConfigName() { return deploymentConfigName; }
    public void setDeploymentConfigName(String deploymentConfigName) { this.deploymentConfigName = deploymentConfigName; }

    public Map<String, Object> getMinimumHealthyHosts() { return minimumHealthyHosts; }
    public void setMinimumHealthyHosts(Map<String, Object> minimumHealthyHosts) { this.minimumHealthyHosts = minimumHealthyHosts; }

    public Double getCreateTime() { return createTime; }
    public void setCreateTime(Double createTime) { this.createTime = createTime; }

    public String getComputePlatform() { return computePlatform; }
    public void setComputePlatform(String computePlatform) { this.computePlatform = computePlatform; }

    public Map<String, Object> getTrafficRoutingConfig() { return trafficRoutingConfig; }
    public void setTrafficRoutingConfig(Map<String, Object> trafficRoutingConfig) { this.trafficRoutingConfig = trafficRoutingConfig; }

    public Map<String, Object> getZonalConfig() { return zonalConfig; }
    public void setZonalConfig(Map<String, Object> zonalConfig) { this.zonalConfig = zonalConfig; }
}
