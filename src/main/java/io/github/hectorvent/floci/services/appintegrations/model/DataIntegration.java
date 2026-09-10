package io.github.hectorvent.floci.services.appintegrations.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DataIntegration {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String kmsKey;
    private String sourceUri;
    private JsonNode scheduleConfiguration;
    private JsonNode fileConfiguration;
    private JsonNode objectConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String accountId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKmsKey() {
        return kmsKey;
    }

    public void setKmsKey(String kmsKey) {
        this.kmsKey = kmsKey;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public void setSourceUri(String sourceUri) {
        this.sourceUri = sourceUri;
    }

    public JsonNode getScheduleConfiguration() {
        return scheduleConfiguration;
    }

    public void setScheduleConfiguration(JsonNode scheduleConfiguration) {
        this.scheduleConfiguration = scheduleConfiguration;
    }

    public JsonNode getFileConfiguration() {
        return fileConfiguration;
    }

    public void setFileConfiguration(JsonNode fileConfiguration) {
        this.fileConfiguration = fileConfiguration;
    }

    public JsonNode getObjectConfiguration() {
        return objectConfiguration;
    }

    public void setObjectConfiguration(JsonNode objectConfiguration) {
        this.objectConfiguration = objectConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
