package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A deleted DB cluster's {@code retained} automated backup, kept when the cluster is deleted with
 * {@code DeleteAutomatedBackups=false}, with the cluster as it was. DescribeDBClusterAutomatedBackups
 * lists only these: a live cluster's backups are not listed, as in AWS.
 */
@RegisterForReflection
public class DbClusterAutomatedBackup {

    public static final String RETAINED = "retained";

    private String dbClusterAutomatedBackupsArn;
    private String status;
    private DbCluster cluster;
    private String region;
    private Instant earliestTime;
    private Instant latestTime;
    private int backupRetentionPeriod;
    /** Whether the cluster's data was saved with the backup, so a restore from it can copy it. */
    private boolean dataSaved;

    public DbClusterAutomatedBackup() {}

    public String getDbClusterAutomatedBackupsArn() { return dbClusterAutomatedBackupsArn; }
    public void setDbClusterAutomatedBackupsArn(String arn) { this.dbClusterAutomatedBackupsArn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public DbCluster getCluster() { return cluster; }
    public void setCluster(DbCluster cluster) { this.cluster = cluster; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Instant getEarliestTime() { return earliestTime; }
    public void setEarliestTime(Instant earliestTime) { this.earliestTime = earliestTime; }

    public Instant getLatestTime() { return latestTime; }
    public void setLatestTime(Instant latestTime) { this.latestTime = latestTime; }

    public int getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(int backupRetentionPeriod) { this.backupRetentionPeriod = backupRetentionPeriod; }

    public boolean isDataSaved() { return dataSaved; }
    public void setDataSaved(boolean dataSaved) { this.dataSaved = dataSaved; }
}
