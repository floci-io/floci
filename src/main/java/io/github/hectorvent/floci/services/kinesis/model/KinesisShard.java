package io.github.hectorvent.floci.services.kinesis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisShard {
    private String shardId;
    private String parentShardId;
    private String adjacentParentShardId;
    private HashKeyRange hashKeyRange;
    private SequenceNumberRange sequenceNumberRange;
    private List<KinesisRecord> records = new ArrayList<>();
    private boolean closed = false;
    private Instant creationTimestamp = Instant.now();

    public KinesisShard() {}

    public KinesisShard(String shardId, String startingHashKey, String endingHashKey, String startingSequenceNumber) {
        this.shardId = shardId;
        this.hashKeyRange = new HashKeyRange(startingHashKey, endingHashKey);
        this.sequenceNumberRange = new SequenceNumberRange(startingSequenceNumber, null);
    }

    public String getShardId() { return shardId; }
    public void setShardId(String shardId) { this.shardId = shardId; }

    public String getParentShardId() { return parentShardId; }
    public void setParentShardId(String parentShardId) { this.parentShardId = parentShardId; }

    public String getAdjacentParentShardId() { return adjacentParentShardId; }
    public void setAdjacentParentShardId(String adjacentParentShardId) { this.adjacentParentShardId = adjacentParentShardId; }

    public HashKeyRange getHashKeyRange() { return hashKeyRange; }
    public void setHashKeyRange(HashKeyRange range) { this.hashKeyRange = range; }

    public SequenceNumberRange getSequenceNumberRange() { return sequenceNumberRange; }
    public void setSequenceNumberRange(SequenceNumberRange range) { this.sequenceNumberRange = range; }

    public List<KinesisRecord> getRecords() { return records; }
    public void setRecords(List<KinesisRecord> records) { this.records = records; }

    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }

    public Instant getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(Instant timestamp) { this.creationTimestamp = timestamp; }

    @RegisterForReflection
    public record HashKeyRange(String startingHashKey, String endingHashKey) {}

    @RegisterForReflection
    public record SequenceNumberRange(String startingSequenceNumber, String endingSequenceNumber) {}
}
