package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshift.model.TableRestoreStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restores one table of a snapshot on a real cluster: write rows, snapshot, restore under a new name, read them back. */
@QuarkusTest
class RedshiftTableRestoreIntegrationTest {

    @Inject
    RedshiftService service;

    @Inject
    RedshiftTableRestoreService tableRestores;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Redshift table restore integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            service.deleteCluster(clusterId);
        }
    }

    private static String jdbcUrl(Cluster cluster) {
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl(cluster), username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl(cluster), username, password);
        }
    }

    private RedshiftTableRestoreService.Request restore(String snapshotId, String sourceTable, String newTable) {
        return new RedshiftTableRestoreService.Request(clusterId, snapshotId, "dev", null, sourceTable, null, null,
                newTable, false);
    }

    private TableRestoreStatus finished(String requestId) {
        return Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                () -> tableRestores.describeTableRestoreStatus(clusterId, requestId, null, null).items().getFirst(),
                status -> !TableRestoreStatus.PENDING.equals(status.getStatus())
                        && !TableRestoreStatus.IN_PROGRESS.equals(status.getStatus()));
    }

    private static List<String> notes(Connection connection, String table) throws SQLException {
        List<String> notes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT note FROM " + table + " ORDER BY id")) {
            while (rows.next()) {
                notes.add(rows.getString(1));
            }
        }
        return notes;
    }

    @Test
    void aTableOfASnapshotIsRestoredUnderANewNameWithItsRows() throws Exception {
        clusterId = "tbl-restore-" + UUID.randomUUID().toString().substring(0, 8);
        String snapshotId = clusterId + "-snap";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id integer, note varchar(40))");
            statement.execute("INSERT INTO orders VALUES (1, 'first'), (2, 'it''s, quoted'), (3, 'third')");
            service.createSnapshot(snapshotId, clusterId);
            statement.execute("INSERT INTO orders VALUES (4, 'after the snapshot')");

            TableRestoreStatus accepted = tableRestores.restoreTable(restore(snapshotId, "orders", "orders_restored"));
            assertEquals(TableRestoreStatus.PENDING, accepted.getStatus());
            TableRestoreStatus done = finished(accepted.getTableRestoreRequestId());
            assertEquals(TableRestoreStatus.SUCCEEDED, done.getStatus(), done.getMessage());

            assertEquals(List.of("first", "it's, quoted", "third"), notes(connection, "orders_restored"));
            assertEquals(4, notes(connection, "orders").size(), "the source table is left as it was");

            TableRestoreStatus again = finished(
                    tableRestores.restoreTable(restore(snapshotId, "orders", "orders_restored"))
                            .getTableRestoreRequestId());
            assertEquals(TableRestoreStatus.FAILED, again.getStatus());
            assertTrue(again.getMessage().contains("already exists"), again.getMessage());
            assertEquals(List.of("first", "it's, quoted", "third"), notes(connection, "orders_restored"));
        } finally {
            service.deleteSnapshot(snapshotId);
        }
    }
}
