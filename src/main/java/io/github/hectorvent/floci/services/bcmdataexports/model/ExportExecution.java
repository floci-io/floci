package io.github.hectorvent.floci.services.bcmdataexports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Tracking record for a single execution of an {@link Export}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExportExecution {

    private String executionId;
    private String exportArn;
    private String exportStatus;        // INITIATION_IN_PROCESS | QUERY_QUEUED | QUERY_IN_PROCESS | QUERY_FAILURE | DELIVERY_IN_PROCESS | DELIVERY_SUCCESS | DELIVERY_FAILURE
    private String createdBy;           // USER | SCHEDULE
    private long createdAt;
    private long completedAt;
    private String statusReason;        // free-text on failure

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String v) { this.executionId = v; }

    public String getExportArn() { return exportArn; }
    public void setExportArn(String v) { this.exportArn = v; }

    public String getExportStatus() { return exportStatus; }
    public void setExportStatus(String v) { this.exportStatus = v; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long v) { this.completedAt = v; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String v) { this.statusReason = v; }
}
