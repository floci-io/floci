package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisStreamingDestination {

    public static final String PRECISION_MILLISECOND = "MILLISECOND";
    public static final String PRECISION_MICROSECOND = "MICROSECOND";

    private String streamArn;
    private String destinationStatus;
    private String destinationStatusDescription;
    private String approximateCreationDateTimePrecision;

    public KinesisStreamingDestination() {}

    public KinesisStreamingDestination(String streamArn) {
        this(streamArn, PRECISION_MILLISECOND);
    }

    public KinesisStreamingDestination(String streamArn, String approximateCreationDateTimePrecision) {
        this.streamArn = streamArn;
        this.destinationStatus = "ACTIVE";
        this.destinationStatusDescription = "Kinesis streaming is enabled for this table";
        this.approximateCreationDateTimePrecision = approximateCreationDateTimePrecision;
    }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getDestinationStatus() { return destinationStatus; }
    public void setDestinationStatus(String destinationStatus) { this.destinationStatus = destinationStatus; }

    public String getDestinationStatusDescription() { return destinationStatusDescription; }
    public void setDestinationStatusDescription(String desc) { this.destinationStatusDescription = desc; }

    /** Destinations persisted before the precision was stored read back as the AWS default, MILLISECOND. */
    public String getApproximateCreationDateTimePrecision() {
        return approximateCreationDateTimePrecision != null ? approximateCreationDateTimePrecision : PRECISION_MILLISECOND;
    }
    public void setApproximateCreationDateTimePrecision(String precision) {
        this.approximateCreationDateTimePrecision = precision;
    }
}
