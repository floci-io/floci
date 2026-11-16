package io.floci.aws.services.dsql.dataplane;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsqlBootstrapTest {

    @Test
    void dockerRenderingMatchesTodaysScriptExceptTheSizeFunction() throws IOException {
        String golden;
        try (InputStream in = getClass().getResourceAsStream("/dsql/bootstrap-2.5.sql")) {
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String expected = golden.replace("pg_database_size('postgres')", "pg_database_size(current_database())");
        assertEquals(expected, DsqlBootstrap.render("postgres", "dbowner", "admin"));
    }

    @Test
    void externalRenderingNamesTheClustersObjects() {
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
