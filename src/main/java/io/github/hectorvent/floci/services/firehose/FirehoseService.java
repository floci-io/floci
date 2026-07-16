package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FirehoseService {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);
    private static final String DEFAULT_BUCKET = "floci-firehose-results";
    // How often the background flusher checks buffers against their stream's
    // IntervalInSeconds. Delivery therefore lands within interval + 1s.
    private static final long FLUSH_TICK_MILLIS = 1_000L;
    // Bounds retries of a persistently failing destination so the in-memory
    // buffer can't grow without limit; AWS likewise gives up eventually
    // (retry window, then error output) rather than buffering forever.
    private static final int MAX_DELIVERY_ATTEMPTS = 10;

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    // Keyed per account to mirror the account-prefixed streamStore, so
    // same-named streams in different accounts don't share a buffer.
    private final Map<BufferKey, StreamBuffer> buffers = new ConcurrentHashMap<>();
    private final S3Service s3Service;
    private final RegionResolver regionResolver;
    private ScheduledExecutorService flushScheduler;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, RegionResolver regionResolver) {
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
    }

    void onStart(@Observes StartupEvent ev) {
        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "floci-firehose-flusher");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flushDueBuffers,
                FLUSH_TICK_MILLIS, FLUSH_TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (flushScheduler != null) {
            flushScheduler.shutdownNow();
            flushScheduler = null;
        }
    }

    public String createDeliveryStream(String name, S3Destination s3Config) {
        return createDeliveryStream(name, s3Config, List.of());
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags) {
        return createDeliveryStream(name, s3Config, tags, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType) {
        if (streamStore.get(name).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Firehose " + name + " under accountId " + regionResolver.getAccountId() + " already exists", 400);
        }
        validateBufferingHints(s3Config);
        String arn = AwsArnUtils.Arn.of("firehose", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "deliverystream/" + name).toString();
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config);
        description.setAccountId(regionResolver.getAccountId());
        description.setTags(tags);
        if (deliveryStreamType != null && !deliveryStreamType.isBlank()) {
            description.setDeliveryStreamType(deliveryStreamType);
        }
        streamStore.put(name, description);
        buffers.put(bufferKey(name), new StreamBuffer());
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
        // Ranges from the BufferingHints API reference: SizeInMBs 1-128, IntervalInSeconds 0-900.
        requireRange(hints.getSizeInMBs(), 1, 128, "bufferingHints.sizeInMBs");
        requireRange(hints.getIntervalInSeconds(), 0, 900, "bufferingHints.intervalInSeconds");
    }

    private static void requireRange(Integer value, int min, int max, String member) {
        if (value == null || (value >= min && value <= max)) {
            return;
        }
        throw new AwsException("ValidationException",
                "1 validation error detected: Value '" + value + "' at '" + member
                        + "' failed to satisfy constraint: Member must have value "
                        + (value < min ? "greater than or equal to " + min : "less than or equal to " + max),
                400);
    }

    private static void mergeDestination(S3Destination current, S3Destination update) {
        if (update.getRoleArn() != null) current.setRoleArn(update.getRoleArn());
        if (update.getBucketArn() != null) current.setBucketArn(update.getBucketArn());
        if (update.getPrefix() != null) current.setPrefix(update.getPrefix());
        if (update.getErrorOutputPrefix() != null) current.setErrorOutputPrefix(update.getErrorOutputPrefix());
        if (update.getCompressionFormat() != null) current.setCompressionFormat(update.getCompressionFormat());
        if (update.getBufferingHints() != null) current.setBufferingHints(update.getBufferingHints());
        if (update.getEncryptionConfiguration() != null) current.setEncryptionConfiguration(update.getEncryptionConfiguration());
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
        // Undelivered buffered data is dropped, matching AWS: deleting a
        // delivery stream can lose data that hasn't reached the destination.
        buffers.remove(bufferKey(name));
        LOG.infov("Deleted Firehose delivery stream: {0}", name);
    }

    public List<String> listDeliveryStreams() {
        return streamStore.scan(k -> true).stream()
                .map(DeliveryStreamDescription::getDeliveryStreamName).toList();
    }

    public void putRecord(String streamName, Record record) {
        putRecordBatch(streamName, List.of(record));
    }

    public void putRecordBatch(String streamName, List<Record> records) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        StreamBuffer buffer = buffers.computeIfAbsent(bufferKey(streamName), k -> new StreamBuffer());
        for (Record r : records) {
            buffer.add(r.getData() == null ? new byte[0] : r.getData());
        }
        flushIfSizeReached(streamName, stream, buffer);
    }

    public void flush(String streamName) {
        streamStore.get(streamName).ifPresent(stream -> flush(streamName, stream));
    }

    /** Size half of BufferingHints: deliver as soon as SizeInMBs worth of data is buffered. */
    private void flushIfSizeReached(String streamName, DeliveryStreamDescription stream, StreamBuffer buffer) {
        DeliveryStreamDescription.BufferingHints hints = bufferingHints(stream);
        long thresholdBytes = hints.getSizeInMBs() * 1024L * 1024L;
        if (buffer.byteCount() >= thresholdBytes) {
            flush(streamName, stream);
        }
    }

    /**
     * Interval half of BufferingHints: runs on the background flusher thread every
     * {@link #FLUSH_TICK_MILLIS} and delivers any buffer whose oldest record has
     * been waiting at least the stream's IntervalInSeconds.
     */
    private void flushDueBuffers() {
        for (Map.Entry<BufferKey, StreamBuffer> entry : buffers.entrySet()) {
            try {
                flushIfDue(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                LOG.errorv("Firehose flush tick failed for stream {0}: {1}",
                        entry.getKey().streamName(), e.getMessage());
            }
        }
    }

    private void flushIfDue(BufferKey key, StreamBuffer buffer) {
        Instant oldest = buffer.oldestRecordAt();
        if (oldest == null) {
            return;
        }
        DeliveryStreamDescription stream = streamForAccount(key);
        if (stream == null) {
            // Stream deleted outside the request path that owned the buffer: drop the
            // orphaned buffer — conditionally, since the stream (and a fresh buffer)
            // may have been re-created concurrently.
            buffers.remove(key, buffer);
            return;
        }
        int interval = bufferingHints(stream).getIntervalInSeconds();
        if (Duration.between(oldest, Instant.now()).getSeconds() < interval) {
            return;
        }
        // Only the actual delivery needs the synthetic request scope: the S3 write
        // resolves buckets through account-aware storage, which reads the calling
        // account from the active request context, and this thread has none.
        runUnderAccount(key.accountId(), () -> flush(key.streamName(), stream));
    }

    /**
     * Async-worker read of the stream store: outside a request scope the
     * account-aware backend would fall back to the default account, so use the
     * explicit per-account overload with the buffer's owning account.
     */
    private DeliveryStreamDescription streamForAccount(BufferKey key) {
        if (streamStore instanceof AccountAwareStorageBackend<DeliveryStreamDescription> accountAware) {
            return accountAware.getForAccount(key.accountId(), key.streamName()).orElse(null);
        }
        return streamStore.get(key.streamName()).orElse(null);
    }

    /**
     * Activates a synthetic CDI request scope bound to {@code accountId} and runs
     * {@code body} inside it (same pattern as {@code CurEmissionScheduler}): the
     * account-aware storage backends used by the stream store and S3 read the
     * calling account from the active request context. If a scope was already
     * active, the previous account is restored afterwards so it isn't left
     * behind on a reused thread.
     */
    private void runUnderAccount(String accountId, Runnable body) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        RequestContext ctx = Arc.container().instance(RequestContext.class).get();
        String previousAccountId = alreadyActive ? ctx.getAccountId() : null;
        try {
            ctx.setAccountId(accountId);
            body.run();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            } else {
                ctx.setAccountId(previousAccountId);
            }
        }
    }

    private void flush(String streamName, DeliveryStreamDescription stream) {
        StreamBuffer buffer = buffers.get(bufferKey(streamName));
        if (buffer == null) {
            return;
        }

        List<byte[]> toFlush = buffer.drain();
        if (toFlush.isEmpty()) {
            return;
        }

        try {
            String bucket = resolveBucket(stream);
            String prefix = resolvePrefix(stream);
            String key = prefix + UUID.randomUUID() + ".json";

            ensureBucket(bucket);

            // Assemble the newline-delimited body on raw bytes: record data is an
            // arbitrary binary blob, so a String round-trip would corrupt non-UTF-8
            // payloads (and copy every byte twice for nothing).
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (byte[] data : toFlush) {
                body.write(data, 0, data.length);
                if (data.length > 0 && data[data.length - 1] != '\n') {
                    body.write('\n');
                }
            }

            s3Service.putObject(bucket, key, body.toByteArray(), "application/x-ndjson", Map.of());
            buffer.markDelivered();
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3}",
                    toFlush.size(), streamName, bucket, key);
        } catch (Exception e) {
            // Put the drained records back (ahead of anything buffered meanwhile,
            // preserving order) so the next interval tick retries instead of
            // silently dropping them — up to MAX_DELIVERY_ATTEMPTS, after which the
            // batch is dropped so a permanently failing destination can't grow the
            // in-memory buffer without bound.
            if (buffer.restoreForRetry(toFlush, MAX_DELIVERY_ATTEMPTS)) {
                LOG.errorv("Failed to flush Firehose stream {0}, will retry: {1}", streamName, e.getMessage());
            } else {
                LOG.errorv("Failed to flush Firehose stream {0} after {1} attempts, dropping {2} records: {3}",
                        streamName, MAX_DELIVERY_ATTEMPTS, toFlush.size(), e.getMessage());
            }
        }
    }

    private BufferKey bufferKey(String streamName) {
        return new BufferKey(regionResolver.getAccountId(), streamName);
    }

    /** Effective hints for delivery decisions: the stream's own, or the AWS defaults (5 MiB / 300 s). */
    private static DeliveryStreamDescription.BufferingHints bufferingHints(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        if (s3 != null && s3.getBufferingHints() != null
                && s3.getBufferingHints().getSizeInMBs() != null
                && s3.getBufferingHints().getIntervalInSeconds() != null) {
            return s3.getBufferingHints();
        }
        return DeliveryStreamDescription.BufferingHints.defaults();
    }

    /** Identifies one stream's buffer: the owning account plus the stream name. */
    private record BufferKey(String accountId, String streamName) {}

    /**
     * In-memory delivery buffer for one stream: the pending record payloads plus
     * the byte count (drives the SizeInMBs trigger) and the arrival time of the
     * oldest pending record (drives the IntervalInSeconds trigger).
     */
    private static final class StreamBuffer {
        private final List<byte[]> records = new ArrayList<>();
        private long byteCount;
        private Instant oldestRecordAt;
        private int failedAttempts;

        synchronized void add(byte[] data) {
            if (records.isEmpty()) {
                oldestRecordAt = Instant.now();
            }
            records.add(data);
            byteCount += data.length;
        }

        synchronized List<byte[]> drain() {
            List<byte[]> drained = new ArrayList<>(records);
            records.clear();
            byteCount = 0;
            oldestRecordAt = null;
            return drained;
        }

        synchronized void markDelivered() {
            failedAttempts = 0;
        }

        /**
         * Puts a failed batch back at the head of the buffer for a later retry.
         * Returns false — and drops the batch — once maxAttempts deliveries of it
         * have failed.
         */
        synchronized boolean restoreForRetry(List<byte[]> drained, int maxAttempts) {
            if (++failedAttempts >= maxAttempts) {
                failedAttempts = 0;
                return false;
            }
            records.addAll(0, drained);
            for (byte[] data : drained) {
                byteCount += data.length;
            }
            // Pace retries by the stream's interval rather than retrying every tick.
            oldestRecordAt = Instant.now();
            return true;
        }

        synchronized long byteCount() {
            return byteCount;
        }

        synchronized Instant oldestRecordAt() {
            return oldestRecordAt;
        }
    }

    private String resolveBucket(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        if (s3 != null && s3.bucketName() != null) {
            return s3.bucketName();
        }
        return DEFAULT_BUCKET;
    }

    private String resolvePrefix(DeliveryStreamDescription stream) {
        S3Destination s3 = stream.s3Destination();
        String prefix = (s3 != null && s3.getPrefix() != null) ? s3.getPrefix() : stream.getDeliveryStreamName() + "/";

        // Substitute time-based placeholders matching real Firehose
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        prefix = prefix
                .replace("{year}", String.format("%04d", now.getYear()))
                .replace("{month}", String.format("%02d", now.getMonthValue()))
                .replace("{day}", String.format("%02d", now.getDayOfMonth()))
                .replace("{hour}", String.format("%02d", now.getHour()));

        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private void ensureBucket(String bucket) {
        try {
            s3Service.createBucket(bucket, regionResolver.getDefaultRegion());
        } catch (Exception ignored) {}
    }
}
