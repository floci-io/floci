package io.github.hectorvent.floci.services.redshift.container;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftCatalogResourceTest {

    @Test
    void testBootstrapCatalogResourceExistsAndContainsExpectedViews() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/redshift/bootstrap-catalog.sql")) {
            assertNotNull(in, "bootstrap-catalog.sql must exist on classpath");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("pg_table_def"), "Missing DDL for pg_table_def");
            assertTrue(sql.contains("svv_table_info"), "Missing DDL for svv_table_info");
            assertTrue(sql.contains("svv_all_columns"), "Missing DDL for svv_all_columns");
            assertTrue(sql.contains("svv_tables"), "Missing DDL for svv_tables");
            assertTrue(sql.contains("stv_tbl_perm"), "Missing DDL for stv_tbl_perm");
            assertTrue(sql.contains("stl_load_errors"), "Missing DDL for stl_load_errors");
            assertTrue(sql.contains("svl_qlog"), "Missing DDL for svl_qlog");
            assertTrue(sql.contains("pg_user_info"), "Missing DDL for pg_user_info");
            assertTrue(sql.contains("svl_user_info"), "Missing DDL for svl_user_info");
            assertTrue(sql.contains("stv_sessions"), "Missing DDL for stv_sessions");
            assertTrue(sql.contains("stv_recents"), "Missing DDL for stv_recents");
            assertTrue(sql.contains("pg_database_info"), "Missing DDL for pg_database_info");
            assertTrue(sql.contains("svv_columns"), "Missing DDL for svv_columns");
            assertTrue(sql.contains("svv_transactions"), "Missing DDL for svv_transactions");
            assertTrue(sql.contains("stv_slices"), "Missing DDL for stv_slices");
            assertTrue(sql.contains("stl_query"), "Missing DDL for stl_query");
            assertTrue(sql.contains("stv_wlm_query_state"), "Missing DDL for stv_wlm_query_state");
            assertTrue(sql.contains("svv_diskusage"), "Missing DDL for svv_diskusage");
        }
    }
}
