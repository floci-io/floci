package io.floci.aws.services.redshift;

import io.floci.aws.services.floci.duck.FlociDuckClient;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class RedshiftUnloadParquetIntegrationTest {
    @Inject
    RedshiftService redshift;
    @Inject
    S3Service s3;
    @Inject
    FlociDuckClient duck;

    @BeforeAll
    static void requireDocker() throws ClassNotFoundException {
        SidecarContainersProfile.requireDocker();
        Class.forName("org.postgresql.Driver");
    }

    @ParameterizedTest
    @Timeout(180)
    @ValueSource(booleans = {false, true})
    void typedParquetRoundTripsOverSimpleAndNamedExtendedQueries(boolean extended) throws Exception {
        String id = "unload-pq-" + UUID.randomUUID().toString().substring(0, 8);
        String bucket = id;
        s3.createBucket(bucket, "us-east-1");
        Cluster cluster = redshift.createCluster(id, "dc2.large", "admin", "Secret123");
        try {
            await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> "available".equals(redshift.describeClusters(id).getFirst().getClusterStatus()));
            String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                    + "/dev?socketTimeout=90&" + (extended ? "prepareThreshold=1" : "preferQueryMode=simple");
            try (Connection connection = DriverManager.getConnection(url, "admin", "Secret123");
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE pq_src (id bigint, amount decimal(18,4), label text, "
                        + "optional text, day date, happened timestamp, active boolean)");
                statement.execute("INSERT INTO pq_src VALUES (9007199254740993, 12345678901234.5678, "
                        + "'comma,quote\"line\nnext', NULL, '2026-11-04', '2026-11-04 12:34:56.123456', true), "
                        + "(2, NULL, '', '', NULL, NULL, false)");
                String unload = "UNLOAD ('SELECT * FROM pq_src ORDER BY id') TO 's3://" + bucket
                        + "/data/' FORMAT AS PARQUET MANIFEST ALLOWOVERWRITE";
                try (PreparedStatement prepared = connection.prepareStatement(unload)) {
                    if (extended) {
                        assertNull(prepared.getMetaData());
                    }
                    prepared.execute();
                    prepared.execute();
                }
                List<Map<String, Object>> rows = duck.query("SELECT id::VARCHAR AS id, amount::VARCHAR AS amount, "
                        + "label, optional, day::VARCHAR AS day, happened::VARCHAR AS happened, active "
                        + "FROM read_parquet('s3://" + bucket + "/data/*.parquet') ORDER BY id::BIGINT", null);
                assertEquals(2, rows.size());
                assertEquals("", rows.get(0).get("label"));
                assertEquals("", rows.get(0).get("optional"));
                assertNull(rows.get(0).get("amount"));
                assertEquals("9007199254740993", rows.get(1).get("id"));
                assertEquals("12345678901234.5678", rows.get(1).get("amount"));
                assertEquals("comma,quote\"line\nnext", rows.get(1).get("label"));
                assertNull(rows.get(1).get("optional"));
                assertEquals("2026-11-04", rows.get(1).get("day"));
                assertEquals("2026-11-04 12:34:56.123456", rows.get(1).get("happened"));
                assertEquals(true, rows.get(1).get("active"));
                S3Object data = s3.listObjects(bucket, "data/", null, 100).stream()
                        .filter(object -> object.getKey().endsWith(".parquet")).findFirst().orElseThrow();
                byte[] bytes = s3.getObject(bucket, data.getKey()).getData();
                assertEquals("PAR1", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
                assertEquals("PAR1", new String(bytes, bytes.length - 4, 4, StandardCharsets.US_ASCII));
                String manifest = new String(s3.getObject(bucket, "data/manifest").getData(), StandardCharsets.UTF_8);
                assertTrue(manifest.contains(data.getKey()));
                assertTrue(manifest.contains(String.valueOf(bytes.length)));
                try (PreparedStatement empty = connection.prepareStatement("UNLOAD ('SELECT * FROM pq_src WHERE false') "
                        + "TO 's3://" + bucket + "/empty/' PARQUET PARALLEL OFF")) {
                    empty.execute();
                }
                List<Map<String, Object>> emptyCount = duck.query("SELECT count(*) AS n FROM read_parquet('s3://"
                        + bucket + "/empty/000.parquet')", null);
                assertEquals(0, ((Number) emptyCount.getFirst().get("n")).intValue());
                assertEquals(7, duck.query("DESCRIBE SELECT * FROM read_parquet('s3://" + bucket
                        + "/empty/000.parquet')", null).size());
                statement.execute("UNLOAD ('SELECT optional FROM pq_src ORDER BY id') TO 's3://"
                        + bucket + "/single-column/' PARQUET");
                List<Map<String, Object>> singleColumn = duck.query("SELECT optional FROM read_parquet('s3://"
                        + bucket + "/single-column/*.parquet')", null);
                assertEquals(2, singleColumn.size());
                assertEquals("", singleColumn.get(0).get("optional"));
                assertNull(singleColumn.get(1).get("optional"));
                statement.execute("UNLOAD ('SELECT id FROM generate_series(1, 250000) AS g(id) ORDER BY id') "
                        + "TO 's3://" + bucket + "/split/' PARQUET PARALLEL OFF MAXFILESIZE 65536 MANIFEST");
                List<S3Object> parts = s3.listObjects(bucket, "split/", null, 100).stream()
                        .filter(object -> object.getKey().endsWith(".parquet")).toList();
                assertTrue(parts.size() > 1, "DuckDB should split at row-group boundaries");
                String splitManifest = new String(s3.getObject(bucket, "split/manifest").getData(), StandardCharsets.UTF_8);
                for (int i = 0; i < parts.size(); i++) {
                    S3Object part = parts.get(i);
                    assertEquals(String.format("split/%03d.parquet", i), part.getKey());
                    assertTrue(splitManifest.contains(part.getKey()));
                    assertTrue(splitManifest.contains(String.valueOf(part.getSize())));
                }
                List<Map<String, Object>> splitRows = duck.query("SELECT count(*) AS n, count(DISTINCT id) AS unique_ids "
                        + "FROM read_parquet('s3://" + bucket + "/split/*.parquet')", null);
                assertEquals(250000, ((Number) splitRows.getFirst().get("n")).intValue());
                assertEquals(250000, ((Number) splitRows.getFirst().get("unique_ids")).intValue());
                assertUnsupportedTypes(connection, statement, url, bucket);
                SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(
                        "UNLOAD ('SELECT missing FROM pq_src') TO 's3://" + bucket + "/bad/' PARQUET"));
                assertEquals("42703", rejected.getSQLState());
                try (ResultSet result = statement.executeQuery("SELECT 1")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        } finally {
            redshift.deleteCluster(id);
            for (S3Object object : s3.listObjects(bucket, null, null, 1000)) {
                s3.deleteObject(bucket, object.getKey());
            }
            s3.deleteBucket(bucket);
        }
    }
    private void assertUnsupportedTypes(Connection connection, Statement statement, String url, String bucket) throws Exception {
        int backendPid;
        try (ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(pid.next());
            backendPid = pid.getInt(1);
        }
        String numericUnload = "UNLOAD ('SELECT amount::numeric AS total FROM pq_src') TO 's3://"
                + bucket + "/unsupported/' PARQUET";
        SQLException numeric = assertThrows(SQLException.class, () -> statement.execute(numericUnload));
        assertEquals("0A000", numeric.getSQLState());
        assertTrue(numeric.getMessage().contains("column \"total\""));
        assertTrue(numeric.getMessage().contains("NUMERIC without declared precision and scale"));
        assertTrue(numeric.getMessage().contains("DECIMAL(p,s)"));
        // Observe the rejected connection before sending it another command that could finish its transaction.
        try (Connection observer = DriverManager.getConnection(url, "admin", "Secret123");
             Statement inspection = observer.createStatement();
             ResultSet state = inspection.executeQuery("SELECT xact_start IS NULL FROM pg_stat_activity WHERE pid = " + backendPid)) {
            assertTrue(state.next());
            assertTrue(state.getBoolean(1), "Type rejection must not leave an implicit transaction open");
        }
        String uuidUnload = "UNLOAD ('SELECT NULL::uuid AS payload') TO 's3://" + bucket + "/unsupported/' PARQUET";
        SQLException uuid = assertThrows(SQLException.class, () -> statement.execute(uuidUnload));
        assertEquals("0A000", uuid.getSQLState());
        assertTrue(uuid.getMessage().contains("column \"payload\""));
        assertTrue(uuid.getMessage().contains("UUID (OID 2950)"));
        assertTrue(uuid.getMessage().contains("VARCHAR"));
        assertTrue(s3.listObjects(bucket, "unsupported/", null, 100).isEmpty());
        connection.setAutoCommit(false);
        try {
            statement.execute("SELECT 1");
            SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(numericUnload));
            assertEquals("0A000", rejected.getSQLState());
            SQLException failedTransaction = assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
            assertEquals("25P02", failedTransaction.getSQLState());
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        statement.execute("SELECT 1");
    }

}
