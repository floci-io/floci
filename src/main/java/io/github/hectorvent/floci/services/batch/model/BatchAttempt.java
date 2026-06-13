package io.github.hectorvent.floci.services.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchAttempt {
    private Long startedAt;
    private Long stoppedAt;
    private String statusReason;
    private BatchAttemptContainer container;

    public Long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(Long stoppedAt) {
        this.stoppedAt = stoppedAt;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public BatchAttemptContainer getContainer() {
        return container;
    }

    public void setContainer(BatchAttemptContainer container) {
        this.container = container;
    }
}
