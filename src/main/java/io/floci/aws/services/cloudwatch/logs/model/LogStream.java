package io.floci.aws.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogStream {

    private String logGroupName;
    private String logStreamName;
    private long createdTime;
    private Long firstEventTimestamp;
    private Long lastEventTimestamp;
    private long lastIngestionTime;
    private String uploadSequenceToken;
    private long storedBytes;

    public LogStream() {}

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public String getLogStreamName() { return logStreamName; }
    public void setLogStreamName(String logStreamName) { this.logStreamName = logStreamName; }

    public long getCreatedTime() { return createdTime; }
    public void setCreatedTime(long createdTime) { this.createdTime = createdTime; }

    public Long getFirstEventTimestamp() { return firstEventTimestamp; }
    public void setFirstEventTimestamp(Long firstEventTimestamp) { this.firstEventTimestamp = firstEventTimestamp; }

    public Long getLastEventTimestamp() { return lastEventTimestamp; }
    public void setLastEventTimestamp(Long lastEventTimestamp) { this.lastEventTimestamp = lastEventTimestamp; }

    public long getLastIngestionTime() { return lastIngestionTime; }
    public void setLastIngestionTime(long lastIngestionTime) { this.lastIngestionTime = lastIngestionTime; }

    public String getUploadSequenceToken() { return uploadSequenceToken; }
    public void setUploadSequenceToken(String uploadSequenceToken) { this.uploadSequenceToken = uploadSequenceToken; }

    public long getStoredBytes() { return storedBytes; }
    public void setStoredBytes(long storedBytes) { this.storedBytes = storedBytes; }
}
