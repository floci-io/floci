package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RedshiftSpectrumIntegrationTest.SpectrumSidecarProfile.class)
class RedshiftSpectrumIntegrationTest {
    private static final String ROLE_NAME = "SpectrumItRole";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"redshift.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @Inject
    RedshiftService redshiftService;
    @Inject
    S3Service s3Service;
    @Inject
    GlueService glueService;
    @Inject
    IamService iamService;
    @Inject
    FlociDuckClient duckClient;

    private String clusterId;
    private String bucket;
    private String glueDatabase;

    public static final class SpectrumSidecarProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.test-port", "4566");
        }
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift Spectrum integration tests");
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
        if (glueDatabase != null) {
            for (String tableName : List.of("events", "events__stg", "more")) {
                try {
                    glueService.deleteTable(glueDatabase, tableName);
                } catch (RuntimeException ignored) {
                    // Cleanup is best effort for tables a test did not create.
                }
            }
            glueService.deleteDatabase(glueDatabase);
        }
        if (bucket != null) {
            for (String key : List.of("events/part-1.csv", "events/part-2.csv", "events/part-1.parquet",
                    "events__stg/part-1.csv", "more/part-1.csv")) {
                try {
                    s3Service.deleteObject(bucket, key);
                } catch (RuntimeException ignored) {
                    // Cleanup is best effort for keys a test did not create.
                }
            }
            s3Service.deleteBucket(bucket);
        }
    }

    private Cluster newCluster(String scenario) {
        if (iamService.findRole("000000000000", ROLE_NAME).isEmpty()) {
            iamService.createRole(ROLE_NAME, "/", TRUST_POLICY, null, 0, null);
            iamService.putRolePolicy(ROLE_NAME, "AllowS3", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Action":["s3:GetObject","s3:ListBucket"],"Resource":"*"}]}""");
        }
        clusterId = "it-spectrum-" + scenario + "-" + System.nanoTime();
        return redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123",
                null, List.of(), List.of(ROLE_ARN));
    }

    private void seedCsvTable(String tableName, String csv) {
        seedCsvTable(tableName, csv, "1");
    }

    private void seedCsvTable(String tableName, String csv, String skipHeaderLines) {
        if (bucket == null) {
            bucket = "spectrum-it-" + System.nanoTime();
            s3Service.createBucket(bucket, "us-east-1");
        }
        s3Service.putObject(bucket, tableName + "/part-1.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv", null);
        if (glueDatabase == null) {
            glueDatabase = "spectrum_it_" + System.nanoTime();
            glueService.createDatabase(new Database(glueDatabase));
        }
        Column id = new Column();
        id.setName("id");
        id.setType("int");
        Column name = new Column();
        name.setName("name");
        name.setType("string");
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(List.of(id, name));
        descriptor.setLocation("s3://" + bucket + "/" + tableName + "/");
        descriptor.setInputFormat("org.apache.hadoop.mapred.TextInputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
        descriptor.setSerdeInfo(serde);
        Table table = new Table();
        table.setName(tableName);
        table.setTableType("EXTERNAL_TABLE");
        table.setParameters(Map.of("skip.header.line.count", skipHeaderLines));
        table.setStorageDescriptor(descriptor);
        glueService.createTable(glueDatabase, table);
    }

    private void seedParquetTable() {
        bucket = "spectrum-it-" + System.nanoTime();
        s3Service.createBucket(bucket, "us-east-1");
        duckClient.execute("COPY (SELECT 7 AS id, 'Parquet' AS name) TO 's3://" + bucket
                + "/events/part-1.parquet' (FORMAT PARQUET)", null, null, "000000000000");
        glueDatabase = "spectrum_it_" + System.nanoTime();
        glueService.createDatabase(new Database(glueDatabase));
        Column id = new Column();
        id.setName("id");
        id.setType("int");
        Column name = new Column();
        name.setName("name");
        name.setType("string");
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(List.of(id, name));
        descriptor.setLocation("s3://" + bucket + "/events/");
        descriptor.setInputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        descriptor.setOutputFormat("org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat");
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe");
        descriptor.setSerdeInfo(serde);
        Table table = new Table();
        table.setName("events");
        table.setTableType("EXTERNAL_TABLE");
        table.setStorageDescriptor(descriptor);
        glueService.createTable(glueDatabase, table);
    }

    private String createSchemaSql(String schema) {
        return "CREATE EXTERNAL SCHEMA " + schema + " FROM DATA CATALOG DATABASE '" + glueDatabase
                + "' IAM_ROLE '" + ROLE_ARN + "'";
    }

    private static Connection connect(Cluster cluster) {
        return connect(cluster, "admin", "Secret123");
    }

    private static Connection connect(Cluster cluster, String username, String password) {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort()
                + "/dev?socketTimeout=30&loginTimeout=20";
        return Awaitility.await().atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(500)).ignoreExceptions()
                .until(() -> DriverManager.getConnection(url, username, password), Objects::nonNull);
    }

    @Test
    @Timeout(120)
    void queriesGlueTableAndJoinsItWithNativeTable() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n2,Bob\n");
        Cluster cluster = newCluster("join");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE TABLE owners (id int, team text)");
            statement.execute("INSERT INTO owners VALUES (2, 'core')");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT e.name, o.team FROM analytics.events e JOIN owners o ON o.id = e.id")) {
                assertTrue(rows.next());
                assertEquals("Bob", rows.getString("name"));
                assertEquals("core", rows.getString("team"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    @Timeout(120)
    void reloadsChangedS3DataAndSupportsExtendedBindParameters() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("fresh");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            try (ResultSet first = statement.executeQuery("SELECT count(*) FROM analytics.events")) {
                assertTrue(first.next());
                assertEquals(1, first.getInt(1));
            }
            s3Service.putObject(bucket, "events/part-2.csv", "id,name\n2,Bob\n"
                    .getBytes(StandardCharsets.UTF_8), "text/csv", null);
            try (PreparedStatement prepared = connection.prepareStatement(
                    "SELECT name FROM analytics.events WHERE id = ?")) {
                prepared.setInt(1, 2);
                try (ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("Bob", rows.getString(1));
                }
            }
        }
    }

    @Test
    @Timeout(120)
    void reloadsExternalTableInPlaceAndKeepsDependentViewsCurrent() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("view-reload");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            try (ResultSet initial = statement.executeQuery("SELECT count(*) FROM analytics.events")) {
                assertTrue(initial.next());
                assertEquals(1, initial.getInt(1));
            }
            statement.execute("CREATE VIEW dependent AS SELECT id, name FROM analytics.events");

            s3Service.putObject(bucket, "events/part-2.csv", "id,name\n2,Bob\n"
                    .getBytes(StandardCharsets.UTF_8), "text/csv", null);

            try (ResultSet reloaded = statement.executeQuery("SELECT count(*) FROM analytics.events")) {
                assertTrue(reloaded.next());
                assertEquals(2, reloaded.getInt(1));
            }
            try (ResultSet dependent = statement.executeQuery("SELECT id, name FROM dependent ORDER BY id")) {
                assertTrue(dependent.next());
                assertEquals(1, dependent.getInt("id"));
                assertEquals("Alice", dependent.getString("name"));
                assertTrue(dependent.next());
                assertEquals(2, dependent.getInt("id"));
                assertEquals("Bob", dependent.getString("name"));
                assertFalse(dependent.next());
            }
        }
    }

    @Test
    @Timeout(120)
    void createsGlueTablesListsMetadataAndRejectsExternalWrites() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("ddl");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE EXTERNAL TABLE analytics.more (id INT, name VARCHAR) "
                    + "ROW FORMAT DELIMITED FIELDS TERMINATED BY ',' STORED AS TEXTFILE "
                    + "LOCATION 's3://" + bucket + "/events/'");
            assertEquals("s3://" + bucket + "/events/",
                    glueService.getTable(glueDatabase, "more").getStorageDescriptor().getLocation());
            try (ResultSet schemas = statement.executeQuery(
                    "SELECT databasename FROM svv_external_schemas WHERE schemaname = 'analytics'")) {
                assertTrue(schemas.next());
                assertEquals(glueDatabase, schemas.getString(1));
            }
            SQLException write = assertThrows(SQLException.class,
                    () -> statement.execute("INSERT INTO analytics.events VALUES (9, 'no')"));
            assertEquals("0A000", write.getSQLState());
            statement.execute("DROP TABLE analytics.more");
        }
    }

    @Test
    @Timeout(120)
    void nonMasterUserCanReadExternalCatalogViewsWithoutWriteAccessToInternalTables() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("catalog-reader");
        try (Connection admin = connect(cluster); Statement statement = admin.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE USER spectrum_reader WITH PASSWORD 'ReadOnly123!'");
        }

        try (Connection reader = connect(cluster, "spectrum_reader", "ReadOnly123!");
             Statement statement = reader.createStatement()) {
            try (ResultSet schemas = statement.executeQuery(
                    "SELECT databasename FROM svv_external_schemas WHERE schemaname = 'analytics'")) {
                assertTrue(schemas.next());
                assertEquals(glueDatabase, schemas.getString(1));
            }
            SQLException write = assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM floci_internal.external_schemas"));
            assertEquals("42501", write.getSQLState());
        }
    }

    @Test
    @Timeout(120)
    void preservesMaterializedGlueTableWhoseNameMatchesTheOldStagingSuffix() throws SQLException {
        seedCsvTable("events", "id,name\n1,Primary\n");
        seedCsvTable("events__stg", "id,name\n2,Preserved\n");
        Cluster cluster = newCluster("staging-collision");

        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            assertEquals("Preserved", queryOnlyValue(statement, "SELECT name FROM analytics.events__stg"));
            assertEquals("Primary", queryOnlyValue(statement, "SELECT name FROM analytics.events"));
            assertEquals("Preserved", queryOnlyValue(statement, "SELECT name FROM analytics.events__stg"));
        }
    }

    private static String queryOnlyValue(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertFalse(rows.next());
            return value;
        }
    }

    @Test
    @Timeout(120)
    void pagesGlueSpectrumRowsAcrossMultipleExecutesWhenFetchSizeIsSet() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n2,Bob\n3,Carol\n4,Dave\n5,Eve\n");
        Cluster cluster = newCluster("fetch-size");
        try (Connection connection = connect(cluster); Statement setup = connection.createStatement()) {
            setup.execute(createSchemaSql("analytics"));
            connection.setAutoCommit(false);
            try (PreparedStatement prepared = connection.prepareStatement("SELECT id, name FROM analytics.events")) {
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

    @Test
    @Timeout(120)
    void queriesParquetGlueTableThroughDuckDbMaterializer() throws SQLException {
        seedParquetTable();
        Cluster cluster = newCluster("parquet");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            try (ResultSet rows = statement.executeQuery("SELECT id, name FROM analytics.events")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt("id"));
                assertEquals("Parquet", rows.getString("name"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    @Timeout(120)
    void nativeDropStatementsReachPostgresThroughExtendedQuery() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("native-drop");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE TABLE native_t (id int)");
            statement.execute("CREATE SCHEMA local_schema");
            // A PreparedStatement runs over the Extended Query protocol, where a native DROP that only
            // looks like external DDL used to be answered with 0A000 instead of being forwarded.
            try (PreparedStatement dropTable = connection.prepareStatement("DROP TABLE public.native_t");
                 PreparedStatement dropSchema = connection.prepareStatement("DROP SCHEMA local_schema")) {
                dropTable.execute();
                dropSchema.execute();
            }
            try (ResultSet gone = statement.executeQuery("SELECT to_regclass('public.native_t') IS NULL")) {
                assertTrue(gone.next());
                assertTrue(gone.getBoolean(1));
            }
        }
    }

    @Test
    @Timeout(120)
    void dropSchemaIsRestrictedByDefaultAndCascadesOnlyWhenAskedTo() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("restrict");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE VIEW dependent AS SELECT id FROM analytics.events");

            SQLException refused = assertThrows(SQLException.class, () -> statement.execute("DROP SCHEMA analytics"));
            assertEquals("2BP01", refused.getSQLState());
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM analytics.events")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet view = statement.executeQuery("SELECT count(*) FROM dependent")) {
                assertTrue(view.next());
            }

            statement.execute("DROP SCHEMA analytics CASCADE");
            SQLException viewGone = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM dependent"));
            assertEquals("42P01", viewGone.getSQLState());
        }
    }

    @Test
    @Timeout(120)
    void recreatedSchemaReloadsItsTablesEvenThoughGlueAndS3AreUnchanged() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("recreate");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.executeQuery("SELECT count(*) FROM analytics.events").close();
            statement.execute("DROP SCHEMA analytics");
            statement.execute(createSchemaSql("analytics"));

            try (ResultSet rows = statement.executeQuery("SELECT name FROM analytics.events")) {
                assertTrue(rows.next());
                assertEquals("Alice", rows.getString(1));
            }
        }
    }

    @Test
    @Timeout(120)
    void headerlessCsvTableJoinsWithNativeTableUsingItsDeclaredColumns() throws SQLException {
        seedCsvTable("events", "1,Alice\n2,Bob\n", "0");
        Cluster cluster = newCluster("headerless");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE TABLE owners (id int, team text)");
            statement.execute("INSERT INTO owners VALUES (1, 'core')");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT e.name, o.team FROM analytics.events e JOIN owners o ON o.id = e.id")) {
                assertTrue(rows.next());
                assertEquals("Alice", rows.getString("name"));
                assertEquals("core", rows.getString("team"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    @Timeout(120)
    void tableAliasNamedLikeAnExternalSchemaIsNotLoadedAsATable() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("alias");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE TABLE owners (id int)");
            statement.execute("INSERT INTO owners VALUES (5)");
            try (ResultSet rows = statement.executeQuery("SELECT analytics.id FROM owners AS analytics")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
            }
        }
    }

    @Test
    @Timeout(120)
    void externalWriteBehindANativeWriteInABatchIsRejected() throws SQLException {
        seedCsvTable("events", "id,name\n1,Alice\n");
        Cluster cluster = newCluster("batch-write");
        try (Connection connection = connect(cluster); Statement statement = connection.createStatement()) {
            statement.execute(createSchemaSql("analytics"));
            statement.execute("CREATE TABLE owners (id int)");

            SQLException write = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE owners SET id = 1; DELETE FROM analytics.events"));

            assertEquals("0A000", write.getSQLState());
        }
    }
}
