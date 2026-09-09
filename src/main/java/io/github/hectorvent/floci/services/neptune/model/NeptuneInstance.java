package io.github.hectorvent.floci.services.neptune.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class NeptuneInstance {

    private String dbInstanceIdentifier;
    private String dbClusterIdentifier;
    private String dbInstanceClass;
    private String engineVersion;
    private String status;
    private String endpoint;
    private int port;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbInstanceArn;
    private String dbiResourceId;
    private String availabilityZone;
    private boolean autoMinorVersionUpgrade = true;
    private int promotionTier = 1;
    private boolean publiclyAccessible;
    private String dbParameterGroupName;
    private String dbSubnetGroupName;
    private String preferredBackupWindow;
    private String preferredMaintenanceWindow;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Instant createdAt;

    public NeptuneInstance() {}

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) { this.dbInstanceIdentifier = dbInstanceIdentifier; }

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getDbInstanceClass() { return dbInstanceClass; }
    public void setDbInstanceClass(String dbInstanceClass) { this.dbInstanceClass = dbInstanceClass; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public String getDbInstanceArn() { return dbInstanceArn; }
    public void setDbInstanceArn(String dbInstanceArn) { this.dbInstanceArn = dbInstanceArn; }

    public String getDbiResourceId() { return dbiResourceId; }
    public void setDbiResourceId(String dbiResourceId) { this.dbiResourceId = dbiResourceId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public boolean isAutoMinorVersionUpgrade() { return autoMinorVersionUpgrade; }
    public void setAutoMinorVersionUpgrade(boolean autoMinorVersionUpgrade) {
        this.autoMinorVersionUpgrade = autoMinorVersionUpgrade;
    }

    public int getPromotionTier() { return promotionTier; }
    public void setPromotionTier(int promotionTier) { this.promotionTier = promotionTier; }

    public boolean isPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(boolean publiclyAccessible) { this.publiclyAccessible = publiclyAccessible; }

    public String getDbParameterGroupName() { return dbParameterGroupName; }
    public void setDbParameterGroupName(String dbParameterGroupName) { this.dbParameterGroupName = dbParameterGroupName; }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }

    public String getPreferredBackupWindow() { return preferredBackupWindow; }
    public void setPreferredBackupWindow(String preferredBackupWindow) { this.preferredBackupWindow = preferredBackupWindow; }

    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }
}
