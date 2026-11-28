package io.floci.aws.core.common;

import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SweepWorkerTerminationTest {
    @Test
    void stopWaitsForTheWorkerToExitAfterTheExecutorTerminates() throws Exception {
        AtomicReference<SweepWorker> owner = new AtomicReference<>();
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
        SweepWorker worker = new SweepWorker("sweep-tail", 3600, true, now -> owner.get().stop(),
                factory, Duration.ofSeconds(5));
        owner.set(worker);
        Thread caller = Thread.ofPlatform().unstarted(worker::stop);
        try {
            worker.start();
            assertTrue(executorExited.await(5, TimeUnit.SECONDS));
            caller.start();
            assertEquals(Thread.State.TIMED_WAITING, ThreadStates.awaitState(caller,
                    Set.of(Thread.State.TIMED_WAITING, Thread.State.TERMINATED), Duration.ofSeconds(5),
                    "caller joining the worker thread"));
            releaseThread.countDown();
            assertTrue(caller.join(Duration.ofSeconds(5)));
            assertFalse(workerThread.get().isAlive());
        } finally {
            releaseThread.countDown();
            worker.stop();
        }
    }

    @Test
    void stopReturnsWithinItsBudgetWhenTheSweepIgnoresInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        SweepWorker worker = new SweepWorker("uncooperative-sweep", 3600, true, now -> {
            workerThread.set(Thread.currentThread());
            entered.countDown();
            awaitRelease(release);
        }, Thread.ofPlatform().daemon(true).factory(), Duration.ofMillis(25));
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            try {
                worker.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                caller.submit(worker::stop).get(2, TimeUnit.SECONDS);
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
