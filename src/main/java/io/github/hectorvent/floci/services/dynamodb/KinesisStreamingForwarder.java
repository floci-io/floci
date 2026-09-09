package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Forwards DynamoDB change events to Kinesis with a <strong>bounded best-effort, in-process retry</strong>
 * delivery contract (NOT durable at-least-once: the buffer is in memory and lost on restart).
 *
 * <p>The write path only ENQUEUES (it never performs Kinesis I/O or blocks: a slow/dead destination can
 * neither stall a DynamoDB write nor the TTL sweep). A per-destination single-flight drain, scheduled on a
 * small daemon pool, performs the actual {@code putRecord} outside the destination lock and:
 * <ul>
 *   <li>retries transient/unknown failures with capped exponential backoff (≤{@value #MAX_PROBES} probes),</li>
 *   <li>drops a record immediately on a deterministic terminal failure (invalid argument / validation /
 *       serialization) so a poison record cannot wedge the queue,</li>
 *   <li>gives up the whole per-destination episode after the probe budget (dropping the buffered records
 *       and marking the destination GAVE_UP until the next successful send),</li>
 *   <li>preserves per-destination FIFO across retries (stronger than AWS, which may reorder/duplicate),</li>
 *   <li>evicts the oldest buffered record on overflow (max {@value #MAX_BUFFERED} per destination).</li>
 * </ul>
 *
 * <p>The destination's {@code DestinationStatus} is intentionally NOT auto-demoted (AWS has no
 * delivery-failure status); delivery health is surfaced separately via {@link #forwardingStats()}.
 * Records dropped/retried are counted and their last error retained for observability.
 */
@ApplicationScoped
public class KinesisStreamingForwarder {

    private static final Logger LOG = Logger.getLogger(KinesisStreamingForwarder.class);

    static final int MAX_BUFFERED = 1000;
    static final int MAX_PROBES = 10;
    static final long INITIAL_BACKOFF_MS = 250;
    static final long MAX_BACKOFF_MS = 8000;

    /** Schedules drain runs; abstracted so tests can drive drains deterministically. */
    interface Scheduler {
        void schedule(Runnable task, long delayMillis);

        void shutdown();
    }

    enum Health { HEALTHY, RETRYING, GAVE_UP }

    record PendingRecord(String accountId, String region, String streamName, String tableName,
                         byte[] data, String partitionKey, Instant enqueuedAt) {
    }

    /** Per-destination delivery state. Every field is accessed only while holding {@code this} monitor. */
    static final class DestinationState {
        final String key;
        final String accountId;
        final String region;
        final String tableName;
        final String streamArn;
        final ArrayDeque<PendingRecord> queue = new ArrayDeque<>();
        long epoch;
        int consecutiveFailedProbes;
        boolean drainScheduled;
        long forwarded;
        long retried;
        long dropped;
        long overflowed;
        long discardedOnDisable;
        String lastErrorType;
        String lastErrorMessage;
        Health health = Health.HEALTHY;

        DestinationState(String key, String accountId, String region, String tableName, String streamArn) {
            this.key = key;
            this.accountId = accountId;
            this.region = region;
            this.tableName = tableName;
            this.streamArn = streamArn;
        }
    }

    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;
    private final Scheduler scheduler;
    private final ConcurrentHashMap<String, DestinationState> states = new ConcurrentHashMap<>();

    @Inject
    public KinesisStreamingForwarder(KinesisService kinesisService, ObjectMapper objectMapper) {
        this(kinesisService, objectMapper, defaultScheduler());
    }

    /** Test seam: inject a deterministic Scheduler so drains run on demand rather than on real timers. */
    KinesisStreamingForwarder(KinesisService kinesisService, ObjectMapper objectMapper, Scheduler scheduler) {
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    private static Scheduler defaultScheduler() {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cdc-forward-drain");
            t.setDaemon(true);
            return t;
        });
        return new Scheduler() {
            @Override
            public void schedule(Runnable task, long delayMillis) {
                exec.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            }

            @Override
            public void shutdown() {
                exec.shutdownNow();
            }
        };
    }

    // ──────────────────────────── Enqueue (write path: never blocks, never throws) ────────────────────────────

    public void forward(String eventName, JsonNode oldItem, JsonNode newItem,
                        TableDefinition table, String region, String ownerAccountId) {
        List<KinesisStreamingDestination> destinations = table.getKinesisStreamingDestinations();
        if (destinations == null || destinations.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        JsonNode sourceItem = newItem != null ? newItem : oldItem;
        ObjectNode keys = buildKeys(sourceItem, table);

        byte[] data;
        String partitionKey;
        try {
            ObjectNode payload = buildPayload(eventName, keys, newItem, oldItem, table.getTableName(), region, now);
            data = objectMapper.writeValueAsBytes(payload);
            partitionKey = extractPartitionKey(keys, table);
        } catch (Exception e) {
            // Serialization is a deterministic terminal failure: nothing can be forwarded for this event.
            for (KinesisStreamingDestination dest : destinations) {
                if (!"ACTIVE".equals(dest.getDestinationStatus())) {
                    continue;
                }
                DestinationState st = stateFor(ownerAccountId, region, table.getTableName(), dest.getStreamArn());
                synchronized (st) {
                    st.dropped++;
                    recordError(st, e);
                }
            }
            LOG.errorv(e, "Dropped DynamoDB CDC event for table {0}: failed to serialize payload", table.getTableName());
            return;
        }

        for (KinesisStreamingDestination dest : destinations) {
            if (!"ACTIVE".equals(dest.getDestinationStatus())) {
                continue;
            }
            String streamName = extractStreamName(dest.getStreamArn());
            DestinationState st = stateFor(ownerAccountId, region, table.getTableName(), dest.getStreamArn());
            PendingRecord rec = new PendingRecord(ownerAccountId, region, streamName, table.getTableName(),
                    data, partitionKey, now);
            synchronized (st) {
                if (st.queue.size() >= MAX_BUFFERED) {
                    st.queue.pollFirst(); // drop OLDEST
                    st.overflowed++;
                    st.dropped++;
                    LOG.warnv("CDC buffer full for stream {0} (>{1} records); dropped the oldest",
                            st.streamArn, MAX_BUFFERED);
                }
                st.queue.addLast(rec);
                if (!st.drainScheduled) {
                    scheduleDrain(st, 0);
                }
            }
        }
    }

    private DestinationState stateFor(String accountId, String region, String tableName, String streamArn) {
        String key = accountId + "|" + region + "|" + tableName + "|" + streamArn;
        return states.computeIfAbsent(key,
                k -> new DestinationState(k, accountId, region, tableName, streamArn));
    }

    // ──────────────────────────── Drain (per-destination single-flight; I/O outside the lock) ────────────────────────────

    private void drain(DestinationState st, long scheduledEpoch) {
        while (true) {
            PendingRecord head;
            synchronized (st) {
                if (scheduledEpoch != st.epoch) {
                    return; // this destination was disabled/deleted; a fresh episode (if any) owns the queue
                }
                if (st.queue.isEmpty()) {
                    st.drainScheduled = false;
                    if (st.health != Health.GAVE_UP) {
                        st.health = Health.HEALTHY;
                    }
                    return;
                }
                // Lease the head OUT of the queue for the duration of the send. A concurrent overflow
                // eviction can then only drop a still-buffered record, never the one being delivered,
                // and completion below reconciles exactly this leased record (no blind pollFirst).
                head = st.queue.pollFirst();
            }

            boolean success = false;
            boolean terminal = false;
            Throwable failure = null;
            try {
                if (head.accountId() != null) {
                    kinesisService.putRecordForAccount(head.accountId(), head.streamName(), head.data(),
                            head.partitionKey(), head.region());
                } else {
                    kinesisService.putRecord(head.streamName(), head.data(), head.partitionKey(), head.region());
                }
                success = true;
            } catch (AwsException e) {
                failure = e;
                terminal = isTerminal(e);
            } catch (Exception e) {
                failure = e; // unknown/operational failure -> retryable
            }

            synchronized (st) {
                if (scheduledEpoch != st.epoch) {
                    // Torn down mid-send: the leased record is discarded along with the rest of the buffer.
                    // The dispatched send may still complete, an inherent and harmless race for an in-memory
                    // emulator (teardown stops all FUTURE sends; it cannot recall one already in flight).
                    return;
                }
                if (success) {
                    st.forwarded++;
                    st.consecutiveFailedProbes = 0;
                    st.health = Health.HEALTHY;
                    continue;
                }
                recordError(st, failure);
                if (terminal) {
                    // The leased poison record is already out of the queue: drop it (do NOT wedge) and let
                    // the records behind it flow. Its probe budget dies with it: the next record earns a fresh one.
                    st.dropped++;
                    st.consecutiveFailedProbes = 0;
                    LOG.errorv(failure, "Dropped DynamoDB CDC record (terminal {0}) for stream {1}",
                            st.lastErrorType, st.streamArn);
                    continue;
                }
                // retryable: return the leased record to the head so per-destination FIFO is preserved.
                st.queue.addFirst(head);
                st.retried++;
                st.consecutiveFailedProbes++;
                st.health = Health.RETRYING;
                if (st.consecutiveFailedProbes >= MAX_PROBES) {
                    long gaveUp = st.queue.size();
                    st.dropped += gaveUp;
                    st.queue.clear();
                    st.health = Health.GAVE_UP;
                    st.drainScheduled = false;
                    // Reset the budget so a later event starts a fresh episode: a transient outage must
                    // not permanently wedge delivery: the next forward re-attempts and self-heals.
                    st.consecutiveFailedProbes = 0;
                    LOG.errorv("CDC delivery gave up for stream {0} after {1} attempts; dropped {2} buffered "
                                    + "record(s). Last error: {3}",
                            st.streamArn, MAX_PROBES, gaveUp, st.lastErrorMessage);
                    return;
                }
                scheduleDrain(st, backoff(st.consecutiveFailedProbes));
                return;
            }
        }
    }

    /**
     * Schedule a drain run. The caller MUST hold {@code st}'s monitor. A scheduler fault (e.g.
     * {@link java.util.concurrent.RejectedExecutionException} once the executor is shutting down) must
     * never escape into the DynamoDB write path and must not wedge the destination: on failure the
     * {@code drainScheduled} flag is cleared so a later event can schedule a fresh drain.
     */
    private void scheduleDrain(DestinationState st, long delayMillis) {
        st.drainScheduled = true;
        long ep = st.epoch;
        try {
            scheduler.schedule(() -> drain(st, ep), delayMillis);
        } catch (RuntimeException rex) {
            st.drainScheduled = false;
            LOG.warnv(rex, "CDC drain scheduling failed for stream {0}; buffered records await a later event",
                    st.streamArn);
        }
    }

    private static boolean isTerminal(AwsException e) {
        String code = e.getErrorCode();
        return "InvalidArgumentException".equals(code) || "ValidationException".equals(code);
    }

    private static long backoff(int probe) {
        long shifted = INITIAL_BACKOFF_MS * (1L << Math.min(probe - 1, 20));
        return Math.min(MAX_BACKOFF_MS, shifted);
    }

    private static void recordError(DestinationState st, Throwable failure) {
        st.lastErrorType = failure instanceof AwsException a && a.getErrorCode() != null
                ? a.getErrorCode() : failure.getClass().getSimpleName();
        st.lastErrorMessage = failure.getMessage();
    }

    // ──────────────────────────── Lifecycle ────────────────────────────

    /**
     * Disable of a specific destination: discard its buffer and stop any in-flight drain (epoch bump).
     * The state is RETAINED (not removed) so its cumulative delivery stats stay inspectable and a
     * subsequent re-enable reuses it; only a table deletion removes it.
     */
    public void onDestinationDisabled(String accountId, String region, String tableName, String streamArn) {
        DestinationState st = states.get(accountId + "|" + region + "|" + tableName + "|" + streamArn);
        if (st != null) {
            invalidate(st);
        }
    }

    /** Table deletion: discard all of its destinations' buffers, stop their drains, and drop their state. */
    public void onTableDeleted(String accountId, String region, String tableName) {
        String prefix = accountId + "|" + region + "|" + tableName + "|";
        states.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                invalidate(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private void invalidate(DestinationState st) {
        synchronized (st) {
            st.epoch++; // a scheduled drain from the old epoch will abort on its next check
            st.discardedOnDisable += st.queue.size();
            st.queue.clear();
            st.drainScheduled = false;
            st.consecutiveFailedProbes = 0; // a re-enable starts a fresh delivery episode
            st.health = Health.HEALTHY;
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    /** Blocks until every destination's queue is empty and no drain is scheduled, or the timeout elapses. */
    public boolean awaitIdle(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            boolean idle = true;
            for (DestinationState st : states.values()) {
                synchronized (st) {
                    if (!st.queue.isEmpty() || st.drainScheduled) {
                        idle = false;
                        break;
                    }
                }
            }
            if (idle) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ──────────────────────────── Observability ────────────────────────────

    /** Total CDC records permanently dropped across all destinations (terminal, give-up, or overflow). */
    public long getForwardFailureCount() {
        long sum = 0;
        for (DestinationState st : states.values()) {
            synchronized (st) {
                sum += st.dropped;
            }
        }
        return sum;
    }

    /** Immutable per-destination delivery-health snapshots (each copied under its destination lock). */
    List<DestinationForwardingStats> forwardingStats() {
        List<DestinationForwardingStats> out = new ArrayList<>();
        for (DestinationState st : states.values()) {
            synchronized (st) {
                out.add(new DestinationForwardingStats(st.health.name(), st.forwarded,
                        st.retried, st.dropped, st.overflowed, st.discardedOnDisable,
                        st.queue.size(), st.lastErrorType));
            }
        }
        return out;
    }

    // ──────────────────────────── Payload construction (unchanged) ────────────────────────────

    private ObjectNode buildPayload(String eventName, JsonNode keys,
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

    private String extractPartitionKey(JsonNode keys, TableDefinition table) {
        if (keys == null || keys.isEmpty()) {
            return "default";
        }
        String pkName = table.getPartitionKeyName();
        JsonNode pkValue = keys.get(pkName);
        if (pkValue == null) {
            return "default";
        }
        if (pkValue.has("S")) {
            return pkValue.get("S").asText();
        }
        if (pkValue.has("N")) {
            return pkValue.get("N").asText();
        }
        if (pkValue.has("B")) {
            return pkValue.get("B").asText();
        }
        return pkValue.toString();
    }

    private String extractStreamName(String streamArn) {
        int idx = streamArn.lastIndexOf('/');
        if (idx >= 0) {
            return streamArn.substring(idx + 1);
        }
        return streamArn;
    }
}
