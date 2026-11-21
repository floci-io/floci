package io.floci.aws.testing;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.ThreadStates.awaitState;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Assertions for operations that must serialize around an armed storage call. */
public final class ParkingStoreAssertions {

    private ParkingStoreAssertions() {}

    /**
     * Runs the first operation until it parks, asserts the second waits, then releases the store
     * and waits for both results. The caller arms the store and checks the final resource state.
     * Releases the store and shuts down the executor even when an assertion fails.
     */
    public static void assertOperationWaitsForParkedStore(ParkingStore<?> store, Runnable parkedOperation,
                                                         Runnable waitingOperation)
            throws InterruptedException, ExecutionException, TimeoutException {
        assertOperationWaitsForParkedStore(store, parkedOperation, waitingOperation, Duration.ofSeconds(5));
    }

    /** Overrides the worker-state observation window; startup, result and cleanup bounds stay unchanged. */
    static void assertOperationWaitsForParkedStore(ParkingStore<?> store, Runnable parkedOperation,
                                                  Runnable waitingOperation, Duration blockingTimeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> parked = executor.submit(parkedOperation);
            assertTrue(store.awaitParked(), "first operation reached the armed store");
            AtomicReference<Thread> waitingThread = new AtomicReference<>();
            CountDownLatch waitingStarted = new CountDownLatch(1);
            Future<?> waiting = executor.submit(() -> {
                waitingThread.set(Thread.currentThread());
                waitingStarted.countDown();
                waitingOperation.run();
            });
            assertTrue(waitingStarted.await(5, TimeUnit.SECONDS), "second operation started");
            awaitState(waitingThread.get(), EnumSet.of(Thread.State.BLOCKED, Thread.State.WAITING),
                    blockingTimeout, "second operation");
            assertFalse(waiting.isDone(), "second operation must wait for the parked operation");
            store.release();
            parked.get(5, TimeUnit.SECONDS);
            waiting.get(5, TimeUnit.SECONDS);
        } finally {
            store.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
