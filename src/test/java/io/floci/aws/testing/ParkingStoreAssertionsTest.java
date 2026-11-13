package io.floci.aws.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkingStoreAssertionsTest {

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
                        }));

        assertTrue(failure.getMessage().startsWith(
                "second operation must block or wait for the parked operation"));
    }
}
