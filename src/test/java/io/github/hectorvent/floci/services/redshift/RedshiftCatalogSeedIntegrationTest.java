package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftCatalogSeedIntegrationTest {

    @Inject
    RedshiftService service;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for Redshift catalog integration tests");
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
        return "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
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
    void testCatalogViewsIntrospection() throws Exception {
        clusterId = "cat-seed-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement stmt = conn.createStatement()) {

            // Create sample table for metadata verification
            stmt.execute("CREATE TABLE test_catalog_users (id integer, username varchar(50) NOT NULL)");

            // 1. Verify pg_table_def
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT \"column\", type, \"notnull\" FROM pg_table_def WHERE tablename = ? ORDER BY \"column\"")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "pg_table_def must contain at least one column");
                    assertEquals("id", rs.getString("column"));
                    assertFalse(rs.getBoolean("notnull"));

                    assertTrue(rs.next(), "pg_table_def must contain second column");
                    assertEquals("username", rs.getString("column"));
                    assertTrue(rs.getBoolean("notnull"));
                }
            }

            // 2. Verify svv_table_info
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT \"table\", \"schema\", encoded, diststyle FROM svv_table_info WHERE \"table\" = ?")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "svv_table_info must contain a record for test_catalog_users");
                    assertEquals("test_catalog_users", rs.getString("table"));
                    assertEquals("public", rs.getString("schema"));
                    assertEquals("EVEN", rs.getString("diststyle"));
                }
            }

            // 3. Verify svv_all_columns
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name, is_nullable FROM svv_all_columns WHERE table_name = ? ORDER BY column_name")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("id", rs.getString("column_name"));
                    assertEquals("YES", rs.getString("is_nullable"));

                    assertTrue(rs.next());
                    assertEquals("username", rs.getString("column_name"));
                    assertEquals("NO", rs.getString("is_nullable"));
                }
            }

            // 4. Verify svv_tables
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT table_name FROM svv_tables WHERE table_name = ?")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "svv_tables must contain test_catalog_users");
                }
            }

            // 5. Verify stv_tbl_perm
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name FROM stv_tbl_perm WHERE name = ?")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "stv_tbl_perm must contain test_catalog_users");
                }
            }

            // 6. Verify stl_load_errors
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM stl_load_errors")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            // 7. Verify svl_qlog
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM svl_qlog")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1);
            }

            // 8. Verify pg_user_info
            try (ResultSet rs = stmt.executeQuery("SELECT usename FROM pg_user_info WHERE usename = 'admin'")) {
                assertTrue(rs.next(), "pg_user_info must contain admin user");
            }

            // 9. Verify stv_sessions
            try (ResultSet rs = stmt.executeQuery("SELECT process, user_name, db_name FROM stv_sessions WHERE user_name = 'admin'")) {
                assertTrue(rs.next(), "stv_sessions must contain admin session");
                assertEquals("admin", rs.getString("user_name"));
                assertEquals("dev", rs.getString("db_name"));
            }

            // 10. Verify stv_recents
            try (ResultSet rs = stmt.executeQuery("SELECT status, user_name, db_name FROM stv_recents WHERE user_name = 'admin'")) {
                assertTrue(rs.next(), "stv_recents must contain admin queries");
                assertEquals("admin", rs.getString("user_name"));
            }

            // 11. Verify pg_database_info
            try (ResultSet rs = stmt.executeQuery("SELECT datname FROM pg_database_info WHERE datname = 'dev'")) {
                assertTrue(rs.next(), "pg_database_info must contain dev database");
            }

            // 12. Verify svv_columns
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name, data_type FROM svv_columns WHERE table_name = ? ORDER BY column_name")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "svv_columns must contain id column");
                    assertEquals("id", rs.getString("column_name"));
                    assertTrue(rs.next(), "svv_columns must contain username column");
                    assertEquals("username", rs.getString("column_name"));
                }
            }

            // 13. Verify svv_transactions
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM svv_transactions")) {
                assertTrue(rs.next());
            }

            // 14. Verify stv_slices
            try (ResultSet rs = stmt.executeQuery("SELECT slice, node FROM stv_slices")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("slice"));
                assertEquals(0, rs.getInt("node"));
            }

            // 15. Verify stl_query
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM stl_query")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            // 16. Verify newly created database inherits catalog views from template1
            stmt.execute("CREATE DATABASE test_clone_db");
        }

        String cloneDbUrl = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/test_clone_db";
        try (Connection cloneConn = DriverManager.getConnection(cloneDbUrl, "admin", "Secret123");
             Statement cloneStmt = cloneConn.createStatement()) {
            try (ResultSet rs = cloneStmt.executeQuery("SELECT count(*) FROM pg_table_def")) {
                assertTrue(rs.next(), "cloned database must have pg_table_def from template1");
            }
            try (ResultSet rs = cloneStmt.executeQuery("SELECT count(*) FROM svv_table_info")) {
                assertTrue(rs.next(), "cloned database must have svv_table_info from template1");
            }
            try (ResultSet rs = cloneStmt.executeQuery("SELECT count(*) FROM stv_sessions")) {
                assertTrue(rs.next(), "cloned database must have stv_sessions from template1");
            }
        }
    }
}
