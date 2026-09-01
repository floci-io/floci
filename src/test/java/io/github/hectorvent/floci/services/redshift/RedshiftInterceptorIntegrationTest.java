package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftInterceptorIntegrationTest {

    @Inject
    RedshiftService redshiftService;

    @Inject
    S3Service s3Service;

    private String clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            try {
                redshiftService.deleteCluster(clusterId);
            } catch (Exception ignored) {
            }
        }
    }

    private static String jdbcUrl(Cluster c) {
        // preferQueryMode=simple forces pgjdbc onto the Simple Query ('Q') protocol,
        // which is the only protocol the interceptor inspects; with the driver default
        // (extended) a Statement is sent as Parse/Bind/Execute and never intercepted.
        return "jdbc:postgresql://127.0.0.1:" + c.getEndpoint().getPort() + "/dev?preferQueryMode=simple";
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl(cluster), username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl(cluster), username, password);
        }
    }

    @Test
    void testCreateTableWithRedshiftKeywords() throws SQLException {
        clusterId = "it-interceptor-ddl";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int ENCODE az64, d date) DISTSTYLE KEY DISTKEY (id) COMPOUND SORTKEY (d);");
            st.execute("INSERT INTO sales VALUES (1, '2026-01-01');");

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sales")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void testS3CopyFromSingleObjectAndPrefix() throws Exception {
        String bucket = "it-interceptor-bucket-copy";
        s3Service.createBucket(bucket, "us-east-1");
        s3Service.putObject(bucket, "data/part1.csv",
                "1,2026-01-01\n2,2026-01-02\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);

        clusterId = "it-interceptor-copy";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int, d date);");
            st.execute("COPY sales FROM 's3://" + bucket + "/data/' IAM_ROLE 'arn:aws:iam::123456789012:role/RedshiftRole' CSV DELIMITER ',';");

            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sales")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void testS3Unload() throws Exception {
        String bucket = "it-interceptor-bucket-unload";
        s3Service.createBucket(bucket, "us-east-1");

        clusterId = "it-interceptor-unload";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int, d date);");
            st.execute("INSERT INTO sales VALUES (10, '2026-02-01'), (20, '2026-02-02');");

            st.execute("UNLOAD ('SELECT * FROM sales ORDER BY id') TO 's3://" + bucket + "/out/' MANIFEST HEADER;");

            try (InputStream in = s3Service.openObjectStream(bucket, "out/000", null)) {
                assertNotNull(in);
                String data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(data.contains("id"), "Header should contain column name id");
                assertTrue(data.contains("10|2026-02-01"));
                assertTrue(data.contains("20|2026-02-02"));
            }

            try (InputStream in = s3Service.openObjectStream(bucket, "out/manifest", null)) {
                assertNotNull(in);
                String manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(manifest.contains("s3://" + bucket + "/out/000"));
                assertTrue(manifest.contains("entries"));
            }
        }
    }

    @Test
    void testS3CopyMissingObjectReturnsError() throws Exception {
        String bucket = "it-interceptor-bucket-missing";
        s3Service.createBucket(bucket, "us-east-1");

        clusterId = "it-interceptor-missing";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE sales (id int);");

            SQLException ex = assertThrows(SQLException.class, () ->
                    st.execute("COPY sales FROM 's3://" + bucket + "/missing.csv';"));
            assertTrue(ex.getMessage().contains("not found") || ex.getMessage().contains("missing.csv") || ex.getMessage().contains("S3 object"),
                    "Expected missing S3 object message but got: " + ex.getMessage());
        }
    }
}
