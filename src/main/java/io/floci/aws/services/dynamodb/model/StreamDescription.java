package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StreamDescription {

    private String streamArn;
    private String streamLabel;
    private String streamStatus;
    private String streamViewType;
    private String tableName;
    private Instant creationDateTime;
    private String startingSequenceNumber;

    public StreamDescription() {}

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getStreamLabel() { return streamLabel; }
    public void setStreamLabel(String streamLabel) { this.streamLabel = streamLabel; }

    public String getStreamStatus() { return streamStatus; }
    public void setStreamStatus(String streamStatus) { this.streamStatus = streamStatus; }

    public String getStreamViewType() { return streamViewType; }
    public void setStreamViewType(String streamViewType) { this.streamViewType = streamViewType; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public String getStartingSequenceNumber() { return startingSequenceNumber; }
    public void setStartingSequenceNumber(String startingSequenceNumber) {
        this.startingSequenceNumber = startingSequenceNumber;
    }
}
