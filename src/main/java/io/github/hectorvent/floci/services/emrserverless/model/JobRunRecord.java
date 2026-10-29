package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A stored job run: the run as GetJobRun reports it, plus what only Floci needs. The client token
 * and the request it came with decide whether a repeated StartJobRun is a retry or a conflict; the
 * application type fills ListJobRuns' {@code type}. A container run is one the {@code docker} runner
 * mode runs, whose state its container decides. A run retried after a failed attempt keeps each earlier
 * attempt as GetJobRun reports it for that attempt, oldest first; {@code jobRun} is the latest.
 */
@RegisterForReflection
public class JobRunRecord {
    private JobRun jobRun;
    private String clientToken;
    private JsonNode request;
    private String applicationType;
    private boolean containerRun;
    private List<JobRun> earlierAttempts = new ArrayList<>();

    public JobRun getJobRun() { return jobRun; }
    public void setJobRun(JobRun jobRun) { this.jobRun = jobRun; }
    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
    public JsonNode getRequest() { return request; }
    public void setRequest(JsonNode request) { this.request = request; }
    public String getApplicationType() { return applicationType; }
    public void setApplicationType(String applicationType) { this.applicationType = applicationType; }
    public boolean isContainerRun() { return containerRun; }
    public void setContainerRun(boolean containerRun) { this.containerRun = containerRun; }
    public List<JobRun> getEarlierAttempts() { return earlierAttempts; }
    public void setEarlierAttempts(List<JobRun> earlierAttempts) { this.earlierAttempts = earlierAttempts; }
}
