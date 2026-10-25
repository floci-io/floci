package io.github.hectorvent.floci.services.rds.proxy;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static io.github.hectorvent.floci.testing.RdsRequests.createPostgresInstance;
import static io.github.hectorvent.floci.testing.RdsRequests.deleteInstance;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A client cancels a running query through the proxy, as it does on an RDS for PostgreSQL endpoint. */
@QuarkusTest
@Tag("docker")
class RdsPostgresCancelIntegrationTest {

    private static final String SLEEP = "SELECT pg_sleep(30)";

    @BeforeEach
    void checkDocker() throws InterruptedException {
        assumeDocker();
    }

    @Test
    void aCancelledQueryStopsOnTheInstance() throws Exception {
        String dbId = "pg-cancel-" + Long.toString(System.nanoTime(), 36);
        int port = createPostgresInstance(dbId, "master", "Master123!", false);
        try (Connection connection = connect(port);
             Statement statement = connection.createStatement();
             Connection observer = connect(port)) {
            long started = System.nanoTime();
            CompletableFuture<Void> query = CompletableFuture.runAsync(() -> {
                try {
                    statement.execute(SLEEP);
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> sleeping(observer));

            statement.cancel();

            CompletionException failed = assertThrows(CompletionException.class, query::join);
            assertThat(failed.getCause(), instanceOf(SQLException.class));
            assertThat(((SQLException) failed.getCause()).getSQLState(), equalTo("57014"));
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started), lessThan(10L));
        } finally {
            deleteInstance(dbId);
        }
    }

    private static Connection connect(int port) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "master");
        properties.setProperty("password", "Master123!");
        properties.setProperty("sslmode", "disable");
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/appdb", properties);
    }

    /** Whether the backend runs the sleep, so a cancel sent now has a query to stop. */
    private static boolean sleeping(Connection observer) throws SQLException {
        try (Statement statement = observer.createStatement();
             ResultSet row = statement.executeQuery("SELECT count(*) FROM pg_stat_activity"
                     + " WHERE state = 'active' AND query = '" + SLEEP + "'")) {
            return row.next() && row.getInt(1) > 0;
        }
    }
}
