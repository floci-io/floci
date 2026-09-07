package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Stmt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rejectsAParameterCarryingNoRecognisedType() {
        assertEquals("Unsupported parameter type in parameters array",
                assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", "{\"Q\":\"x\"}")).getMessage());
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
}
