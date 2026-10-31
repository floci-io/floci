package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Redshift Serverless scheduled action. The activation and invocation members are internal: the
 * scheduler keeps them, and no AWS response shows them. The same type carries an update request, in which a null member
 * means the caller left it out.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScheduledAction {

    private String scheduledActionName;
    private String scheduledActionUuid;
    private String namespaceName;
    private String roleArn;
    private String description;
    private Boolean enabled;
    private Instant startTime;
    private Instant endTime;
    private Instant scheduleAt;
    private String scheduleCron;
    private String targetNamespaceName;
    private String snapshotNamePrefix;
    private Integer retentionPeriod;
    private Map<String, String> snapshotTags;
    private String ownerId;
    private boolean scheduleSpecified;
    private boolean targetSpecified;
    private Instant activatedAt;
    private Instant lastClaimedInvocation;
    private Instant lastCompletedInvocation;
    private String lastFailure;

    public ScheduledAction() {
    }

    public ScheduledAction(ScheduledAction other) {
        this.ownerId = other.ownerId;
        this.activatedAt = other.activatedAt;
        this.lastClaimedInvocation = other.lastClaimedInvocation;
        this.lastCompletedInvocation = other.lastCompletedInvocation;
        this.lastFailure = other.lastFailure;
        this.scheduleSpecified = other.scheduleSpecified;
        this.targetSpecified = other.targetSpecified;
        this.scheduledActionName = other.scheduledActionName;
        this.scheduledActionUuid = other.scheduledActionUuid;
        this.namespaceName = other.namespaceName;
        this.roleArn = other.roleArn;
        this.description = other.description;
        this.enabled = other.enabled;
        this.startTime = other.startTime;
        this.endTime = other.endTime;
        this.scheduleAt = other.scheduleAt;
        this.scheduleCron = other.scheduleCron;
        this.targetNamespaceName = other.targetNamespaceName;
        this.snapshotNamePrefix = other.snapshotNamePrefix;
        this.retentionPeriod = other.retentionPeriod;
        this.snapshotTags = other.snapshotTags == null ? null : new LinkedHashMap<>(other.snapshotTags);
    }

    public String getScheduledActionName() {
        return scheduledActionName;
    }

    public void setScheduledActionName(String scheduledActionName) {
        this.scheduledActionName = scheduledActionName;
    }

    public String getScheduledActionUuid() {
        return scheduledActionUuid;
    }

    public void setScheduledActionUuid(String scheduledActionUuid) {
        this.scheduledActionUuid = scheduledActionUuid;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Instant getScheduleAt() {
        return scheduleAt;
    }

    public void setScheduleAt(Instant scheduleAt) {
        this.scheduleAt = scheduleAt;
    }

    public String getScheduleCron() {
        return scheduleCron;
    }

    public void setScheduleCron(String scheduleCron) {
        this.scheduleCron = scheduleCron;
    }

    public String getTargetNamespaceName() {
        return targetNamespaceName;
    }

    public void setTargetNamespaceName(String targetNamespaceName) {
        this.targetNamespaceName = targetNamespaceName;
    }

    public String getSnapshotNamePrefix() {
        return snapshotNamePrefix;
    }

    public void setSnapshotNamePrefix(String snapshotNamePrefix) {
        this.snapshotNamePrefix = snapshotNamePrefix;
    }

    public Integer getRetentionPeriod() {
        return retentionPeriod;
    }

    public void setRetentionPeriod(Integer retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public Map<String, String> getSnapshotTags() {
        return snapshotTags;
    }

    public void setSnapshotTags(Map<String, String> snapshotTags) {
        this.snapshotTags = snapshotTags;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isScheduleSpecified() {
        return scheduleSpecified;
    }

    public void setScheduleSpecified(boolean scheduleSpecified) {
        this.scheduleSpecified = scheduleSpecified;
    }

    public boolean isTargetSpecified() {
        return targetSpecified;
    }

    public void setTargetSpecified(boolean targetSpecified) {
        this.targetSpecified = targetSpecified;
    }

    /** When the current schedule began counting, so no invocation before it is replayed. */
    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    /** The latest scheduled time whose snapshot was started; it is never attempted twice. */
    public Instant getLastClaimedInvocation() {
        return lastClaimedInvocation;
    }

    public void setLastClaimedInvocation(Instant lastClaimedInvocation) {
        this.lastClaimedInvocation = lastClaimedInvocation;
    }

    public Instant getLastCompletedInvocation() {
        return lastCompletedInvocation;
    }

    public void setLastCompletedInvocation(Instant lastCompletedInvocation) {
        this.lastCompletedInvocation = lastCompletedInvocation;
    }

    public String getLastFailure() {
        return lastFailure;
    }

    public void setLastFailure(String lastFailure) {
        this.lastFailure = lastFailure;
    }
}
