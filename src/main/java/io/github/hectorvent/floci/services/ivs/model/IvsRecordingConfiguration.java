package io.github.hectorvent.floci.services.ivs.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class IvsRecordingConfiguration {

    private String arn;
    private String name;
    private JsonNode destinationConfiguration;
    private JsonNode thumbnailConfiguration;
    private JsonNode renditionConfiguration;
    private Integer recordingReconnectWindowSeconds;
    private Map<String, String> tags;
    private String accountId;

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

    public JsonNode getDestinationConfiguration() {
        return destinationConfiguration;
    }

    public void setDestinationConfiguration(JsonNode destinationConfiguration) {
        this.destinationConfiguration = destinationConfiguration;
    }

    public JsonNode getThumbnailConfiguration() {
        return thumbnailConfiguration;
    }

    public void setThumbnailConfiguration(JsonNode thumbnailConfiguration) {
        this.thumbnailConfiguration = thumbnailConfiguration;
    }

    public JsonNode getRenditionConfiguration() {
        return renditionConfiguration;
    }

    public void setRenditionConfiguration(JsonNode renditionConfiguration) {
        this.renditionConfiguration = renditionConfiguration;
    }

    public Integer getRecordingReconnectWindowSeconds() {
        return recordingReconnectWindowSeconds;
    }

    public void setRecordingReconnectWindowSeconds(Integer recordingReconnectWindowSeconds) {
        this.recordingReconnectWindowSeconds = recordingReconnectWindowSeconds;
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
