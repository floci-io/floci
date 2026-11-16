package io.floci.aws.services.rds.container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresStartupSignalTest {
    @Test
    void ignoresTheTemporaryUnixOnlyServer() {
        RdsContainerManager.PostgresStartupSignal startup = new RdsContainerManager.PostgresStartupSignal();
        startup.accept("LOG: listening on Unix socket");
        startup.accept("LOG: database system is ready to accept connections");
        assertFalse(startup.signal.tryAcquire());
        startup.accept("LOG: listening on IPv4 address");
        assertFalse(startup.signal.tryAcquire());
        startup.accept("LOG: database system is ready to accept connections");
        assertTrue(startup.signal.tryAcquire());
    }

    @Test
    void retainsAnEarlySignalUntilTheSqlRetryWaits() {
        RdsContainerManager.PostgresStartupSignal startup = new RdsContainerManager.PostgresStartupSignal();
        startup.accept("LOG: listening on IPv6 address");
        startup.accept("LOG: database system is ready to accept connections");
        assertTrue(startup.signal.tryAcquire());
        assertFalse(startup.signal.tryAcquire());
    }

    @Test
    void duplicateReadyLinesCannotConsumeTheRetryBudget() {
        RdsContainerManager.PostgresStartupSignal startup = new RdsContainerManager.PostgresStartupSignal();
        startup.accept("LOG: listening on IPv4 address");
        startup.accept("LOG: database system is ready to accept connections");
        startup.accept("LOG: database system is ready to accept connections");
        assertTrue(startup.signal.tryAcquire());
        assertFalse(startup.signal.tryAcquire());
    }

    @Test
    void anUnsupportedLogFormatKeepsTheTimedFallback() {
        RdsContainerManager.PostgresStartupSignal startup = new RdsContainerManager.PostgresStartupSignal();
        startup.accept("custom database is ready");
        assertFalse(startup.signal.tryAcquire());
    }
}
