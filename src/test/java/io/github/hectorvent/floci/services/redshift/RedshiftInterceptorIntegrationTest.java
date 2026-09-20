package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.iam.IamService;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftInterceptorIntegrationTest {

    private static final String REDSHIFT_TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;

    @Inject
    RedshiftService service;

    @Inject
    S3Service s3;

    @Inject
    IamService iamService;

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

    private static String jdbcUrl(Cluster cluster) {
        // Use 127.0.0.1 explicitly instead of c.getEndpoint().getAddress() to avoid UnknownHostException
        // in CI environments where floci.emulator.hostname is set to host.docker.internal.
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
    }

    private static String namedPreparedJdbcUrl(Cluster cluster) {
        return jdbcUrl(cluster) + "?prepareThreshold=1";
    }

    /**
     * Catalog-based column discovery for {@code FORMAT AS JSON 'auto'} without an explicit column
     * list only runs over the Simple Query protocol (see {@code docs/services/redshift.md}), so
     * tests exercising that discovery must force it explicitly: pgjdbc's default query mode is
     * Extended even for a plain {@link java.sql.Statement}.
     */
    private static String simpleQueryJdbcUrl(Cluster cluster) {
        return jdbcUrl(cluster) + "?preferQueryMode=simple";
    }

    private static Connection waitForConnection(Cluster cluster, String username, String password) throws SQLException {
        return waitForConnection(jdbcUrl(cluster), username, password);
    }

    private static Connection waitForConnection(String jdbcUrl, String username, String password) throws SQLException {
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(jdbcUrl, username, password), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(jdbcUrl, username, password); // throw original
        }
    }

    @Test
    void preparedDdlCopyAndUnloadUseDefaultExtendedQuery() throws Exception {
        clusterId = "it-extended-prepared";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-extended";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "copy/data.txt",
                "1|alice\n2|bob\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
                PreparedStatement ddl = connection.prepareStatement(
                        "CREATE TABLE ext_sales (id int ENCODE az64, name text) "
                                + "DISTSTYLE KEY DISTKEY (id)");
                PreparedStatement copy = connection.prepareStatement(
                        "COPY ext_sales FROM 's3://redshift-extended/copy/data.txt'");
                PreparedStatement unload = connection.prepareStatement(
                        "UNLOAD ('select id, name from ext_sales order by id') "
                                + "TO 's3://redshift-extended/unload/' ALLOWOVERWRITE")) {
            ddl.execute();
            copy.execute();
            unload.execute();
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM ext_sales")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }

        List<S3Object> objects = s3.listObjects(bucket, "unload/", null, 100);
        assertEquals(1, objects.size());
        assertEquals("1|alice\n2|bob\n", new String(
                s3.getObject(bucket, objects.get(0).getKey()).getData(), StandardCharsets.UTF_8));
    }

    @Test
    void copyWithIamRoleSucceedsWhenEnforceAuthIsOffEvenWithoutAPolicy() throws Exception {
        clusterId = "it-copy-iam-role-no-enforce";
        String bucket = "redshift-iam-role-no-enforce";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "people/p1.txt",
                "1|alice\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        iamService.createRole("CopyRoleNoPolicy", "/", REDSHIFT_TRUST_POLICY, null, 0, null);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123", null, List.of(),
                List.of("arn:aws:iam::000000000000:role/CopyRoleNoPolicy"));

        try (Connection connection = waitForConnection(cluster, "admin", "Secret123");
                Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TABLE people (id int, name text)");
            try (PreparedStatement copy = connection.prepareStatement(
                    "COPY people FROM 's3://" + bucket + "/people/p1.txt' "
                            + "IAM_ROLE 'arn:aws:iam::000000000000:role/CopyRoleNoPolicy'")) {
                copy.execute();
            }
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT count(*) FROM people")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void namedPreparedCopyAndUnloadCanBeExecutedTwice() throws Exception {
        clusterId = "it-extended-named";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        String bucket = "redshift-extended-named";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "copy/data.txt", "7|seven\n".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        try (Connection connection = waitForConnection(
                namedPreparedJdbcUrl(cluster), "admin", "Secret123")) {
            connection.createStatement().execute("CREATE TABLE named_sales (id int, name text)");
            try (PreparedStatement copy = connection.prepareStatement(
                    "COPY named_sales FROM 's3://redshift-extended-named/copy/data.txt'")) {
                copy.execute();
                copy.execute();
            }
            try (PreparedStatement unload = connection.prepareStatement(
                    "UNLOAD ('select id, name from named_sales order by id') "
                            + "TO 's3://redshift-extended-named/unload/' ALLOWOVERWRITE")) {
                unload.execute();
                unload.execute();
            }
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT count(*) FROM named_sales")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }

        List<S3Object> objects = s3.listObjects(bucket, "unload/", null, 100);
        assertEquals(1, objects.size());
        assertEquals("7|seven\n7|seven\n", new String(
                s3.getObject(bucket, objects.get(0).getKey()).getData(), StandardCharsets.UTF_8));
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
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
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
            });
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

        List<S3Object> objs = s3.listObjects(bucket, "g/", null, 100);
        assertEquals(1, objs.size());
        assertTrue(objs.get(0).getKey().endsWith(".gz"), objs.get(0).getKey());
        byte[] data = s3.getObject(bucket, objs.get(0).getKey()).getData();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
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

        S3Object manifest = s3.getObject(bucket, "m/manifest");
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

    @Test
    void copyFromS3JsonAutoExplicitColumnsLoadsRows() throws Exception {
        clusterId = "it-copy-json-explicit";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-json-explicit";
        s3.createBucket(bucket, "us-east-1");
        String ndjson = "{\"id\": 1, \"name\": \"Alice\", \"score\": 95.5}\n"
                + "{\"id\": 2, \"name\": \"Bob\", \"score\": 82.0}\n";
        s3.putObject(bucket, "data/users.json", ndjson.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE users (id int, name text, score numeric)");
            c.createStatement().execute("COPY users (id, name, score) FROM 's3://" + bucket + "/data/users.json' FORMAT AS JSON 'auto'");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*), sum(score) FROM users")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(177.5, rs.getDouble(2), 0.001);
            }
        }
    }

    @Test
    void copyFromS3JsonAutoWithoutColumnsDiscoversSchema() throws Exception {
        clusterId = "it-copy-json-auto-schema";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-json-auto-schema";
        s3.createBucket(bucket, "us-east-1");
        String ndjson = "{\"id\": 10, \"name\": \"Charlie\", \"note\": \"hello\"}\n"
                + "{\"id\": 20, \"name\": \"David\", \"note\": \"world\"}\n";
        s3.putObject(bucket, "data/items.json", ndjson.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        // Column discovery only runs over Simple Query; force it explicitly since pgjdbc's
        // default query mode is Extended even for a plain Statement.
        try (Connection c = waitForConnection(simpleQueryJdbcUrl(cluster), "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE items (id int, name text, note text)");
            c.createStatement().execute("COPY items FROM 's3://" + bucket + "/data/items.json' FORMAT AS JSON 'auto'");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*), max(id) FROM items")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(20, rs.getInt(2));
            }
        }
    }

    @Test
    void copyFromS3JsonAutoWithoutColumnsOverExtendedQueryFailsClearly() throws Exception {
        clusterId = "it-copy-json-auto-extended-rejects";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-json-auto-extended";
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "data/items.json",
                "{\"id\": 1}\n".getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        // Default connection: pgjdbc's default query mode is Extended even for a plain Statement,
        // where the column list is fixed at Parse time and catalog discovery cannot run.
        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE ext_items (id int)");
            SQLException ex = assertThrows(SQLException.class, () -> c.createStatement().execute(
                    "COPY ext_items FROM 's3://" + bucket + "/data/items.json' FORMAT AS JSON 'auto'"));
            assertTrue(ex.getMessage().contains("explicit column list"));
        }
    }

    @Test
    void copyFromS3JsonAutoGzipLoadsRows() throws Exception {
        clusterId = "it-copy-json-gzip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-copy-json-gzip";
        s3.createBucket(bucket, "us-east-1");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            gz.write("{\"id\": 100, \"val\": \"zipped\"}\n".getBytes(StandardCharsets.UTF_8));
        }
        s3.putObject(bucket, "data/compressed.json.gz", raw.toByteArray(), "application/gzip", Map.of());

        // Column discovery only runs over Simple Query; force it explicitly since pgjdbc's
        // default query mode is Extended even for a plain Statement.
        try (Connection c = waitForConnection(simpleQueryJdbcUrl(cluster), "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE gz_items (id int, val text)");
            c.createStatement().execute("COPY gz_items FROM 's3://" + bucket + "/data/compressed.json.gz' GZIP FORMAT AS JSON 'auto'");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*), max(val) FROM gz_items")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("zipped", rs.getString(2));
            }
        }
    }

    @Test
    void copyFromS3ManifestRoundtrip() throws Exception {
        clusterId = "it-manifest-roundtrip";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-manifest-roundtrip";
        s3.createBucket(bucket, "us-east-1");

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE src (id int, name text)");
            c.createStatement().execute("INSERT INTO src VALUES (1, 'alpha'), (2, 'beta')");

            // UNLOAD with MANIFEST
            c.createStatement().execute("UNLOAD ('select id, name from src order by id') "
                    + "TO 's3://" + bucket + "/export/' MANIFEST ALLOWOVERWRITE");

            // Target table
            c.createStatement().execute("CREATE TABLE dst (id int, name text)");

            // COPY using the generated manifest
            c.createStatement().execute("COPY dst (id, name) FROM 's3://" + bucket + "/export/manifest' MANIFEST");

            try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM dst")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void copyFromS3ManifestMissingMandatoryFails() throws Exception {
        clusterId = "it-manifest-missing";
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        String bucket = "redshift-manifest-missing";
        s3.createBucket(bucket, "us-east-1");
        String manifestJson = "{\"entries\": [{\"url\": \"s3://" + bucket + "/missing.csv\", \"mandatory\": true}]}";
        s3.putObject(bucket, "manifest", manifestJson.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());

        try (Connection c = waitForConnection(cluster, "admin", "Secret123")) {
            c.createStatement().execute("CREATE TABLE t_mand (id int)");
            SQLException ex = assertThrows(SQLException.class,
                    () -> c.createStatement().execute("COPY t_mand FROM 's3://" + bucket + "/manifest' MANIFEST"));
            assertTrue(ex.getMessage().toLowerCase().contains("does not exist") || ex.getMessage().toLowerCase().contains("missing.csv"), ex.getMessage());
        }
    }
}

