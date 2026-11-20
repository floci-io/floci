package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DsqlDatabasesTest {

    @Test
    void dockerBackendDescribesTodaysDatabase() {
        DsqlDatabases.Backend backend = new DsqlDatabases.Backend("h", 1, "postgres", "dbowner", null,
                RoleNames.IDENTITY, BackendCredentials.TRUST);

        assertEquals("jdbc:postgresql://h:1/postgres", backend.jdbcUrl());
        assertEquals("admin", RoleNames.IDENTITY.toBackend("admin"));
        assertEquals("admin", RoleNames.IDENTITY.toLogical("admin"));
        assertTrue(RoleNames.IDENTITY.isIdentity());
    }

    @Test
    void backendDescriptionHidesTheSuperuserPassword() {
        DsqlDatabases.Backend backend = new DsqlDatabases.Backend("h", 1, "postgres", "floci", "s3cr3t-Pa55",
                RoleNames.IDENTITY, BackendCredentials.TRUST);

        assertFalse(backend.toString().contains("s3cr3t-Pa55"), backend.toString());
        assertTrue(backend.toString().contains("superuserPassword=***"), backend.toString());
        DsqlEndpoint.Route route = new DsqlEndpoint.Route("c.dsql", "arn", "us-east-1", "000000000000", backend);
        assertFalse(route.toString().contains("s3cr3t-Pa55"), route.toString());
    }

    @Test
    void aTrustedDatabaseWantsNoPassword() {
        assertNull(BackendCredentials.TRUST.passwordFor("x"));
    }

    @Test
    void aServerNotYetAcceptingConnectionsIsWaitedFor() throws SQLException {
        Connection server = mock(Connection.class);
        AtomicInteger attempts = new AtomicInteger();

        Connection opened = DsqlDatabases.awaitConnection(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new SQLException("the database system is starting up", "57P03");
            }
            return server;
        }, Duration.ofSeconds(10), Duration.ofMillis(1), true, "The server");

        assertSame(server, opened);
        assertEquals(3, attempts.get());
    }

    @Test
    void aRefusedLoginIsNotRetriedWhenItIsFinal() {
        AtomicInteger attempts = new AtomicInteger();
        long started = System.nanoTime();

        SQLException refused = assertThrows(SQLException.class, () -> DsqlDatabases.awaitConnection(() -> {
            attempts.incrementAndGet();
            throw new SQLException("password authentication failed for user \"postgres\"", "28P01");
        }, Duration.ofSeconds(90), Duration.ofMillis(250), true, "The server"));

        assertEquals("28P01", refused.getSQLState());
        assertEquals(1, attempts.get());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "it fails at once");
    }

    @Test
    void aRefusedLoginIsTriedAgainWhenItIsNotFinal() throws SQLException {
        // The Docker backend's wait, which tries a refused login again as it always did.
        Connection server = mock(Connection.class);
        AtomicInteger attempts = new AtomicInteger();

        Connection opened = DsqlDatabases.awaitConnection(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new SQLException("role \"dbowner\" does not exist", "28000");
            }
            return server;
        }, Duration.ofSeconds(10), Duration.ofMillis(1), false, "The database");

        assertSame(server, opened);
        assertEquals(3, attempts.get());
    }

    @Test
    void aServerThatNeverAnswersFailsAtTheDeadlineWithItsLastError() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException last = new SQLException("Connection refused", "08001");

        SQLException refused = assertThrows(SQLException.class, () -> DsqlDatabases.awaitConnection(() -> {
            attempts.incrementAndGet();
            throw last;
        }, Duration.ofMillis(100), Duration.ofMillis(10), true, "The external PostgreSQL server db.example:5432"));

        assertEquals("The external PostgreSQL server db.example:5432 did not let Floci in within 0 s: Connection"
                + " refused", refused.getMessage());
        assertEquals("08001", refused.getSQLState());
        assertSame(last, refused.getCause());
        assertTrue(attempts.get() > 1, "it was tried again");
    }

    @Test
    void aZeroTimeoutStillTriesOnce() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(SQLException.class, () -> DsqlDatabases.awaitConnection(() -> {
            attempts.incrementAndGet();
            throw new SQLException("Connection refused", "08001");
        }, Duration.ZERO, DsqlDatabases.READY_INTERVAL, true, "The server"));

        assertEquals(1, attempts.get());
    }

    @Test
    void bothBackendsTryEvery250Milliseconds() {
        assertEquals(Duration.ofMillis(250), DsqlDatabases.READY_INTERVAL);
    }
}
