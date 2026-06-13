package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchTimeout {
    private Integer attemptDurationSeconds;

    public Integer getAttemptDurationSeconds() {
        return attemptDurationSeconds;
    }

    public void setAttemptDurationSeconds(Integer attemptDurationSeconds) {
        this.attemptDurationSeconds = attemptDurationSeconds;
    }
}
