package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlBootstrapTest {

    private static String docker() {
        return DsqlBootstrap.render("postgres", "dbowner", "admin");
    }

    @Test
    void noRenderingLeavesAPlaceholderBehind() {
        assertFalse(docker().contains("{{"));
        assertFalse(DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin").contains("{{"));
        assertFalse(DsqlBootstrap.render("My-Db", "o\"w", "it's").contains("{{"));
    }

    @Test
    void dockerRenderingNamesTheDatabaseAndRolesAsBefore() {
        String script = docker();
        assertTrue(script.contains("WHERE rolname = 'admin') THEN"));
        assertTrue(script.contains("CREATE ROLE admin WITH LOGIN CREATEROLE CREATEDB"));
        assertTrue(script.contains("GRANT CREATE ON DATABASE postgres TO admin"));
        assertTrue(script.contains("ALTER DATABASE postgres SET default_transaction_isolation"));
        assertTrue(script.contains("CREATE SCHEMA IF NOT EXISTS sys AUTHORIZATION dbowner"));
        assertTrue(script.contains("pg_database_size(current_database())"));
    }

    @Test
    void anotherRenderingNamesItsOwnDatabaseAndRoles() {
        String script = DsqlBootstrap.render("dsql_abc", "dsqlowner_abc", "dabc_admin");
        assertTrue(script.contains("GRANT CREATE ON DATABASE dsql_abc TO dabc_admin"));
        assertTrue(script.contains("AUTHORIZATION dsqlowner_abc"));
        assertFalse(script.contains("'admin'"));
        assertFalse(script.contains(" postgres"));
    }

    @Test
    void identifiersThatNeedQuotingAreQuotedAndLiteralsEscaped() {
        String script = DsqlBootstrap.render("My-Db", "o\"w", "it's");
        assertTrue(script.contains("GRANT CREATE ON DATABASE \"My-Db\" TO \"it's\""));
        assertTrue(script.contains("AUTHORIZATION \"o\"\"w\""));
        assertTrue(script.contains("rolname = 'it''s'"));
    }
}
