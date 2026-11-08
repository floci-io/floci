package io.floci.aws.services.emrserverless.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ListJobRunsResponse {
    private List<JobRunSummary> jobRuns;
    private String nextToken;

    public List<JobRunSummary> getJobRuns() { return jobRuns; }
    public void setJobRuns(List<JobRunSummary> jobRuns) { this.jobRuns = jobRuns; }
    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
