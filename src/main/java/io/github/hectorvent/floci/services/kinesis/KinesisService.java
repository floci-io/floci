package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class KinesisService {
    private static final Logger LOG = Logger.getLogger(KinesisService.class);
    private static final Set<String> VALID_SHARD_LEVEL_METRICS = Set.of(
            "IncomingBytes", "IncomingRecords", "OutgoingBytes", "OutgoingRecords",
            "WriteProvisionedThroughputExceeded", "ReadProvisionedThroughputExceeded",
            "IteratorAgeMilliseconds", "ALL");
    private static final Set<String> VALID_STREAM_MODES = Set.of("PROVISIONED", "ON_DEMAND");
    private static final String DEFAULT_STREAM_MODE = "PROVISIONED";

    private final StorageBackend<String, KinesisStream> store;
    private final StorageBackend<String, KinesisConsumer> consumerStore;
    private final RegionResolver regionResolver;
    private final AtomicLong sequenceGenerator = new AtomicLong(System.currentTimeMillis());

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
        String resolvedMode = streamMode != null ? streamMode : DEFAULT_STREAM_MODE;
        if (!VALID_STREAM_MODES.contains(resolvedMode)) {
            throw new AwsException("InvalidArgumentException",
                    "StreamMode must be PROVISIONED or ON_DEMAND, got: " + resolvedMode, 400);
        }

        String storageKey = regionKey(region, streamName);
        if (store.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException", "Stream already exists: " + streamName, 400);
        }

        String arn = regionResolver.buildArn("kinesis", region, "stream/" + streamName);
        KinesisStream stream = new KinesisStream(streamName, arn);
        stream.setStreamMode(resolvedMode);

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
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Updated stream mode for {0} to {1}", streamName, streamMode);
    }

    public List<String> listStreams(String region) {
        String prefix = region + "::";
        return store.scan(key -> key.startsWith(prefix)).stream()
                .map(KinesisStream::getStreamName)
                .sorted()
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
        store.delete(storageKey);
        LOG.infov("Deleted Kinesis stream: {0}", streamName);
    }

    public void addTagsToStream(String streamName, Map<String, String> tags, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        stream.getTags().putAll(tags);
        store.put(regionKey(region, streamName), stream);
    }

    public void removeTagsFromStream(String streamName, List<String> tagKeys, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        tagKeys.forEach(stream.getTags()::remove);
        store.put(regionKey(region, streamName), stream);
    }

    public Map<String, String> listTagsForStream(String streamName, String region) {
        return resolveStream(streamName, region).getTags();
    }

    public void startStreamEncryption(String streamName, String encryptionType, String keyId, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        stream.setEncryptionType(encryptionType);
        stream.setKeyId(keyId);
        store.put(regionKey(region, streamName), stream);
    }

    public void increaseStreamRetentionPeriod(String streamName, int retentionPeriodHours, String region) {
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
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Increased retention period for stream {0} to {1} hours", streamName, retentionPeriodHours);
    }

    public void decreaseStreamRetentionPeriod(String streamName, int retentionPeriodHours, String region) {
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
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Decreased retention period for stream {0} to {1} hours", streamName, retentionPeriodHours);
    }

    public Set<String> enableEnhancedMonitoring(String streamName, List<String> metrics, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        Set<String> current = new HashSet<>(stream.getEnhancedMonitoringMetrics());
        Set<String> desired = resolveMetrics(metrics);
        stream.getEnhancedMonitoringMetrics().addAll(desired);
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Enabled enhanced monitoring for stream {0}: {1}", streamName, desired);
        return current;
    }

    public Set<String> disableEnhancedMonitoring(String streamName, List<String> metrics, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        Set<String> current = new HashSet<>(stream.getEnhancedMonitoringMetrics());
        Set<String> toRemove = resolveMetrics(metrics);
        stream.getEnhancedMonitoringMetrics().removeAll(toRemove);
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Disabled enhanced monitoring for stream {0}: {1}", streamName, toRemove);
        return current;
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
        KinesisStream stream = resolveStream(streamName, region);
        stream.setEncryptionType("NONE");
        stream.setKeyId(null);
        store.put(regionKey(region, streamName), stream);
    }

    public void splitShard(String streamName, String shardId, String newStartingHashKey, String region) {
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

        KinesisShard child1 = new KinesisShard(nextShardId(stream), start, subtractOne(newStartingHashKey), String.valueOf(sequenceGenerator.get()));
        child1.setParentShardId(shardId);

        KinesisShard child2 = new KinesisShard(nextShardId(stream), newStartingHashKey, end, String.valueOf(sequenceGenerator.get()));
        child2.setParentShardId(shardId);

        stream.getShards().add(child1);
        stream.getShards().add(child2);
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Split shard {0} in stream {1}", shardId, streamName);
    }

    public void mergeShards(String streamName, String shardId, String adjacentShardId, String region) {
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
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Merged shards {0} and {1} in stream {2}", shardId, adjacentShardId, streamName);
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

    public PutRecordResult putRecordWithShardId(String streamName, byte[] data, String partitionKey, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        KinesisShard shard = selectShard(stream, partitionKey);

        String sequenceNumber = String.valueOf(sequenceGenerator.incrementAndGet());
        KinesisRecord record = new KinesisRecord(data, partitionKey, sequenceNumber, Instant.now());

        shard.getRecords().add(record);
        store.put(regionKey(region, streamName), stream);

        return new PutRecordResult(sequenceNumber, shard.getShardId());
    }

    public String getShardIterator(String streamName, String shardId, String type, String sequenceNumber, String region) {
        return getShardIterator(streamName, shardId, type, sequenceNumber, null, region);
    }

    public String getShardIterator(String streamName, String shardId, String type, String sequenceNumber,
                                   Long timestampMillis, String region) {
        resolveStream(streamName, region); // validate exists
        // Format: streamName|shardId|type|sequenceNumber|index|timestampMillis
        // The 6th slot was added for AT_TIMESTAMP; empty for other iterator types.
        // Old 5-part iterators still decode via split(-1) compatibility in getRecords.
        String raw = String.format("%s|%s|%s|%s|%d|%s",
                streamName, shardId, type,
                sequenceNumber != null ? sequenceNumber : "",
                0,
                timestampMillis != null ? timestampMillis.toString() : "");
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> getRecords(String shardIterator, Integer limit, String region) {
        byte[] decoded = Base64.getDecoder().decode(shardIterator);
        // Use limit=-1 so trailing empty slots round-trip and old 5-part iterators still work.
        String[] parts = new String(decoded, StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote("|"), -1);
        if (parts.length < 5) throw new AwsException("InvalidArgumentException", "Invalid shard iterator", 400);

        String streamName = parts[0];
        String shardId = parts[1];
        String type = parts[2];
        String startSeq = parts[3];
        int lastIndex = Integer.parseInt(parts[4]);
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

        // Simple implementation of iterator types
        if ("TRIM_HORIZON".equals(type)) {
            startIndex = lastIndex;
        } else if ("LATEST".equals(type)) {
            startIndex = allRecords.size();
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

    private KinesisShard selectShard(KinesisStream stream, String partitionKey) {
        // Simple hash-based shard selection among ALL shards, then resolve to open one
        int index = Math.abs(partitionKey.hashCode()) % stream.getShards().size();
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
}
