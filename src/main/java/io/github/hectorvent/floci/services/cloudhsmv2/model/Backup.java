package io.github.hectorvent.floci.services.cloudhsmv2.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Domain model for a CloudHSM v2 Backup.
 */
@RegisterForReflection
public class Backup {

    private String backupId;
    private String backupState;
    private String clusterId;
    private Instant createTimestamp;
    private Instant deleteTimestamp;
    private Instant copyTimestamp;
    private String neverExpires;
    private String sourceRegion;
    private String sourceBackup;
    private String sourceCluster;
    private String resourcePolicy;
    private Map<String, String> tagList = new LinkedHashMap<>();
    private String mode;
    private String hsmType;

    public String getBackupId() {
        return backupId;
    }

    public void setBackupId(String backupId) {
        this.backupId = backupId;
    }

    public String getBackupState() {
        return backupState;
    }

    public void setBackupState(String backupState) {
        this.backupState = backupState;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public Instant getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(Instant createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public Instant getDeleteTimestamp() {
        return deleteTimestamp;
    }

    public void setDeleteTimestamp(Instant deleteTimestamp) {
        this.deleteTimestamp = deleteTimestamp;
    }

    public Instant getCopyTimestamp() {
        return copyTimestamp;
    }

    public void setCopyTimestamp(Instant copyTimestamp) {
        this.copyTimestamp = copyTimestamp;
    }

    public String getNeverExpires() {
        return neverExpires;
    }

    public void setNeverExpires(String neverExpires) {
        this.neverExpires = neverExpires;
    }

    public String getSourceRegion() {
        return sourceRegion;
    }

    public void setSourceRegion(String sourceRegion) {
        this.sourceRegion = sourceRegion;
    }

    public String getSourceBackup() {
        return sourceBackup;
    }

    public void setSourceBackup(String sourceBackup) {
        this.sourceBackup = sourceBackup;
    }

    public String getSourceCluster() {
        return sourceCluster;
    }

    public void setSourceCluster(String sourceCluster) {
        this.sourceCluster = sourceCluster;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public Map<String, String> getTagList() {
        return tagList;
    }

    public void setTagList(Map<String, String> tagList) {
        this.tagList = tagList != null ? tagList : new LinkedHashMap<>();
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }
}
