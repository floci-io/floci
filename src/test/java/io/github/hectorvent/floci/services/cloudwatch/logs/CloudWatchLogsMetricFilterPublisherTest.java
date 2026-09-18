package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterRetryTest.FaultStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterRetryTest.await;
import static io.github.hectorvent.floci.services.cloudwatch.logs.PublicationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PutLogEvents evaluates the group's filters on the request thread and hands the resulting immutable
 * samples to a bounded queue. The single publisher thread writes them; the request never waits on the
 * Metrics sink. A full queue degrades to an inline write instead of dropping accepted samples.
 */
class CloudWatchLogsMetricFilterPublisherTest {
    private final FaultStore sink = new FaultStore();
    private final PublicationTestSupport f = new PublicationTestSupport(sink);

    @AfterEach
    void stop() { f.service.stop(); }

    @Test
    void ingestionQueuesSamplesAndWritesNothingUntilTheQueueIsDrained() {
        f.put("queued", "ERROR", "3", 7.0);
        f.enqueue(TIME, "ERROR", "INFO", "ERROR");
        assertEquals(3, f.service.queuedSamples());
        assertTrue(sink.attempts.isEmpty(), "the ingestion thread must not write to the Metrics sink");
        f.stats("queued", TIME, 0, 0);
        f.service.publishQueued();
        assertEquals(0, f.service.queuedSamples());
        f.stats("queued", TIME, 13, 3);
    }

    @Test
    void theStartedWorkerDrainsTheQueueWithoutFurtherIngestionOrReads() throws Exception {
        f.put("worker", "ERROR", "3", 7.0);
        CountDownLatch written = new CountDownLatch(2);
        sink.after = (key, datum) -> written.countDown();
        f.service.start(true, true);
        f.enqueue(TIME, "ERROR", "INFO");
        assertTrue(written.await(5, TimeUnit.SECONDS), "the worker must publish queued samples on its own");
        f.stats("worker", TIME, 10, 2);
        assertEquals(0, f.service.queuedSamples());
    }

    @Test
    void samplesQueuedBeforeStartAreDrainedWhenTheWorkerStarts() throws Exception {
        f.put("early", "ERROR", "3", 7.0);
        f.enqueue(TIME, "INFO");
        CountDownLatch written = new CountDownLatch(1);
        sink.after = (key, datum) -> written.countDown();
        f.service.start(true, true);
        assertTrue(written.await(5, TimeUnit.SECONDS));
        f.stats("early", TIME, 7, 1);
    }

    @Test
    void aFullQueueWritesTheBatchInlineInsteadOfDroppingIt() {
        PublicationTestSupport bounded = new PublicationTestSupport(sink, 4);
        try {
            bounded.put("bounded", "ERROR", "3", 7.0);
            bounded.enqueue(TIME, "INFO", "INFO", "INFO");
            assertEquals(3, bounded.service.queuedSamples());
            bounded.enqueue(TIME, "ERROR", "ERROR");
            assertEquals(3, bounded.service.queuedSamples(), "the batch that does not fit is written at once");
            bounded.stats("bounded", TIME, 6, 2);
            bounded.service.publishQueued();
            bounded.stats("bounded", TIME, 27, 5);
        } finally {
            bounded.service.stop();
        }
    }

    @Test
    void failedFirstAttemptsMoveToTheRetryQueueWithTheirIds() {
        f.put("failing", "ERROR", "3", 7.0);
        sink.fail = true;
        f.enqueue(TIME, "ERROR", "INFO");
        f.service.publishQueued();
        assertEquals(0, f.service.queuedSamples());
        assertEquals(2, f.service.pendingSamples());
        sink.fail = false;
        f.service.retryPending();
        assertEquals(0, f.service.pendingSamples());
        f.stats("failing", TIME, 10, 2);
        assertEquals(2, sink.keys().size(), "retries replace the same publication keys");
    }

    @Test
    void resetDiscardsQueuedSamplesAndResumesTheSameInstance() {
        f.put("reset", "ERROR", "3", 7.0);
        f.enqueue(TIME, "INFO", "INFO");
        f.service.beforeReset();
        assertEquals(0, f.service.queuedSamples());
        f.service.publishQueued();
        f.service.afterReset();
        f.service.publishQueued();
        f.stats("reset", TIME, 0, 0);
        f.ingest(TIME, "INFO");
        f.stats("reset", TIME, 7, 1);
    }

    @Test
    void resetWaitsForTheActiveWriteAndNothingIsWrittenAfterItReturns() throws Exception {
        f.put("midway", "ERROR", "3", 7.0);
        f.enqueue(TIME, "INFO", "INFO", "INFO");
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sink.before = (key, datum) -> { inWrite.countDown(); await(release); };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> drain = pool.submit(f.service::publishQueued);
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            Future<?> reset = pool.submit(f.service::beforeReset);
            assertThrows(TimeoutException.class, () -> reset.get(100, TimeUnit.MILLISECONDS),
                    "reset must wait for the write holding the monitor");
            sink.before = (key, datum) -> {};
            release.countDown();
            reset.get(5, TimeUnit.SECONDS);
            int writtenWhenResetReturned = sink.attempts.size();
            drain.get(5, TimeUnit.SECONDS);
            assertEquals(writtenWhenResetReturned, sink.attempts.size(), "no write may follow a completed beforeReset");
            assertEquals(0, f.service.queuedSamples());
            f.service.afterReset();
            f.service.publishQueued();
            assertEquals(writtenWhenResetReturned, sink.attempts.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aResetBetweenTwoSamplesOfABatchDiscardsTheRest() {
        f.put("between", "ERROR", "3", 7.0);
        f.enqueue(TIME, "INFO", "INFO", "INFO");
        // The monitor is reentrant, so a reset issued from inside the first write lands exactly
        // between two samples of the batch, which unfair scheduling cannot guarantee from another thread.
        sink.after = (key, datum) -> {
            f.service.beforeReset();
            f.service.afterReset();
        };
        f.service.publishQueued();
        assertEquals(1, sink.attempts.size(), "the samples after the reset belong to the wiped state");
        assertEquals(0, f.service.queuedSamples());
        sink.after = (key, datum) -> {};
        f.ingest(TIME, "INFO");
        f.stats("between", TIME, 14, 2);
    }

    @Test
    void stopDrainsQueuedSamplesBeforeTheWorkerShutsDown() {
        f.put("stopping", "ERROR", "3", 7.0);
        f.enqueue(TIME, "ERROR", "INFO");
        f.service.stop();
        assertEquals(0, f.service.queuedSamples());
        f.stats("stopping", TIME, 10, 2);
    }

    @Test
    void deletingTheFilterDoesNotCancelSamplesAlreadyAccepted() {
        f.put("deleted", "ERROR", "3", 7.0);
        f.enqueue(TIME, "ERROR");
        f.service.deleteMetricFilter(GROUP, "deleted", REGION);
        f.service.publishQueued();
        f.stats("deleted", TIME, 3, 1);
    }

    @Test
    void eventsIngestedWhileDisabledAreNeverQueued() {
        f.service.start(true, false);
        f.put("disabled", "ERROR", "3", 7.0);
        f.enqueue(TIME, "ERROR");
        assertEquals(0, f.service.queuedSamples());
        f.service.publishQueued();
        f.stats("disabled", TIME, 0, 0);
        assertEquals(List.of(), sink.attempts);
    }
}
