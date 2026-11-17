package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlBootstrapTest {

    private static String docker() {
        return DsqlBootstrap.render("postgres", "dbowner", "admin", "", "", false);
    }

    private static String external() {
        return DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin", "dsqlconnect_abc", "dabc_", true);
    }

    @Test
    void noRenderingLeavesAPlaceholderBehind() {
        assertFalse(docker().contains("{{"));
        assertFalse(external().contains("{{"));
        assertFalse(DsqlBootstrap.render("My-Db", "o\"w", "it's", "c'n", "p'x_", true).contains("{{"));
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
        assertTrue(script.contains("rolname = 'dsqlconnect_abc'"));
        assertTrue(script.contains("GRANT %I TO %I WITH INHERIT TRUE, SET FALSE"));
    }

    @Test
    void externalRenderingNeedsAConnectRoleOutsideThePrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin", "", "dabc_", true));
        assertThrows(IllegalArgumentException.class,
                () -> DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin", "dabc_connect", "dabc_", true));
    }

    @Test
    void noClientCallableProcedureRevokesARolesConnect() {
        assertFalse(external().contains("_release_backend_roles"));
        assertFalse(external().contains("REVOKE CONNECT ON DATABASE %I FROM %I"));
    }

    @Test
    void externalRenderingTakesConnectFromPublic() {
        assertTrue(external().contains("REVOKE CONNECT ON DATABASE %I FROM PUBLIC"));
        assertTrue(docker().contains("IF false THEN\n        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '')"));
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
        String script = DsqlBootstrap.render("My-Db", "o\"w", "it's", "c'n", "p'x_", true);
        assertTrue(script.contains("GRANT CREATE ON DATABASE \"My-Db\" TO \"it's\""));
        assertTrue(script.contains("AUTHORIZATION \"o\"\"w\""));
        assertTrue(script.contains("rolname = 'it''s'"));
        assertTrue(script.contains("rolname = 'o\"w'"));
        assertTrue(script.contains("prefix constant text := 'p''x_';"));
        assertTrue(script.contains("rolname = 'c''n'"));
        assertTrue(DsqlBootstrap.render("user", "table", "select", "", "", false)
                .contains("GRANT CREATE ON DATABASE \"user\" TO \"select\""));
    }
}
