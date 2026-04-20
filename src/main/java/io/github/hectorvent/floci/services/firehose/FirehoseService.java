package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FirehoseService {

    private static final Logger LOG = Logger.getLogger(FirehoseService.class);

    private final StorageBackend<String, DeliveryStreamDescription> streamStore;
    private final Map<String, List<byte[]>> buffers = new ConcurrentHashMap<>();
    private final S3Service s3Service;

    @Inject
    public FirehoseService(StorageFactory storageFactory, S3Service s3Service) {
        this.streamStore = storageFactory.create("firehose", "streams.json", new TypeReference<Map<String, DeliveryStreamDescription>>() {});
        this.s3Service = s3Service;
    }

    public String createDeliveryStream(String name) {
        String arn = "arn:aws:firehose:us-east-1:000000000000:deliverystream/" + name;
        DeliveryStreamDescription description = new DeliveryStreamDescription(name, arn);
        streamStore.put(name, description);
        buffers.put(name, Collections.synchronizedList(new ArrayList<>()));
        LOG.infov("Created Firehose Delivery Stream: {0}", name);
        return arn;
    }

    public DeliveryStreamDescription describeDeliveryStream(String name) {
        return streamStore.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Stream not found: " + name, 400));
    }

    public void deleteDeliveryStream(String name) {
        describeDeliveryStream(name); // Checks if it exists and throws if not
        streamStore.remove(name);
        buffers.remove(name);
        LOG.infov("Deleted Firehose Delivery Stream: {0}", name);
    }

    public List<String> listDeliveryStreams() {
        return streamStore.scan(k -> true).stream().map(DeliveryStreamDescription::getDeliveryStreamName).toList();
    }

    public void putRecord(String streamName, Record record) {
        describeDeliveryStream(streamName); // Check exists
        buffers.get(streamName).add(record.getData());

        // Flush every 5 records for observable local feedback
        if (buffers.get(streamName).size() >= 5) {
            flush(streamName);
        }
    }

    public void flush(String streamName) {
        List<byte[]> buffer = buffers.get(streamName);
        if (buffer.isEmpty()) {
            return;
        }

        List<byte[]> toFlush;
        synchronized (buffer) {
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }

        try {
            StringBuilder sb = new StringBuilder();
            for (byte[] data : toFlush) {
                sb.append(new String(data, StandardCharsets.UTF_8)).append("\n");
            }

            String bucket = "floci-firehose-results";
            String key = streamName + "/" + UUID.randomUUID() + ".json";

            try {
                s3Service.createBucket(bucket, "us-east-1");
            } catch (Exception ignored) {}

            byte[] jsonBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            s3Service.putObject(bucket, key, jsonBytes, "application/x-ndjson", Map.of());
            LOG.infov("Flushed {0} records from stream {1} to s3://{2}/{3}", toFlush.size(), streamName, bucket, key);
        } catch (Exception e) {
            LOG.errorv("Failed to flush Firehose stream {0}: {1}", streamName, e.getMessage());
        }
    }
}
