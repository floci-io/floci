package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisStreamingDestination {

    private String streamArn;
    private String destinationStatus;
    private String destinationStatusDescription;
    private String approximateCreationDateTimePrecision;

    public KinesisStreamingDestination() {}

    public KinesisStreamingDestination(String streamArn) {
        this.streamArn = streamArn;
        this.destinationStatus = "ACTIVE";
        this.destinationStatusDescription = "Kinesis streaming is enabled for this table";
        this.approximateCreationDateTimePrecision = "MILLISECOND";
    }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getDestinationStatus() { return destinationStatus; }
    public void setDestinationStatus(String destinationStatus) { this.destinationStatus = destinationStatus; }

    public String getDestinationStatusDescription() { return destinationStatusDescription; }
    public void setDestinationStatusDescription(String desc) { this.destinationStatusDescription = desc; }

    public String getApproximateCreationDateTimePrecision() { return approximateCreationDateTimePrecision; }
    public void setApproximateCreationDateTimePrecision(String precision) {
        this.approximateCreationDateTimePrecision = precision;
    }
}
