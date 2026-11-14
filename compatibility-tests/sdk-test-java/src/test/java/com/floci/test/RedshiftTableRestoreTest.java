package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.CreateClusterRequest;
import software.amazon.awssdk.services.redshift.model.CreateClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterRequest;
import software.amazon.awssdk.services.redshift.model.DeleteClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.DescribeClustersRequest;
import software.amazon.awssdk.services.redshift.model.DescribeTableRestoreStatusRequest;
import software.amazon.awssdk.services.redshift.model.Endpoint;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.RestoreTableFromClusterSnapshotRequest;
import software.amazon.awssdk.services.redshift.model.TableRestoreStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redshift Table Restore")
class RedshiftTableRestoreTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password123";
    private static final String DATABASE = "dev";

    private static RedshiftClient client;
    private static String clusterId;
    private static String snapshotId;

    @BeforeAll
    static void setup() {
        client = TestFixtures.redshiftClient();
    }

    @AfterAll
    static void cleanup() {
        if (client == null) {
            return;
        }
        try {
            if (snapshotId != null) {
                client.deleteClusterSnapshot(
                        DeleteClusterSnapshotRequest.builder().snapshotIdentifier(snapshotId).build());
            }
            if (clusterId != null) {
                client.deleteCluster(DeleteClusterRequest.builder()
                        .clusterIdentifier(clusterId).skipFinalClusterSnapshot(true).build());
            }
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Restore one table of a snapshot under a new name, and refuse what the snapshot does not hold")
    void restoreTableFromClusterSnapshot() throws Exception {
        clusterId = TestFixtures.uniqueName("rs-table");
        snapshotId = TestFixtures.uniqueName("rs-table-snap");
        client.createCluster(CreateClusterRequest.builder()
                .clusterIdentifier(clusterId)
                .nodeType("dc2.large")
                .masterUsername(USERNAME)
                .masterUserPassword(PASSWORD)
                .build());
        Endpoint endpoint = client.describeClusters(
                DescribeClustersRequest.builder().clusterIdentifier(clusterId).build()).clusters().get(0).endpoint();

        try (Connection connection = awaitPostgresConnection(endpoint.address(), endpoint.port());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INT, note VARCHAR(40))");
            statement.execute("INSERT INTO orders VALUES (1, 'first'), (2, 'second')");
            client.createClusterSnapshot(CreateClusterSnapshotRequest.builder()
                    .snapshotIdentifier(snapshotId).clusterIdentifier(clusterId).build());
            statement.execute("INSERT INTO orders VALUES (3, 'after the snapshot')");

            TableRestoreStatus accepted = client.restoreTableFromClusterSnapshot(
                    RestoreTableFromClusterSnapshotRequest.builder()
                            .clusterIdentifier(clusterId)
                            .snapshotIdentifier(snapshotId)
                            .sourceDatabaseName(DATABASE)
                            .sourceTableName("orders")
                            .newTableName("orders_restored")
                            .build()).tableRestoreStatus();
            assertThat(accepted.tableRestoreRequestId()).isNotBlank();
            assertThat(accepted.sourceSchemaName()).isEqualTo("public");
            assertThat(accepted.newTableName()).isEqualTo("orders_restored");

            TableRestoreStatus described = awaitFinished(accepted.tableRestoreRequestId());
            assertThat(described.statusAsString()).isEqualTo("SUCCEEDED");
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM orders_restored")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }

            TableRestoreStatus duplicate = awaitFinished(client.restoreTableFromClusterSnapshot(
                    RestoreTableFromClusterSnapshotRequest.builder()
                            .clusterIdentifier(clusterId)
                            .snapshotIdentifier(snapshotId)
                            .sourceDatabaseName(DATABASE)
                            .sourceTableName("orders")
                            .newTableName("orders_restored")
                            .build()).tableRestoreStatus().tableRestoreRequestId());
            assertThat(duplicate.statusAsString()).isEqualTo("FAILED");
            assertThat(duplicate.message()).isNotBlank();
        }

        assertThat(client.describeTableRestoreStatus(DescribeTableRestoreStatusRequest.builder()
                .clusterIdentifier(clusterId).build()).tableRestoreStatusDetails()).hasSize(2);
        assertThatThrownBy(() -> client.restoreTableFromClusterSnapshot(
                RestoreTableFromClusterSnapshotRequest.builder()
                        .clusterIdentifier(clusterId)
                        .snapshotIdentifier(snapshotId)
                        .sourceDatabaseName(DATABASE)
                        .sourceTableName("missing")
                        .newTableName("copy")
                        .build()))
                .isInstanceOf(RedshiftException.class)
                .hasMessageContaining("does not exist");
    }

    private static TableRestoreStatus awaitFinished(String requestId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        TableRestoreStatus status;
        do {
            status = client.describeTableRestoreStatus(DescribeTableRestoreStatusRequest.builder()
                    .clusterIdentifier(clusterId).tableRestoreRequestId(requestId).build())
                    .tableRestoreStatusDetails().get(0);
            if (!"PENDING".equals(status.statusAsString()) && !"IN_PROGRESS".equals(status.statusAsString())) {
                return status;
            }
            Thread.sleep(500);
        } while (Instant.now().isBefore(deadline));
        return status;
    }

    private static Connection awaitPostgresConnection(String host, int port) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        SQLException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                Properties properties = new Properties();
                properties.setProperty("user", USERNAME);
                properties.setProperty("password", PASSWORD);
                properties.setProperty("sslmode", "disable");
                properties.setProperty("connectTimeout", "5");
                return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + DATABASE,
                        properties);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        throw last != null ? last : new SQLException("Timed out waiting for PostgreSQL at " + host + ":" + port);
    }
}
