package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A manual DB cluster snapshot, the {@code DBClusterSnapshot} structure of the RDS API. The
 * cluster's data is kept beside it in the snapshot data store, keyed by the snapshot id.
 */
@RegisterForReflection
public class DbClusterSnapshot {
    private String dbClusterSnapshotIdentifier;
    private String dbClusterSnapshotArn;
    private String dbClusterIdentifier;
    private Instant snapshotCreateTime;
    private Instant clusterCreateTime;
    private DatabaseEngine engine;
    private String engineIdentifier;
    private String engineVersion;
    private String engineMode;
    private int allocatedStorage;
    private String status;
    private int port;
    private String vpcId;
    private List<String> availabilityZones = new ArrayList<>();
    private String masterUsername;
    private String masterPassword;
    private String databaseName;
    private String licenseModel;
    private String snapshotType = "manual";
    private int percentProgress;
    private boolean storageEncrypted;
    private String kmsKeyId;
    private String sourceDbClusterSnapshotArn;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbClusterResourceId;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> restoreAccountIds = new ArrayList<>();

    public String getDbClusterSnapshotIdentifier() { return dbClusterSnapshotIdentifier; }
    public void setDbClusterSnapshotIdentifier(String v) { this.dbClusterSnapshotIdentifier = v; }

    public String getDbClusterSnapshotArn() { return dbClusterSnapshotArn; }
    public void setDbClusterSnapshotArn(String v) { this.dbClusterSnapshotArn = v; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String v) { this.dbClusterIdentifier = v; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant v) { this.snapshotCreateTime = v; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant v) { this.clusterCreateTime = v; }

    public DatabaseEngine getEngine() { return engine; }
    public void setEngine(DatabaseEngine v) { this.engine = v; }

    public String getEngineIdentifier() { return engineIdentifier; }
    public void setEngineIdentifier(String v) { this.engineIdentifier = v; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String v) { this.engineVersion = v; }

    public String getEngineMode() { return engineMode; }
    public void setEngineMode(String v) { this.engineMode = v; }

    public int getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(int v) { this.allocatedStorage = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public int getPort() { return port; }
    public void setPort(int v) { this.port = v; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> v) { this.availabilityZones = v; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String v) { this.masterUsername = v; }

    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String v) { this.masterPassword = v; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String v) { this.databaseName = v; }

    public String getLicenseModel() { return licenseModel; }
    public void setLicenseModel(String v) { this.licenseModel = v; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String v) { this.snapshotType = v; }

    public int getPercentProgress() { return percentProgress; }
    public void setPercentProgress(int v) { this.percentProgress = v; }

    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean v) { this.storageEncrypted = v; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String v) { this.kmsKeyId = v; }

    public String getSourceDbClusterSnapshotArn() { return sourceDbClusterSnapshotArn; }
    public void setSourceDbClusterSnapshotArn(String v) { this.sourceDbClusterSnapshotArn = v; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean v) { this.iamDatabaseAuthenticationEnabled = v; }

    public String getDbClusterResourceId() { return dbClusterResourceId; }
    public void setDbClusterResourceId(String v) { this.dbClusterResourceId = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v; }

    public List<String> getRestoreAccountIds() { return restoreAccountIds; }
    public void setRestoreAccountIds(List<String> v) { this.restoreAccountIds = v; }
}
