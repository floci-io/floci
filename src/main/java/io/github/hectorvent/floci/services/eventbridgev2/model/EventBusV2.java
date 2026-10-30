package io.github.hectorvent.floci.services.eventbridgev2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventBusV2 {
    private String name;
    private String arn;
    private String description;
    private String kmsKeyIdentifier;
    private Integer retentionPeriodInDays;
    private String state;
    private Instant creationTime;
    private Instant lastModifiedTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;
    private String createRequest;
    private long sequenceNumber;

    public EventBusV2() {
    }

    /** The sequence number of the last event the bus accepted; the next is one more. */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKmsKeyIdentifier() {
        return kmsKeyIdentifier;
    }

    public void setKmsKeyIdentifier(String kmsKeyIdentifier) {
        this.kmsKeyIdentifier = kmsKeyIdentifier;
    }

    public Integer getRetentionPeriodInDays() {
        return retentionPeriodInDays;
    }

    public void setRetentionPeriodInDays(Integer retentionPeriodInDays) {
        this.retentionPeriodInDays = retentionPeriodInDays;
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
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    /** The {@code ClientToken} the bus was created with, which makes a retried create return it. */
    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    /** The create request without its client token, which a retry with the same token must repeat. */
    public String getCreateRequest() {
        return createRequest;
    }

    public void setCreateRequest(String createRequest) {
        this.createRequest = createRequest;
    }
}
