package io.github.hectorvent.floci.services.ivschat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

public class IvschatLoggingConfiguration {

    private String arn;
    private String id;
    private String name;
    private Instant createTime;
    private Instant updateTime;
    private JsonNode destinationConfiguration;
    private Map<String, String> tags;
    private String accountId;

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }

    public Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }

    public JsonNode getDestinationConfiguration() {
        return destinationConfiguration;
    }

    public void setDestinationConfiguration(JsonNode destinationConfiguration) {
        this.destinationConfiguration = destinationConfiguration;
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
