package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ── Tokenizer tests ──

    @Nested
    class TokenizerTests {

        @Test
        void standardExpression() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("pk = :pk AND sk BETWEEN :a AND :b");
            List<ExpressionEvaluator.TokenType> types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
            assertEquals(List.of(
                    ExpressionEvaluator.TokenType.IDENTIFIER,  // pk
                    ExpressionEvaluator.TokenType.EQ,          // =
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :pk
                    ExpressionEvaluator.TokenType.AND,         // AND
                    ExpressionEvaluator.TokenType.IDENTIFIER,  // sk
                    ExpressionEvaluator.TokenType.BETWEEN,     // BETWEEN
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :a
                    ExpressionEvaluator.TokenType.AND,         // AND
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :b
                    ExpressionEvaluator.TokenType.EOF
            ), types);
        }

        @Test
        void compactFormat() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            List<ExpressionEvaluator.TokenType> types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
            assertEquals(List.of(
                    ExpressionEvaluator.TokenType.LPAREN,
                    ExpressionEvaluator.TokenType.NAME_REF,    // #f0
                    ExpressionEvaluator.TokenType.EQ,
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :v0
                    ExpressionEvaluator.TokenType.RPAREN,
                    ExpressionEvaluator.TokenType.AND,
                    ExpressionEvaluator.TokenType.LPAREN,
                    ExpressionEvaluator.TokenType.NAME_REF,    // #f1
                    ExpressionEvaluator.TokenType.BETWEEN,
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :v1
                    ExpressionEvaluator.TokenType.AND,
                    ExpressionEvaluator.TokenType.VALUE_REF,   // :v2
                    ExpressionEvaluator.TokenType.RPAREN,
                    ExpressionEvaluator.TokenType.EOF
            ), types);
        }

        @Test
        void allComparators() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("a = b a <> b a < b a <= b a > b a >= b");
            List<ExpressionEvaluator.TokenType> comparators = tokens.stream()
                    .map(ExpressionEvaluator.Token::type)
                    .filter(t -> t != ExpressionEvaluator.TokenType.IDENTIFIER && t != ExpressionEvaluator.TokenType.EOF)
                    .toList();
            assertEquals(List.of(
                    ExpressionEvaluator.TokenType.EQ,
                    ExpressionEvaluator.TokenType.NE,
                    ExpressionEvaluator.TokenType.LT,
                    ExpressionEvaluator.TokenType.LE,
                    ExpressionEvaluator.TokenType.GT,
                    ExpressionEvaluator.TokenType.GE
            ), comparators);
        }

        @Test
        void functionTokens() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("attribute_exists(a) AND begins_with(b, :v) AND contains(c, :w) AND size(d) > :x AND attribute_not_exists(e)");
            List<String> functions = tokens.stream()
                    .filter(t -> t.type() == ExpressionEvaluator.TokenType.FUNCTION)
                    .map(ExpressionEvaluator.Token::value)
                    .toList();
            assertEquals(List.of("attribute_exists", "begins_with", "contains", "size", "attribute_not_exists"), functions);
        }

        @Test
        void inAndBetweenKeywords() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("x IN (:a, :b) AND y BETWEEN :c AND :d");
            assertTrue(tokens.stream().anyMatch(t -> t.type() == ExpressionEvaluator.TokenType.IN));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == ExpressionEvaluator.TokenType.BETWEEN));
        }

        @Test
        void dottedPath() {
            List<ExpressionEvaluator.Token> tokens = ExpressionEvaluator.tokenize("info.nested = :v");
            List<ExpressionEvaluator.TokenType> types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
            assertEquals(List.of(
                    ExpressionEvaluator.TokenType.IDENTIFIER,  // info
                    ExpressionEvaluator.TokenType.DOT,
                    ExpressionEvaluator.TokenType.IDENTIFIER,  // nested
                    ExpressionEvaluator.TokenType.EQ,
                    ExpressionEvaluator.TokenType.VALUE_REF,
                    ExpressionEvaluator.TokenType.EOF
            ), types);
        }
    }

    // ── Parser tests ──

    @Nested
    class ParserTests {

        @Test
        void simpleComparison() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("pk = :pk");
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, expr);
        }

        @Test
        void andExpression() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("a = :a AND b = :b");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            assertEquals(2, ((ExpressionEvaluator.AndExpr) expr).operands().size());
        }

        @Test
        void orExpression() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("a = :a OR b = :b");
            assertInstanceOf(ExpressionEvaluator.OrExpr.class, expr);
            assertEquals(2, ((ExpressionEvaluator.OrExpr) expr).operands().size());
        }

        @Test
        void notExpression() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("NOT a = :a");
            assertInstanceOf(ExpressionEvaluator.NotExpr.class, expr);
        }

        @Test
        void nestedParens() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("(a = :a OR b = :b) AND c = :c");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            ExpressionEvaluator.AndExpr and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.OrExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(1));
        }

        @Test
        void betweenAndNotConfusedWithLogicalAnd() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("sk BETWEEN :a AND :b");
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, expr);
        }

        @Test
        void betweenInsideAnd() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("pk = :pk AND sk BETWEEN :a AND :b");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            ExpressionEvaluator.AndExpr and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, and.operands().get(1));
        }

        @Test
        void inOperator() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("status IN (:a, :b, :c)");
            assertInstanceOf(ExpressionEvaluator.InExpr.class, expr);
            assertEquals(3, ((ExpressionEvaluator.InExpr) expr).candidates().size());
        }

        @Test
        void inOperatorSingleValue() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("status IN (:a)");
            assertInstanceOf(ExpressionEvaluator.InExpr.class, expr);
            assertEquals(1, ((ExpressionEvaluator.InExpr) expr).candidates().size());
        }

        @Test
        void functionCallCondition() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("attribute_exists(myAttr)");
            assertInstanceOf(ExpressionEvaluator.FunctionCallExpr.class, expr);
        }

        @Test
        void sizeComparison() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("size(myList) > :val");
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, expr);
            ExpressionEvaluator.CompareExpr cmp = (ExpressionEvaluator.CompareExpr) expr;
            assertInstanceOf(ExpressionEvaluator.FunctionOperand.class, cmp.left());
        }

        @Test
        void compactFormatParsesCorrectly() {
            ExpressionEvaluator.Expr expr = ExpressionEvaluator.parse("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            ExpressionEvaluator.AndExpr and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, and.operands().get(1));
        }
    }

    // ── splitKeyCondition tests ──

    @Nested
    class SplitKeyConditionTests {

        @Test
        void pkAndSkEquals() {
            String[] result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND sk = :sk");
            assertEquals("pk = :pk", result[0]);
            assertEquals("sk = :sk", result[1]);
        }

        @Test
        void pkAndSkBetweenParenthesized() {
            String[] result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND (sk BETWEEN :a AND :b)");
            assertEquals("pk = :pk", result[0]);
            assertEquals("(sk BETWEEN :a AND :b)", result[1]);
        }

        @Test
        void compactFormat() {
            String[] result = ExpressionEvaluator.splitKeyCondition("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            assertEquals("(#f0 = :v0)", result[0]);
            assertEquals("(#f1 BETWEEN :v1 AND :v2)", result[1]);
        }

        @Test
        void pkOnly() {
            String[] result = ExpressionEvaluator.splitKeyCondition("pk = :pk");
            assertEquals("pk = :pk", result[0]);
            assertNull(result[1]);
        }

        @Test
        void pkAndSkBeginsWith() {
            String[] result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND begins_with(sk, :prefix)");
            assertEquals("pk = :pk", result[0]);
            assertEquals("begins_with(sk, :prefix)", result[1]);
        }

        @Test
        void pkAndSkBetweenNoParen() {
            String[] result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND sk BETWEEN :a AND :b");
            assertEquals("pk = :pk", result[0]);
            assertEquals("sk BETWEEN :a AND :b", result[1]);
        }
    }

    // ── Evaluator (matches) tests ──

    @Nested
    class EvaluatorTests {

        private JsonNode item(String json) throws Exception {
            return mapper.readTree(json);
        }

        private JsonNode values(String json) throws Exception {
            return mapper.readTree(json);
        }

        private JsonNode names(String json) throws Exception {
            return mapper.readTree(json);
        }

        // DynamoDB's size() of a Binary is its length in bytes; "AQID" encodes the three bytes 1, 2, 3.
        @Test
        void sizeOfABinaryCountsItsBytes() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"AQID\"}}");
            JsonNode v = values("{\":three\": {\"N\": \"3\"}}");
            assertTrue(ExpressionEvaluator.matches("size(b) = :three", i, null, v));
        }

        @Test
        void aBinaryOfThreeBytesIsSmallerThanFour() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"AQID\"}}");
            JsonNode v = values("{\":four\": {\"N\": \"4\"}}");
            assertTrue(ExpressionEvaluator.matches("size(b) < :four", i, null, v));
        }

        @Test
        void sizeOfAnEmptyBinaryIsZero() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"\"}}");
            JsonNode v = values("{\":zero\": {\"N\": \"0\"}}");
            assertTrue(ExpressionEvaluator.matches("size(b) = :zero", i, null, v));
        }

        // A binary that is not base64 fails with a 400 SerializationException, as the binary
        // comparisons below do, rather than being sized by its text.
        @Test
        void sizeOfABinaryThatIsNotBase64IsRejected() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"not base64!!\"}}");
            JsonNode v = values("{\":three\": {\"N\": \"3\"}}");
            AwsException e = assertThrows(AwsException.class,
                    () -> ExpressionEvaluator.matches("size(b) = :three", i, null, v));
            assertEquals("SerializationException", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }

        // begins_with on a Binary compares bytes: "AQ==" (the byte 1) is a prefix of "AQID"
        // (the bytes 1, 2, 3) even though its base64 text is not.
        @Test
        void beginsWithOnABinaryMatchesABytePrefix() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"AQID\"}}");
            JsonNode v = values("{\":prefix\": {\"B\": \"AQ==\"}}");
            assertTrue(ExpressionEvaluator.matches("begins_with(b, :prefix)", i, null, v));
        }

        @Test
        void beginsWithOnABinaryRejectsADifferentFirstByte() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"AQID\"}}");
            JsonNode v = values("{\":prefix\": {\"B\": \"Ag==\"}}");
            assertFalse(ExpressionEvaluator.matches("begins_with(b, :prefix)", i, null, v));
        }

        @Test
        void beginsWithOnABinaryThatIsNotBase64IsRejected() throws Exception {
            JsonNode i = item("{\"b\": {\"B\": \"not base64!!\"}}");
            JsonNode v = values("{\":prefix\": {\"B\": \"AQ==\"}}");
            AwsException e = assertThrows(AwsException.class,
                    () -> ExpressionEvaluator.matches("begins_with(b, :prefix)", i, null, v));
            assertEquals("SerializationException", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }

        @Test
        void orderingAgainstAnotherTypeMatchesNothing() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"5\"}}");
            JsonNode v = values("{\":low\": {\"N\": \"1\"}, \":high\": {\"N\": \"9\"}}");
            assertFalse(ExpressionEvaluator.matches("a > :low", i, null, v));
            assertFalse(ExpressionEvaluator.matches("a BETWEEN :low AND :high", i, null, v));
        }

        // AND, OR, NOT logic

        @Test
        void andBothTrue() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"2\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("a = :a AND b = :b", i, null, v));
        }

        @Test
        void andOneFalse() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"3\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertFalse(ExpressionEvaluator.matches("a = :a AND b = :b", i, null, v));
        }

        @Test
        void orOneTrue() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"3\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("a = :a OR b = :b", i, null, v));
        }

        @Test
        void orBothFalse() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertFalse(ExpressionEvaluator.matches("a = :a OR b = :b", i, null, v));
        }

        @Test
        void notTrue() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"X\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"}}");
            assertTrue(ExpressionEvaluator.matches("NOT a = :a", i, null, v));
        }

        @Test
        void notFalse() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"1\"}}");
            assertFalse(ExpressionEvaluator.matches("NOT a = :a", i, null, v));
        }

        // Comparison operators on strings

        @Test
        void stringEquals() throws Exception {
            JsonNode i = item("{\"name\": {\"S\": \"Alice\"}}");
            JsonNode v = values("{\n\":v\": {\"S\": \"Alice\"}}");
            assertTrue(ExpressionEvaluator.matches("name = :v", i, null, v));
        }

        @Test
        void stringNotEquals() throws Exception {
            JsonNode i = item("{\"name\": {\"S\": \"Alice\"}}");
            JsonNode v = values("{\n\":v\": {\"S\": \"Bob\"}}");
            assertTrue(ExpressionEvaluator.matches("name <> :v", i, null, v));
        }

        @Test
        void stringLessThan() throws Exception {
            JsonNode i = item("{\"name\": {\"S\": \"Alice\"}}");
            JsonNode v = values("{\n\":v\": {\"S\": \"Bob\"}}");
            assertTrue(ExpressionEvaluator.matches("name < :v", i, null, v));
        }

        // Comparison operators on numbers

        @Test
        void numberEquals() throws Exception {
            JsonNode i = item("{\"age\": {\"N\": \"25\"}}");
            JsonNode v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age = :v", i, null, v));
        }

        @Test
        void numberGreaterThan() throws Exception {
            JsonNode i = item("{\"age\": {\"N\": \"30\"}}");
            JsonNode v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age > :v", i, null, v));
        }

        @Test
        void numberLessThanOrEqual() throws Exception {
            JsonNode i = item("{\"age\": {\"N\": \"25\"}}");
            JsonNode v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age <= :v", i, null, v));
        }

        // <> on BOOL and missing attributes

        @Test
        void boolNotEqualFalse() throws Exception {
            JsonNode i = item("{\"active\": {\"BOOL\": \"false\"}}");
            JsonNode v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertTrue(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void boolNotEqualTrue() throws Exception {
            JsonNode i = item("{\"active\": {\"BOOL\": \"true\"}}");
            JsonNode v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertFalse(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void missingAttributeNotEqual() throws Exception {
            // DynamoDB: missing <> val → true
            JsonNode i = item("{\"other\": {\"S\": \"x\"}}");
            JsonNode v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertTrue(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void missingAttributeEquals() throws Exception {
            // DynamoDB: missing = val → false
            JsonNode i = item("{\"other\": {\"S\": \"x\"}}");
            JsonNode v = values("{\n\":v\": {\"S\": \"hello\"}}");
            assertFalse(ExpressionEvaluator.matches("name = :v", i, null, v));
        }

        // IN operator

        @Test
        void inWithNumbers() throws Exception {
            JsonNode i = item("{\"status\": {\"N\": \"2\"}}");
            JsonNode v = values("{\n\":a\": {\"N\": \"1\"},\n\":b\": {\"N\": \"2\"},\n\":c\": {\"N\": \"3\"}}");
            assertTrue(ExpressionEvaluator.matches("status IN (:a, :b, :c)", i, null, v));
        }

        @Test
        void inWithStrings() throws Exception {
            JsonNode i = item("{\"color\": {\"S\": \"red\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"red\"},\n\":b\": {\"S\": \"blue\"}}");
            assertTrue(ExpressionEvaluator.matches("color IN (:a, :b)", i, null, v));
        }

        @Test
        void inNotMatching() throws Exception {
            JsonNode i = item("{\"color\": {\"S\": \"green\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"red\"},\n\":b\": {\"S\": \"blue\"}}");
            assertFalse(ExpressionEvaluator.matches("color IN (:a, :b)", i, null, v));
        }

        @Test
        void inSingleValue() throws Exception {
            JsonNode i = item("{\"status\": {\"S\": \"active\"}}");
            JsonNode v = values("{\n\":a\": {\"S\": \"active\"}}");
            assertTrue(ExpressionEvaluator.matches("status IN (:a)", i, null, v));
        }

        // BETWEEN

        @Test
        void betweenStrings() throws Exception {
            JsonNode i = item("{\"sk\": {\"S\": \"B\"}}");
            JsonNode v = values("{\n\":low\": {\"S\": \"A\"},\n\":high\": {\"S\": \"C\"}}");
            assertTrue(ExpressionEvaluator.matches("sk BETWEEN :low AND :high", i, null, v));
        }

        @Test
        void betweenOutOfRange() throws Exception {
            JsonNode i = item("{\"sk\": {\"S\": \"D\"}}");
            JsonNode v = values("{\n\":low\": {\"S\": \"A\"},\n\":high\": {\"S\": \"C\"}}");
            assertFalse(ExpressionEvaluator.matches("sk BETWEEN :low AND :high", i, null, v));
        }

        // attribute_exists / attribute_not_exists

        @Test
        void attributeExistsPresent() throws Exception {
            JsonNode i = item("{\"name\": {\"S\": \"Alice\"}}");
            assertTrue(ExpressionEvaluator.matches("attribute_exists(name)", i, null, null));
        }

        @Test
        void attributeExistsMissing() throws Exception {
            JsonNode i = item("{\"other\": {\"S\": \"x\"}}");
            assertFalse(ExpressionEvaluator.matches("attribute_exists(name)", i, null, null));
        }

        @Test
        void attributeNotExistsPresent() throws Exception {
            JsonNode i = item("{\"name\": {\"S\": \"Alice\"}}");
            assertFalse(ExpressionEvaluator.matches("attribute_not_exists(name)", i, null, null));
        }

        @Test
        void attributeNotExistsMissing() throws Exception {
            JsonNode i = item("{\"other\": {\"S\": \"x\"}}");
            assertTrue(ExpressionEvaluator.matches("attribute_not_exists(name)", i, null, null));
        }

        @Test
        void attributeExistsNested() throws Exception {
            JsonNode i = item("{\"info\": {\"M\": {\"email\": {\"S\": \"a@b.com\"}}}}");
            assertTrue(ExpressionEvaluator.matches("attribute_exists(info.email)", i, null, null));
        }

        @Test
        void attributeExistsNestedMissing() throws Exception {
            JsonNode i = item("{\"info\": {\"M\": {\"name\": {\"S\": \"Alice\"}}}}");
            assertFalse(ExpressionEvaluator.matches("attribute_exists(info.email)", i, null, null));
        }

        // begins_with

        @Test
        void beginsWithMatch() throws Exception {
            JsonNode i = item("{\"sk\": {\"S\": \"USER#123\"}}");
            JsonNode v = values("{\n\":prefix\": {\"S\": \"USER#\"}}");
            assertTrue(ExpressionEvaluator.matches("begins_with(sk, :prefix)", i, null, v));
        }

        @Test
        void beginsWithNoMatch() throws Exception {
            JsonNode i = item("{\"sk\": {\"S\": \"ORDER#123\"}}");
            JsonNode v = values("{\n\":prefix\": {\"S\": \"USER#\"}}");
            assertFalse(ExpressionEvaluator.matches("begins_with(sk, :prefix)", i, null, v));
        }

        // contains

        @Test
        void containsString() throws Exception {
            JsonNode i = item("{\"desc\": {\"S\": \"hello world\"}}");
            JsonNode v = values("{\n\":sub\": {\"S\": \"world\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(desc, :sub)", i, null, v));
        }

        @Test
        void containsList() throws Exception {
            JsonNode i = item("{\"tags\": {\"L\": [{\"S\": \"a\"}, {\"S\": \"b\"}, {\"S\": \"c\"}]}}");
            JsonNode v = values("{\n\":val\": {\"S\": \"b\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(tags, :val)", i, null, v));
        }

        @Test
        void containsStringSet() throws Exception {
            JsonNode i = item("{\"tags\": {\"SS\": [\"a\", \"b\", \"c\"]}}");
            JsonNode v = values("{\n\":val\": {\"S\": \"b\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(tags, :val)", i, null, v));
        }

        @Test
        void containsNumberSet() throws Exception {
            JsonNode i = item("{\"nums\": {\"NS\": [\"1\", \"2\", \"3\"]}}");
            JsonNode v = values("{\n\":val\": {\"N\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(nums, :val)", i, null, v));
        }

        // Expression attribute names

        @Test
        void expressionAttributeNames() throws Exception {
            JsonNode i = item("{\"status\": {\"S\": \"active\"}}");
            JsonNode v = values("{\n\":v\": {\"S\": \"active\"}}");
            JsonNode n = names("{\"#s\": \"status\"}");
            assertTrue(ExpressionEvaluator.matches("#s = :v", i, n, v));
        }

        // Nested parentheses

        @Test
        void nestedParentheses() throws Exception {
            // ((a = :1 OR b = :2) AND c = :3) OR d = :4
            JsonNode i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}, \"c\": {\"S\": \"3\"}, \"d\": {\"S\": \"4\"}}");
            JsonNode v = values("{\n\":1\": {\"S\": \"1\"},\n\":2\": {\"S\": \"2\"},\n\":3\": {\"S\": \"3\"},\n\":4\": {\"S\": \"4\"}}");
            // a!=1, b!=2 so inner OR is false, AND c=3 doesn't matter (false AND true = false)
            // d=4 so outer OR is true
            assertTrue(ExpressionEvaluator.matches("((a = :1 OR b = :2) AND c = :3) OR d = :4", i, null, v));
        }

        @Test
        void nestedParenthesesAllFalse() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}, \"c\": {\"S\": \"3\"}, \"d\": {\"S\": \"Z\"}}");
            JsonNode v = values("{\n\":1\": {\"S\": \"1\"},\n\":2\": {\"S\": \"2\"},\n\":3\": {\"S\": \"3\"},\n\":4\": {\"S\": \"4\"}}");
            assertFalse(ExpressionEvaluator.matches("((a = :1 OR b = :2) AND c = :3) OR d = :4", i, null, v));
        }

        // Compact format end-to-end

        @Test
        void compactFormatEvaluation() throws Exception {
            JsonNode i = item("{\"pk\": {\"S\": \"USER#1\"}, \"sk\": {\"S\": \"B\"}}");
            JsonNode v = values("{\n\":v0\": {\"S\": \"USER#1\"},\n\":v1\": {\"S\": \"A\"},\n\":v2\": {\"S\": \"C\"}}");
            JsonNode n = names("{\"#f0\": \"pk\", \"#f1\": \"sk\"}");
            assertTrue(ExpressionEvaluator.matches("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)", i, n, v));
        }

        // Null/empty expression

        @Test
        void nullExpressionMatchesAll() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}}");
            assertTrue(ExpressionEvaluator.matches(null, i, null, null));
        }

        @Test
        void blankExpressionMatchesAll() throws Exception {
            JsonNode i = item("{\"a\": {\"S\": \"1\"}}");
            assertTrue(ExpressionEvaluator.matches("  ", i, null, null));
        }
    }

    // ── Validation tests ──

    @Nested
    class ValidationTests {

        @Test
        void exactAwsErrorFormatForLexicallyInvalidTokens() {
            // Test various invalid characters that cause the tokenizer to fail
            List<String> invalidChars = List.of("-", "+", "*", "/", "&", "|", "!", "@", "~", ";", "?");

            for (String ch : invalidChars) {
                String expression = "a " + ch + " b";
                AwsException e = assertThrows(AwsException.class, () ->
                                ExpressionEvaluator.validateExpression(expression, "ConditionExpression", null, null),
                        "Expected exception for character: " + ch);

                assertEquals("ValidationException", e.getErrorCode());
                assertEquals(400, e.getHttpStatus());
                assertEquals("Invalid ConditionExpression: Syntax error; token: \"" + ch + "\", near: \"a " + ch + " b\"", e.getMessage());
            }
        }

        @Test
        void reversedBetweenBoundsAreRejectedAtParseTime() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode values = mapper.createObjectNode();
            values.set(":hi", mapper.createObjectNode().put("N", "10"));
            values.set(":lo", mapper.createObjectNode().put("N", "1"));

            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("n BETWEEN :hi AND :lo",
                            "ConditionExpression", null, values));
            assertEquals("ValidationException", e.getErrorCode());
            assertEquals("1 validation error detected: Invalid ConditionExpression: The BETWEEN operator "
                    + "requires upper bound to be greater than or equal to lower bound; lower bound operand: "
                    + "AttributeValue: {N:10}, upper bound operand: AttributeValue: {N:1}", e.getMessage());
        }

        // Checked against real DynamoDB (us-east-1, 2026-09-07): U+E000 as the lower bound
        // and U+10000 as the upper bound is accepted, because DynamoDB orders strings by
        // their UTF-8 bytes. Java's UTF-16 ordering puts them the other way round.
        @Test
        void stringBoundsAreOrderedByUtf8Bytes() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode values = mapper.createObjectNode();
            values.set(":lo", mapper.createObjectNode().put("S", "\uE000"));
            values.set(":hi", mapper.createObjectNode().put("S", "\uD800\uDC00"));

            assertDoesNotThrow(() -> ExpressionEvaluator.validateExpression("n BETWEEN :lo AND :hi",
                    "ConditionExpression", null, values));

            ObjectNode reversed = mapper.createObjectNode();
            reversed.set(":lo", mapper.createObjectNode().put("S", "\uD800\uDC00"));
            reversed.set(":hi", mapper.createObjectNode().put("S", "\uE000"));

            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("n BETWEEN :lo AND :hi",
                            "ConditionExpression", null, reversed));
            assertEquals("ValidationException", e.getErrorCode());
        }

        // Checked against real DynamoDB (us-east-1, 2026-09-07): binary bounds compare by
        // unsigned bytes, and a bound that is not valid base64 fails the request with a 400
        // SerializationException before any comparison happens.
        @Test
        void binaryBoundsCompareByBytesAndRejectMalformedBase64() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode reversed = mapper.createObjectNode();
            reversed.set(":lo", mapper.createObjectNode().put("B", "/w=="));
            reversed.set(":hi", mapper.createObjectNode().put("B", "AA=="));

            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("n BETWEEN :lo AND :hi",
                            "ConditionExpression", null, reversed));
            assertEquals("ValidationException", e.getErrorCode());

            ObjectNode malformed = mapper.createObjectNode();
            malformed.set(":lo", mapper.createObjectNode().put("B", "!!!not-base64!!!"));
            malformed.set(":hi", mapper.createObjectNode().put("B", "@@@@"));

            AwsException serialization = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("n BETWEEN :lo AND :hi",
                            "ConditionExpression", null, malformed));
            assertEquals("SerializationException", serialization.getErrorCode());
            assertEquals(400, serialization.getHttpStatus());
        }

        // Checked against real DynamoDB (ap-northeast-1, 2026-09-10): a FilterExpression
        // comparing against a binary that is not base64 fails the request with a 400
        // SerializationException, so the comparison itself must never decode blindly.
        @Test
        void binaryComparisonRejectsMalformedBase64() {
            ObjectNode valid = mapper.createObjectNode().put("B", "AQID");
            ObjectNode malformed = mapper.createObjectNode().put("B", "not base64!!");

            AwsException e = assertThrows(AwsException.class,
                    () -> ExpressionEvaluator.compareAttributeValues(valid, malformed));
            assertEquals("SerializationException", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }

        // A FilterExpression carries the same text without the envelope on AWS.
        @Test
        void filterExpressionReportsTheReversedBoundsWithoutTheEnvelope() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode values = mapper.createObjectNode();
            values.set(":hi", mapper.createObjectNode().put("N", "10"));
            values.set(":lo", mapper.createObjectNode().put("N", "1"));

            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("n BETWEEN :hi AND :lo",
                            "FilterExpression", null, values));
            assertEquals("Invalid FilterExpression: The BETWEEN operator requires upper bound to be "
                    + "greater than or equal to lower bound; lower bound operand: AttributeValue: {N:10}, "
                    + "upper bound operand: AttributeValue: {N:1}", e.getMessage());
        }

        @Test
        void orderedBetweenBoundsAreAccepted() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode values = mapper.createObjectNode();
            values.set(":lo", mapper.createObjectNode().put("N", "1"));
            values.set(":hi", mapper.createObjectNode().put("N", "10"));

            assertDoesNotThrow(() -> ExpressionEvaluator.validateExpression("n BETWEEN :lo AND :hi",
                    "ConditionExpression", null, values));
        }

        @Test
        void missingOperandThrowsSyntaxError() {
            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("a =", "FilterExpression", null, null));
            assertEquals("ValidationException", e.getErrorCode());
            assertTrue(e.getMessage().contains("Invalid FilterExpression: Syntax error"), e.getMessage());
        }

        @Test
        void redundantParenthesesThrowsSpecificError() {
            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("((a = b))", "KeyConditionExpression", null, null));
            assertEquals("ValidationException", e.getErrorCode());
            assertTrue(e.getMessage().contains("Invalid KeyConditionExpression: The expression has redundant parentheses;"), e.getMessage());
        }

        @Test
        void unexpectedTokenThrowsSyntaxError() {
            AwsException e = assertThrows(AwsException.class, () ->
                    ExpressionEvaluator.validateExpression("a AND OR b", "ConditionExpression", null, null));
            assertEquals("ValidationException", e.getErrorCode());
            assertTrue(e.getMessage().contains("Invalid ConditionExpression: Syntax error"), e.getMessage());
        }
    }
}
