package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Command {

    private String commandId;
    private String documentName;
    private String documentVersion;
    private String comment;
    private Instant expiresAfter;
    private Map<String, List<String>> parameters;
    private List<String> instanceIds;
    private Instant requestedDateTime;
    private String status = "Pending";
    private String statusDetails = "Pending";
    private int timeoutSeconds = 3600;
    private int targetCount;
    private int completedCount;
    private int errorCount;
    private String outputS3BucketName;
    private String outputS3KeyPrefix;
    private String outputS3Region;
    private String region;

    public Command() {}

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }

    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }

    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Instant getExpiresAfter() { return expiresAfter; }
    public void setExpiresAfter(Instant expiresAfter) { this.expiresAfter = expiresAfter; }

    public Map<String, List<String>> getParameters() { return parameters; }
    public void setParameters(Map<String, List<String>> parameters) { this.parameters = parameters; }

    public List<String> getInstanceIds() { return instanceIds; }
    public void setInstanceIds(List<String> instanceIds) { this.instanceIds = instanceIds; }

    public Instant getRequestedDateTime() { return requestedDateTime; }
    public void setRequestedDateTime(Instant requestedDateTime) { this.requestedDateTime = requestedDateTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusDetails() { return statusDetails; }
    public void setStatusDetails(String statusDetails) { this.statusDetails = statusDetails; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }

    public int getCompletedCount() { return completedCount; }
    public void setCompletedCount(int completedCount) { this.completedCount = completedCount; }

    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

    public String getOutputS3BucketName() { return outputS3BucketName; }
    public void setOutputS3BucketName(String outputS3BucketName) { this.outputS3BucketName = outputS3BucketName; }

    public String getOutputS3KeyPrefix() { return outputS3KeyPrefix; }
    public void setOutputS3KeyPrefix(String outputS3KeyPrefix) { this.outputS3KeyPrefix = outputS3KeyPrefix; }

    public String getOutputS3Region() { return outputS3Region; }
    public void setOutputS3Region(String outputS3Region) { this.outputS3Region = outputS3Region; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
