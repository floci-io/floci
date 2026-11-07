package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * StartJobRun's body. configurationOverrides, executionIamPolicy and retryPolicy are kept as sent
 * and returned by GetJobRun unchanged.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StartJobRunRequest {
    private String clientToken;
    private String executionRoleArn;
    private Map<String, Object> executionIamPolicy;
    private JobDriver jobDriver;
    private Map<String, Object> configurationOverrides;
    private Map<String, String> tags;
    private Long executionTimeoutMinutes;
    private String name;
    private String mode;
    private Map<String, Object> retryPolicy;

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }
    public Map<String, Object> getExecutionIamPolicy() { return executionIamPolicy; }
    public void setExecutionIamPolicy(Map<String, Object> executionIamPolicy) { this.executionIamPolicy = executionIamPolicy; }
    public JobDriver getJobDriver() { return jobDriver; }
    public void setJobDriver(JobDriver jobDriver) { this.jobDriver = jobDriver; }
    public Map<String, Object> getConfigurationOverrides() { return configurationOverrides; }
    public void setConfigurationOverrides(Map<String, Object> configurationOverrides) { this.configurationOverrides = configurationOverrides; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public Long getExecutionTimeoutMinutes() { return executionTimeoutMinutes; }
    public void setExecutionTimeoutMinutes(Long executionTimeoutMinutes) { this.executionTimeoutMinutes = executionTimeoutMinutes; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Map<String, Object> getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(Map<String, Object> retryPolicy) { this.retryPolicy = retryPolicy; }
}
