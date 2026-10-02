package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamoDbStreamRecord {

    private String eventId;
    private String eventVersion;
    private String eventName;
    private String eventSource;
    private String awsRegion;
    private String sequenceNumber;
    private long approximateCreationDateTime;
    private JsonNode keys;
    private JsonNode newImage;
    private JsonNode oldImage;
    private String streamViewType;
    private boolean ttlDeletion;

    public DynamoDbStreamRecord() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventVersion() { return eventVersion; }
    public void setEventVersion(String eventVersion) { this.eventVersion = eventVersion; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getEventSource() { return eventSource; }
    public void setEventSource(String eventSource) { this.eventSource = eventSource; }

    public String getAwsRegion() { return awsRegion; }
    public void setAwsRegion(String awsRegion) { this.awsRegion = awsRegion; }

    public String getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(String sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public long getApproximateCreationDateTime() { return approximateCreationDateTime; }
    public void setApproximateCreationDateTime(long approximateCreationDateTime) {
        this.approximateCreationDateTime = approximateCreationDateTime;
    }

    public JsonNode getKeys() { return keys; }
    public void setKeys(JsonNode keys) { this.keys = keys; }

    public JsonNode getNewImage() { return newImage; }
    public void setNewImage(JsonNode newImage) { this.newImage = newImage; }

    public JsonNode getOldImage() { return oldImage; }
    public void setOldImage(JsonNode oldImage) { this.oldImage = oldImage; }

    public String getStreamViewType() { return streamViewType; }
    public void setStreamViewType(String streamViewType) { this.streamViewType = streamViewType; }

    public boolean isTtlDeletion() { return ttlDeletion; }
    public void setTtlDeletion(boolean ttlDeletion) { this.ttlDeletion = ttlDeletion; }
}
