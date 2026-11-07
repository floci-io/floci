package io.floci.aws.services.docdb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DocumentDB cluster snapshot. The data itself, a {@code mongodump} archive of the cluster's
 * MongoDB container, is kept beside the record under the same key rather than on it, so listing
 * snapshots never reads every archive.
 */
@RegisterForReflection
public class DocDbClusterSnapshot {

    private String dbClusterSnapshotIdentifier;
    private String dbClusterIdentifier;
    private String dbClusterSnapshotArn;
    private String status;
    private String snapshotType;
    private String engineVersion;
    private String masterUsername;
    /** Kept so a restore starts its container with the credentials the data carries; never returned by the API. */
    private String masterPassword;
    private int port;
    private String vpcId;
    private boolean storageEncrypted;
    private String kmsKeyId;
    private Instant snapshotCreateTime;
    private Instant clusterCreateTime;
    private long sizeBytes;
    /** Taken in mock mode, with no backend to read: the record alone, with no archive to restore. */
    private boolean metadataOnly;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getDbClusterSnapshotIdentifier() { return dbClusterSnapshotIdentifier; }
    public void setDbClusterSnapshotIdentifier(String v) { this.dbClusterSnapshotIdentifier = v; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String v) { this.dbClusterIdentifier = v; }

    public String getDbClusterSnapshotArn() { return dbClusterSnapshotArn; }
    public void setDbClusterSnapshotArn(String v) { this.dbClusterSnapshotArn = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String v) { this.snapshotType = v; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String v) { this.engineVersion = v; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String v) { this.masterUsername = v; }

    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String v) { this.masterPassword = v; }

    public int getPort() { return port; }
    public void setPort(int v) { this.port = v; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }

    public boolean isMetadataOnly() { return metadataOnly; }
    public void setMetadataOnly(boolean v) { this.metadataOnly = v; }

    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean v) { this.storageEncrypted = v; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String v) { this.kmsKeyId = v; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant v) { this.snapshotCreateTime = v; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant v) { this.clusterCreateTime = v; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v != null ? v : new LinkedHashMap<>(); }
}
