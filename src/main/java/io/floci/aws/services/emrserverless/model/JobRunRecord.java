package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stored job run: the run as GetJobRun reports it, plus what only Floci needs. The client token
 * and the request it came with decide whether a repeated StartJobRun is a retry or a conflict; the
 * application type fills ListJobRuns' {@code type}. A container run is one the {@code docker} runner
 * mode runs, whose state its container decides. A run retried after a failed attempt keeps each earlier
 * attempt as GetJobRun reports it for that attempt, oldest first; {@code jobRun} is the latest. Each attempt's
 * driver log destinations are kept as it was admitted, by attempt number. A container run cancelled with a grace
 * period keeps when that period ends, until which its driver may run on to shut down by itself.
 */
@RegisterForReflection
public class JobRunRecord {
    private JobRun jobRun;
    private String clientToken;
    // Orders it among those created within the same millisecond: EmrServerlessService.nextSequence.
    private long sequence;
    private JsonNode request;
    private String applicationType;
    private boolean containerRun;
    private List<JobRun> earlierAttempts = new ArrayList<>();
    private Map<Integer, DriverLogDestinations> driverLogs = new LinkedHashMap<>();
    private Instant shutdownDeadline;

    public JobRun getJobRun() { return jobRun; }
    public void setJobRun(JobRun jobRun) { this.jobRun = jobRun; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public JsonNode getRequest() { return request; }
    public void setRequest(JsonNode request) { this.request = request; }
    public String getApplicationType() { return applicationType; }
    public void setApplicationType(String applicationType) { this.applicationType = applicationType; }
    public boolean isContainerRun() { return containerRun; }
    public void setContainerRun(boolean containerRun) { this.containerRun = containerRun; }
    public List<JobRun> getEarlierAttempts() { return earlierAttempts; }
    public void setEarlierAttempts(List<JobRun> earlierAttempts) { this.earlierAttempts = earlierAttempts; }
    public Map<Integer, DriverLogDestinations> getDriverLogs() { return driverLogs; }
    public void setDriverLogs(Map<Integer, DriverLogDestinations> driverLogs) { this.driverLogs = driverLogs; }
    public Instant getShutdownDeadline() { return shutdownDeadline; }
    public void setShutdownDeadline(Instant shutdownDeadline) { this.shutdownDeadline = shutdownDeadline; }
}
