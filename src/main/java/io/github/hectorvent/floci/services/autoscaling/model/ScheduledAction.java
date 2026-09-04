package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledAction {

    private String scheduledActionName;
    private String scheduledActionArn;
    private String autoScalingGroupName;
    private Instant startTime;
    private Instant endTime;
    private String recurrence;
    private String timeZone;
    private Integer minSize;
    private Integer maxSize;
    private Integer desiredCapacity;
    private String region;

    public ScheduledAction() {}

    public String getScheduledActionName() { return scheduledActionName; }
    public void setScheduledActionName(String v) { this.scheduledActionName = v; }

    public String getScheduledActionArn() { return scheduledActionArn; }
    public void setScheduledActionArn(String v) { this.scheduledActionArn = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant v) { this.startTime = v; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant v) { this.endTime = v; }

    public String getRecurrence() { return recurrence; }
    public void setRecurrence(String v) { this.recurrence = v; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String v) { this.timeZone = v; }

    public Integer getMinSize() { return minSize; }
    public void setMinSize(Integer v) { this.minSize = v; }

    public Integer getMaxSize() { return maxSize; }
    public void setMaxSize(Integer v) { this.maxSize = v; }

    public Integer getDesiredCapacity() { return desiredCapacity; }
    public void setDesiredCapacity(Integer v) { this.desiredCapacity = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
}
