package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A DB instance's automated backups as DescribeDBInstanceAutomatedBackups reports them. A live
 * instance's {@code active} backup is built from the instance when described. A {@code retained}
 * backup, kept when an instance is deleted with {@code DeleteAutomatedBackups=false}, and a
 * {@code replicating} backup in another Region are stored, with the instance as it was.
 */
@RegisterForReflection
public class DbInstanceAutomatedBackup {

    public static final String ACTIVE = "active";
    public static final String RETAINED = "retained";
    public static final String REPLICATING = "replicating";

    private String dbInstanceAutomatedBackupsArn;
    private String status;
    /** The source instance: live for an active backup, as it was for a stored one. */
    private DbInstance instance;
    /** The source instance's Region. */
    private String region;
    private Instant earliestTime;
    private Instant latestTime;
    private int backupRetentionPeriod;
    private String kmsKeyId;
    /** Whether the source's data was saved with the backup, so a restore from it can copy it. */
    private boolean dataSaved;

    public DbInstanceAutomatedBackup() {}

    public String getDbInstanceAutomatedBackupsArn() { return dbInstanceAutomatedBackupsArn; }
    public void setDbInstanceAutomatedBackupsArn(String arn) { this.dbInstanceAutomatedBackupsArn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public DbInstance getInstance() { return instance; }
    public void setInstance(DbInstance instance) { this.instance = instance; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Instant getEarliestTime() { return earliestTime; }
    public void setEarliestTime(Instant earliestTime) { this.earliestTime = earliestTime; }

    public Instant getLatestTime() { return latestTime; }
    public void setLatestTime(Instant latestTime) { this.latestTime = latestTime; }

    public int getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(int backupRetentionPeriod) { this.backupRetentionPeriod = backupRetentionPeriod; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public boolean isDataSaved() { return dataSaved; }
    public void setDataSaved(boolean dataSaved) { this.dataSaved = dataSaved; }
}
