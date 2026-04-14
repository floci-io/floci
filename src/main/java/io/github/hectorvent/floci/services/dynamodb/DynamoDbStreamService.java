package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class DynamoDbStreamService {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamService.class);

    public static final String SHARD_ID = "shardId-0000000001-00000000001";
    static final int MAX_RECORDS = 1000;

    private static final DateTimeFormatter STREAM_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ConcurrentHashMap<String, StreamDescription> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<DynamoDbStreamRecord>> records =
            new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private final ObjectMapper objectMapper;
    private KinesisService kinesisService;

    @Inject
    public DynamoDbStreamService(ObjectMapper objectMapper, StorageFactory storageFactory,
                                 KinesisService kinesisService) {
        this(objectMapper, storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}), kinesisService);
    }

    /** Package-private constructor for testing. */
    DynamoDbStreamService(ObjectMapper objectMapper, StorageBackend<String, TableDefinition> tableStore) {
        this(objectMapper, tableStore, null);
    }

    DynamoDbStreamService(ObjectMapper objectMapper, StorageBackend<String, TableDefinition> tableStore,
                          KinesisService kinesisService) {
        this.objectMapper = objectMapper;
        this.kinesisService = kinesisService;
        loadPersistedStreams(tableStore);
    }


    private void loadPersistedStreams(StorageBackend<String, TableDefinition> tableStore) {
        if (tableStore == null) return;
        for (String tableKey : tableStore.keys()){
                String region = tableKey.split("::", 2)[0];
                tableStore.get(tableKey).ifPresent(table -> {
                        if (!table.isStreamEnabled()) return;
                        this.enableStream(table.getTableName(), table.getTableArn(), table.getStreamViewType(), region, table.getStreamArn());
                    });
        }
    }

    public StreamDescription enableStream(String tableName, String tableArn, String viewType, String region) {
        return enableStream(tableName, tableArn, viewType, region, null);
    }

    public StreamDescription enableStream(String tableName, String tableArn, String viewType, String region, String streamArnInput) {
        String key = streamKey(region, tableName);
        StreamDescription existing = streams.get(key);
        if (existing != null && "ENABLED".equals(existing.getStreamStatus())) {
            return existing;
        }

        String streamArn = streamArnInput;
        Instant now = Instant.now();
        String label;
        if (streamArn == null){
            label = STREAM_LABEL_FORMAT.format(now);
            streamArn = tableArn + "/stream/" + label;
        }
        else {
            label = streamArn.split("/stream/", 2)[1];
        }

        StreamDescription sd = new StreamDescription();
        sd.setStreamArn(streamArn);
        sd.setStreamLabel(label);
        sd.setStreamStatus("ENABLED");
        sd.setStreamViewType(viewType);
        sd.setTableName(tableName);
        sd.setCreationDateTime(now);
        sd.setStartingSequenceNumber(String.format("%021d", 1));

        streams.put(key, sd);
        records.put(streamArn, new ConcurrentLinkedDeque<>());
        LOG.infov("Enabled stream for table {0} in region {1}: {2}", tableName, region, streamArn);
        return sd;
    }

    public void disableStream(String tableName, String region) {
        String key = streamKey(region, tableName);
        StreamDescription sd = streams.get(key);
        if (sd != null) {
            sd.setStreamStatus("DISABLED");
            LOG.infov("Disabled stream for table {0} in region {1}", tableName, region);
        }
    }

    public void deleteStream(String tableName, String region) {
        String key = streamKey(region, tableName);
        StreamDescription sd = streams.remove(key);
        if (sd != null) {
            records.remove(sd.getStreamArn());
            LOG.infov("Deleted stream for table {0} in region {1}", tableName, region);
        }
    }

    public void captureEvent(String tableName, String eventName,
                             JsonNode oldItem, JsonNode newItem,
                             TableDefinition table, String region) {
        String key = streamKey(region, tableName);
        StreamDescription sd = streams.get(key);
        if (sd == null || !"ENABLED".equals(sd.getStreamStatus())) {
            return;
        }

        long seq = sequenceCounter.incrementAndGet();
        String sequenceNumber = String.format("%021d", seq);

        JsonNode sourceItem = newItem != null ? newItem : oldItem;
        ObjectNode keys = buildKeys(sourceItem, table);

        String viewType = sd.getStreamViewType();
        JsonNode newImage = buildImage(newItem, viewType, true);
        JsonNode oldImage = buildImage(oldItem, viewType, false);

        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventId(UUID.randomUUID().toString());
        record.setEventVersion("1.1");
        record.setEventName(eventName);
        record.setEventSource("aws:dynamodb");
        record.setAwsRegion(region);
        record.setSequenceNumber(sequenceNumber);
        record.setApproximateCreationDateTime(Instant.now().getEpochSecond());
        record.setKeys(keys);
        record.setNewImage(newImage);
        record.setOldImage(oldImage);
        record.setStreamViewType(viewType);

        ConcurrentLinkedDeque<DynamoDbStreamRecord> deque = records.get(sd.getStreamArn());
        if (deque != null) {
            deque.addLast(record);
            while (deque.size() > MAX_RECORDS) {
                deque.pollFirst();
            }
        }
    }

    public void forwardToKinesisDestinations(String eventName, JsonNode oldItem, JsonNode newItem,
                                              TableDefinition table, String region) {
        if (kinesisService == null) return;

        List<KinesisStreamingDestination> destinations = table.getKinesisStreamingDestinations();
        if (destinations == null || destinations.isEmpty()) return;

        Instant now = Instant.now();
        JsonNode sourceItem = newItem != null ? newItem : oldItem;
        ObjectNode keys = buildKeys(sourceItem, table);

        for (KinesisStreamingDestination dest : destinations) {
            if (!"ACTIVE".equals(dest.getDestinationStatus())) continue;

            try {
                ObjectNode payload = buildKinesisPayload(eventName, keys, newItem, oldItem,
                        table.getTableName(), region, now);
                byte[] data = objectMapper.writeValueAsBytes(payload);

                String partitionKey = extractPartitionKey(keys, table);
                String streamName = extractStreamName(dest.getStreamArn());

                kinesisService.putRecord(streamName, data, partitionKey, region);
                LOG.debugv("Forwarded DynamoDB event to Kinesis stream {0}: {1} on {2}",
                        streamName, eventName, table.getTableName());
            } catch (Exception e) {
                LOG.warnv("Failed to forward DynamoDB event to Kinesis destination {0}: {1}",
                        dest.getStreamArn(), e.getMessage());
            }
        }
    }

    private ObjectNode buildKinesisPayload(String eventName, JsonNode keys,
                                            JsonNode newImage, JsonNode oldImage,
                                            String tableName, String region, Instant timestamp) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("awsRegion", region);
        payload.put("eventID", UUID.randomUUID().toString());
        payload.put("eventName", eventName);
        payload.putNull("userIdentity");
        payload.put("recordFormat", "application/json");
        payload.put("tableName", tableName);
        payload.put("eventSource", "aws:dynamodb");

        ObjectNode dynamodb = objectMapper.createObjectNode();
        dynamodb.put("ApproximateCreationDateTime", timestamp.toEpochMilli());
        if (keys != null) {
            dynamodb.set("Keys", keys);
        }
        if (newImage != null) {
            dynamodb.set("NewImage", newImage);
        }
        if (oldImage != null) {
            dynamodb.set("OldImage", oldImage);
        }
        dynamodb.put("SizeBytes", 0);
        dynamodb.put("ApproximateCreationDateTimePrecision", "MILLISECOND");
        payload.set("dynamodb", dynamodb);

        return payload;
    }

    private String extractPartitionKey(JsonNode keys, TableDefinition table) {
        if (keys == null || keys.isEmpty()) return "default";
        String pkName = table.getPartitionKeyName();
        JsonNode pkValue = keys.get(pkName);
        if (pkValue == null) return "default";
        if (pkValue.has("S")) return pkValue.get("S").asText();
        if (pkValue.has("N")) return pkValue.get("N").asText();
        if (pkValue.has("B")) return pkValue.get("B").asText();
        return pkValue.toString();
    }

    private String extractStreamName(String streamArn) {
        int idx = streamArn.lastIndexOf('/');
        if (idx >= 0) return streamArn.substring(idx + 1);
        return streamArn;
    }

    private ObjectNode buildKeys(JsonNode item, TableDefinition table) {
        ObjectNode keys = objectMapper.createObjectNode();
        if (item == null) {
            return keys;
        }
        for (KeySchemaElement ks : table.getKeySchema()) {
            String attrName = ks.getAttributeName();
            if (item.has(attrName)) {
                keys.set(attrName, item.get(attrName));
            }
        }
        return keys;
    }

    private JsonNode buildImage(JsonNode item, String viewType, boolean isNewImage) {
        if (item == null) {
            return null;
        }
        return switch (viewType) {
            case "KEYS_ONLY" -> null;
            case "NEW_IMAGE" -> isNewImage ? item : null;
            case "OLD_IMAGE" -> !isNewImage ? item : null;
            case "NEW_AND_OLD_IMAGES" -> item;
            default -> null;
        };
    }

    public List<StreamDescription> listStreams(String tableNameFilter, String region) {
        List<StreamDescription> result = new ArrayList<>();
        for (StreamDescription sd : streams.values()) {
            if (tableNameFilter != null && !tableNameFilter.equals(sd.getTableName())) {
                continue;
            }
            if (region != null && !sd.getStreamArn().contains(":" + region + ":")) {
                continue;
            }
            result.add(sd);
        }
        return result;
    }

    public StreamDescription describeStream(String streamArn) {
        for (StreamDescription sd : streams.values()) {
            if (streamArn.equals(sd.getStreamArn())) {
                return sd;
            }
        }
        throw new AwsException("ResourceNotFoundException",
                "Stream not found: " + streamArn, 400);
    }

    public String getShardIterator(String streamArn, String shardId,
                                   String iteratorType, String sequenceNumber) {
        StreamDescription sd = describeStream(streamArn);
        if (!"ENABLED".equals(sd.getStreamStatus()) && !"DISABLED".equals(sd.getStreamStatus())) {
            throw new AwsException("ResourceNotFoundException",
                    "Stream not found: " + streamArn, 400);
        }

        ConcurrentLinkedDeque<DynamoDbStreamRecord> deque = records.get(streamArn);
        List<DynamoDbStreamRecord> snapshot = deque != null ? new ArrayList<>(deque) : List.of();

        int position = switch (iteratorType) {
            case "TRIM_HORIZON" -> 0;
            case "LATEST" -> snapshot.size();
            case "AT_SEQUENCE_NUMBER" -> findSequencePosition(snapshot, sequenceNumber, false);
            case "AFTER_SEQUENCE_NUMBER" -> findSequencePosition(snapshot, sequenceNumber, true);
            default -> throw new AwsException("ValidationException",
                    "Unknown iterator type: " + iteratorType, 400);
        };

        return encodeIterator(streamArn, position);
    }

    private int findSequencePosition(List<DynamoDbStreamRecord> records, String targetSeq, boolean after) {
        for (int i = 0; i < records.size(); i++) {
            String seq = records.get(i).getSequenceNumber();
            int cmp = seq.compareTo(targetSeq);
            if (after ? cmp > 0 : cmp >= 0) {
                return i;
            }
        }
        return records.size();
    }

    public record GetRecordsResult(List<DynamoDbStreamRecord> records, String nextShardIterator) {}

    public GetRecordsResult getRecords(String shardIterator, Integer limit) {
        String[] parts = decodeIterator(shardIterator);
        String streamArn = parts[0];
        int position = Integer.parseInt(parts[1]);

        ConcurrentLinkedDeque<DynamoDbStreamRecord> deque = records.get(streamArn);
        List<DynamoDbStreamRecord> snapshot = deque != null ? new ArrayList<>(deque) : List.of();

        int effectiveLimit = limit != null ? limit : 100;
        int end = Math.min(position + effectiveLimit, snapshot.size());
        List<DynamoDbStreamRecord> page = snapshot.subList(position, end);

        String nextIterator = encodeIterator(streamArn, end);
        return new GetRecordsResult(new ArrayList<>(page), nextIterator);
    }

    private String encodeIterator(String streamArn, int position) {
        String raw = streamArn + "|" + position;
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }

    private String[] decodeIterator(String iterator) {
        try {
            String raw = new String(Base64.getDecoder().decode(iterator));
            int lastPipe = raw.lastIndexOf('|');
            if (lastPipe < 0) {
                throw new AwsException("ValidationException", "Invalid shard iterator", 400);
            }
            return new String[]{raw.substring(0, lastPipe), raw.substring(lastPipe + 1)};
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid shard iterator", 400);
        }
    }

    private String streamKey(String region, String tableName) {
        return region + "::" + tableName;
    }
}
