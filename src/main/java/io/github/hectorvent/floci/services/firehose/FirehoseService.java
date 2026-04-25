package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service, RegionResolver regionResolver) {
        this.streamStore = storageFactory.create("firehose", "streams.json",
                new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
    }

    public String createDeliveryStream(String name, S3Destination s3Config) {
        String arn = "arn:aws:firehose:" + regionResolver.getDefaultRegion()
                + ":" + regionResolver.getAccountId() + ":deliverystream/" + name;
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn, s3Config);
        streamStore.put(name, description);
        buffers.put(name, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose delivery stream: {0}", name);
        return arn;
    }

    public DeliveryStreamDescription describeDeliveryStream(String name) {
        return streamStore.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Delivery stream not found: " + name, 400));
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
            String prefix = resolvePrefix(stream);
            String key = prefix + UUID.randomUUID() + ".json";

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
