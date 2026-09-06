package io.github.hectorvent.floci.services.iot.rules;

import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Call;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.SelectAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSqlParserTest {

    @Test
    void parsesSelectAllWithTopicFilter() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'devices/+/telemetry'");

        assertEquals(List.of(new SelectAll()), query.projections());
        assertEquals("devices/+/telemetry", query.topicFilter());
        assertNull(query.where());
        assertTrue(query.isSelectAllOnly());
    }

    @Test
    void treatsKeywordsAsCaseInsensitiveAndIdentifiersAsCaseSensitive() {
        RuleSql query = RuleSqlParser.parse("select Payload as Alias from 'a/b' where Payload = 'x'");

        assertEquals(List.of(new Aliased(new Path(List.of("Payload")), "Alias")), query.projections());
        assertEquals(new Comparison(new Path(List.of("Payload")), Operator.EQUAL, RuleSql.text("x")), query.where());
    }

    @Test
    void defaultsTheAliasOfATopicFunctionToTopic() {
        RuleSql query = RuleSqlParser.parse("SELECT topic() FROM 'a/b'");

        assertEquals(List.of(new Aliased(new Call("topic", List.of()), "topic")), query.projections());
    }

    @Test
    void defaultsTheAliasOfAPathToItsLastSegment() {
        RuleSql query = RuleSqlParser.parse("SELECT state.reported.temperature FROM 'a/b'");

        assertEquals(List.of(new Aliased(new Path(List.of("state", "reported", "temperature")), "temperature")),
                query.projections());
    }

    @Test
    void parsesASegmentIndexOnTopic() {
        RuleSql query = RuleSqlParser.parse("SELECT *, topic(3) AS thingId FROM 'fleet/telemetry/+/status'");

        assertEquals(new SelectAll(), query.projections().get(0));
        assertEquals(new Aliased(new Call("topic", List.of(RuleSql.number(3))), "thingId"), query.projections().get(1));
        assertFalse(query.isSelectAllOnly());
    }

    @Test
    void parsesAGroupedPredicateInsideADisjunction() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE endswith(clientToken, ':ingest:inbound') "
                + "OR (endswith(clientToken, ':events:outbound') AND startswith(clientToken, 'gateway:'))");

        Disjunction where = assertInstanceOf(Disjunction.class, query.where());
        assertEquals(new Call("endswith", List.of(new Path(List.of("clientToken")), RuleSql.text(":ingest:inbound"))),
                where.left());
        assertInstanceOf(Conjunction.class, where.right());
    }

    @Test
    void bindsAndTighterThanOr() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE a = '1' OR b = '2' AND c = '3'");

        Disjunction where = assertInstanceOf(Disjunction.class, query.where());
        assertInstanceOf(Comparison.class, where.left());
        assertInstanceOf(Conjunction.class, where.right());
    }

    @Test
    void bindsNotTighterThanAnd() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE NOT a = '1' AND b = '2'");

        Conjunction where = assertInstanceOf(Conjunction.class, query.where());
        assertInstanceOf(Negation.class, where.left());
        assertInstanceOf(Comparison.class, where.right());
    }

    @ParameterizedTest
    @CsvSource({
            "'=',    EQUAL",
            "'<>',   NOT_EQUAL",
            "'!=',   NOT_EQUAL",
            "'<',    LESS",
            "'<=',   LESS_OR_EQUAL",
            "'>',    GREATER",
            "'>=',   GREATER_OR_EQUAL"
    })
    void parsesEveryComparisonOperator(String symbol, Operator expected) {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE level " + symbol + " 3");

        assertEquals(new Comparison(new Path(List.of("level")), expected, RuleSql.number(3)), query.where());
    }

    @Test
    void parsesNegativeNumberBooleanAndEscapedStringLiterals() {
        RuleSql query = RuleSqlParser.parse(
                "SELECT * FROM 'a/b' WHERE offset > -2 AND active = TRUE AND name = 'o''brien'");

        Conjunction outer = assertInstanceOf(Conjunction.class, query.where());
        Conjunction inner = assertInstanceOf(Conjunction.class, outer.left());
        assertEquals(new Comparison(new Path(List.of("offset")), Operator.GREATER, RuleSql.number(-2)), inner.left());
        assertEquals(new Comparison(new Path(List.of("active")), Operator.EQUAL, RuleSql.bool(true)), inner.right());
        assertEquals(new Comparison(new Path(List.of("name")), Operator.EQUAL, RuleSql.text("o'brien")), outer.right());
    }

    @Test
    void acceptsDoubleQuotedStringsAsAwsExamplesDo() {
        assertEquals(RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE startswith(name, 'ra''n')"),
                RuleSqlParser.parse("SELECT * FROM \"a/b\" WHERE startswith(name, \"ra'n\")"));
        assertEquals(RuleSql.text("say \"hi\""),
                ((Comparison) RuleSqlParser.parse("SELECT * FROM 'a' WHERE x = \"say \"\"hi\"\"\"").where()).right());
    }

    @Test
    void keepsReservedTopicFiltersVerbatim() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM '$aws/events/presence/connected/+'");

        assertEquals("$aws/events/presence/connected/+", query.topicFilter());
    }

    @Test
    void reportsTheOffendingTokenAndPositionOfAnUnsupportedFunction() {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class,
                () -> RuleSqlParser.parse("SELECT clientid() FROM 'a/b'"));

        assertEquals("clientid", failure.token());
        assertEquals(7, failure.position());
        assertTrue(failure.getMessage().contains("clientid"), failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT * FROM 'a/b' WHERE state IN ('on')          | IN",
            "SELECT * FROM 'a/b' WHERE state IS NULL            | IS",
            "SELECT CASE WHEN a THEN 1 END FROM 'a/b'           | WHEN",
            "SELECT * FROM 'a/b' WHERE encode(a, 'base64') = 'x'| encode",
            "SELECT * FROM 'a/b' WHERE a[0] = 'x'               | [",
            "SELECT * FROM 'a/b' ORDER BY a                     | ORDER"
    })
    void rejectsConstructsOutsideTheSubset(String sql, String expectedToken) {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));

        assertEquals(expectedToken, failure.token());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM",
            "SELECT * 'a/b'",
            "SELECT FROM 'a/b'",
            "SELECT * FROM 'a/b",
            "SELECT * FROM 'a/b' WHERE",
            "SELECT * FROM 'a/b' WHERE a =",
            "SELECT * FROM 'a/b' WHERE (a = 'b'",
            "SELECT topic(0) FROM 'a/b'",
            "SELECT topic('x') FROM 'a/b'",
            "SELECT topic(1, 2) FROM 'a/b'",
            "SELECT * FROM 'a/b' WHERE startswith(a) = TRUE",
            "SELECT *, FROM 'a/b'",
            "",
            "   "
    })
    void rejectsMalformedStatements(String sql) {
        assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));
    }

    @Test
    void rejectsANullStatement() {
        assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(null));
    }

    @Test
    void treatsFunctionNamesAsCaseInsensitive() {
        RuleSql query = RuleSqlParser.parse("SELECT TOPIC() FROM 'a/b' WHERE StartsWith = 'x' AND ENDSWITH(a, 'y')");

        assertEquals(List.of(new Aliased(new Call("topic", List.of()), "topic")), query.projections());
        Conjunction where = assertInstanceOf(Conjunction.class, query.where());
        assertEquals(new Call("endswith", List.of(new Path(List.of("a")), RuleSql.text("y"))), where.right());
    }

    @Test
    void rejectsANumberTooLargeToRepresent() {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class,
                () -> RuleSqlParser.parse("SELECT * FROM 'a/b' WHERE level = 99999999999999999999999"));

        assertEquals("99999999999999999999999", failure.token());
    }

    @Test
    void acceptsAStatementExactlyOnTheTokenLimit() {
        String sql = "SELECT * FROM 'a/b' WHERE a = 'x'" + " AND a = 'x'".repeat(248);

        assertEquals(1000, tokenCount(sql));
        assertNotNull(RuleSqlParser.parse(sql).where());
    }

    @Test
    void rejectsAStatementPastTheTokenLimitNamingARealToken() {
        String sql = "SELECT * FROM 'a/b' WHERE a = 'x'" + " AND a = 'x'".repeat(249);

        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));

        assertFalse(failure.token().isEmpty(), "The reported token must be a real token, not the end of input");
    }

    /** Mirrors the parser's tokenizer closely enough to pin the boundary the limit is checked against. */
    private static int tokenCount(String sql) {
        return 5 + 3 + 4 * (sql.split(" AND ", -1).length - 1);
    }

    @Test
    void rejectsAStatementDeepEnoughToOverflowTheStack() {
        String sql = "SELECT * FROM 'a/b' WHERE " + "(".repeat(5000) + "a = 'b'" + ")".repeat(5000);

        assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));
    }
}
