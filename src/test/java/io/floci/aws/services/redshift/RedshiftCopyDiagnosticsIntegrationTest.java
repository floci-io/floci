package io.floci.aws.services.redshift;

import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedshiftCopyDiagnosticsIntegrationTest {
    @Inject
    RedshiftService redshift;
    @Inject
    S3Service s3;

    private final String id = "copy-diagnostics-" + UUID.randomUUID().toString().substring(0, 8);
    private Cluster cluster;

    @BeforeAll
    void start() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker is required for a Redshift cluster");
        cluster = redshift.createCluster(id, "dc2.large", "admin", "Secret123");
        s3.createBucket(id, "us-east-1");
        s3.putObject(id, "private", "private-record\n".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
    }

    @AfterAll
    void stop() throws Exception {
        if (cluster != null) {
            redshift.deleteCluster(id);
        }
    }

    @Test
    void catalogLockDoesNotStallEachRejectedRowOrReplaceTheCopyError() throws Exception {
        s3.putObject(id, "locked", "invalid-number\n".repeat(100).getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        try (Connection locker = connect("admin"); Statement lock = locker.createStatement();
             Connection caller = connect("admin"); Statement copy = caller.createStatement()) {
            copy.execute("CREATE TABLE diagnostics_locked_target (id integer)");
            copy.setQueryTimeout(5);
            locker.setAutoCommit(false);
            lock.execute("LOCK TABLE floci_internal.copy_load_errors IN ACCESS EXCLUSIVE MODE");
            try {
                assertTimeout(Duration.ofSeconds(5), () -> assertEquals(0,
                        copy.executeUpdate("COPY diagnostics_locked_target FROM 's3://" + id + "/locked' IGNOREALLERRORS")));
                SQLException error = assertThrows(SQLException.class, () -> copy.execute(
                        "COPY diagnostics_locked_target FROM 's3://" + id + "/private'"));
                assertEquals("22P02", error.getSQLState());
            } finally {
                locker.rollback();
            }
        }
    }

    @Test
    void ordinaryUsersSeeOnlyTheirOwnDiagnosticsAndCannotMutateOrReadBackingData() throws Exception {
        try (Connection admin = connect("admin"); Statement owner = admin.createStatement()) {
            owner.execute("CREATE ROLE diagnostics_alice LOGIN PASSWORD 'Secret123'");
            owner.execute("CREATE ROLE diagnostics_bob LOGIN PASSWORD 'Secret123'");
            owner.execute("GRANT diagnostics_bob TO diagnostics_alice");
            owner.execute("CREATE TABLE diagnostics_target (id integer)");
            owner.execute("GRANT ALL ON diagnostics_target TO diagnostics_alice, diagnostics_bob");
            for (String user : new String[]{"diagnostics_alice", "diagnostics_bob"}) {
                try (Connection connection = connect(user); Statement statement = connection.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.execute("COPY diagnostics_target FROM 's3://"
                            + id + "/private'"));
                    assertEquals(1, count(statement, "stl_load_errors"));
                    try (ResultSet rows = statement.executeQuery("SELECT userid, raw_line FROM stl_load_errors")) {
                        assertTrue(rows.next());
                        assertEquals("private-record", rows.getString("raw_line").trim());
                        int userId = rows.getInt("userid");
                        assertFalse(rows.next());
                        assertEquals(userId, scalar(statement, "SELECT oid FROM pg_roles WHERE rolname = current_user"));
                    }
                    assertDenied(statement, "SELECT * FROM floci_internal.copy_load_errors");
                    assertDenied(statement, "DELETE FROM stl_load_errors");
                    assertDenied(statement, "UPDATE stl_load_errors SET raw_line = 'forged'");
                    assertDenied(statement, "INSERT INTO stl_load_errors (raw_line) VALUES ('forged')");
                    assertDenied(statement, "INSERT INTO floci_internal.copy_load_errors (raw_line) VALUES ('forged')");
                }
            }
            try (Connection alice = connect("diagnostics_alice"); Statement statement = alice.createStatement()) {
                statement.execute("SET ROLE diagnostics_bob");
                assertThrows(SQLException.class, () -> statement.execute("COPY diagnostics_target FROM 's3://"
                        + id + "/private'"));
                assertEquals(2, count(statement, "stl_load_errors"));
                statement.execute("RESET ROLE");
                assertEquals(2, count(statement, "stl_load_errors"));
            }
            assertEquals(3, count(owner, "stl_load_errors"));
            assertEquals(3, count(owner, "floci_internal.copy_load_errors"));
            // Recreate the older PUBLIC-writable catalog to exercise the upgrade path with retained rows.
            owner.execute("DROP VIEW pg_catalog.stl_load_errors");
            owner.execute("ALTER TABLE floci_internal.copy_load_errors RENAME TO stl_load_errors");
            owner.execute("ALTER TABLE floci_internal.stl_load_errors SET SCHEMA pg_catalog");
            owner.execute("GRANT ALL ON pg_catalog.stl_load_errors TO PUBLIC");
            try (InputStream input = getClass().getResourceAsStream("/redshift/bootstrap-catalog.sql")) {
                assertNotNull(input);
                String bootstrap = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                owner.execute(bootstrap);
                owner.execute(bootstrap);
            }
            assertEquals(3, count(owner, "stl_load_errors"));
            try (Connection alice = connect("diagnostics_alice"); Statement statement = alice.createStatement()) {
                assertEquals(2, count(statement, "stl_load_errors"));
                assertDenied(statement, "SELECT * FROM floci_internal.copy_load_errors");
                assertDenied(statement, "DELETE FROM stl_load_errors");
                assertThrows(SQLException.class, () -> statement.execute("COPY diagnostics_target FROM 's3://"
                        + id + "/private'"));
                assertEquals(3, count(statement, "stl_load_errors"));
            }
            assertEquals(4, count(owner, "stl_load_errors"));
        }
    }

    private Connection connect(String user) throws SQLException {
        return RedshiftConnections.waitForConnection(RedshiftConnections.jdbcUrl(cluster)
                + "?sslmode=disable&gssEncMode=disable", user, "Secret123");
    }

    private static int count(Statement statement, String table) throws SQLException {
        return scalar(statement, "SELECT count(*) FROM " + table);
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void assertDenied(Statement statement, String sql) {
        SQLException error = assertThrows(SQLException.class, () -> statement.execute(sql));
        assertEquals("42501", error.getSQLState());
    }
}
