package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ExecutionProperty {
    @JsonProperty("MaxConcurrentRuns")
    private Integer maxConcurrentRuns;

    public ExecutionProperty() {}

    public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
}
