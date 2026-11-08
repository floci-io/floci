package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.Cond;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.PVal;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.PartiQLType;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.Path;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.Seg;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser.Stmt;
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT m.inner FROM "t"
            SELECT pk, l[0].VALUE FROM "t"
            SELECT pk FROM "t" WHERE m.a.size.b = 1
            SELECT pk FROM "t" WHERE attribute_exists(m.key)
            SELECT pk FROM "t" WHERE m.'a' = 1
            SELECT pk FROM "t" WHERE m.? = 1
            SELECT pk FROM "t" WHERE m.true = 1
            SELECT pk FROM "t" WHERE m.
            SELECT pk FROM "t" WHERE m.inner = <<>>
            SELECT pk FROM "t" WHERE m.inner = 1 AND n =
            SELECT pk FROM "t".inner
            UPDATE "t" SET m.select = 1 WHERE pk = 1
            UPDATE "t" SET a = m.null WHERE pk = 1
            UPDATE "t" REMOVE m.from WHERE pk = 1
            DELETE FROM "t".inner WHERE pk = 1
            INSERT INTO "t".inner VALUE {'pk': 1}
            """)
    void refusesAKeywordOrATermAfterAPathDot(String statement) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("Statement wasn't well formed, can't be processed: Invalid path dot component", e.getMessage());
    }

    @Test
    void readsAQuotedKeywordAfterAPathDot() {
        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(
                "SELECT m.\"inner\", m.foo FROM \"t\" WHERE m.attribute_exists = 1", List.of()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" WHERE foo(1)                                 | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE EXISTS(pk) = 1                         | Unrecognized function: exists at 1:25:6
            SELECT * FROM "t" WHERE "FOO"(1)                               | Unrecognized function: foo at 1:25:5
            SELECT * FROM "t" WHERE pk = set_add(a, <<1>>)                 | Unrecognized function: set_add at 1:30:7
            SELECT * FROM "t" WHERE foo(bar(1))                            | Unrecognized function: bar at 1:29:3
            SELECT * FROM "t" WHERE foo(1) AND pk = <<>>                   | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE foo(1) < pk                            | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE foo(1) BETWEEN 1 AND 2                 | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE foo(1) IN [bar(2)]                     | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE NOT (foo(1) = bar(2))                  | Unrecognized function: foo at 1:30:3
            SELECT * FROM "t" WHERE pk IN [foo(1)]                         | Unrecognized function: foo at 1:32:3
            SELECT * FROM "t" WHERE pk < foo(1)                            | Unrecognized function: foo at 1:30:3
            SELECT * FROM "t" WHERE true = foo(1)                          | Unrecognized function: foo at 1:32:3
            SELECT * FROM "t" WHERE pk = size(foo(1))                      | Unrecognized function: foo at 1:35:3
            SELECT * FROM "t" WHERE foo(size(a))                           | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE foo(attribute_exists(a))               | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE pk = <<>> AND foo(1)                   | Empty bags are not supported
            SELECT * FROM "t" WHERE foo(<<>>)                              | Empty bags are not supported
            SELECT * FROM "t" WHERE pk = [foo(1), <<>>]                    | Unsupported data type: Call under key root[0] at 1:31:3
            SELECT * FROM "t" WHERE pk = {'a': [foo(1)]}                   | Unsupported data type: Call under key root.a[0] at 1:37:3
            SELECT * FROM "t" WHERE pk = <<foo(1)>>                        | Unsupported data type in Bag. DynamoDB only supports either numbers or strings in bags at 1:32:3
            SELECT * FROM "t" WHERE pk = <<'a', foo(1)>>                   | Unsupported data type: Call under key root at 1:37:3
            SELECT * FROM "t" WHERE foo(1).a = 1                           | Unexpected path component at 1:25:3
            SELECT * FROM "t" WHERE foo(<<>>)[0]                           | Unexpected path component at 1:25:3
            SELECT * FROM "t" WHERE pk = foo(1).a                          | Unexpected path component at 1:30:3
            SELECT * FROM "t" WHERE foo(<<>>) IS NULL                      | The left hand side argument to IS must be a Variable Reference, Literal, or Path
            SELECT * FROM "t" WHERE count(*) IS NULL                       | The left hand side argument to IS must be a Variable Reference, Literal, or Path
            SELECT * FROM "t" WHERE list_append(a, [foo(1)]) AND pk = <<>> | Unsupported data type: Call under key root[0] at 1:41:3
            SELECT * FROM "t" WHERE pk = list_append(a, [1])               | The function is not allowed in a condition expression; function: list_append
            SELECT * FROM "t" WHERE count(<<>>) = 1                        | Unsupported token in expression: count at 1:25:5
            SELECT * FROM "t" WHERE foo(1) AND SUM(DISTINCT a)             | Unrecognized function: foo at 1:25:3
            UPDATE "t" SET a = foo(1) WHERE pk = 1                         | Unrecognized function: foo at 1:20:3
            UPDATE "t" SET a = 'x' + foo(1) WHERE pk = 1                   | Unrecognized function: foo at 1:26:3
            UPDATE "t" SET a = foo(list_append(b, [1])) WHERE pk = 1       | Unrecognized function: foo at 1:20:3
            UPDATE "t" SET a = list_append(foo(1), [1]) WHERE pk = 1       | Unrecognized function: foo at 1:32:3
            UPDATE "t" SET a = foo(1) WHERE bar(2)                         | Unrecognized function: bar at 1:33:3
            UPDATE "t" SET a = foo(1) WHERE pk = <<>>                      | Empty bags are not supported
            UPDATE "t" SET a = size(b), c = foo(1) WHERE pk = 1            | The function is not allowed in an update expression; function: size
            UPDATE "t" SET a = foo(attribute_exists(b)) WHERE pk = 1       | The function is not allowed in an update expression; function: attribute_exists
            UPDATE "t" SET a = [size(b)] WHERE pk = 1                      | Unsupported data type: Call under key root[0] at 1:21:4
            UPDATE "t" SET a = count(*) WHERE pk = 1                       | Unsupported token in expression: count at 1:20:5
            SELECT foo(<<>>) FROM "t" WHERE bar(1)                         | Unexpected path component at 1:8:3
            SELECT pk, COUNT(*) FROM "t"                                   | Unexpected path component at 1:12:5
            SELECT COUNT(DISTINCT pk) FROM "t"                             | Unexpected path component at 1:8:5
            SELECT foo(1).a FROM "t"                                       | Unexpected path component at 1:8:3
            SELECT foo(1) FROM a.b.c                                       | A path may contain at most 2 components in the FROM clause
            INSERT INTO "t" VALUE {'pk': foo(<<>>)}                        | Unsupported data type: foo under key root at 1:30:3
            INSERT INTO "t" VALUE {'pk': 'a', 'x': LOWER(1), 'y': <<>>}    | Unsupported data type: lower under key root at 1:40:5
            INSERT INTO "t" VALUE {'pk': 'a', 'x': {'y': foo(<<>>)}}       | Unsupported data type: Call under key root.y at 1:46:3
            INSERT INTO "t" VALUE {'pk': 'a', 'x': <<>>, 'y': foo(1)}      | Empty bags are not supported
            """)
    void refusesACallByWhereItStands(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" WHERE foo(*)         | foo(*) is not allowed
            SELECT * FROM "t" WHERE FOO(*, 1)      | FOO(*) is not allowed
            SELECT * FROM "t" WHERE SUM(*)         | sum(*) is not allowed
            SELECT COUNT() FROM "t"                | Aggregate functions are always unary
            SELECT * FROM "t" WHERE foo(1 2)       | Expected RIGHT_PAREN
            SELECT foo(1 FROM "t"                  | Expected RIGHT_PAREN
            SELECT * FROM "t" WHERE foo(           | Unexpected term
            SELECT * FROM "t" WHERE foo(1,)        | Unexpected term
            SELECT * FROM "t" WHERE foo(m.inner)   | Invalid path dot component
            SELECT * FROM "t" WHERE foo(1) =       | Missing right-hand side expression of infix operator
            INSERT INTO "t" VALUE {'pk': foo(1 2)} | Expected RIGHT_PAREN
            """)
    void aSyntaxErrorInACallWinsOverTheCall(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("Statement wasn't well formed, can't be processed: " + message, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"f(", "(", "NOT "})
    void answersAStatementNestedTooDeepWithAnInternalFailure(String nesting) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(
                "SELECT * FROM \"t\" WHERE " + nesting.repeat(1_000_000), List.of()));

        assertEquals("InternalFailure", e.getErrorCode());
        assertEquals(500, e.getHttpStatus());
    }

    @Test
    void stillRefusesAConditionFunctionAsAValue() {
        AwsException e = assertThrows(AwsException.class,
                () -> DynamoDbPartiQLParser.parse("SELECT * FROM \"t\" WHERE pk = size(a)", List.of()));

        assertEquals("Expected value literal or ?, got: size", e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            attribute_exists()                      | attribute_exists     | 0
            ATTRIBUTE_EXISTS(a, b)                  | attribute_exists     | 2
            "attribute_not_exists"()                | attribute_not_exists | 0
            attribute_exists('x', 'y')              | attribute_exists     | 2
            attribute_exists(?, ?, 1)               | attribute_exists     | 3
            a = 1 AND size()                        | size                 | 0
            size(a, b) = 1                          | size                 | 2
            size() < true                           | size                 | 0
            size() IN [1]                           | size                 | 0
            size() BETWEEN 1 AND 2                  | size                 | 0
            begins_with(a)                          | begins_with          | 1
            contains(a, b, c)                       | contains             | 3
            attribute_type()                        | attribute_type       | 0
            NOT attribute_exists()                  | attribute_exists     | 0
            (attribute_exists())                    | attribute_exists     | 0
            a = 'x' OR attribute_exists()           | attribute_exists     | 0
            a = attribute_exists()                  | attribute_exists     | 0
            a = size()                              | size                 | 0
            a IN [attribute_exists()]               | attribute_exists     | 0
            attribute_exists() < 1                  | attribute_exists     | 0
            attribute_exists() BETWEEN 1 AND 2      | attribute_exists     | 0
            attribute_exists() = attribute_exists() | attribute_exists     | 0
            attribute_exists(size())                | size                 | 0
            attribute_exists(size(), size())        | size                 | 0
            begins_with(size(), 'x')                | size                 | 0
            """)
    void refusesTheWrongNumberOfOperands(String condition, String function, int operands) {
        assertEquals("Incorrect number of operands for operator or function; operator or function: " + function
                + ", number of operands: " + operands, refusal(condition, 2));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            attribute_exists('x')                                     | attribute_exists
            attribute_exists(?)                                       | attribute_exists
            attribute_not_exists(1)                                   | attribute_not_exists
            attribute_exists(true)                                    | attribute_exists
            attribute_exists(null)                                    | attribute_exists
            attribute_exists([1])                                     | attribute_exists
            attribute_exists({'x': 1})                                | attribute_exists
            attribute_exists(<<'x'>>)                                 | attribute_exists
            attribute_not_exists(size(a))                             | attribute_not_exists
            attribute_exists(attribute_exists(a))                     | attribute_exists
            attribute_exists(attribute_exists(1), attribute_exists()) | attribute_exists
            a = attribute_exists(1)                                   | attribute_exists
            attribute_exists(a) = attribute_exists('x')               | attribute_exists
            """)
    void requiresADocumentPath(String condition, String function) {
        assertEquals("Operator or function requires a document path; operator or function: " + function,
                refusal(condition, 1));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            size(1) = 1                 | size           | N
            a = 1 AND size(1)           | size           | N
            size(-1) = 1                | size           | N
            size(true) = 1              | size           | BOOL
            size(null) = 1              | size           | NULL
            size(size(a)) = 1           | size           | N
            size(attribute_exists(a))=1 | size           | BOOL
            a < size(1)                 | size           | N
            a IN [size(1)]              | size           | N
            a BETWEEN size(1) AND 2     | size           | N
            contains(size(1), 'x')      | size           | N
            attribute_exists(size(1))   | size           | N
            begins_with(a, 1)           | begins_with    | N
            begins_with(1, 'x')         | begins_with    | N
            begins_with('x', 1)         | begins_with    | N
            begins_with(1, true)        | begins_with    | N
            begins_with(true, 1)        | begins_with    | BOOL
            begins_with(1, a)           | begins_with    | N
            begins_with(null, 'x')      | begins_with    | NULL
            begins_with(a, [1])         | begins_with    | L
            begins_with(a, {'x': 1})    | begins_with    | M
            begins_with(a, <<'x'>>)     | begins_with    | SS
            begins_with(a, size(a))     | begins_with    | N
            attribute_type(a, 1)        | attribute_type | N
            attribute_type(1, 1)        | attribute_type | N
            attribute_type(true, true)  | attribute_type | BOOL
            attribute_type(a, null)     | attribute_type | NULL
            attribute_type(a, [1])      | attribute_type | L
            attribute_type(a, size(a))  | attribute_type | N
            """)
    void refusesAnOperandOfTheWrongType(String condition, String function, String type) {
        assertEquals("Incorrect operand type for operator or function; operator or function: " + function
                + ", operand type: " + type, refusal(condition, 0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            contains(a, a)                 | contains       | [a]
            contains("a", a)               | contains       | [a]
            begins_with(m.a, m.a)          | begins_with    | [m, a]
            begins_with(m."a".b, m.a.b)    | begins_with    | [m, a, b]
            attribute_type(a, a)           | attribute_type | [a]
            attribute_type(l[0].a, l[0].a) | attribute_type | [l, [0], a]
            contains(m."x y", m."x y")     | contains       | [m, x y]
            """)
    void refusesTheSamePathAsBothOperands(String condition, String function, String path) {
        assertEquals("The first operand must be distinct from the remaining operands for this operator or function; "
                + "operator: " + function + ", first operand: " + path, refusal(condition, 0));
    }

    private String refusal(String condition, int parameters) {
        return statementRefusal("SELECT * FROM \"t\" WHERE " + condition, parameters);
    }

    private String statementRefusal(String statement, int parameters) {
        List<JsonNode> values = IntStream.range(0, parameters).mapToObj(i -> json("{\"S\":\"x\"}")).toList();
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, values));
        return e.getMessage();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" WHERE foo(1) AND attribute_exists()             | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE attribute_exists() AND foo(1)             | Unrecognized function: foo at 1:48:3
            SELECT * FROM "t" WHERE attribute_exists(foo(1))                  | Unrecognized function: foo at 1:42:3
            SELECT * FROM "t" WHERE attribute_exists(foo(1), size())          | Unrecognized function: foo at 1:42:3
            SELECT * FROM "t" WHERE size(foo(1)) = 1                          | Unrecognized function: foo at 1:30:3
            SELECT * FROM "t" WHERE foo(size(1))                              | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE foo(attribute_exists())                   | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE attribute_exists(<<>>)                    | Empty bags are not supported
            SELECT * FROM "t" WHERE attribute_exists() AND a = <<>>           | Empty bags are not supported
            SELECT * FROM "t" WHERE a < true AND foo(1)                       | Unrecognized function: foo at 1:38:3
            SELECT * FROM "t" WHERE a < true AND a = <<>>                     | Empty bags are not supported
            SELECT * FROM "t" WHERE begins_with(a, 1) AND foo(1)              | Unrecognized function: foo at 1:47:3
            SELECT * FROM "t" WHERE attribute_type(a, 1) AND foo(1)           | Unrecognized function: foo at 1:50:3
            SELECT * FROM "t" WHERE attribute_type(a, 'BAD') AND foo(1)       | Unrecognized function: foo at 1:54:3
            SELECT * FROM "t" WHERE attribute_exists(a) < 1 AND foo(1)        | Unrecognized function: foo at 1:53:3
            SELECT * FROM "t" WHERE attribute_exists(a) BETWEEN 1 AND 2 AND foo(1) | Unrecognized function: foo at 1:65:3
            SELECT * FROM "t" WHERE a BETWEEN true AND 2 AND foo(1)           | Unrecognized function: foo at 1:50:3
            SELECT * FROM "t" WHERE a < true AND m[99999999999] = 1           | List index is not within the allowable range; index: [99999999999] at 1:40:11
            SELECT * FROM "t" WHERE attribute_exists() AND m[-1] = 1          | List index is not within the allowable range; index: [-1] at 1:51:1
            SELECT * FROM "t" WHERE m[99999999999] = 1 AND foo(1)             | List index is not within the allowable range; index: [99999999999] at 1:27:11
            SELECT * FROM "t" WHERE foo(1) AND m[99999999999] = 1             | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE a = <<>> AND m[99999999999] = 1           | Empty bags are not supported
            SELECT * FROM "t" WHERE foo(l[99999999999])                       | List index is not within the allowable range; index: [99999999999] at 1:31:11
            SELECT l[99999999999] FROM "t" WHERE foo(1)                       | List index is not within the allowable range; index: [99999999999] at 1:10:11
            SELECT foo(1), l[99999999999] FROM "t"                            | Unexpected path component at 1:8:3
            SELECT * FROM "t" WHERE foo(1) AND a = b                          | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE a = m.b AND foo(1)                        | Unrecognized function: foo at 1:37:3
            SELECT * FROM "t" WHERE a = l[99999999999]                        | List index is not within the allowable range; index: [99999999999] at 1:31:11
            SELECT * FROM "t" WHERE attribute_exists() IS NULL                | The left hand side argument to IS must be a Variable Reference, Literal, or Path
            SELECT * FROM "t" WHERE size(1) IS NULL                           | The left hand side argument to IS must be a Variable Reference, Literal, or Path
            SELECT * FROM "t" WHERE attribute_exists(a) IS NULL               | The left hand side argument to IS must be a Variable Reference, Literal, or Path
            SELECT * FROM "t" WHERE attribute_exists().a = 1                  | Unexpected path component at 1:25:16
            SELECT * FROM "t" WHERE size(a)[0] = 1                            | Unexpected path component at 1:25:4
            SELECT * FROM a.b.c WHERE attribute_exists()                      | A path may contain at most 2 components in the FROM clause
            SELECT * FROM a.b.c WHERE a < true                                | A path may contain at most 2 components in the FROM clause
            SELECT * FROM a.b.c WHERE m[99999999999] = 1                      | A path may contain at most 2 components in the FROM clause
            """)
    void reportsTheOperandChecksLast(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            attribute_exists('x') AND a < true       | Operator or function requires a document path; operator or function: attribute_exists
            a < true AND b = c                       | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            a < true AND attribute_exists('x')       | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            attribute_exists() AND begins_with(a, a) | Incorrect number of operands for operator or function; operator or function: attribute_exists, number of operands: 0
            begins_with(a, a) AND attribute_exists() | The first operand must be distinct from the remaining operands for this operator or function; operator: begins_with, first operand: [a]
            attribute_exists(a) < 1 AND size()       | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            size() AND attribute_exists(a) < 1       | Incorrect number of operands for operator or function; operator or function: size, number of operands: 0
            attribute_type(a, 1) AND size()          | Incorrect operand type for operator or function; operator or function: attribute_type, operand type: N
            """)
    void reportsTheFirstOperandCheckInTheStatement(String condition, String message) {
        assertEquals(message, refusal(condition, 0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET l[99999999999] = 1 WHERE foo(1)                  | Unrecognized function: foo at 1:41:3
            UPDATE "t" REMOVE l[99999999999] WHERE attribute_exists()       | Incorrect number of operands for operator or function; operator or function: attribute_exists, number of operands: 0
            UPDATE "t" SET a = foo(1) WHERE a < true                        | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            UPDATE "t" SET a = size(b) WHERE attribute_exists('x')          | Operator or function requires a document path; operator or function: attribute_exists
            UPDATE "t" SET a = 'x' + 1 WHERE foo(1)                         | Unrecognized function: foo at 1:34:3
            UPDATE "t" SET a = 'x' + 1, b = foo(1) WHERE pk = 1             | Unrecognized function: foo at 1:33:3
            UPDATE "t" SET a = 'x' + 1, b = <<>> WHERE pk = 1               | Empty bags are not supported
            UPDATE "t" SET a = 'x' + 1 REMOVE l[99999999999] WHERE pk = 1   | List index is not within the allowable range; index: [99999999999] at 1:37:11
            UPDATE "t" SET a = set_add(b, <<'x'>>), c = foo(1) WHERE pk = 1 | The first argument to SET_ADD must equal the assignment value at 1:20:7
            FROM "t" WHERE attribute_exists('x') SET a = size(b)            | Operator or function requires a document path; operator or function: attribute_exists
            FROM "t" WHERE a < true SET a = foo(1)                          | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            FROM "t" WHERE foo(1) SET a = bar(1)                            | Unrecognized function: foo at 1:16:3
            DELETE FROM "t" WHERE pk = 1 AND a < true RETURNING ALL NEW *   | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            DELETE FROM "t" WHERE attribute_exists() RETURNING ALL NEW *    | Incorrect number of operands for operator or function; operator or function: attribute_exists, number of operands: 0
            """)
    void checksTheWhereClauseBeforeTheEdits(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET a = set_add() WHERE pk = 1                     | SET_ADD must have exactly two arguments at 1:20:7
            UPDATE "t" SET a = set_add(a) WHERE pk = 1                    | SET_ADD must have exactly two arguments at 1:20:7
            UPDATE "t" SET a = set_delete(a, <<1>>, <<2>>) WHERE pk = 1   | SET_DELETE must have exactly two arguments at 1:20:10
            UPDATE "t" SET a = "SET_ADD"(a) WHERE pk = 1                  | SET_ADD must have exactly two arguments at 1:20:9
            UPDATE "t" SET a = set_add(foo(1)) WHERE pk = 1               | SET_ADD must have exactly two arguments at 1:20:7
            UPDATE "t" SET a = set_add(a, <<>>, 1) WHERE pk = 1           | SET_ADD must have exactly two arguments at 1:20:7
            UPDATE "t" SET a = 'x' + 1, b = set_add(b) WHERE pk = 1       | SET_ADD must have exactly two arguments at 1:33:7
            UPDATE "t" SET a = list_append(b), c = set_add(c) WHERE pk = 1 | SET_ADD must have exactly two arguments at 1:40:7
            UPDATE "t" SET a = foo(1), b = set_add(b) WHERE pk = 1        | Unrecognized function: foo at 1:20:3
            UPDATE "t" SET a = set_add(a), b = foo(1) WHERE pk = 1        | SET_ADD must have exactly two arguments at 1:20:7
            UPDATE "t" SET a = set_add(b, 1), c = set_add(c) WHERE pk = 1 | The first argument to SET_ADD must equal the assignment value at 1:20:7
            UPDATE "t" SET a = set_add(a) WHERE foo(1)                    | Unrecognized function: foo at 1:37:3
            FROM "t" WHERE attribute_exists('x') SET a = set_add(a)       | Operator or function requires a document path; operator or function: attribute_exists
            UPDATE "t" SET a = set_add(a).b WHERE pk = 1                  | Unexpected path component at 1:20:7
            UPDATE "t" SET a = set_add(foo(1))[0] WHERE pk = 1            | Unexpected path component at 1:20:7
            UPDATE "t" SET a = set_add(*) WHERE pk = 1                    | Statement wasn't well formed, can't be processed: set_add(*) is not allowed
            UPDATE "t" SET a = set_add(a WHERE pk = 1                     | Statement wasn't well formed, can't be processed: Expected RIGHT_PAREN
            """)
    void refusesTheWrongNumberOfArgumentsToASetFunction(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET a = set_add(<<'a'>>, <<'b'>>) WHERE pk = 1     | The first argument to SET_ADD must equal the assignment value at 1:20:7
            UPDATE "t" SET a = set_delete('a', 'b') WHERE pk = 1          | The first argument to SET_DELETE must equal the assignment value at 1:20:10
            UPDATE "t" SET a = set_add(1, 2) WHERE pk = 1                 | The first argument to SET_ADD must equal the assignment value at 1:20:7
            UPDATE "t" SET a = set_add(list_append(a, [1]), 1) WHERE pk = 1 | The first argument to SET_ADD must equal the assignment value at 1:20:7
            UPDATE "t" SET a = set_add(list_append(1, 2), 1) WHERE pk = 1 | The first argument to SET_ADD must equal the assignment value at 1:20:7
            UPDATE "t" SET a = set_add(foo(1), <<'b'>>) WHERE pk = 1      | Unrecognized function: foo at 1:28:3
            UPDATE "t" SET a = set_add(a, foo(1)) WHERE pk = 1            | Unrecognized function: foo at 1:31:3
            UPDATE "t" SET a = set_add(b, foo(1)) WHERE pk = 1            | Unrecognized function: foo at 1:31:3
            UPDATE "t" SET a = set_add(<<>>, <<'b'>>) WHERE pk = 1        | Empty bags are not supported
            UPDATE "t" SET a = set_add(b, <<>>) WHERE pk = 1              | Empty bags are not supported
            UPDATE "t" SET a = set_add(set_add(a, <<'a'>>), <<'b'>>) WHERE pk = 1 | Unrecognized function: set_add at 1:28:7
            UPDATE "t" SET a = set_add(size(a), <<'b'>>) WHERE pk = 1     | The function is not allowed in an update expression; function: size
            UPDATE "t" SET a = set_add(a, size(b)) WHERE pk = 1           | The function is not allowed in an update expression; function: size
            UPDATE "t" SET a = set_add(a, list_append(b, [1])) WHERE pk = 1 | The second argument to SET_ADD must be a value with type SET at 1:28:1
            UPDATE "t" SET a = set_add(a, list_append(1, 2)) WHERE pk = 1 | The second argument to SET_ADD must be a value with type SET at 1:28:1
            UPDATE "t" SET a = set_add(a, b) WHERE pk = 1                 | The second argument to SET_ADD must be a value with type SET at 1:28:1
            UPDATE "t" SET a = set_add(a, b.c) WHERE pk = 1               | The second argument to SET_ADD must be a value with type SET at 1:28:1
            """)
    void refusesASetFunctionArgumentByItsKind(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET a = set_add(a) + 1 WHERE pk = 1                | Unrecognized function: set_add at 1:20:7
            UPDATE "t" SET a = set_delete(a, <<'b'>>) - 1 WHERE pk = 1    | Unrecognized function: set_delete at 1:20:10
            UPDATE "t" SET a = set_add(<<>>) + 1 WHERE pk = 1             | Empty bags are not supported
            UPDATE "t" SET a = set_add(foo(1)) + 1 WHERE pk = 1           | Unrecognized function: foo at 1:28:3
            UPDATE "t" SET a = set_add(a) + foo(1) WHERE pk = 1           | Unrecognized function: set_add at 1:20:7
            UPDATE "t" SET a = set_add(b, 1) + 'x' WHERE pk = 1           | Unrecognized function: set_add at 1:20:7
            UPDATE "t" SET a = 1 - set_add(a) WHERE pk = 1                | Unrecognized function: set_add at 1:24:7
            """)
    void readsASetFunctionBesideAnOperatorAsAnUnknownFunction(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            list_append()                         | 0
            list_append(a)                        | 1
            list_append([1])                      | 1
            LIST_APPEND(1)                        | 1
            "list_append"('a')                    | 1
            list_append(a [1])                    | 1
            list_append(a, [1], [2])              | 3
            list_append(1, 2, 3, 4)               | 4
            """)
    void refusesTheWrongNumberOfListAppendOperands(String call, String count) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(
                "UPDATE \"t\" SET a = " + call + " WHERE pk = 1", List.of()));

        assertEquals("Incorrect number of operands for operator or function; operator or function: list_append, "
                + "number of operands: " + count, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            list_append(1, 2)                     | N
            list_append(a, 'x')                   | S
            list_append('x', a)                   | S
            list_append(true, a)                  | BOOL
            list_append(a, null)                  | NULL
            list_append(<<'x'>>, a)               | SS
            list_append(a, <<1>>)                 | NS
            list_append({'x': 1}, a)              | M
            LIST_APPEND(1, 2)                     | N
            list_append(list_append(1, 2))        | N
            list_append(a, b, list_append(1, 2))  | N
            list_append(list_append(a, [1]), 1)   | N
            """)
    void refusesAListAppendOperandThatIsNotAList(String call, String type) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(
                "UPDATE \"t\" SET a = " + call + " WHERE pk = 1", List.of()));

        assertEquals("Incorrect operand type for operator or function; operator or function: list_append, "
                + "operand type: " + type, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET a = list_append(foo(1)) WHERE pk = 1             | Unrecognized function: foo at 1:32:3
            UPDATE "t" SET a = list_append(a, foo(1), 1) WHERE pk = 1       | Unrecognized function: foo at 1:35:3
            UPDATE "t" SET a = list_append(<<>>) WHERE pk = 1               | Empty bags are not supported
            UPDATE "t" SET a = list_append(size(b)) WHERE pk = 1            | The function is not allowed in an update expression; function: size
            UPDATE "t" SET a = list_append(l[99999999999]) WHERE pk = 1     | List index is not within the allowable range; index: [99999999999] at 1:34:11
            UPDATE "t" SET a = list_append(b), c = foo(1) WHERE pk = 1      | Unrecognized function: foo at 1:40:3
            UPDATE "t" SET a = list_append(1, 2), c = <<>> WHERE pk = 1     | Empty bags are not supported
            UPDATE "t" SET a = list_append(b) WHERE foo(1)                  | Unrecognized function: foo at 1:41:3
            FROM "t" WHERE a < true SET a = list_append(b)                  | Incorrect operand type for operator or function; operator or function: <, operand type: BOOL
            UPDATE "t" SET a = 'x' + 1, b = list_append(c) WHERE pk = 1     | Incorrect operand type for operator or function; operator or function: +, operand type: S
            UPDATE "t" SET a = list_append(b), c = 'x' + 1 WHERE pk = 1     | Incorrect number of operands for operator or function; operator or function: list_append, number of operands: 1
            UPDATE "t" SET a = list_append(1, 2), b = list_append(c) WHERE pk = 1 | Incorrect operand type for operator or function; operator or function: list_append, operand type: N
            UPDATE "t" SET a = list_append(list_append(b)) WHERE pk = 1     | Incorrect number of operands for operator or function; operator or function: list_append, number of operands: 1
            UPDATE "t" SET a = list_append(1, list_append(b)) WHERE pk = 1  | Incorrect number of operands for operator or function; operator or function: list_append, number of operands: 1
            UPDATE "t" SET a = list_append(b).c WHERE pk = 1                | Unexpected path component at 1:20:11
            UPDATE "t" SET a = list_append(foo(1))[0] WHERE pk = 1          | Unexpected path component at 1:20:11
            UPDATE "t" SET a = list_append(*) WHERE pk = 1                  | Statement wasn't well formed, can't be processed: list_append(*) is not allowed
            UPDATE "t" SET a = list_append(b WHERE pk = 1                   | Statement wasn't well formed, can't be processed: Expected RIGHT_PAREN
            """)
    void reportsAListAppendOperandCheckInItsStage(String statement, String message) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals(message, e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            UPDATE "t" SET a = list_append(b, [1]) + 1 WHERE pk = 1         | +, operand type: L
            UPDATE "t" SET a = 1 - list_append(b, [1]) WHERE pk = 1         | -, operand type: L
            UPDATE "t" SET a = list_append(b, [1]) + 'x' WHERE pk = 1       | +, operand type: L
            UPDATE "t" SET a = 'x' + list_append(b, [1]) WHERE pk = 1       | +, operand type: S
            UPDATE "t" SET a = list_append(1, 2) + 1 WHERE pk = 1           | list_append, operand type: N
            """)
    void refusesAListAppendBesideAnOperator(String statement, String operand) {
        AwsException e = assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(statement, List.of()));

        assertEquals("Incorrect operand type for operator or function; operator or function: " + operand,
                e.getMessage());
    }

    @Test
    void checksAParameterAsTheOperandOfAnUpdateFunction() {
        List<JsonNode> string = List.of(json("{\"S\":\"x\"}"));
        List<JsonNode> list = List.of(json("{\"L\":[]}"));
        List<JsonNode> stringSet = List.of(json("{\"SS\":[\"x\"]}"));
        String append = "UPDATE \"t\" SET a = list_append(a, ?) WHERE pk = 1";
        String add = "UPDATE \"t\" SET a = set_add(a, ?) WHERE pk = 1";

        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(append, list));
        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(add, stringSet));
        assertEquals("Incorrect operand type for operator or function; operator or function: list_append, "
                + "operand type: S",
                assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(append, string)).getMessage());
        assertEquals("The second argument to SET_ADD must be a value with type SET at 1:28:1",
                assertThrows(AwsException.class, () -> DynamoDbPartiQLParser.parse(add, string)).getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            SELECT * FROM "t" WHERE pk = ?                           | 0 | COUNT
            SELECT * FROM "t" WHERE pk = ? AND x = ?                 | 1 | COUNT
            DELETE FROM "t" WHERE pk = ?                             | 0 | COUNT
            INSERT INTO "t" VALUE {'pk': ?}                          | 0 | COUNT
            UPDATE "t" SET a = ? WHERE pk = 1                        | 0 | COUNT
            UPDATE "t" SET a = 1 WHERE pk = ?                        | 0 | COUNT
            SELECT * FROM "t" WHERE pk = ? AND foo(1)                | 0 | COUNT
            SELECT * FROM "t" WHERE foo(1) AND pk = ?                | 0 | Unrecognized function: foo at 1:25:3
            SELECT * FROM "t" WHERE pk = ? AND foo(1) AND x = ?      | 1 | Unrecognized function: foo at 1:36:3
            SELECT * FROM "t" WHERE pk = ? AND x = ? AND foo(1)      | 1 | COUNT
            SELECT * FROM "t" WHERE foo(?)                           | 0 | COUNT
            SELECT * FROM "t" WHERE pk = ? AND x = <<>>              | 0 | COUNT
            SELECT * FROM "t" WHERE x = <<>> AND pk = ?              | 0 | Empty bags are not supported
            SELECT * FROM "t" WHERE attribute_exists('a') AND pk = ? | 0 | COUNT
            SELECT * FROM "t" WHERE pk = ? AND                       | 0 | NWF Missing right-hand side expression of infix operator
            SELECT * FROM "" WHERE pk = ?                            | 0 | Path component cannot be an empty string
            SELECT * FROM "t" WHERE x = [?]                          | 0 | Unsupported data type: Parameter under key root[0] at 1:30:1
            SELECT * FROM "t" WHERE x = <<?>>                        | 0 | BAG at 1:31:1
            SELECT * FROM "t" WHERE x = <<?>>                        | 1 | BAG at 1:31:1
            SELECT * FROM "t" WHERE x = <<1, ?>>                     | 0 | Unsupported data type: Parameter under key root at 1:34:1
            SELECT * FROM "t" WHERE foo(?).a = 1                     | 0 | Unexpected path component at 1:25:3
            SELECT * FROM "t" WHERE foo(?) IS NULL                   | 0 | IS_LEFT
            SELECT * FROM "t" WHERE size(?)                          | 0 | COUNT
            UPDATE "t" SET a = ? WHERE foo(1)                        | 0 | Unrecognized function: foo at 1:28:3
            UPDATE "t" SET a = ? WHERE attribute_exists('a')         | 0 | DOCUMENT_PATH
            UPDATE "t" SET a = foo(1) WHERE pk = ?                   | 0 | COUNT
            UPDATE "t" SET a = ?, b = foo(1) WHERE pk = ?            | 1 | COUNT
            UPDATE "t" SET a = foo(1), b = ? WHERE pk = 1            | 0 | Unrecognized function: foo at 1:20:3
            UPDATE "t" SET a = ? REMOVE l[99999999999] WHERE pk = 1  | 0 | COUNT
            UPDATE "t" REMOVE l[99999999999] SET a = ? WHERE pk = 1  | 0 | LIST_INDEX at 1:21:11
            FROM "t" WHERE foo(1) SET a = ?                          | 0 | Unrecognized function: foo at 1:16:3
            FROM "t" WHERE pk = ? SET a = foo(1)                     | 0 | COUNT
            FROM "t" WHERE pk = ? SET a = foo(1), b = ?              | 1 | Unrecognized function: foo at 1:31:3
            UPDATE "t" SET ss = set_add(?) WHERE pk = 1              | 0 | SET_ADD must have exactly two arguments at 1:21:7
            UPDATE "t" SET ss = set_add(ss, ?) WHERE pk = 1          | 0 | COUNT
            UPDATE "t" SET ss = set_add(ss, ?).a WHERE pk = 1        | 0 | Unexpected path component at 1:21:7
            UPDATE "t" SET ss = set_add(ss, ?) + 1 WHERE pk = 1      | 0 | COUNT
            UPDATE "t" SET l = list_append(?) WHERE pk = 1           | 0 | COUNT
            INSERT INTO "t" ?                                        | 0 | MULTIPLE_ITEMS
            INSERT INTO "t" VALUE {'pk': 1, 'x': <<>>, 'y': ?}       | 0 | Empty bags are not supported
            INSERT INTO "t" VALUE {'pk': 1, 'y': ?, 'x': <<>>}       | 0 | COUNT
            """)
    void reportsAMissingParameterInStatementOrder(String statement, int parameters, String message) {
        String expected = message
                .replace("COUNT", "Number of parameters in request and statement don't match.")
                .replace("NWF ", "Statement wasn't well formed, can't be processed: ")
                .replace("BAG", "Unsupported data type in Bag. DynamoDB only supports either numbers or strings in bags")
                .replace("IS_LEFT", "The left hand side argument to IS must be a Variable Reference, Literal, or Path")
                .replace("DOCUMENT_PATH", "Operator or function requires a document path; operator or function: "
                        + "attribute_exists")
                .replace("LIST_INDEX", "List index is not within the allowable range; index: [99999999999]")
                .replace("MULTIPLE_ITEMS", "Unsupported operation: Inserting multiple items in a single statement "
                        + "is not supported, use \"INSERT INTO tableName VALUE item\" instead");

        assertEquals(expected, statementRefusal(statement, parameters));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM \"t\" WHERE pk = 'a'", "SELECT * FROM \"t\" WHERE pk = ?"})
    void ignoresAParameterTheStatementDoesNotUse(String statement) {
        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(statement,
                List.of(json("{\"S\":\"x\"}"), json("{\"S\":\"y\"}"))));
    }

    @Test
    void countsInOperandsWithTheOperandChecks() {
        String tooMany = "The IN operator is provided with too many operands; number of operands: 101";
        String in = "a IN [" + inValues(101) + "]";
        int foo = "SELECT * FROM \"t\" WHERE ".length() + in.length() + " AND ".length() + 1;

        assertEquals(tooMany, refusal(in + " AND a < true", 0));
        assertEquals(tooMany, refusal(in + " AND attribute_exists()", 0));
        assertEquals("Incorrect operand type for operator or function; operator or function: <, operand type: BOOL",
                refusal("a < true AND " + in, 0));
        assertEquals("Unrecognized function: foo at 1:" + foo + ":3", refusal(in + " AND foo(1)", 0));
    }

    @Test
    void readsAFunctionNameWithoutParenthesesAsAPath() {
        assertDoesNotThrow(() -> DynamoDbPartiQLParser.parse(
                "SELECT * FROM \"t\" WHERE begins_with = 1 AND contains.a = 1", List.of()));
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
    @ValueSource(strings = {
            "SELECT * FROM \"t\" WHERE pk = 1 # note",
            "SELECT FROM WHERE #",
            "SELECT * FROM \"t\" WHERE pk = 1e200 #",
            "SELECT * FROM \"t\" WHERE pk = 1 ~",
            "SELECT * FROM \"t\" WHERE pk = 1 !",
            "SELECT * FROM \"t\" WHERE pk = 1 `",
            "SELECT * FROM \"t\" WHERE é = 1",
            "SELECT * FROM \"t\" WHERE pk = 1١",
            "SELECT * FROM \"t\" WHERE pk = 1 ",
            "SELECT * FROM \"t\" WHERE pk = 1\u001c",
            "\0SELECT * FROM \"t\" WHERE pk = 1",
            "SELECT * FROM \"t\" WHERE pk = 'abc",
            "SELECT * FROM \"t\" WHERE pk = 'a''",
            "SELECT * FROM \"t\" WHERE \"pk = 1",
            "SELECT * FROM \"t"})
    void refusesAStatementTheLexerCannotRead(String statement) {
        assertEquals("Statement wasn't well formed, can't be processed: ", refusal(statement));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$x", "x$", "$", "a$1"})
    void readsADollarSignInAName(String name) {
        Cond cond = firstCond("SELECT pk FROM \"t\" WHERE " + name + " = 1");

        Path path = assertInstanceOf(Cond.Eq.class, cond).path();
        assertEquals(List.of(new Seg.Name(name)), path.segments());
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
            INSERT INTO "t" list_append(a)                          | MULTIPLE_ITEMS
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
