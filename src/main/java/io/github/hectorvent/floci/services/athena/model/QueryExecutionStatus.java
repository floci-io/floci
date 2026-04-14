package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

@RegisterForReflection
public class QueryExecutionStatus {
    @JsonProperty("State")
    private QueryExecutionState state;
    @JsonProperty("StateChangeReason")
    private String stateChangeReason;
    @JsonProperty("SubmissionDateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant submissionDateTime;
    @JsonProperty("CompletionDateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant completionDateTime;

    public QueryExecutionStatus() {}
    public QueryExecutionStatus(QueryExecutionState state) {
        this.state = state;
        this.submissionDateTime = Instant.now();
    }

    public QueryExecutionState getState() { return state; }
    public void setState(QueryExecutionState state) { this.state = state; }
    public String getStateChangeReason() { return stateChangeReason; }
    public void setStateChangeReason(String stateChangeReason) { this.stateChangeReason = stateChangeReason; }
    public Instant getSubmissionDateTime() { return submissionDateTime; }
    public void setSubmissionDateTime(Instant submissionDateTime) { this.submissionDateTime = submissionDateTime; }
    public Instant getCompletionDateTime() { return completionDateTime; }
    public void setCompletionDateTime(Instant completionDateTime) { this.completionDateTime = completionDateTime; }
}
