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
}
