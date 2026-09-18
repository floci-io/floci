package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.github.hectorvent.floci.services.cloudwatch.logs.PublicationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudWatchLogsMetricFilterRetryTest {
    private final FaultStore sink = new FaultStore();
    private final PublicationTestSupport f = new PublicationTestSupport(sink);

    @AfterEach
    void stop() { f.service.stop(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realPartialWritesRetryWithoutDuplicatesAndIngestionStillSucceeds(boolean after) {
        f.put("broken", "ERROR", "3", 7.0);
        AtomicBoolean armed = new AtomicBoolean(true);
        BiConsumer<String, MetricDatum> fault = (key, datum) -> {
            if (datum.getValue() == 7 && armed.compareAndSet(true, false)) { throw new IllegalStateException("sink failure"); }
        };
        if (after) { sink.after = fault; } else { sink.before = fault; }
        f.ingest(TIME, "ERROR", "INFO", "ERROR");
        assertEquals(1, f.service.pendingSamples());
        f.service.retryPending();
        f.service.retryPending();
        f.stats("broken", TIME, 13, 3);
        assertEquals(0, f.service.pendingSamples());
        assertEquals(3, sink.keys().size());
        assertTrue(sink.attempts.size() > sink.attempts.stream().distinct().count());
        f.ingest(TIME, "ERROR", "INFO", "ERROR");
        f.stats("broken", TIME, 26, 6); // new identical accepted logs are legitimate contributions
    }

    @Test
    void confirmingAnIndeterminateDeleteCancelsOnlyTheAbsentOwnersPendingSamples() {
        f.put("gone", "ERROR", "3", null);
        sink.fail = true;
        f.ingest(TIME, "ERROR");
        f.account.set(OTHER_ACCOUNT);
        f.group(GROUP, REGION);
        f.put("gone", "ERROR", "9", null);
        f.ingest(TIME, "ERROR");
        assertEquals(2, f.service.pendingSamples());
        f.account.set(ACCOUNT);
        synchronized (f.filters) {
            f.filters.keys().forEach(f.filters::delete);
        }
        assertTrue(f.service.confirmMetricFilterAbsent(GROUP, "gone", REGION,
                () -> assertTrue(Thread.holdsLock(f.filters))));
        assertEquals(1, f.service.pendingSamples(), "confirmed absence must finish deletion's publication cleanup");
        sink.fail = false;
        f.service.retryPending();
        f.stats("gone", TIME, 0, 0);
        f.account.set(OTHER_ACCOUNT);
        f.stats("gone", TIME, 9, 1);
    }

    @Test
    void failedWorkRetainsCanonicalAccountAndImmutableTransformationAcrossUpdate() {
        MetricFilter old = definition("filter", "{ $.value = * }", "$.value", null);
        old.getMetricTransformations().getFirst().setDimensions(Map.of("A", "$.a"));
        f.service.putMetricFilter(old, REGION);
        sink.fail = true;
        f.ingest(TIME, "{\"value\":3,\"a\":\"alpha\"}");
        MetricFilter replacement = definition("filter", "ERROR", "99", 17.0);
        replacement.getMetricTransformations().getFirst().setMetricNamespace("New");
        replacement.getMetricTransformations().getFirst().setMetricName("NewName");
        replacement.getMetricTransformations().getFirst().setUnit("Bytes");
        f.service.putMetricFilter(replacement, REGION);
        clearInvocations(f.resolver);
        f.account.set(OTHER_ACCOUNT); // no request context survives on the worker
        sink.fail = false;
        f.service.retryPending();
        verify(f.resolver, never()).getAccountId();
        f.stats("filter", List.of(new Dimension("A", "alpha")), TIME, 0, 0);
        f.account.set(ACCOUNT);
        f.stats("filter", List.of(new Dimension("A", "alpha")), TIME, 3, 1);
        assertEquals("Count", f.points("filter", List.of(new Dimension("A", "alpha")), TIME, REGION).getFirst().unit());
        assertTrue(f.metrics.listMetrics("New", null, null, REGION).isEmpty());
        f.ingest(TIME, "ERROR");
        assertEquals(1, f.metrics.listMetrics("New", "NewName", null, REGION).size());
    }

    @Test
    void accountIsResolvedOnceForImmediateAndRetainedPublication() {
        f.put("account", "ERROR", "3", 7.0);
        clearInvocations(f.resolver);
        sink.fail = true;
        f.ingest(TIME, "ERROR", "INFO");
        verify(f.resolver, times(1)).getAccountId();
        f.account.set(OTHER_ACCOUNT);
        sink.fail = false;
        f.service.retryPending();
        verify(f.resolver, times(1)).getAccountId();
        f.stats("account", TIME, 0, 0);
        f.account.set(ACCOUNT);
        f.stats("account", TIME, 10, 2);
    }

    @Test
    void failingFilterDoesNotStopOtherFiltersOrSuccessfulLogsIngestion() {
        f.put("broken", "ERROR", "3", null);
        f.put("healthy", "ERROR", "5", null);
        sink.before = (key, datum) -> {
            if (datum.getNamespace().equals("broken")) { throw new IllegalStateException("one namespace unavailable"); }
        };
        f.ingest(TIME, "ERROR");
        f.stats("healthy", TIME, 5, 1);
        assertEquals(1, f.service.pendingSamples());
        sink.before = (key, datum) -> {};
        f.service.retryPending();
        f.stats("broken", TIME, 3, 1);
    }

    @Test
    void sampleBoundReportsOverflowAtErrorPreservesQueuedIdsAndReleasesCapacity() {
        f.put("bound", "ERROR", "3", 7.0);
        sink.fail = true;
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger(CloudWatchLogsMetricFilterService.class.getName());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            f.ingest(null, GROUP, REGION, TIME, Collections.nCopies(10_003, "INFO"));
            assertEquals(10_000, f.service.pendingSamples(), "bound counts samples, not batches");
            assertTrue(records.stream().anyMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue()
                    && formatted(r).contains(ACCOUNT) && formatted(r).contains(GROUP)
                    && formatted(r).contains("region=" + REGION) && formatted(r).contains("filter=bound")
                    && formatted(r).contains("dropped=3")), "overflow must be ERROR with ownership and dropped count");
            List<String> queuedKeys = List.copyOf(sink.attempts.subList(0, 10_000));
            sink.fail = false;
            f.service.retryPending();
            f.stats("bound", TIME, 70_000, 10_000);
            assertEquals(0, f.service.pendingSamples());
            assertEquals(Set.copyOf(queuedKeys), sink.keys());
            sink.fail = true;
            f.ingest(TIME, "INFO");
            assertEquals(1, f.service.pendingSamples());
            f.service.deleteMetricFilter(GROUP, "bound", REGION);
            assertEquals(0, f.service.pendingSamples());
            f.put("bound", "ERROR", "3", 7.0);
            f.ingest(TIME, "INFO");
            assertEquals(1, f.service.pendingSamples());
        } finally { logger.removeHandler(handler); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteCancelsOnlyItsAccountRegionGroupAndFilter(boolean group) {
        f.put("same", "ERROR", "3", null);
        f.put("sibling", "ERROR", "5", null);
        sink.fail = true;
        f.ingest(TIME, "ERROR");
        f.group(GROUP, "us-east-1");
        f.service.putMetricFilter(definition("same", "ERROR", "3", null), "us-east-1");
        f.ingest(null, GROUP, "us-east-1", TIME, List.of("ERROR"));
        f.group("/other", REGION);
        MetricFilter other = definition("same", "ERROR", "3", null);
        other.setLogGroupName("/other");
        f.service.putMetricFilter(other, REGION);
        f.ingest(null, "/other", REGION, TIME, List.of("ERROR"));
        f.account.set(OTHER_ACCOUNT);
        f.group(GROUP, REGION);
        f.put("same", "ERROR", "3", null);
        f.ingest(TIME, "ERROR");
        f.account.set(ACCOUNT);
        if (group) { f.logs.deleteLogGroup(GROUP, REGION); }
        else { f.service.deleteMetricFilter(GROUP, "same", REGION); }
        assertEquals(group ? 3 : 4, f.service.pendingSamples());
        sink.fail = false;
        f.service.retryPending();
        f.stats("same", TIME, 3, 1); // other group only
        f.stats("sibling", TIME, group ? 0 : 5, group ? 0 : 1);
        assertEquals(3, f.points("same", List.of(), TIME, "us-east-1").getFirst().sum());
        f.account.set(OTHER_ACCOUNT);
        f.stats("same", TIME, 3, 1);
    }

    @Test
    void clearOnTheSameInstanceReleasesQueuedWork() {
        f.put("reset", "ERROR", "3", 7.0);
        sink.fail = true;
        f.ingest(TIME, "INFO");
        assertEquals(1, f.service.pendingSamples());
        f.service.clear();
        assertEquals(0, f.service.pendingSamples());
        sink.fail = false;
        f.service.retryPending();
        f.stats("reset", TIME, 0, 0);
        f.ingest(TIME, "INFO");
        f.stats("reset", TIME, 7, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"filter", "group", "update", "clear", "stop"})
    void mutationRacingRetryWaitsForTheActiveWriteAndNeverReinterpretsIt(String mutation) throws Exception {
        f.put("race", "ERROR", "3", 7.0);
        sink.fail = true;
        f.ingest(TIME, "INFO");
        sink.fail = false;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sink.before = (key, datum) -> { entered.countDown(); await(release); };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> retry = pool.submit(f.service::retryPending);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch started = new CountDownLatch(1);
            Future<?> change = pool.submit(() -> {
                started.countDown();
                switch (mutation) {
                    case "filter" -> f.service.deleteMetricFilter(GROUP, "race", REGION);
                    case "group" -> f.logs.deleteLogGroup(GROUP, REGION);
                    case "clear" -> f.service.clear();
                    case "stop" -> f.service.stop();
                    case "update" -> {
                        MetricFilter next = definition("race", "ERROR", "99", 17.0);
                        next.getMetricTransformations().getFirst().setMetricNamespace("new");
                        f.service.putMetricFilter(next, REGION);
                    }
                    default -> fail(mutation);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> change.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            retry.get(5, TimeUnit.SECONDS);
            change.get(5, TimeUnit.SECONDS);
            sink.before = (key, datum) -> {};
            assertEquals(0, f.service.pendingSamples());
            f.service.retryPending();
            f.stats("race", TIME, 7, 1);
            if (mutation.equals("stop")) {
                f.ingest(TIME, "INFO");
                f.stats("race", TIME, 7, 1);
                return;
            }
            if (mutation.equals("group")) { f.group(GROUP, REGION); }
            if (!mutation.equals("update")) { f.put("race", "ERROR", "3", 11.0); }
            f.ingest(TIME, "INFO");
            f.stats("race", TIME, mutation.equals("update") ? 7 : 18, mutation.equals("update") ? 1 : 2);
            if (mutation.equals("update")) { f.stats("new", TIME, 17, 1); }
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousStreamsAndConcurrentRetryTicksKeepEveryAcceptedContribution() throws Exception {
        f.put("streams", "ERROR", "3", 7.0);
        f.logs.createLogStream(GROUP, "other", REGION);
        sink.fail = true;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> { await(start); f.ingest(TIME, "ERROR", "INFO"); });
            Future<?> second = pool.submit(() -> {
                await(start);
                f.logs.putLogEvents(GROUP, "other", List.of(Map.of("timestamp", TIME, "message", "INFO")), REGION);
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            f.service.publishQueued();
            assertEquals(3, f.service.pendingSamples());
            sink.fail = false;
            Future<?> retryOne = pool.submit(f.service::retryPending);
            Future<?> retryTwo = pool.submit(f.service::retryPending);
            retryOne.get(5, TimeUnit.SECONDS);
            retryTwo.get(5, TimeUnit.SECONDS);
            f.stats("streams", TIME, 17, 3);
            assertEquals(0, f.service.pendingSamples());
        } finally {
            start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eitherDisabledServicePreventsPublicationAndWorkerStartup(boolean logsDisabled) {
        f.service.start(!logsDisabled, logsDisabled);
        f.put("disabled", "ERROR", "3", 7.0);
        f.ingest(TIME, "ERROR");
        assertFalse(f.service.publisherRunning());
        f.stats("disabled", TIME, 0, 0);
        assertEquals(0, f.service.pendingSamples());
    }

    @Test
    void enabledWorkerStartsOnceAndStopIsIdempotentAndCancelsPending() {
        f.service.start(true, true);
        f.service.start(true, true);
        assertTrue(f.service.publisherRunning());
        f.put("stop", "ERROR", "3", 7.0);
        sink.fail = true;
        f.ingest(TIME, "INFO");
        f.service.stop();
        f.service.stop();
        assertFalse(f.service.publisherRunning());
        assertEquals(0, f.service.pendingSamples());
        sink.fail = false;
        f.service.retryPending();
        f.ingest(TIME, "INFO");
        f.stats("stop", TIME, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void loggingOneHundredFailedTicksKeepOneDetailPerOutageAndBoundedSummaries(int filters) {
        for (int i = 0; i < filters; i++) { f.put("outage-" + i, "ERROR", "3", 7.0); }
        sink.fail = true;
        try (FailureLogs logs = new FailureLogs()) {
            f.ingest(TIME, "INFO");
            for (int tick = 0; tick < 100; tick++) { f.service.retryPending(); }
            assertEquals(101 * filters, sink.attempts.size(), "diagnostic throttling must not throttle retries");
            assertEquals(filters, f.service.pendingSamples());
            assertEquals(filters, logs.details(), "one stack per owning filter outage, not per tick or sample");
            assertEquals(1, logs.summaries(), "one aggregate stack-free warning after 60 failed ticks, even for 100 filters");
            LogRecord summary = logs.warnings.stream().filter(record -> record.getThrown() == null).findFirst().orElseThrow();
            assertTrue(formatted(summary).contains("failedSamples=" + filters));
            assertTrue(formatted(summary).contains("filters=" + filters));
        }
    }

    @Test
    void loggingNewFailedIngestionDoesNotRepeatAnOutstandingOutagesStack() {
        f.put("outage", "ERROR", "3", 7.0);
        sink.fail = true;
        try (FailureLogs logs = new FailureLogs()) {
            for (int batch = 0; batch < 100; batch++) { f.ingest(TIME, "INFO"); }
            assertEquals(100, f.service.pendingSamples());
            assertEquals(100, sink.attempts.size());
            assertEquals(1, logs.details());
            assertEquals(0, logs.summaries(), "new batches must not bypass retry summary throttling");
        }
    }

    @Test
    void loggingRecoveryThenNewOutageGetsFreshDetailAndSummaryWindow() {
        f.put("outage", "ERROR", "3", 7.0);
        sink.fail = true;
        try (FailureLogs logs = new FailureLogs()) {
            f.ingest(TIME, "INFO");
            for (int tick = 0; tick < 100; tick++) { f.service.retryPending(); }
            sink.fail = false;
            f.service.retryPending();
            assertEquals(0, f.service.pendingSamples());
            f.stats("outage", TIME, 7, 1);
            sink.fail = true;
            f.ingest(TIME, "INFO");
            assertEquals(2, logs.details(), "recovery must allow fresh detail for the next outage");
            for (int tick = 0; tick < 59; tick++) { f.service.retryPending(); }
            assertEquals(1, logs.summaries(), "recovery must restart the summary window");
            f.service.retryPending();
            assertEquals(2, logs.summaries());
            assertEquals(2, logs.details());
            assertEquals(163, sink.attempts.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"filter", "group", "clear", "reset"})
    void loggingLastCancellationOrResetReleasesSuppressionAndRestartsSummaryWindow(String operation) {
        f.put("outage", "ERROR", "3", 7.0);
        sink.fail = true;
        try (FailureLogs logs = new FailureLogs()) {
            f.ingest(TIME, "INFO");
            for (int tick = 0; tick < 59; tick++) { f.service.retryPending(); }
            switch (operation) {
                case "filter" -> f.service.deleteMetricFilter(GROUP, "outage", REGION);
                case "group" -> f.logs.deleteLogGroup(GROUP, REGION);
                case "clear" -> f.service.clear();
                case "reset" -> {
                    f.service.beforeReset();
                    f.service.clear();
                    f.service.afterReset();
                }
                default -> fail(operation);
            }
            assertEquals(0, f.service.pendingSamples());
            if (operation.equals("group")) { f.group(GROUP, REGION); }
            f.put("outage", "ERROR", "3", 7.0);
            f.ingest(TIME, "INFO");
            f.service.retryPending();
            assertEquals(2, logs.details());
            assertEquals(0, logs.summaries(), "cancelling the final pending owner must restart the summary window");
            for (int tick = 0; tick < 59; tick++) { f.service.retryPending(); }
            assertEquals(1, logs.summaries());
            assertEquals(2, logs.details());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loggingScopedCancellationDoesNotRearmAnUnrelatedPendingOutage(boolean group) {
        f.put("outage", "ERROR", "3", 7.0);
        f.group("/other", REGION);
        MetricFilter sibling = definition("sibling", "ERROR", "3", 7.0);
        sibling.setLogGroupName("/other");
        f.service.putMetricFilter(sibling, REGION);
        sink.fail = true;
        try (FailureLogs logs = new FailureLogs()) {
            f.ingest(TIME, "INFO");
            f.ingest(null, "/other", REGION, TIME, List.of("INFO"));
            for (int tick = 0; tick < 10; tick++) { f.service.retryPending(); }
            if (group) {
                f.logs.deleteLogGroup(GROUP, REGION);
                f.group(GROUP, REGION);
            } else {
                f.service.deleteMetricFilter(GROUP, "outage", REGION);
            }
            assertEquals(1, f.service.pendingSamples());
            f.put("outage", "ERROR", "3", 7.0);
            f.ingest(TIME, "INFO");
            f.ingest(null, "/other", REGION, TIME, List.of("INFO"));
            for (int tick = 0; tick < 100; tick++) { f.service.retryPending(); }
            assertEquals(3, f.service.pendingSamples());
            assertEquals(3, logs.details(), "only the cancelled owner gets new detail; its sibling stays suppressed");
            assertEquals(1, logs.summaries());
        }
    }

    private static class FailureLogs extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(CloudWatchLogsMetricFilterService.class.getName());
        private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();

        FailureLogs() { logger.addHandler(this); }
        @Override public void publish(LogRecord record) {
            if (record.getLevel().intValue() == Level.WARNING.intValue()) { warnings.add(record); }
        }
        long details() { return warnings.stream().filter(record -> record.getThrown() != null).count(); }
        long summaries() { return warnings.stream().filter(record -> record.getThrown() == null).count(); }
        @Override public void flush() {}
        @Override public void close() { logger.removeHandler(this); }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static String formatted(LogRecord r) {
        return MessageFormat.format(r.getMessage(), r.getParameters() == null ? new Object[0] : r.getParameters());
    }

    static class FaultStore extends InMemoryStorage<String, MetricDatum> {
        volatile boolean fail;
        volatile BiConsumer<String, MetricDatum> before = (key, value) -> {};
        volatile BiConsumer<String, MetricDatum> after = (key, value) -> {};
        final List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        @Override public void put(String key, MetricDatum value) {
            attempts.add(key);
            if (fail) { throw new IllegalStateException("storage offline"); }
            before.accept(key, value);
            super.put(key, value);
            after.accept(key, value);
        }
    }
}
