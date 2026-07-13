package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FirehoseService {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);
    private static final String DEFAULT_BUCKET = "floci-firehose-results";
    private static final int DEFAULT_FLUSH_COUNT = 5;

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final S3Service s3Service;
    private final RegionResolver regionResolver;
    private final Clock clock;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, RegionResolver regionResolver,
                           Clock clock) {
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    public String createDeliveryStream(String name, S3Destination s3Config) {
        return createDeliveryStream(name, s3Config, List.of());
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags) {
        return createDeliveryStream(name, s3Config, tags, null);
    }

    public String createDeliveryStream(String name, S3Destination s3Config, List<DeliveryStreamDescription.Tag> tags,
                                       String deliveryStreamType) {
        validateBufferingHints(s3Config);
        String arn = AwsArnUtils.Arn.of("firehose", regionResolver.getDefaultRegion(), regionResolver.getAccountId(), "deliverystream/" + name).toString();
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config);
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
        if (update.getCustomTimeZone() != null) current.setCustomTimeZone(update.getCustomTimeZone());
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
        buffers.remove(name);
        LOG.infov("Deleted Firehose delivery stream: {0}", name);
    }

    public List<String> listDeliveryStreams() {
        return streamStore.scan(k -> true).stream()
                .map(DeliveryStreamDescription::getDeliveryStreamName).toList();
    }

    public void putRecord(String streamName, Record record) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        buffers.computeIfAbsent(streamName, k -> Collections.synchronizedList(new ArrayList<>()))
               .add(record.getData());

        if (buffers.get(streamName).size() >= DEFAULT_FLUSH_COUNT) {
            flush(streamName, stream);
        }
    }

    public void putRecordBatch(String streamName, List<Record> records) {
        DeliveryStreamDescription stream = describeDeliveryStream(streamName);
        List<byte[]> buffer = buffers.computeIfAbsent(
                streamName, k -> Collections.synchronizedList(new ArrayList<>()));
        for (Record r : records) {
            buffer.add(r.getData());
        }
        if (buffer.size() >= DEFAULT_FLUSH_COUNT) {
            flush(streamName, stream);
        }
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
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }

        try {
            String bucket = resolveBucket(stream);
            S3Destination s3 = stream.s3Destination();
            ZoneId zone = S3ObjectKeyResolver.resolveZone(s3 != null ? s3.getCustomTimeZone() : null);
            String key = S3ObjectKeyResolver.resolveKey(s3 != null ? s3.getPrefix() : null,
                    stream.getDeliveryStreamName(), stream.getVersionId(), clock.instant(), zone);

            ensureBucket(bucket);

            StringBuilder sb = new StringBuilder();
            for (byte[] data : toFlush) {
                sb.append(new String(data, StandardCharsets.UTF_8));
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
            }

            byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
            s3Service.putObject(bucket, key, body, "application/x-ndjson", Map.of());
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3}",
                    toFlush.size(), streamName, bucket, key);
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}: {1}", streamName, e.getMessage());
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
}
