package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DbSnapshot {

    private String dbSnapshotIdentifier;
    private String dbInstanceIdentifier;
    private Instant snapshotCreateTime;
    private DatabaseEngine engine;
    private String engineVersion;
    private int allocatedStorage;
    private String status;
    private String masterUsername;
    private String availabilityZone;
    private String vpcId;
    private Instant instanceCreateTime;
    private int port;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbiResourceId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbSnapshot() {}

    public DbSnapshot(String dbSnapshotIdentifier, String dbInstanceIdentifier, Instant snapshotCreateTime,
                      DatabaseEngine engine, String engineVersion, int allocatedStorage, String status,
                      String masterUsername, String availabilityZone, String vpcId, Instant instanceCreateTime,
                      int port, boolean iamDatabaseAuthenticationEnabled, String dbiResourceId) {
        this.dbSnapshotIdentifier = dbSnapshotIdentifier;
        this.dbInstanceIdentifier = dbInstanceIdentifier;
        this.snapshotCreateTime = snapshotCreateTime;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.allocatedStorage = allocatedStorage;
        this.status = status;
        this.masterUsername = masterUsername;
        this.availabilityZone = availabilityZone;
        this.vpcId = vpcId;
        this.instanceCreateTime = instanceCreateTime;
        this.port = port;
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
        this.dbiResourceId = dbiResourceId;
    }

    public String getDbSnapshotIdentifier() { return dbSnapshotIdentifier; }
    public void setDbSnapshotIdentifier(String dbSnapshotIdentifier) { this.dbSnapshotIdentifier = dbSnapshotIdentifier; }

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) { this.dbInstanceIdentifier = dbInstanceIdentifier; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant snapshotCreateTime) { this.snapshotCreateTime = snapshotCreateTime; }

    public DatabaseEngine getEngine() { return engine; }
    public void setEngine(DatabaseEngine engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public int getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(int allocatedStorage) { this.allocatedStorage = allocatedStorage; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public Instant getInstanceCreateTime() { return instanceCreateTime; }
    public void setInstanceCreateTime(Instant instanceCreateTime) { this.instanceCreateTime = instanceCreateTime; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) { this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled; }

    public String getDbiResourceId() { return dbiResourceId; }
    public void setDbiResourceId(String dbiResourceId) { this.dbiResourceId = dbiResourceId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
