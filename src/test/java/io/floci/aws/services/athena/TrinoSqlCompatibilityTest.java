package io.floci.aws.services.athena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrinoSqlCompatibilityTest {

    @Test
    void castToATrinoArrayTypeBecomesADuckDbList() {
        assertEquals("SELECT CAST(json_parse(col) AS JSON[]) FROM t",
                TrinoSqlCompatibility.translate("SELECT CAST(json_parse(col) AS ARRAY<JSON>) FROM t"));
        assertEquals("SELECT cast(x as BIGINT[])", TrinoSqlCompatibility.translate("SELECT cast(x as ARRAY(BIGINT))"));
    }

    @Test
    void mapsRowsAndNestingAreTranslatedToo() {
        assertEquals("SELECT TRY_CAST(x AS MAP(VARCHAR, BIGINT[]))",
                TrinoSqlCompatibility.translate("SELECT TRY_CAST(x AS MAP<VARCHAR, ARRAY(BIGINT)>)"));
        assertEquals("SELECT CAST(x AS STRUCT(a BIGINT, \"b c\" VARCHAR[]))",
                TrinoSqlCompatibility.translate("SELECT CAST(x AS ROW(a BIGINT, \"b c\" ARRAY<VARCHAR>))"));
        assertEquals("SELECT CAST(CAST(x AS JSON[]) AS VARCHAR[])",
                TrinoSqlCompatibility.translate("SELECT CAST(CAST(x AS ARRAY<JSON>) AS ARRAY<VARCHAR>)"));
    }

    @Test
    void typesDuckDbAlreadySpellsTheSameWayAreKept() {
        assertEquals("SELECT CAST(p AS DECIMAL(10,2)), CAST(s AS VARCHAR(5)), CAST(t AS TIMESTAMP)",
                TrinoSqlCompatibility.translate(
                        "SELECT CAST(p AS DECIMAL(10,2)), CAST(s AS VARCHAR(5)), CAST(t AS TIMESTAMP)"));
    }

    @Test
    void literalsQuotedIdentifiersCommentsAndLookalikeNamesAreLeftAlone() {
        String sql = "SELECT 'CAST(x AS ARRAY<JSON>)' AS s, \"cast(\" AS q, podcast(1), x AS y -- CAST(x AS ARRAY<JSON>)\n"
                + "FROM t /* CAST(y AS ARRAY<JSON>) */";

        assertEquals(sql, TrinoSqlCompatibility.translate(sql));
    }

    @Test
    void quotedRowFieldNamesKeepTheirEscapedQuotesAndCommas() {
        assertEquals("SELECT CAST(x AS STRUCT(\"a\"\"b\" INTEGER[], \"c,d\" BIGINT))",
                TrinoSqlCompatibility.translate("SELECT CAST(x AS ROW(\"a\"\"b\" ARRAY<INTEGER>, \"c,d\" BIGINT))"));
    }

    @Test
    void commentsInsideATargetTypeDoNotStopItsTranslation() {
        assertEquals("SELECT CAST(x AS JSON[])", TrinoSqlCompatibility.translate("SELECT CAST(x AS ARRAY /* note */ <JSON>)"));
        assertEquals("SELECT CAST(x AS STRUCT(a VARCHAR[]))",
                TrinoSqlCompatibility.translate("SELECT CAST(x AS ROW(a -- note\n ARRAY<VARCHAR>))"));
    }

    @Test
    void unnamedRowFieldsGetPositionalNames() {
        assertEquals("SELECT CAST(ROW(1, 'x') AS STRUCT(field0 BIGINT, field1 VARCHAR[], field2 DOUBLE PRECISION))",
                TrinoSqlCompatibility.translate(
                        "SELECT CAST(ROW(1, 'x') AS ROW(BIGINT, ARRAY<VARCHAR>, DOUBLE PRECISION))"));
    }

    @Test
    void literalJsonPathsBecomeJsonPointersThatCanNameAnyMember() {
        assertEquals("SELECT json_extract_scalar(col, '/a/b c/0')",
                TrinoSqlCompatibility.translate("SELECT json_extract_scalar(col, '$.a[\"b c\"][0]')"));
        assertEquals("SELECT JSON_EXTRACT(col, '/a\"b'), json_size(col, '/x~1y~0z'), json_extract(col, '')",
                TrinoSqlCompatibility.translate(
                        "SELECT JSON_EXTRACT(col, '$[\"a\\\"b\"]'), json_size(col, '$[\"x/y~z\"]'), json_extract(col, '$')"));
        assertEquals("SELECT json_extract_scalar(col, '/it''s')",
                TrinoSqlCompatibility.translate("SELECT json_extract_scalar(col, '$[\"it''s\"]')"));
    }

    @Test
    void wildcardAndComputedPathsAreLeftForDuckDb() {
        String sql = "SELECT json_extract(col, '$.a[*]'), json_extract_scalar(col, p), json_size(col, '$..a')";

        assertEquals(sql, TrinoSqlCompatibility.translate(sql));
    }

    @Test
    void anUnbalancedCastIsReturnedAsWritten() {
        assertEquals("SELECT CAST(x AS ARRAY<JSON>", TrinoSqlCompatibility.translate("SELECT CAST(x AS ARRAY<JSON>"));
    }
}
