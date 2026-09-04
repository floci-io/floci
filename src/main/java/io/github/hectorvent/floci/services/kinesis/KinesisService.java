package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import java.util.ArrayList;
import java.util.Set;

@ApplicationScoped
public class KinesisService implements ResourceProvider {
    private static final Logger LOG = Logger.getLogger(KinesisService.class);
    private static final Set<String> VALID_SHARD_LEVEL_METRICS = Set.of(
            "IncomingBytes", "IncomingRecords", "OutgoingBytes", "OutgoingRecords",
            "WriteProvisionedThroughputExceeded", "ReadProvisionedThroughputExceeded",
            "IteratorAgeMilliseconds", "ALL");
    private static final Set<String> VALID_STREAM_MODES = Set.of("PROVISIONED", "ON_DEMAND");
    private static final String DEFAULT_STREAM_MODE = "PROVISIONED";
    private static final int DEFAULT_INSPECTION_RECORD_LIMIT = 100;
    private static final int MAX_INSPECTION_RECORD_LIMIT = 1000;
    // MaxRecordSizeInKiB bounds, as AWS constrains them on CreateStream and
    // UpdateMaxRecordSize. The per-stream default lives on KinesisStream.
    private static final int MIN_RECORD_SIZE_KIB = 1024;
    private static final int MAX_RECORD_SIZE_KIB = 10240;
    private static final int MAX_RECORDS_PER_REQUEST = 500;
    private static final int MAX_REQUEST_SIZE_BYTES = 10 * 1024 * 1024;

    private final StorageBackend<String, KinesisStream> store;
    private final StorageBackend<String, KinesisConsumer> consumerStore;
    private final RegionResolver regionResolver;
    private final AtomicLong sequenceGenerator = new AtomicLong(System.currentTimeMillis());
    // One monitor per stream key, handed out by lockFor. Serializes sequence allocation + shard
    // append + persist so the in-memory log, the assigned sequence order and the WAL write order
    // all agree, and serializes every other read-modify-write on a stream against deleteStream.
    private final ConcurrentHashMap<String, Object> streamAppendLocks = new ConcurrentHashMap<>();
    // Package-private test seam, run inside the append critical section after the sequence is
    // allocated and before the record is appended. Default no-op in production.
    Runnable putRecordAppendHook = () -> {};
    // Package-private test seam, run after the pre-lock resolve/validation and before the append
    // lock is acquired. Lets a test deterministically wedge a producer in the window where a
    // concurrent deleteStream can win the lock first, exercising the in-lock re-resolve guard.
    // Default no-op in production.
    Runnable putRecordBeforeLockHook = () -> {};

    @Inject
    public KinesisService(StorageFactory factory, RegionResolver regionResolver) {
        this(factory.create("kinesis", "kinesis-streams.json",
                        new TypeReference<Map<String, KinesisStream>>() {}),
                factory.create("kinesis", "kinesis-consumers.json",
                        new TypeReference<Map<String, KinesisConsumer>>() {}),
                regionResolver);
    }

    KinesisService(StorageBackend<String, KinesisStream> store,
                   StorageBackend<String, KinesisConsumer> consumerStore,
                   RegionResolver regionResolver) {
        this.store = store;
        this.consumerStore = consumerStore;
        this.regionResolver = regionResolver;
    }

    public KinesisStream createStream(String streamName, int shardCount, String region) {
        return createStream(streamName, shardCount, null, region);
    }

    public KinesisStream createStream(String streamName, int shardCount, String streamMode, String region) {
        return createStream(streamName, shardCount, streamMode, null, region);
    }

    /**
     * @param maxRecordSizeInKiB CreateStream's optional MaxRecordSizeInKiB; null leaves the stream
     *                           at the AWS default. It is a distinct value from 0, which AWS
     *                           rejects as below the documented minimum.
     */
    public KinesisStream createStream(String streamName, int shardCount, String streamMode,
                                      Integer maxRecordSizeInKiB, String region) {
        String resolvedMode = streamMode != null ? streamMode : DEFAULT_STREAM_MODE;
        if (!VALID_STREAM_MODES.contains(resolvedMode)) {
            throw new AwsException("InvalidArgumentException",
                    "StreamMode must be PROVISIONED or ON_DEMAND, got: " + resolvedMode, 400);
        }
        if (maxRecordSizeInKiB != null) {
            validateMaxRecordSize(maxRecordSizeInKiB, "InvalidArgumentException");
        }

        String storageKey = regionKey(region, streamName);
        if (store.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException", "Stream already exists: " + streamName, 400);
        }

        String arn = regionResolver.buildArn("kinesis", region, "stream/" + streamName);
        KinesisStream stream = new KinesisStream(streamName, arn);
        stream.setAccountId(regionResolver.getAccountId());
        stream.setStreamMode(resolvedMode);
        if (maxRecordSizeInKiB != null) {
            stream.setMaxRecordSizeInKiB(maxRecordSizeInKiB);
        }

        for (int i = 0; i < shardCount; i++) {
            String shardId = String.format("shardId-%012d", i);
            stream.getShards().add(new KinesisShard(shardId, "0", "340282366920938463463374607431768211455", "0"));
        }

        store.put(storageKey, stream);
        LOG.infov("Created Kinesis stream: {0} in region {1} with {2} shards (mode: {3})",
                streamName, region, shardCount, resolvedMode);
        return stream;
    }

    public void updateStreamMode(String streamName, String streamMode, String region) {
        if (streamMode == null || !VALID_STREAM_MODES.contains(streamMode)) {
            throw new AwsException("InvalidArgumentException",
                    "StreamMode must be PROVISIONED or ON_DEMAND, got: " + streamMode, 400);
        }
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            if (!"ACTIVE".equals(stream.getStreamStatus())) {
                throw new AwsException("ResourceInUseException",
                        "Stream " + streamName + " is not ACTIVE (current state: " + stream.getStreamStatus() + ")", 400);
            }
            // Same-mode is a no-op. Mirrors the same-value behaviour in
            // increase/decreaseStreamRetentionPeriod (see #342). Avoids breaking
            // terraform-provider-aws which calls UpdateStreamMode on every refresh.
            if (streamMode.equals(stream.getStreamMode())) {
                return;
            }
            stream.setStreamMode(streamMode);
            store.put(key, stream);
            LOG.infov("Updated stream mode for {0} to {1}", streamName, streamMode);
        }
    }

    public List<String> listStreams(String region) {
        String prefix = region + "::";
        return store.scan(key -> key.startsWith(prefix)).stream()
                .map(KinesisStream::getStreamName)
                .sorted()
                .toList();
    }

    public List<KinesisStream> listStreamDetails(String region) {
        String prefix = region + "::";
        return store.scan(key -> key.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(KinesisStream::getStreamName))
                .toList();
    }

    public KinesisStream describeStream(String streamName, String region) {
        return resolveStream(streamName, region);
    }

    public KinesisConsumer registerStreamConsumer(String streamArn, String consumerName, String region) {
        String consumerArn = streamArn + "/consumer/" + consumerName + ":" + System.currentTimeMillis();
        KinesisConsumer consumer = new KinesisConsumer(consumerName, consumerArn, streamArn);
        consumerStore.put(region + "::" + consumerArn, consumer);
        LOG.infov("Registered Kinesis consumer: {0} for stream {1}", consumerName, streamArn);
        return consumer;
    }

    public void deregisterStreamConsumer(String streamArn, String consumerName, String consumerArn, String region) {
        String resolvedArn = consumerArn;
        if (resolvedArn == null && streamArn != null && consumerName != null) {
            resolvedArn = consumerStore.scan(k -> true).stream()
                    .filter(c -> c.getStreamArn().equals(streamArn) && c.getConsumerName().equals(consumerName))
                    .findFirst().map(KinesisConsumer::getConsumerArn).orElse(null);
        }
        if (resolvedArn != null) {
            consumerStore.delete(region + "::" + resolvedArn);
            LOG.infov("Deregistered Kinesis consumer: {0}", resolvedArn);
        }
    }

    public KinesisConsumer describeStreamConsumer(String streamArn, String consumerName, String consumerArn, String region) {
        if (consumerArn != null) {
            return consumerStore.get(region + "::" + consumerArn)
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Consumer not found", 400));
        }
        return consumerStore.scan(k -> true).stream()
                .filter(c -> c.getStreamArn().equals(streamArn) && c.getConsumerName().equals(consumerName))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Consumer not found", 400));
    }

    public List<KinesisConsumer> listStreamConsumers(String streamArn, String region) {
        return consumerStore.scan(k -> true).stream()
                .filter(c -> c.getStreamArn().equals(streamArn))
                .toList();
    }

    public void deleteStream(String streamName, String region) {
        String storageKey = regionKey(region, streamName);
        // Delete under the per-stream monitor every writer takes, so the delete is serialized with
        // any in-flight append, split, merge or metadata write on this stream. See lockFor for why
        // the monitor is deliberately left in the map.
        synchronized (lockFor(storageKey)) {
            store.delete(storageKey);
        }
        LOG.infov("Deleted Kinesis stream: {0}", streamName);
    }

    public void addTagsToStream(String streamName, Map<String, String> tags, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            stream.getTags().putAll(tags);
            store.put(key, stream);
        }
    }

    public void removeTagsFromStream(String streamName, List<String> tagKeys, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            tagKeys.forEach(stream.getTags()::remove);
            store.put(key, stream);
        }
    }

    public Map<String, String> listTagsForStream(String streamName, String region) {
        return resolveStream(streamName, region).getTags();
    }

    public void startStreamEncryption(String streamName, String encryptionType, String keyId, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            stream.setEncryptionType(encryptionType);
            stream.setKeyId(keyId);
            store.put(key, stream);
        }
    }

    public void increaseStreamRetentionPeriod(String streamName, int retentionPeriodHours, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            if (retentionPeriodHours > 8760) {
                throw new AwsException("InvalidArgumentException",
                        "Retention period must not exceed 8760 hours (365 days)", 400);
            }
            if (retentionPeriodHours < stream.getRetentionPeriodHours()) {
                throw new AwsException("InvalidArgumentException",
                        "Requested retention period (" + retentionPeriodHours +
                        " hours) must not be less than current retention period (" +
                        stream.getRetentionPeriodHours() + " hours)", 400);
            }
            // Same value is a no-op on real AWS despite the API doc wording ("must be more than
            // current"). Proof: terraform-provider-aws calls IncreaseStreamRetentionPeriod on
            // stream creation unconditionally when retention_period is set (stream.go Create path),
            // so every default-retention TF stream would fail if AWS rejected same-value. See #342.
            if (retentionPeriodHours == stream.getRetentionPeriodHours()) {
                return;
            }
            stream.setRetentionPeriodHours(retentionPeriodHours);
            store.put(key, stream);
            LOG.infov("Increased retention period for stream {0} to {1} hours", streamName, retentionPeriodHours);
        }
    }

    public void decreaseStreamRetentionPeriod(String streamName, int retentionPeriodHours, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            if (retentionPeriodHours < 24) {
                throw new AwsException("InvalidArgumentException",
                        "Retention period must not be less than 24 hours", 400);
            }
            if (retentionPeriodHours > stream.getRetentionPeriodHours()) {
                throw new AwsException("InvalidArgumentException",
                        "Requested retention period (" + retentionPeriodHours +
                        " hours) must not be greater than current retention period (" +
                        stream.getRetentionPeriodHours() + " hours)", 400);
            }
            // Same value is a no-op on real AWS (mirrors IncreaseStreamRetentionPeriod). See #342.
            if (retentionPeriodHours == stream.getRetentionPeriodHours()) {
                return;
            }
            stream.setRetentionPeriodHours(retentionPeriodHours);
            store.put(key, stream);
            LOG.infov("Decreased retention period for stream {0} to {1} hours", streamName, retentionPeriodHours);
        }
    }

    public Set<String> enableEnhancedMonitoring(String streamName, List<String> metrics, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            Set<String> current = new HashSet<>(stream.getEnhancedMonitoringMetrics());
            Set<String> desired = resolveMetrics(metrics);
            stream.getEnhancedMonitoringMetrics().addAll(desired);
            store.put(key, stream);
            LOG.infov("Enabled enhanced monitoring for stream {0}: {1}", streamName, desired);
            return current;
        }
    }

    public Set<String> disableEnhancedMonitoring(String streamName, List<String> metrics, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            Set<String> current = new HashSet<>(stream.getEnhancedMonitoringMetrics());
            Set<String> toRemove = resolveMetrics(metrics);
            stream.getEnhancedMonitoringMetrics().removeAll(toRemove);
            store.put(key, stream);
            LOG.infov("Disabled enhanced monitoring for stream {0}: {1}", streamName, toRemove);
            return current;
        }
    }

    private Set<String> resolveMetrics(List<String> metrics) {
        if (metrics.isEmpty()) {
            throw new AwsException("InvalidArgumentException",
                    "ShardLevelMetrics must contain at least one metric", 400);
        }
        // Validate all entries before expanding ALL
        for (String m : metrics) {
            if (!VALID_SHARD_LEVEL_METRICS.contains(m)) {
                throw new AwsException("InvalidArgumentException",
                        "Invalid ShardLevelMetric: " + m, 400);
            }
        }
        if (metrics.contains("ALL")) {
            Set<String> all = new HashSet<>(VALID_SHARD_LEVEL_METRICS);
            all.remove("ALL");
            return all;
        }
        return new HashSet<>(metrics);
    }

    public void stopStreamEncryption(String streamName, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            stream.setEncryptionType("NONE");
            stream.setKeyId(null);
            store.put(key, stream);
        }
    }

    public void splitShard(String streamName, String shardId, String newStartingHashKey, String region) {
        String key = regionKey(region, streamName);
        // Share the producer critical section so a split can't race an append: selectShard runs
        // under the same lock, so a producer never appends to a parent a completed split has closed.
        synchronized (lockFor(key)) {
            // Resolve under the lock so a concurrent deleteStream (same monitor) cannot leave us
            // mutating and re-persisting a stale, deleted instance.
            KinesisStream stream = resolveStream(streamName, region);
            KinesisShard parent = stream.getShards().stream()
                    .filter(s -> s.getShardId().equals(shardId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard " + shardId + " not found", 400));

            if (parent.isClosed()) {
                throw new AwsException("InvalidArgumentException", "Shard " + shardId + " is already closed", 400);
            }

            parent.setClosed(true);
            parent.setSequenceNumberRange(new KinesisShard.SequenceNumberRange(
                    parent.getSequenceNumberRange().startingSequenceNumber(),
                    String.valueOf(sequenceGenerator.get())));

            String start = parent.getHashKeyRange().startingHashKey();
            String end = parent.getHashKeyRange().endingHashKey();

            // Derive both child ids from the base size WITHOUT relying on the first add: nextShardId
            // is size-based, so two sequential adds would either collide (both ids computed pre-add)
            // or expose a half-published topology (child1 visible before child2). Compute base+0 and
            // base+1 up front so the ids stay distinct, then publish BOTH children in one atomic
            // CopyOnWriteArrayList.addAll, so a lock-free reader can never observe {parent, child1}
            // without child2.
            int base = stream.getShards().size();
            KinesisShard child1 = new KinesisShard(String.format("shardId-%012d", base), start, subtractOne(newStartingHashKey), String.valueOf(sequenceGenerator.get()));
            child1.setParentShardId(shardId);

            KinesisShard child2 = new KinesisShard(String.format("shardId-%012d", base + 1), newStartingHashKey, end, String.valueOf(sequenceGenerator.get()));
            child2.setParentShardId(shardId);

            stream.getShards().addAll(List.of(child1, child2));
            store.put(key, stream);
            LOG.infov("Split shard {0} in stream {1}", shardId, streamName);
        }
    }

    public void mergeShards(String streamName, String shardId, String adjacentShardId, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            // Resolve under the lock so a concurrent deleteStream (same monitor) cannot leave us
            // mutating and re-persisting a stale, deleted instance.
            KinesisStream stream = resolveStream(streamName, region);
            KinesisShard shard1 = stream.getShards().stream()
                    .filter(s -> s.getShardId().equals(shardId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard " + shardId + " not found", 400));
            KinesisShard shard2 = stream.getShards().stream()
                    .filter(s -> s.getShardId().equals(adjacentShardId))
                    .findFirst()
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard " + adjacentShardId + " not found", 400));

            if (shard1.isClosed() || shard2.isClosed()) {
                throw new AwsException("InvalidArgumentException", "One or both shards are already closed", 400);
            }

            shard1.setClosed(true);
            shard2.setClosed(true);
            String seq = String.valueOf(sequenceGenerator.get());
            shard1.setSequenceNumberRange(new KinesisShard.SequenceNumberRange(shard1.getSequenceNumberRange().startingSequenceNumber(), seq));
            shard2.setSequenceNumberRange(new KinesisShard.SequenceNumberRange(shard2.getSequenceNumberRange().startingSequenceNumber(), seq));

            // Combine hash ranges (assuming they are adjacent)
            java.math.BigInteger s1Start = new java.math.BigInteger(shard1.getHashKeyRange().startingHashKey());
            java.math.BigInteger s2Start = new java.math.BigInteger(shard2.getHashKeyRange().startingHashKey());

            String start = s1Start.min(s2Start).toString();
            java.math.BigInteger s1End = new java.math.BigInteger(shard1.getHashKeyRange().endingHashKey());
            java.math.BigInteger s2End = new java.math.BigInteger(shard2.getHashKeyRange().endingHashKey());
            String end = s1End.max(s2End).toString();

            KinesisShard child = new KinesisShard(nextShardId(stream), start, end, seq);
            child.setParentShardId(shardId);
            child.setAdjacentParentShardId(adjacentShardId);

            stream.getShards().add(child);
            store.put(key, stream);
            LOG.infov("Merged shards {0} and {1} in stream {2}", shardId, adjacentShardId, streamName);
        }
    }

    private String nextShardId(KinesisStream stream) {
        return String.format("shardId-%012d", stream.getShards().size());
    }

    private String subtractOne(String val) {
        return new java.math.BigInteger(val).subtract(java.math.BigInteger.ONE).toString();
    }

    public record PutRecordResult(String sequenceNumber, String shardId) {}

    public String putRecord(String streamName, byte[] data, String partitionKey, String region) {
        return putRecordWithShardId(streamName, data, partitionKey, region).sequenceNumber();
    }

    /** Record size as AWS measures it: the data blob plus the partition key. */
    static int recordSize(int dataBytes, String partitionKey) {
        return dataBytes
                + (partitionKey != null ? partitionKey.getBytes(StandardCharsets.UTF_8).length : 0);
    }

    public void validateRecordSize(KinesisStream stream, byte[] data, String partitionKey) {
        int size = recordSize(data != null ? data.length : 0, partitionKey);
        int max = stream.getMaxRecordSizeInKiB() * 1024;
        if (size > max) {
            throw new AwsException("InvalidArgumentException",
                    "Record size (data + partition key) of " + size + " bytes exceeds the maximum of "
                            + max + " bytes.", 400);
        }
    }

    /**
     * The request-wide PutRecords caps. Both reject the whole request rather than failing records
     * individually, because PutRecordsResultEntry.ErrorCode carries only throughput and internal
     * errors — the same reason the per-record size check aborts the batch.
     */
    void validateRecordCount(int recordCount) {
        if (recordCount < 1) {
            throw new AwsException("InvalidArgumentException",
                    "A PutRecords request requires at least one record.", 400);
        }
        if (recordCount > MAX_RECORDS_PER_REQUEST) {
            throw new AwsException("InvalidArgumentException",
                    "A PutRecords request supports at most " + MAX_RECORDS_PER_REQUEST
                            + " records, got " + recordCount + ".", 400);
        }
    }

    void validateRequestSize(long totalBytes) {
        if (totalBytes > MAX_REQUEST_SIZE_BYTES) {
            throw new AwsException("InvalidArgumentException",
                    "A PutRecords request (data + partition keys) is limited to " + MAX_REQUEST_SIZE_BYTES
                            + " bytes, got " + totalBytes + ".", 400);
        }
    }

    /**
     * The bounds are the same on both actions but the code each publishes for a violation is not:
     * UpdateMaxRecordSize documents ValidationException for an out-of-range size, while CreateStream
     * reserves ValidationException for the on-demand case and describes InvalidArgumentException as
     * "a specified parameter exceeds its restrictions".
     */
    private static void validateMaxRecordSize(int maxRecordSizeInKiB, String errorCode) {
        if (maxRecordSizeInKiB < MIN_RECORD_SIZE_KIB || maxRecordSizeInKiB > MAX_RECORD_SIZE_KIB) {
            throw new AwsException(errorCode,
                    "MaxRecordSizeInKiB must be between " + MIN_RECORD_SIZE_KIB + " and "
                            + MAX_RECORD_SIZE_KIB + ", got " + maxRecordSizeInKiB + ".", 400);
        }
    }

    public void updateMaxRecordSize(String streamName, int maxRecordSizeInKiB, String region) {
        String key = regionKey(region, streamName);
        synchronized (lockFor(key)) {
            KinesisStream stream = resolveStream(streamName, region);
            validateMaxRecordSize(maxRecordSizeInKiB, "ValidationException");
            if ("ON_DEMAND".equals(stream.getStreamMode())) {
                throw new AwsException("ValidationException",
                        "UpdateMaxRecordSize is only supported for data streams with the provisioned capacity mode.",
                        400);
            }
            if (!"ACTIVE".equals(stream.getStreamStatus())) {
                throw new AwsException("ResourceInUseException",
                        "Stream " + streamName + " is not ACTIVE (current state: "
                                + stream.getStreamStatus() + ")", 400);
            }
            stream.setMaxRecordSizeInKiB(maxRecordSizeInKiB);
            store.put(key, stream);
            LOG.infov("Updated max record size for {0} to {1} KiB", streamName, maxRecordSizeInKiB);
        }
    }

    public PutRecordResult putRecordWithShardId(String streamName, byte[] data, String partitionKey, String region) {
        String key = regionKey(region, streamName);
        KinesisStream stream = resolveStream(streamName, region);
        validateRecordSize(stream, data, partitionKey);
        putRecordBeforeLockHook.run();

        // Serialize shard selection, sequence allocation, append and persistence per stream so
        // concurrent producers can neither lose an append (plain ArrayList) nor store records
        // out of sequence order (which would break AT_/AFTER_SEQUENCE_NUMBER scans and WAL replay).
        synchronized (lockFor(key)) {
            // Re-resolve under the lock. deleteStream holds this same monitor, so a stream deleted
            // between the resolve above and here is now absent from the store. Appending to and
            // persisting the stale pre-delete instance would resurrect a deleted stream, so bind to
            // the instance currently in the store and fail like any missing stream if it is gone.
            KinesisStream current = resolveStream(streamName, region);
            KinesisShard shard = selectShard(current, partitionKey);
            String sequenceNumber = String.valueOf(sequenceGenerator.incrementAndGet());
            putRecordAppendHook.run();
            KinesisRecord record = new KinesisRecord(data, partitionKey, sequenceNumber, Instant.now());
            shard.addRecord(record);
            store.put(key, current);
            return new PutRecordResult(sequenceNumber, shard.getShardId());
        }
    }

    public String getShardIterator(String streamName, String shardId, String type, String sequenceNumber, String region) {
        return getShardIterator(streamName, shardId, type, sequenceNumber, null, region);
    }

    public String getShardIterator(String streamName, String shardId, String type, String sequenceNumber,
                                   Long timestampMillis, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        // Format: streamName|shardId|type|sequenceNumber|index|timestampMillis
        // The 6th slot was added for AT_TIMESTAMP; empty for other iterator types.
        // Old 5-part iterators still decode via split(-1) compatibility in getRecords.
        // For LATEST the index slot carries the shard tip at iterator creation time,
        // so records written afterwards are visible to getRecords.
        String raw = String.format("%s|%s|%s|%s|%d|%s",
                streamName, shardId, type,
                sequenceNumber != null ? sequenceNumber : "",
                iteratorStartIndex(stream, shardId, type),
                timestampMillis != null ? timestampMillis.toString() : "");
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // A shard iterator is an opaque token we mint; a client that replays a garbage one gets
    // InvalidArgumentException, not a 500 from an unguarded base64 decode or Integer.parse.
    private String[] decodeShardIterator(String shardIterator) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(shardIterator);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArgumentException", "Invalid shard iterator", 400);
        }
        // Use limit=-1 so trailing empty slots round-trip and old 5-part iterators still work.
        String[] parts = new String(decoded, StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote("|"), -1);
        if (parts.length < 5) {
            throw new AwsException("InvalidArgumentException", "Invalid shard iterator", 400);
        }
        return parts;
    }

    private int iteratorStartIndex(KinesisStream stream, String shardId, String type) {
        // AWS validates the shard at GetShardIterator time for every iterator type.
        KinesisShard shard = stream.getShards().stream()
                .filter(s -> s.getShardId().equals(shardId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard not found", 400));
        // LATEST resumes from the tip snapshot taken now; other types resolve in getRecords.
        return "LATEST".equals(type) ? shard.recordCount() : 0;
    }

    private int parseIteratorIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidArgumentException", "Invalid shard iterator", 400);
        }
    }

    public Map<String, Object> getRecords(String shardIterator, Integer limit, String region) {
        String[] parts = decodeShardIterator(shardIterator);

        String streamName = parts[0];
        String shardId = parts[1];
        String type = parts[2];
        String startSeq = parts[3];
        int lastIndex = parseIteratorIndex(parts[4]);
        Long timestampMillis = null;
        if (parts.length >= 6 && !parts[5].isEmpty()) {
            try {
                timestampMillis = Long.parseLong(parts[5]);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidArgumentException", "Invalid timestamp in shard iterator", 400);
            }
        }

        KinesisStream stream = resolveStream(streamName, region);
        KinesisShard shard = stream.getShards().stream()
                .filter(s -> s.getShardId().equals(shardId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard not found", 400));

        List<KinesisRecord> allRecords = shard.getRecords();
        int startIndex = 0;

        // Simple implementation of iterator types.
        // LATEST resumes from the shard tip snapshot encoded at GetShardIterator time,
        // so records appended after the iterator was obtained are returned.
        if ("TRIM_HORIZON".equals(type) || "LATEST".equals(type)) {
            startIndex = lastIndex;
        } else if ("AT_SEQUENCE_NUMBER".equals(type)) {
            for (int i = 0; i < allRecords.size(); i++) {
                if (allRecords.get(i).getSequenceNumber().equals(startSeq)) {
                    startIndex = i;
                    break;
                }
            }
        } else if ("AFTER_SEQUENCE_NUMBER".equals(type)) {
             for (int i = 0; i < allRecords.size(); i++) {
                if (allRecords.get(i).getSequenceNumber().equals(startSeq)) {
                    startIndex = i + 1;
                    break;
                }
            }
        } else if ("AT_TIMESTAMP".equals(type)) {
            if (timestampMillis == null) {
                throw new AwsException("InvalidArgumentException",
                        "AT_TIMESTAMP iterator requires a Timestamp", 400);
            }
            // First record with ApproximateArrivalTimestamp >= requested timestamp.
            // If none match (all records predate timestamp or shard is empty), start past end (no records returned, caught up).
            startIndex = allRecords.size();
            for (int i = 0; i < allRecords.size(); i++) {
                Instant arr = allRecords.get(i).getApproximateArrivalTimestamp();
                if (arr != null && arr.toEpochMilli() >= timestampMillis) {
                    startIndex = i;
                    break;
                }
            }
        }

        int max = limit != null ? Math.min(limit, 1000) : 1000;
        List<KinesisRecord> result = new ArrayList<>();
        int nextIndex = startIndex;
        for (int i = startIndex; i < allRecords.size() && result.size() < max; i++) {
            result.add(allRecords.get(i));
            nextIndex = i + 1;
        }

        // Continuation iterator: type=TRIM_HORIZON + resume-at-nextIndex is the existing
        // "resume by index" convention (the type label is misleading but preserved for compat).
        // Timestamp slot empty on continuation.
        String nextIterator = Base64.getEncoder().encodeToString(
                String.format("%s|%s|%s|%s|%d|", streamName, shardId, "TRIM_HORIZON", "", nextIndex)
                .getBytes(StandardCharsets.UTF_8));

        Map<String, Object> response = new HashMap<>();
        response.put("Records", result);
        response.put("NextShardIterator", nextIterator);
        response.put("MillisBehindLatest", computeMillisBehindLatest(allRecords, nextIndex));
        return response;
    }

    public record PeekedRecord(String shardId, KinesisRecord record) {}

    /**
     * Returns up to {@code limit} of the most-recent records across all (or one) shard.
     * Records are returned in ascending arrival-timestamp order (oldest first).
     *
     * <p>With no pagination cursor, {@value MAX_INSPECTION_RECORD_LIMIT} is a hard ceiling
     * on the total number of records reachable in a single call regardless of the {@code limit}
     * parameter.
     */
    public List<PeekedRecord> peekRecords(String streamName, String shardId, Integer limit, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        int resolvedLimit = resolveInspectionRecordLimit(limit);
        if (resolvedLimit == 0) {
            return List.of();
        }

        if (shardId != null && !shardId.isBlank()) {
            boolean exists = stream.getShards().stream().anyMatch(shard -> shard.getShardId().equals(shardId));
            if (!exists) {
                throw new AwsException("ResourceNotFoundException", "Shard " + shardId + " not found", 400);
            }
        }

        Comparator<PeekedRecord> oldestFirst = Comparator.comparing(
                peeked -> peeked.record().getApproximateArrivalTimestamp(),
                Comparator.nullsFirst(Comparator.naturalOrder()));
        PriorityQueue<PeekedRecord> newest = new PriorityQueue<>(oldestFirst);
        stream.getShards().stream()
                .filter(shard -> shardId == null || shardId.isBlank() || shard.getShardId().equals(shardId))
                .forEach(shard -> shard.getRecords().forEach(record -> {
                    newest.add(new PeekedRecord(shard.getShardId(), record));
                    if (newest.size() > resolvedLimit) {
                        newest.poll();
                    }
                }));

        return newest.stream()
                .sorted(Comparator.comparing(peeked -> peeked.record().getApproximateArrivalTimestamp(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Resolves the caller-supplied limit to a value in {@code [0, MAX_INSPECTION_RECORD_LIMIT]}.
     * Both ends are clamped silently, consistent with the behaviour of
     * {@link #getRecords} and {@link #getRecordsForAccount}.
     */
    private int resolveInspectionRecordLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_INSPECTION_RECORD_LIMIT;
        }
        return Math.max(0, Math.min(limit, MAX_INSPECTION_RECORD_LIMIT));
    }

    /**
     * Time delta in ms between the last record returned and the shard tip.
     * Zero when caught up, the shard is empty, or no records were returned.
     */
    private long computeMillisBehindLatest(List<KinesisRecord> allRecords, int nextIndex) {
        if (nextIndex <= 0 || nextIndex >= allRecords.size()) {
            return 0L;
        }
        Instant lastReturned = allRecords.get(nextIndex - 1).getApproximateArrivalTimestamp();
        Instant tip = allRecords.get(allRecords.size() - 1).getApproximateArrivalTimestamp();
        if (lastReturned == null || tip == null) {
            return 0L;
        }
        return Math.max(0L, tip.toEpochMilli() - lastReturned.toEpochMilli());
    }

    private KinesisStream resolveStream(String streamName, String region) {
        return store.get(regionKey(region, streamName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Stream " + streamName + " not found", 400));
    }

    private KinesisStream resolveStreamForAccount(String accountId, String streamName, String region) {
        if (accountId != null && store instanceof AccountAwareStorageBackend<KinesisStream> aware) {
            return aware.getForAccount(accountId, regionKey(region, streamName))
                    .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                            "Stream " + streamName + " not found", 400));
        }
        return resolveStream(streamName, region);
    }

    public String getShardIteratorForAccount(String accountId, String streamName, String shardId,
                                             String type, String sequenceNumber, String region) {
        KinesisStream stream = resolveStreamForAccount(accountId, streamName, region);
        String raw = String.format("%s|%s|%s|%s|%d|",
                streamName, shardId, type,
                sequenceNumber != null ? sequenceNumber : "",
                iteratorStartIndex(stream, shardId, type));
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> getRecordsForAccount(String accountId, String shardIterator,
                                                    Integer limit, String region) {
        String[] parts = decodeShardIterator(shardIterator);
        String streamName = parts[0];
        String shardId = parts[1];
        String type = parts[2];
        String startSeq = parts[3];
        int lastIndex = parseIteratorIndex(parts[4]);

        KinesisStream stream = resolveStreamForAccount(accountId, streamName, region);
        KinesisShard shard = stream.getShards().stream()
                .filter(s -> s.getShardId().equals(shardId))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Shard not found", 400));

        List<KinesisRecord> allRecords = shard.getRecords();
        int startIndex = 0;
        // LATEST resumes from the shard tip snapshot encoded at GetShardIterator time.
        if ("TRIM_HORIZON".equals(type) || "LATEST".equals(type)) {
            startIndex = lastIndex;
        } else if ("AFTER_SEQUENCE_NUMBER".equals(type)) {
            for (int i = 0; i < allRecords.size(); i++) {
                if (allRecords.get(i).getSequenceNumber().equals(startSeq)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        int max = limit != null ? Math.min(limit, 1000) : 1000;
        List<KinesisRecord> result = new ArrayList<>();
        int nextIndex = startIndex;
        for (int i = startIndex; i < allRecords.size() && result.size() < max; i++) {
            result.add(allRecords.get(i));
            nextIndex = i + 1;
        }

        String nextIterator = Base64.getEncoder().encodeToString(
                String.format("%s|%s|%s|%s|%d|", streamName, shardId, "TRIM_HORIZON", "", nextIndex)
                        .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = new HashMap<>();
        response.put("Records", result);
        response.put("NextShardIterator", nextIterator);
        response.put("MillisBehindLatest", computeMillisBehindLatest(allRecords, nextIndex));
        return response;
    }

    private KinesisShard selectShard(KinesisStream stream, String partitionKey) {
        // Simple hash-based shard selection among ALL shards, then resolve to open one
        int index = (int) (Math.abs((long) partitionKey.hashCode()) % stream.getShards().size());
        KinesisShard shard = stream.getShards().get(index);
        
        // If closed, find the first open child (simplified)
        while (shard.isClosed()) {
            KinesisShard finalShard = shard;
            shard = stream.getShards().stream()
                    .filter(s -> finalShard.getShardId().equals(s.getParentShardId()) || finalShard.getShardId().equals(s.getAdjacentParentShardId()))
                    .filter(s -> !s.isClosed())
                    .findFirst()
                    .orElse(shard); // Fallback to itself if no open child found
            if (shard == finalShard) break; // prevent infinite loop
        }
        return shard;
    }

    private String regionKey(String region, String name) {
        return region + "::" + name;
    }

    /**
     * The monitor guarding a stream key. Every path that mutates a stored stream takes it: record
     * appends, split and merge, the metadata writes, and {@link #deleteStream}.
     *
     * <p>Holding it is only half the contract: a writer must also resolve the stream inside the
     * monitor. A stream resolved before the monitor was acquired may already have been deleted, and
     * persisting that stale instance would resurrect it or clobber a stream since recreated under
     * the same name.
     *
     * <p>Monitors are never removed. One stable monitor per key is a bounded leak, whereas dropping
     * one on delete would let a writer that already resolved the stream create a fresh monitor and
     * persist the deleted instance under it, defeating the serialization.
     */
    private Object lockFor(String storageKey) {
        return streamAppendLocks.computeIfAbsent(storageKey, k -> new Object());
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (KinesisStream stream : store.scan(k -> true)) {
            String arn = stream.getStreamArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "kinesis:stream", "kinesis",
                    parsed.region(), parsed.accountId(),
                    stream.getStreamCreationTimestamp() != null
                            ? stream.getStreamCreationTimestamp() : Instant.now(),
                    stream.getTags() != null ? stream.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("kinesis:stream", "kinesis", true));
    }
}
