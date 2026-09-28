package io.github.hectorvent.floci.services.dynamodb;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DynamoDbTtlServiceTest {

    @Test
    void aFailingSweepDoesNotEscapeTheScheduledTask() {
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        doThrow(new IllegalStateException("sweep failed")).when(dynamoDbService).deleteExpiredItems();
        DynamoDbTtlService ttlService = new DynamoDbTtlService(dynamoDbService);

        assertDoesNotThrow(ttlService::sweep);
    }

    @Test
    void aPausedSweepDeletesNothingUntilResumed() {
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        DynamoDbTtlService ttlService = new DynamoDbTtlService(dynamoDbService);

        ttlService.pause();
        ttlService.sweep();
        verify(dynamoDbService, never()).deleteExpiredItems();

        ttlService.resume();
        ttlService.sweep();
        verify(dynamoDbService).deleteExpiredItems();
    }

    @Test
    void pauseWaitsForTheSweepInProgress() throws Exception {
        assertWaitsForTheSweepInProgress(DynamoDbTtlService::pause);
    }

    @Test
    void stopWaitsForASweepInProgress() throws Exception {
        assertWaitsForTheSweepInProgress(DynamoDbTtlService::stop);
    }

    /** Also asserts that no sweep deletes anything after {@code call} returns. */
    private static void assertWaitsForTheSweepInProgress(Consumer<DynamoDbTtlService> call) throws Exception {
        DynamoDbService dynamoDbService = mock(DynamoDbService.class);
        CountDownLatch sweeping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sweeping.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(dynamoDbService).deleteExpiredItems();
        DynamoDbTtlService ttlService = new DynamoDbTtlService(dynamoDbService);
        Thread sweep = new Thread(ttlService::sweep);
        sweep.start();
        assertTrue(sweeping.await(10, TimeUnit.SECONDS));

        Thread caller = new Thread(() -> call.accept(ttlService));
        caller.start();
        awaitBlocked(caller);
        release.countDown();
        caller.join(10_000);
        sweep.join(10_000);

        assertEquals(Thread.State.TERMINATED, caller.getState());
        assertEquals(Thread.State.TERMINATED, sweep.getState());
        ttlService.sweep();
        verify(dynamoDbService).deleteExpiredItems();
    }

    /** Observes the calling thread reach the sweep's monitor instead of sleeping to hope for it. */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail("the call returned or never reached the sweep's monitor while a sweep was running");
            }
            Thread.onSpinWait();
        }
    }
}
