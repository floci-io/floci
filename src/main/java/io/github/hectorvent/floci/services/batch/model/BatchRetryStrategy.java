package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchRetryStrategy {
    private Integer attempts;
    private List<Map<String, Object>> evaluateOnExit = new ArrayList<>();

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public List<Map<String, Object>> getEvaluateOnExit() {
        return evaluateOnExit;
    }

    public void setEvaluateOnExit(List<Map<String, Object>> evaluateOnExit) {
        this.evaluateOnExit = evaluateOnExit != null ? evaluateOnExit : new ArrayList<>();
    }
}
