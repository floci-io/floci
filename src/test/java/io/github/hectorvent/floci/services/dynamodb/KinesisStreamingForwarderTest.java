package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CDC delivery contract (issue: DynamoDB→Kinesis records dropped on forward exception).
 *
 * <p>{@code forward} is proven ENQUEUE-ONLY (no synchronous Kinesis I/O, never throws); the actual send,
 * retry, terminal-drop, give-up, overflow, FIFO, and lifecycle behaviour are exercised deterministically
 * by driving a manual {@link KinesisStreamingForwarder.Scheduler} step by step: no real timers, no sleeps
 * in the delivery assertions.
 */
class KinesisStreamingForwarderTest {

    private static final String ACCOUNT = "000000000000";
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/test-stream";

    private KinesisService kinesisService;
    private ObjectMapper objectMapper;
    private ManualScheduler scheduler;
    private KinesisStreamingForwarder forwarder;

    @BeforeEach
    void setUp() {
        kinesisService = mock(KinesisService.class);
        objectMapper = new ObjectMapper();
        scheduler = new ManualScheduler();
        forwarder = new KinesisStreamingForwarder(kinesisService, objectMapper, scheduler);
    }

    // ──────────────────────────── helpers ────────────────────────────

    /** A Scheduler the test drives by hand: tasks queue up and run only when the test asks. */
    static final class ManualScheduler implements KinesisStreamingForwarder.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();
        boolean shutdown;

        @Override
        public void schedule(Runnable task, long delayMillis) {
            tasks.addLast(task);
            delays.add(delayMillis);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        boolean runOne() {
            Runnable r = tasks.pollFirst();
            if (r == null) {
                return false;
            }
            r.run();
            return true;
        }

        int runUntilQuiet() {
            int steps = 0;
            while (steps < 20_000 && runOne()) {
                steps++;
            }
            return steps;
        }
    }

    /** A Scheduler that rejects while {@code reject} is set (executor-shutdown simulation), else queues. */
    static final class RejectingScheduler implements KinesisStreamingForwarder.Scheduler {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean reject = true;

        @Override
        public void schedule(Runnable task, long delayMillis) {
            if (reject) {
                throw new RejectedExecutionException("executor is shutting down");
            }
            tasks.addLast(task);
        }

        @Override
        public void shutdown() {
        }

        boolean runOne() {
            Runnable r = tasks.pollFirst();
            if (r == null) {
                return false;
            }
            r.run();
            return true;
        }
    }

    private TableDefinition tableWithDestination(String... streamArns) {
        TableDefinition table = new TableDefinition("test-table",
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")));
        for (String arn : streamArns) {
            table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(arn));
        }
        return table;
    }

    private ObjectNode item(String pk) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode pkValue = objectMapper.createObjectNode();
        pkValue.put("S", pk);
        node.set("pk", pkValue);
        return node;
    }

    private DestinationForwardingStats onlyStat() {
        List<DestinationForwardingStats> stats = forwarder.forwardingStats();
        assertEquals(1, stats.size(), "expected exactly one destination state");
        return stats.get(0);
    }

    // ──────────────────────────── enqueue path ────────────────────────────

    @Test
    void forwardOnlyEnqueues_neverCallsKinesisSynchronouslyNorThrows() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("kinesis down"));

        assertDoesNotThrow(() -> forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT));

        // The write path did no Kinesis I/O: the record is buffered, not delivered and not dropped.
        verifyNoInteractions(kinesisService);
        DestinationForwardingStats s = onlyStat();
        assertEquals(1, s.queueDepth());
        assertEquals(0L, s.forwarded());
        assertEquals(0L, s.dropped());
    }

    @Test
    void skipsDisabledDestination() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        table.getKinesisStreamingDestinations().get(0).setDestinationStatus("DISABLED");

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);

        assertTrue(forwarder.forwardingStats().isEmpty(), "a disabled destination is never enqueued");
        assertFalse(scheduler.runOne(), "nothing scheduled for a disabled destination");
    }

    @Test
    void skipsWhenNoDestinations() {
        forwarder.forward("INSERT", null, item("k1"), tableWithDestination(), "us-east-1", ACCOUNT);
        verifyNoInteractions(kinesisService);
        assertTrue(forwarder.forwardingStats().isEmpty());
    }

    // ──────────────────────────── happy-path drain ────────────────────────────

    @Test
    void deliversBufferedRecordOnDrain() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq-1");

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        verify(kinesisService).putRecordForAccount(eq(ACCOUNT), eq("test-stream"), any(byte[].class),
                eq("k1"), eq("us-east-1"));
        DestinationForwardingStats s = onlyStat();
        assertEquals(1L, s.forwarded());
        assertEquals(0, s.queueDepth());
        assertEquals("HEALTHY", s.health());
    }

    @Test
    void threadsTableOwnerAccountToThePut() {
        // The owning account is threaded through so an out-of-request-scope forward lands in the owner's stream.
        TableDefinition table = tableWithDestination("arn:aws:kinesis:us-east-1:111111111111:stream/test-stream");
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq-1");

        forwarder.forward("REMOVE", item("k1"), null, table, "us-east-1", "111111111111");
        scheduler.runUntilQuiet();

        verify(kinesisService).putRecordForAccount(eq("111111111111"), eq("test-stream"), any(byte[].class),
                eq("k1"), eq("us-east-1"));
    }

    @Test
    void preservesFifoAcrossARetry() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        List<String> delivered = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new RuntimeException("transient on the very first attempt");
                    }
                    delivered.add(inv.getArgument(3)); // partitionKey
                    return "seq";
                });

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        forwarder.forward("INSERT", null, item("k2"), table, "us-east-1", ACCOUNT);
        forwarder.forward("INSERT", null, item("k3"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        // k1 was retried, yet ordering is preserved: the head is not skipped past.
        assertEquals(List.of("k1", "k2", "k3"), delivered);
        DestinationForwardingStats s = onlyStat();
        assertEquals(3L, s.forwarded());
        assertEquals(1L, s.retried());
        assertEquals(0L, s.dropped());
    }

    // ──────────────────────────── failure classification ────────────────────────────

    @Test
    void unknownFailureIsRetriedNotDropped() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("transient"));

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        scheduler.runOne(); // exactly one drain attempt

        DestinationForwardingStats s = onlyStat();
        assertEquals(1, s.queueDepth(), "an unknown failure keeps the record buffered for retry");
        assertEquals(0L, s.dropped());
        assertEquals(1L, s.retried());
        assertEquals("RETRYING", s.health());
    }

    @Test
    void terminalFailureDropsThePoisonRecordWithoutWedgingTheQueue() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        List<String> delivered = new ArrayList<>();
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String pk = inv.getArgument(3);
                    if ("k1".equals(pk)) {
                        throw new AwsException("ValidationException", "record too large", 400);
                    }
                    delivered.add(pk);
                    return "seq";
                });

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT); // poison
        forwarder.forward("INSERT", null, item("k2"), table, "us-east-1", ACCOUNT); // good
        scheduler.runUntilQuiet();

        // The poison record is dropped immediately; the good record behind it is still delivered.
        assertEquals(List.of("k2"), delivered);
        DestinationForwardingStats s = onlyStat();
        assertEquals(1L, s.dropped());
        assertEquals(1L, s.forwarded());
        assertEquals("ValidationException", s.lastErrorType());
        assertEquals(1L, forwarder.getForwardFailureCount());
    }

    @Test
    void givesUpAfterProbeBudgetThenSelfHealsOnALaterEvent() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        AtomicBoolean healthy = new AtomicBoolean(false);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    if (!healthy.get()) {
                        throw new RuntimeException("stream unavailable");
                    }
                    return "seq";
                });

        // Episode 1: persistent failure exhausts the probe budget and drops the buffered record.
        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        DestinationForwardingStats gaveUp = onlyStat();
        assertEquals("GAVE_UP", gaveUp.health());
        assertEquals(1L, gaveUp.dropped());
        assertEquals(0, gaveUp.queueDepth());
        // Exactly MAX_PROBES (10) send attempts: the initial immediate run + 9 retry schedules, backoff
        // 250ms doubling to the 8s cap. Give-up fires on the 10th failure (no 11th schedule).
        assertEquals(List.of(0L, 250L, 500L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L, 8000L),
                List.copyOf(scheduler.delays));

        // Episode 2: a fresh event with a recovered stream re-attempts (budget was reset) and heals.
        healthy.set(true);
        forwarder.forward("INSERT", null, item("k2"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        DestinationForwardingStats healed = onlyStat();
        assertEquals("HEALTHY", healed.health());
        assertEquals(1L, healed.forwarded());
        assertEquals(1L, healed.dropped(), "the episode-1 drop is retained in the cumulative count");
        assertEquals(0, healed.queueDepth());
    }

    // ──────────────────────────── overflow ────────────────────────────

    @Test
    void overflowDropsOldestBeyondTheBufferCap() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        int over = 2;
        for (int i = 0; i < KinesisStreamingForwarder.MAX_BUFFERED + over; i++) {
            forwarder.forward("INSERT", null, item("k" + i), table, "us-east-1", ACCOUNT);
        }
        // Never drained: the buffer is capped and the oldest are evicted.
        DestinationForwardingStats s = onlyStat();
        assertEquals(KinesisStreamingForwarder.MAX_BUFFERED, s.queueDepth());
        assertEquals(over, s.overflowed());
        assertEquals(over, s.dropped());
        assertEquals(over, forwarder.getForwardFailureCount());
    }

    // ──────────────────────────── lifecycle ────────────────────────────

    @Test
    void disableDiscardsBufferAndStalesTheInFlightDrain() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);

        forwarder.onDestinationDisabled(ACCOUNT, "us-east-1", "test-table", STREAM_ARN);

        DestinationForwardingStats s = onlyStat(); // state retained on disable
        assertEquals(0, s.queueDepth());
        assertEquals(1L, s.discardedOnDisable());

        // The drain scheduled before the disable is now stale: running it is a no-op (epoch bumped).
        scheduler.runUntilQuiet();
        verifyNoInteractions(kinesisService);
        assertEquals(0L, onlyStat().forwarded());
    }

    @Test
    void tableDeleteRemovesStateAndStalesTheInFlightDrain() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);

        forwarder.onTableDeleted(ACCOUNT, "us-east-1", "test-table");

        assertTrue(forwarder.forwardingStats().isEmpty(), "table delete drops the destination state");
        scheduler.runUntilQuiet();
        verifyNoInteractions(kinesisService);
    }

    @Test
    void awaitIdleReflectsQueueState() {
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq");

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        assertFalse(forwarder.awaitIdle(Duration.ofMillis(50)), "buffered + drain pending is not idle");

        scheduler.runUntilQuiet();
        assertTrue(forwarder.awaitIdle(Duration.ofMillis(50)), "drained empty is idle");
    }

    // ──────────────────────────── race / robustness ────────────────────────────

    @Test
    void overflowWhileTheHeadIsInFlightNeverEvictsTheRecordBeingSent() {
        // Deterministically reproduces the overflow-vs-in-flight race: while k0 is being sent, a burst of
        // producers overfills the buffer. The in-flight record must not be evicted, and nothing may be lost
        // without accounting (the peek-then-poll approach would evict k0 and then poll a different record).
        TableDefinition table = tableWithDestination(STREAM_ARN);
        int burst = KinesisStreamingForwarder.MAX_BUFFERED + 5;
        List<String> delivered = new ArrayList<>();
        AtomicBoolean filled = new AtomicBoolean(false);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String pk = inv.getArgument(3);
                    if ("k0".equals(pk) && filled.compareAndSet(false, true)) {
                        for (int i = 1; i <= burst; i++) {
                            forwarder.forward("INSERT", null, item("k" + i), table, "us-east-1", ACCOUNT);
                        }
                    }
                    delivered.add(pk);
                    return "seq";
                });

        forwarder.forward("INSERT", null, item("k0"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        // k0 (in flight during the overflow) was delivered, not evicted; the 5 oldest buffered were dropped.
        assertEquals("k0", delivered.get(0));
        assertFalse(delivered.contains("k1"), "the oldest buffered record is the overflow victim, not k0");
        DestinationForwardingStats s = onlyStat();
        assertEquals(5L, s.overflowed());
        assertEquals(5L, s.dropped());
        assertEquals(burst + 1 - 5, s.forwarded());
        // Every one of the burst+1 records is accounted for exactly once: no silent loss.
        assertEquals(burst + 1, s.forwarded() + s.dropped());
        assertEquals(0, s.queueDepth());
    }

    @Test
    void schedulerRejectionNeitherEscapesForwardNorWedgesTheDestination() {
        RejectingScheduler rejecting = new RejectingScheduler();
        KinesisStreamingForwarder f = new KinesisStreamingForwarder(kinesisService, objectMapper, rejecting);
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq");

        // Executor down: scheduling the initial drain is rejected, but forward must not throw, and the
        // record stays buffered (not dropped).
        assertDoesNotThrow(() -> f.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT));
        assertEquals(1, f.forwardingStats().get(0).queueDepth());

        // Executor recovers: a later event must be able to schedule a fresh drain (no wedge), and BOTH the
        // previously-buffered and the new record drain in FIFO order.
        rejecting.reject = false;
        f.forward("INSERT", null, item("k2"), table, "us-east-1", ACCOUNT);
        while (rejecting.runOne()) {
            // drive the recovered drain to completion
        }
        DestinationForwardingStats s = f.forwardingStats().get(0);
        assertEquals(2L, s.forwarded());
        assertEquals(0, s.queueDepth());
    }

    @Test
    void terminalHeadDropGivesTheNextRecordItsOwnFullProbeBudget() {
        // k1 fails transiently then terminally; k2 must then get a FRESH MAX_PROBES budget, not the leftover.
        TableDefinition table = tableWithDestination(STREAM_ARN);
        Map<String, Integer> attempts = new HashMap<>();
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String pk = inv.getArgument(3);
                    int n = attempts.merge(pk, 1, Integer::sum);
                    if ("k1".equals(pk)) {
                        if (n <= 3) {
                            throw new RuntimeException("transient");
                        }
                        throw new AwsException("ValidationException", "poison", 400); // 4th attempt: terminal
                    }
                    throw new RuntimeException("k2 stream down"); // k2 always transient
                });

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        forwarder.forward("INSERT", null, item("k2"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        assertEquals(4, attempts.get("k1"), "3 transient retries then the terminal attempt");
        // If the budget had not been reset on the terminal drop, k2 would give up after only
        // MAX_PROBES - 3 attempts. A fresh budget means exactly MAX_PROBES attempts for k2.
        assertEquals(KinesisStreamingForwarder.MAX_PROBES, attempts.get("k2"));
        DestinationForwardingStats s = onlyStat();
        assertEquals(2L, s.dropped()); // k1 (terminal) + k2 (give-up)
        assertEquals("GAVE_UP", s.health());
    }

    @Test
    void disableDuringInFlightSendCompletesButDoesNotResurrectOrDoubleCount() {
        // Characterizes the inherent teardown race (documented as best-effort): a send dispatched before a
        // disable may complete, but the torn-down destination is neither re-counted nor resurrected.
        TableDefinition table = tableWithDestination(STREAM_ARN);
        when(kinesisService.putRecordForAccount(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    forwarder.onDestinationDisabled(ACCOUNT, "us-east-1", "test-table", STREAM_ARN);
                    return "seq";
                });

        forwarder.forward("INSERT", null, item("k1"), table, "us-east-1", ACCOUNT);
        scheduler.runUntilQuiet();

        // The dispatched send ran exactly once; its result is discarded (epoch bumped), the buffer is empty,
        // and no further drain runs.
        verify(kinesisService, times(1)).putRecordForAccount(anyString(), anyString(), any(byte[].class),
                anyString(), anyString());
        DestinationForwardingStats s = onlyStat();
        assertEquals(0, s.queueDepth());
        assertEquals(0L, s.forwarded());
    }
}
