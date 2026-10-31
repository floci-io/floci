package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RegisterForReflection
public class Snapshot {
    private String snapshotIdentifier;
    private String clusterIdentifier;
    private String status;
    private int port;
    private String masterUsername;
    private String masterPassword;
    private String sqlDump;
    private String snapshotArn;
    private Instant snapshotCreateTime;
    private Integer manualSnapshotRetentionPeriod;
    private String snapshotType;
    private Integer automatedSnapshotRetentionPeriod;
    private Instant scheduledTime;
    private String clusterGeneration;
    private String sourceRegion;
    private String sourceSnapshotArn;
    private Instant sourceSnapshotCreateTime;
    private String kmsKeyId;
    private Set<String> copiedToRegions = new LinkedHashSet<>();

    public String getSourceRegion() { return sourceRegion; }
    public void setSourceRegion(String value) { sourceRegion = value; }
    public String getSourceSnapshotArn() { return sourceSnapshotArn; }
    public void setSourceSnapshotArn(String value) { sourceSnapshotArn = value; }
    public Instant getSourceSnapshotCreateTime() { return sourceSnapshotCreateTime; }
    public void setSourceSnapshotCreateTime(Instant value) { sourceSnapshotCreateTime = value; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String value) { kmsKeyId = value; }
    /** Regions this snapshot has been carried to, kept so a copy that was deleted there stays gone. */
    public Set<String> getCopiedToRegions() { return copiedToRegions; }
    public void setCopiedToRegions(Set<String> value) {
        copiedToRegions = value == null ? new LinkedHashSet<>() : value;
    }

    public String getClusterGeneration() { return clusterGeneration; }
    public void setClusterGeneration(String value) { clusterGeneration = value; }

    public String getSnapshotType() { return snapshotType == null ? "manual" : snapshotType; }
    public void setSnapshotType(String value) { snapshotType = value; }
    public Integer getAutomatedSnapshotRetentionPeriod() { return automatedSnapshotRetentionPeriod; }
    public void setAutomatedSnapshotRetentionPeriod(Integer value) { automatedSnapshotRetentionPeriod = value; }
    public Instant getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(Instant value) { scheduledTime = value; }

    public Snapshot() {}

    public Snapshot(Snapshot other) {
        this.snapshotIdentifier = other.snapshotIdentifier;
        this.clusterIdentifier = other.clusterIdentifier;
        this.status = other.status;
        this.port = other.port;
        this.masterUsername = other.masterUsername;
        this.masterPassword = other.masterPassword;
        this.sqlDump = other.sqlDump;
        this.snapshotArn = other.snapshotArn;
        this.snapshotCreateTime = other.snapshotCreateTime;
        this.manualSnapshotRetentionPeriod = other.manualSnapshotRetentionPeriod;
        this.snapshotType = other.snapshotType;
        this.automatedSnapshotRetentionPeriod = other.automatedSnapshotRetentionPeriod;
        this.scheduledTime = other.scheduledTime;
        this.clusterGeneration = other.clusterGeneration;
        this.sourceRegion = other.sourceRegion;
        this.sourceSnapshotArn = other.sourceSnapshotArn;
        this.sourceSnapshotCreateTime = other.sourceSnapshotCreateTime;
        this.kmsKeyId = other.kmsKeyId;
        this.copiedToRegions = new LinkedHashSet<>(other.copiedToRegions);
        this.tags = new LinkedHashMap<>(other.tags);
    }

    public Snapshot(String snapshotIdentifier, String clusterIdentifier, String status, int port, String masterUsername) {
        this.snapshotIdentifier = snapshotIdentifier;
        this.clusterIdentifier = clusterIdentifier;
        this.status = status;
        this.port = port;
        this.masterUsername = masterUsername;
    }

    public Snapshot(String snapshotIdentifier, String clusterIdentifier, String status, int port, String masterUsername, String sqlDump) {
        this.snapshotIdentifier = snapshotIdentifier;
        this.clusterIdentifier = clusterIdentifier;
        this.status = status;
        this.port = port;
        this.masterUsername = masterUsername;
        this.sqlDump = sqlDump;
    }

    public String getSnapshotIdentifier() {
        return snapshotIdentifier;
    }

    public void setSnapshotIdentifier(String snapshotIdentifier) {
        this.snapshotIdentifier = snapshotIdentifier;
    }

    public String getClusterIdentifier() {
        return clusterIdentifier;
    }

    public void setClusterIdentifier(String clusterIdentifier) {
        this.clusterIdentifier = clusterIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMasterUsername() {
        return masterUsername;
    }

    public void setMasterUsername(String masterUsername) {
        this.masterUsername = masterUsername;
    }

    public String getMasterPassword() {
        return masterPassword;
    }

    public void setMasterPassword(String masterPassword) {
        this.masterPassword = masterPassword;
    }

    public String getSqlDump() {
        return sqlDump;
    }

    public void setSqlDump(String sqlDump) {
        this.sqlDump = sqlDump;
    }

    public String getSnapshotArn() {
        return snapshotArn;
    }

    public void setSnapshotArn(String snapshotArn) {
        this.snapshotArn = snapshotArn;
    }

    public Instant getSnapshotCreateTime() {
        return snapshotCreateTime;
    }

    public void setSnapshotCreateTime(Instant snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public Integer getManualSnapshotRetentionPeriod() {
        return manualSnapshotRetentionPeriod == null ? -1 : manualSnapshotRetentionPeriod;
    }

    public void setManualSnapshotRetentionPeriod(Integer manualSnapshotRetentionPeriod) {
        this.manualSnapshotRetentionPeriod = manualSnapshotRetentionPeriod;
    }

    private Map<String, String> tags = new LinkedHashMap<>();

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
