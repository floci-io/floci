package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetJobResponse {
    @JsonProperty("Job")
    private Job job;

    public GetJobResponse() {}

    public GetJobResponse(Job job) {
        this.job = job;
    }

    public Job getJob() { return job; }
    public void setJob(Job job) { this.job = job; }
}
