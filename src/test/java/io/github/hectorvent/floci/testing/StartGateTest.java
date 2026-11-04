package io.github.hectorvent.floci.testing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StartGateTest {

    @Test
    void interruptedWaitRetainsWorkerFailure() throws Exception {
        AssertionError workerFailure = new AssertionError("worker failed during interrupted wait");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<StartGate.Results<Integer>> operation = new FutureTask<>(
                () -> StartGate.runConcurrently(2, index -> {
                    entered.countDown();
                    if (index == 0) {
                        throw workerFailure;
                    }
                    awaitRelease(release);
                    return index;
                }));
        Thread caller = new Thread(operation, "start-gate-interrupted-caller");
        caller.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> operation.get(10, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(workerFailure));
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive());
        }
    }

    @Test
    void completedWorkersStillReturnValuesAndErrors() throws InterruptedException {
        AssertionError workerFailure = new AssertionError("completed failure");
        StartGate.Results<Integer> result = StartGate.runConcurrently(3, index -> {
            if (index == 1) {
                throw workerFailure;
            }
            return index;
        });
        assertEquals(Arrays.asList(0, null, 2), result.values());
        assertEquals(1, result.errors().size());
        assertSame(workerFailure, result.errors().getFirst());
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException expected) {
            // StartGate must cancel blocked workers after timeout or caller interruption.
            Thread.currentThread().interrupt();
        }
    }
}
