package io.github.hectorvent.floci.services.connect.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ConnectStorageConfig {

    private String associationId;
    private String instanceId;
    private String resourceType;
    private String storageType;
    private JsonNode storageConfig;

    public String getAssociationId() {
        return associationId;
    }

    public void setAssociationId(String associationId) {
        this.associationId = associationId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public JsonNode getStorageConfig() {
        return storageConfig;
    }

    public void setStorageConfig(JsonNode storageConfig) {
        this.storageConfig = storageConfig;
    }
}
