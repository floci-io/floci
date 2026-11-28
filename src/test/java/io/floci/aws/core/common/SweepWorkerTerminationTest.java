package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SweepWorkerTerminationTest {
    @Test
    void stopWaitsForTheWorkerToExitAfterTheExecutorTerminates() throws Exception {
        CountDownLatch swept = new CountDownLatch(1);
        CountDownLatch executorExited = new CountDownLatch(1);
        CountDownLatch releaseThread = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        ThreadFactory factory = task -> {
            Thread thread = Thread.ofPlatform().daemon(true).unstarted(() -> {
                try {
                    task.run();
                } finally {
                    Thread.interrupted(); // shutdownNow may have interrupted this thread.
                    executorExited.countDown();
                    awaitRelease(releaseThread);
                }
            });
            workerThread.set(thread);
            return thread;
        };
        SweepWorker worker = new SweepWorker("sweep-tail", 3600, true, now -> swept.countDown(), factory);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            try {
                worker.start();
                assertTrue(swept.await(5, TimeUnit.SECONDS));
                Future<?> stopped = caller.submit(worker::stop);
                assertTrue(executorExited.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> stopped.get(250, TimeUnit.MILLISECONDS),
                        "stop returned while the worker thread was still alive");
                releaseThread.countDown();
                stopped.get(5, TimeUnit.SECONDS);
                assertFalse(workerThread.get().isAlive());
            } finally {
                releaseThread.countDown();
                worker.stop();
            }
        }
    }

    @Test
    void stopReturnsWithinItsBudgetWhenTheSweepIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        SweepWorker worker = new SweepWorker("uncooperative-sweep", true, 3600, true, now -> {
            workerThread.set(Thread.currentThread());
            entered.countDown();
            awaitRelease(release);
        });
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            try {
                worker.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                caller.submit(worker::stop).get(8, TimeUnit.SECONDS);
                assertTrue(workerThread.get().isAlive());
            } finally {
                release.countDown();
                worker.stop();
            }
        }
    }

    @Test
    void aSweepCanStopItsOwnWorker() throws Exception {
        AtomicReference<SweepWorker> owner = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("self-stopping-sweep", true, 3600, true, now -> {
            owner.get().stop();
            stopped.countDown();
        });
        owner.set(worker);
        try {
            worker.start();
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
        } finally {
            worker.stop();
        }
    }

    @Test
    void stopPreservesTheCallersInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SweepWorker worker = new SweepWorker("interrupted-caller", true, 3600, true, now -> {
            entered.countDown();
            awaitRelease(release);
        });
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            worker.stop();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            worker.stop();
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // Only the test releases this worker, even after shutdown interrupts it.
            }
        }
    }
}
