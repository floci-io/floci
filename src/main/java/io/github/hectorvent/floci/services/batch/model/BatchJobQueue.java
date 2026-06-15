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
public class BatchJobQueue {
    private String jobQueueName;
    private String jobQueueArn;
    private String state;
    private String status;
    private String statusReason;
    private int priority;
    private String jobQueueType;
    private List<BatchComputeEnvironmentOrder> computeEnvironmentOrder = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();
    private long createdAt;
    private String region;
    private String accountId;

    public String getJobQueueName() {
        return jobQueueName;
    }

    public void setJobQueueName(String jobQueueName) {
        this.jobQueueName = jobQueueName;
    }

    public String getJobQueueArn() {
        return jobQueueArn;
    }

    public void setJobQueueArn(String jobQueueArn) {
        this.jobQueueArn = jobQueueArn;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getJobQueueType() {
        return jobQueueType;
    }

    public void setJobQueueType(String jobQueueType) {
        this.jobQueueType = jobQueueType;
    }

    public List<BatchComputeEnvironmentOrder> getComputeEnvironmentOrder() {
        return computeEnvironmentOrder;
    }

    public void setComputeEnvironmentOrder(List<BatchComputeEnvironmentOrder> computeEnvironmentOrder) {
        this.computeEnvironmentOrder = computeEnvironmentOrder != null ? computeEnvironmentOrder : new ArrayList<>();
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
