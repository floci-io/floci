package io.floci.aws.services.dsql.dataplane;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.dsql.DsqlClusterFixture;
import io.floci.aws.services.dsql.DsqlDatabasesProfile;
import io.floci.aws.services.dsql.DsqlWireClient;
import io.floci.aws.testing.MutableClock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.dsql.DsqlWireClient.query;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transaction lives 300 seconds on DSQL, ended as DSQL ends it (measured, eu-west-1, 2026-10-21):
 * the client's next message after the limit, a ROLLBACK included, gets the age error and the
 * connection closes; a transaction idle past the limit and 15 seconds more is ended the same way
 * unasked; a statement that runs across the limit ends in the age error and keeps nothing, inside a
 * transaction block or not; and a failed transaction idle as long is closed. The test moves the
 * endpoint's clock instead of waiting, and makes a statement run long by having it wait for a row
 * another connection holds.
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlTransactionAgeIntegrationTest {

    private static final Duration PAST_THE_LIMIT = Duration.ofSeconds(301);
    private static final Duration PAST_THE_GRACE = Duration.ofSeconds(316);

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @Inject
    MutableClock clock;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    private DsqlClusterFixture cluster;

    @BeforeAll
    void createCluster() throws Exception {
        cluster = new DsqlClusterFixture(httpPort, endpoint);
        cluster.create(dockerClient);
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE aged (id int PRIMARY KEY, n int)");
            statement.execute("INSERT INTO aged VALUES (1, 0)");
        }
    }

    @AfterAll
    void deleteCluster() {
        cluster.delete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extended", "simple"})
    void aMessagePastTheLimitEndsTheTransactionAndTheConnection(String queryMode) throws Exception {
        try (Connection connection = connect(queryMode); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            clock.advance(PAST_THE_LIMIT);
            SQLException expired = assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));

            assertMentions(expired, "transaction age limit of 300s exceeded");
            assertFalse(connection.isValid(2));
        }
    }

    @Test
    void anIdleTransactionPastTheLimitAndItsGraceIsEndedUnasked() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            clock.advance(PAST_THE_GRACE);
            endpoint.runDueChecks();

            SQLException ended = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 2").close());
            assertMentions(ended, "Current transaction age 316s exceeds 300s");
            assertFalse(connection.isValid(2));
        }
    }

    @Test
    void aStatementRunningPastTheLimitEndsInTheAgeErrorAndFailsTheTransaction() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            Object outcome = pastTheLimit(() -> statement.executeQuery("SELECT n FROM aged WHERE id = 1 FOR UPDATE"));

            assertMentions(assertInstanceOf(SQLException.class, outcome), "transaction age limit of 300s exceeded");
            connection.rollback();
            connection.setAutoCommit(true);
            try (ResultSet row = statement.executeQuery("SELECT 3")) {
                assertTrue(row.next());
                assertEquals(3, row.getInt(1));
            }
        }
    }

    /** A statement past the limit fails outside a transaction block too, and what it wrote is not kept (measured). */
    @Test
    void aStatementPastTheLimitFailsOutsideATransactionToo() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            Object write = pastTheLimit(() -> statement.executeUpdate("UPDATE aged SET n = 7 WHERE id = 1"));
            assertMentions(assertInstanceOf(SQLException.class, write), "transaction age limit of 300s exceeded");

            Object read = pastTheLimit(() -> statement.executeQuery("SELECT n FROM aged WHERE id = 1 FOR UPDATE"));
            assertMentions(assertInstanceOf(SQLException.class, read), "transaction age limit of 300s exceeded");
        }
        assertEquals(0, aged());
    }

    /** A statement past the limit fails the rest of its query, a COMMIT in it included, and keeps nothing (measured). */
    @Test
    void aCommitAfterAStatementPastTheLimitKeepsNothing() throws Exception {
        try (DsqlWireClient client = cluster.wireClient("admin", cluster.adminToken())) {
            Object afterRead = pastTheLimit(() -> {
                client.send(query("BEGIN; INSERT INTO aged VALUES (2, 8);"
                        + " SELECT n FROM aged WHERE id = 1 FOR UPDATE; COMMIT"));
                return client.untilReady();
            });
            assertTrue(((List<?>) afterRead).contains("ERROR 54000"), String.valueOf(afterRead));
            // As on DSQL, the connection is left in the failed transaction.
            client.send(query("ROLLBACK"));
            client.untilReady();

            Object afterWrite = pastTheLimit(() -> {
                client.send(query("BEGIN; UPDATE aged SET n = 9 WHERE id = 1; COMMIT"));
                return client.untilReady();
            });
            assertTrue(((List<?>) afterWrite).contains("ERROR 54000"), String.valueOf(afterWrite));
            client.send(query("ROLLBACK"));
            client.untilReady();
        }
        assertEquals(0, aged());
    }

    @Test
    void aFailedTransactionIdlePastTheLimitAndItsGraceIsClosed() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 / 0").close());

            clock.advance(PAST_THE_GRACE);
            endpoint.runDueChecks();

            SQLException closed = assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));
            assertMentions(closed, "terminating connection due to idle-in-transaction timeout");
            assertFalse(connection.isValid(2));
        }
    }

    /**
     * A client message after a failed transaction's first idle wait still lets it be closed once it
     * idles as long again.
     */
    @Test
    void aFailedTransactionIsClosedOnceIdleAfterEachMessage() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1 / 0").close());

            clock.advance(Duration.ofSeconds(200));
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 2").close());
            clock.advance(Duration.ofSeconds(200));
            endpoint.runDueChecks();
            clock.advance(Duration.ofSeconds(116));
            endpoint.runDueChecks();

            SQLException closed = assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));
            assertMentions(closed, "terminating connection due to idle-in-transaction timeout");
        }
    }

    /** A BEGIN queued behind a long statement starts when it can run, not when it was sent. */
    @Test
    void aBeginQueuedBehindALongStatementStartsWhenItRuns() throws Exception {
        try (DsqlWireClient client = cluster.wireClient("admin", cluster.adminToken());
             Connection holder = holdRow()) {
            client.send(query("SELECT n FROM aged WHERE id = 1 FOR UPDATE"), query("BEGIN"), query("SELECT 1"));
            awaitWaitingForTheRow();
            clock.advance(Duration.ofSeconds(200));
            holder.rollback();
            assertEquals(List.of("SELECT 1"), client.untilReady());
            assertEquals(List.of("BEGIN"), client.untilReady());
            assertEquals(List.of("SELECT 1"), client.untilReady());

            // Past the limit since the BEGIN was sent, within it since it ran.
            clock.advance(Duration.ofSeconds(150));
            client.send(query("SELECT 2"));
            assertEquals(List.of("SELECT 1"), client.untilReady());
            client.send(query("ROLLBACK"));
            assertEquals(List.of("ROLLBACK"), client.untilReady());
        }
    }

    /** A transaction a prepared BEGIN opens starts when the BEGIN runs, not when it was prepared. */
    @Test
    void aPreparedBeginStartsItsTransactionWhenItRuns() throws Exception {
        try (Connection connection = connect("extended");
             PreparedStatement begin = connection.prepareStatement("BEGIN");
             Statement statement = connection.createStatement()) {
            begin.execute();
            statement.execute("ROLLBACK");

            clock.advance(Duration.ofSeconds(250));
            begin.execute();
            clock.advance(Duration.ofSeconds(100));
            try (ResultSet row = statement.executeQuery("SELECT 1")) {
                assertTrue(row.next());
            }
            statement.execute("ROLLBACK");
        }
    }

    /** A statement a test lets run past the age limit; what it returns, or throws, is its outcome. */
    private interface Running {
        Object run() throws Exception;
    }

    /**
     * Runs {@code statement}, which waits for the row another connection holds, moves the clock
     * past the age limit while it waits, lets the checks due then run, and lets the statement end:
     * its result, or what it threw.
     */
    private Object pastTheLimit(Running statement) throws Exception {
        try (Connection holder = holdRow()) {
            CompletableFuture<Object> outcome = CompletableFuture.supplyAsync(() -> {
                try {
                    return statement.run();
                } catch (Exception e) {
                    return e;
                }
            });
            awaitWaitingForTheRow();
            clock.advance(PAST_THE_LIMIT);
            endpoint.runDueChecks();
            // The statement is stopped at the limit; only then does the row go.
            outcome.get(30, TimeUnit.SECONDS);
            try {
                holder.rollback();
            } catch (SQLException ended) {
                // The holder's own transaction is past the limit too, so its ROLLBACK ends its
                // connection, which lets the row go all the same.
            }
            return outcome.get(30, TimeUnit.SECONDS);
        }
    }

    /** A connection holding row 1 of {@code aged} until it rolls back. */
    private Connection holdRow() throws Exception {
        Connection holder = connect("extended");
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.executeQuery("SELECT n FROM aged WHERE id = 1 FOR UPDATE").close();
        }
        return holder;
    }

    /** Waits until a statement waits for a lock, read outside any transaction so each look is fresh. */
    private void awaitWaitingForTheRow() {
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            try (Connection connection = connect("extended"); Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery(
                         "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")) {
                return row.next() && row.getInt(1) > 0;
            }
        });
    }

    /** The sum of {@code aged}'s counters, 0 while nothing a test wrote was kept. */
    private int aged() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT coalesce(sum(n), 0) FROM aged")) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    /** Whether {@code error} or an exception chained to it says {@code text}. */
    private static void assertMentions(SQLException error, String text) {
        List<String> messages = messages(error);
        assertTrue(messages.stream().anyMatch(message -> message.contains(text)), String.join(" | ", messages));
    }

    /**
     * The messages and details of an exception and of every exception chained to it, each once:
     * the driver can chain an exception back to one before it.
     */
    private static List<String> messages(SQLException error) {
        List<String> messages = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable each = error; each != null && seen.add(each); each = each.getCause() != null ? each.getCause()
                : each instanceof SQLException sql ? sql.getNextException() : null) {
            messages.add(String.valueOf(each.getMessage()));
            if (each instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                messages.add(psql.getSQLState() + " " + psql.getServerErrorMessage().getMessage() + " "
                        + psql.getServerErrorMessage().getDetail());
            }
        }
        return messages;
    }

    private Connection connect(String queryMode) throws Exception {
        Properties properties = cluster.properties("admin", cluster.adminToken());
        properties.setProperty("preferQueryMode", queryMode);
        properties.setProperty("prepareThreshold", "1");
        return DriverManager.getConnection(cluster.url(), properties);
    }
}
