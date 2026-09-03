package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

@RegisterForReflection
public class LastCrawlInfo {
    @JsonProperty("ErrorMessage")
    private String errorMessage;

    @JsonProperty("LogGroup")
    private String logGroup;

    @JsonProperty("LogStream")
    private String logStream;

    @JsonProperty("MessagePrefix")
    private String messagePrefix;

    @JsonProperty("StartTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant startTime;

    @JsonProperty("Status")
    private String status;

    public LastCrawlInfo() {}

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getLogGroup() { return logGroup; }
    public void setLogGroup(String logGroup) { this.logGroup = logGroup; }

    public String getLogStream() { return logStream; }
    public void setLogStream(String logStream) { this.logStream = logStream; }

    public String getMessagePrefix() { return messagePrefix; }
    public void setMessagePrefix(String messagePrefix) { this.messagePrefix = messagePrefix; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
