package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.KinesisStreamSource;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FirehoseService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);
    private static final String DEFAULT_BUCKET = "floci-firehose-results";
    private static final int DEFAULT_BUFFERING_INTERVAL_SECONDS = 300;
    private static final int DEFAULT_BUFFERING_SIZE_MBS = 5;
    /**
     * botocore's {@code AWSKMSKeyARNForSSE} shape: a KMS <em>key</em> ARN, not an alias and
     * not a bare key id. Accepting anything else would let DescribeDeliveryStream report an
     * encryption key that could never exist, where real AWS rejects the call outright.
     */
    private static final java.util.regex.Pattern SSE_KEY_ARN_PATTERN = java.util.regex.Pattern.compile(
            "arn:.*:kms:[a-zA-Z0-9\\-]+:\\d{12}:key/[a-zA-Z_0-9+=,.@\\-_/]+");
    private static final int SSE_KEY_ARN_MAX_LENGTH = 512;

    // Per shard, per tick. GetRecords caps at 1000 anyway; a smaller page keeps one
    // busy source stream from monopolising the single flusher thread.
    private static final int SOURCE_POLL_LIMIT = 500;

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final Map<String, Instant> bufferSince = new ConcurrentHashMap<>();
    private final S3Service s3Service;
    private final KinesisService kinesisService;
    private final RegionResolver regionResolver;
    // Per delivery stream, the shard iterator each source shard has been consumed to.
    // Iterators are the checkpoint: a delivery stream restarted mid-shard resumes from
    // the tip it reached, and one that has never polled starts at DeliveryStartTimestamp.
    //
    // Persisted, unlike buffers/bufferSince above, because losing it is not merely losing
    // what was in flight: an emulator restart would rebuild every iterator from
    // DeliveryStartTimestamp and re-deliver the source stream's whole retained history to
    // S3. A Kinesis shard iterator is a self-describing token
    // (streamName|shardId|type|sequenceNumber|index|timestamp, base64) with no
    // JVM-lifetime component, and KinesisService never trims a shard's record list, so a
    // checkpoint written before a restart still resolves to the same position after one.
    //
    // This store holds only COMMITTED positions: a shard is advanced here once the
    // records read up to that point have actually landed in S3. See
    // pendingSourceIterators for why the two halves cannot be one map.
    private final StorageBackend<String, Map<String, String>> sourceIteratorStore;
    // The advanced-but-not-yet-durable half of the checkpoint, deliberately in memory.
    //
    // Polled records do not go straight to S3; they go into buffers above and reach S3
    // only when a flush trips. Persisting the advanced iterator at poll time would let
    // the durable checkpoint outrun durable delivery: a crash after the put but before
    // the flush loses the buffer and resumes past records that never reached S3, which is
    // silent, permanent loss. So the poller advances here, and only a flush that actually
    // wrote to S3 promotes these into sourceIteratorStore.
    //
    // Losing this map costs nothing but duplicates: the restored poller re-reads records
    // that were buffered and never delivered, and delivers them. Kinesis Data Firehose is
    // at-least-once, so a duplicate is correct-if-untidy where a skip is not.
    //
    // Values are replaced whole, never mutated in place, so a flusher that snapshots one
    // commits exactly the position that snapshot covered.
    private final Map<String, Map<String, String>> pendingSourceIterators = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long tickIntervalSeconds;
    private final int flushRecordCount;
    private final boolean flusherEnabled;
    private final ScheduledExecutorService flushExecutor;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, KinesisService kinesisService,
                           RegionResolver regionResolver, Clock clock, EmulatorConfig config) {
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.sourceIteratorStore = storageFactory.create("firehose", "source-iterators.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.s3Service = s3Service;
        this.kinesisService = kinesisService;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.tickIntervalSeconds = Math.max(1, config.services().firehose().tickIntervalSeconds());
        this.flushRecordCount = Math.max(0, config.services().firehose().flushRecordCount());
        this.flusherEnabled = config.services().firehose().enabled();
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "firehose-buffer-flusher");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!flusherEnabled) {
            LOG.info("Firehose buffer flusher disabled by configuration");
            return;
        }
        flushExecutor.scheduleAtFixedRate(this::tickSafely, tickIntervalSeconds, tickIntervalSeconds, TimeUnit.SECONDS);
        LOG.infov("Firehose buffer flusher started (tick every {0}s)", tickIntervalSeconds);
    }

    // ShutdownDelayInitiatedEvent fires before every ShutdownEvent observer, so this
    // drain lands the pending records in S3 while EmulatorLifecycle.onStop can still
    // persist them to disk via storageFactory.flushAll(). Each flush also commits the
    // source checkpoint it just made durable, so a graceful shutdown neither loses
    // records nor re-delivers them; storageFactory.flushAll() then writes that
    // checkpoint out with everything else.
    void onPreShutdown(@Observes ShutdownDelayInitiatedEvent ignored) {
        flushExecutor.shutdownNow();
        buffers.keySet().forEach(this::flush);
    }

    void tickSafely() {
        try {
            pollKinesisSources();
            flushDueBuffers(clock.instant());
        } catch (Throwable t) {
            LOG.warnv("Firehose buffer flush tick failed: {0}", t.getMessage());
        }
    }

    void flushDueBuffers(Instant now) {
        for (Map.Entry<String, List<byte[]>> entry : buffers.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String streamName = entry.getKey();
            try {
                DeliveryStreamDescription stream = describeDeliveryStream(streamName);
                Instant since = bufferSince.putIfAbsent(streamName, now);
                if (since == null) {
                    since = now;
                }
                if (!now.isBefore(since.plusSeconds(bufferingIntervalSeconds(stream)))) {
                    flush(streamName, stream);
                }
            } catch (Exception e) {
                LOG.warnv("Firehose buffer flush failed for stream {0}: {1}", streamName, e.getMessage());
            }
        }
    }

    private static int bufferingIntervalSeconds(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        // describeDeliveryStream already applied defaults, so hints only miss
        // when the stream has no S3 destination at all (default-bucket delivery).
        if (s3 == null || s3.getBufferingHints() == null || s3.getBufferingHints().getIntervalInSeconds() == null) {
            return DEFAULT_BUFFERING_INTERVAL_SECONDS;
        }
        return s3.getBufferingHints().getIntervalInSeconds();
    }

    public String createDeliveryStream(String name, S3Destination s3Config) {
        return createDeliveryStream(name, s3Config, List.of());
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags) {
        return createDeliveryStream(name, s3Config, tags, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType) {
        return createDeliveryStream(name, s3Config, tags, deliveryStreamType, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType, KinesisStreamSource source) {
        if (name == null || name.isEmpty() || name.length() > 64 || !name.matches("[a-zA-Z0-9_.-]+")) {
            throw new AwsException("InvalidArgumentException",
                    "Delivery stream name must be between 1 and 64 characters and contain only letters, numbers, underscores, hyphens, or periods.", 400);
        }

        if (streamStore.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Delivery stream " + name + " already exists.", 409);
        }

        validateBufferingHints(s3Config);
        String arn = AwsArnUtils.Arn.of("firehose", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "deliverystream/" + name).toString();
        // CreateDeliveryStream's KinesisStreamSourceConfiguration carries only the ARN and
        // role -- DeliveryStartTimestamp exists only on the Description shape, which AWS
        // fills in at creation. Stamping it here, at creation time, is what makes the first
        // shard iterator in pollKinesisSource start AT_TIMESTAMP=now instead of falling back
        // to TRIM_HORIZON: attaching Firehose to a stream that already holds records must not
        // backfill its history into S3.
        if (source != null && source.getDeliveryStartTimestamp() == null) {
            source.setDeliveryStartTimestamp(Instant.now());
        }
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config, source);
        description.setAccountId(regionResolver.getAccountId());
        description.setTags(tags);
        if (deliveryStreamType != null && !deliveryStreamType.isBlank()) {
            description.setDeliveryStreamType(deliveryStreamType);
        }
        streamStore.put(name, description);
        buffers.put(name, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose delivery stream: {0}", name);
        return arn;
    }

    public void updateDestination(String name, String currentVersionId, String destinationId, S3Destination update) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        if (!stream.getVersionId().equals(currentVersionId)) {
            throw new AwsException("ConcurrentModificationException",
                    "Cannot update firehose: " + name + " since the current version id: " + stream.getVersionId()
                            + " and specified version id: " + currentVersionId + " do not match", 400);
        }
        DeliveryStreamDescription.Destination destination = stream.getDestinations() != null && !stream.getDestinations().isEmpty()
                ? stream.getDestinations().get(0)
                : null;
        if (destination == null || !destination.getDestinationId().equals(destinationId)) {
            throw new AwsException("InvalidArgumentException",
                    "Destination Id " + destinationId + " not found", 400);
        }
        if (update == null) {
            throw new AwsException("InvalidArgumentException",
                    "A destination update is required for UpdateDestination.", 400);
        }
        validateBufferingHints(update);
        S3Destination current = destination.getExtendedS3DestinationDescription();
        if (current == null) {
            update.applyDefaults();
            destination.setExtendedS3DestinationDescription(update);
        } else {
            mergeDestination(current, update);
        }
        stream.setVersionId(String.valueOf(parseVersionId(stream.getVersionId()) + 1));
        stream.setLastUpdateTimestamp(java.time.Instant.now());
        streamStore.put(name, stream);
        LOG.infov("Updated destination {0} of Firehose delivery stream {1}", destinationId, name);
    }

    public void startDeliveryStreamEncryption(String name, String keyType, String keyArn) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        String effectiveKeyType = keyType == null ? "AWS_OWNED_CMK" : keyType;
        if (!effectiveKeyType.equals("AWS_OWNED_CMK") && !effectiveKeyType.equals("CUSTOMER_MANAGED_CMK")) {
            throw new AwsException("InvalidArgumentException",
                    "KeyType must be AWS_OWNED_CMK or CUSTOMER_MANAGED_CMK.", 400);
        }
        if (effectiveKeyType.equals("CUSTOMER_MANAGED_CMK") && (keyArn == null || keyArn.isBlank())) {
            throw new AwsException("InvalidArgumentException",
                    "KeyARN is required for CUSTOMER_MANAGED_CMK.", 400);
        }
        if (keyArn != null && !keyArn.isBlank()
                && (keyArn.length() > SSE_KEY_ARN_MAX_LENGTH || !SSE_KEY_ARN_PATTERN.matcher(keyArn).matches())) {
            throw new AwsException("InvalidArgumentException",
                    "KeyARN is not a valid KMS key ARN: " + keyArn, 400);
        }
        stream.setDeliveryStreamEncryptionConfiguration(
                new DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration(
                        effectiveKeyType,
                        effectiveKeyType.equals("CUSTOMER_MANAGED_CMK") ? keyArn : null,
                        "ENABLED"));
        streamStore.put(name, stream);
    }

    public void stopDeliveryStreamEncryption(String name) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration current =
                stream.getDeliveryStreamEncryptionConfiguration();
        String keyType = current == null ? "AWS_OWNED_CMK" : current.getKeyType();
        // Keep the customer key identity on disable: real AWS still reports the KeyARN
        // of a stopped CUSTOMER_MANAGED_CMK stream, and dropping it here would make a
        // later DescribeDeliveryStream forget which key the stream was encrypted with.
        String keyArn = current == null ? null : current.getKeyArn();
        stream.setDeliveryStreamEncryptionConfiguration(
                new DeliveryStreamDescription.DeliveryStreamEncryptionConfiguration(
                        keyType, keyArn, "DISABLED"));
        streamStore.put(name, stream);
    }

    // A corrupt persisted version can only reach here when the caller echoed it
    // (the equality check above passed), so self-heal instead of failing with a 500
    // or blaming the client.
    private static long parseVersionId(String versionId) {
        try {
            return Long.parseLong(versionId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * AWS requires SizeInMBs and IntervalInSeconds to be specified together:
     * "This parameter is optional but if you specify a value for it, you must
     * also specify a value for IntervalInSeconds, and vice versa" (firehose
     * service-2.json). Rejecting partial hints here is what keeps the
     * whole-object replacement in mergeDestination faithful to AWS.
     */
    private static void validateBufferingHints(S3Destination config) {
        DeliveryStreamDescription.BufferingHints hints = config == null ? null : config.getBufferingHints();
        if (hints == null) {
            return;
        }
        if ((hints.getSizeInMBs() == null) != (hints.getIntervalInSeconds() == null)) {
            throw new AwsException("InvalidArgumentException",
                    "If you specify a value for SizeInMBs, you must also specify a value for IntervalInSeconds, and vice versa.",
                    400);
        }
    }

    private static void mergeDestination(S3Destination current, S3Destination update) {
        if (update.getRoleArn() != null) current.setRoleArn(update.getRoleArn());
        if (update.getBucketArn() != null) current.setBucketArn(update.getBucketArn());
        if (update.getPrefix() != null) current.setPrefix(update.getPrefix());
        if (update.getErrorOutputPrefix() != null) current.setErrorOutputPrefix(update.getErrorOutputPrefix());
        if (update.getCompressionFormat() != null) current.setCompressionFormat(update.getCompressionFormat());
        if (update.getFileExtension() != null) current.setFileExtension(update.getFileExtension());
        if (update.getCustomTimeZone() != null) current.setCustomTimeZone(update.getCustomTimeZone());
        if (update.getBufferingHints() != null) current.setBufferingHints(update.getBufferingHints());
        if (update.getEncryptionConfiguration() != null) current.setEncryptionConfiguration(update.getEncryptionConfiguration());
        if (update.getS3BackupMode() != null) current.setS3BackupMode(update.getS3BackupMode());
    }

    public void tagDeliveryStream(String name, List<DeliveryStreamDescription.Tag> tagsToTag) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        Map<String, String> tagMap = new LinkedHashMap<>();
        for (DeliveryStreamDescription.Tag t : stream.getTags()) {
            tagMap.put(t.getKey(), t.getValue());
        }
        for (DeliveryStreamDescription.Tag t : tagsToTag) {
            tagMap.put(t.getKey(), t.getValue());
        }
        List<DeliveryStreamDescription.Tag> newTags = new ArrayList<>();
        tagMap.forEach((k, v) -> newTags.add(new DeliveryStreamDescription.Tag(k, v)));
        stream.setTags(newTags);
        streamStore.put(name, stream);
        LOG.infov("Tagged Firehose delivery stream {0}: {1}", name, tagsToTag);
    }

    public void untagDeliveryStream(String name, List<String> tagKeys) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        List<DeliveryStreamDescription.Tag> newTags = new ArrayList<>();
        for (DeliveryStreamDescription.Tag t : stream.getTags()) {
            if (!tagKeys.contains(t.getKey())) {
                newTags.add(t);
            }
        }
        stream.setTags(newTags);
        streamStore.put(name, stream);
        LOG.infov("Untagged Firehose delivery stream {0}: {1}", name, tagKeys);
    }

    public List<DeliveryStreamDescription.Tag> listTagsForDeliveryStream(String name, String exclusiveStartTagKey, Integer limit) {
        DeliveryStreamDescription stream = describeDeliveryStream(name);
        List<DeliveryStreamDescription.Tag> tags = stream.getTags();
        int startIndex = 0;
        if (exclusiveStartTagKey != null && !exclusiveStartTagKey.isEmpty()) {
            for (int i = 0; i < tags.size(); i++) {
                if (tags.get(i).getKey().equals(exclusiveStartTagKey)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }
        int size = tags.size() - startIndex;
        int end = tags.size();
        if (limit != null && limit > 0 && limit < size) {
            end = startIndex + limit;
        }
        return new ArrayList<>(tags.subList(startIndex, end));
    }

    public DeliveryStreamDescription describeDeliveryStream(String name) {
        DeliveryStreamDescription stream = streamStore.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Delivery stream not found: " + name, 400));
        // Normalizes streams persisted before required output members existed.
        if (stream.s3Destination() != null) {
            stream.s3Destination().applyDefaults();
        }
        return stream;
    }

    public void deleteDeliveryStream(String name) {
        describeDeliveryStream(name);
        streamStore.delete(name);
        // Pending records are discarded, not flushed: verified against real AWS
        // (2026-07-13, eu-west-1) — 3 records buffered under a 300s/5MB hint never
        // reached the bucket after DeleteDeliveryStream completed.
        buffers.remove(name);
        bufferSince.remove(name);
        pendingSourceIterators.remove(name);
        sourceIteratorStore.delete(name);
        LOG.infov("Deleted Firehose delivery stream: {0}", name);
    }

    public List<String> listDeliveryStreams() {
        return streamStore.scan(k -> true).stream()
                .map(DeliveryStreamDescription::getDeliveryStreamName).toList();
    }

    /**
     * Ingests records written to a delivery stream's Kinesis source.
     *
     * <p>A {@code KinesisStreamAsSource} delivery stream has no PutRecord of its own on
     * real AWS: everything it delivers arrives by Firehose reading the source stream.
     * Without this the source configuration is recorded and then ignored, so a stream
     * created with one accepts the Terraform apply, reports healthy, and silently never
     * delivers -- which is how terraform-aws-messaging's TestKinesisFirehose fails
     * ("no objects found in the bucket" after six retries) while every API call in it
     * succeeds.
     *
     * <p>Consumption uses the ordinary shard-iterator API, so the records seen here are
     * exactly the records a GetRecords consumer would see, and the iterator is the
     * checkpoint. The first iterator starts at {@code DeliveryStartTimestamp} rather than
     * TRIM_HORIZON, matching AWS: attaching Firehose to a stream that already holds
     * records does not backfill them.
     */
    void pollKinesisSources() {
        for (DeliveryStreamDescription stream : streamStore.scan(k -> true)) {
            KinesisStreamSource source = stream.getSource() == null
                    ? null : stream.getSource().getKinesisStreamSourceDescription();
            if (source == null || source.getKinesisStreamArn() == null) {
                continue;
            }
            try {
                pollKinesisSource(stream.getDeliveryStreamName(), source);
            } catch (Exception e) {
                // A source stream that was deleted (or was never created) is the caller's
                // business, not a reason to stop polling every other delivery stream.
                LOG.debugv("Firehose source poll failed for stream {0}: {1}",
                        stream.getDeliveryStreamName(), e.getMessage());
            }
        }
    }

    private void pollKinesisSource(String deliveryStreamName, KinesisStreamSource source) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(source.getKinesisStreamArn());
        String sourceName = arn.resource().startsWith("stream/")
                ? arn.resource().substring("stream/".length())
                : arn.resource();
        String region = arn.region() == null || arn.region().isBlank()
                ? regionResolver.getDefaultRegion() : arn.region();
        KinesisStream sourceStream = kinesisService.describeStream(sourceName, region);
        for (KinesisShard shard : sourceStream.getShards()) {
            // Where to read from: the pending position if a flush still owes these
            // records to S3, otherwise the committed one. Reading pending is what stops
            // a second poll re-reading what the first already buffered; committing only
            // on flush is what stops the durable checkpoint outrunning delivery.
            String iterator = sourceIteratorsInUse(deliveryStreamName).get(shard.getShardId());
            if (iterator == null) {
                Instant start = source.getDeliveryStartTimestamp();
                iterator = start == null
                        ? kinesisService.getShardIterator(sourceName, shard.getShardId(),
                                "TRIM_HORIZON", null, region)
                        : kinesisService.getShardIterator(sourceName, shard.getShardId(),
                                "AT_TIMESTAMP", null, start.toEpochMilli(), region);
            }
            Map<String, Object> page = kinesisService.getRecords(iterator, SOURCE_POLL_LIMIT, region);
            @SuppressWarnings("unchecked")
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            String next = (String) page.get("NextShardIterator");
            String advanced = next != null ? next : iterator;
            String shardId = shard.getShardId();
            if (records == null || records.isEmpty()) {
                // Nothing was buffered, so this advance owes S3 nothing and is safe to
                // park as pending on its own.
                advanceSourceIterator(deliveryStreamName, shardId, advanced);
                continue;
            }
            // Through putRecordBatch so source records buffer, and trigger a flush, on
            // exactly the same terms as records handed to PutRecord directly. The
            // advance runs under the buffer lock together with the records it covers, so
            // no flush can ever see one without the other.
            putRecordBatch(deliveryStreamName, records.stream()
                            .map(record -> new Record(record.getData()))
                            .toList(),
                    () -> advanceSourceIterator(deliveryStreamName, shardId, advanced));
        }
    }

    /** The position the poller reads from: pending if a flush still owes it, else committed. */
    private Map<String, String> sourceIteratorsInUse(String deliveryStreamName) {
        Map<String, String> pending = pendingSourceIterators.get(deliveryStreamName);
        return pending != null ? pending : sourceIteratorStore.get(deliveryStreamName).orElseGet(Map::of);
    }

    // Rebased on whatever is current rather than on a map carried across the shard loop,
    // so that a flush which discarded pending (because its S3 write failed) rolls the
    // other shards back to committed instead of having this poll re-assert them.
    private void advanceSourceIterator(String deliveryStreamName, String shardId, String iterator) {
        Map<String, String> advanced = new LinkedHashMap<>(sourceIteratorsInUse(deliveryStreamName));
        advanced.put(shardId, iterator);
        pendingSourceIterators.put(deliveryStreamName, Collections.unmodifiableMap(advanced));
    }

    // Merged rather than written whole: two flushes can complete out of order, and a
    // whole-map write would then drop a shard's newer committed position. Merging can
    // still move one shard backwards, which costs a duplicate and never a skip.
    private void commitSourceIterators(String deliveryStreamName, Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return;
        }
        // Copied out and written back whole: the store may hand back its own instance,
        // and a persistent backend only learns of a change through put().
        Map<String, String> committed =
                new LinkedHashMap<>(sourceIteratorStore.get(deliveryStreamName).orElseGet(Map::of));
        committed.putAll(checkpoint);
        sourceIteratorStore.put(deliveryStreamName, committed);
    }

    public void putRecord(String streamName, Record record) {
        putRecordBatch(streamName, List.of(record));
    }

    public void putRecordBatch(String streamName, List<Record> records) {
        putRecordBatch(streamName, records, null);
    }

    /**
     * @param checkpointAdvance run under the buffer lock once the records are buffered,
     *                          or null for records that are not read from a source stream.
     */
    private void putRecordBatch(String streamName, List<Record> records, Runnable checkpointAdvance) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        List<byte[]> buffer = buffers.computeIfAbsent(
                streamName, k -> Collections.synchronizedList(new ArrayList<>()));
        long bufferedBytes = 0;
        int bufferedCount;
        // Records, their buffering-start timestamp and any source checkpoint advance move
        // together under the buffer lock so the flusher never sees one without the other.
        synchronized (buffer) {
            for (Record r : records) {
                buffer.add(r.getData());
            }
            if (checkpointAdvance != null) {
                checkpointAdvance.run();
            }
            bufferSince.putIfAbsent(streamName, clock.instant());
            bufferedCount = buffer.size();
            for (byte[] data : buffer) {
                bufferedBytes += data.length;
            }
        }
        if ((flushRecordCount > 0 && bufferedCount >= flushRecordCount)
                || bufferedBytes >= bufferingSizeLimitBytes(stream)) {
            flush(streamName, stream);
        }
    }

    private static long bufferingSizeLimitBytes(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        int sizeInMBs = (s3 == null || s3.getBufferingHints() == null || s3.getBufferingHints().getSizeInMBs() == null)
                ? DEFAULT_BUFFERING_SIZE_MBS
                : s3.getBufferingHints().getSizeInMBs();
        return sizeInMBs * 1024L * 1024L;
    }

    public void flush(String streamName) {
        streamStore.get(streamName).ifPresent(stream -> flush(streamName, stream));
    }

    private void flush(String streamName, DeliveryStreamDescription stream) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        List<byte[]> toFlush;
        // Taken with the records, under the same lock: this is the position the records
        // about to be written cover, and committing it is this flush's job.
        Map<String, String> checkpoint;
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
            bufferSince.remove(streamName);
            checkpoint = pendingSourceIterators.remove(streamName);
        }
        if (toFlush.isEmpty()) {
            // Lost the race against a concurrent flush; nothing left to deliver. Any
            // checkpoint taken here covers no undelivered records -- it can only have
            // come from a poll that read an empty page -- so it commits as it stands.
            commitSourceIterators(streamName, checkpoint);
            return;
        }

        try {
            String bucket = resolveBucket(stream);
            S3Destination s3 = stream.s3Destination();
            FirehoseCompression compression =
                    FirehoseCompression.forDelivery(s3 == null ? null : s3.getCompressionFormat());
            String key = S3ObjectKeyResolver.resolveKey(s3, stream.getDeliveryStreamName(),
                    stream.getVersionId(), clock.instant(), compression);

            ensureBucket(bucket);

            // Records are arbitrary bytes, so they are concatenated as bytes: routing
            // them through a String would corrupt any payload that is not valid UTF-8.
            // The appended newline is a deliberate deviation kept from before this
            // method compressed anything: real AWS inserts no separator at all
            // (verified: three "abc" records arrive as the 9 bytes "abcabcabc").
            // See the deviation noted in docs/services/firehose.md.
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            for (byte[] data : toFlush) {
                payload.writeBytes(data);
                if (data.length > 0 && data[data.length - 1] != '\n') {
                    payload.write('\n');
                }
            }

            byte[] body = compression.compress(payload.toByteArray());
            s3Service.putObject(bucket, key, body, "application/octet-stream", Map.of(),
                    new PutObjectOptions().withContentEncoding(compression.contentEncoding()));
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3} ({4})",
                    toFlush.size(), streamName, bucket, key, compression.wireValue());
            // Only now: the records these iterators were read past are durable.
            commitSourceIterators(streamName, checkpoint);
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}: {1}", streamName, e.getMessage());
            // checkpoint is deliberately neither committed nor put back. The durable
            // checkpoint stays where it was, so the next poll reads these records again
            // and this failed delivery repairs itself.
        }
    }

    private String resolveBucket(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        if (s3 != null && s3.bucketName() != null) {
            return s3.bucketName();
        }
        return DEFAULT_BUCKET;
    }

    private void ensureBucket(String bucket) {
        try {
            s3Service.createBucket(bucket, regionResolver.getDefaultRegion());
        } catch (Exception ignored) {}
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (DeliveryStreamDescription stream : streamStore.scan(k -> true)) {
            String arn = stream.getDeliveryStreamARN();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            Map<String, String> tags = new LinkedHashMap<>();
            if (stream.getTags() != null) {
                for (DeliveryStreamDescription.Tag tag : stream.getTags()) {
                    tags.put(tag.getKey(), tag.getValue() != null ? tag.getValue() : "");
                }
            }
            resources.add(new ExplorerResource(
                    arn, "firehose:deliverystream", "firehose",
                    parsed.region(), parsed.accountId(),
                    stream.getCreateTimestamp() != null ? stream.getCreateTimestamp() : Instant.now(),
                    tags));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("firehose:deliverystream", "firehose", true));
    }
}
