package io.github.hectorvent.floci.services.cloudhsmv2.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Domain model for a CloudHSM v2 cluster.
 *
 * <p>Follows the same mutable-POJO pattern used by other Floci service models
 * (e.g. MemoryDB {@code Cluster}, ACM {@code Certificate}).
 */
@RegisterForReflection
public class Cluster {

    private String clusterId;
    private ClusterState state;
    private String stateMessage;
    private String hsmType;
    private String vpcId;
    private String sourceBackupId;
    private Certificates certificates;
    private List<Hsm> hsms = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> subnetMapping = new LinkedHashMap<>();
    private String securityGroup;
    private Instant createTimestamp;
    private Map<String, String> tagList = new LinkedHashMap<>();
    private String backupPolicy;
    private BackupRetentionPolicy backupRetentionPolicy;
    private String resourcePolicy;
    private String mode;
    private String networkType;

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public ClusterState getState() {
        return state;
    }

    public void setState(ClusterState state) {
        this.state = state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public void setStateMessage(String stateMessage) {
        this.stateMessage = stateMessage;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getSourceBackupId() {
        return sourceBackupId;
    }

    public void setSourceBackupId(String sourceBackupId) {
        this.sourceBackupId = sourceBackupId;
    }

    public Certificates getCertificates() {
        return certificates;
    }

    public void setCertificates(Certificates certificates) {
        this.certificates = certificates;
    }

    public List<Hsm> getHsms() {
        return hsms;
    }

    public void setHsms(List<Hsm> hsms) {
        this.hsms = hsms != null ? hsms : new ArrayList<>();
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? subnetIds : new ArrayList<>();
    }

    public Map<String, String> getSubnetMapping() {
        return subnetMapping;
    }

    public void setSubnetMapping(Map<String, String> subnetMapping) {
        this.subnetMapping = subnetMapping != null ? subnetMapping : new LinkedHashMap<>();
    }

    public String getSecurityGroup() {
        return securityGroup;
    }

    public void setSecurityGroup(String securityGroup) {
        this.securityGroup = securityGroup;
    }

    public Instant getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(Instant createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public Map<String, String> getTagList() {
        return tagList;
    }

    public void setTagList(Map<String, String> tagList) {
        this.tagList = tagList != null ? tagList : new LinkedHashMap<>();
    }

    public String getBackupPolicy() {
        return backupPolicy;
    }

    public void setBackupPolicy(String backupPolicy) {
        this.backupPolicy = backupPolicy;
    }

    public BackupRetentionPolicy getBackupRetentionPolicy() {
        return backupRetentionPolicy;
    }

    public void setBackupRetentionPolicy(BackupRetentionPolicy backupRetentionPolicy) {
        this.backupRetentionPolicy = backupRetentionPolicy;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    /** True when the cluster has been initialized and has at least one HSM. */
    public boolean isReadyForActive() {
        return state == ClusterState.INITIALIZED && !hsms.isEmpty();
    }
}
