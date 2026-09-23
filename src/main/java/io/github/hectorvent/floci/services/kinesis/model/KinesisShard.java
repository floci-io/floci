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
    private final Object recordsMonitor = new Object();
    private List<KinesisRecord> records = new ArrayList<>();
    private long prunedRecordCount;
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

    /**
     * A shallow snapshot of this shard's records. Safe to index and iterate without a
     * ConcurrentModificationException even while producers append concurrently. Structural
     * mutation of the returned list does NOT affect the shard (append via {@link #addRecord});
     * element references are shared, so per-element setters still write through.
     */
    public List<KinesisRecord> getRecords() {
        synchronized (recordsMonitor) {
            return new ArrayList<>(records);
        }
    }

    /**
     * Records the shard held at one instant, with the number of records pruned before them.
     * Both values are read under one lock, so a concurrent prune cannot leave the count ahead of
     * the snapshot.
     */
    public RecordsSnapshot snapshotRecords() {
        synchronized (recordsMonitor) {
            return new RecordsSnapshot(new ArrayList<>(records), prunedRecordCount);
        }
    }

    /** A shard's records and the count of records pruned before the first of them. */
    public record RecordsSnapshot(List<KinesisRecord> records, long prunedRecordCount) {}

    /** Appends a record. The sole production mutation path for a shard's log. */
    public void addRecord(KinesisRecord record) {
        synchronized (recordsMonitor) {
            records.add(record);
        }
    }

    /** Number of records currently held, without copying the log. */
    public int recordCount() {
        synchronized (recordsMonitor) {
            return records.size();
        }
    }

    /**
     * Drops every record whose {@link KinesisRecord#getApproximateArrivalTimestamp()} is strictly
     * before {@code cutoff}, freeing the underlying storage rather than merely hiding them from
     * scans (bounded memory, per the Kinesis retention-period contract). Records are always
     * appended in arrival order, so expired records are a contiguous prefix of the log and this is
     * a single prefix trim, not a general filter. A record with a {@code null} timestamp is never
     * pruned (defensive: it should not occur in practice, but pruning it would be unverifiable).
     */
    public void pruneRecordsBefore(Instant cutoff) {
        synchronized (recordsMonitor) {
            int firstRetained = 0;
            while (firstRetained < records.size()) {
                Instant arrival = records.get(firstRetained).getApproximateArrivalTimestamp();
                if (arrival == null || !arrival.isBefore(cutoff)) {
                    break;
                }
                firstRetained++;
            }
            if (firstRetained > 0) {
                records = new ArrayList<>(records.subList(firstRetained, records.size()));
                prunedRecordCount += firstRetained;
            }
        }
    }

    /** Records pruned from the front of the log since the shard was created. */
    public long getPrunedRecordCount() {
        synchronized (recordsMonitor) {
            return prunedRecordCount;
        }
    }

    /** Jackson rehydration only. */
    public void setPrunedRecordCount(long prunedRecordCount) {
        synchronized (recordsMonitor) {
            this.prunedRecordCount = prunedRecordCount;
        }
    }

    /** Jackson rehydration only; not safe for concurrent replacement during live traffic. */
    public void setRecords(List<KinesisRecord> records) {
        synchronized (recordsMonitor) {
            this.records = records == null ? new ArrayList<>() : new ArrayList<>(records);
        }
    }

    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }

    public Instant getCreationTimestamp() { return creationTimestamp; }
    public void setCreationTimestamp(Instant timestamp) { this.creationTimestamp = timestamp; }

    @RegisterForReflection
    public record HashKeyRange(String startingHashKey, String endingHashKey) {}

    @RegisterForReflection
    public record SequenceNumberRange(String startingSequenceNumber, String endingSequenceNumber) {}
}
