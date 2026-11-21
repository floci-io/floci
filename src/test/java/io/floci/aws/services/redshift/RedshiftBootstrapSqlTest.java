package io.floci.aws.services.redshift;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the bootstrap script defines, checked without a database; the Docker tests run it. */
class RedshiftBootstrapSqlTest {

    private static String bootstrap() throws IOException {
        try (InputStream in = RedshiftBootstrapSqlTest.class.getResourceAsStream("/redshift/bootstrap-catalog.sql")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void definesTheVarbyteAndSuperDomainsOnceEach() throws IOException {
        String sql = bootstrap();

        assertEquals(1, count(sql, "CREATE DOMAIN pg_catalog.varbyte AS bytea;"));
        assertEquals(1, count(sql, "CREATE DOMAIN pg_catalog.super AS jsonb;"));
        // Run again on a database that has them, so each is created only when absent.
        assertTrue(sql.contains("t.typname = 'varbyte') THEN"));
        assertTrue(sql.contains("t.typname = 'super') THEN"));
    }

    @Test
    void definesEachVarbyteAndSuperFunctionWithItsArgumentTypes() throws IOException {
        String sql = bootstrap();

        for (String signature : List.of(
                "pg_catalog.from_varbyte(value pg_catalog.varbyte, format text)",
                "pg_catalog.to_varbyte(value text, format text)",
                "pg_catalog.json_parse(value text)",
                "pg_catalog.json_parse(value pg_catalog.varbyte)",
                "pg_catalog.can_json_parse(value text)",
                "pg_catalog.can_json_parse(value pg_catalog.varbyte)",
                "pg_catalog.json_serialize(value pg_catalog.super)",
                "pg_catalog.json_typeof(value pg_catalog.super)")) {
            assertEquals(1, count(sql, "CREATE OR REPLACE FUNCTION " + signature), signature);
        }
    }

    @Test
    void closesEveryDollarQuotedBodyItOpens() throws IOException {
        assertEquals(0, count(bootstrap(), "$floci$") % 2);
    }

    @Test
    void putsTheVarbyteAndSuperSectionAfterTheGrantsAndRevokesSoAFailureInItNeverSkipsThem() throws IOException {
        String sql = bootstrap();
        int section = sql.indexOf("-- 18. VARBYTE and SUPER");

        assertTrue(section > sql.lastIndexOf("REVOKE EXECUTE ON FUNCTION"));
        assertTrue(section > sql.lastIndexOf("GRANT SELECT ON ALL TABLES IN SCHEMA pg_catalog TO PUBLIC;"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
