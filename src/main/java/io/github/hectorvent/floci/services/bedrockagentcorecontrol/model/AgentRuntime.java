package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An emulated Amazon Bedrock AgentCore Runtime. Holds the current (latest) state
 * plus the history of version snapshots. No real agent execution — this is a
 * metadata record only.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRuntime {

    private String agentRuntimeId;
    private String agentRuntimeName;
    private String uuid;
    private String roleArn;
    private String description;
    private String status;
    private String workloadIdentityArn;
    private int latestVersion;
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private String accountId;
    private String clientToken;

    private JsonNode agentRuntimeArtifact;
    private JsonNode networkConfiguration;
    private JsonNode authorizerConfiguration;
    private JsonNode protocolConfiguration;
    private Map<String, String> environmentVariables = new HashMap<>();
    private Map<String, String> tags = new HashMap<>();
    private List<AgentRuntimeVersion> versions = new ArrayList<>();
    private List<AgentRuntimeEndpoint> endpoints = new ArrayList<>();

    public String getAgentRuntimeId() {
        return agentRuntimeId;
    }

    public void setAgentRuntimeId(String agentRuntimeId) {
        this.agentRuntimeId = agentRuntimeId;
    }

    public String getAgentRuntimeName() {
        return agentRuntimeName;
    }

    public void setAgentRuntimeName(String agentRuntimeName) {
        this.agentRuntimeName = agentRuntimeName;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWorkloadIdentityArn() {
        return workloadIdentityArn;
    }

    public void setWorkloadIdentityArn(String workloadIdentityArn) {
        this.workloadIdentityArn = workloadIdentityArn;
    }

    public int getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(int latestVersion) {
        this.latestVersion = latestVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(Instant lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public JsonNode getAgentRuntimeArtifact() {
        return agentRuntimeArtifact;
    }

    public void setAgentRuntimeArtifact(JsonNode agentRuntimeArtifact) {
        this.agentRuntimeArtifact = agentRuntimeArtifact;
    }

    public JsonNode getNetworkConfiguration() {
        return networkConfiguration;
    }

    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public JsonNode getAuthorizerConfiguration() {
        return authorizerConfiguration;
    }

    public void setAuthorizerConfiguration(JsonNode authorizerConfiguration) {
        this.authorizerConfiguration = authorizerConfiguration;
    }

    public JsonNode getProtocolConfiguration() {
        return protocolConfiguration;
    }

    public void setProtocolConfiguration(JsonNode protocolConfiguration) {
        this.protocolConfiguration = protocolConfiguration;
    }

    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public List<AgentRuntimeVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<AgentRuntimeVersion> versions) {
        this.versions = versions;
    }

    public List<AgentRuntimeEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<AgentRuntimeEndpoint> endpoints) {
        this.endpoints = endpoints;
    }
}
