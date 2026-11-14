package io.floci.aws.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A request to restore one table from a snapshot, and how it went. Shared by Redshift, which reports the
 * cluster, and Redshift Serverless, which reports the namespace and workgroup.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableRestoreStatus {

    public static final String PENDING = "PENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private String tableRestoreRequestId;
    private String status;
    private String message;
    private Instant requestTime;
    private String clusterIdentifier;
    private String namespaceName;
    private String workgroupName;
    private String snapshotIdentifier;
    private String sourceDatabaseName;
    private String sourceSchemaName;
    private String sourceTableName;
    private String targetDatabaseName;
    private String targetSchemaName;
    private String newTableName;
    private Long progressInMegaBytes;
    private Long totalDataInMegaBytes;

    public TableRestoreStatus() {
    }

    public TableRestoreStatus(TableRestoreStatus other) {
        this.tableRestoreRequestId = other.tableRestoreRequestId;
        this.status = other.status;
        this.message = other.message;
        this.requestTime = other.requestTime;
        this.clusterIdentifier = other.clusterIdentifier;
        this.namespaceName = other.namespaceName;
        this.workgroupName = other.workgroupName;
        this.snapshotIdentifier = other.snapshotIdentifier;
        this.sourceDatabaseName = other.sourceDatabaseName;
        this.sourceSchemaName = other.sourceSchemaName;
        this.sourceTableName = other.sourceTableName;
        this.targetDatabaseName = other.targetDatabaseName;
        this.targetSchemaName = other.targetSchemaName;
        this.newTableName = other.newTableName;
        this.progressInMegaBytes = other.progressInMegaBytes;
        this.totalDataInMegaBytes = other.totalDataInMegaBytes;
    }

    public String getTableRestoreRequestId() { return tableRestoreRequestId; }
    public void setTableRestoreRequestId(String value) { tableRestoreRequestId = value; }

    /** One of {@link #PENDING}, {@link #IN_PROGRESS}, {@link #SUCCEEDED} or {@link #FAILED}. */
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }

    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }

    public Instant getRequestTime() { return requestTime; }
    public void setRequestTime(Instant value) { requestTime = value; }

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String value) { clusterIdentifier = value; }

    public String getNamespaceName() { return namespaceName; }
    public void setNamespaceName(String value) { namespaceName = value; }

    public String getWorkgroupName() { return workgroupName; }
    public void setWorkgroupName(String value) { workgroupName = value; }

    public String getSnapshotIdentifier() { return snapshotIdentifier; }
    public void setSnapshotIdentifier(String value) { snapshotIdentifier = value; }

    public String getSourceDatabaseName() { return sourceDatabaseName; }
    public void setSourceDatabaseName(String value) { sourceDatabaseName = value; }

    public String getSourceSchemaName() { return sourceSchemaName; }
    public void setSourceSchemaName(String value) { sourceSchemaName = value; }

    public String getSourceTableName() { return sourceTableName; }
    public void setSourceTableName(String value) { sourceTableName = value; }

    public String getTargetDatabaseName() { return targetDatabaseName; }
    public void setTargetDatabaseName(String value) { targetDatabaseName = value; }

    public String getTargetSchemaName() { return targetSchemaName; }
    public void setTargetSchemaName(String value) { targetSchemaName = value; }

    public String getNewTableName() { return newTableName; }
    public void setNewTableName(String value) { newTableName = value; }

    public Long getProgressInMegaBytes() { return progressInMegaBytes; }
    public void setProgressInMegaBytes(Long value) { progressInMegaBytes = value; }

    public Long getTotalDataInMegaBytes() { return totalDataInMegaBytes; }
    public void setTotalDataInMegaBytes(Long value) { totalDataInMegaBytes = value; }
}
