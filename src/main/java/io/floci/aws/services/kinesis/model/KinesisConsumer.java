package io.floci.aws.services.kinesis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisConsumer {
    private String consumerName;
    private String consumerArn;
    private String consumerStatus;
    private Instant consumerCreationTimestamp;
    private String streamArn;
    private Map<String, String> tags = new HashMap<>();

    public KinesisConsumer() {}

    public KinesisConsumer(String consumerName, String consumerArn, String streamArn) {
        this.consumerName = consumerName;
        this.consumerArn = consumerArn;
        this.streamArn = streamArn;
        this.consumerStatus = "ACTIVE";
        this.consumerCreationTimestamp = Instant.now();
    }

    public String getConsumerName() { return consumerName; }
    public void setConsumerName(String consumerName) { this.consumerName = consumerName; }

    public String getConsumerArn() { return consumerArn; }
    public void setConsumerArn(String consumerArn) { this.consumerArn = consumerArn; }

    public String getConsumerStatus() { return consumerStatus; }
    public void setConsumerStatus(String consumerStatus) { this.consumerStatus = consumerStatus; }

    public Instant getConsumerCreationTimestamp() { return consumerCreationTimestamp; }
    public void setConsumerCreationTimestamp(Instant timestamp) { this.consumerCreationTimestamp = timestamp; }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new HashMap<>() : tags; }
}
