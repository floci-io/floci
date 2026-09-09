package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A timer started by a StartTimer decision, pending until its fire time passes. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfTimer {

    private String timerId;
    private String control;
    private String startToFireTimeout;
    private long startedEventId;
    private long decisionTaskCompletedEventId;
    private double startedTimestamp;
    private double fireTimestamp;
    private boolean canceled;

    public String getTimerId() {
        return timerId;
    }

    public void setTimerId(String timerId) {
        this.timerId = timerId;
    }

    public String getControl() {
        return control;
    }

    public void setControl(String control) {
        this.control = control;
    }

    public String getStartToFireTimeout() {
        return startToFireTimeout;
    }

    public void setStartToFireTimeout(String startToFireTimeout) {
        this.startToFireTimeout = startToFireTimeout;
    }

    public long getStartedEventId() {
        return startedEventId;
    }

    public void setStartedEventId(long startedEventId) {
        this.startedEventId = startedEventId;
    }

    public long getDecisionTaskCompletedEventId() {
        return decisionTaskCompletedEventId;
    }

    public void setDecisionTaskCompletedEventId(long decisionTaskCompletedEventId) {
        this.decisionTaskCompletedEventId = decisionTaskCompletedEventId;
    }

    public double getStartedTimestamp() {
        return startedTimestamp;
    }

    public void setStartedTimestamp(double startedTimestamp) {
        this.startedTimestamp = startedTimestamp;
    }

    public double getFireTimestamp() {
        return fireTimestamp;
    }

    public void setFireTimestamp(double fireTimestamp) {
        this.fireTimestamp = fireTimestamp;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
