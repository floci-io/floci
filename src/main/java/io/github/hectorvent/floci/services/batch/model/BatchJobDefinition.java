package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchJobDefinition {
    private String jobDefinitionName;
    private String jobDefinitionArn;
    private int revision;
    private String status;
    private String type;
    private BatchContainerProperties containerProperties;
    private Map<String, String> parameters = new HashMap<>();
    private BatchRetryStrategy retryStrategy;
    private BatchTimeout timeout;
    private List<String> platformCapabilities = List.of();
    private Map<String, String> tags = new HashMap<>();
    private long createdAt;
    private String region;
    private String accountId;

    public String getJobDefinitionName() {
        return jobDefinitionName;
    }

    public void setJobDefinitionName(String jobDefinitionName) {
        this.jobDefinitionName = jobDefinitionName;
    }

    public String getJobDefinitionArn() {
        return jobDefinitionArn;
    }

    public void setJobDefinitionArn(String jobDefinitionArn) {
        this.jobDefinitionArn = jobDefinitionArn;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BatchContainerProperties getContainerProperties() {
        return containerProperties;
    }

    public void setContainerProperties(BatchContainerProperties containerProperties) {
        this.containerProperties = containerProperties;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters != null ? parameters : new HashMap<>();
    }

    public BatchRetryStrategy getRetryStrategy() {
        return retryStrategy;
    }

    public void setRetryStrategy(BatchRetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
    }

    public BatchTimeout getTimeout() {
        return timeout;
    }

    public void setTimeout(BatchTimeout timeout) {
        this.timeout = timeout;
    }

    public List<String> getPlatformCapabilities() {
        return platformCapabilities;
    }

    public void setPlatformCapabilities(List<String> platformCapabilities) {
        this.platformCapabilities = platformCapabilities != null ? platformCapabilities : List.of();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
