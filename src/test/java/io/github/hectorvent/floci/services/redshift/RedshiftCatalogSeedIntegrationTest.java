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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

            // 12. Verify svv_columns (AWS Redshift contract)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, data_type " +
                    "FROM svv_columns WHERE table_name = ? ORDER BY column_name")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "svv_columns must contain id column");
                    assertEquals("dev", rs.getString("table_catalog"));
                    assertEquals("public", rs.getString("table_schema"));
                    assertEquals("id", rs.getString("column_name"));
                    assertEquals(1, rs.getInt("ordinal_position"));
                    assertTrue(rs.next(), "svv_columns must contain username column");
                    assertEquals("username", rs.getString("column_name"));
                    assertEquals(2, rs.getInt("ordinal_position"));
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

            // 15. Verify stl_query (dynamic view over pg_stat_activity)
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM stl_query")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 1, "stl_query must report active or recent queries");
            }

            // 16. Verify stv_wlm_query_state
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM stv_wlm_query_state")) {
                assertTrue(rs.next());
            }

            // 17. Verify svv_diskusage
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, size, used FROM svv_diskusage WHERE name = ?")) {
                ps.setString(1, "test_catalog_users");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "svv_diskusage must contain test_catalog_users");
                    assertEquals("test_catalog_users", rs.getString("name"));
                }
            }

            // 18. Verify COPY error records into stl_load_errors
            try {
                stmt.execute("COPY test_catalog_users FROM 's3://nonexistent-seed-bucket/bad.csv' FORMAT AS CSV");
            } catch (SQLException expected) {
                // Expected to fail because S3 bucket does not exist
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT filename, err_code, err_reason FROM stl_load_errors WHERE filename LIKE '%nonexistent-seed-bucket%'")) {
                assertTrue(rs.next(), "stl_load_errors must record the failed COPY attempt");
                assertTrue(rs.getString("filename").contains("nonexistent-seed-bucket"));
                assertEquals(1204, rs.getInt("err_code"));
            }

            // 19. Verify newly created database inherits catalog views from template1
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

    @Test
    void testAllDocumentedRedshiftColumnsContract() throws Exception {
        clusterId = "cat-contract-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = service.createCluster(clusterId, "dc2.large", "admin", "Secret123");

        try (Connection conn = waitForConnection(cluster, "admin", "Secret123");
             Statement stmt = conn.createStatement()) {

            // Pre-seed a test table so metadata views have rows
            stmt.execute("CREATE TABLE contract_test_table (col_int integer, col_varchar varchar(100))");

            String[] contractQueries = new String[] {
                    // 1. pg_table_def (AWS Redshift documented columns)
                    "SELECT schemaname, tablename, \"column\", type, encoding, distkey, sortkey, \"notnull\" FROM pg_table_def LIMIT 1",

                    // 2. svv_table_info (AWS Redshift documented columns)
                    "SELECT \"database\", \"schema\", table_id, \"table\", encoded, diststyle, sortkey1, max_varchar, sortkey1_enc, sortkey_num, size, pct_used, empty, unsorted, stats_off, tbl_rows, skew_sortkey1, skew_rows, estimated_visible_rows, risk_event, vacuum_sort_benefit, create_time FROM svv_table_info LIMIT 1",

                    // 3. svv_all_columns (AWS Redshift documented columns)
                    "SELECT database_name, schema_name, table_name, column_name, ordinal_position, column_default, is_nullable, data_type, character_maximum_length, numeric_precision, numeric_scale, remarks FROM svv_all_columns LIMIT 1",

                    // 4. svv_tables (AWS Redshift documented columns)
                    "SELECT table_catalog, table_schema, table_name, table_type, remarks FROM svv_tables LIMIT 1",

                    // 5. stv_tbl_perm (AWS Redshift documented columns)
                    "SELECT slice, id, name, rows, sorted_rows, temp, db_id, insert_pristine, delete_pristine, backup, dist_style, block_count FROM stv_tbl_perm LIMIT 1",

                    // 6. stl_load_errors (AWS Redshift documented columns)
                    "SELECT userid, slice, tbl, starttime, session, query, filename, line_number, colname, type, col_length, position, raw_line, raw_field_value, err_code, err_reason, is_partial, start_offset, copy_job_id FROM stl_load_errors LIMIT 1",

                    // 7. svl_qlog (AWS Redshift documented columns)
                    "SELECT userid, query, xid, pid, starttime, endtime, elapsed, aborted, insert_pristine, concurrency_scaling_status, source_query, label, substring, concurrency_scaling_status_txt, from_sp_call FROM svl_qlog LIMIT 1",

                    // 8. pg_user_info (Redshift user catalog view)
                    "SELECT usename, usesysid, usecreatedb, usesuper, usecatupd, valuntil, useconfig, useconnlimit, syslogaccess, last_ddl_ts, sessiontimeout, external_id FROM pg_user_info LIMIT 1",

                    // 8b. svl_user_info (AWS Redshift documented columns)
                    "SELECT usename, usesysid, usecreatedb, usesuper, usecatupd, useconnlimit, syslogaccess, last_ddl_ts, sessiontimeout, external_id FROM svl_user_info LIMIT 1",

                    // 9. stv_sessions (AWS Redshift documented columns)
                    "SELECT starttime, process, user_name, db_name, timeout_sec FROM stv_sessions LIMIT 1",

                    // 10. stv_recents (AWS Redshift documented columns)
                    "SELECT userid, status, starttime, duration, user_name, db_name, query, pid FROM stv_recents LIMIT 1",

                    // 11. pg_database_info (AWS Redshift documented columns)
                    "SELECT datname, datid, datdba, encoding, datconnlimit, datistemplate, datallowconn, dattablespace FROM pg_database_info LIMIT 1",

                    // 12. svv_columns (AWS Redshift documented columns)
                    "SELECT table_catalog, table_schema, table_name, column_name, ordinal_position, column_default, is_nullable, data_type, character_maximum_length, numeric_precision, numeric_precision_radix, numeric_scale, datetime_precision, interval_type, interval_precision, character_set_catalog, character_set_schema, character_set_name, collation_catalog, collation_schema, collation_name, domain_name, remarks FROM svv_columns LIMIT 1",

                    // 13. svv_transactions (AWS Redshift documented columns)
                    "SELECT txn_owner, txn_db, xid, pid, txn_start, lock_mode, lockable_object_type, relation, granted FROM svv_transactions LIMIT 1",

                    // 14. stv_slices (AWS Redshift documented columns)
                    "SELECT node, slice, localslice, type FROM stv_slices LIMIT 1",

                    // 15. stl_query (AWS Redshift documented columns)
                    "SELECT userid, query, label, xid, pid, database, querytxt, starttime, endtime, aborted, insert_pristine, concurrency_scaling_status FROM stl_query LIMIT 1",

                    // 16. stv_wlm_query_state (AWS Redshift documented columns)
                    "SELECT xid, task, query, service_class, slot_count, wlm_start_time, state, queue_time, exec_time, query_priority FROM stv_wlm_query_state LIMIT 1",

                    // 17. svv_diskusage (AWS Redshift documented columns)
                    "SELECT db_id, name, slice, col, tbl, blocknum, num_values, minvalue, maxvalue, sb_pos, pinned, on_disk, modified, hdr_modified, unsorted, tombstone, preferred_diskno, temporary, newblock FROM svv_diskusage LIMIT 1"
            };

            for (String sql : contractQueries) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    assertNotNull(rs.getMetaData(), "ResultSet metadata must not be null for query: " + sql);
                }
            }
        }
    }
}
