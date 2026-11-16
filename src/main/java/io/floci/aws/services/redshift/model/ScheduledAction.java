package io.floci.aws.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A scheduled action of a provisioned cluster. The activation and invocation members are internal
 * bookkeeping of the sweep: no AWS response shows them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledAction {

    public static final String PAUSE_CLUSTER = "PauseCluster";
    public static final String RESUME_CLUSTER = "ResumeCluster";

    private String scheduledActionName;
    private String iamRole;
    private String schedule;
    private String description;
    private String targetActionType;
    private String clusterIdentifier;
    private boolean enabled = true;
    private Instant startTime;
    private Instant endTime;
    private Instant activatedAt;
    private Instant lastClaimedInvocation;
    private Instant lastCompletedInvocation;
    private String lastFailure;

    public ScheduledAction() {
    }

    public ScheduledAction(ScheduledAction other) {
        this.scheduledActionName = other.scheduledActionName;
        this.iamRole = other.iamRole;
        this.schedule = other.schedule;
        this.description = other.description;
        this.targetActionType = other.targetActionType;
        this.clusterIdentifier = other.clusterIdentifier;
        this.enabled = other.enabled;
        this.startTime = other.startTime;
        this.endTime = other.endTime;
        this.activatedAt = other.activatedAt;
        this.lastClaimedInvocation = other.lastClaimedInvocation;
        this.lastCompletedInvocation = other.lastCompletedInvocation;
        this.lastFailure = other.lastFailure;
    }

    public String getScheduledActionName() { return scheduledActionName; }
    public void setScheduledActionName(String value) { scheduledActionName = value; }

    public String getIamRole() { return iamRole; }
    public void setIamRole(String value) { iamRole = value; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String value) { schedule = value; }

    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }

    /** {@link #PAUSE_CLUSTER} or {@link #RESUME_CLUSTER}. */
    public String getTargetActionType() { return targetActionType; }
    public void setTargetActionType(String value) { targetActionType = value; }

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String value) { clusterIdentifier = value; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant value) { startTime = value; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant value) { endTime = value; }

    /** When the current schedule began counting, so no invocation before it is replayed. */
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant value) { activatedAt = value; }

    /** The latest scheduled time that was attempted; it is never attempted twice. */
    public Instant getLastClaimedInvocation() { return lastClaimedInvocation; }
    public void setLastClaimedInvocation(Instant value) { lastClaimedInvocation = value; }

    public Instant getLastCompletedInvocation() { return lastCompletedInvocation; }
    public void setLastCompletedInvocation(Instant value) { lastCompletedInvocation = value; }

    public String getLastFailure() { return lastFailure; }
    public void setLastFailure(String value) { lastFailure = value; }
}
