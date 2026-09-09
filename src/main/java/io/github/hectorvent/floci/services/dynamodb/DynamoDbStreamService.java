package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
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
    private static final String ZERO_SEQUENCE_NUMBER = "000000000000000000000";

    private static final DateTimeFormatter STREAM_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ConcurrentHashMap<String, StreamDescription> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<DynamoDbStreamRecord>> records =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> streamRecordCounts = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private final ObjectMapper objectMapper;

    @Inject
    public DynamoDbStreamService(ObjectMapper objectMapper, StorageFactory storageFactory) {
        this(objectMapper, storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}));
    }

    /** Package-private constructor for testing. */
    DynamoDbStreamService(ObjectMapper objectMapper, StorageBackend<String, TableDefinition> tableStore) {
        this.objectMapper = objectMapper;
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
            // Re-enabling a live stream with a different view type retargets it. The records this
            // stream emits are built from the description's view type, so leaving it untouched
            // would keep producing the old shape while the table reports the requested one.
            if (viewType != null && !viewType.equals(existing.getStreamViewType())) {
                existing.setStreamViewType(viewType);
                LOG.infov("Stream view type for table {0} in region {1} is now {2}",
                        tableName, region, viewType);
            }
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
        streamRecordCounts.put(streamArn, new AtomicLong());
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
            streamRecordCounts.remove(sd.getStreamArn());
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
            streamRecordCounts.computeIfAbsent(sd.getStreamArn(), ignored -> new AtomicLong())
                    .incrementAndGet();
            deque.addLast(record);
            while (deque.size() > MAX_RECORDS) {
                deque.pollFirst();
            }
        }
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

        String cursorSequence = switch (iteratorType) {
            case "TRIM_HORIZON" -> snapshot.isEmpty() ? zeroSequence() : snapshot.get(0).getSequenceNumber();
            case "LATEST" -> snapshot.isEmpty() ? zeroSequence() : snapshot.get(snapshot.size() - 1).getSequenceNumber();
            case "AT_SEQUENCE_NUMBER", "AFTER_SEQUENCE_NUMBER" -> {
                if (sequenceNumber == null || sequenceNumber.isBlank()) {
                    throw new AwsException("ValidationException",
                            "Sequence number is required for this iterator type", 400);
                }
                yield sequenceNumber;
            }
            default -> throw new AwsException("ValidationException",
                    "Unknown iterator type: " + iteratorType, 400);
        };

        boolean inclusive = "TRIM_HORIZON".equals(iteratorType) || "AT_SEQUENCE_NUMBER".equals(iteratorType);
        long recordCount = zeroSequence().equals(cursorSequence)
                ? streamRecordCounts.getOrDefault(streamArn, new AtomicLong()).get()
                : -1;
        return encodeIterator(streamArn, cursorSequence, inclusive, recordCount);
    }

    private int findSequencePosition(List<DynamoDbStreamRecord> records, String targetSeq, boolean inclusive) {
        for (int i = 0; i < records.size(); i++) {
            String seq = records.get(i).getSequenceNumber();
            int cmp = seq.compareTo(targetSeq);
            if (inclusive ? cmp >= 0 : cmp > 0) {
                return i;
            }
        }
        return records.size();
    }

    public record GetRecordsResult(List<DynamoDbStreamRecord> records, String nextShardIterator) {}

    public GetRecordsResult getRecords(String shardIterator, Integer limit) {
        String[] parts = decodeIterator(shardIterator);
        String streamArn = parts[0];
        String cursorSequence = parts[1];
        boolean inclusive = Boolean.parseBoolean(parts[2]);
        long cursorRecordCount = parseRecordCount(parts[3]);

        ConcurrentLinkedDeque<DynamoDbStreamRecord> deque = records.get(streamArn);
        List<DynamoDbStreamRecord> snapshot = deque != null ? new ArrayList<>(deque) : List.of();
        int position = findSequencePosition(snapshot, cursorSequence, inclusive);
        long currentRecordCount = streamRecordCounts.getOrDefault(streamArn, new AtomicLong()).get();
        if (zeroSequence().equals(cursorSequence) && cursorRecordCount >= 0
                && currentRecordCount - cursorRecordCount > MAX_RECORDS) {
            throw new AwsException("TrimmedDataAccessException",
                    "The requested sequence number has been trimmed", 400);
        }
        if (!snapshot.isEmpty() && !zeroSequence().equals(cursorSequence)
                && cursorSequence.compareTo(snapshot.get(0).getSequenceNumber()) < 0) {
            throw new AwsException("TrimmedDataAccessException",
                    "The requested sequence number has been trimmed", 400);
        }

        int effectiveLimit = limit != null ? limit : 100;
        int end = Math.min(position + effectiveLimit, snapshot.size());
        List<DynamoDbStreamRecord> page = snapshot.subList(position, end);

        String nextSequence = page.isEmpty()
                ? cursorSequence
                : page.get(page.size() - 1).getSequenceNumber();
        long nextRecordCount = zeroSequence().equals(nextSequence) ? cursorRecordCount : -1;
        String nextIterator = encodeIterator(streamArn, nextSequence, page.isEmpty() && inclusive, nextRecordCount);
        return new GetRecordsResult(new ArrayList<>(page), nextIterator);
    }

    private String encodeIterator(String streamArn, String sequenceNumber, boolean inclusive, long recordCount) {
        String raw = streamArn + "|" + sequenceNumber + "|" + inclusive + "|" + recordCount;
        return Base64.getEncoder().encodeToString(raw.getBytes());
    }

    private String[] decodeIterator(String iterator) {
        try {
            String raw = new String(Base64.getDecoder().decode(iterator));
            int lastPipe = raw.lastIndexOf('|');
            int sequencePipe = raw.lastIndexOf('|', lastPipe - 1);
            int inclusivePipe = raw.lastIndexOf('|', sequencePipe - 1);
            if (inclusivePipe < 0 || sequencePipe < 0 || lastPipe < 0 || lastPipe == raw.length() - 1) {
                throw new AwsException("ValidationException", "Invalid shard iterator", 400);
            }
            String inclusive = raw.substring(sequencePipe + 1, lastPipe);
            String recordCount = raw.substring(lastPipe + 1);
            if (!"true".equals(inclusive) && !"false".equals(inclusive)) {
                throw new AwsException("ValidationException", "Invalid shard iterator", 400);
            }
            try {
                Long.parseLong(recordCount);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "Invalid shard iterator", 400);
            }
            return new String[]{raw.substring(0, inclusivePipe), raw.substring(inclusivePipe + 1, sequencePipe),
                    inclusive, recordCount};
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid shard iterator", 400);
        }
    }

    private long parseRecordCount(String recordCount) {
        try {
            return Long.parseLong(recordCount);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "Invalid shard iterator", 400);
        }
    }

    private String zeroSequence() {
        return ZERO_SEQUENCE_NUMBER;
    }

    private String streamKey(String region, String tableName) {
        return region + "::" + tableName;
    }
}
