package io.github.hectorvent.floci.services.timestreaminfluxdb.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DbBackup {
    private String id;
    private String name;
    private String arn;
    private String status;
    private Instant createdAt;
    private String expiresAfter;
    private String dbResourceId;
    private String resourceType;
    private String type;
    private String engineType;
    private String deploymentType;
    private String kmsKeyId;
    private JsonNode clusterConfiguration;
    private String dbParameterGroupId;
    private String dbInstanceType;
    private JsonNode logDeliveryConfiguration;
    private String failoverMode;
    private String dbStorageType;
    private Integer allocatedStorage;
    private List<String> vpcSubnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private Boolean publiclyAccessible;
    private Integer port;
    private String networkType;
    private String influxAuthParametersSecretArn;
    private JsonNode maintenanceSchedule;
    private JsonNode dbBackupConfigurations;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;
    private String region;
    private boolean dataCaptured;

    public DbBackup() {
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public boolean isDataCaptured() { return dataCaptured; }
    public void setDataCaptured(boolean dataCaptured) { this.dataCaptured = dataCaptured; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getExpiresAfter() { return expiresAfter; }
    public void setExpiresAfter(String expiresAfter) { this.expiresAfter = expiresAfter; }
    public String getDbResourceId() { return dbResourceId; }
    public void setDbResourceId(String dbResourceId) { this.dbResourceId = dbResourceId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public String getDeploymentType() { return deploymentType; }
    public void setDeploymentType(String deploymentType) { this.deploymentType = deploymentType; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public JsonNode getClusterConfiguration() { return clusterConfiguration; }
    public void setClusterConfiguration(JsonNode clusterConfiguration) { this.clusterConfiguration = clusterConfiguration; }
    public String getDbParameterGroupId() { return dbParameterGroupId; }
    public void setDbParameterGroupId(String dbParameterGroupId) { this.dbParameterGroupId = dbParameterGroupId; }
    public String getDbInstanceType() { return dbInstanceType; }
    public void setDbInstanceType(String dbInstanceType) { this.dbInstanceType = dbInstanceType; }
    public JsonNode getLogDeliveryConfiguration() { return logDeliveryConfiguration; }
    public void setLogDeliveryConfiguration(JsonNode logDeliveryConfiguration) { this.logDeliveryConfiguration = logDeliveryConfiguration; }
    public String getFailoverMode() { return failoverMode; }
    public void setFailoverMode(String failoverMode) { this.failoverMode = failoverMode; }
    public String getDbStorageType() { return dbStorageType; }
    public void setDbStorageType(String dbStorageType) { this.dbStorageType = dbStorageType; }
    public Integer getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(Integer allocatedStorage) { this.allocatedStorage = allocatedStorage; }
    public List<String> getVpcSubnetIds() { return vpcSubnetIds; }
    public void setVpcSubnetIds(List<String> vpcSubnetIds) { this.vpcSubnetIds = copy(vpcSubnetIds); }
    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) { this.vpcSecurityGroupIds = copy(vpcSecurityGroupIds); }
    public Boolean getPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(Boolean publiclyAccessible) { this.publiclyAccessible = publiclyAccessible; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
    public String getInfluxAuthParametersSecretArn() { return influxAuthParametersSecretArn; }
    public void setInfluxAuthParametersSecretArn(String influxAuthParametersSecretArn) { this.influxAuthParametersSecretArn = influxAuthParametersSecretArn; }
    public JsonNode getMaintenanceSchedule() { return maintenanceSchedule; }
    public void setMaintenanceSchedule(JsonNode maintenanceSchedule) { this.maintenanceSchedule = maintenanceSchedule; }
    public JsonNode getDbBackupConfigurations() { return dbBackupConfigurations; }
    public void setDbBackupConfigurations(JsonNode dbBackupConfigurations) { this.dbBackupConfigurations = dbBackupConfigurations; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }
}
