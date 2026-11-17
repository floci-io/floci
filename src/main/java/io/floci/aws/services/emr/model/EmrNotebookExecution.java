package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A notebook execution, as DescribeNotebookExecution reports it, with the Region it is in, the service role and
 * relative path it was started with, and its place among the account's executions.
 */
@RegisterForReflection
public class EmrNotebookExecution {

    private String id;
    private String arn;
    private String editorId;
    private String relativePath;
    private String name;
    private String notebookParams;
    private String engineId;
    private String engineType;
    private String masterInstanceSecurityGroupId;
    private String executionRoleArn;
    private String serviceRole;
    private String notebookInstanceSecurityGroupId;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String notebookS3Bucket;
    private String notebookS3Key;
    private String outputNotebookS3Bucket;
    private String outputNotebookS3Key;
    private String outputNotebookFormat;
    private Map<String, String> environmentVariables;
    private String status;
    private Instant startTime;
    private Instant endTime;
    private String lastStateChangeReason;
    private String region;
    private long sequence;

    public EmrNotebookExecution() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getEditorId() { return editorId; }
    public void setEditorId(String editorId) { this.editorId = editorId; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNotebookParams() { return notebookParams; }
    public void setNotebookParams(String notebookParams) { this.notebookParams = notebookParams; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String engineId) { this.engineId = engineId; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public String getMasterInstanceSecurityGroupId() { return masterInstanceSecurityGroupId; }
    public void setMasterInstanceSecurityGroupId(String masterInstanceSecurityGroupId) {
        this.masterInstanceSecurityGroupId = masterInstanceSecurityGroupId;
    }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }
    public String getServiceRole() { return serviceRole; }
    public void setServiceRole(String serviceRole) { this.serviceRole = serviceRole; }
    public String getNotebookInstanceSecurityGroupId() { return notebookInstanceSecurityGroupId; }
    public void setNotebookInstanceSecurityGroupId(String notebookInstanceSecurityGroupId) {
        this.notebookInstanceSecurityGroupId = notebookInstanceSecurityGroupId;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getNotebookS3Bucket() { return notebookS3Bucket; }
    public void setNotebookS3Bucket(String notebookS3Bucket) { this.notebookS3Bucket = notebookS3Bucket; }
    public String getNotebookS3Key() { return notebookS3Key; }
    public void setNotebookS3Key(String notebookS3Key) { this.notebookS3Key = notebookS3Key; }
    public String getOutputNotebookS3Bucket() { return outputNotebookS3Bucket; }
    public void setOutputNotebookS3Bucket(String outputNotebookS3Bucket) {
        this.outputNotebookS3Bucket = outputNotebookS3Bucket;
    }
    public String getOutputNotebookS3Key() { return outputNotebookS3Key; }
    public void setOutputNotebookS3Key(String outputNotebookS3Key) { this.outputNotebookS3Key = outputNotebookS3Key; }
    public String getOutputNotebookFormat() { return outputNotebookFormat; }
    public void setOutputNotebookFormat(String outputNotebookFormat) {
        this.outputNotebookFormat = outputNotebookFormat;
    }
    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public String getLastStateChangeReason() { return lastStateChangeReason; }
    public void setLastStateChangeReason(String lastStateChangeReason) {
        this.lastStateChangeReason = lastStateChangeReason;
    }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
}
