package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterParserTest {

    @Test
    void rewritesNamedPlaceholdersToPositional() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where id = :id and name = :name");

        assertEquals("select * from t where id = ? and name = ?", parsed.sql());
        assertEquals(List.of("id", "name"), parsed.parameterOrder());
    }

    @Test
    void repeatsPlaceholderOncePerOccurrence() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where a = :id or b = :id");

        assertEquals("select * from t where a = ? or b = ?", parsed.sql());
        assertEquals(List.of("id", "id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideStringLiteralsAndIdentifiers() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select ':notparam', \":col:\", `x:y` from t where id = :id");

        assertEquals("select ':notparam', \":col:\", `x:y` from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void preservesPostgresCastOperatorAndCastsParameters() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select id::text from t where created = :ts::timestamp");

        assertEquals("select id::text from t where created = ?::timestamp", parsed.sql());
        assertEquals(List.of("ts"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideComments() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select 1 -- :nope\n/* :also */ where id = :id");

        assertEquals("select 1 -- :nope\n/* :also */ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideDollarQuotedStrings() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select $tag$ :nope $tag$ where id = :id");

        assertEquals("select $tag$ :nope $tag$ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashAsEscapeInStringLiteralWhenEnabled() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select * from t where note = 'it\\'s a :id' and id = :id", true);

        assertEquals("select * from t where note = 'it\\'s a :id' and id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashQuoteAsClosingQuoteWhenEscapesDisabled() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select 'a\\' as c, :id", false);

        assertEquals("select 'a\\' as c, ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresBackslashInsideBacktickIdentifierEvenWhenEscapesEnabled() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select `a\\` , id from t where id = :id", true);

        assertEquals("select `a\\` , id from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void backslashEscapedQuoteInsideAnEscapeStringDoesNotEndTheLiteral() {
        SqlParameterParser.ParsedSql parsed = SqlParameterParser.parse(
                "select E'it\\'s :value' as v where id = :id");

        assertEquals("select E'it\\'s :value' as v where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void isMultiStatementIgnoresSemicolonsInsideCommentsLiteralsAndDollarQuotes() {
        assertFalse(SqlParameterParser.isMultiStatement("select 1 -- a; b\n"));
        assertFalse(SqlParameterParser.isMultiStatement("select * from t /* x; y */ where a = 1"));
        assertFalse(SqlParameterParser.isMultiStatement("select ';' as sep"));
        assertFalse(SqlParameterParser.isMultiStatement("select $tag$a;b$tag$"));
        assertFalse(SqlParameterParser.isMultiStatement("select 1;"));
        assertFalse(SqlParameterParser.isMultiStatement("select 1 ;  \n "));
    }

    @Test
    void isMultiStatementDetectsARealSecondStatement() {
        assertTrue(SqlParameterParser.isMultiStatement("select 1; select 2"));
        assertTrue(SqlParameterParser.isMultiStatement("insert into t values (1); delete from t"));
    }

    @Test
    void escapeStringWithAnEscapedQuoteIsNotSeenAsMultiStatement() {
        assertFalse(SqlParameterParser.isMultiStatement("select E'a\\';b' as v"));
    }
}
