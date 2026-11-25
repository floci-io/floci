package io.floci.aws.services.eventbridgev2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An EventBridge V2 webhook event source. The configuration is kept in the form the source returns it, without
 * the credentials, which live in the secret.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventSourceV2 {
    private String name;
    private String arn;
    private String eventBusArn;
    private String description;
    private JsonNode configuration;
    private String state;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String createRequestHash;
    private String webhookHost;
    private String webhookToken;
    private String secretArn;
    private Map<String, String> rotationRequests = new LinkedHashMap<>();

    public EventSourceV2() {
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

    public JsonNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(JsonNode configuration) {
        this.configuration = configuration;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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
        this.tags = tags;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getCreateRequestHash() {
        return createRequestHash;
    }

    public void setCreateRequestHash(String createRequestHash) {
        this.createRequestHash = createRequestHash;
    }

    public String getWebhookHostId() {
        return webhookHost;
    }

    public void setWebhookHostId(String webhookHost) {
        this.webhookHost = webhookHost;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public String getSecretArn() {
        return secretArn;
    }

    public void setSecretArn(String secretArn) {
        this.secretArn = secretArn;
    }

    /** The ClientToken of each rotation, oldest first, with a hash of the request it came with. */
    public Map<String, String> getRotationRequests() {
        return rotationRequests;
    }

    public void setRotationRequests(Map<String, String> rotationRequests) {
        this.rotationRequests = rotationRequests == null ? new LinkedHashMap<>() : rotationRequests;
    }
}
