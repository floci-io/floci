package io.github.hectorvent.floci.services.iot.rules;

import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Expr;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of parsing that are easy to get wrong and invisible in a happy path test: words
 * that begin with a keyword, statements without the usual spacing, how chains of AND and OR
 * nest, and the literal forms at the edge of the grammar.
 */
class RuleSqlParserRobustnessTest {

    private static final String CANONICAL =
            "SELECT *, topic(3) AS id FROM 'a/b' WHERE a = 'x' AND NOT (b <> 'y' OR c > 1)";

    @ParameterizedTest
    @ValueSource(strings = {
            "orange", "asset", "notify", "android", "frombar", "true_flag", "nullable", "selector",
            "whereabouts", "order", "ins", "existing", "valueOf", "case_id", "Andy", "Orb", "not_ready",
            "select_count", "from_device", "as_of", "fromDate", "topic_name", "startswith_x"
    })
    void aWordThatBeginsWithAKeywordIsAFieldName(String field) {
        RuleSql query = RuleSqlParser.parse("SELECT " + field + " FROM 'a/b' WHERE " + field + " = 'x'");

        assertEquals(List.of(new Aliased(new Path(List.of(field)), field)), query.projections());
        assertEquals(new Comparison(new Path(List.of(field)), Operator.EQUAL, RuleSql.text("x")), query.where());
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "Select", "sElEcT", "from", "As", "and", "Or", "NoT", "True", "false", "Null"})
    void aKeywordIsRecognisedInAnyCase(String keyword) {
        assertThrows(RuleSqlParseException.class,
                () -> RuleSqlParser.parse("SELECT " + keyword + " FROM 'a/b'"),
                keyword + " must be a keyword, not a field name");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT *,topic(3)AS id FROM 'a/b' WHERE a='x'AND NOT(b<>'y'OR c>1)",
            "SELECT\t*,\n\ttopic(3) AS id\nFROM 'a/b'\nWHERE a = 'x'\r\nAND NOT (b <> 'y' OR c > 1)",
            "   SELECT *, topic(3) AS id FROM 'a/b' WHERE a = 'x' AND NOT (b <> 'y' OR c > 1)   \n",
            "SELECT   *  ,   topic( 3 )   AS   id   FROM   'a/b'   WHERE   a   =   'x'   AND   NOT   (   b   <>   'y'   OR   c   >   1   )",
            "select *, TOPIC(3) as id from 'a/b' where a = 'x' and not (b <> 'y' or c > 1)"
    })
    void spacingAndCaseDoNotChangeTheParse(String variant) {
        assertEquals(RuleSqlParser.parse(CANONICAL), RuleSqlParser.parse(variant));
    }

    @Test
    void andAndOrChainsAreLeftAssociative() {
        Expr a = comparison("a");
        Expr b = comparison("b");
        Expr c = comparison("c");
        Expr d = comparison("d");

        assertEquals(new Conjunction(new Conjunction(a, b), c),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE a = 'x' AND b = 'x' AND c = 'x'").where());
        assertEquals(new Disjunction(new Disjunction(a, b), c),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE a = 'x' OR b = 'x' OR c = 'x'").where());
        assertEquals(new Disjunction(new Disjunction(a, new Conjunction(b, c)), d),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE a = 'x' OR b = 'x' AND c = 'x' OR d = 'x'").where());
        assertEquals(new Disjunction(new Negation(a), b),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE NOT a = 'x' OR b = 'x'").where());
        assertEquals(new Negation(new Negation(a)),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE NOT NOT a = 'x'").where());
        assertEquals(new Negation(new Disjunction(a, b)),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE NOT (a = 'x' OR b = 'x')").where());
    }

    @Test
    void keepsUnicodeInFiltersAndLiterals() {
        RuleSql query = RuleSqlParser.parse("SELECT * FROM 'devices/ä/+' WHERE name = '日本' OR emoji = '🚀'");

        assertEquals("devices/ä/+", query.topicFilter());
        Disjunction where = (Disjunction) query.where();
        assertEquals(RuleSql.text("日本"), ((Comparison) where.left()).right());
        assertEquals(RuleSql.text("🚀"), ((Comparison) where.right()).right());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "''         | text",
            "0          | long:0",
            "007        | long:7",
            "-0         | long:0",
            "42         | long:42",
            "-17        | long:-17",
            "1.50       | decimal:1.50",
            "-0.001     | decimal:-0.001",
            "9223372036854775807 | long:9223372036854775807"
    })
    void parsesEveryAcceptedLiteralForm(String literal, String expected) {
        Comparison where = (Comparison) RuleSqlParser.parse("SELECT * FROM 't' WHERE a = " + literal).where();

        RuleSql.Literal value = switch (expected.split(":")[0]) {
            case "long" -> RuleSql.number(Long.parseLong(expected.substring(5)));
            case "decimal" -> RuleSql.decimal(new BigDecimal(expected.substring(8)));
            default -> RuleSql.text("");
        };
        assertEquals(value, where.right());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.     | .",
            ".5     | .",
            "1e5    | e5",
            "+1     | +",
            "0x1F   | x1F",
            "1_000  | _000",
            "1..2   | .",
            "--1    | -"
    })
    void rejectsLiteralFormsOutsideTheGrammarNamingTheToken(String literal, String token) {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class,
                () -> RuleSqlParser.parse("SELECT * FROM 't' WHERE a = " + literal));

        assertEquals(token, failure.token());
    }

    @Test
    void acceptsIdentifiersWithDigitsAndUnderscoresAndLongNames() {
        String longName = "f".repeat(1000);
        RuleSql query = RuleSqlParser.parse("SELECT field1, _private, a1.b2._c3, " + longName + " FROM 't'");

        assertEquals(List.of(
                new Aliased(new Path(List.of("field1")), "field1"),
                new Aliased(new Path(List.of("_private")), "_private"),
                new Aliased(new Path(List.of("a1", "b2", "_c3")), "_c3"),
                new Aliased(new Path(List.of(longName)), longName)), query.projections());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT 1field FROM 't'          | 1",
            "SELECT a. FROM 't'              | FROM",
            "SELECT .a FROM 't'              | .",
            "SELECT a..b FROM 't'            | .",
            "SELECT a.from FROM 't'          | from",
            "SELECT a b FROM 't'             | b",
            "SELECT a AS FROM 't'            | FROM",
            "SELECT a AS 'x' FROM 't'        | x",
            "SELECT a AS b.c FROM 't'        | .",
            "SELECT * AS all FROM 't'        | AS",
            "SELECT ** FROM 't'              | *",
            "SELECT , a FROM 't'             | ,",
            "SELECT a, FROM 't'              | FROM",
            "SELECT a,, b FROM 't'           | ,",
            "SELECT * FROM 't' 'u'           | u",
            "SELECT * FROM 't' WHERE WHERE a | WHERE",
            "SELECT * FROM 't' WHERE ()      | )",
            "SELECT * FROM 't' WHERE (a = 'x'))  | )",
            "SELECT * FROM 't' WHERE a = 'x' AND | ''",
            "SELECT * FROM 't' WHERE a = 'x' OR OR b = 'y' | OR",
            "SELECT * FROM 't' WHERE NOT     | ''",
            "SELECT * FROM 't' WHERE a == 'x'    | =",
            "SELECT * FROM 't' WHERE a => 1      | >",
            "SELECT * FROM 't' WHERE a =< 1      | <",
            "SELECT * FROM 't' WHERE a >< 1      | <",
            "SELECT * FROM 't' WHERE a = 'x' = 'y' | =",
            "SELECT * FROM 't' WHERE a && b      | &",
            "SELECT * FROM 't' WHERE a ! = b     | !",
            "SELECT * FROM 't' WHERE topic(1)(2) = 'x' | (",
            "SELECT * FROM 't' WHERE startswith(a, 'x',) | )",
            "SELECT * FROM 't' WHERE startswith(, 'x')   | ,",
            "SELECT * FROM 't' WHERE startswith(a 'x')   | x",
            "SELECT * FROM 't' WHERE endswith(a, 'x', 'y') | endswith",
            "SELECT * FROM 't' WHERE topic(a) = 'x'      | topic",
            "SELECT * FROM 't' WHERE topic(1.5) = 'x'    | topic",
            "SELECT * FROM 't' WHERE topic(-1) = 'x'     | topic",
            "SELECT * FROM 't' WHERE topic(99999999999999999999) = 'x' | 99999999999999999999",
            "SELECT * FROM ''                | ''",
            "SELECT * FROM '   '             | '   '",
            "SELECT * FROM t                 | t",
            "SELECT * FROM topic/subtopic    | /",
            "SELECT * FROM 'a/b' -- comment  | -",
            "SELECT * FROM 'a/b'; DROP x     | ;",
            "SELECT * FROM 'a/b' /* c */     | /"
    })
    void rejectsMalformedShapesNamingTheFirstTokenItCannotPlace(String sql, String token) {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));

        assertEquals(token, failure.token(), failure.getMessage());
    }

    private static Expr comparison(String field) {
        return new Comparison(new Path(List.of(field)), Operator.EQUAL, RuleSql.text("x"));
    }
}
