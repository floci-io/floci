package io.floci.aws.services.athena;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    /** The shape of a facet query: one row per (column name, value) pair of each source row. */
    @Test
    void aMultiArgumentUnnestBecomesALateralSubqueryWithOneUnnestPerArray() {
        assertEquals("SELECT facet.column_name, facet.value FROM events "
                        + "CROSS JOIN LATERAL (SELECT unnest(ARRAY['region', 'status']), "
                        + "unnest(ARRAY[CAST(region AS VARCHAR), status])) AS facet (column_name, value) "
                        + "WHERE facet.value <> ''",
                TrinoSqlCompatibility.translate("SELECT facet.column_name, facet.value FROM events "
                        + "CROSS JOIN UNNEST(ARRAY['region', 'status'], ARRAY[CAST(region AS VARCHAR), status]) "
                        + "AS facet (column_name, value) WHERE facet.value <> ''"));
    }

    /** Access Graph's facet query writes the source over several lines, as some clients send CRLF. */
    @Test
    void aMultiLineUnnestIsRewrittenWhateverItsLineBreaks() {
        for (String lineBreak : List.of("\n", "\r\n")) {
            String sql = String.join(lineBreak, "FROM \"flagged_raw_data\"", "\tCROSS JOIN UNNEST(",
                    "\t\tARRAY['identity', 'target_resource'],", "\t\tARRAY[CAST(identity AS VARCHAR), target_resource]",
                    "\t) AS facet (column_name, value)", "\tWHERE facet.value <> ''");

            assertEquals("FROM \"flagged_raw_data\"" + lineBreak + "\tCROSS JOIN LATERAL (SELECT "
                            + "unnest(ARRAY['identity', 'target_resource']), "
                            + "unnest(ARRAY[CAST(identity AS VARCHAR), target_resource])) AS facet (column_name, value)"
                            + lineBreak + "\tWHERE facet.value <> ''",
                    TrinoSqlCompatibility.translate(sql));
        }
    }

    @Test
    void aFirstCommaOrLeftJoinSourceAndAnAliasWithoutAsAreRewrittenToo() {
        assertEquals("SELECT * FROM LATERAL (SELECT unnest(a), unnest(b)) AS u (x, y)",
                TrinoSqlCompatibility.translate("SELECT * FROM UNNEST(a, b) AS u (x, y)"));
        assertEquals("SELECT * FROM t, LATERAL (SELECT unnest(a), unnest(b)) AS u (x, y)",
                TrinoSqlCompatibility.translate("SELECT * FROM t, UNNEST(a, b) u (x, y)"));
        assertEquals("SELECT * FROM t LEFT JOIN LATERAL (SELECT unnest(a), unnest(b)) AS u (x, y) ON TRUE",
                TrinoSqlCompatibility.translate("SELECT * FROM t LEFT JOIN unnest (a, b) AS u (x, y) ON TRUE"));
    }

    /** Commas inside an array, a call, a literal or a CASE belong to that argument, not to UNNEST's list. */
    @Test
    void commasInsideAnUnnestArgumentDoNotSplitIt() {
        assertEquals("SELECT * FROM t CROSS JOIN LATERAL (SELECT unnest(ARRAY[f(a, b), 'c,d']), "
                        + "unnest(ARRAY[CASE WHEN k IN ('x', 'y') THEN NULL ELSE k END, z])) AS u (p, q)",
                TrinoSqlCompatibility.translate("SELECT * FROM t CROSS JOIN UNNEST(ARRAY[f(a, b), 'c,d'], "
                        + "ARRAY[CASE WHEN k IN ('x', 'y') THEN NULL ELSE k END, z]) AS u (p, q)"));
    }

    @Test
    void eachUnnestArgumentIsTranslatedItself() {
        assertEquals("SELECT * FROM t CROSS JOIN LATERAL (SELECT unnest(CAST(json_parse(a) AS JSON[])), "
                        + "unnest(b)) AS u (x, y)",
                TrinoSqlCompatibility.translate(
                        "SELECT * FROM t CROSS JOIN UNNEST(CAST(json_parse(a) AS ARRAY<JSON>), b) AS u (x, y)"));
    }

    /** A line comment in an argument would otherwise run on over the rest of the rewritten source. */
    @Test
    void commentsInAnUnnestSourceAreDroppedAndQuotedNamesKeepTheirQuoting() {
        assertEquals("SELECT * FROM t CROSS JOIN LATERAL (SELECT unnest(a), unnest(b)) AS \"U\" (\"Key\", \"v w\")",
                TrinoSqlCompatibility.translate("SELECT * FROM t CROSS JOIN UNNEST(a -- names\n, b /* values */) "
                        + "/* c */ AS -- d\n \"U\" (\"Key\" /* k */, \"v w\" -- v\n)"));
    }

    /**
     * Without a column list there is nothing to name the subquery's columns; with WITH ORDINALITY, a list that
     * doesn't name every array, a clause keyword where an alias would be, or an alias whose quote never closes, the
     * source is left for DuckDB to report. So is a function only named like it.
     */
    @Test
    void unnestSourcesTheRewriteCannotKeepFaithfulAreLeftAsWritten() {
        for (String sql : List.of(
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) AS u",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) WHERE (x, y) = (1, 2)",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) WITH ORDINALITY AS u (x, y, n)",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) AS u (x)",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) AS u (x, y + 1)",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b) AS \"u (x, y)",
                "SELECT * FROM t CROSS JOIN UNNEST(a) AS u (x)",
                "SELECT * FROM t CROSS JOIN my_unnest(a, b) AS u (x, y)",
                "SELECT * FROM t CROSS JOIN my_schema.unnest(a, b) AS u (x, y)",
                "SELECT * FROM t CROSS JOIN UNNEST(a, b")) {
            assertEquals(sql, TrinoSqlCompatibility.translate(sql));
        }
    }

    @Test
    void unnestInsideALiteralAQuotedIdentifierOrACommentIsNotRewritten() {
        String sql = "SELECT 'UNNEST(a, b) AS u (x, y)' AS \"UNNEST(a, b) AS u (x, y)\" "
                + "/* UNNEST(a, b) AS u (x, y) */ FROM t -- UNNEST(a, b) AS u (x, y)";

        assertEquals(sql, TrinoSqlCompatibility.translate(sql));
    }

    @Test
    void everyMultiArgumentUnnestInTheQueryIsRewritten() {
        assertEquals("SELECT * FROM t CROSS JOIN LATERAL (SELECT unnest(a), unnest(b)) AS u (x, y) "
                        + "CROSS JOIN LATERAL (SELECT unnest(c), unnest(d)) AS v (p, q)",
                TrinoSqlCompatibility.translate("SELECT * FROM t CROSS JOIN UNNEST(a, b) AS u (x, y) "
                        + "CROSS JOIN UNNEST(c, d) AS v (p, q)"));
    }

    /** Brackets count when splitting arguments, so a JSON function given an array element still gets its path. */
    @Test
    void aJsonPathAfterAnArrayElementArgumentIsTranslated() {
        assertEquals("SELECT json_extract_scalar(ARRAY['{\"a\":1}', '{}'][1], '/a')",
                TrinoSqlCompatibility.translate("SELECT json_extract_scalar(ARRAY['{\"a\":1}', '{}'][1], '$.a')"));
    }

    @Test
    void anUnbalancedCastIsReturnedAsWritten() {
        assertEquals("SELECT CAST(x AS ARRAY<JSON>", TrinoSqlCompatibility.translate("SELECT CAST(x AS ARRAY<JSON>"));
    }
}
