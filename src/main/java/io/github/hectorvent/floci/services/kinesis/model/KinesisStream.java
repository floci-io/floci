package io.github.hectorvent.floci.services.kinesis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisStream {
    private String streamName;
    private String streamArn;
    private String streamStatus;
    private List<KinesisShard> shards = new ArrayList<>();
    private int retentionPeriodHours = 24;
    private Instant streamCreationTimestamp;
    private Map<String, String> tags = new HashMap<>();
    private String encryptionType = "NONE";
    private String keyId;
    private String streamMode = "PROVISIONED";
    private Set<String> enhancedMonitoringMetrics = new HashSet<>();

    public KinesisStream() {}

    public KinesisStream(String streamName, String streamArn) {
        this.streamName = streamName;
        this.streamArn = streamArn;
        this.streamStatus = "ACTIVE";
        this.streamCreationTimestamp = Instant.now();
    }

    public String getStreamName() { return streamName; }
    public void setStreamName(String streamName) { this.streamName = streamName; }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getStreamStatus() { return streamStatus; }
    public void setStreamStatus(String streamStatus) { this.streamStatus = streamStatus; }

    public List<KinesisShard> getShards() { return shards; }
    public void setShards(List<KinesisShard> shards) { this.shards = shards; }

    public int getRetentionPeriodHours() { return retentionPeriodHours; }
    public void setRetentionPeriodHours(int retentionPeriodHours) { this.retentionPeriodHours = retentionPeriodHours; }

    public Instant getStreamCreationTimestamp() { return streamCreationTimestamp; }
    public void setStreamCreationTimestamp(Instant timestamp) { this.streamCreationTimestamp = timestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getEncryptionType() { return encryptionType; }
    public void setEncryptionType(String encryptionType) { this.encryptionType = encryptionType; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getStreamMode() { return streamMode; }
    public void setStreamMode(String streamMode) { this.streamMode = streamMode; }

    public Set<String> getEnhancedMonitoringMetrics() { return enhancedMonitoringMetrics; }
    public void setEnhancedMonitoringMetrics(Set<String> enhancedMonitoringMetrics) { this.enhancedMonitoringMetrics = enhancedMonitoringMetrics; }
}
