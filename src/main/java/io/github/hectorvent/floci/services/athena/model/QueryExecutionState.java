package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum QueryExecutionState {
    @JsonProperty("QUEUED")
    QUEUED,
    @JsonProperty("RUNNING")
    RUNNING,
    @JsonProperty("SUCCEEDED")
    SUCCEEDED,
    @JsonProperty("FAILED")
    FAILED,
    @JsonProperty("CANCELLED")
    CANCELLED
}
