package io.github.hectorvent.floci.services.rdsdata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RdsDataSqlParametersTest {

    @Test
    void rewritesNamedPlaceholdersToPositional() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select * from t where id = :id and name = :name");

        assertEquals("select * from t where id = ? and name = ?", parsed.sql());
        assertEquals(List.of("id", "name"), parsed.parameterOrder());
    }

    @Test
    void repeatsPlaceholderOncePerOccurrence() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select * from t where a = :id or b = :id");

        assertEquals("select * from t where a = ? or b = ?", parsed.sql());
        assertEquals(List.of("id", "id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideStringLiteralsAndIdentifiers() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select ':notparam', \":col:\", `x:y` from t where id = :id");

        assertEquals("select ':notparam', \":col:\", `x:y` from t where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void preservesPostgresCastOperatorAndCastsParameters() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select id::text from t where created = :ts::timestamp");

        assertEquals("select id::text from t where created = ?::timestamp", parsed.sql());
        assertEquals(List.of("ts"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideComments() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select 1 -- :nope\n/* :also */ where id = :id");

        assertEquals("select 1 -- :nope\n/* :also */ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }

    @Test
    void ignoresColonsInsideDollarQuotedStrings() {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(
                "select $tag$ :nope $tag$ where id = :id");

        assertEquals("select $tag$ :nope $tag$ where id = ?", parsed.sql());
        assertEquals(List.of("id"), parsed.parameterOrder());
    }
}
