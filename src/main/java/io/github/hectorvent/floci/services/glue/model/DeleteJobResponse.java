package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DeleteJobResponse {
    @JsonProperty("JobName")
    private String jobName;

    public DeleteJobResponse() {}

    public DeleteJobResponse(String jobName) {
        this.jobName = jobName;
    }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
}
