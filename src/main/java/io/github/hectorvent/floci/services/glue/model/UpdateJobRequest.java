package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class UpdateJobRequest {
    @JsonProperty("JobName")
    private String jobName;

    @JsonProperty("JobUpdate")
    private JobUpdate jobUpdate;

    public UpdateJobRequest() {}

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public JobUpdate getJobUpdate() { return jobUpdate; }
    public void setJobUpdate(JobUpdate jobUpdate) { this.jobUpdate = jobUpdate; }
}
