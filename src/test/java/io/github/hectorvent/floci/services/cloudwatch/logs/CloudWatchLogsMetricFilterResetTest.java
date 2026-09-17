package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.lifecycle.EmulatorInfoController;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterRetryTest.await;
import static io.github.hectorvent.floci.services.cloudwatch.logs.PublicationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudWatchLogsMetricFilterResetTest {
    private final CloudWatchLogsMetricFilterRetryTest.FaultStore sink = new CloudWatchLogsMetricFilterRetryTest.FaultStore();
    private final PublicationTestSupport f = new PublicationTestSupport(sink);

    @AfterEach
    void stop() { f.service.stop(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualResetControllerDrainsPublicationBeforeStorageWipe(boolean afterWrite) throws Exception {
        f.put("reset", "ERROR", "3", 7.0);
        sink.fail = true;
        f.ingest(TIME, "INFO");
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        if (afterWrite) { sink.after = (key, datum) -> { inWrite.countDown(); await(releaseWrite); }; }
        else { sink.before = (key, datum) -> { inWrite.countDown(); await(releaseWrite); }; }
        sink.fail = false;
        CountDownLatch resetReachedService = new CountDownLatch(1);
        Resettable resettable = new Resettable() {
            @Override public void beforeReset() { resetReachedService.countDown(); f.service.beforeReset(); }
            @Override public void clear() { resetReachedService.countDown(); f.service.clear(); }
            @Override public void afterReset() { f.service.afterReset(); }
        };
        EmulatorInfoController controller = controller(resettable);
        doAnswer(call -> { f.metricStore.clear(); f.filters.clear(); return null; }).when(f.factory).clearAll();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> retry = pool.submit(f.service::retryPending);
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            Future<?> reset = pool.submit(controller::reset);
            assertTrue(resetReachedService.await(5, TimeUnit.SECONDS));
            assertFalse(reset.isDone(), "reset must drain the active store write");
            releaseWrite.countDown();
            retry.get(5, TimeUnit.SECONDS);
            reset.get(5, TimeUnit.SECONDS);
            f.service.retryPending();
            assertEquals(0, f.service.pendingSamples());
            f.stats("reset", TIME, 0, 0);
            assertTrue(f.filters.keys().isEmpty());
            sink.before = (key, datum) -> {};
            sink.after = (key, datum) -> {};
            f.put("reset", "ERROR", "3", 11.0);
            f.ingest(TIME, "INFO");
            f.stats("reset", TIME, 11, 1);
        } finally {
            releaseWrite.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void resetPausesConcurrentIngestionDuringWipeAndResumesSameInstance() throws Exception {
        f.put("reset", "ERROR", "3", 7.0);
        CountDownLatch wiping = new CountDownLatch(1);
        CountDownLatch finishWipe = new CountDownLatch(1);
        doAnswer(call -> { wiping.countDown(); await(finishWipe); f.metricStore.clear(); f.filters.clear(); return null; })
                .when(f.factory).clearAll();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> reset = pool.submit(controller(f.service)::reset);
            assertTrue(wiping.await(5, TimeUnit.SECONDS));
            int attempts = sink.attempts.size();
            f.ingest(TIME, "INFO");
            assertEquals(attempts, sink.attempts.size(), "publication cannot enter the metric sink during reset");
            finishWipe.countDown();
            reset.get(5, TimeUnit.SECONDS);
            f.put("reset", "ERROR", "3", 11.0);
            f.ingest(TIME, "INFO");
            f.stats("reset", TIME, 11, 1);
        } finally {
            finishWipe.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedStorageResetStillResumesAndCancelsOldQueue() {
        f.put("reset", "ERROR", "3", 7.0);
        sink.fail = true;
        f.ingest(TIME, "INFO");
        doThrow(new IllegalStateException("wipe failed")).when(f.factory).clearAll();
        assertThrows(IllegalStateException.class, () -> controller(f.service).reset());
        assertEquals(0, f.service.pendingSamples());
        sink.fail = false;
        f.service.retryPending();
        f.ingest(TIME, "INFO");
        f.stats("reset", TIME, 7, 1);
    }

    @SuppressWarnings("unchecked")
    private EmulatorInfoController controller(Resettable resettable) {
        Instance<Resettable> resettables = mock(Instance.class);
        when(resettables.iterator()).thenAnswer(call -> List.of(resettable).iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        return new EmulatorInfoController(null, null, f.factory, resettables, teardowns, null, null);
    }
}
