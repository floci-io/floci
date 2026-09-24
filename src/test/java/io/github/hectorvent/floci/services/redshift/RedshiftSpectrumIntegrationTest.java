package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftSpectrumIntegrationTest {

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    private String clusterId;
    private String bucket;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift Spectrum integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        if (bucket != null) {
            s3Service.deleteObject(bucket, "events/part-1.csv");
            s3Service.deleteObject(bucket, "invalid/part-1.csv");
            s3Service.deleteBucket(bucket);
        }
    }

    @Test
    @Timeout(60)
    void queriesCsvExternalTableThroughRedshiftWireProxy() throws SQLException {
        bucket = "spectrum-it-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        clusterId = "it-spectrum-" + System.nanoTime();
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
            Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'");
            SQLException duplicateSchema = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE EXTERNAL SCHEMA analytics FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'"));
            assertEquals("42P06", duplicateSchema.getSQLState());
            statement.execute("CREATE EXTERNAL TABLE analytics.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");
            try (ResultSet rows = statement.executeQuery("SELECT id, name FROM analytics.events")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("id"));
                assertEquals("Alice", rows.getString("name"));
                assertTrue(rows.next());
                assertEquals(2, rows.getInt("id"));
                assertEquals("Bob", rows.getString("name"));
                assertTrue(!rows.next());
            }
            s3Service.putObject(bucket, "invalid/part-1.csv", "invalid,Bad\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", null);
            statement.execute("CREATE EXTERNAL TABLE analytics.invalid_events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/invalid/'");
            SQLException invalidRow = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT * FROM analytics.invalid_events"));
            assertEquals("22000", invalidRow.getSQLState());
            try (ResultSet healthCheck = statement.executeQuery("SELECT 1")) {
                assertTrue(healthCheck.next());
                assertEquals(1, healthCheck.getInt(1));
            }
        }
    }

    @Test
    @Timeout(60)
    void queriesCsvExternalTableThroughExtendedQueryProtocol() throws SQLException {
        bucket = "spectrum-it-ext-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n".getBytes(StandardCharsets.UTF_8), "text/csv", null);
        clusterId = "it-spectrum-ext-" + System.nanoTime();
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?socketTimeout=20&loginTimeout=20";
        try (Connection connection = Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
            Statement setup = connection.createStatement()) {
            setup.execute("CREATE EXTERNAL SCHEMA analytics_ext FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'");
            setup.execute("CREATE EXTERNAL TABLE analytics_ext.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");
            // Phase 1 does not support bind-parameterized Spectrum predicates (SpectrumQueryClassifier
            // rejects them), so this exercises Extended Query with a literal predicate: pgjdbc still
            // uses Parse/Bind/Describe/Execute/Sync for PreparedStatement even without a '?'.
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(
                    "SELECT id, name FROM analytics_ext.events WHERE id = 1")) {
                // Forces pgjdbc to send Describe('S', ...) (Describe Statement, not just Describe
                // Portal): the response must be ParameterDescription followed by RowDescription/NoData.
                assertEquals(0, prepared.getParameterMetaData().getParameterCount());
                try (ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt("id"));
                    assertEquals("Alice", rows.getString("name"));
                    assertTrue(!rows.next());
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void pagesSpectrumRowsAcrossMultipleExecutesWhenFetchSizeIsSet() throws SQLException {
        bucket = "spectrum-it-fetch-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "events/part-1.csv",
                "id,name\n1,Alice\n2,Bob\n3,Carol\n4,Dave\n5,Eve\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        clusterId = "it-spectrum-fetch-" + System.nanoTime();
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?socketTimeout=20&loginTimeout=20";
        try (Connection connection = Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
             Statement setup = connection.createStatement()) {
            setup.execute("CREATE EXTERNAL SCHEMA analytics_fetch FROM DATA CATALOG DATABASE 'dev' IAM_ROLE 'role'");
            setup.execute("CREATE EXTERNAL TABLE analytics_fetch.events (id INTEGER, name VARCHAR) "
                    + "STORED AS TEXTFILE LOCATION 's3://" + bucket + "/events/' "
                    + "TBLPROPERTIES ('skip.header.line.count'='1')");

            // Fetch size only becomes a real server-side cursor (Execute with maxRows > 0, answered
            // with PortalSuspended) in pgjdbc when autoCommit is off.
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement prepared = connection.prepareStatement(
                    "SELECT id, name FROM analytics_fetch.events")) {
                prepared.setFetchSize(2);
                List<Integer> ids = new ArrayList<>();
                try (ResultSet rows = prepared.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getInt("id"));
                    }
                }
                assertEquals(List.of(1, 2, 3, 4, 5), ids);
            }
            connection.commit();
        }
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, username, password), Objects::nonNull);
    }
}
