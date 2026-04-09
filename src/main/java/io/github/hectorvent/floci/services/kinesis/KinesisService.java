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
        String storageKey = regionKey(region, streamName);
        if (store.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException", "Stream already exists: " + streamName, 400);
        }

        String arn = regionResolver.buildArn("kinesis", region, "stream/" + streamName);
        KinesisStream stream = new KinesisStream(streamName, arn);

        for (int i = 0; i < shardCount; i++) {
            String shardId = String.format("shardId-%012d", i);
            stream.getShards().add(new KinesisShard(shardId, "0", "340282366920938463463374607431768211455", "0"));
        }

        store.put(storageKey, stream);
        LOG.infov("Created Kinesis stream: {0} in region {1} with {2} shards", streamName, region, shardCount);
        return stream;
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
        if (retentionPeriodHours <= stream.getRetentionPeriodHours()) {
            throw new AwsException("InvalidArgumentException",
                    "Requested retention period (" + retentionPeriodHours +
                    " hours) must be greater than current retention period (" +
                    stream.getRetentionPeriodHours() + " hours)", 400);
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
        if (retentionPeriodHours >= stream.getRetentionPeriodHours()) {
            throw new AwsException("InvalidArgumentException",
                    "Requested retention period (" + retentionPeriodHours +
                    " hours) must be less than current retention period (" +
                    stream.getRetentionPeriodHours() + " hours)", 400);
        }
        stream.setRetentionPeriodHours(retentionPeriodHours);
        store.put(regionKey(region, streamName), stream);
        LOG.infov("Decreased retention period for stream {0} to {1} hours", streamName, retentionPeriodHours);
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

    public String putRecord(String streamName, byte[] data, String partitionKey, String region) {
        KinesisStream stream = resolveStream(streamName, region);
        KinesisShard shard = selectShard(stream, partitionKey);
        
        String sequenceNumber = String.valueOf(sequenceGenerator.incrementAndGet());
        KinesisRecord record = new KinesisRecord(data, partitionKey, sequenceNumber, Instant.now());
        
        shard.getRecords().add(record);
        store.put(regionKey(region, streamName), stream);
        
        return sequenceNumber;
    }

    public String getShardIterator(String streamName, String shardId, String type, String sequenceNumber, String region) {
        resolveStream(streamName, region); // validate exists
        // Format: streamName|shardId|type|sequenceNumber|index
        String raw = String.format("%s|%s|%s|%s|%d", 
                streamName, shardId, type, sequenceNumber != null ? sequenceNumber : "", 0);
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> getRecords(String shardIterator, Integer limit, String region) {
        byte[] decoded = Base64.getDecoder().decode(shardIterator);
        String[] parts = new String(decoded, StandardCharsets.UTF_8).split(java.util.regex.Pattern.quote("|"), 5);
        if (parts.length < 5) throw new AwsException("InvalidArgumentException", "Invalid shard iterator", 400);

        String streamName = parts[0];
        String shardId = parts[1];
        String type = parts[2];
        String startSeq = parts[3];
        int lastIndex = Integer.parseInt(parts[4]);

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
        }

        int max = limit != null ? Math.min(limit, 1000) : 1000;
        List<KinesisRecord> result = new ArrayList<>();
        int nextIndex = startIndex;
        for (int i = startIndex; i < allRecords.size() && result.size() < max; i++) {
            result.add(allRecords.get(i));
            nextIndex = i + 1;
        }

        String nextIterator = Base64.getEncoder().encodeToString(
                String.format("%s|%s|%s|%s|%d", streamName, shardId, "TRIM_HORIZON", "", nextIndex)
                .getBytes(StandardCharsets.UTF_8));

        Map<String, Object> response = new HashMap<>();
        response.put("Records", result);
        response.put("NextShardIterator", nextIterator);
        response.put("MillisBehindLatest", Math.max(0, allRecords.size() - nextIndex));
        return response;
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
