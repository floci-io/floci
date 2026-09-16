package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated, non-CDI unit tests for {@link VtlExecutionGuard}, run directly against the thread's
 * deadline state without going through Velocity or Quarkus, for fast deterministic coverage of
 * the execution-time deadline mechanism itself.
 */
class VtlExecutionGuardTest {

    @AfterEach
    void clearAnyLeftoverDeadline() {
        // Guard against a failed assertion mid-test leaking a deadline into later tests on the
        // same thread (JUnit5 reuses threads across test methods by default).
        VtlExecutionGuard.end();
    }

    @Test
    void checkDeadline_withNoActiveDeadline_doesNothing() {
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void checkDeadline_beforeDeadlinePasses_doesNothing() throws InterruptedException {
        VtlExecutionGuard.begin(Duration.ofSeconds(30));
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void checkDeadline_afterDeadlinePasses_throws() throws InterruptedException {
        VtlExecutionGuard.begin(Duration.ofMillis(1));
        Thread.sleep(20);
        VtlLimitExceededException ex =
                assertThrows(VtlLimitExceededException.class, VtlExecutionGuard::checkDeadline);
        assertNotNullMessage(ex);
    }

    @Test
    void end_clearsDeadline_soCheckDeadlineNoLongerThrows() throws InterruptedException {
        VtlExecutionGuard.begin(Duration.ofMillis(1));
        Thread.sleep(20);
        VtlExecutionGuard.end();
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    @Test
    void begin_replacesAnyPreviousDeadline() throws InterruptedException {
        VtlExecutionGuard.begin(Duration.ofMillis(1));
        Thread.sleep(20);
        // A fresh begin() must reset the deadline, not extend the expired one.
        VtlExecutionGuard.begin(Duration.ofSeconds(30));
        assertDoesNotThrow(VtlExecutionGuard::checkDeadline);
    }

    private static void assertNotNullMessage(VtlLimitExceededException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            throw new AssertionError("expected a descriptive message on VtlLimitExceededException");
        }
    }
}
