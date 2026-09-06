package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftInterceptorIntegrationTest {

    @Inject
    RedshiftService service;

    @Inject
    S3Service s3;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift interceptor integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            return exit == 0;
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

    private static String jdbcUrl(Cluster c) {
        // Use 127.0.0.1 explicitly instead of c.getEndpoint().getAddress() to avoid UnknownHostException
        // in CI environments where floci.emulator.hostname is set to host.docker.internal.
        // preferQueryMode=simple forces pgjdbc to use the simple query protocol ('Q' messages)
        // rather than extended query protocol ('P'/'B'/'E'/'S' messages).
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
            return DriverManager.getConnection(jdbcUrl(cluster), username, password); // throw original
        }
    }

    @Test
    void rewritesCreateTableDdlOverSimpleQueryProtocol() throws SQLException {
        clusterId = "it-interceptor-create";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

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
    void rewritesAlterTableDdlOverSimpleQueryProtocol() throws SQLException {
        clusterId = "it-interceptor-alter";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
            Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id int)");
            st.execute("ALTER TABLE t ADD COLUMN note varchar(20) ENCODE lzo;");
            st.execute("INSERT INTO t VALUES (1, 'test-note');");
            try (ResultSet rs = st.executeQuery("SELECT id, note FROM t WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("test-note", rs.getString(2));
            }
        }
    }

    @Test
    void copyFromS3SingleObjectLoadsRows() throws Exception {
        clusterId = "it-copy-single";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-it";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "people/p1.txt",
                "1|alice\n2|bob\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE people (id int, name text)");
            c.createStatement().execute("COPY people FROM 's3://redshift-copy-it/people/p1.txt'");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM people")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void copyFromS3PrefixConcatenatesObjects() throws Exception {
        clusterId = "it-copy-prefix";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-it-prefix";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "d/a", "1|a\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        s3.putObject(bucket, "d/b", "2|b\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE t (id int, v text)");
            c.createStatement().execute("COPY t FROM 's3://redshift-copy-it-prefix/d/'");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM t")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void copyFromS3GzipObjectLoadsRows() throws Exception {
        clusterId = "it-copy-gzip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-it-gzip";
        s3.createBucket(bucket, "us-east-1");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            gz.write("10|x\n11|y\n".getBytes(StandardCharsets.UTF_8));
        }
        s3.putObject(bucket, "g/data.gz", raw.toByteArray(), "application/gzip", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE g (id int, v text)");
            c.createStatement().execute("COPY g FROM 's3://redshift-copy-it-gzip/g/data.gz' GZIP");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM g")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void copyFromMissingObjectSurfacesASqlError() throws Exception {
        clusterId = "it-copy-missing";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-it-missing";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE t2 (id int)");
            SQLException ex = assertThrows(
                    SQLException.class,
                    () -> c.createStatement().execute("COPY t2 FROM 's3://redshift-copy-it-missing/does/not/exist'"));
            assertTrue(
                    ex.getMessage().toLowerCase().contains("not found"), ex.getMessage());
        }
    }

    @Test
    void copyFromMissingObjectInTransactionAbortsTransaction() throws Exception {
        clusterId = "it-copy-tx-abort";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-it-tx";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE tx_test (id int)");
            c.setAutoCommit(false);
            Statement st = c.createStatement();
            st.execute("INSERT INTO tx_test VALUES (1)");
            assertThrows(
                    SQLException.class,
                    () -> st.execute("COPY tx_test FROM 's3://redshift-copy-it-tx/does/not/exist'"));
            // The backend must now be in the aborted transaction state ('E').
            // Subsequent statements in this transaction block must fail.
            assertThrows(
                    SQLException.class,
                    () -> st.execute("INSERT INTO tx_test VALUES (2)"));
            c.rollback();
            c.setAutoCommit(true);
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM tx_test")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void unloadToS3PrefixWritesRowsAsObjects() throws Exception {
        clusterId = "it-unload-basic";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-unload-it";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE u_src (id int, name text)");
            c.createStatement().execute("INSERT INTO u_src VALUES (1, 'alice'), (2, 'bob')");
            c.createStatement().execute(
                    "UNLOAD ('select id, name from u_src order by id') TO 's3://redshift-unload-it/out/'");
        }

        List<S3Object> objs =
                s3.listObjects(bucket, "out/", null, 100);
        assertTrue(objs.size() >= 1, "UNLOAD must write at least one object");
        StringBuilder all = new StringBuilder();
        objs.stream()
                .sorted(Comparator.comparing(S3Object::getKey))
                // listObjects returns metadata-only entries; fetch each body with getObject.
                .forEach(o -> all.append(new String(
                        s3.getObject(bucket, o.getKey()).getData(), StandardCharsets.UTF_8)));
        assertEquals("1|alice\n2|bob\n", all.toString());
    }

    @Test
    void unloadGzipWritesCompressedObject() throws Exception {
        clusterId = "it-unload-gzip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-unload-it-gzip";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE g_src (id int)");
            c.createStatement().execute("INSERT INTO g_src VALUES (7), (8)");
            c.createStatement().execute("UNLOAD ('select id from g_src order by id') TO 's3://redshift-unload-it-gzip/g/' GZIP");
        }

        var objs = s3.listObjects(bucket, "g/", null, 100);
        assertEquals(1, objs.size());
        assertTrue(objs.get(0).getKey().endsWith(".gz"), objs.get(0).getKey());
        byte[] data = s3.getObject(bucket, objs.get(0).getKey()).getData();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            in.transferTo(raw);
        }
        assertEquals("7\n8\n", raw.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unloadWithManifestWritesAManifestObject() throws Exception {
        clusterId = "it-unload-manifest";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-unload-it-manifest";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE m_src (id int)");
            c.createStatement().execute("INSERT INTO m_src VALUES (1)");
            c.createStatement().execute("UNLOAD ('select id from m_src') TO 's3://redshift-unload-it-manifest/m/' MANIFEST");
        }

        var manifest = s3.getObject(bucket, "m/manifest");
        assertNotNull(manifest);
        String json = new String(manifest.getData(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"entries\""), json);
        assertTrue(json.contains("s3://redshift-unload-it-manifest/m/"), json);
    }

    @Test
    void unloadIntoNonEmptyPrefixWithoutAllowOverwriteRaisesSqlError() throws Exception {
        clusterId = "it-unload-overwrite";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-unload-it-ow";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "o/existing", "x".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE o_src (id int)");
            c.createStatement().execute("INSERT INTO o_src VALUES (1)");
            SQLException ex = assertThrows(SQLException.class,
                    () -> c.createStatement().execute("UNLOAD ('select id from o_src') TO 's3://redshift-unload-it-ow/o/'"));
            assertTrue(ex.getMessage().toLowerCase().contains("allowoverwrite"), ex.getMessage());
        }

        // With ALLOWOVERWRITE the same statement succeeds.
        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute(
                    "UNLOAD ('select id from o_src') TO 's3://redshift-unload-it-ow/o/' ALLOWOVERWRITE");
        }
    }
}
