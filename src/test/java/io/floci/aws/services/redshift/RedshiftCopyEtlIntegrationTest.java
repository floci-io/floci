package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftCopyEtlIntegrationTest {
    @Inject
    RedshiftService redshift;
    @Inject
    S3Service s3;

    private final String id = "copy-etl-" + UUID.randomUUID().toString().substring(0, 8);
    private Cluster cluster;
    private String url;
    private String user;

    @BeforeAll
    void start() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for a Redshift cluster");
        cluster = redshift.createCluster(id, "dc2.large", "admin", "Secret123");
        url = RedshiftConnections.jdbcUrl(cluster);
        user = "admin";
        s3.createBucket(id, "us-east-1");
    }

    @AfterAll
    void stop() throws Exception {
        if (cluster != null) {
            redshift.deleteCluster(id);
        }
    }

    private Connection connect(String mode) throws SQLException {
        return RedshiftConnections.waitForConnection(url + "?sslmode=disable&gssEncMode=disable&preferQueryMode=" + mode,
                user, "Secret123");
    }

    private String table() {
        return "etl_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String copy(String table, String key, String options) {
        return "COPY " + table + " FROM 's3://" + id + "/" + key + "' " + options;
    }

    private void put(String key, String data) {
        s3.putObject(id, key, data.getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
    }

    private static long count(Statement statement, String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void escapedDecimalsConvertInBothBulkAndTolerantLoads(String mode) throws Exception {
        String table = table();
        put(table, "12\\.345\n\\0612.345\n12\\056345\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (amount decimal(8,2))");
            for (String options : List.of("", "ROUNDEC", "IGNOREALLERRORS", "ROUNDEC IGNOREALLERRORS")) {
                assertEquals(3, statement.executeUpdate(copy(table, table, options)));
                try (ResultSet rows = statement.executeQuery("SELECT min(amount), max(amount) FROM " + table)) {
                    assertTrue(rows.next());
                    String expected = options.contains("ROUNDEC") ? "12.35" : "12.34";
                    assertEquals(expected, rows.getString(1));
                    assertEquals(expected, rows.getString(2));
                }
                statement.execute("TRUNCATE " + table);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void decimalCopyTruncatesByDefaultAndRoundsOnlyWithRoundec(String mode) throws Exception {
        String table = table();
        put(table, "20.259|text\n-20.255|negative\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (amount decimal(8,2), label text)");
            for (String option : List.of("", "ROUNDEC")) {
                assertEquals(2, statement.executeUpdate(copy(table, table, option)));
                try (ResultSet rows = statement.executeQuery("SELECT amount FROM " + table + " ORDER BY amount")) {
                    assertTrue(rows.next());
                    assertEquals(option.isEmpty() ? "-20.25" : "-20.26", rows.getString(1));
                    assertTrue(rows.next());
                    assertEquals(option.isEmpty() ? "20.25" : "20.26", rows.getString(1));
                }
                statement.execute("TRUNCATE " + table);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void ignoreBlankLinesPreservesWhitespaceAndQuotedNewlines(String mode) throws Exception {
        String table = table();
        put(table, "\n\r\n\"hello\n\nworld\"\n   \n\"\"\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (label text)");
            assertEquals(3, statement.executeUpdate(copy(table, table, "CSV IGNOREBLANKLINES")));
            assertEquals(3, count(statement, table));
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table + " WHERE label = E'hello\\n\\nworld'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void loadErrorsSurviveFailedCopyAndExplicitRollback(String mode) throws Exception {
        String table = table();
        put(table, "id|label\n1|good\nbad|broken\n2|last\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, label text)");
            assertThrows(SQLException.class, () -> statement.execute(copy(table, table, "IGNOREHEADER 1")));
            assertEquals(0, count(statement, table));
            connection.setAutoCommit(false);
            assertEquals(2, statement.executeUpdate(copy(table, table, "IGNOREHEADER 1 IGNOREALLERRORS")));
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(0, count(statement, table));
            try (ResultSet rows = statement.executeQuery("SELECT filename, line_number, colname, raw_line, err_code "
                    + "FROM stl_load_errors WHERE filename = 's3://" + id + "/" + table + "'")) {
                for (int error = 0; error < 2; error++) {
                    assertTrue(rows.next());
                    assertEquals("s3://" + id + "/" + table, rows.getString(1));
                    assertEquals(3, rows.getLong(2));
                    assertEquals("id", rows.getString(3).trim());
                    assertEquals("bad|broken", rows.getString(4).trim());
                    assertEquals(1207, rows.getInt(5));
                }
                assertFalse(rows.next());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void maxErrorLoadsGoodRowsAndCountsErrorsAcrossObjects(String mode) throws Exception {
        String table = table();
        String prefix = table + "/";
        put(prefix + "a", "id|name\n1|one\nbad|skip\n");
        put(prefix + "b", "id|name\n2|two\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int NOT NULL, name text)");
            assertEquals(2, statement.executeUpdate(copy(table, prefix, "IGNOREHEADER 1 MAXERROR 2")));
            assertEquals(2, count(statement, table));
            put(prefix + "b", "id|name\n2|two\nnope|skip\n");
            assertThrows(SQLException.class,
                    () -> statement.execute(copy(table, prefix, "IGNOREHEADER 1 MAXERROR 2")));
            assertEquals(2, count(statement, table));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void metadataOptionsLoadTemporalAndMissingFields(String mode) throws Exception {
        String table = table();
        put(table, "1|31/10/2026|abcdef\n2|01/11/2026\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, day date, label varchar(3))");
            assertEquals(2, statement.executeUpdate(copy(table, table,
                    "DATEFORMAT 'DD/MM/YYYY' FILLRECORD TRUNCATECOLUMNS")));
            try (ResultSet rows = statement.executeQuery("SELECT day, label FROM " + table + " ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals("2026-10-31", rows.getString(1));
                assertEquals("abc", rows.getString(2));
                assertTrue(rows.next());
                assertEquals("2026-11-01", rows.getString(1));
                assertEquals("", rows.getString(2));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void badTemporalFieldUsesTheSameErrorBudgetAsConversionErrors(String mode) throws Exception {
        String table = table();
        put(table, "1|31/10/2026\n2|bad-date\nbad-number|01/11/2026\n3|02/11/2026\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, day date)");
            assertEquals(2, statement.executeUpdate(copy(table, table, "DATEFORMAT 'DD/MM/YYYY' MAXERROR 3")));
            assertThrows(SQLException.class,
                    () -> statement.execute(copy(table, table, "DATEFORMAT 'DD/MM/YYYY' MAXERROR 2")));
            assertEquals(2, count(statement, table));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void explicitTransactionRetainsTheCallersSavepointOnLoadFailure(String mode) throws Exception {
        String table = table();
        put(table, "1|good\nbad|first\nbad|second\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, name text)");
            connection.setAutoCommit(false);
            statement.execute("INSERT INTO " + table + " VALUES (99, 'before')");
            Savepoint before = connection.setSavepoint();
            assertThrows(SQLException.class, () -> statement.execute(copy(table, table, "MAXERROR 2")));
            SQLException aborted = assertThrows(SQLException.class, () -> count(statement, table));
            assertEquals("25P02", aborted.getSQLState());
            connection.rollback(before);
            assertEquals(1, count(statement, table));
            connection.rollback();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void savedStatementDiscoversColumnsWhenItExecutesAgain(String mode) throws Exception {
        String table = table();
        put(table, "1|abcdef\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, name varchar(3))");
            try (PreparedStatement prepared = connection.prepareStatement(copy(table, table, "TRUNCATECOLUMNS"))) {
                for (int iteration = 0; iteration < 6; iteration++) {
                    assertEquals(1, prepared.executeUpdate());
                }
                statement.execute("ALTER TABLE " + table + " ALTER COLUMN name TYPE varchar(5)");
                assertEquals(1, prepared.executeUpdate());
                try (ResultSet rows = statement.executeQuery("SELECT max(length(name)) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(5, rows.getInt(1));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void jsonAutoDiscoversColumnsAndSkipsOnlyBadRecords(String mode) throws Exception {
        String table = table();
        put(table, "{\"id\":1,\"name\":\"one\"}\n{broken}\n{\"id\":2,\"name\":\"two\"}\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, name text)");
            assertEquals(2, statement.executeUpdate(copy(table, table, "FORMAT JSON 'auto' MAXERROR 2")));
            assertEquals(2, count(statement, table));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extended"})
    void failedImplicitBatchRollsBackStatementsBeforeAndAfterCopy(String mode) throws Exception {
        String table = table();
        put(table, "1|one\nbad|skip\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, name text)");
            statement.addBatch("INSERT INTO " + table + " VALUES (9, 'before')");
            statement.addBatch(copy(table, table, "MAXERROR 2"));
            statement.addBatch("INSERT INTO " + table + " VALUES ('bad', 'after')");
            assertThrows(BatchUpdateException.class, statement::executeBatch);
            assertEquals(0, count(statement, table));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void copyAndUnloadInTheSameBatchCommitAtTheFinalSync(String mode) throws Exception {
        String table = table();
        put(table, "1|one\nbad|skip\n");
        try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id int, name text)");
            statement.addBatch(copy(table, table, "MAXERROR 2"));
            statement.addBatch("UNLOAD ('SELECT id FROM " + table + "') TO 's3://" + id + "/" + table + "-out/' PARALLEL OFF");
            int[] counts = statement.executeBatch();
            assertEquals(2, counts.length);
            assertEquals(1, count(statement, table));
            assertTrue(s3.objectExists(id, table + "-out/000"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "extended"})
    void corruptGzipReturnsAnErrorWithoutDestroyingTheCallersSavepoint(String mode) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(compressed)) {
            output.write("1|one\n".getBytes(StandardCharsets.UTF_8));
        }
        byte[] truncated = Arrays.copyOf(compressed.toByteArray(), compressed.size() - 6);
        for (byte[] content : List.of("not-gzip".getBytes(StandardCharsets.UTF_8), truncated)) {
            for (int maxError : List.of(0, 2)) {
                String table = table();
                s3.putObject(id, table, content, "application/gzip", Map.of());
                try (Connection connection = connect(mode); Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + table + " (id int, name text)");
                    connection.setAutoCommit(false);
                    statement.execute("INSERT INTO " + table + " VALUES (99, 'before')");
                    Savepoint before = connection.setSavepoint();
                    SQLException error = assertThrows(SQLException.class,
                            () -> statement.execute(copy(table, table, "GZIP MAXERROR " + maxError)));
                    assertFalse(error.getSQLState().startsWith("08"), "Bad S3 data must not disconnect the caller");
                    connection.rollback(before);
                    assertEquals(1, count(statement, table));
                    connection.rollback();
                }
            }
        }
    }
}
