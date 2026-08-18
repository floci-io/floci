package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class RedshiftCluster {

    private String clusterIdentifier;
    private String clusterNamespaceArn;
    private String nodeType;
    private String clusterType;
    private String clusterStatus;
    private String clusterAvailabilityStatus;
    private String modifyStatus;
    private String masterUsername;
    private String dbName;
    private String endpointAddress;
    private int endpointPort;
    private Instant clusterCreateTime;
    private int automatedSnapshotRetentionPeriod;
    private int manualSnapshotRetentionPeriod;
    private String clusterSubnetGroupName;
    private String vpcId;
    private String availabilityZone;
    private String preferredMaintenanceWindow;
    private String clusterVersion;
    private String clusterRevisionNumber;
    private boolean allowVersionUpgrade;
    private int numberOfNodes;
    private boolean publiclyAccessible;
    private boolean encrypted;
    private String kmsKeyId;
    private boolean enhancedVpcRouting;
    private String maintenanceTrackName;
    private String elasticIp;
    private String defaultIamRoleArn;
    private String ipAddressType;
    private String multiAz;
    private String availabilityZoneRelocationStatus;
    private List<String> clusterSecurityGroups = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private List<String> clusterParameterGroups = new ArrayList<>();
    private List<String> iamRoles = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }

    public String getClusterNamespaceArn() { return clusterNamespaceArn; }
    public void setClusterNamespaceArn(String clusterNamespaceArn) { this.clusterNamespaceArn = clusterNamespaceArn; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getClusterType() { return clusterType; }
    public void setClusterType(String clusterType) { this.clusterType = clusterType; }

    public String getClusterStatus() { return clusterStatus; }
    public void setClusterStatus(String clusterStatus) { this.clusterStatus = clusterStatus; }

    public String getClusterAvailabilityStatus() { return clusterAvailabilityStatus; }
    public void setClusterAvailabilityStatus(String clusterAvailabilityStatus) {
        this.clusterAvailabilityStatus = clusterAvailabilityStatus;
    }

    public String getModifyStatus() { return modifyStatus; }
    public void setModifyStatus(String modifyStatus) { this.modifyStatus = modifyStatus; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getEndpointAddress() { return endpointAddress; }
    public void setEndpointAddress(String endpointAddress) { this.endpointAddress = endpointAddress; }

    public int getEndpointPort() { return endpointPort; }
    public void setEndpointPort(int endpointPort) { this.endpointPort = endpointPort; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant clusterCreateTime) { this.clusterCreateTime = clusterCreateTime; }

    public int getAutomatedSnapshotRetentionPeriod() { return automatedSnapshotRetentionPeriod; }
    public void setAutomatedSnapshotRetentionPeriod(int automatedSnapshotRetentionPeriod) {
        this.automatedSnapshotRetentionPeriod = automatedSnapshotRetentionPeriod;
    }

    public int getManualSnapshotRetentionPeriod() { return manualSnapshotRetentionPeriod; }
    public void setManualSnapshotRetentionPeriod(int manualSnapshotRetentionPeriod) {
        this.manualSnapshotRetentionPeriod = manualSnapshotRetentionPeriod;
    }

    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) {
        this.clusterSubnetGroupName = clusterSubnetGroupName;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public String getClusterVersion() { return clusterVersion; }
    public void setClusterVersion(String clusterVersion) { this.clusterVersion = clusterVersion; }

    public String getClusterRevisionNumber() { return clusterRevisionNumber; }
    public void setClusterRevisionNumber(String clusterRevisionNumber) {
        this.clusterRevisionNumber = clusterRevisionNumber;
    }

    public boolean isAllowVersionUpgrade() { return allowVersionUpgrade; }
    public void setAllowVersionUpgrade(boolean allowVersionUpgrade) { this.allowVersionUpgrade = allowVersionUpgrade; }

    public int getNumberOfNodes() { return numberOfNodes; }
    public void setNumberOfNodes(int numberOfNodes) { this.numberOfNodes = numberOfNodes; }

    public boolean isPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(boolean publiclyAccessible) { this.publiclyAccessible = publiclyAccessible; }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public boolean isEnhancedVpcRouting() { return enhancedVpcRouting; }
    public void setEnhancedVpcRouting(boolean enhancedVpcRouting) { this.enhancedVpcRouting = enhancedVpcRouting; }

    public String getMaintenanceTrackName() { return maintenanceTrackName; }
    public void setMaintenanceTrackName(String maintenanceTrackName) { this.maintenanceTrackName = maintenanceTrackName; }

    public String getElasticIp() { return elasticIp; }
    public void setElasticIp(String elasticIp) { this.elasticIp = elasticIp; }

    public String getDefaultIamRoleArn() { return defaultIamRoleArn; }
    public void setDefaultIamRoleArn(String defaultIamRoleArn) { this.defaultIamRoleArn = defaultIamRoleArn; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public String getMultiAz() { return multiAz; }
    public void setMultiAz(String multiAz) { this.multiAz = multiAz; }

    public String getAvailabilityZoneRelocationStatus() { return availabilityZoneRelocationStatus; }
    public void setAvailabilityZoneRelocationStatus(String availabilityZoneRelocationStatus) {
        this.availabilityZoneRelocationStatus = availabilityZoneRelocationStatus;
    }

    public List<String> getClusterSecurityGroups() { return clusterSecurityGroups; }
    public void setClusterSecurityGroups(List<String> clusterSecurityGroups) {
        this.clusterSecurityGroups = clusterSecurityGroups;
    }

    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds;
    }

    public List<String> getClusterParameterGroups() { return clusterParameterGroups; }
    public void setClusterParameterGroups(List<String> clusterParameterGroups) {
        this.clusterParameterGroups = clusterParameterGroups;
    }

    public List<String> getIamRoles() { return iamRoles; }
    public void setIamRoles(List<String> iamRoles) { this.iamRoles = iamRoles; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
