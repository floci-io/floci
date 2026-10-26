package io.github.hectorvent.floci.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListJobRunAttemptsResponse {
    private List<JobRunAttemptSummary> jobRunAttempts;
    private String nextToken;

    public List<JobRunAttemptSummary> getJobRunAttempts() { return jobRunAttempts; }
    public void setJobRunAttempts(List<JobRunAttemptSummary> jobRunAttempts) { this.jobRunAttempts = jobRunAttempts; }
    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
