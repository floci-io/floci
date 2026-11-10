package io.floci.aws.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** A retained one-off scheduled action and its execution state. */
@RegisterForReflection
public class ScheduledAction {
    @RegisterForReflection
    public enum State { PENDING, CLAIMED, SUCCEEDED, FAILED, SKIPPED }

    private String serviceNamespace;
    private String resourceId;
    private String scalableDimension;
    private String scheduledActionName;
    private String scheduledActionArn;
    private String schedule;
    private String timezone;
    private Instant deadline;
    private Integer minCapacity;
    private Integer maxCapacity;
    private double creationTime;
    private State state;

    public ScheduledAction() {}

    public ScheduledAction(ScheduledAction source) {
        this.serviceNamespace = source.serviceNamespace;
        this.resourceId = source.resourceId;
        this.scalableDimension = source.scalableDimension;
        this.scheduledActionName = source.scheduledActionName;
        this.scheduledActionArn = source.scheduledActionArn;
        this.schedule = source.schedule;
        this.timezone = source.timezone;
        this.deadline = source.deadline;
        this.minCapacity = source.minCapacity;
        this.maxCapacity = source.maxCapacity;
        this.creationTime = source.creationTime;
        this.state = source.state;
    }

    public String getServiceNamespace() { return serviceNamespace; }
    public void setServiceNamespace(String value) { this.serviceNamespace = value; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String value) { this.resourceId = value; }

    public String getScalableDimension() { return scalableDimension; }
    public void setScalableDimension(String value) { this.scalableDimension = value; }

    public String getScheduledActionName() { return scheduledActionName; }
    public void setScheduledActionName(String value) { this.scheduledActionName = value; }

    public String getScheduledActionArn() { return scheduledActionArn; }
    public void setScheduledActionArn(String value) { this.scheduledActionArn = value; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String value) { this.schedule = value; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String value) { this.timezone = value; }

    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant value) { this.deadline = value; }

    public Integer getMinCapacity() { return minCapacity; }
    public void setMinCapacity(Integer value) { this.minCapacity = value; }

    public Integer getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(Integer value) { this.maxCapacity = value; }

    public double getCreationTime() { return creationTime; }
    public void setCreationTime(double value) { this.creationTime = value; }

    public State getState() { return state; }
    public void setState(State value) { this.state = value; }
}
