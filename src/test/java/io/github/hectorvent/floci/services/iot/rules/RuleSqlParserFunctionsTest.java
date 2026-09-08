package io.github.hectorvent.floci.services.iot.rules;

import io.github.hectorvent.floci.services.iot.rules.RuleSql.Aliased;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.ArrayLiteral;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Call;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Comparison;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Conjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Disjunction;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Membership;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Negation;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Operator;
import io.github.hectorvent.floci.services.iot.rules.RuleSql.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleSqlParserFunctionsTest {

    @ParameterizedTest
    @CsvSource({
            "clientid,   clientid",
            "CLIENTID,   clientid",
            "timestamp,  timestamp",
            "accountid,  accountid",
            "newuuid,    newuuid",
            "NewUUID,    newuuid"
    })
    void parsesTheMessageFunctionsWithoutArgumentsUnderTheirCanonicalName(String written, String canonical) {
        RuleSql query = RuleSqlParser.parse("SELECT " + written + "() FROM 'a/b' WHERE " + written + "() = 'x'");

        assertEquals(List.of(new Aliased(new Call(canonical, List.of()), canonical)), query.projections());
        assertEquals(new Comparison(new Call(canonical, List.of()), Operator.EQUAL, RuleSql.text("x")), query.where());
    }

    @ParameterizedTest
    @CsvSource({
            "isNull,      isNull",
            "isnull,      isNull",
            "ISNULL,      isNull",
            "isUndefined, isUndefined",
            "isundefined, isUndefined"
    })
    void parsesIsNullAndIsUndefinedWithOneArgumentUnderTheirCanonicalName(String written, String canonical) {
        RuleSql query = RuleSqlParser.parse("SELECT " + written + "(a.b) FROM 'a/b' WHERE " + written + "(topic(3))");

        assertEquals(List.of(new Aliased(new Call(canonical, List.of(new Path(List.of("a", "b")))), canonical)),
                query.projections());
        assertEquals(new Call(canonical, List.of(new Call("topic", List.of(RuleSql.number(3))))), query.where());
    }

    @Test
    void parsesInAgainstAFieldAndAgainstAnArrayLiteral() {
        assertEquals(new Membership(new Path(List.of("x")), new Path(List.of("arr"))),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE x IN arr").where());
        assertEquals(new Membership(RuleSql.number(3), new ArrayLiteral(List.of(RuleSql.number(1), RuleSql.text("a")))),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE 3 in [1, 'a']").where());
    }

    @Test
    void inBindsLikeAComparison() {
        Membership membership = new Membership(new Path(List.of("a")), new ArrayLiteral(List.of(RuleSql.number(1))));
        Comparison comparison = new Comparison(new Path(List.of("b")), Operator.EQUAL, RuleSql.number(2));

        assertEquals(new Conjunction(membership, comparison),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE a IN [1] AND b = 2").where());
        assertEquals(new Disjunction(comparison, membership),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE b = 2 OR a IN [1]").where());
        assertEquals(new Negation(membership),
                RuleSqlParser.parse("SELECT * FROM 't' WHERE NOT a IN [1]").where());
    }

    @Test
    void parsesArrayLiteralsOfAnyShapeAndRequiresAnAliasToSelectOne() {
        RuleSql query = RuleSqlParser.parse("SELECT [] AS empty, [1, 'a', TRUE, NULL, x.y, topic(1), [2]] AS mixed FROM 't'");

        assertEquals(List.of(
                new Aliased(new ArrayLiteral(List.of()), "empty"),
                new Aliased(new ArrayLiteral(List.of(
                        RuleSql.number(1), RuleSql.text("a"), RuleSql.bool(true), RuleSql.nullValue(),
                        new Path(List.of("x", "y")), new Call("topic", List.of(RuleSql.number(1))),
                        new ArrayLiteral(List.of(RuleSql.number(2))))), "mixed")), query.projections());
        assertEquals("[", assertThrows(RuleSqlParseException.class,
                () -> RuleSqlParser.parse("SELECT [1] FROM 't'")).token());
    }

    @ParameterizedTest
    @ValueSource(strings = {"index", "inbound", "int_value", "IN_flag", "isNullable", "isUndefinedYet", "timestamps"})
    void aWordThatBeginsWithANewKeywordOrFunctionIsStillAFieldName(String field) {
        assertEquals(List.of(new Aliased(new Path(List.of(field)), field)),
                RuleSqlParser.parse("SELECT " + field + " FROM 't'").projections());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT in FROM 't'                     | in",
            "SELECT * FROM 't' WHERE x IN           | ''",
            "SELECT * FROM 't' WHERE IN [1]         | IN",
            "SELECT * FROM 't' WHERE x IN [1,       | ''",
            "SELECT * FROM 't' WHERE x IN [,]       | ,",
            "SELECT * FROM 't' WHERE x IN [1 2]     | 2",
            "SELECT * FROM 't' WHERE x IN [1]]      | ]",
            "SELECT * FROM 't' WHERE x IN (1, 2)    | (",
            "SELECT * FROM 't' WHERE x IN [1] IN [2] | IN",
            "SELECT * FROM 't' WHERE x = 1 IN [1]   | IN",
            "SELECT * FROM 't' WHERE x NOT IN [1]   | NOT",
            "SELECT timestamp(1) FROM 't'           | timestamp",
            "SELECT clientid('x') FROM 't'          | clientid",
            "SELECT accountid(a) FROM 't'           | accountid",
            "SELECT newuuid(1) FROM 't'             | newuuid",
            "SELECT isNull() AS n FROM 't'          | isNull",
            "SELECT isNull(a, b) AS n FROM 't'      | isNull",
            "SELECT isUndefined() AS u FROM 't'     | isUndefined",
            "SELECT timestamp FROM 't' WHERE timestamp( | ''"
    })
    void rejectsMalformedUsesNamingTheToken(String sql, String token) {
        RuleSqlParseException failure = assertThrows(RuleSqlParseException.class, () -> RuleSqlParser.parse(sql));

        assertEquals(token, failure.token(), failure.getMessage());
    }
}
