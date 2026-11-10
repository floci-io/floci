package io.floci.aws.services.kinesis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisRecord {
    private byte[] data;
    private String partitionKey;
    private String sequenceNumber;
    private Instant approximateArrivalTimestamp;

    public KinesisRecord() {}

    public KinesisRecord(byte[] data, String partitionKey, String sequenceNumber, Instant timestamp) {
        this.data = data;
        this.partitionKey = partitionKey;
        this.sequenceNumber = sequenceNumber;
        this.approximateArrivalTimestamp = timestamp;
    }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public String getPartitionKey() { return partitionKey; }
    public void setPartitionKey(String partitionKey) { this.partitionKey = partitionKey; }

    public String getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(String sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public Instant getApproximateArrivalTimestamp() { return approximateArrivalTimestamp; }
    public void setApproximateArrivalTimestamp(Instant timestamp) { this.approximateArrivalTimestamp = timestamp; }
}
