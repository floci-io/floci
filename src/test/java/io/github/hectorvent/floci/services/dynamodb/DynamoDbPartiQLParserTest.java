package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Path;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Seg;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Stmt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbPartiQLParserTest {

    private static final String SELECT = "SELECT pk FROM \"t\" WHERE val ";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Cond firstCond(String statement, String... params) {
        var stmt = DynamoDbPartiQLParser.parse(statement, Arrays.stream(params).map(this::json).toList());
        return assertInstanceOf(Stmt.Select.class, stmt).where().get(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"B\":\"AQID\"}",
            "{\"SS\":[\"a\",\"b\"]}",
            "{\"NS\":[\"1\",\"2\"]}",
            "{\"BS\":[\"AQID\"]}",
            "{\"L\":[{\"S\":\"a\"}]}",
            "{\"M\":{\"a\":{\"S\":\"b\"}}}"
    })
    void bindsAParameterOfATypeWithNoLiteralSyntax(String param) {
        var cond = firstCond(SELECT + "= ?", param);

        var val = assertInstanceOf(Cond.Eq.class, cond).val();
        assertEquals(json(param), assertInstanceOf(PVal.Av.class, val).node());
    }

    // Parameter shape and base64 rules below were checked against real DynamoDB
    // (ap-northeast-1, 2026-09-10).
    @ParameterizedTest
    @ValueSource(strings = {"{\"Q\":\"x\"}", "{}"})
    void rejectsAParameterCarryingNoRecognisedType(String param) {
        var e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Supplied AttributeValue is empty, must contain exactly one of the supported datatypes",
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"S\":\"a\",\"N\":\"1\"}",
            "{\"B\":\"AQID\",\"SS\":[\"a\"]}",
            "{\"L\":[{\"S\":\"a\",\"N\":\"1\"}]}",
            "{\"M\":{\"k\":{\"S\":\"a\",\"N\":\"1\"}}}"
    })
    void rejectsAParameterCarryingMoreThanOneType(String param) {
        var e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Supplied AttributeValue has more than one datatypes set, "
                + "must contain exactly one of the supported datatypes", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"B\":\"not base64!!\"}",
            "{\"BS\":[\"AQID\",\"not base64!!\"]}",
            "{\"L\":[{\"B\":\"not base64!!\"}]}",
            "{\"M\":{\"k\":{\"B\":\"not base64!!\"}}}",
            "{\"B\":123}"
    })
    void rejectsABinaryParameterThatIsNotBase64(String param) {
        var e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

        assertEquals("SerializationException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void namesTheLengthOfBase64ThatIsNotAMultipleOfFour() {
        var e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", "{\"B\":\"AQI\"}"));

        assertEquals("SerializationException", e.getErrorCode());
        assertEquals("Base64 encoded length is expected a multiple of 4 bytes but found: 3", e.getMessage());
    }

    @Test
    void acceptsAnEmptyBinaryParameter() {
        var cond = firstCond(SELECT + "= ?", "{\"B\":\"\"}");

        assertInstanceOf(PVal.Av.class, assertInstanceOf(Cond.Eq.class, cond).val());
    }

    // AWS reads every parameter before it looks at the statement, so one past the
    // last placeholder still fails the request.
    @Test
    void rejectsAMalformedParameterBeyondThePlaceholders() {
        var e = assertThrows(AwsException.class,
                () -> firstCond(SELECT + "= ?", "{\"S\":\"a\"}", "{\"B\":\"not base64!!\"}"));

        assertEquals("SerializationException", e.getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({
            "'<',  {\"BOOL\":true},          BOOL",
            "'<=', {\"NULL\":true},          NULL",
            "'>',  {\"L\":[]},               L",
            "'>=', {\"M\":{}},               M",
            "'<',  {\"SS\":[\"a\"]},         SS",
            "'<',  {\"NS\":[\"1\"]},         NS",
            "'<',  {\"BS\":[\"AQID\"]},      BS"
    })
    void rejectsAnOrderingOperatorOnATypeWithNoOrdering(String op, String param, String type) {
        var e = assertThrows(AwsException.class, () -> firstCond(SELECT + op + " ?", param));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Incorrect operand type for operator or function; "
                + "operator or function: " + op + ", operand type: " + type, e.getMessage());
    }

    @Test
    void namesBetweenAsTheOperatorWhenItsBoundIsUnordered() {
        var e = assertThrows(AwsException.class,
                () -> firstCond(SELECT + "BETWEEN ? AND ?", "{\"BOOL\":true}", "{\"BOOL\":false}"));

        assertEquals("Incorrect operand type for operator or function; "
                + "operator or function: BETWEEN, operand type: BOOL", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "<>"})
    void acceptsEqualityOnATypeWithNoOrdering(String op) {
        assertDoesNotThrow(() -> firstCond(SELECT + op + " ?", "{\"SS\":[\"a\"]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"S\":\"a\"}", "{\"N\":\"1\"}", "{\"B\":\"AQID\"}"})
    void acceptsAnOrderingOperatorOnAnOrderedType(String param) {
        var cond = firstCond(SELECT + "< ?", param);

        assertEquals("<", assertInstanceOf(Cond.Cmp.class, cond).op());
    }

    @Test
    void parsesADocumentPathInASelectColumn() {
        Stmt stmt = DynamoDbPartiQLParser.parse("SELECT a.b[2].c FROM \"t\" WHERE pk = 'p'", List.of());

        Path column = assertInstanceOf(Stmt.Select.class, stmt).columns().getFirst();
        assertEquals(List.of(new Seg.Name("a"), new Seg.Name("b"), new Seg.Index(2), new Seg.Name("c")),
                column.segments());
        assertEquals("a", column.root());
        assertEquals("c", column.leafName());
    }

    @Test
    void parsesADocumentPathInAnUpdateTarget() {
        Stmt stmt = DynamoDbPartiQLParser.parse(
                "UPDATE \"t\" SET profile.sub = 'x' REMOVE tags[0] WHERE pk = 'p'", List.of());

        Stmt.Update update = assertInstanceOf(Stmt.Update.class, stmt);
        assertEquals(List.of(new Seg.Name("profile"), new Seg.Name("sub")),
                update.sets().getFirst().path().segments());
        assertEquals(List.of(new Seg.Name("tags"), new Seg.Index(0)),
                update.removes().getFirst().segments());
    }

    @Test
    void keepsABareAttributeNameAsARootOnlyPath() {
        Path path = assertInstanceOf(Cond.Eq.class, firstCond(SELECT + "= 'x'")).path();

        assertTrue(path.isRootOnly());
        assertEquals("val", path.root());
    }

    @Test
    void parsesAnInListAsOneCondition() {
        Cond.In in = assertInstanceOf(Cond.In.class, firstCond(SELECT + "IN ['a', 'b']"));

        assertEquals(2, in.values().size());
        assertEquals("val", in.path().root());
    }

    @ParameterizedTest
    @CsvSource({"IS MISSING, false", "IS NOT MISSING, true"})
    void parsesTheMissingPredicateWithItsNegation(String predicate, boolean negated) {
        Cond.Missing missing = assertInstanceOf(Cond.Missing.class, firstCond(SELECT + predicate));

        assertEquals(negated, missing.negated());
    }

    @Test
    void parsesNotAheadOfThePredicateItNegates() {
        Stmt stmt = DynamoDbPartiQLParser.parse(
                "SELECT pk FROM \"t\" WHERE NOT begins_with(val, 'a')", List.of());

        Cond.Not not = assertInstanceOf(Cond.Not.class,
                assertInstanceOf(Stmt.Select.class, stmt).where().getFirst());
        assertInstanceOf(Cond.BeginsWith.class, not.operand());
    }

    // OR binds looser than AND, so the whole clause is one top-level condition
    // rather than a key equality standing beside a filter.
    @Test
    void parsesAGroupedOrAsASingleTopLevelCondition() {
        Stmt stmt = DynamoDbPartiQLParser.parse(
                "SELECT pk FROM \"t\" WHERE (pk = 'a' AND v = 1) OR (pk = 'b')", List.of());

        List<Cond> where = assertInstanceOf(Stmt.Select.class, stmt).where();
        assertEquals(1, where.size());
        Cond.Or or = assertInstanceOf(Cond.Or.class, where.getFirst());
        assertEquals(2, or.operands().size());
        assertInstanceOf(Cond.And.class, or.operands().getFirst());
    }

    @Test
    void rejectsAListIndexThatIsNotAWholeNumber() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("SELECT a[1.5] FROM \"t\" WHERE pk = 'p'", List.of()));

        assertEquals("ValidationException", e.getErrorCode());
    }
}
