package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbClusterSnapshot {

    private String dbClusterSnapshotIdentifier;
    private String dbClusterSnapshotArn;
    private String sourceDbClusterSnapshotArn;
    private String dbClusterIdentifier;
    private Instant snapshotCreateTime;
    private Instant clusterCreateTime;
    private DatabaseEngine engine;
    private String engineIdentifier;
    private String engineVersion;
    private String status;
    private int percentProgress;
    private String snapshotType;
    private String masterUsername;
    private String masterPassword;
    private String databaseName;
    private int port;
    private boolean iamDatabaseAuthenticationEnabled;
    private boolean storageEncrypted;
    private String engineMode;
    private String dbClusterResourceId;
    private String dbClusterParameterGroupName;
    private String dbSubnetGroupName;
    private String vpcId;
    private String availabilityZone;
    private boolean multiAz;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> restoreAccountIds = new ArrayList<>();

    public DbClusterSnapshot() {}

    public String getDbClusterSnapshotIdentifier() { return dbClusterSnapshotIdentifier; }
    public void setDbClusterSnapshotIdentifier(String dbClusterSnapshotIdentifier) {
        this.dbClusterSnapshotIdentifier = dbClusterSnapshotIdentifier;
    }

    public String getDbClusterSnapshotArn() { return dbClusterSnapshotArn; }
    public void setDbClusterSnapshotArn(String dbClusterSnapshotArn) {
        this.dbClusterSnapshotArn = dbClusterSnapshotArn;
    }

    public String getSourceDbClusterSnapshotArn() { return sourceDbClusterSnapshotArn; }
    public void setSourceDbClusterSnapshotArn(String sourceDbClusterSnapshotArn) {
        this.sourceDbClusterSnapshotArn = sourceDbClusterSnapshotArn;
    }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) {
        this.dbClusterIdentifier = dbClusterIdentifier;
    }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant snapshotCreateTime) { this.snapshotCreateTime = snapshotCreateTime; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant clusterCreateTime) { this.clusterCreateTime = clusterCreateTime; }

    public DatabaseEngine getEngine() { return engine; }
    public void setEngine(DatabaseEngine engine) { this.engine = engine; }

    public String getEngineIdentifier() { return engineIdentifier; }
    public void setEngineIdentifier(String engineIdentifier) { this.engineIdentifier = engineIdentifier; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPercentProgress() { return percentProgress; }
    public void setPercentProgress(int percentProgress) { this.percentProgress = percentProgress; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getMasterPassword() { return masterPassword; }
    public void setMasterPassword(String masterPassword) { this.masterPassword = masterPassword; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean storageEncrypted) { this.storageEncrypted = storageEncrypted; }

    public String getEngineMode() { return engineMode; }
    public void setEngineMode(String engineMode) { this.engineMode = engineMode; }

    public String getDbClusterResourceId() { return dbClusterResourceId; }
    public void setDbClusterResourceId(String dbClusterResourceId) { this.dbClusterResourceId = dbClusterResourceId; }

    public String getDbClusterParameterGroupName() { return dbClusterParameterGroupName; }
    public void setDbClusterParameterGroupName(String dbClusterParameterGroupName) {
        this.dbClusterParameterGroupName = dbClusterParameterGroupName;
    }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public boolean isMultiAz() { return multiAz; }
    public void setMultiAz(boolean multiAz) { this.multiAz = multiAz; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public List<String> getRestoreAccountIds() { return restoreAccountIds; }
    public void setRestoreAccountIds(List<String> restoreAccountIds) {
        this.restoreAccountIds = restoreAccountIds != null
                ? new ArrayList<>(restoreAccountIds)
                : new ArrayList<>();
    }
}
