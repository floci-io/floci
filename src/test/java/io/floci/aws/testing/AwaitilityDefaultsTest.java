package io.floci.aws.testing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class AwaitilityDefaultsTest {

    private static final Duration DEFAULT_POLL_DELAY = Duration.ofMillis(100);

    @Test
    void awaitChecksAHeldConditionWithoutSleepingFirst() {
        assertFirstCheckIsImmediate();
    }

    /**
     * With Awaitility's own default every await sleeps 100 ms before its first check, so the
     * fastest of several attempts tells the two apart without depending on a quiet machine.
     */
    static void assertFirstCheckIsImmediate() {
        long fastest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 5; attempt++) {
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(5)).until(() -> true);
            fastest = Math.min(fastest, System.nanoTime() - start);
        }
        assertTrue(fastest < DEFAULT_POLL_DELAY.toNanos(),
                "an await on a held condition took " + Duration.ofNanos(fastest).toMillis()
                        + " ms: AwaitilityDefaults did not reach this class loader's Awaitility");
    }
}
