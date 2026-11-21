package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** StartSession's body. */
@RegisterForReflection
public class StartSessionRequest {
    private String clientToken;
    private String executionRoleArn;
    private Map<String, Object> configurationOverrides;
    private Map<String, String> tags;
    private Long idleTimeoutMinutes;
    private String name;

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }
    public Map<String, Object> getConfigurationOverrides() { return configurationOverrides; }
    public void setConfigurationOverrides(Map<String, Object> configurationOverrides) {
        this.configurationOverrides = configurationOverrides;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public Long getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
    public void setIdleTimeoutMinutes(Long idleTimeoutMinutes) { this.idleTimeoutMinutes = idleTimeoutMinutes; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
