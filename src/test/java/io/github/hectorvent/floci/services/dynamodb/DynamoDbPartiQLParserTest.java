package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.PartiQLType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Path;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Seg;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbPartiQLParser.Stmt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        Stmt stmt = DynamoDbPartiQLParser.parse(statement, Arrays.stream(params).map(this::json).toList());
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
        Cond cond = firstCond(SELECT + "= ?", param);

        PVal val = assertInstanceOf(Cond.Eq.class, cond).val();
        assertEquals(json(param), assertInstanceOf(PVal.Av.class, val).node());
    }

    // Parameter shape and base64 rules below were checked against real DynamoDB
    // (ap-northeast-1, 2026-09-10).
    @ParameterizedTest
    @ValueSource(strings = {"{\"Q\":\"x\"}", "{}"})
    void rejectsAParameterCarryingNoRecognisedType(String param) {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

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
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

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
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", param));

        assertEquals("SerializationException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void namesTheLengthOfBase64ThatIsNotAMultipleOfFour() {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= ?", "{\"B\":\"AQI\"}"));

        assertEquals("SerializationException", e.getErrorCode());
        assertEquals("Base64 encoded length is expected a multiple of 4 bytes but found: 3", e.getMessage());
    }

    @Test
    void acceptsAnEmptyBinaryParameter() {
        Cond cond = firstCond(SELECT + "= ?", "{\"B\":\"\"}");

        assertInstanceOf(PVal.Av.class, assertInstanceOf(Cond.Eq.class, cond).val());
    }

    // AWS reads every parameter before it looks at the statement, so one past the
    // last placeholder still fails the request.
    @Test
    void rejectsAMalformedParameterBeyondThePlaceholders() {
        AwsException e = assertThrows(AwsException.class,
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
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + op + " ?", param));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Incorrect operand type for operator or function; "
                + "operator or function: " + op + ", operand type: " + type, e.getMessage());
    }

    @Test
    void namesBetweenAsTheOperatorWhenItsBoundIsUnordered() {
        AwsException e = assertThrows(AwsException.class,
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
        Cond cond = firstCond(SELECT + "< ?", param);

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
    void parsesAFromFirstUpdateAsAnUpdate() {
        Stmt stmt = DynamoDbPartiQLParser.parse(
                "FROM \"t\" WHERE pk = 'p' AND a = 1 SET c = 1, d = 2 REMOVE b RETURNING ALL NEW *", List.of());

        Stmt.Update update = assertInstanceOf(Stmt.Update.class, stmt);
        assertEquals("t", update.table());
        assertEquals(2, update.where().size());
        assertEquals(2, update.sets().size());
        assertEquals("b", update.removes().getFirst().root());
        assertEquals(DynamoDbPartiQLParser.Returning.ALL_NEW, update.returning());
        assertEquals("dynamodb:PartiQLUpdate", DynamoDbPartiQLParser.iamAction("FROM \"t\" WHERE pk = 'p' SET c = 1"));
    }

    @Test
    void parsesAFromFirstUpdateWithoutAWhere() {
        Stmt stmt = DynamoDbPartiQLParser.parse("FROM \"t\" SET c = 1", List.of());

        assertTrue(assertInstanceOf(Stmt.Update.class, stmt).where().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FROM \"t\"", "FROM \"t\" WHERE pk = 'p'", "FROM \"t\" WHERE pk = 'p' RETURNING ALL OLD *",
            "UPDATE \"t\" WHERE pk = 'p'", "UPDATE \"t\" WHERE pk = 'p' SET c = 1"})
    void refusesAnUpdateWhoseSetOrRemoveDoesNotFollowTheTable(String statement) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Statement wasn't well formed, can't be processed: Expected data manipulation", e.getMessage());
    }

    @Test
    void readsAWhereAfterTheSetOfAFromFirstUpdateAsALeftoverToken() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("FROM \"t\" SET c = 1 WHERE pk = 'p'", List.of()));

        assertEquals("Statement wasn't well formed, can't be processed: Unexpected token after expression",
                e.getMessage());
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

    @ParameterizedTest
    @CsvSource({
            "IS BOOL,                 BOOLEAN, false",
            "is boolean,              BOOLEAN, false",
            "IS NOT STRING,           STRING,  true",
            "'IS NUMERIC(5, +2)',     NUMERIC, false",
            "IS BLOB,                 BLOB,    false",
            "IS TUPLE,                TUPLE,   false",
            "IS LIST,                 LIST,    false",
            "IS NULL,                 NULL,    false"
    })
    void parsesATypeTestWithItsNegation(String predicate, PartiQLType type, boolean negated) {
        Cond.IsType isType = assertInstanceOf(Cond.IsType.class, firstCond(SELECT + predicate));

        assertEquals(type, isType.type());
        assertEquals(negated, isType.negated());
    }

    @ParameterizedTest
    @CsvSource({
            "INT,                    IntegerType",
            "BIGINT,                 Integer8Type",
            "double  precision,      DoublePrecisionType",
            "CHAR VARYING(5),        CharacterVaryingType",
            "TIME(3) WITH TIME ZONE, TimeWithTimeZoneType",
            "'DECIMAL(5, 2)',        DecimalType"
    })
    void refusesATypeDynamoDbCannotTest(String type, String className) {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "IS " + type));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Unsupported data type for IS operator: " + className + ". Supported data types are "
                + "[MISSING, NULL, BOOLEAN, BLOB, STRING, NUMERIC, STRUCT, TUPLE, LIST] at 1:26:3", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NUMBER", "MAP", "DOUBLE", "DOUBLE FOO", "'BOOL'", "\"BOOL\"", "?", ""})
    void refusesANameThatIsNotAType(String type) {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "IS " + type));

        assertEquals("Statement wasn't well formed, can't be processed: Expected type name", e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "IS INT(5)                       | is type argument integer must have arity of 0..0",
            "IS NOT DEC(1, 2, 3)             | is_not type argument decimal must have arity of 0..2",
            "IS CHAR VARYING(1, 2)           | is type argument character_varying must have arity of 0..1",
            "IS TIME(1, 2) WITH TIME ZONE    | is type argument time must have arity of 0..1",
            "IS NUMERIC(a, 1, 2)             | is type argument numeric must have arity of 0..2",
            "IS NUMERIC(-1)                  | Type parameter must be an unsigned integer literal",
            "IS NUMERIC(5.5)                 | Type parameter must be an unsigned integer literal",
            "IS NUMERIC(9223372036854775808) | Type parameter must be an unsigned integer literal",
            "IS NUMERIC(2147483648)          | Type parameter exceeded maximum value",
            "IS NUMERIC()                    | Unexpected term",
            "IS NUMERIC(5 5)                 | Expected RIGHT_PAREN",
            "IS TIME ZONE                    | Expected WITH keyword",
            "IS TIME WITH                    | Expected TIME keyword",
            "IS TIME WITH TIME               | Expected ZONE keyword"
    })
    void refusesAMalformedTypeName(String predicate, String message) {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + predicate));

        assertEquals("Statement wasn't well formed, can't be processed: " + message, e.getMessage());
    }

    @Test
    void refusesTheFirstUnsupportedTypeOnceTheWholeStatementParses() {
        AwsException parseError = assertThrows(AwsException.class,
                () -> firstCond(SELECT + "IS INT AND x IS FOO"));
        AwsException e = assertThrows(AwsException.class,
                () -> firstCond("SELECT pk FROM \"t\" WHERE x = 1 AND\n  \"val\".a IS INT OR val IS DECIMAL"));

        assertEquals("Statement wasn't well formed, can't be processed: Expected type name", parseError.getMessage());
        assertEquals("Unsupported data type for IS operator: IntegerType. Supported data types are "
                + "[MISSING, NULL, BOOLEAN, BLOB, STRING, NUMERIC, STRUCT, TUPLE, LIST] at 2:3:5", e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT pk FROM "t" WHERE                   | Unexpected term
            SELECT pk FROM "t" WHERE ;                 | Unexpected term
            SELECT pk FROM "t" WHERE NOT               | Unexpected term
            SELECT pk FROM "t" WHERE (                 | Unexpected term
            SELECT pk FROM "t" WHERE *                 | Unexpected term
            SELECT pk FROM "t" WHERE begins_with(      | Unexpected term
            SELECT pk FROM "t" WHERE pk IN ['a',       | Unexpected term
            SELECT pk FROM "t" WHERE pk = 'a' OR ;     | Unexpected term
            SELECT * FROM                              | Unexpected term
            ;                                          | Unexpected term
            UPDATE "t" SET a =                         | Unexpected term
            INSERT INTO "t"                            | Unexpected term
            INSERT INTO "t" VALUE {'pk':               | Unexpected term
            SELECT pk FROM "t" WHERE ORDER BY pk       | Unexpected keyword
            SELECT pk FROM "t" WHERE pk = - ORDER      | Unexpected keyword
            SELECT pk FROM "t" WHERE IS NULL           | Unexpected operator
            SELECT pk FROM "t" WHERE pk = LIKE         | Unexpected operator
            SELECT pk FROM "t" WHERE pk = 'a' AND      | Missing right-hand side expression of infix operator
            SELECT pk FROM "t" WHERE pk <              | Missing right-hand side expression of infix operator
            SELECT pk FROM "t" WHERE pk IN             | Missing right-hand side expression of infix operator
            UPDATE "t" SET a = a +                     | Missing right-hand side expression of infix operator
            SELECT pk FROM "t" WHERE pk BETWEEN 'a' AND | Expected expression after AND
            DELETE FROM                                | Expected identifier for simple path
            DELETE FROM WHERE pk = 'a'                 | Expected identifier for simple path
            UPDATE "t" SET a = 1,                      | Expected identifier for simple path
            INSERT INTO like VALUE {'pk': 'a'}         | Expected identifier for simple path
            SELECT FROM "t"                            | Expected identifier or an expression but found unexpected keyword 'from' in a select list.
            SELECT pk, * FROM "t" WHERE                | Other expressions may not be present in the select list when '*' is used without dot notation.
            SELECT *, pk FROM "t"                      | Other expressions may not be present in the select list when '*' is used without dot notation.
            """)
    void namesWhatItFoundWhereATermShouldStart(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("Statement wasn't well formed, can't be processed: " + message, e.getMessage());
    }

    @Test
    void namesWhatFollowsALongRunOfSigns() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse(SELECT + "= " + "+ ".repeat(300_000), List.of()));

        assertEquals("Statement wasn't well formed, can't be processed: Unexpected term", e.getMessage());
    }

    @Test
    void refusesAStarStringInTheSelectListAsAValidationError() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("SELECT '*' FROM \"t\"", List.of()));

        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void parsesNotAheadOfThePredicateItNegates() {
        Stmt stmt = DynamoDbPartiQLParser.parse(
                "SELECT pk FROM \"t\" WHERE NOT begins_with(val, 'a')", List.of());

        Cond.Not not = assertInstanceOf(Cond.Not.class,
                assertInstanceOf(Stmt.Select.class, stmt).where().getFirst());
        assertInstanceOf(Cond.BeginsWith.class, not.operand());
    }

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

    @ParameterizedTest
    @CsvSource({
            ".73e1,    .73e1",
            "-.73e1,   -.73e1",
            "+.73e1,   .73e1",
            "5.,       5.",
            "5.e1,     5.e1",
            "7.31E+6,  7.31E+6",
            "-7,       -7"
    })
    void readsANumberLiteralWithALeadingPointOrAnExponent(String literal, String value) {
        Cond cond = firstCond(SELECT + "= " + literal);

        PVal val = assertInstanceOf(Cond.Eq.class, cond).val();
        assertEquals(value, assertInstanceOf(PVal.Num.class, val).v());
    }

    @Test
    void keepsAPointAfterANameAsAPathStep() {
        Cond cond = firstCond("SELECT pk FROM \"t\" WHERE a.b = 1");

        Path path = assertInstanceOf(Cond.Eq.class, cond).path();
        assertEquals(List.of(new Seg.Name("a"), new Seg.Name("b")), path.segments());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e2147483648", "1e-2147483648", "1.5e-2147483647", "1e99999999999"})
    void refusesAnExponentThatDoesNotFitAnInt(String literal) {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= " + literal));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Statement wasn't well formed, can't be processed: ", e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" WHERE pk = 1e126                                        | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = -1e-131                                      | UNDERFLOW under root
            SELECT * FROM "t" WHERE pk = 0e126                                        | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = 0e-131                                       | UNDERFLOW under root
            SELECT * FROM "t" WHERE pk = 1e2147483646                                 | UNDERFLOW under root
            SELECT * FROM "t" WHERE pk = 0.01e2147483647                              | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = 9.99999999999999999999999999999999999999e125 | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = 123456789012345678901234567890123456789      | DIGITS under root
            SELECT * FROM "t" WHERE pk IN [1, 1e126]                                  | OVERFLOW under root
            SELECT * FROM "t" WHERE a = [1, 1e126]                                    | OVERFLOW under root[1]
            INSERT INTO "t" VALUE {'pk': 1, 'm': {'x': [1e126]}}                      | OVERFLOW under root.x[0]
            INSERT INTO "t" VALUE {'pk': 1, 'l': [{'x': <<1e126>>}]}                  | OVERFLOW under root[0].x
            SELECT * FROM "t" WHERE a = <<1e126, 1e126>>                              | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = 1e126 AND a = <<1, 1>>                       | OVERFLOW under root
            SELECT * FROM "t" WHERE a = <<1, 1>> AND pk = 1e126                       | DUPLICATES
            UPDATE "t" SET a = 1e126 WHERE pk = 1e-131                                | UNDERFLOW under root
            UPDATE "t" SET a = <<1, 1>> WHERE pk = 1e126                              | OVERFLOW under root
            UPDATE "t" SET a = set_add(a, <<true>>) WHERE pk = 1e126                  | OVERFLOW under root
            UPDATE "t" SET a = set_add(a, <<true>>) WHERE pk = 1 AND                  | MISSING_RIGHT_HAND_SIDE
            SELECT * FROM "t" WHERE pk = 1e126 AND a < [1]                            | OVERFLOW under root
            SELECT * FROM "t" WHERE pk = 1e126 AND a = 1 AND                          | MISSING_RIGHT_HAND_SIDE
            SELECT * FROM "t" WHERE a = <<1, 1>> AND                                  | MISSING_RIGHT_HAND_SIDE
            """)
    void refusesALiteralItCannotStoreOnceTheStatementParses(String statement, String message) {
        String expected = message
                .replace("UNDERFLOW", "Number underflow. Attempting to store a number with magnitude smaller than "
                        + "supported range")
                .replace("OVERFLOW", "Number overflow. Attempting to store a number with magnitude larger than "
                        + "supported range")
                .replace("DIGITS", "Attempting to store more than 38 significant digits in a Number")
                .replace("DUPLICATES", "One or more parameter values were invalid: Input collection [1, 1] "
                        + "contains duplicates! under root")
                .replace("MISSING_RIGHT_HAND_SIDE", "Statement wasn't well formed, can't be processed: "
                        + "Missing right-hand side expression of infix operator");

        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals(expected, e.getMessage());
    }

    @Test
    void checksTheDepthOfALiteralOnceTheStatementParses() {
        String deep = "[".repeat(40) + "1" + "]".repeat(40);

        assertEquals("Statement wasn't well formed, can't be processed: Unexpected token after expression",
                refusal("INSERT INTO \"t\" VALUE {'pk': 1, 'a': " + deep + "} foo"));
        assertEquals("Number overflow. Attempting to store a number with magnitude larger than supported range under root",
                refusal("UPDATE \"t\" SET a = " + deep + " WHERE pk = 1e126"));
        assertEquals("Nesting Levels have exceeded supported limits under root" + "[0]".repeat(32),
                refusal("SELECT * FROM \"t\" WHERE a = " + "[".repeat(100_000) + "1" + "]".repeat(100_000)));
        assertTrue(refusal("SELECT * FROM \"t\" WHERE a = " + "<<".repeat(100_000) + "1" + ">>".repeat(100_000))
                .startsWith("Unsupported data type in Bag."));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" a WHERE pk = 1                        | ALIASING
            SELECT * FROM "t" AS "a"                                | ALIASING
            SELECT * FROM "t"."i" AS a AT i BY b                    | ALIASING
            SELECT * FROM "t" at i ORDER BY pk                      | ALIASING
            UPDATE "t" a SET b = 1e126 WHERE pk = 1e126             | ALIASING
            FROM "t" a WHERE pk = 1 SET b = 1                       | ALIASING
            DELETE FROM "t" a WHERE pk = 1 RETURNING ALL NEW *      | ALIASING
            EXISTS(SELECT * FROM "t" a WHERE pk = 1)                | ALIASING
            SELECT * FROM ""."i"."x" a WHERE b < [1]                | ALIASING
            SELECT * FROM "t" a WHERE pk =                          | NWF Missing right-hand side expression of infix operator
            SELECT * FROM "t" AS WHERE pk = 1                       | NWF Expected identifier for AS-alias
            SELECT * FROM "t" AS exists                             | NWF Expected identifier for AS-alias
            SELECT * FROM "t" a AT                                  | NWF Expected identifier for AT-alias
            SELECT * FROM "t" BY old                                | NWF Expected identifier for BY-alias
            SELECT * FROM "t" a AS b                                | LEFTOVER
            SELECT * FROM "t" size WHERE pk = 1                     | LEFTOVER
            SELECT * FROM "t"."i"."x" WHERE pk =                    | NWF Missing right-hand side expression of infix operator
            SELECT * FROM "t"."i"."" WHERE pk = 1e126               | Path component cannot be an empty string
            SELECT * FROM "t"."i"."x" WHERE b < [1]                 | A path may contain at most 2 components in the FROM clause
            DELETE FROM "" WHERE pk = 1 RETURNING ALL NEW *         | Path component cannot be an empty string
            DELETE FROM "t" WHERE pk = 1e126 RETURNING ALL NEW *    | OVERFLOW
            DELETE FROM "t" WHERE pk = 1 RETURNING ALL NEW * x      | LEFTOVER
            INSERT INTO "t" a VALUE {'pk': 1}                       | LEFTOVER
            INSERT INTO "t" {'pk': 1e126};                          | MULTIPLE_ITEMS
            INSERT INTO "t" AS a VALUE {'pk': 1}                    | NWF Unexpected term
            SELECT * FROM "t" WHERE at = 1                          | NWF Unexpected term
            UPDATE "t" SET as = 1 WHERE pk = 1                      | NWF Expected identifier for simple path
            """)
    void refusesATableAliasOnceTheStatementParses(String statement, String message) {
        String expected = message
                .replace("ALIASING", "Aliasing is not supported")
                .replace("LEFTOVER", "NWF Unexpected token after expression")
                .replace("NWF ", "Statement wasn't well formed, can't be processed: ")
                .replace("OVERFLOW", "Number overflow. Attempting to store a number with magnitude larger than "
                        + "supported range under root")
                .replace("MULTIPLE_ITEMS", "Unsupported operation: Inserting multiple items in a single statement "
                        + "is not supported, use \"INSERT INTO tableName VALUE item\" instead");

        assertEquals(expected, refusal(statement));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            x IS INT4                               | Unsupported operation: IsType
            x is character varying;                 | Unsupported operation: IsType
            ((x) IS NULL) IS TIME WITH TIME ZONE    | Unsupported operation: IsType
            "size"                                  | Unsupported operation: Id
            begins_with                             | Unsupported operation: Id
            x.y[0]                                  | Unsupported operation: Path
            (x).y                                   | Unsupported operation: Path
            'a'[0]                                  | Unsupported operation: Path
            ?.x                                     | Unsupported operation: Path
            <<1>>[0]                                | Unsupported operation: Path
            (-1).x                                  | Unsupported operation: Path
            (x IS NOT NULL).y                       | Unsupported operation: Path
            - 1                                     | Unsupported operation: Lit
            - + 1                                   | Unsupported operation: Lit
            -((1))                                  | Unsupported operation: Lit
            1e126                                   | Unsupported operation: Lit
            NULL                                    | Unsupported operation: Lit
            MISSING                                 | Unsupported operation: Missing
            ?                                       | Unsupported operation: Parameter
            [1e126]                                 | Unsupported operation: List
            {}                                      | Unsupported operation: Struct
            <<1, 1>>                                | Unsupported operation: Bag
            x IS NOT NULL IS NULL                   | Unsupported operation: IsType
            -x IS NULL                              | Unsupported operation: IsType
            -(1) IS NULL                            | Unsupported operation: IsType
            (x).y IS NULL                           | Unsupported operation: IsType
            x IS FOO                                | NWF Expected type name
            x IS NOT                                | NWF Expected type name
            x IS INT4(1)                            | NWF is type argument integer4 must have arity of 0..0
            x IS INT4 y                             | NWF Unexpected token after expression
            x AS y                                  | NWF Unexpected token after expression
            x IS NOT NULL y                         | NWF Unexpected token after expression
            -x y                                    | NWF Unexpected token after expression
            x IS NULL.y                             | NWF Unexpected token after expression
            (x) (1)                                 | NWF Unexpected token after expression
            ()                                      | NWF Unexpected term
            (WHERE                                  | NWF Unexpected keyword
            ((x)                                    | NWF Expected RIGHT_PAREN
            (x IS NOT NULL                          | NWF Expected RIGHT_PAREN
            SLECT * FROM "t" WHERE pk = 'a'         | NWF Expected data manipulation
            """)
    void refusesAStatementThatIsOneTermByItsKind(String statement, String message) {
        assertEquals(message.replace("NWF ", "Statement wasn't well formed, can't be processed: "), refusal(statement));
    }

    private static String refusal(String statement) {
        return assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of())).getMessage();
    }

    @ParameterizedTest
    @CsvSource({
            "0.123456789123456789123456789123456789125,  0.12345678912345678912345678912345678912",
            "0.123456789123456789123456789123456789135,  0.12345678912345678912345678912345678914",
            "12345678912345678912345678912345678917.5,   12345678912345678912345678912345678918",
            "123456789123456789123456789123456789123.1,  123456789123456789123456789123456789120",
            "-0.999999999999999999999999999999999999999, -1"
    })
    void roundsADecimalLiteralToThirtyEightDigits(String literal, String value) {
        Cond cond = firstCond(SELECT + "= " + literal);

        PVal val = assertInstanceOf(Cond.Eq.class, cond).val();
        assertEquals(0, new BigDecimal(value).compareTo(new BigDecimal(assertInstanceOf(PVal.Num.class, val).v())));
    }

    @Test
    void readsASecondPointAsALeftoverToken() {
        AwsException e = assertThrows(AwsException.class, () -> firstCond(SELECT + "= 1.2.3"));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("Statement wasn't well formed, can't be processed: Unexpected token after expression",
                e.getMessage());
    }

    @Test
    void rejectsAnInOperatorWithMoreThan100Operands() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("SELECT a FROM \"t\" WHERE pk IN [" + inValues(101) + "]", List.of()));

        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("The IN operator is provided with too many operands; number of operands: 101", e.getMessage());
        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(
                "SELECT a FROM \"t\" WHERE pk IN [" + inValues(100) + "]", List.of()));
    }

    @Test
    void readsTheWholeStatementBeforeItCountsInOperands() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("SELECT a FROM \"t\" WHERE pk IN [" + inValues(101) + "] AND", List.of()));

        assertEquals("ValidationException", e.getErrorCode());
        assertNotEquals("The IN operator is provided with too many operands; number of operands: 101", e.getMessage());
    }

    private static String inValues(int count) {
        return IntStream.range(0, count).mapToObj(i -> "'v" + i + "'").collect(Collectors.joining(","));
    }
}
