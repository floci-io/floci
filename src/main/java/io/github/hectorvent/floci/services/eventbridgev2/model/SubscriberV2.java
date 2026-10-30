package io.github.hectorvent.floci.services.eventbridgev2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An EventBridge V2 subscriber. The configuration structures are kept as the request gave them, as the
 * subscriber returns them.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriberV2 {
    private String name;
    private String arn;
    private String eventBusArn;
    private String description;
    private String type;
    private String startingPosition;
    private String state;
    private JsonNode pointInTimeConfiguration;
    private JsonNode invokeConfiguration;
    private JsonNode filterConfiguration;
    private JsonNode transformer;
    private JsonNode batchConfiguration;
    private JsonNode retryPolicy;
    private JsonNode onFailureConfiguration;
    private JsonNode logConfiguration;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String createRequest;

    public SubscriberV2() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getEventBusArn() {
        return eventBusArn;
    }

    public void setEventBusArn(String eventBusArn) {
        this.eventBusArn = eventBusArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStartingPosition() {
        return startingPosition;
    }

    public void setStartingPosition(String startingPosition) {
        this.startingPosition = startingPosition;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public JsonNode getPointInTimeConfiguration() {
        return pointInTimeConfiguration;
    }

    public void setPointInTimeConfiguration(JsonNode pointInTimeConfiguration) {
        this.pointInTimeConfiguration = pointInTimeConfiguration;
    }

    public JsonNode getInvokeConfiguration() {
        return invokeConfiguration;
    }

    public void setInvokeConfiguration(JsonNode invokeConfiguration) {
        this.invokeConfiguration = invokeConfiguration;
    }

    public JsonNode getFilterConfiguration() {
        return filterConfiguration;
    }

    public void setFilterConfiguration(JsonNode filterConfiguration) {
        this.filterConfiguration = filterConfiguration;
    }

    public JsonNode getTransformer() {
        return transformer;
    }

    public void setTransformer(JsonNode transformer) {
        this.transformer = transformer;
    }

    public JsonNode getBatchConfiguration() {
        return batchConfiguration;
    }

    public void setBatchConfiguration(JsonNode batchConfiguration) {
        this.batchConfiguration = batchConfiguration;
    }

    public JsonNode getRetryPolicy() {
        return retryPolicy;
    }

    public void setRetryPolicy(JsonNode retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public JsonNode getOnFailureConfiguration() {
        return onFailureConfiguration;
    }

    public void setOnFailureConfiguration(JsonNode onFailureConfiguration) {
        this.onFailureConfiguration = onFailureConfiguration;
    }

    public JsonNode getLogConfiguration() {
        return logConfiguration;
    }

    public void setLogConfiguration(JsonNode logConfiguration) {
        this.logConfiguration = logConfiguration;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public Instant getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(Instant lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getCreateRequest() {
        return createRequest;
    }

    public void setCreateRequest(String createRequest) {
        this.createRequest = createRequest;
    }
}
