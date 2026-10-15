package io.github.hectorvent.floci.services.rdsdata;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SqlParameterParser.ParsedSql;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RdsDataSqlParametersTest {

    @Test
    void rejectsRealStatementsAfterQuotedOrCommentedSemicolons() {
        for (String sql : List.of("select 1; select 2", "select ';'; select 2",
                "select $tag$;$tag$; select 2", "select 1; /* ignored */ select 2")) {
            AwsException error = assertThrows(AwsException.class, () -> RdsDataSqlParameters.parse(sql, false));
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals("Multistatements aren't supported.", error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
    }

    @Test
    void permitsSingleStatementsWithQuotedAndCommentedSemicolons() {
        for (String sql : List.of("select ';' as value; -- ignored; text\n", "select \"semi;colon\" from t",
                "select $tag$a;b$tag$", "select 1; /* outer /* inner */ select 2; */",
                "select E'it\\';s' as value")) {
            assertDoesNotThrow(() -> RdsDataSqlParameters.parse(sql, false), sql);
        }
        assertDoesNotThrow(() -> RdsDataSqlParameters.parse("select `semi;colon` from t", true));
        assertDoesNotThrow(() -> RdsDataSqlParameters.parse("select 'it\\';s' as value", true));
    }

    @Test
    void mysqlDoubleMinusOperatorsDoNotHideASecondStatement() {
        assertThrows(AwsException.class, () -> RdsDataSqlParameters.parse("select 1--1; select 2", true));
        assertDoesNotThrow(() -> RdsDataSqlParameters.parse("select 1; -- ignored; comment\n", true));
    }

    @Test
    void mysqlHashCommentsDoNotCreateASecondStatement() {
        assertDoesNotThrow(() -> RdsDataSqlParameters.parse("select 1; # ignored; select 2\n", true));
        assertThrows(AwsException.class, () -> RdsDataSqlParameters.parse("select 1; # ignored\n select 2", true));
    }

    @Test
    void rewritesNamedPlaceholdersToPositional() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select * from t where id = :id and name = :name");

        assertEquals("select * from t where id = ? and name = ?", parsed.sql());
        assertEquals(List.of("id", "name"), parsed.parameterOrder());
    }

    @Test
    void repeatsPlaceholderOncePerOccurrence() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select * from t where a = :id or b = :id");

        assertEquals("select * from t where a = ? or b = ?", parsed.sql());
        assertEquals(List.of("id", "id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideStringLiteralsAndIdentifiers() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select ':notparam', \":col:\", `x:y` from t where id = :id");

        assertEquals("select ':notparam', \":col:\", `x:y` from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void preservesPostgresCastOperatorAndCastsParameters() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select id::text from t where created = :ts::timestamp");

        assertEquals("select id::text from t where created = ?::timestamp", parsed.sql());
        assertEquals(List.of("ts"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideComments() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select 1 -- :nope\n/* :also */ where id = :id");

        assertEquals("select 1 -- :nope\n/* :also */ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideDollarQuotedStrings() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select $tag$ :nope $tag$ where id = :id");

        assertEquals("select $tag$ :nope $tag$ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashAsEscapeInStringLiteralWhenEnabled() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select * from t where note = 'it\\'s a :id' and id = :id", true);

        assertEquals("select * from t where note = 'it\\'s a :id' and id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void treatsBackslashQuoteAsClosingQuoteWhenEscapesDisabled() {
        // PostgreSQL default (standard_conforming_strings on): backslash is literal,
        // so the first unescaped quote closes the literal.
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select 'a\\' as c, :id", false);

        assertEquals("select 'a\\' as c, ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresBackslashInsideBacktickIdentifierEvenWhenEscapesEnabled() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select `a\\` , id from t where id = :id", true);

        assertEquals("select `a\\` , id from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void postgresModeDoesNotTreatBackslashAsEscapeEvenInEscapeStringLiteral() {
        ParsedSql parsed = RdsDataSqlParameters.parse(
                "select E'it\\'s :value' as v where id = :id", false);

        assertEquals("select E'it\\'s ?' as v where id = :id", parsed.sql());
        assertEquals(List.of("value"), parsed.parameterOrder());
    }
}
