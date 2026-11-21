package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingStoreAssertionsTest {

    @Test
    void rejectsAnOperationThatFinishesBeforeTheStoreIsReleased() {
        ParkingStore<String> store = new ParkingStore<>();
        store.parkNextWrite();

        AssertionError failure = assertThrows(AssertionError.class,
                () -> assertOperationWaitsForParkedStore(store,
                        () -> store.put("key", "value"), () -> { }));

        assertTrue(failure.getMessage().startsWith(
                "second operation must wait for the parked operation"));
    }

    @Test
    @Timeout(20)
    void rejectsAnOperationThatNeverBlocks() {
        ParkingStore<String> store = new ParkingStore<>();
        store.parkNextWrite();

        AssertionError failure = assertThrows(AssertionError.class,
                () -> assertOperationWaitsForParkedStore(store,
                        () -> store.put("key", "value"),
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                Thread.onSpinWait();
                            }
                        }, Duration.ofMillis(20)));

        assertTrue(failure.getMessage().startsWith(
                "second operation never reached [BLOCKED, WAITING]"));
    }
}
