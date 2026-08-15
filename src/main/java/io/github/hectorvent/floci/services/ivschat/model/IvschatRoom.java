package io.github.hectorvent.floci.services.ivschat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class IvschatRoom {

    private String arn;
    private String id;
    private String name;
    private Instant createTime;
    private Instant updateTime;
    private int maximumMessageRatePerSecond;
    private int maximumMessageLength;
    private JsonNode messageReviewHandler;
    private Map<String, String> tags;
    private List<String> loggingConfigurationIdentifiers;
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

    public int getMaximumMessageRatePerSecond() {
        return maximumMessageRatePerSecond;
    }

    public void setMaximumMessageRatePerSecond(int maximumMessageRatePerSecond) {
        this.maximumMessageRatePerSecond = maximumMessageRatePerSecond;
    }

    public int getMaximumMessageLength() {
        return maximumMessageLength;
    }

    public void setMaximumMessageLength(int maximumMessageLength) {
        this.maximumMessageLength = maximumMessageLength;
    }

    public JsonNode getMessageReviewHandler() {
        return messageReviewHandler;
    }

    public void setMessageReviewHandler(JsonNode messageReviewHandler) {
        this.messageReviewHandler = messageReviewHandler;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public List<String> getLoggingConfigurationIdentifiers() {
        return loggingConfigurationIdentifiers;
    }

    public void setLoggingConfigurationIdentifiers(List<String> loggingConfigurationIdentifiers) {
        this.loggingConfigurationIdentifiers = loggingConfigurationIdentifiers;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
