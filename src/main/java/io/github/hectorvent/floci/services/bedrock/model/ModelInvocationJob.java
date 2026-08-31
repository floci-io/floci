package io.github.hectorvent.floci.services.bedrock.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelInvocationJob {

    @JsonProperty("jobArn")
    private String jobArn;

    @JsonProperty("jobName")
    private String jobName;

    @JsonProperty("modelId")
    private String modelId;

    @JsonProperty("clientRequestToken")
    private String clientRequestToken;

    @JsonProperty("roleArn")
    private String roleArn;

    @JsonProperty("status")
    private ModelInvocationJobStatus status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("submitTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant submitTime;

    @JsonProperty("lastModifiedTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant lastModifiedTime;

    @JsonProperty("endTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant endTime;

    @JsonProperty("jobExpirationTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant jobExpirationTime;

    @JsonProperty("timeoutDurationInHours")
    private Integer timeoutDurationInHours;

    @JsonProperty("inputDataConfig")
    private InputDataConfig inputDataConfig;

    @JsonProperty("outputDataConfig")
    private OutputDataConfig outputDataConfig;

    @JsonProperty("vpcConfig")
    private VpcConfig vpcConfig;

    @JsonProperty("tags")
    private List<Tag> tags;

    @JsonProperty("totalRecordCount")
    private Long totalRecordCount;

    @JsonProperty("processedRecordCount")
    private Long processedRecordCount;

    @JsonProperty("successRecordCount")
    private Long successRecordCount;

    @JsonProperty("errorRecordCount")
    private Long errorRecordCount;

    @JsonProperty("inputTokenCount")
    private Long inputTokenCount;

    @JsonProperty("outputTokenCount")
    private Long outputTokenCount;

    public ModelInvocationJob() {}

    public ModelInvocationJob(String jobArn, String jobName, String modelId, String clientRequestToken,
                              String roleArn, ModelInvocationJobStatus status, String message,
                              Instant submitTime, Instant lastModifiedTime, Instant endTime,
                              Instant jobExpirationTime, Integer timeoutDurationInHours,
                              InputDataConfig inputDataConfig, OutputDataConfig outputDataConfig,
                              VpcConfig vpcConfig, List<Tag> tags) {
        this.jobArn = jobArn;
        this.jobName = jobName;
        this.modelId = modelId;
        this.clientRequestToken = clientRequestToken;
        this.roleArn = roleArn;
        this.status = status;
        this.message = message;
        this.submitTime = submitTime;
        this.lastModifiedTime = lastModifiedTime;
        this.endTime = endTime;
        this.jobExpirationTime = jobExpirationTime;
        this.timeoutDurationInHours = timeoutDurationInHours;
        this.inputDataConfig = inputDataConfig;
        this.outputDataConfig = outputDataConfig;
        this.vpcConfig = vpcConfig;
        this.tags = tags;
    }

    public String getJobArn() {
        return jobArn;
    }

    @JsonIgnore
    public String getJobId() {
        if (jobArn == null) {
            return null;
        }
        int idx = jobArn.lastIndexOf('/');
        return idx != -1 ? jobArn.substring(idx + 1) : jobArn;
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

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getClientRequestToken() {
        return clientRequestToken;
    }

    public void setClientRequestToken(String clientRequestToken) {
        this.clientRequestToken = clientRequestToken;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public ModelInvocationJobStatus getStatus() {
        return status;
    }

    public void setStatus(ModelInvocationJobStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(Instant submitTime) {
        this.submitTime = submitTime;
    }

    public Instant getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(Instant lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Instant getJobExpirationTime() {
        return jobExpirationTime;
    }

    public void setJobExpirationTime(Instant jobExpirationTime) {
        this.jobExpirationTime = jobExpirationTime;
    }

    public Integer getTimeoutDurationInHours() {
        return timeoutDurationInHours;
    }

    public void setTimeoutDurationInHours(Integer timeoutDurationInHours) {
        this.timeoutDurationInHours = timeoutDurationInHours;
    }

    public InputDataConfig getInputDataConfig() {
        return inputDataConfig;
    }

    public void setInputDataConfig(InputDataConfig inputDataConfig) {
        this.inputDataConfig = inputDataConfig;
    }

    public OutputDataConfig getOutputDataConfig() {
        return outputDataConfig;
    }

    public void setOutputDataConfig(OutputDataConfig outputDataConfig) {
        this.outputDataConfig = outputDataConfig;
    }

    public VpcConfig getVpcConfig() {
        return vpcConfig;
    }

    public void setVpcConfig(VpcConfig vpcConfig) {
        this.vpcConfig = vpcConfig;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public Long getTotalRecordCount() {
        return totalRecordCount;
    }

    public void setTotalRecordCount(Long totalRecordCount) {
        this.totalRecordCount = totalRecordCount;
    }

    public Long getProcessedRecordCount() {
        return processedRecordCount;
    }

    public void setProcessedRecordCount(Long processedRecordCount) {
        this.processedRecordCount = processedRecordCount;
    }

    public Long getSuccessRecordCount() {
        return successRecordCount;
    }

    public void setSuccessRecordCount(Long successRecordCount) {
        this.successRecordCount = successRecordCount;
    }

    public Long getErrorRecordCount() {
        return errorRecordCount;
    }

    public void setErrorRecordCount(Long errorRecordCount) {
        this.errorRecordCount = errorRecordCount;
    }

    public Long getInputTokenCount() {
        return inputTokenCount;
    }

    public void setInputTokenCount(Long inputTokenCount) {
        this.inputTokenCount = inputTokenCount;
    }

    public Long getOutputTokenCount() {
        return outputTokenCount;
    }

    public void setOutputTokenCount(Long outputTokenCount) {
        this.outputTokenCount = outputTokenCount;
    }
}
