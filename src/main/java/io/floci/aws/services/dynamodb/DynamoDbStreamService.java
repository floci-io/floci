package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.dynamodb.model.DynamoDbStreamRecord;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.StreamDescription;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class DynamoDbStreamService {

    private static final Logger LOG = Logger.getLogger(DynamoDbStreamService.class);

    /** The first shard of every stream, which a stream with one shard that never rolls over keeps for good. */
    static final String SHARD_ID = shardId(1, 0);
    /** The most records a stream keeps across its shards unless configured otherwise; the oldest goes first. */
    static final int MAX_RECORDS = 1000;
    /** How long a shard iterator can be used when iterator expiry is on, as on AWS. */
    static final Duration ITERATOR_LIFETIME = Duration.ofMinutes(15);
    /** How long records, and shards once closed, are kept when retention is on, as on AWS. */
    static final Duration RETENTION = Duration.ofHours(24);
    /** The most shards one DescribeStream page lists, AWS's upper limit. */
    static final int MAX_SHARDS_PER_PAGE = 100;
    private static final String ZERO_SEQUENCE_NUMBER = "000000000000000000000";

    private static final DateTimeFormatter STREAM_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * How the native engine shapes its streams: the open shards each has, how long a shard stays open before a child
     * takes its keys (null for good), whether iterators expire and records age out as on AWS, and how many records a
     * stream keeps.
     */
    record Settings(int shardCount, Duration shardRollover, boolean iteratorExpiry, boolean retention,
                    int maxRecords) {

        static final Settings DEFAULTS = new Settings(1, null, false, false);

        Settings {
            if (shardCount < 1) {
                throw new IllegalArgumentException("A DynamoDB stream needs at least one shard, not " + shardCount);
            }
            if (shardRollover != null && (shardRollover.isZero() || shardRollover.isNegative())) {
                shardRollover = null;
            }
            if (maxRecords < 1) {
                throw new IllegalArgumentException("A DynamoDB stream keeps at least one record, not " + maxRecords);
            }
        }

        Settings(int shardCount, Duration shardRollover, boolean iteratorExpiry, boolean retention) {
            this(shardCount, shardRollover, iteratorExpiry, retention, MAX_RECORDS);
        }

        static Settings from(EmulatorConfig.DynamoDbServiceConfig config) {
            long rollover = config.streamShardRolloverSeconds();
            return new Settings(config.streamShardCount(), rollover > 0 ? Duration.ofSeconds(rollover) : null,
                    config.streamIteratorExpiryEnabled(), config.streamRetentionEnabled(), config.streamMaxRecords());
        }
    }

    /** A shard as DescribeStream lists it. Its ending sequence number is null while it is open. */
    record ShardDescription(String shardId, String parentShardId, String startingSequenceNumber,
                            String endingSequenceNumber) {}

    record ShardsPage(List<ShardDescription> shards, String lastEvaluatedShardId) {}

    record GetRecordsResult(List<DynamoDbStreamRecord> records, String nextShardIterator) {}

    /** Where an iterator reads from: at {@code sequence} when inclusive, else just after it. */
    private record Cursor(String sequence, boolean inclusive) {}

    /** What a shard iterator carries: the shard it reads, where, and when it was returned. */
    private record IteratorToken(String streamArn, String shardId, Cursor cursor, Instant issuedAt) {}

    /** Keyed by table ARN, which is unique per partition, region, account and table. */
    private final ConcurrentHashMap<String, StreamDescription> streams = new ConcurrentHashMap<>();
    /** Keyed by stream ARN. */
    private final ConcurrentHashMap<String, StreamState> states = new ConcurrentHashMap<>();
    /** Per stream (the stream ARN embeds account + region + table) so accounts never share a counter. */
    private final ConcurrentHashMap<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final Settings settings;
    private final Clock clock;

    @Inject
    public DynamoDbStreamService(ObjectMapper objectMapper, StorageFactory storageFactory, EmulatorConfig config) {
        this(objectMapper, storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}),
                Settings.from(config.services().dynamodb()), Clock.systemUTC());
    }

    /** Package-private constructor for testing. */
    DynamoDbStreamService(ObjectMapper objectMapper, StorageBackend<String, TableDefinition> tableStore) {
        this(objectMapper, tableStore, Settings.DEFAULTS, Clock.systemUTC());
    }

    /** Package-private constructor for testing streams shaped by {@code settings}, on {@code clock}. */
    DynamoDbStreamService(ObjectMapper objectMapper, StorageBackend<String, TableDefinition> tableStore,
                          Settings settings, Clock clock) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.clock = clock;
        loadPersistedStreams(tableStore);
    }

    private void loadPersistedStreams(StorageBackend<String, TableDefinition> tableStore) {
        if (tableStore == null) {
            return;
        }
        // No request scope at startup, so keys() would only see the default account's tables.
        if (tableStore instanceof AccountAwareStorageBackend<TableDefinition> aware) {
            aware.scanAllAccountsRaw().values().forEach(this::loadPersistedStream);
            return;
        }
        for (String tableKey : tableStore.keys()) {
            tableStore.get(tableKey).ifPresent(this::loadPersistedStream);
        }
    }

    private void loadPersistedStream(TableDefinition table) {
        if (table.isStreamEnabled()) {
            enableStream(table.getTableName(), table.getTableArn(), table.getStreamViewType(),
                    AwsArnUtils.parse(table.getTableArn()).region(), table.getStreamArn());
        }
    }

    public StreamDescription enableStream(String tableName, String tableArn, String viewType, String region) {
        return enableStream(tableName, tableArn, viewType, region, null);
    }

    public StreamDescription enableStream(String tableName, String tableArn, String viewType, String region, String streamArnInput) {
        StreamDescription existing = streams.get(tableArn);
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
        Instant now = clock.instant();
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

        StreamState state = new StreamState(counter(streamArn));
        synchronized (state) {
            for (int slot = 0; slot < settings.shardCount(); slot++) {
                state.open(1, slot, null, now);
            }
        }
        // Shards first, so a write that already sees the stream has somewhere to land.
        states.put(streamArn, state);
        streams.put(tableArn, sd);
        LOG.infov("Enabled stream for table {0} in region {1}: {2} ({3} shards)",
                tableName, region, streamArn, settings.shardCount());
        return sd;
    }

    /** Disabling a stream closes its open shards, as on AWS; what they hold stays readable. */
    public void disableStream(String tableArn) {
        StreamDescription sd = streams.get(tableArn);
        if (sd != null) {
            sd.setStreamStatus("DISABLED");
            StreamState state = states.get(sd.getStreamArn());
            if (state != null) {
                synchronized (state) {
                    Instant now = clock.instant();
                    state.shards.stream().filter(Shard::open).toList().forEach(shard -> state.close(shard, now));
                }
            }
            LOG.infov("Disabled stream for table {0}", tableArn);
        }
    }

    public void deleteStream(String tableArn) {
        StreamDescription sd = streams.remove(tableArn);
        if (sd != null) {
            states.remove(sd.getStreamArn());
            sequenceCounters.remove(sd.getStreamArn());
            LOG.infov("Deleted stream for table {0}", tableArn);
        }
    }

    /** Emulator reset: drops every stream and its records. Sequence numbers stay monotonic. */
    public void clear() {
        streams.clear();
        states.clear();
    }

    public void captureEvent(String eventName, JsonNode oldItem, JsonNode newItem,
                             TableDefinition table, String region) {
        captureEvent(eventName, oldItem, newItem, table, region, false);
    }

    /** {@code ttlDeletion} marks a removal made by the time to live sweep rather than by a caller. */
    public void captureEvent(String eventName, JsonNode oldItem, JsonNode newItem,
                             TableDefinition table, String region, boolean ttlDeletion) {
        StreamDescription sd = streams.get(table.getTableArn());
        if (sd == null || !"ENABLED".equals(sd.getStreamStatus())) {
            return;
        }

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
        record.setApproximateCreationDateTime(clock.instant().getEpochSecond());
        record.setKeys(keys);
        record.setNewImage(newImage);
        record.setOldImage(oldImage);
        record.setStreamViewType(viewType);
        record.setTtlDeletion(ttlDeletion);

        StreamState state = states.get(sd.getStreamArn());
        if (state == null) {
            return;
        }
        int slot = slotOf(keys, table);
        synchronized (state) {
            maintain(state, clock.instant());
            Shard shard = state.openShard(slot);
            if (shard == null) {
                // Disabled since the status check above, which closed every shard.
                return;
            }
            record.setSequenceNumber(sequence(state.counter.incrementAndGet()));
            shard.records.addLast(record);
            shard.lastSequence = record.getSequenceNumber();
            state.retained++;
            while (state.retained > settings.maxRecords()) {
                state.trimOldest();
            }
        }
    }

    /**
     * The slot whose open shard takes an item's changes, from its partition key, so one item's changes always land
     * on one shard and stay in order.
     */
    private int slotOf(ObjectNode keys, TableDefinition table) {
        if (settings.shardCount() == 1) {
            return 0;
        }
        for (KeySchemaElement element : table.getKeySchema()) {
            if ("HASH".equals(element.getKeyType()) && keys.has(element.getAttributeName())) {
                return Math.floorMod(keys.get(element.getAttributeName()).toString().hashCode(),
                        settings.shardCount());
            }
        }
        return 0;
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

    public List<StreamDescription> listStreams(String tableNameFilter, String accountId, String region) {
        List<StreamDescription> result = new ArrayList<>();
        for (StreamDescription sd : streams.values()) {
            if (tableNameFilter != null && !tableNameFilter.equals(sd.getTableName())) {
                continue;
            }
            AwsArnUtils.Arn streamArn = AwsArnUtils.parse(sd.getStreamArn());
            if (!accountId.equals(streamArn.accountId()) || !region.equals(streamArn.region())) {
                continue;
            }
            result.add(sd);
        }
        return result;
    }

    StreamDescription describeStream(String streamArn) {
        for (StreamDescription sd : streams.values()) {
            if (streamArn.equals(sd.getStreamArn())) {
                return sd;
            }
        }
        throw new AwsException("ResourceNotFoundException",
                "Stream not found: " + streamArn, 400);
    }

    /** The stream's shards after {@code exclusiveStartShardId}, in shard ID order, a page of at most {@code limit}. */
    ShardsPage describeShards(String streamArn, String exclusiveStartShardId, Integer limit) {
        describeStream(streamArn);
        StreamState state = states.get(streamArn);
        if (state == null) {
            return new ShardsPage(List.of(), null);
        }
        requirePositiveLimit(limit);
        int pageSize = limit == null ? MAX_SHARDS_PER_PAGE : Math.min(limit, MAX_SHARDS_PER_PAGE);
        List<ShardDescription> page = new ArrayList<>();
        boolean more = false;
        synchronized (state) {
            maintain(state, clock.instant());
            for (Shard shard : state.shards) {
                if (exclusiveStartShardId != null && shard.id.compareTo(exclusiveStartShardId) <= 0) {
                    continue;
                }
                if (page.size() == pageSize) {
                    more = true;
                    break;
                }
                page.add(new ShardDescription(shard.id, shard.parentId, shard.startingSequenceNumber,
                        shard.endingSequenceNumber));
            }
        }
        return new ShardsPage(List.copyOf(page), more ? page.get(page.size() - 1).shardId() : null);
    }

    String getShardIterator(String streamArn, String shardId,
                            String iteratorType, String sequenceNumber) {
        StreamDescription sd = describeStream(streamArn);
        if (!"ENABLED".equals(sd.getStreamStatus()) && !"DISABLED".equals(sd.getStreamStatus())) {
            throw new AwsException("ResourceNotFoundException",
                    "Stream not found: " + streamArn, 400);
        }
        boolean bySequence = "AT_SEQUENCE_NUMBER".equals(iteratorType) || "AFTER_SEQUENCE_NUMBER".equals(iteratorType);
        if (!bySequence && !"TRIM_HORIZON".equals(iteratorType) && !"LATEST".equals(iteratorType)) {
            throw new AwsException("ValidationException", "Unknown iterator type: " + iteratorType, 400);
        }
        if (bySequence && (sequenceNumber == null || sequenceNumber.isBlank())) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: SequenceNumber cannot be null", 400);
        }

        StreamState state = states.get(streamArn);
        if (state == null) {
            throw shardNotFound();
        }
        Instant now = clock.instant();
        Cursor cursor;
        synchronized (state) {
            maintain(state, now);
            Shard shard = state.find(shardId);
            if (shard == null) {
                throw shardNotFound();
            }
            cursor = switch (iteratorType) {
                case "TRIM_HORIZON" -> shard.records.isEmpty()
                        ? shard.afterTrimmed() : new Cursor(shard.records.getFirst().getSequenceNumber(), true);
                case "LATEST" -> shard.records.isEmpty()
                        ? shard.afterTrimmed() : new Cursor(shard.records.getLast().getSequenceNumber(), false);
                default -> {
                    if (settings.retention()) {
                        requireHeld(state, shard, sequenceNumber);
                    }
                    yield new Cursor(sequenceNumber, "AT_SEQUENCE_NUMBER".equals(iteratorType));
                }
            };
        }
        return encodeIterator(new IteratorToken(streamArn, shardId, cursor, now));
    }

    /**
     * Refuses a sequence number the shard never held, as AWS does with ValidationException, and one it held but has
     * trimmed, with TrimmedDataAccessException. A sequence number at or below the shard's trim point cannot be told
     * apart from another shard's, so it is taken as trimmed. A shard's starting sequence number is no exception: the
     * first shards of a stream all start at the same one, and only the shard whose record it is holds it.
     */
    private static void requireHeld(StreamState state, Shard shard, String sequenceNumber) {
        BigInteger requested;
        try {
            requested = new BigInteger(sequenceNumber);
        } catch (NumberFormatException e) {
            throw invalidSequenceNumber();
        }
        if (requested.compareTo(new BigInteger(shard.startingSequenceNumber)) < 0
                || requested.compareTo(BigInteger.valueOf(state.counter.get())) > 0
                || (!shard.open() && requested.compareTo(new BigInteger(shard.endingSequenceNumber)) > 0)) {
            throw invalidSequenceNumber();
        }
        if (shard.trimmedThrough != null && requested.compareTo(new BigInteger(shard.trimmedThrough)) <= 0) {
            throw trimmed();
        }
        for (DynamoDbStreamRecord record : shard.records) {
            if (requested.compareTo(new BigInteger(record.getSequenceNumber())) == 0) {
                return;
            }
        }
        throw invalidSequenceNumber();
    }

    GetRecordsResult getRecords(String shardIterator, Integer limit) {
        requirePositiveLimit(limit);
        IteratorToken iterator = decodeIterator(shardIterator);
        Instant now = clock.instant();
        if (settings.iteratorExpiry() && now.isAfter(iterator.issuedAt().plus(ITERATOR_LIFETIME))) {
            throw new AwsException("ExpiredIteratorException", "The provided iterator exceeds the maximum age allowed.",
                    400);
        }
        StreamState state = states.get(iterator.streamArn());
        if (state == null) {
            return new GetRecordsResult(List.of(), encodeIterator(
                    new IteratorToken(iterator.streamArn(), iterator.shardId(), iterator.cursor(), now)));
        }
        Cursor cursor = iterator.cursor();
        List<DynamoDbStreamRecord> page;
        Cursor next;
        boolean ended;
        synchronized (state) {
            maintain(state, now);
            Shard shard = state.find(iterator.shardId());
            if (shard == null) {
                throw shardNotFound();
            }
            if (shard.trimmedThrough != null) {
                int trimmedAgainstCursor = shard.trimmedThrough.compareTo(cursor.sequence());
                if (cursor.inclusive() ? trimmedAgainstCursor >= 0 : trimmedAgainstCursor > 0) {
                    throw trimmed();
                }
            }
            List<DynamoDbStreamRecord> held = new ArrayList<>(shard.records);
            int position = held.size();
            for (int i = 0; i < held.size(); i++) {
                int againstCursor = held.get(i).getSequenceNumber().compareTo(cursor.sequence());
                if (cursor.inclusive() ? againstCursor >= 0 : againstCursor > 0) {
                    position = i;
                    break;
                }
            }
            int effectiveLimit = limit != null ? limit : 100;
            page = new ArrayList<>(held.subList(position, Math.min(position + effectiveLimit, held.size())));
            next = page.isEmpty() ? cursor : new Cursor(page.getLast().getSequenceNumber(), false);
            // A closed shard read to its end has no next iterator, which is how a reader learns it is done.
            ended = !shard.open() && position >= held.size();
        }
        return new GetRecordsResult(page, ended ? null
                : encodeIterator(new IteratorToken(iterator.streamArn(), iterator.shardId(), next, now)));
    }

    /**
     * Applies the passage of time to a stream: with retention on, records past 24 hours are trimmed and shards closed
     * longer ago are dropped; with a rollover, each open shard that has been open that long closes and a child takes
     * its keys. Done on every use of the stream rather than on a timer, so it costs nothing while idle. A closed shard
     * that holds no records is dropped once the shard that replaced it has closed too: nothing is left to read in it,
     * and a reader has had a whole rollover to reach its end. That keeps an idle stream at two generations of shards,
     * and a busy one at the shards that still hold records.
     */
    private void maintain(StreamState state, Instant now) {
        if (settings.retention()) {
            long oldestKept = now.minus(RETENTION).getEpochSecond();
            for (Shard shard : state.shards) {
                while (!shard.records.isEmpty() && shard.records.getFirst().getApproximateCreationDateTime() < oldestKept) {
                    state.trimFirst(shard);
                }
            }
            Instant closedBefore = now.minus(RETENTION);
            state.shards.removeIf(shard -> {
                boolean expired = !shard.open() && shard.closedAt.isBefore(closedBefore);
                if (expired) {
                    state.retained -= shard.records.size();
                }
                return expired;
            });
        }
        if (settings.shardRollover() != null) {
            for (int i = 0, existing = state.shards.size(); i < existing; i++) {
                Shard shard = state.shards.get(i);
                if (shard.open() && !now.isBefore(shard.openedAt.plus(settings.shardRollover()))) {
                    state.close(shard, now);
                    state.open(shard.generation + 1, shard.slot, shard.id, now);
                }
            }
        }
        state.shards.removeIf(shard -> !shard.open() && shard.records.isEmpty() && shard.replacementClosed);
    }

    private String encodeIterator(IteratorToken iterator) {
        String raw = iterator.streamArn() + "|" + iterator.shardId() + "|" + iterator.cursor().sequence() + "|"
                + iterator.cursor().inclusive() + "|" + iterator.issuedAt().toEpochMilli();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** The stream an iterator reads, rejecting a malformed iterator as GetRecords does. */
    String streamArnOf(String shardIterator) {
        return decodeIterator(shardIterator).streamArn();
    }

    /** Fields are read from the right, so the stream ARN keeps whatever it holds. */
    private IteratorToken decodeIterator(String token) {
        String raw;
        try {
            raw = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalidIterator();
        }
        String[] fields = new String[5];
        int end = raw.length();
        for (int field = 4; field > 0; field--) {
            int pipe = raw.lastIndexOf('|', end - 1);
            if (pipe < 0) {
                throw invalidIterator();
            }
            fields[field] = raw.substring(pipe + 1, end);
            end = pipe;
        }
        fields[0] = raw.substring(0, end);
        if (fields[0].isEmpty() || fields[1].isEmpty() || fields[2].isEmpty()
                || !("true".equals(fields[3]) || "false".equals(fields[3]))) {
            throw invalidIterator();
        }
        try {
            return new IteratorToken(fields[0], fields[1], new Cursor(fields[2], Boolean.parseBoolean(fields[3])),
                    Instant.ofEpochMilli(Long.parseLong(fields[4])));
        } catch (NumberFormatException e) {
            throw invalidIterator();
        }
    }

    private AtomicLong counter(String streamArn) {
        return sequenceCounters.computeIfAbsent(streamArn, ignored -> new AtomicLong());
    }

    /** Generation 1, slot 0 is {@code shardId-0000000001-00000000001}; IDs sort in generation, then slot, order. */
    private static String shardId(int generation, int slot) {
        return String.format("shardId-%010d-%011d", generation, slot + 1);
    }

    private static String sequence(long value) {
        return String.format("%021d", value);
    }

    /** {@code Limit} of DescribeStream and GetRecords is at least 1; AWS refuses less before reading anything. */
    private static void requirePositiveLimit(Integer limit) {
        if (limit != null && limit < 1) {
            throw new AwsException("ValidationException", "1 validation error detected: Value at 'limit' failed to "
                    + "satisfy constraint: Member must have value greater than or equal to 1", 400);
        }
    }

    private static AwsException shardNotFound() {
        return new AwsException("ResourceNotFoundException", "Requested resource not found: Shard does not exist", 400);
    }

    private static AwsException trimmed() {
        return new AwsException("TrimmedDataAccessException", "The data you are trying to access has been trimmed.", 400);
    }

    private static AwsException invalidSequenceNumber() {
        return new AwsException("ValidationException", "Invalid SequenceNumber for the shard", 400);
    }

    private static AwsException invalidIterator() {
        return new AwsException("ValidationException", "Invalid shard iterator", 400);
    }

    /** A stream's shards, oldest first, and the records they keep. Guarded by its own monitor. */
    private static final class StreamState {
        final List<Shard> shards = new ArrayList<>();
        final AtomicLong counter;
        /** Records kept across every shard, held to {@link Settings#maxRecords()}. */
        int retained;

        StreamState(AtomicLong counter) {
            this.counter = counter;
        }

        void open(int generation, int slot, String parentId, Instant now) {
            shards.add(new Shard(generation, slot, parentId, sequence(counter.get() + 1), now));
        }

        Shard find(String shardId) {
            for (Shard shard : shards) {
                if (shard.id.equals(shardId)) {
                    return shard;
                }
            }
            return null;
        }

        /** Closes {@code shard} and tells its parent, which no reader needs once it is empty and this has closed. */
        void close(Shard shard, Instant now) {
            shard.close(now);
            Shard parent = shard.parentId == null ? null : find(shard.parentId);
            if (parent != null) {
                parent.replacementClosed = true;
            }
        }

        Shard openShard(int slot) {
            for (Shard shard : shards) {
                if (shard.slot == slot && shard.open()) {
                    return shard;
                }
            }
            return null;
        }

        /** Trims the stream's oldest record, whichever shard holds it. */
        void trimOldest() {
            Shard oldest = null;
            for (Shard shard : shards) {
                if (!shard.records.isEmpty() && (oldest == null || shard.records.getFirst().getSequenceNumber()
                        .compareTo(oldest.records.getFirst().getSequenceNumber()) < 0)) {
                    oldest = shard;
                }
            }
            if (oldest != null) {
                trimFirst(oldest);
            }
        }

        void trimFirst(Shard shard) {
            shard.trimmedThrough = shard.records.removeFirst().getSequenceNumber();
            retained--;
        }
    }

    private static final class Shard {
        final String id;
        final String parentId;
        final int generation;
        final int slot;
        final String startingSequenceNumber;
        final Instant openedAt;
        final ArrayDeque<DynamoDbStreamRecord> records = new ArrayDeque<>();
        /** The sequence number of the newest record ever added, or null. */
        String lastSequence;
        /** The sequence number of the newest record trimmed away, or null: a cursor at or before it lost records. */
        String trimmedThrough;
        String endingSequenceNumber;
        Instant closedAt;
        /** Whether the child shard that took this one's keys has closed in turn. */
        boolean replacementClosed;

        Shard(int generation, int slot, String parentId, String startingSequenceNumber, Instant openedAt) {
            this.id = shardId(generation, slot);
            this.parentId = parentId;
            this.generation = generation;
            this.slot = slot;
            this.startingSequenceNumber = startingSequenceNumber;
            this.openedAt = openedAt;
        }

        boolean open() {
            return closedAt == null;
        }

        void close(Instant now) {
            closedAt = now;
            endingSequenceNumber = lastSequence != null ? lastSequence : startingSequenceNumber;
        }

        /** Where TRIM_HORIZON and LATEST read an empty shard: after anything it trimmed, so no trim is reported. */
        Cursor afterTrimmed() {
            return new Cursor(trimmedThrough != null ? trimmedThrough : ZERO_SEQUENCE_NUMBER, false);
        }
    }
}
