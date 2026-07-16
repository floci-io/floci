package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of an AgentCore Runtime at a specific version. Each
 * {@code UpdateAgentRuntime} appends a new snapshot; prior versions remain
 * retrievable via {@code GetAgentRuntime?version=} and {@code ListAgentRuntimeVersions}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRuntimeVersion {

    private String version;
    private Instant createdAt;
    private String roleArn;
    private String description;
    private JsonNode agentRuntimeArtifact;
    private JsonNode networkConfiguration;
    private JsonNode authorizerConfiguration;
    private JsonNode protocolConfiguration;
    private Map<String, String> environmentVariables;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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
}
