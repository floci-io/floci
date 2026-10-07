package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalingActivity {

    private String activityId;
    private String autoScalingGroupName;
    private String description;
    private String cause;
    private Instant startTime;
    private Instant endTime;
    private String statusCode;  // InProgress | Successful | Failed | Cancelled
    private String statusMessage;
    private int progress;       // 0-100
    // The Auto Scaling group's region; null for activities persisted before it was recorded.
    private String region;

    public ScalingActivity() {}

    public String getActivityId() { return activityId; }
    public void setActivityId(String v) { this.activityId = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getCause() { return cause; }
    public void setCause(String v) { this.cause = v; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant v) { this.startTime = v; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant v) { this.endTime = v; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String v) { this.statusCode = v; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String v) { this.statusMessage = v; }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    public int getProgress() { return progress; }
    public void setProgress(int v) { this.progress = v; }
}
