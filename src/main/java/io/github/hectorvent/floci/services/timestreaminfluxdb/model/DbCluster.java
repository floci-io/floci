package io.github.hectorvent.floci.services.timestreaminfluxdb.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbCluster {
    private String id;
    private String name;
    private String arn;
    private String status;
    private String endpoint;
    private String readerEndpoint;
    private Integer port;
    private String deploymentType;
    private String dbInstanceType;
    private String networkType;
    private String dbStorageType;
    private Integer allocatedStorage;
    private String engineType;
    private Boolean publiclyAccessible;
    private String dbParameterGroupIdentifier;
    private JsonNode logDeliveryConfiguration;
    private JsonNode maintenanceSchedule;
    private String influxAuthParametersSecretArn;
    private List<String> vpcSubnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private String failoverMode;
    private JsonNode clusterConfiguration;
    private JsonNode dbBackupConfigurations;
    private String kmsKeyId;
    private Instant createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;
    private String region;
    private String containerId;

    public DbCluster() {
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(String readerEndpoint) { this.readerEndpoint = readerEndpoint; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getDeploymentType() { return deploymentType; }
    public void setDeploymentType(String deploymentType) { this.deploymentType = deploymentType; }
    public String getDbInstanceType() { return dbInstanceType; }
    public void setDbInstanceType(String dbInstanceType) { this.dbInstanceType = dbInstanceType; }
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
    public String getDbStorageType() { return dbStorageType; }
    public void setDbStorageType(String dbStorageType) { this.dbStorageType = dbStorageType; }
    public Integer getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(Integer allocatedStorage) { this.allocatedStorage = allocatedStorage; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public Boolean getPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(Boolean publiclyAccessible) { this.publiclyAccessible = publiclyAccessible; }
    public String getDbParameterGroupIdentifier() { return dbParameterGroupIdentifier; }
    public void setDbParameterGroupIdentifier(String dbParameterGroupIdentifier) { this.dbParameterGroupIdentifier = dbParameterGroupIdentifier; }
    public JsonNode getLogDeliveryConfiguration() { return logDeliveryConfiguration; }
    public void setLogDeliveryConfiguration(JsonNode logDeliveryConfiguration) { this.logDeliveryConfiguration = logDeliveryConfiguration; }
    public JsonNode getMaintenanceSchedule() { return maintenanceSchedule; }
    public void setMaintenanceSchedule(JsonNode maintenanceSchedule) { this.maintenanceSchedule = maintenanceSchedule; }
    public String getInfluxAuthParametersSecretArn() { return influxAuthParametersSecretArn; }
    public void setInfluxAuthParametersSecretArn(String influxAuthParametersSecretArn) { this.influxAuthParametersSecretArn = influxAuthParametersSecretArn; }
    public List<String> getVpcSubnetIds() { return vpcSubnetIds; }
    public void setVpcSubnetIds(List<String> vpcSubnetIds) { this.vpcSubnetIds = copy(vpcSubnetIds); }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) { this.vpcSecurityGroupIds = copy(vpcSecurityGroupIds); }
    public String getFailoverMode() { return failoverMode; }
    public void setFailoverMode(String failoverMode) { this.failoverMode = failoverMode; }
    public JsonNode getClusterConfiguration() { return clusterConfiguration; }
    public void setClusterConfiguration(JsonNode clusterConfiguration) { this.clusterConfiguration = clusterConfiguration; }
    public JsonNode getDbBackupConfigurations() { return dbBackupConfigurations; }
    public void setDbBackupConfigurations(JsonNode dbBackupConfigurations) { this.dbBackupConfigurations = dbBackupConfigurations; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
