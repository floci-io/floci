package io.github.hectorvent.floci.services.cloudformation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StackInstance {
    private String stackSetId;
    private String stackSetName;
    private String account;
    private String region;
    /** Name of the backing single stack that materializes this instance's resources. */
    private String stackName;
    private String stackId;
    /** Drift status (CURRENT, OUTDATED, INOPERABLE). */
    private String status = "CURRENT";
    /** Last operation status (SUCCEEDED, FAILED, ...) reported under StackInstanceStatus.DetailedStatus. */
    private String detailedStatus = "SUCCEEDED";
    private String statusReason;

    public String getStackSetId() { return stackSetId; }
    public void setStackSetId(String stackSetId) { this.stackSetId = stackSetId; }
    public String getStackSetName() { return stackSetName; }
    public void setStackSetName(String stackSetName) { this.stackSetName = stackSetName; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getStackId() { return stackId; }
    public void setStackId(String stackId) { this.stackId = stackId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDetailedStatus() { return detailedStatus; }
    public void setDetailedStatus(String detailedStatus) { this.detailedStatus = detailedStatus; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
}
