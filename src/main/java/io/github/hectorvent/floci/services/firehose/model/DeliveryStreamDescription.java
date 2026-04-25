package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class DeliveryStreamDescription {
    @JsonProperty("DeliveryStreamName")
    private String deliveryStreamName;
    @JsonProperty("DeliveryStreamARN")
    private String deliveryStreamARN;
    @JsonProperty("DeliveryStreamStatus")
    private DeliveryStreamStatus deliveryStreamStatus;
    @JsonProperty("CreateTimestamp")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTimestamp;
    @JsonProperty("Destinations")
    private List<Destination> destinations;

    public DeliveryStreamDescription() {}
    public DeliveryStreamDescription(String name, String arn, S3Destination s3) {
        this.deliveryStreamName = name;
        this.deliveryStreamARN = arn;
        this.deliveryStreamStatus = DeliveryStreamStatus.ACTIVE;
        this.createTimestamp = Instant.now();
        this.destinations = List.of(new Destination(s3));
    }

    public String getDeliveryStreamName() { return deliveryStreamName; }
    public void setDeliveryStreamName(String deliveryStreamName) { this.deliveryStreamName = deliveryStreamName; }
    public String getDeliveryStreamARN() { return deliveryStreamARN; }
    public void setDeliveryStreamARN(String deliveryStreamARN) { this.deliveryStreamARN = deliveryStreamARN; }
    public DeliveryStreamStatus getDeliveryStreamStatus() { return deliveryStreamStatus; }
    public void setDeliveryStreamStatus(DeliveryStreamStatus deliveryStreamStatus) { this.deliveryStreamStatus = deliveryStreamStatus; }
    public Instant getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(Instant createTimestamp) { this.createTimestamp = createTimestamp; }
    public List<Destination> getDestinations() { return destinations; }
    public void setDestinations(List<Destination> destinations) { this.destinations = destinations; }

    /** Convenience: returns the first S3 destination, or null if none. */
    public S3Destination s3Destination() {
        if (destinations == null || destinations.isEmpty()) return null;
        return destinations.get(0).getS3DestinationDescription();
    }

    @RegisterForReflection
    public static class Destination {
        @JsonProperty("S3DestinationDescription")
        private S3Destination s3DestinationDescription;

        public Destination() {}
        public Destination(S3Destination s3) { this.s3DestinationDescription = s3; }
        public S3Destination getS3DestinationDescription() { return s3DestinationDescription; }
        public void setS3DestinationDescription(S3Destination s3) { this.s3DestinationDescription = s3; }
    }

    @RegisterForReflection
    public static class S3Destination {
        @JsonProperty("BucketARN")
        private String bucketArn;
        @JsonProperty("Prefix")
        private String prefix;
        @JsonProperty("BufferingHints")
        private BufferingHints bufferingHints;

        public S3Destination() {}
        public String getBucketArn() { return bucketArn; }
        public void setBucketArn(String bucketArn) { this.bucketArn = bucketArn; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public BufferingHints getBufferingHints() { return bufferingHints; }
        public void setBufferingHints(BufferingHints bufferingHints) { this.bufferingHints = bufferingHints; }

        /** Extracts bucket name from ARN: arn:aws:s3:::my-bucket → my-bucket */
        public String bucketName() {
            if (bucketArn == null) return null;
            int last = bucketArn.lastIndexOf(':');
            return last >= 0 ? bucketArn.substring(last + 1) : bucketArn;
        }
    }

    @RegisterForReflection
    public static class BufferingHints {
        @JsonProperty("SizeInMBs")
        private int sizeInMBs = 5;
        @JsonProperty("IntervalInSeconds")
        private int intervalInSeconds = 300;

        public BufferingHints() {}
        public int getSizeInMBs() { return sizeInMBs; }
        public void setSizeInMBs(int sizeInMBs) { this.sizeInMBs = sizeInMBs; }
        public int getIntervalInSeconds() { return intervalInSeconds; }
        public void setIntervalInSeconds(int intervalInSeconds) { this.intervalInSeconds = intervalInSeconds; }
    }
}
