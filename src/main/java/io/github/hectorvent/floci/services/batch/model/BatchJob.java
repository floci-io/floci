package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchJob {
    private String jobId;
    private String jobArn;
    private String jobName;
    private String jobQueue;
    private String jobQueueName;
    private String jobDefinition;
    private String jobDefinitionName;
    private int jobDefinitionRevision;
    private String status;
    private String statusReason;
    private long createdAt;
    private Long startedAt;
    private Long stoppedAt;
    private Map<String, String> parameters = new HashMap<>();
    private BatchContainerOverrides containerOverrides;
    private List<String> resolvedCommand = new ArrayList<>();
    private List<BatchKeyValue> resolvedEnvironment = new ArrayList<>();
    private List<BatchResourceRequirement> resourceRequirements = new ArrayList<>();
    private BatchRetryStrategy retryStrategy;
    private BatchTimeout timeout;
    private List<BatchAttempt> attempts = new ArrayList<>();
    private BatchAttemptContainer container = new BatchAttemptContainer();
    private Map<String, String> tags = new HashMap<>();
    private String region;
    private String accountId;
    private String containerImage;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobArn() {
        return jobArn;
    }

    public void setJobArn(String jobArn) {
        this.jobArn = jobArn;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getJobQueue() {
        return jobQueue;
    }

    public void setJobQueue(String jobQueue) {
        this.jobQueue = jobQueue;
    }

    public String getJobQueueName() {
        return jobQueueName;
    }

    public void setJobQueueName(String jobQueueName) {
        this.jobQueueName = jobQueueName;
    }

    public String getJobDefinition() {
        return jobDefinition;
    }

    public void setJobDefinition(String jobDefinition) {
        this.jobDefinition = jobDefinition;
    }

    public String getJobDefinitionName() {
        return jobDefinitionName;
    }

    public void setJobDefinitionName(String jobDefinitionName) {
        this.jobDefinitionName = jobDefinitionName;
    }

    public int getJobDefinitionRevision() {
        return jobDefinitionRevision;
    }

    public void setJobDefinitionRevision(int jobDefinitionRevision) {
        this.jobDefinitionRevision = jobDefinitionRevision;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(Long stoppedAt) {
        this.stoppedAt = stoppedAt;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters != null ? parameters : new HashMap<>();
    }

    public BatchContainerOverrides getContainerOverrides() {
        return containerOverrides;
    }

    public void setContainerOverrides(BatchContainerOverrides containerOverrides) {
        this.containerOverrides = containerOverrides;
    }

    public List<String> getResolvedCommand() {
        return resolvedCommand;
    }

    public void setResolvedCommand(List<String> resolvedCommand) {
        this.resolvedCommand = resolvedCommand != null ? resolvedCommand : new ArrayList<>();
    }

    public List<BatchKeyValue> getResolvedEnvironment() {
        return resolvedEnvironment;
    }

    public void setResolvedEnvironment(List<BatchKeyValue> resolvedEnvironment) {
        this.resolvedEnvironment = resolvedEnvironment != null ? resolvedEnvironment : new ArrayList<>();
    }

    public List<BatchResourceRequirement> getResourceRequirements() {
        return resourceRequirements;
    }

    public void setResourceRequirements(List<BatchResourceRequirement> resourceRequirements) {
        this.resourceRequirements = resourceRequirements != null ? resourceRequirements : new ArrayList<>();
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

    public List<BatchAttempt> getAttempts() {
        return attempts;
    }

    public void setAttempts(List<BatchAttempt> attempts) {
        this.attempts = attempts != null ? attempts : new ArrayList<>();
    }

    public BatchAttemptContainer getContainer() {
        return container;
    }

    public void setContainer(BatchAttemptContainer container) {
        this.container = container;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
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

    public String getContainerImage() {
        return containerImage;
    }

    public void setContainerImage(String containerImage) {
        this.containerImage = containerImage;
    }
}
