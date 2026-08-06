package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
public class IotJobExecution {

    private String jobId;
    private String thingName;
    private String thingArn;
    private String status = "QUEUED";
    private Map<String, String> statusDetails = new HashMap<>();
    private Instant queuedAt;
    private Instant startedAt;
    private Instant lastUpdatedAt;
    private long executionNumber = 1L;
    private long versionNumber = 1L;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getThingName() { return thingName; }
    public void setThingName(String thingName) { this.thingName = thingName; }

    public String getThingArn() { return thingArn; }
    public void setThingArn(String thingArn) { this.thingArn = thingArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, String> getStatusDetails() { return statusDetails; }
    public void setStatusDetails(Map<String, String> statusDetails) { this.statusDetails = statusDetails == null ? new HashMap<>() : new HashMap<>(statusDetails); }

    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant queuedAt) { this.queuedAt = queuedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public long getExecutionNumber() { return executionNumber; }
    public void setExecutionNumber(long executionNumber) { this.executionNumber = executionNumber; }

    public long getVersionNumber() { return versionNumber; }
    public void setVersionNumber(long versionNumber) { this.versionNumber = versionNumber; }
}
