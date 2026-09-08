package io.github.hectorvent.floci.services.neptune.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class NeptuneCluster {

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
    private List<String> availabilityZones = new ArrayList<>();
    private int backupRetentionPeriod = 1;
    private String preferredBackupWindow;
    private String preferredMaintenanceWindow;
    private String dbSubnetGroupName;
    private String dbClusterParameterGroupName;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private boolean storageEncrypted;
    private String kmsKeyId;
    private String storageType;
    private boolean deletionProtection;
    private boolean copyTagsToSnapshot;
    private List<String> enabledCloudwatchLogsExports = new ArrayList<>();
    private List<String> associatedRoleArns = new ArrayList<>();
    private String replicationSourceIdentifier;
    private Double serverlessV2MinCapacity;
    private Double serverlessV2MaxCapacity;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Instant createdAt;

    // Docker / proxy runtime fields — persisted so cleanup works across restarts
    private String containerId;
    private String containerHost;
    private int containerPort;
    private int proxyPort;

    public NeptuneCluster() {}

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

    public List<String> getDbClusterMembers() { return dbClusterMembers; }
    public void setDbClusterMembers(List<String> dbClusterMembers) {
        this.dbClusterMembers = dbClusterMembers != null ? new ArrayList<>(dbClusterMembers) : new ArrayList<>();
    }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> availabilityZones) {
        this.availabilityZones = availabilityZones != null ? new ArrayList<>(availabilityZones) : new ArrayList<>();
    }

    public int getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(int backupRetentionPeriod) { this.backupRetentionPeriod = backupRetentionPeriod; }

    public String getPreferredBackupWindow() { return preferredBackupWindow; }
    public void setPreferredBackupWindow(String preferredBackupWindow) { this.preferredBackupWindow = preferredBackupWindow; }

    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public String getDbSubnetGroupName() { return dbSubnetGroupName; }
    public void setDbSubnetGroupName(String dbSubnetGroupName) { this.dbSubnetGroupName = dbSubnetGroupName; }

    public String getDbClusterParameterGroupName() { return dbClusterParameterGroupName; }
    public void setDbClusterParameterGroupName(String dbClusterParameterGroupName) {
        this.dbClusterParameterGroupName = dbClusterParameterGroupName;
    }

    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds != null ? new ArrayList<>(vpcSecurityGroupIds) : new ArrayList<>();
    }

    public boolean isStorageEncrypted() { return storageEncrypted; }
    public void setStorageEncrypted(boolean storageEncrypted) { this.storageEncrypted = storageEncrypted; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public boolean isDeletionProtection() { return deletionProtection; }
    public void setDeletionProtection(boolean deletionProtection) { this.deletionProtection = deletionProtection; }

    public boolean isCopyTagsToSnapshot() { return copyTagsToSnapshot; }
    public void setCopyTagsToSnapshot(boolean copyTagsToSnapshot) { this.copyTagsToSnapshot = copyTagsToSnapshot; }

    public List<String> getEnabledCloudwatchLogsExports() { return enabledCloudwatchLogsExports; }
    public void setEnabledCloudwatchLogsExports(List<String> enabledCloudwatchLogsExports) {
        this.enabledCloudwatchLogsExports = enabledCloudwatchLogsExports != null
                ? new ArrayList<>(enabledCloudwatchLogsExports) : new ArrayList<>();
    }

    public List<String> getAssociatedRoleArns() { return associatedRoleArns; }
    public void setAssociatedRoleArns(List<String> associatedRoleArns) {
        this.associatedRoleArns = associatedRoleArns != null ? new ArrayList<>(associatedRoleArns) : new ArrayList<>();
    }

    public String getReplicationSourceIdentifier() { return replicationSourceIdentifier; }
    public void setReplicationSourceIdentifier(String replicationSourceIdentifier) {
        this.replicationSourceIdentifier = replicationSourceIdentifier;
    }

    public Double getServerlessV2MinCapacity() { return serverlessV2MinCapacity; }
    public void setServerlessV2MinCapacity(Double serverlessV2MinCapacity) { this.serverlessV2MinCapacity = serverlessV2MinCapacity; }

    public Double getServerlessV2MaxCapacity() { return serverlessV2MaxCapacity; }
    public void setServerlessV2MaxCapacity(Double serverlessV2MaxCapacity) { this.serverlessV2MaxCapacity = serverlessV2MaxCapacity; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
}
