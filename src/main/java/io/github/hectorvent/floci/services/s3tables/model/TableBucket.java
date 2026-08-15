package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class TableBucket {
    private String name;
    private String arn;
    private String createdAt;
    private String ownerAccountId;
    private Object encryptionConfiguration;
    private Object storageClassConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String resourcePolicy;
    private Map<String, Object> maintenanceConfigurations = new LinkedHashMap<>();
    private Map<String, Namespace> namespaces = new LinkedHashMap<>();

    public TableBucket() {
    }

    public TableBucket(String name, String arn, String createdAt, String ownerAccountId,
                       Object encryptionConfiguration, Object storageClassConfiguration, Map<String, String> tags) {
        this.name = name;
        this.arn = arn;
        this.createdAt = createdAt;
        this.ownerAccountId = ownerAccountId;
        this.encryptionConfiguration = encryptionConfiguration;
        this.storageClassConfiguration = storageClassConfiguration;
        if (tags != null) {
            this.tags = new LinkedHashMap<>(tags);
        }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }
    public Object getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(Object encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }
    public Object getStorageClassConfiguration() { return storageClassConfiguration; }
    public void setStorageClassConfiguration(Object storageClassConfiguration) { this.storageClassConfiguration = storageClassConfiguration; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags); }
    public String getResourcePolicy() { return resourcePolicy; }
    public void setResourcePolicy(String resourcePolicy) { this.resourcePolicy = resourcePolicy; }
    public Map<String, Object> getMaintenanceConfigurations() { return maintenanceConfigurations; }
    public void setMaintenanceConfigurations(Map<String, Object> maintenanceConfigurations) { this.maintenanceConfigurations = maintenanceConfigurations == null ? new LinkedHashMap<>() : new LinkedHashMap<>(maintenanceConfigurations); }
    public Map<String, Namespace> getNamespaces() { return namespaces; }
    public void setNamespaces(Map<String, Namespace> namespaces) { this.namespaces = namespaces == null ? new LinkedHashMap<>() : new LinkedHashMap<>(namespaces); }
}
