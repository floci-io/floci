package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Schedule {
    @JsonProperty("ScheduleExpression")
    private String scheduleExpression;

    @JsonProperty("State")
    private String state;

    public Schedule() {}

    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
