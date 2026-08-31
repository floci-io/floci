package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class S3Table {
    private String name;
    private String arn;
    private String namespace;
    private String format;
    private String versionToken;
    private String metadataLocation;
    private String createdAt;
    private String modifiedAt;
    private String ownerAccountId;
    private Object metadata;
    private Object encryptionConfiguration;
    private Object storageClassConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String resourcePolicy;
    private Map<String, Object> maintenanceConfigurations = new LinkedHashMap<>();

    public S3Table() {
    }

    public S3Table(String name, String arn, String namespace, String format, String versionToken,
                   String metadataLocation, String createdAt, String ownerAccountId, Object metadata,
                   Object encryptionConfiguration, Object storageClassConfiguration, Map<String, String> tags) {
        this.name = name;
        this.arn = arn;
        this.namespace = namespace;
        this.format = format;
        this.versionToken = versionToken;
        this.metadataLocation = metadataLocation;
        this.createdAt = createdAt;
        this.modifiedAt = createdAt;
        this.ownerAccountId = ownerAccountId;
        this.metadata = metadata;
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
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getVersionToken() { return versionToken; }
    public void setVersionToken(String versionToken) { this.versionToken = versionToken; }
    public String getMetadataLocation() { return metadataLocation; }
    public void setMetadataLocation(String metadataLocation) { this.metadataLocation = metadataLocation; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(String modifiedAt) { this.modifiedAt = modifiedAt; }
    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }
    public Object getMetadata() { return metadata; }
    public void setMetadata(Object metadata) { this.metadata = metadata; }
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
}
