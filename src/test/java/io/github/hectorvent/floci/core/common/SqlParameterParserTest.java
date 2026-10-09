package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.core.common.SqlParameterParser.Options;
import io.github.hectorvent.floci.core.common.SqlParameterParser.ParsedSql;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterParserTest {

    @Test
    void rewritesNamedPlaceholdersToPositional() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where id = :id and name = :name", Options.REDSHIFT);

        assertEquals("select * from t where id = ? and name = ?", parsed.sql());
        assertEquals(List.of("id", "name"), parsed.parameterOrder());
    }

    @Test
    void repeatsPlaceholderOncePerOccurrence() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where a = :id or b = :id", Options.REDSHIFT);

        assertEquals("select * from t where a = ? or b = ?", parsed.sql());
        assertEquals(List.of("id", "id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideStringLiteralsAndIdentifiersWhenBackticksEnabled() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select ':notparam', \":col:\", `x:y` from t where id = :id", Options.RDS_POSTGRESQL);

        assertEquals("select ':notparam', \":col:\", `x:y` from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void rdsAcceptsParameterNamesThatStartWithADigit() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select :1::int as x, :2 as y, :1", Options.RDS_POSTGRESQL);

        assertEquals("select ?::int as x, ? as y, ?", parsed.sql());
        assertEquals(List.of("1", "2", "1"), parsed.parameterOrder());
    }

    @Test
    void redshiftKeepsColonDigitAsLiteralText() {
        ParsedSql parsed = SqlParameterParser.parse("select :1", Options.REDSHIFT);

        assertEquals("select :1", parsed.sql());
        assertEquals(List.of(), parsed.parameterOrder());
    }

    @Test
    void redshiftDoesNotTreatBackticksAsQuotedIdentifiers() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select `x:y` from t where id = :id", Options.REDSHIFT);

        assertEquals("select `x?` from t where id = ?", parsed.sql());
        assertEquals(List.of("y", "id"), parsed.parameterOrder());
    }

    @Test
    void preservesPostgresCastOperatorAndCastsParameters() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select id::text from t where created = :ts::timestamp", Options.REDSHIFT);

        assertEquals("select id::text from t where created = ?::timestamp", parsed.sql());
        assertEquals(List.of("ts"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideComments() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select 1 -- :nope\n/* :also */ where id = :id", Options.REDSHIFT);

        assertEquals("select 1 -- :nope\n/* :also */ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideDollarQuotedStrings() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select $tag$ :nope $tag$ where id = :id", Options.REDSHIFT);

        assertEquals("select $tag$ :nope $tag$ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashAsEscapeInStringLiteralWhenEnabled() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where note = 'it\\'s a :id' and id = :id", Options.RDS_MYSQL);

        assertEquals("select * from t where note = 'it\\'s a :id' and id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashQuoteAsClosingQuoteWhenEscapesDisabled() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select 'a\\' as c, :id", Options.RDS_POSTGRESQL);

        assertEquals("select 'a\\' as c, ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresBackslashInsideBacktickIdentifierEvenWhenEscapesEnabled() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select `a\\` , id from t where id = :id", Options.RDS_MYSQL);

        assertEquals("select `a\\` , id from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void backslashEscapedQuoteInsideAnEscapeStringDoesNotEndTheLiteral() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select E'it\\'s :value' as v where id = :id", Options.REDSHIFT);

        assertEquals("select E'it\\'s :value' as v where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void rdsPostgresDoesNotTreatEscapeStringsSpecially() {
        ParsedSql parsed = SqlParameterParser.parse(
                "select E'it\\'s :value' as v where id = :id", Options.RDS_POSTGRESQL);

        assertEquals("select E'it\\'s ?' as v where id = :id", parsed.sql());
        assertEquals(List.of("value"), parsed.parameterOrder());
    }

    @Test
    void isMultiStatementIgnoresSemicolonsInsideCommentsLiteralsAndDollarQuotes() {
        assertFalse(SqlParameterParser.isMultiStatement("select 1 -- a; b\n", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select * from t /* x; y */ where a = 1", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select ';' as sep", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select $tag$a;b$tag$", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select 1;", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select 1 ;  \n ", Options.REDSHIFT));
    }

    @Test
    void isMultiStatementDetectsARealSecondStatement() {
        assertTrue(SqlParameterParser.isMultiStatement("select 1; select 2", Options.REDSHIFT));
        assertTrue(SqlParameterParser.isMultiStatement("insert into t values (1); delete from t", Options.REDSHIFT));
    }

    @Test
    void escapeStringWithAnEscapedQuoteIsNotSeenAsMultiStatement() {
        assertFalse(SqlParameterParser.isMultiStatement("select E'a\\';b' as v", Options.REDSHIFT));
    }

    @Test
    void isMultiStatementRespectsBacktickOption() {
        assertTrue(SqlParameterParser.isMultiStatement("select `a;b`", Options.REDSHIFT));
        assertFalse(SqlParameterParser.isMultiStatement("select `a;b`", Options.RDS_MYSQL));
    }

    @Test
    void numericNamesAreNotArraySliceBounds() {
        for (String sql : new String[] {
                "select arr[1:2] from t", "select arr[:2] from t", "select arr[1:] from t",
                "select arr[a:2] from t", "select arr[ :2] from t", "select a[b[1]:2] from t",
                "select a[f(x):2] from t", "select a[1:2][3:4] from t", "select arr[1 :2] from t",
                "select arr[1: 2] from t", "select f(x)[1 :2] from t", "select a[1][2 :3] from t",
                "select a[b[1] :2] from t", "select arr /* note */ [1:2] from t",
                "select arr -- note\n [1:2] from t", "select f(x) /* note */ [1 :2] from t",
                "select arr[1/* note */:2] from t", "select arr[:1] from t",
                "select set[1:2] from t", "select by[1:2] from t", "select values[1:2] from t"}) {
            SqlParameterParser.ParsedSql parsed =
                    SqlParameterParser.parse(sql, SqlParameterParser.Options.RDS_POSTGRESQL);
            assertEquals(sql, parsed.sql());
            assertTrue(parsed.parameterOrder().isEmpty(), sql);
        }
    }

    @Test
    void numericNamesStillParametersOutsideSlices() {
        SqlParameterParser.Options o = SqlParameterParser.Options.RDS_POSTGRESQL;
        SqlParameterParser.ParsedSql p =
                SqlParameterParser.parse("select :1, (:2), a+:3 from t where id = :4 and x in (:5,:6)", o);
        assertEquals("select ?, (?), a+? from t where id = ? and x in (?,?)", p.sql());
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), p.parameterOrder());
        SqlParameterParser.ParsedSql q = SqlParameterParser.parse("select arr[1:2] from t where id = :1", o);
        assertEquals("select arr[1:2] from t where id = ?", q.sql());
        assertEquals(List.of("1"), q.parameterOrder());
    }

    @Test
    void numericNamesInsideArrayConstructorAreParameters() {
        SqlParameterParser.Options o = SqlParameterParser.Options.RDS_POSTGRESQL;
        SqlParameterParser.ParsedSql p = SqlParameterParser.parse("select ARRAY[:1] , array [ :2, :3 ]", o);
        assertEquals("select ARRAY[?] , array [ ?, ? ]", p.sql());
        assertEquals(List.of("1", "2", "3"), p.parameterOrder());
    }

    @Test
    void numericNamesNestedInsideSubscriptsFollowTheirOwnBracket() {
        SqlParameterParser.Options o = SqlParameterParser.Options.RDS_POSTGRESQL;
        SqlParameterParser.ParsedSql p = SqlParameterParser.parse(
                "select a[f(:1):2], b[(:3) :4], ARRAY[c[1:2], :5], d[ARRAY[:6][1]:2] from t where id = :7", o);
        assertEquals("select a[f(?):2], b[(?) :4], ARRAY[c[1:2], ?], d[ARRAY[?][1]:2] from t where id = ?",
                p.sql());
        assertEquals(List.of("1", "3", "5", "6", "7"), p.parameterOrder());
    }

    @Test
    void numericNamesAfterAnOperatorInsideASubscriptAreParameters() {
        SqlParameterParser.Options o = SqlParameterParser.Options.RDS_POSTGRESQL;
        SqlParameterParser.ParsedSql p = SqlParameterParser.parse(
                "select arr[1 + :1], b[2 * :2 : 3], c[x, :3], d[(1) - :4], e[1:2 + :5] from t", o);
        assertEquals("select arr[1 + ?], b[2 * ? : 3], c[x, ?], d[(1) - ?], e[1:2 + ?] from t", p.sql());
        assertEquals(List.of("1", "2", "3", "4", "5"), p.parameterOrder());
    }

    @Test
    void numericNamesInASubscriptAfterACommentAreSliceBoundsOrParametersByPosition() {
        SqlParameterParser.Options o = SqlParameterParser.Options.RDS_POSTGRESQL;
        SqlParameterParser.ParsedSql p = SqlParameterParser.parse(
                "select arr /* a */ [1:2], ARRAY /* b */ [:3], arr /* c */ [1 /* d */ + :4] from t", o);
        assertEquals("select arr /* a */ [1:2], ARRAY /* b */ [?], arr /* c */ [1 /* d */ + ?] from t",
                p.sql());
        assertEquals(List.of("3", "4"), p.parameterOrder());
    }
}
