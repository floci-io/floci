package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.KinesisStreamSource;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
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
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FirehoseService implements ResourceProvider {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);
    private static final String DEFAULT_BUCKET = "floci-firehose-results";
    private static final int DEFAULT_BUFFERING_INTERVAL_SECONDS = 300;
    private static final int DEFAULT_REDSHIFT_RETRY_SECONDS = 3600;
    // AWS retries a failed Redshift load every 5 minutes.
    private static final long REDSHIFT_RETRY_INTERVAL_SECONDS = 300;
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

    private final AccountAwareStorageBackend<DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final Map<String, Instant> bufferSince = new ConcurrentHashMap<>();
    // One delivery at a time per stream, from taking the buffer to committing or
    // restoring its checkpoint. The buffer monitor alone only makes the snapshot atomic:
    // a second flush could still take records polled past a first flush's checkpoint,
    // deliver them and commit that position while the first write is still in flight.
    // If the first write then failed, the durable checkpoint would already sit past its
    // records, and a restart before the retry would skip them for good.
    private final Map<String, Object> flushLocks = new ConcurrentHashMap<>();
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
    // JVM-lifetime component, and KinesisService resolves it by sequence number, so a
    // checkpoint written before a restart still resolves to the same position after one,
    // even once retention has pruned older records. If the checkpointed record itself has
    // expired, reading resumes at the oldest retained record.
    //
    // This store holds only COMMITTED positions: a shard is advanced here once the
    // records read up to that point have actually landed in S3. See
    // pendingSourceIterators for why the two halves cannot be one map.
    private final AccountAwareStorageBackend<Map<String, String>> sourceIteratorStore;
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
    private final FirehoseParquetConverter parquetConverter;
    private final FirehoseLambdaTransformer lambdaTransformer;
    private final FirehoseRedshiftDelivery redshiftDelivery;
    private final Executor redshiftDeliveryExecutor;
    // Per stream, the batches staged in S3 whose COPY has not succeeded yet, oldest first. A failed COPY is
    // retried against the object already staged instead of staging the batch again.
    private final Map<String, Deque<PendingRedshiftCopy>> redshiftPending = new ConcurrentHashMap<>();
    // Tracks streams that currently have a drain task queued or running on redshiftDeliveryExecutor.
    private final Set<String> activeRedshiftDrains = ConcurrentHashMap.newKeySet();
    // Serialises the read, version check, merge and write of a Redshift UpdateDestination per stream.
    private final Map<String, Object> redshiftUpdateLocks = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long tickIntervalSeconds;
    private final int flushRecordCount;
    private final boolean flusherEnabled;
    private final ScheduledExecutorService flushExecutor;

    private String currentRegion() {
        String region = regionResolver.getRegion();
        return region == null || region.isBlank() ? regionResolver.getDefaultRegion() : region;
    }

    private String scopedKey(String streamName) {
        return scopedKey(regionResolver.getAccountId(), currentRegion(), streamName);
    }

    private static String scopedKey(String accountId, String region, String streamName) {
        return accountId + "/" + region + "/" + streamName;
    }


    private static String accountFromScopedKey(String key) {
        return key.substring(0, key.indexOf('/'));
    }

    private static String logicalKeyFromScopedKey(String key) {
        return key.substring(key.indexOf('/') + 1);
    }

    private static String regionFromScopedKey(String key) {
        String logicalKey = logicalKeyFromScopedKey(key);
        return logicalKey.substring(0, logicalKey.indexOf('/'));
    }

    private static String streamNameFromScopedKey(String key) {
        String logicalKey = logicalKeyFromScopedKey(key);
        return logicalKey.substring(logicalKey.indexOf('/') + 1);
    }

    private static boolean belongsTo(String accountId, String region, DeliveryStreamDescription stream) {
        if (!accountId.equals(stream.getAccountId())) {
            return false;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(stream.getDeliveryStreamARN());
            return accountId.equals(arn.accountId()) && region.equals(arn.region());
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<DeliveryStreamDescription> streamGet(String key) {
        String accountId = accountFromScopedKey(key);
        String region = regionFromScopedKey(key);
        String streamName = streamNameFromScopedKey(key);
        return streamStore.getForAccountMigratingLegacyKeys(accountId, logicalKeyFromScopedKey(key),
                List.of(streamName), stream -> belongsTo(accountId, region, stream), false);
    }

    private void streamPut(String key, DeliveryStreamDescription stream) {
        streamStore.putForAccount(accountFromScopedKey(key), logicalKeyFromScopedKey(key), stream);
    }

    private void streamDelete(String key) {
        streamStore.deleteForAccount(accountFromScopedKey(key), logicalKeyFromScopedKey(key));
    }

    private Optional<Map<String, String>> iteratorGet(String key) {
        // Do not migrate the old account/name checkpoint key: its opaque iterator token has no
        // region, so with same-named streams in multiple regions there is no safe way to tell
        // which stream it belongs to. Leaving it untouched makes the regional stream start from
        // its configured delivery timestamp instead of risking a cross-region checkpoint.
        return sourceIteratorStore.getForAccount(accountFromScopedKey(key), logicalKeyFromScopedKey(key));
    }

    private void iteratorPut(String key, Map<String, String> value) {
        sourceIteratorStore.putForAccount(accountFromScopedKey(key), logicalKeyFromScopedKey(key), value);
    }

    private void iteratorDelete(String key) {
        sourceIteratorStore.deleteForAccount(accountFromScopedKey(key), logicalKeyFromScopedKey(key));
    }

    /** Kept so callers that never deliver to Redshift need no delivery collaborator; null means unavailable. */
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, KinesisService kinesisService,
                           RegionResolver regionResolver, Clock clock, EmulatorConfig config,
                           FirehoseParquetConverter parquetConverter,
                           FirehoseLambdaTransformer lambdaTransformer) {
        this(storageFactory, s3Service, kinesisService, regionResolver, clock, config, parquetConverter,
                lambdaTransformer, null);
    }

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, KinesisService kinesisService,
                           RegionResolver regionResolver, Clock clock, EmulatorConfig config,
                           FirehoseParquetConverter parquetConverter,
                           FirehoseLambdaTransformer lambdaTransformer,
                           FirehoseRedshiftDelivery redshiftDelivery) {
        this(storageFactory, s3Service, kinesisService, regionResolver, clock, config, parquetConverter,
                lambdaTransformer, redshiftDelivery, null);
    }

    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, KinesisService kinesisService,
                           RegionResolver regionResolver, Clock clock, EmulatorConfig config,
                           FirehoseParquetConverter parquetConverter,
                           FirehoseLambdaTransformer lambdaTransformer,
                           FirehoseRedshiftDelivery redshiftDelivery,
                           Executor redshiftDeliveryExecutor) {
        this.redshiftDelivery = redshiftDelivery;
        this.redshiftDeliveryExecutor = redshiftDeliveryExecutor != null
                ? redshiftDeliveryExecutor
                : Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "firehose-redshift-delivery");
                    t.setDaemon(true);
                    return t;
                });
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.sourceIteratorStore = storageFactory.create("firehose", "source-iterators.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.s3Service = s3Service;
        this.kinesisService = kinesisService;
        this.regionResolver = regionResolver;
        this.parquetConverter = parquetConverter;
        this.lambdaTransformer = lambdaTransformer;
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
    // checkpoint out with everything else. Redshift COPY tasks are allowed a brief
    // window to complete before the delivery executor is forced down.
    void onPreShutdown(@Observes ShutdownDelayInitiatedEvent ignored) {
        flushExecutor.shutdownNow();
        buffers.keySet().forEach(this::flushByKey);
        if (redshiftDeliveryExecutor instanceof ExecutorService es) {
            es.shutdown();
            try {
                if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                    dropAndLogQueuedTasks(es);
                }
            } catch (InterruptedException e) {
                dropAndLogQueuedTasks(es);
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void dropAndLogQueuedTasks(ExecutorService es) {
        List<Runnable> dropped = es.shutdownNow();
        if (!dropped.isEmpty()) {
            LOG.warnv("Dropping {0} queued Redshift COPY task(s) on Firehose shutdown", dropped.size());
        }
    }

    /**
     * Ticks never overlap: {@code flushExecutor} is single threaded, and the source poll is a
     * read of the shard iterator, a fetch and an advance that is not atomic across the three, so
     * two concurrent ticks would fetch the same records twice and buffer them twice. Tests drive
     * this method directly alongside the scheduled tick, which is the only way a second caller
     * arises, so the guarantee is stated here rather than left to the executor.
     */
    synchronized void tickSafely() {
        try {
            pollKinesisSources();
            flushDueBuffers(clock.instant());
            retryPendingRedshiftCopies();
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
                DeliveryStreamDescription stream = describeDeliveryStreamByKey(streamName);
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
        return createDeliveryStream(regionResolver.getDefaultRegion(), regionResolver.getAccountId(), name, s3Config,
                tags, deliveryStreamType, source);
    }

    public String createDeliveryStream(String region, String name, S3Destination s3Config,
                                       List<DeliveryStreamDescription.Tag> tags, String deliveryStreamType,
                                       KinesisStreamSource source) {
        return createDeliveryStream(region, regionResolver.getAccountId(), name, s3Config, tags,
                deliveryStreamType, source);
    }

    public String createDeliveryStream(String region, String accountId, String name, S3Destination s3Config,
                                       List<DeliveryStreamDescription.Tag> tags, String deliveryStreamType,
                                       KinesisStreamSource source) {
        return createDeliveryStream(region, accountId, name, s3Config, null, tags, deliveryStreamType, source);
    }

    public String createDeliveryStream(String region, String name, S3Destination s3Config,
                                       RedshiftDestination redshift, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType, KinesisStreamSource source) {
        return createDeliveryStream(region, regionResolver.getAccountId(), name, s3Config, redshift, tags,
                deliveryStreamType, source);
    }

    public String createDeliveryStream(String region, String accountId, String name, S3Destination s3Config,
                                       RedshiftDestination redshift, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType, KinesisStreamSource source) {
        String streamKey = scopedKey(accountId, region, name);
        if (name == null || name.isEmpty() || name.length() > 64 || !name.matches("[a-zA-Z0-9_.-]+")) {
            throw new AwsException("InvalidArgumentException",
                    "Delivery stream name must be between 1 and 64 characters and contain only letters, numbers, underscores, hyphens, or periods.", 400);
        }

        if (streamGet(streamKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Delivery stream " + name + " already exists.", 409);
        }

        validateLogging(null, s3Config);
        validateBufferingHints(s3Config);
        if (redshift != null) {
            validateLogging(null, redshift.getS3());
            validateLogging(null, redshift.getS3Backup());
            validateBufferingHints(redshift.getS3());
            validateBufferingHints(redshift.getS3Backup());
        }
        DataFormatConversionValidator.validateEffective(s3Config);
        ProcessingConfigurationValidator.validateEffective(s3Config);
        if (s3Config != null) {
            s3Config.canonicalizeProcessors();
        }
        String arn = AwsArnUtils.Arn.of("firehose", region, accountId,
                "deliverystream/" + name).toString();
        // CreateDeliveryStream's KinesisStreamSourceConfiguration carries only the ARN and
        // role -- DeliveryStartTimestamp exists only on the Description shape, which AWS
        // fills in at creation. Stamping it here, at creation time, is what makes the first
        // shard iterator in pollKinesisSource start AT_TIMESTAMP=now instead of falling back
        // to TRIM_HORIZON: attaching Firehose to a stream that already holds records must not
        // backfill its history into S3.
        if (source != null && source.getDeliveryStartTimestamp() == null) {
            source.setDeliveryStartTimestamp(Instant.now());
        }
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config, redshift, source);
        description.setAccountId(accountId);
        description.setTags(tags);
        if (deliveryStreamType != null && !deliveryStreamType.isBlank()) {
            description.setDeliveryStreamType(deliveryStreamType);
        }
        streamPut(streamKey, description);
        buffers.put(streamKey, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose delivery stream: {0}", name);
        return arn;
    }

    public void updateDestination(String name, String currentVersionId, String destinationId, S3Destination update) {
        updateDestination(scopedKey(name), name, currentVersionId, destinationId, update);
    }

    public void updateDestination(String accountId, String region, String name, String currentVersionId,
                                  String destinationId, S3Destination update) {
        updateDestination(scopedKey(accountId, region, name), name, currentVersionId, destinationId, update);
    }

    /** The stream an UpdateDestination call targets, with the destination it addresses. */
    private record UpdateTarget(DeliveryStreamDescription stream, DeliveryStreamDescription.Destination destination) {}

    /**
     * The checks every UpdateDestination variant starts with, in the order AWS applies them: the stream exists, the
     * caller holds its current version, and the destination id is the stream's destination.
     */
    private UpdateTarget resolveUpdateTarget(String streamKey, String name, String currentVersionId,
                                             String destinationId) {
        DeliveryStreamDescription stream = streamGet(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Delivery stream not found: " + name, 400));
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
        return new UpdateTarget(stream, destination);
    }

    private void updateDestination(String streamKey, String name, String currentVersionId, String destinationId, S3Destination update) {
        UpdateTarget target = resolveUpdateTarget(streamKey, name, currentVersionId, destinationId);
        DeliveryStreamDescription stream = target.stream();
        DeliveryStreamDescription.Destination destination = target.destination();
        if (update == null) {
            throw new AwsException("InvalidArgumentException",
                    "A destination update is required for UpdateDestination.", 400);
        }
        if (destination.getRedshiftDestinationDescription() != null) {
            throw new AwsException("InvalidArgumentException",
                    "The delivery stream " + name + " has a Redshift destination; use RedshiftDestinationUpdate.", 400);
        }
        validateLogging(destination.getExtendedS3DestinationDescription(), update);
        validateBufferingHints(update);
        S3Destination current = destination.getExtendedS3DestinationDescription();
        if (current == null) {
            DataFormatConversionValidator.validateEffective(update);
            ProcessingConfigurationValidator.validateEffective(update);
            update.applyDefaults();
            update.canonicalizeProcessors();
            destination.setExtendedS3DestinationDescription(update);
        } else {
            // AWS validates the merged effective state, not the update shape: enabling
            // conversion is rejected while a stored GZIP is in effect, and re-enabling
            // with only {"Enabled": true} succeeds on the stored schema (probe u5/u6).
            // Validating a merged view first also keeps a failed update from leaving a
            // half-merged destination behind in the memory backend.
            DataFormatConversionValidator.validateEffective(mergedView(current, update));
            // Not the merged view: an update carrying a ProcessingConfiguration replaces
            // the stored one outright, so AWS validates what the update itself says. A
            // processor without LambdaArn is rejected even when the stored one had it
            // (probed 2026-09-10, unlike the conversion block above).
            ProcessingConfigurationValidator.validateEffective(update);
            mergeDestination(current, update);
            current.canonicalizeProcessors();
        }
        stream.setVersionId(String.valueOf(parseVersionId(stream.getVersionId()) + 1));
        stream.setLastUpdateTimestamp(java.time.Instant.now());
        streamPut(streamKey, stream);
        LOG.infov("Updated destination {0} of Firehose delivery stream {1}", destinationId, name);
    }

    public void updateRedshiftDestination(String name, String currentVersionId, String destinationId,
                                          RedshiftDestination update) {
        String streamKey = scopedKey(name);
        synchronized (redshiftUpdateLocks.computeIfAbsent(streamKey, k -> new Object())) {
            UpdateTarget target = resolveUpdateTarget(streamKey, name, currentVersionId, destinationId);
            DeliveryStreamDescription stream = target.stream();
            RedshiftDestination current = target.destination().getRedshiftDestinationDescription();
            if (current == null) {
                throw new AwsException("InvalidArgumentException",
                        "The delivery stream " + name + " does not have a Redshift destination.", 400);
            }
            RedshiftDestinationValidator.validateBackupTransition(current.getS3BackupMode(), update.getS3BackupMode());
            String effectiveBackupMode = update.getS3BackupMode() != null
                    ? update.getS3BackupMode() : current.getS3BackupMode();
            if ("Enabled".equals(effectiveBackupMode) && update.getS3Backup() == null && current.getS3Backup() == null) {
                throw new AwsException("InvalidArgumentException",
                        "S3BackupMode is Enabled but no S3 backup configuration is set.", 400);
            }
            validateLogging(current.getS3(), update.getS3());
            validateLogging(current.getS3Backup(), update.getS3Backup());
            validateBufferingHints(update.getS3());
            validateBufferingHints(update.getS3Backup());
            if (update.getS3Backup() != null && current.getS3Backup() == null) {
                RedshiftDestinationValidator.validateNewBackup(update.getS3Backup(), "redshiftDestinationUpdate");
            }
            mergeRedshiftDestination(current, update);
            stream.setVersionId(String.valueOf(parseVersionId(stream.getVersionId()) + 1));
            stream.setLastUpdateTimestamp(Instant.now());
            streamPut(streamKey, stream);
            LOG.infov("Updated Redshift destination {0} of Firehose delivery stream {1}", destinationId, name);
        }
    }

    /** Members present in the update replace the stored ones; absent members are kept. */
    private static void mergeRedshiftDestination(RedshiftDestination current, RedshiftDestination update) {
        if (update.getRoleArn() != null) {
            current.setRoleArn(update.getRoleArn());
        }
        if (update.getClusterJdbcUrl() != null) {
            current.setClusterJdbcUrl(update.getClusterJdbcUrl());
        }
        if (update.getUsername() != null) {
            current.setUsername(update.getUsername());
        }
        if (update.getPassword() != null) {
            current.setPassword(update.getPassword());
        }
        if (update.getCopyCommand() != null) {
            current.setCopyCommand(update.getCopyCommand());
        }
        if (update.getRetryOptions() != null) {
            current.setRetryOptions(update.getRetryOptions());
        }
        if (update.getS3BackupMode() != null) {
            current.setS3BackupMode(update.getS3BackupMode());
        }
        if (update.getSecretsManagerConfiguration() != null) {
            current.setSecretsManagerConfiguration(update.getSecretsManagerConfiguration());
        }
        if (update.getProcessingConfiguration() != null) {
            current.setProcessingConfiguration(update.getProcessingConfiguration());
        }
        if (update.getCloudWatchLoggingOptions() != null) {
            current.setCloudWatchLoggingOptions(update.getCloudWatchLoggingOptions());
        }
        if (update.getS3() != null) {
            if (current.getS3() == null) {
                update.getS3().applyDefaults();
                current.setS3(update.getS3());
            } else {
                mergeDestination(current.getS3(), update.getS3());
            }
        }
        if (update.getS3Backup() != null) {
            if (current.getS3Backup() == null) {
                update.getS3Backup().applyDefaults();
                current.setS3Backup(update.getS3Backup());
            } else {
                mergeDestination(current.getS3Backup(), update.getS3Backup());
            }
        }
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
        streamPut(scopedKey(name), stream);
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
        streamPut(scopedKey(name), stream);
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

    /**
     * Once logging is on, both names are required, checked on the merged result for an update
     * (probed 2026-10-23). An update without a logging block leaves the stored one as it was.
     */
    private static void validateLogging(S3Destination stored, S3Destination update) {
        if (update == null || update.getCloudWatchLoggingOptions() == null) {
            return;
        }
        DeliveryStreamDescription.CloudWatchLoggingOptions effective = mergeLogging(
                stored == null ? null : stored.getCloudWatchLoggingOptions(), update.getCloudWatchLoggingOptions());
        if (!Boolean.TRUE.equals(effective.getEnabled())) {
            return;
        }
        if (effective.getLogGroupName() == null || effective.getLogGroupName().isEmpty()) {
            throw new AwsException("InvalidArgumentException",
                    "CloudWatch Log Group Name is required if CloudWatch Logging is enabled", 400);
        }
        if (effective.getLogStreamName() == null || effective.getLogStreamName().isEmpty()) {
            throw new AwsException("InvalidArgumentException",
                    "CloudWatch Log Stream Name is required if CloudWatch Logging is enabled", 400);
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
        if (update.getCloudWatchLoggingOptions() != null) {
            current.setCloudWatchLoggingOptions(mergeLogging(
                    current.getCloudWatchLoggingOptions(), update.getCloudWatchLoggingOptions()));
        }
        // Replaced whole, not merged member-wise: an update carrying only
        // {"Enabled": false} leaves Processors empty on real AWS, where the same shape
        // preserves the conversion block's members (probed 2026-09-10).
        if (update.getProcessingConfiguration() != null) {
            current.setProcessingConfiguration(update.getProcessingConfiguration());
        }
        if (update.getDataFormatConversionConfiguration() != null) {
            current.setDataFormatConversionConfiguration(mergeConversion(
                    current.getDataFormatConversionConfiguration(), update.getDataFormatConversionConfiguration()));
        }
    }

    /**
     * Unlike the whole-object replacement above, DataFormatConversionConfiguration
     * merges member-wise: real AWS keeps the stored Schema/Input/Output when an update
     * carries only {"Enabled": false} (probe u2). Where there is anything to merge the
     * result is a new object, so a validation pass over a {@link #mergedView} never
     * mutates the stored configuration; with nothing stored yet the update is returned
     * as it stands, since there is no stored state for a caller to reach through it.
     */
    private static DeliveryStreamDescription.DataFormatConversionConfiguration mergeConversion(
            DeliveryStreamDescription.DataFormatConversionConfiguration current,
            DeliveryStreamDescription.DataFormatConversionConfiguration update) {
        if (current == null) {
            return update;
        }
        DeliveryStreamDescription.DataFormatConversionConfiguration merged =
                new DeliveryStreamDescription.DataFormatConversionConfiguration();
        merged.setEnabled(update.getEnabled() != null ? update.getEnabled() : current.getEnabled());
        merged.setSchemaConfiguration(mergeSchema(
                current.getSchemaConfiguration(), update.getSchemaConfiguration()));
        merged.setInputFormatConfiguration(update.getInputFormatConfiguration() != null
                ? update.getInputFormatConfiguration() : current.getInputFormatConfiguration());
        merged.setOutputFormatConfiguration(update.getOutputFormatConfiguration() != null
                ? update.getOutputFormatConfiguration() : current.getOutputFormatConfiguration());
        return merged;
    }

    /**
     * Member-wise, like the conversion block: an update carrying only {"Enabled": false} keeps the
     * stored group and stream, and one naming only a group keeps Enabled (probed 2026-10-22).
     */
    private static DeliveryStreamDescription.CloudWatchLoggingOptions mergeLogging(
            DeliveryStreamDescription.CloudWatchLoggingOptions current,
            DeliveryStreamDescription.CloudWatchLoggingOptions update) {
        if (current == null) {
            return update;
        }
        DeliveryStreamDescription.CloudWatchLoggingOptions merged = new DeliveryStreamDescription.CloudWatchLoggingOptions();
        merged.setEnabled(update.getEnabled() != null ? update.getEnabled() : current.getEnabled());
        merged.setLogGroupName(update.getLogGroupName() != null ? update.getLogGroupName() : current.getLogGroupName());
        merged.setLogStreamName(update.getLogStreamName() != null ? update.getLogStreamName() : current.getLogStreamName());
        return merged;
    }

    /**
     * SchemaConfiguration merges a member at a time as well, so an update naming only
     * a new TableName keeps the stored role, database and region (probed). Replacing
     * it wholesale would drop those and then fail the required-member checks.
     */
    private static DeliveryStreamDescription.SchemaConfiguration mergeSchema(
            DeliveryStreamDescription.SchemaConfiguration current,
            DeliveryStreamDescription.SchemaConfiguration update) {
        if (update == null) {
            return current;
        }
        if (current == null) {
            return update;
        }
        DeliveryStreamDescription.SchemaConfiguration merged =
                new DeliveryStreamDescription.SchemaConfiguration();
        merged.setRoleArn(update.getRoleArn() != null ? update.getRoleArn() : current.getRoleArn());
        merged.setCatalogId(update.getCatalogId() != null ? update.getCatalogId() : current.getCatalogId());
        merged.setDatabaseName(update.getDatabaseName() != null
                ? update.getDatabaseName() : current.getDatabaseName());
        merged.setTableName(update.getTableName() != null ? update.getTableName() : current.getTableName());
        merged.setRegion(update.getRegion() != null ? update.getRegion() : current.getRegion());
        merged.setVersionId(update.getVersionId() != null ? update.getVersionId() : current.getVersionId());
        return merged;
    }

    private static S3Destination mergedView(S3Destination current, S3Destination update) {
        S3Destination view = new S3Destination();
        view.setRoleArn(current.getRoleArn());
        view.setBucketArn(current.getBucketArn());
        view.setPrefix(current.getPrefix());
        view.setErrorOutputPrefix(current.getErrorOutputPrefix());
        view.setCompressionFormat(current.getCompressionFormat());
        view.setFileExtension(current.getFileExtension());
        view.setCustomTimeZone(current.getCustomTimeZone());
        view.setBufferingHints(current.getBufferingHints());
        view.setEncryptionConfiguration(current.getEncryptionConfiguration());
        view.setS3BackupMode(current.getS3BackupMode());
        view.setDataFormatConversionConfiguration(current.getDataFormatConversionConfiguration());
        mergeDestination(view, update);
        return view;
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
        streamPut(scopedKey(name), stream);
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
        streamPut(scopedKey(name), stream);
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
        return describeDeliveryStream(scopedKey(name), name);
    }

    public DeliveryStreamDescription describeDeliveryStream(String accountId, String region, String name) {
        return describeDeliveryStream(scopedKey(accountId, region, name), name);
    }

    private DeliveryStreamDescription describeDeliveryStream(String streamKey, String name) {
        return describeDeliveryStreamByKey(streamKey, name);
    }

    private DeliveryStreamDescription describeDeliveryStreamByKey(String streamKey) {
        return describeDeliveryStreamByKey(streamKey, streamKey.substring(streamKey.lastIndexOf('/') + 1));
    }

    private DeliveryStreamDescription describeDeliveryStreamByKey(String streamKey, String streamName) {
        DeliveryStreamDescription stream = streamGet(streamKey)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Delivery stream not found: " + streamName, 400));
        // Normalizes streams persisted before required output members existed.
        stream.applyDefaults();
        return stream;
    }

    public void deleteDeliveryStream(String name) {
        deleteDeliveryStream(regionResolver.getAccountId(), currentRegion(), name);
    }

    public void deleteDeliveryStream(String accountId, String region, String name) {
        String streamKey = scopedKey(accountId, region, name);
        // Under the lock a Redshift update holds across its read and write: an update that read the stream just before
        // this delete would otherwise write it back afterwards. The lock entry is kept, because removing it while an
        // update waits on it would let a later caller take a second lock for the same stream.
        synchronized (redshiftUpdateLocks.computeIfAbsent(streamKey, k -> new Object())) {
            describeDeliveryStream(streamKey, name);
            streamDelete(streamKey);
        }
        // Pending records are discarded, not flushed: verified against real AWS
        // (2026-07-13, eu-west-1) — 3 records buffered under a 300s/5MB hint never
        // reached the bucket after DeleteDeliveryStream completed.
        buffers.remove(streamKey);
        bufferSince.remove(streamKey);
        redshiftPending.remove(streamKey);
        activeRedshiftDrains.remove(streamKey);
        pendingSourceIterators.remove(streamKey);
        iteratorDelete(streamKey);
        LOG.infov("Deleted Firehose delivery stream: {0}", name);
    }

    public record DeliveryStreamPage(List<String> names, boolean hasMore) {}

    public DeliveryStreamPage listDeliveryStreams(int limit, String exclusiveStartName) {
        String regionPrefix = currentRegion() + "/";
        List<String> names = streamStore.scan(k -> k.startsWith(regionPrefix)).stream()
                .map(DeliveryStreamDescription::getDeliveryStreamName)
                .sorted()
                .filter(name -> exclusiveStartName == null || name.compareTo(exclusiveStartName) > 0)
                .toList();
        return new DeliveryStreamPage(names.subList(0, Math.min(limit, names.size())), names.size() > limit);
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
        for (AccountAwareStorageBackend.AccountEntry<DeliveryStreamDescription> entry
                : streamStore.scanAllAccountEntries(k -> true)) {
            DeliveryStreamDescription stream = entry.value();
            KinesisStreamSource source = stream.getSource() == null
                    ? null : stream.getSource().getKinesisStreamSourceDescription();
            if (source == null || source.getKinesisStreamArn() == null) {
                continue;
            }
            try {
                AwsArnUtils.Arn ownerArn = AwsArnUtils.parse(stream.getDeliveryStreamARN());
                pollKinesisSource(scopedKey(ownerArn.accountId(), ownerArn.region(), stream.getDeliveryStreamName()),
                        ownerArn.accountId(), stream.getDeliveryStreamName(), source);
            } catch (Exception e) {
                // A source stream that was deleted (or was never created) is the caller's
                // business, not a reason to stop polling every other delivery stream.
                LOG.debugv("Firehose source poll failed for stream {0}: {1}",
                        stream.getDeliveryStreamName(), e.getMessage());
            }
        }
    }

    private void pollKinesisSource(String streamKey, String accountId, String deliveryStreamName,
                                   KinesisStreamSource source) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(source.getKinesisStreamArn());
        String sourceName = arn.resource().startsWith("stream/")
                ? arn.resource().substring("stream/".length())
                : arn.resource();
        String region = arn.region() == null || arn.region().isBlank()
                ? regionResolver.getDefaultRegion() : arn.region();
        String sourceAccountId = arn.accountId() == null || arn.accountId().isBlank()
                ? accountId : arn.accountId();
        KinesisStream sourceStream = kinesisService.describeStreamForAccount(sourceAccountId, sourceName, region);
        for (KinesisShard shard : sourceStream.getShards()) {
            // Where to read from: the pending position if a flush still owes these
            // records to S3, otherwise the committed one. Reading pending is what stops
            // a second poll re-reading what the first already buffered; committing only
            // on flush is what stops the durable checkpoint outrunning delivery.
            String iterator = sourceIteratorsInUse(streamKey).get(shard.getShardId());
            if (iterator == null) {
                Instant start = source.getDeliveryStartTimestamp();
                iterator = start == null
                        ? kinesisService.getShardIteratorForAccount(sourceAccountId, sourceName, shard.getShardId(),
                                "TRIM_HORIZON", null, region)
                        : kinesisService.getShardIteratorForAccount(sourceAccountId, sourceName, shard.getShardId(),
                                "AT_TIMESTAMP", null, start.toEpochMilli(), region);
            }
            Map<String, Object> page = kinesisService.getRecordsForAccount(sourceAccountId, iterator,
                    SOURCE_POLL_LIMIT, region);
            @SuppressWarnings("unchecked")
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            String next = (String) page.get("NextShardIterator");
            String advanced = next != null ? next : iterator;
            String shardId = shard.getShardId();
            if (records == null || records.isEmpty()) {
                // Nothing was buffered, so this advance owes S3 nothing and is safe to
                // park as pending on its own.
                advanceSourceIterator(streamKey, shardId, advanced);
                continue;
            }
            // Through putRecordBatch so source records buffer, and trigger a flush, on
            // exactly the same terms as records handed to PutRecord directly. The
            // advance runs under the buffer lock together with the records it covers, so
            // no flush can ever see one without the other.
            putRecordBatch(streamKey, deliveryStreamName, records.stream()
                            .map(record -> new Record(record.getData()))
                            .toList(),
                    () -> advanceSourceIterator(streamKey, shardId, advanced));
        }
    }

    /** The position the poller reads from: pending if a flush still owes it, else committed. */
    private Map<String, String> sourceIteratorsInUse(String streamKey) {
        Map<String, String> pending = pendingSourceIterators.get(streamKey);
        return pending != null ? pending : iteratorGet(streamKey).orElseGet(Map::of);
    }

    // Rebased on whatever is current rather than on a map carried across the shard loop,
    // so that a flush which took pending (and, if its S3 write failed, restored it) is
    // never overwritten by a stale copy this poll carried past that flush.
    private void advanceSourceIterator(String streamKey, String shardId, String iterator) {
        Map<String, String> advanced = new LinkedHashMap<>(sourceIteratorsInUse(streamKey));
        advanced.put(shardId, iterator);
        pendingSourceIterators.put(streamKey, Collections.unmodifiableMap(advanced));
    }

    // Merged rather than written whole: two flushes can complete out of order, and a
    // whole-map write would then drop a shard's newer committed position. Merging can
    // still move one shard backwards, which costs a duplicate and never a skip.
    private void commitSourceIterators(String streamKey, Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return;
        }
        // Copied out and written back whole: the store may hand back its own instance,
        // and a persistent backend only learns of a change through put().
        Map<String, String> committed =
                new LinkedHashMap<>(iteratorGet(streamKey).orElseGet(Map::of));
        committed.putAll(checkpoint);
        iteratorPut(streamKey, committed);
    }

    public void putRecord(String streamName, Record record) {
        putRecordBatch(scopedKey(streamName), streamName, List.of(record), null);
    }

    public void putRecord(String accountId, String region, String streamName, Record record) {
        putRecordBatch(scopedKey(accountId, region, streamName), streamName, List.of(record), null);
    }

    public void putRecordBatch(String streamName, List<Record> records) {
        putRecordBatch(scopedKey(streamName), streamName, records, null);
    }

    public void putRecordBatch(String accountId, String region, String streamName, List<Record> records) {
        putRecordBatch(scopedKey(accountId, region, streamName), streamName, records, null);
    }

    /**
     * @param checkpointAdvance run under the buffer lock once the records are buffered,
     *                          or null for records that are not read from a source stream.
     */
    private void putRecordBatch(String streamKey, String streamName, List<Record> records, Runnable checkpointAdvance) {
        DeliveryStreamDescription stream = describeDeliveryStreamByKey(streamKey);
        List<byte[]> buffer = buffers.computeIfAbsent(
                streamKey, k -> Collections.synchronizedList(new ArrayList<>()));
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
            bufferSince.putIfAbsent(streamKey, clock.instant());
            bufferedCount = buffer.size();
            for (byte[] data : buffer) {
                bufferedBytes += data.length;
            }
        }
        if ((flushRecordCount > 0 && bufferedCount >= flushRecordCount)
                || bufferedBytes >= bufferingSizeLimitBytes(stream)) {
            flush(streamKey, stream);
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
        flushByKey(scopedKey(streamName));
    }

    public void flush(String accountId, String region, String streamName) {
        flushByKey(scopedKey(accountId, region, streamName));
    }

    private void flushByKey(String streamKey) {
        streamGet(streamKey).ifPresent(stream -> flush(streamKey, stream));
    }

    private void flush(String streamName, DeliveryStreamDescription stream) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer == null || buffer.isEmpty()) {
            return;
        }

        synchronized (flushLocks.computeIfAbsent(streamName, k -> new Object())) {
            List<byte[]> toFlush;
            // Taken with the records, under the same lock: this is the position the records
            // about to be written cover, and committing it is this flush's job.
            Map<String, String> checkpoint;
            Instant since;
            synchronized (buffer) {
                toFlush = new ArrayList<>(buffer);
                buffer.clear();
                since = bufferSince.remove(streamName);
                checkpoint = pendingSourceIterators.remove(streamName);
            }
            if (toFlush.isEmpty()) {
                // Lost the race for the stream lock; nothing left to deliver. Any
                // checkpoint taken here covers no undelivered records -- it can only have
                // come from a poll that read an empty page -- so it commits as it stands.
                commitSourceIterators(streamName, checkpoint);
                return;
            }

            // Every store this delivery touches, Glue and S3 included, reads the account
            // from the request context, and a scheduled flush has none. Without this the
            // work would run as the default account rather than the stream's owner: the
            // stream's Glue table would be missed and its objects would land in the wrong
            // partition. The sidecar follows the same context, so both sides stay together.
            RequestScopes.runAs(stream.getAccountId(),
                    () -> deliverBuffer(streamName, stream, toFlush, since, checkpoint));
        }
    }

    private void deliverBuffer(String streamName, DeliveryStreamDescription stream,
                               List<byte[]> toFlush, Instant since, Map<String, String> checkpoint) {
        try {
            String bucket = resolveBucket(stream);
            S3Destination s3 = stream.s3Destination();
            List<byte[]> records = toFlush;
            if (s3 != null && s3.isProcessingEnabled()) {
                ensureBucket(bucket, stream);
                FirehoseLambdaTransformer.Outcome transformed =
                        lambdaTransformer.transform(stream, bucket, records, clock.instant());
                LOG.infov("Transformed {0} records from stream {1} ({2} dropped, {3} failed)",
                        records.size(), streamName, transformed.droppedRecords(), transformed.failedRecords());
                records = transformed.records();
                if (records.isEmpty()) {
                    // Nothing survived the transform. The batch is accounted for, dropped
                    // records deliberately leaving no trace and failed ones already in the
                    // error output, so the source may advance past it.
                    commitSourceIterators(streamName, checkpoint);
                    return;
                }
            }
            if (s3 != null && s3.isDataFormatConversionEnabled()) {
                ensureBucket(bucket, stream);
                FirehoseParquetConverter.Outcome outcome =
                        parquetConverter.deliver(stream, bucket, records, clock.instant());
                LOG.infov("Converted {0} records ({1} failed) from stream {2} to s3://{3}/{4}",
                        outcome.convertedRecords(), outcome.failedRecords(), streamName, bucket,
                        outcome.dataKey() != null ? outcome.dataKey() : outcome.errorKey());
                commitSourceIterators(streamName, checkpoint);
                return;
            }
            FirehoseCompression compression =
                    FirehoseCompression.forDelivery(s3 == null ? null : s3.getCompressionFormat());
            String key = S3ObjectKeyResolver.resolveKey(s3, stream.getDeliveryStreamName(),
                    stream.getVersionId(), clock.instant(), compression);

            ensureBucket(bucket, stream);

            // Records are arbitrary bytes, so they are concatenated as bytes: routing
            // them through a String would corrupt any payload that is not valid UTF-8.
            // The appended newline is a deliberate deviation kept from before this
            // method compressed anything: real AWS inserts no separator at all
            // (verified: three "abc" records arrive as the 9 bytes "abcabcabc").
            // See the deviation noted in docs/services/firehose.md.
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            for (byte[] data : records) {
                payload.writeBytes(data);
                if (data.length > 0 && data[data.length - 1] != '\n') {
                    payload.write('\n');
                }
            }

            byte[] rawPayload = payload.toByteArray();
            byte[] body = compression.compress(rawPayload);
            s3Service.putObject(bucket, key, body, "application/octet-stream", Map.of(),
                    new PutObjectOptions().withContentEncoding(compression.contentEncoding()));
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3} ({4})",
                    records.size(), streamName, bucket, key, compression.wireValue());
            if (isRedshiftStream(stream)) {
                deliverRedshiftBatch(streamName, stream, bucket, key, rawPayload, checkpoint);
                return;
            }
            // Only now: the records these iterators were read past are durable.
            commitSourceIterators(streamName, checkpoint);
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}; keeping {1} records buffered for retry: {2}",
                    streamName, toFlush.size(), e.getMessage());
            restoreFailedFlush(streamName, toFlush, since, checkpoint);
        }
    }

    /**
     * Everything after a Redshift batch is staged and queued. The batch is durable from the moment it is queued, so
     * no step here may hand its records back to the buffer: that would stage and load them a second time. A failing
     * backup write or checkpoint write is logged instead, and the load itself retries from the queue.
     */
    private void deliverRedshiftBatch(String streamName, DeliveryStreamDescription stream,
                                     String bucket, String key, byte[] rawPayload,
                                     Map<String, String> checkpoint) {
        RedshiftDestination redshift = stream.getDestinations().get(0).getRedshiftDestinationDescription();
        try {
            writeRedshiftBackup(stream, redshift, rawPayload);
        } catch (IOException | RuntimeException e) {
            LOG.errorv(e, "Backup write for stream {0} failed; the batch is staged in S3 and loads normally but "
                    + "has no backup copy: {1}", streamName, describe(e));
        }
        try {
            commitSourceIterators(streamName, checkpoint);
        } catch (RuntimeException e) {
            LOG.errorv(e, "Could not persist the source checkpoint of stream {0} after its batch was staged; the "
                    + "source records may be read again: {1}", streamName, describe(e));
        }
        redshiftPending.compute(streamName, (k, existing) -> {
            Deque<PendingRedshiftCopy> queue = existing != null ? existing : new ConcurrentLinkedDeque<>();
            queue.addLast(new PendingRedshiftCopy(bucket, key));
            return queue;
        });
        submitRedshiftDrain(streamName, stream);
    }

    /**
     * A batch already staged in S3 and waiting for its COPY. {@code failingSince} marks when the initial attempt began
     * and {@code nextAttempt} spaces the retries out by 5 minutes, so a failing load neither floods the log nor holds the
     * shared tick thread for a connection attempt on every tick.
     */
    private static final class PendingRedshiftCopy {
        private final String bucket;
        private final String key;
        private Instant failingSince;
        private Instant nextAttempt;
        private int failures;

        private PendingRedshiftCopy(String bucket, String key) {
            this.bucket = bucket;
            this.key = key;
        }
    }

    /** Retries the COPY of every stream that still has staged batches waiting and due. Called on each tick. */
    void retryPendingRedshiftCopies() {
        for (String streamKey : redshiftPending.keySet()) {
            if (!isRedshiftDrainDue(streamKey)) {
                continue;
            }
            Optional<DeliveryStreamDescription> stream = streamGet(streamKey);
            if (stream.isEmpty() || !isRedshiftStream(stream.get())) {
                redshiftPending.remove(streamKey);
                continue;
            }
            submitRedshiftDrain(streamKey, stream.get());
        }
    }

    private boolean isRedshiftDrainDue(String streamKey) {
        Deque<PendingRedshiftCopy> queue = redshiftPending.get(streamKey);
        if (queue == null || queue.isEmpty()) {
            return false;
        }
        PendingRedshiftCopy head = queue.peekFirst();
        return head != null && (head.nextAttempt == null || !clock.instant().isBefore(head.nextAttempt));
    }

    private void submitRedshiftDrain(String streamKey, DeliveryStreamDescription stream) {
        if (!activeRedshiftDrains.add(streamKey)) {
            return;
        }
        try {
            redshiftDeliveryExecutor.execute(() -> {
                try {
                    RequestScopes.runAs(stream.getAccountId(), () -> drainRedshiftCopies(streamKey, stream));
                } finally {
                    activeRedshiftDrains.remove(streamKey);
                    if (redshiftPending.containsKey(streamKey) && isRedshiftDrainDue(streamKey)) {
                        submitRedshiftDrain(streamKey, stream);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            activeRedshiftDrains.remove(streamKey);
            LOG.errorv("Redshift delivery executor rejected task for stream {0}: {1}", streamKey, describe(e));
        }
    }

    /** Loads the stream's staged batches oldest first and stops at the first one that must be retried. */
    private void drainRedshiftCopies(String streamKey, DeliveryStreamDescription stream) {
        Deque<PendingRedshiftCopy> queue = redshiftPending.get(streamKey);
        if (queue == null) {
            return;
        }
        if (!isRedshiftStream(stream)) {
            redshiftPending.remove(streamKey);
            return;
        }
        RedshiftDestination redshift = stream.getDestinations().get(0).getRedshiftDestinationDescription();
        PendingRedshiftCopy next;
        while (redshiftPending.containsKey(streamKey) && (next = queue.peekFirst()) != null
                && copyStaged(streamKey, stream, redshift, next)) {
            queue.pollFirst();
        }
        redshiftPending.computeIfPresent(streamKey, (k, remaining) -> remaining.isEmpty() ? null : remaining);
    }

    /**
     * Returns true when the batch is accounted for (loaded, or failed past the retry window) and
     * false when it must be tried again.
     */
    private boolean copyStaged(String streamKey, DeliveryStreamDescription stream, RedshiftDestination redshift,
                               PendingRedshiftCopy pending) {
        String table = redshift.getCopyCommand().getDataTableName();
        if (redshiftDelivery == null) {
            LOG.warnv("Stream {0} has a Redshift destination but Redshift delivery is unavailable; "
                    + "the batch stays in s3://{1}/{2}", streamKey, pending.bucket, pending.key);
            return true;
        }
        Instant now = clock.instant();
        if (pending.nextAttempt != null && now.isBefore(pending.nextAttempt)) {
            return false;
        }
        int window = redshift.getRetryOptions() != null && redshift.getRetryOptions().getDurationInSeconds() != null
                ? redshift.getRetryOptions().getDurationInSeconds() : DEFAULT_REDSHIFT_RETRY_SECONDS;
        if (pending.failingSince != null && window > 0 && !now.isBefore(pending.failingSince.plusSeconds(window))) {
            LOG.errorv("Redshift delivery into table {0} failed for stream {1} and the retry window of {2}s is over; "
                    + "the batch stays in s3://{3}/{4}", table, streamKey, window, pending.bucket, pending.key);
            return true;
        }
        Instant attemptStart = now;
        try {
            String region = AwsArnUtils.regionOrDefault(stream.getDeliveryStreamARN(),
                    regionResolver.getDefaultRegion());
            redshiftDelivery.copy(stream.getAccountId(), region, redshift, pending.bucket, pending.key);
            return true;
        } catch (RuntimeException e) {
            Instant failedAt = clock.instant();
            boolean expected = e instanceof AwsException || e instanceof IllegalStateException
                    || e instanceof IllegalArgumentException;
            if (pending.failingSince == null) {
                pending.failingSince = attemptStart;
            }
            pending.failures++;
            if (window > 0 && failedAt.isBefore(pending.failingSince.plusSeconds(window))) {
                pending.nextAttempt = failedAt.plusSeconds(REDSHIFT_RETRY_INTERVAL_SECONDS);
                String reason = describe(e);
                if (!expected) {
                    LOG.errorv(e, "Unexpected failure delivering s3://{0}/{1} into table {2} for stream {3}; "
                            + "retrying in {4}s", pending.bucket, pending.key, table, streamKey, REDSHIFT_RETRY_INTERVAL_SECONDS);
                } else if (pending.failures == 1) {
                    LOG.warnv("Redshift delivery of s3://{0}/{1} into table {2} failed for stream {3}, "
                            + "retrying in {4}s: {5}", pending.bucket, pending.key, table, streamKey, REDSHIFT_RETRY_INTERVAL_SECONDS,
                            reason);
                } else {
                    LOG.debugv("Redshift delivery of s3://{0}/{1} into table {2} failed again for stream {3} "
                            + "(attempt {4}), retrying in {5}s: {6}", pending.bucket, pending.key, table, streamKey,
                            pending.failures, REDSHIFT_RETRY_INTERVAL_SECONDS, reason);
                }
                return false;
            }
            LOG.errorv("Redshift delivery into table {0} failed for stream {1} and the retry window of {2}s is over; "
                    + "the batch stays in s3://{3}/{4}: {5}", table, streamKey, window, pending.bucket, pending.key,
                    describe(e));
            return true;
        }
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** Writes the raw batch to the backup location under that location's own prefix, compression and bucket. */
    private void writeRedshiftBackup(DeliveryStreamDescription stream, RedshiftDestination redshift,
                                     byte[] rawPayload) throws IOException {
        S3Destination backup = redshift.getS3Backup();
        if (!"Enabled".equals(redshift.getS3BackupMode()) || backup == null || backup.bucketName() == null) {
            return;
        }
        FirehoseCompression compression = FirehoseCompression.forDelivery(backup.getCompressionFormat());
        String backupKey = S3ObjectKeyResolver.resolveKey(backup, stream.getDeliveryStreamName(),
                stream.getVersionId(), clock.instant(), compression);
        ensureBucket(backup.bucketName(), stream);
        s3Service.putObject(backup.bucketName(), backupKey, compression.compress(rawPayload),
                "application/octet-stream", Map.of(),
                new PutObjectOptions().withContentEncoding(compression.contentEncoding()));
    }

    private static boolean isRedshiftStream(DeliveryStreamDescription stream) {
        return stream.getDestinations() != null && !stream.getDestinations().isEmpty()
                && stream.getDestinations().get(0).getRedshiftDestinationDescription() != null;
    }

    private void restoreFailedFlush(String streamName, List<byte[]> toFlush, Instant since,
                                    Map<String, String> checkpoint) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer != null) {
            synchronized (buffer) {
                List<byte[]> restored = new ArrayList<>(toFlush);
                restored.addAll(buffer);
                buffer.clear();
                buffer.addAll(restored);
                // The restored batch is the oldest data in the buffer again, so the buffering
                // window starts where it did before the failed flush, not at a record that
                // arrived while the write was in flight.
                bufferSince.put(streamName, since != null ? since : clock.instant());
                if (checkpoint != null) {
                    Map<String, String> pending = new LinkedHashMap<>(checkpoint);
                    Map<String, String> concurrent = pendingSourceIterators.get(streamName);
                    if (concurrent != null) {
                        pending.putAll(concurrent);
                    }
                    pendingSourceIterators.put(streamName, Collections.unmodifiableMap(pending));
                }
            }
        }
    }

    private String resolveBucket(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        if (s3 != null && s3.bucketName() != null) {
            return s3.bucketName();
        }
        return DEFAULT_BUCKET;
    }

    /**
     * Creates a missing destination bucket. A stream's own destination goes in the stream's region,
     * from its ARN; the shared default bucket, used by every stream without one, stays in the default
     * region so the first stream to deliver does not decide it for all the others.
     */
    private void ensureBucket(String bucket, DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        boolean ownDestination = s3 != null && s3.bucketName() != null;
        String region = ownDestination
                ? AwsArnUtils.regionOrDefault(stream.getDeliveryStreamARN(), regionResolver.getDefaultRegion())
                : regionResolver.getDefaultRegion();
        try {
            if (ownDestination) {
                s3Service.createBucket(bucket, region);
            } else {
                s3Service.createInternalBucket(bucket, region);
            }
        } catch (Exception ignored) {
            // The bucket usually exists already; a real problem with it surfaces on the write that follows.
        }
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (DeliveryStreamDescription stream : streamStore.scanAllAccounts()) {
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
