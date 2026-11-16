package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlBootstrapTest {

    private static String docker() {
        return DsqlBootstrap.render("postgres", "dbowner", "admin", "", false);
    }

    private static String external() {
        return DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin", "dabc_", true);
    }

    @Test
    void noRenderingLeavesAPlaceholderBehind() {
        assertFalse(docker().contains("{{"));
        assertFalse(external().contains("{{"));
        assertFalse(DsqlBootstrap.render("My-Db", "o\"w", "it's", "p'x_", true).contains("{{"));
    }

    @Test
    void dockerRenderingNamesTheDatabaseAndRolesQuoted() {
        String script = docker();
        assertTrue(script.contains("WHERE rolname = 'admin') THEN"));
        assertTrue(script.contains("CREATE ROLE \"admin\" WITH LOGIN CREATEROLE CREATEDB"));
        assertTrue(script.contains("GRANT CREATE ON DATABASE \"postgres\" TO \"admin\""));
        assertTrue(script.contains("ALTER DATABASE \"postgres\" SET default_transaction_isolation"));
        assertTrue(script.contains("CREATE SCHEMA IF NOT EXISTS sys AUTHORIZATION \"dbowner\""));
        assertTrue(script.contains("pg_database_size(current_database())"));
    }

    @Test
    void dockerRenderingUsesNoPrefixAndStaysInDockerMode() {
        String script = docker();
        assertTrue(script.contains("sys.backend_roles"));
        assertTrue(script.contains("prefix constant text := '';"));
        assertTrue(script.contains("IF false THEN"));
        assertTrue(script.contains("NOT false OR"));
        assertFalse(script.contains("'dabc_'"));
    }

    @Test
    void externalRenderingRecordsTheClustersRolesUnderItsPrefix() {
        String script = external();
        assertTrue(script.contains("CREATE TABLE IF NOT EXISTS sys.backend_roles"));
        assertTrue(script.contains("prefix constant text := 'dabc_';"));
        assertTrue(script.contains("IF true THEN"));
        assertTrue(script.contains("NOT true OR"));
        assertTrue(script.contains("rolname = 'dsqlowner_abc'"));
    }

    @Test
    void externalRenderingNamesTheClustersObjects() {
        String script = external();
        assertTrue(script.contains("GRANT CREATE ON DATABASE \"dsql_abc\" TO \"dabc_admin\""));
        assertTrue(script.contains("AUTHORIZATION \"dsqlowner_abc\""));
        assertFalse(script.contains("'admin'"));
        assertFalse(script.contains("\"postgres\""));
    }

    @Test
    void everyIdentifierIsQuotedAndLiteralsEscaped() {
        String script = DsqlBootstrap.render("My-Db", "o\"w", "it's", "p'x_", true);
        assertTrue(script.contains("GRANT CREATE ON DATABASE \"My-Db\" TO \"it's\""));
        assertTrue(script.contains("AUTHORIZATION \"o\"\"w\""));
        assertTrue(script.contains("rolname = 'it''s'"));
        assertTrue(script.contains("rolname = 'o\"w'"));
        assertTrue(script.contains("prefix constant text := 'p''x_';"));
        assertTrue(DsqlBootstrap.render("user", "table", "select", "", false)
                .contains("GRANT CREATE ON DATABASE \"user\" TO \"select\""));
    }
}
