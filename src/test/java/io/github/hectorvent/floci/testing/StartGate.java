package io.github.hectorvent.floci.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs tasks on their own threads, all released together, so a race in the code under test has its best chance. */
public final class StartGate {

    private StartGate() {}

    /**
     * What the tasks returned, in task order, with {@code null} for a task that threw, and what the
     * throwing tasks threw.
     */
    public record Results<T>(List<T> values, List<Throwable> errors) {}

    /**
     * Runs {@code task} once per index from 0 to {@code threadCount - 1}, each on its own thread, and
     * waits up to 30 seconds for all of them.
     */
    public static <T> Results<T> runConcurrently(int threadCount, IntFunction<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        Object[] values = new Object[threadCount];
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Throwable failure = null;
        try {
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        values[index] = task.apply(index);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(30, TimeUnit.SECONDS),
                    "concurrent tasks did not complete within 30s");
        } catch (InterruptedException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS),
                        "thread pool did not terminate");
            } catch (InterruptedException | RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (failure != null) {
                    synchronized (errors) {
                        for (Throwable workerFailure : errors) {
                            if (workerFailure != failure) {
                                failure.addSuppressed(workerFailure);
                            }
                        }
                    }
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<T> results = (List<T>) Arrays.asList(values);
        return new Results<>(results, List.copyOf(errors));
    }
}
