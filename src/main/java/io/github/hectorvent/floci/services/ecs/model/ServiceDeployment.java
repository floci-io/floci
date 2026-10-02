package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class ServiceDeployment {

    private String serviceDeploymentArn;
    private String serviceArn;
    private String clusterArn;
    /**
     * Internal only. AWS's {@code ServiceDeployment} shape has no {@code taskDefinition} member:
     * a caller reaches the task definition through {@link #targetServiceRevisionArn}, so this is
     * never written to the wire.
     */
    private String taskDefinition;
    private String status;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    /** When a deployment that ended without completing was stopped. */
    private Instant stoppedAt;
    /** Why the deployment is in its status; set when it is stopped. */
    private String statusReason;
    private Instant updatedAt;
    private String targetServiceRevisionArn;
    private List<String> sourceServiceRevisionArns;
    /** Internal revision restored if this deployment rolls back. */
    private String rollbackTargetServiceRevisionArn;
    private Instant rollbackStartedAt;
    private String rollbackReason;
    private List<String> alarmNames;
    private List<String> triggeredAlarmNames;
    private String alarmStatus;
    /** Tasks the circuit breaker has counted as failing to start. */
    private int failedTasks;

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public String getTargetServiceRevisionArn() { return targetServiceRevisionArn; }
    public void setTargetServiceRevisionArn(String targetServiceRevisionArn) {
        this.targetServiceRevisionArn = targetServiceRevisionArn;
    }

    public List<String> getSourceServiceRevisionArns() { return sourceServiceRevisionArns; }
    public void setSourceServiceRevisionArns(List<String> sourceServiceRevisionArns) {
        this.sourceServiceRevisionArns = sourceServiceRevisionArns;
    }

    public String getRollbackTargetServiceRevisionArn() { return rollbackTargetServiceRevisionArn; }
    public void setRollbackTargetServiceRevisionArn(String rollbackTargetServiceRevisionArn) {
        this.rollbackTargetServiceRevisionArn = rollbackTargetServiceRevisionArn;
    }

    public Instant getRollbackStartedAt() { return rollbackStartedAt; }
    public void setRollbackStartedAt(Instant rollbackStartedAt) { this.rollbackStartedAt = rollbackStartedAt; }

    public String getRollbackReason() { return rollbackReason; }
    public void setRollbackReason(String rollbackReason) { this.rollbackReason = rollbackReason; }

    public List<String> getAlarmNames() { return alarmNames; }
    public void setAlarmNames(List<String> alarmNames) { this.alarmNames = alarmNames; }

    public List<String> getTriggeredAlarmNames() { return triggeredAlarmNames; }
    public void setTriggeredAlarmNames(List<String> triggeredAlarmNames) {
        this.triggeredAlarmNames = triggeredAlarmNames;
    }

    public String getAlarmStatus() { return alarmStatus; }
    public void setAlarmStatus(String alarmStatus) { this.alarmStatus = alarmStatus; }

    public int getFailedTasks() { return failedTasks; }
    public void setFailedTasks(int failedTasks) { this.failedTasks = failedTasks; }

    public String getServiceDeploymentArn() { return serviceDeploymentArn; }
    public void setServiceDeploymentArn(String serviceDeploymentArn) { this.serviceDeploymentArn = serviceDeploymentArn; }

    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String serviceArn) { this.serviceArn = serviceArn; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
