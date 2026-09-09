package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DeleteJobRequest {
    @JsonProperty("JobName")
    private String jobName;

    public DeleteJobRequest() {}

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
}
