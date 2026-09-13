package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class Stage {

    private String stageName;
    private String deploymentId;
    private String description;
    private Map<String, String> variables = new HashMap<>();
    private Map<String, MethodSetting> methodSettings = new HashMap<>();
    /** Stage-level cache switch; a method's own cachingEnabled only applies when this is on. */
    private boolean cacheClusterEnabled;
    private String cacheClusterSize;
    private String cacheClusterStatus = "NOT_AVAILABLE";

    public boolean isCacheClusterEnabled() {
        return cacheClusterEnabled;
    }

    public void setCacheClusterEnabled(boolean cacheClusterEnabled) {
        this.cacheClusterEnabled = cacheClusterEnabled;
        this.cacheClusterStatus = cacheClusterEnabled ? "AVAILABLE" : "NOT_AVAILABLE";
    }

    public String getCacheClusterSize() {
        return cacheClusterSize;
    }

    public void setCacheClusterSize(String cacheClusterSize) {
        this.cacheClusterSize = cacheClusterSize;
    }

    public String getCacheClusterStatus() {
        return cacheClusterStatus;
    }

    public void setCacheClusterStatus(String cacheClusterStatus) {
        this.cacheClusterStatus = cacheClusterStatus;
    }
    private long createdDate;
    private long lastUpdatedDate;

    public String getStageName() {
        return stageName;
    }

    public void setStageName(String stageName) {
        this.stageName = stageName;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables != null ? variables : new HashMap<>();
    }

    public Map<String, MethodSetting> getMethodSettings() {
        return methodSettings;
    }

    public void setMethodSettings(Map<String, MethodSetting> methodSettings) {
        this.methodSettings = methodSettings != null ? methodSettings : new HashMap<>();
    }

    public long getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(long createdDate) {
        this.createdDate = createdDate;
    }

    public long getLastUpdatedDate() {
        return lastUpdatedDate;
    }

    public void setLastUpdatedDate(long lastUpdatedDate) {
        this.lastUpdatedDate = lastUpdatedDate;
    }
}
