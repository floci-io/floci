package io.github.hectorvent.floci.services.mediapackage.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class MediaPackageChannel {

    private String id;
    private String arn;
    private String description;
    private String createdAt;
    private Map<String, String> tags;
    private JsonNode hlsIngest;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public JsonNode getHlsIngest() {
        return hlsIngest;
    }

    public void setHlsIngest(JsonNode hlsIngest) {
        this.hlsIngest = hlsIngest;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
