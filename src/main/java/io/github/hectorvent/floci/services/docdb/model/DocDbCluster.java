package io.github.hectorvent.floci.services.docdb.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DocDbCluster {
    private String masterUsername;
    private Map<String, String> tags = new LinkedHashMap<>();

    private String dbClusterIdentifier;
    private String status;
    private String engineVersion;
    private String endpoint;
    private int port;
    private String readerEndpoint;
    private boolean iamDatabaseAuthenticationEnabled;
    private String dbClusterArn;
    private String dbClusterResourceId;
    private List<String> dbClusterMembers = new ArrayList<>();
    private String dbSubnetGroupName;
    private String dbClusterParameterGroupName;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private boolean storageEncrypted;
    private String kmsKeyId;
    // AWS defaults to one day of automated backups; a record persisted before the field reads the same
    private int backupRetentionPeriod = 1;
    private String preferredBackupWindow;
    private String preferredMaintenanceWindow;
    private boolean deletionProtection;
    private Instant createdAt;

    // Docker / proxy runtime fields — persisted so cleanup works across restarts
    private String containerId;
    private String containerHost;
    private int containerPort;

    public DocDbCluster (){}

    public String getMasterUsername(){return masterUsername;}
    public void setMasterUsername(String masterUsername){this.masterUsername = masterUsername;}

    public String getDbClusterIdentifier() { return dbClusterIdentifier; }
    public void setDbClusterIdentifier(String dbClusterIdentifier) { this.dbClusterIdentifier = dbClusterIdentifier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(String readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public String getDbClusterArn() { return dbClusterArn; }
    public void setDbClusterArn(String dbClusterArn) { this.dbClusterArn = dbClusterArn; }

    public String getDbClusterResourceId() { return dbClusterResourceId; }
    public void setDbClusterResourceId(String dbClusterResourceId) { this.dbClusterResourceId = dbClusterResourceId; }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }
    public String getDbClusterParameterGroupName() { return dbClusterParameterGroupName; }
    public void setDbClusterParameterGroupName(String dbClusterParameterGroupName) { this.dbClusterParameterGroupName = dbClusterParameterGroupName; }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) { this.vpcSecurityGroupIds = vpcSecurityGroupIds == null ? new ArrayList<>() : vpcSecurityGroupIds; }
    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean storageEncrypted) { this.storageEncrypted = storageEncrypted; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public int getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(int backupRetentionPeriod) { this.backupRetentionPeriod = backupRetentionPeriod; }
    public String getPreferredBackupWindow() { return preferredBackupWindow; }
    public void setPreferredBackupWindow(String preferredBackupWindow) { this.preferredBackupWindow = preferredBackupWindow; }
    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) { this.preferredMaintenanceWindow = preferredMaintenanceWindow; }
    public boolean isDeletionProtection() { return deletionProtection; }
    public void setDeletionProtection(boolean deletionProtection) { this.deletionProtection = deletionProtection; }

    public List<String> getDbClusterMembers() { return dbClusterMembers; }
    public void setDbClusterMembers(List<String> dbClusterMembers) {
        this.dbClusterMembers = dbClusterMembers != null ? new ArrayList<>(dbClusterMembers) : new ArrayList<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }

    public Map<String, String> getTags() { return tags; }

    /** Normalizes null: a record persisted before tags were stored deserializes without them. */
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }


}