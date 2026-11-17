package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theRecordedFormDropsTrailingSemicolonsAndTheWhitespaceAroundTheStatement() {
        assertEquals("SELECT 1", TrinoSqlCompatibility.recordedForm("SELECT 1;"));
        assertEquals("SELECT 1", TrinoSqlCompatibility.recordedForm("SELECT 1 ;"));
        assertEquals("SELECT 1", TrinoSqlCompatibility.recordedForm("\n  SELECT 1;; ;\n"));
        assertEquals("DROP TABLE t", TrinoSqlCompatibility.recordedForm("DROP TABLE t;;"));
        assertEquals("SELECT 1", TrinoSqlCompatibility.recordedForm(";SELECT 1"));
        assertEquals("SELECT 1", TrinoSqlCompatibility.recordedForm("SELECT 1"));
    }

    @Test
    void aQueryHoldingNoStatementIsRecordedAsGiven() {
        for (String sql : List.of("", "   \n", ";", "; ;")) {
            assertEquals(sql, TrinoSqlCompatibility.recordedForm(sql));
            assertFalse(TrinoSqlCompatibility.holdsSeveralStatements(sql));
        }
        assertEquals("-- only a comment", TrinoSqlCompatibility.recordedForm(" -- only a comment\n;"));
    }

    @Test
    void theRecordedFormKeepsCommentsAtEitherEndAndSemicolonsInsideTheStatement() {
        assertEquals("SELECT 1 -- done", TrinoSqlCompatibility.recordedForm("SELECT 1 -- done\r\n"));
        assertEquals("SELECT 1 -- done", TrinoSqlCompatibility.recordedForm("SELECT 1 -- done\r;"));
        assertEquals("SELECT 1 /* c */", TrinoSqlCompatibility.recordedForm("SELECT 1 /* c */;"));
        assertEquals("-- lead\nSELECT 1", TrinoSqlCompatibility.recordedForm("\n-- lead\nSELECT 1;"));
        assertEquals("SELECT 'a;b' AS \"c;\"", TrinoSqlCompatibility.recordedForm("SELECT 'a;b' AS \"c;\";"));
    }

    @Test
    void aSemicolonWithAStatementOnBothSidesSeparatesTwo() {
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("SELECT 1; SELECT 2"));
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("SELECT 1; -- done"));
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("SELECT 1; /* done */"));
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("-- lead\n; SELECT 1"));
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("INSERT INTO t VALUES (3);INSERT INTO t VALUES (4)"));
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements("SELECT 1 -- a carriage return ends me\r; SELECT 2"));
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements("SELECT 1;; ;\n"));
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements(";SELECT 1"));
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements(
                "SELECT 'a; b' AS s, \"c;d\" AS q -- e; f\n/* g; h */"));
    }

    /** Athena (engine v3) runs this: a backtick-quoted name is one token, as in Trino's lexer. */
    @Test
    void aSemicolonInABacktickQuotedNameSeparatesNothing() {
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements(
                "CREATE EXTERNAL TABLE t (`a;b` int, `c``;d` int) LOCATION 's3://bucket/t/'"));
    }

    /**
     * Trino ends the comment at its first close, leaving a literal holding the semicolon; DuckDB nests it, leaving a
     * second INSERT. Either reading's second statement counts, and Athena (engine v3) rejects the query too.
     */
    @Test
    void aSecondStatementEitherTrinoOrDuckDbWouldReadCounts() {
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements(
                "INSERT INTO t VALUES (1) /* outer /* inner */ ' */; INSERT INTO t VALUES (2) -- '"));
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements("SELECT /* a /* b */ 1 AS a"));
    }

    /** Athena (engine v3) splits Hive DDL by the same rules, so it rejects this as two statements too. */
    @Test
    void aSemicolonAfterAHiveBackslashEscapeSeparatesStatementsAsOnAthena() {
        assertTrue(TrinoSqlCompatibility.holdsSeveralStatements(
                "CREATE EXTERNAL TABLE t (id string COMMENT 'a\\'; b') LOCATION 's3://bucket/t/'"));
    }

    @Test
    void parametersAreBoundInOrderEachAsAnExpressionOfItsOwn() {
        assertEquals("SELECT 'eu' AS s, (1 + 1) * 2 AS n FROM t WHERE d = date('2026-11-10')",
                TrinoSqlCompatibility.bindParameters("SELECT ? AS s, ? * 2 AS n FROM t WHERE d = date(?)",
                        List.of("'eu'", "1 + 1", "'2026-11-10'")));
        assertEquals("SELECT (ARRAY[1, 2]), (f(a, b)), 'x, y', 10, (-1)",
                TrinoSqlCompatibility.bindParameters("SELECT ?, ?, ?, ?, ?",
                        List.of("ARRAY[1, 2]", "f(a, b)", "'x, y'", "10", "-1")));
    }

    /** As on Athena (engine v3), which returns these values as the strings 1);SELECT(2, 1;SELECT2 and 1,2. */
    @Test
    void aValueThatIsNotOneExpressionIsBoundAsAStringOfItsTokens() {
        assertEquals("SELECT '1);SELECT(2' AS a, '1;SELECT2' AS b, '1,2' AS c, '''a b'',1' AS d",
                TrinoSqlCompatibility.bindParameters("SELECT ? AS a, ? AS b, ? AS c, ? AS d",
                        List.of("1); SELECT (2", "1; SELECT 2", "1, 2", "'a b', 1")));
    }

    /** Brackets as many as they close but not pairing up make no expression either; nested ones that pair do. */
    @Test
    void aValueWhoseBracketsDoNotPairUpIsBoundAsAStringOfItsTokens() {
        assertEquals("SELECT '(1]' AS a, '([)]' AS b, (f([1], (2))) AS c",
                TrinoSqlCompatibility.bindParameters("SELECT ? AS a, ? AS b, ? AS c",
                        List.of("(1]", "([ ) ]", "f([1], (2))")));
    }

    /**
     * An open quote would turn the literals after the placeholder inside out, so the semicolons in them would end
     * the statement. Athena (engine v3) returns {@code 'a} as the string {@code 'a}.
     */
    @Test
    void aValueLeavingAQuoteOpenIsBoundAsAStringAndAddsNoStatement() {
        String bound = TrinoSqlCompatibility.bindParameters(
                "INSERT INTO t VALUES (?, '); INSERT INTO t2 VALUES (9); --')", List.of("'a"));

        assertEquals("INSERT INTO t VALUES ('''a', '); INSERT INTO t2 VALUES (9); --')", bound);
        assertFalse(TrinoSqlCompatibility.holdsSeveralStatements(bound));
        assertEquals("INSERT INTO t VALUES ('''' || '); INSERT INTO t VALUES (9); --')",
                TrinoSqlCompatibility.bindParameters("INSERT INTO t VALUES (? || '); INSERT INTO t VALUES (9); --')",
                        List.of("'")));
    }

    /** As on Athena (engine v3): a lone name is a string, even where a column has it; TRUE, NULL and the like are not. */
    @Test
    void aLoneNameIsBoundAsAStringAndAKeywordAsAnExpression() {
        assertEquals("SELECT 'x' AS a, '\"x\"' AS b, (true) AS c, (null) AS d, (current_date) AS e, (abs(-3)) AS f",
                TrinoSqlCompatibility.bindParameters("SELECT ? AS a, ? AS b, ? AS c, ? AS d, ? AS e, ? AS f",
                        List.of("x", "\"x\"", "true", "null", "current_date", "abs(-3)")));
    }

    /** Athena (engine v3) returns 1 for both: the value is a token of its own. */
    @Test
    void aBoundValueIsSpacedFromTheTokensAroundIt() {
        assertEquals("SELECT 1 e2", TrinoSqlCompatibility.bindParameters("SELECT ?e2", List.of("1")));
        assertEquals("SELECT 1", TrinoSqlCompatibility.bindParameters("SELECT?", List.of("1")));
        assertEquals("SELECT (1 + 1)", TrinoSqlCompatibility.bindParameters("SELECT?", List.of("1 + 1")));
        assertEquals("SELECT 'a' || 'b'", TrinoSqlCompatibility.bindParameters("SELECT ? || 'b'", List.of("'a'")));
    }

    /** A literal goes in as if written in place, so a JSON path given as a parameter is translated too. */
    @Test
    void aLiteralValueIsBoundBareAndTranslatedAsIfWrittenInPlace() {
        assertEquals("SELECT json_extract_scalar(j, '/a b') AS v", TrinoSqlCompatibility.translate(
                TrinoSqlCompatibility.bindParameters("SELECT json_extract_scalar(j, ?) AS v",
                        List.of("'$[\"a b\"]'"))));
    }

    @Test
    void aQuestionMarkInALiteralAQuotedIdentifierOrACommentIsNotAPlaceholder() {
        assertEquals("SELECT '?' AS \"?\", 'x' AS v -- ?\n/* ? */",
                TrinoSqlCompatibility.bindParameters("SELECT '?' AS \"?\", ? AS v -- ?\n/* ? */", List.of("'x'")));
    }

    @Test
    void aStatementWithoutPlaceholdersRunsAsWrittenWhateverItsParameters() {
        assertEquals("SELECT 1 AS a", TrinoSqlCompatibility.bindParameters("SELECT 1 AS a", List.of("1")));
        assertEquals("SELECT 1 AS a", TrinoSqlCompatibility.bindParameters("SELECT 1 AS a", null));
    }

    @Test
    void aLineCommentInAValueRunsOnOverTheValuesAfterIt() {
        assertEquals("SELECT 1 AS a", TrinoSqlCompatibility.bindParameters("SELECT ? AS a", List.of("1 -- x")));
        assertEquals("INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 2 but found 1",
                assertThrows(AwsException.class, () -> TrinoSqlCompatibility.bindParameters("SELECT ? AS a, ? AS b",
                        List.of("1 -- x", "2"))).getMessage());
    }

    @Test
    void aParameterCountThatDoesNotMatchFailsAtWhereTheStatementStarts() {
        assertEquals("INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 1 but found 2",
                assertThrows(AwsException.class, () -> TrinoSqlCompatibility.bindParameters("SELECT ? AS a",
                        List.of("1", "2"))).getMessage());
        assertEquals("INVALID_PARAMETER_USAGE: line 1:1: Incorrect number of parameters: expected 1 but found 0",
                assertThrows(AwsException.class, () -> TrinoSqlCompatibility.bindParameters("SELECT ? AS a",
                        List.of())).getMessage());
        assertEquals("INVALID_PARAMETER_USAGE: line 2:1: Incorrect number of parameters: expected 1 but found 0",
                assertThrows(AwsException.class, () -> TrinoSqlCompatibility.bindParameters("-- lead\nSELECT ? AS a",
                        null)).getMessage());
    }
}
