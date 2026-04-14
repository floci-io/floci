package io.github.hectorvent.floci.services.firehose.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;

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

    public DeliveryStreamDescription() {}
    public DeliveryStreamDescription(String name, String arn) {
        this.deliveryStreamName = name;
        this.deliveryStreamARN = arn;
        this.deliveryStreamStatus = DeliveryStreamStatus.ACTIVE;
        this.createTimestamp = Instant.now();
    }

    public String getDeliveryStreamName() { return deliveryStreamName; }
    public void setDeliveryStreamName(String deliveryStreamName) { this.deliveryStreamName = deliveryStreamName; }
    public String getDeliveryStreamARN() { return deliveryStreamARN; }
    public void setDeliveryStreamARN(String deliveryStreamARN) { this.deliveryStreamARN = deliveryStreamARN; }
    public DeliveryStreamStatus getDeliveryStreamStatus() { return deliveryStreamStatus; }
    public void setDeliveryStreamStatus(DeliveryStreamStatus deliveryStreamStatus) { this.deliveryStreamStatus = deliveryStreamStatus; }
    public Instant getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(Instant createTimestamp) { this.createTimestamp = createTimestamp; }
}
