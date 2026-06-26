package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import io.github.hectorvent.floci.core.common.AwsException;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ── Tokenizer tests ──

    @Nested
    class TokenizerTests {

        @Test
        void standardExpression() {
            var tokens = ExpressionEvaluator.tokenize("pk = :pk AND sk BETWEEN :a AND :b");
            var types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
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
            var tokens = ExpressionEvaluator.tokenize("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            var types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
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
            var tokens = ExpressionEvaluator.tokenize("a = b a <> b a < b a <= b a > b a >= b");
            var comparators = tokens.stream()
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
            var tokens = ExpressionEvaluator.tokenize("attribute_exists(a) AND begins_with(b, :v) AND contains(c, :w) AND size(d) > :x AND attribute_not_exists(e)");
            var functions = tokens.stream()
                    .filter(t -> t.type() == ExpressionEvaluator.TokenType.FUNCTION)
                    .map(ExpressionEvaluator.Token::value)
                    .toList();
            assertEquals(List.of("attribute_exists", "begins_with", "contains", "size", "attribute_not_exists"), functions);
        }

        @Test
        void inAndBetweenKeywords() {
            var tokens = ExpressionEvaluator.tokenize("x IN (:a, :b) AND y BETWEEN :c AND :d");
            assertTrue(tokens.stream().anyMatch(t -> t.type() == ExpressionEvaluator.TokenType.IN));
            assertTrue(tokens.stream().anyMatch(t -> t.type() == ExpressionEvaluator.TokenType.BETWEEN));
        }

        @Test
        void dottedPath() {
            var tokens = ExpressionEvaluator.tokenize("info.nested = :v");
            var types = tokens.stream().map(ExpressionEvaluator.Token::type).toList();
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
            var expr = ExpressionEvaluator.parse("pk = :pk");
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, expr);
        }

        @Test
        void andExpression() {
            var expr = ExpressionEvaluator.parse("a = :a AND b = :b");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            assertEquals(2, ((ExpressionEvaluator.AndExpr) expr).operands().size());
        }

        @Test
        void orExpression() {
            var expr = ExpressionEvaluator.parse("a = :a OR b = :b");
            assertInstanceOf(ExpressionEvaluator.OrExpr.class, expr);
            assertEquals(2, ((ExpressionEvaluator.OrExpr) expr).operands().size());
        }

        @Test
        void notExpression() {
            var expr = ExpressionEvaluator.parse("NOT a = :a");
            assertInstanceOf(ExpressionEvaluator.NotExpr.class, expr);
        }

        @Test
        void nestedParens() {
            var expr = ExpressionEvaluator.parse("(a = :a OR b = :b) AND c = :c");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            var and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.OrExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(1));
        }

        @Test
        void betweenAndNotConfusedWithLogicalAnd() {
            var expr = ExpressionEvaluator.parse("sk BETWEEN :a AND :b");
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, expr);
        }

        @Test
        void betweenInsideAnd() {
            var expr = ExpressionEvaluator.parse("pk = :pk AND sk BETWEEN :a AND :b");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            var and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, and.operands().get(1));
        }

        @Test
        void inOperator() {
            var expr = ExpressionEvaluator.parse("status IN (:a, :b, :c)");
            assertInstanceOf(ExpressionEvaluator.InExpr.class, expr);
            assertEquals(3, ((ExpressionEvaluator.InExpr) expr).candidates().size());
        }

        @Test
        void inOperatorSingleValue() {
            var expr = ExpressionEvaluator.parse("status IN (:a)");
            assertInstanceOf(ExpressionEvaluator.InExpr.class, expr);
            assertEquals(1, ((ExpressionEvaluator.InExpr) expr).candidates().size());
        }

        @Test
        void functionCallCondition() {
            var expr = ExpressionEvaluator.parse("attribute_exists(myAttr)");
            assertInstanceOf(ExpressionEvaluator.FunctionCallExpr.class, expr);
        }

        @Test
        void sizeComparison() {
            var expr = ExpressionEvaluator.parse("size(myList) > :val");
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, expr);
            var cmp = (ExpressionEvaluator.CompareExpr) expr;
            assertInstanceOf(ExpressionEvaluator.FunctionOperand.class, cmp.left());
        }

        @Test
        void compactFormatParsesCorrectly() {
            var expr = ExpressionEvaluator.parse("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            assertInstanceOf(ExpressionEvaluator.AndExpr.class, expr);
            var and = (ExpressionEvaluator.AndExpr) expr;
            assertInstanceOf(ExpressionEvaluator.CompareExpr.class, and.operands().get(0));
            assertInstanceOf(ExpressionEvaluator.BetweenExpr.class, and.operands().get(1));
        }
    }

    // ── splitKeyCondition tests ──

    @Nested
    class SplitKeyConditionTests {

        @Test
        void pkAndSkEquals() {
            var result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND sk = :sk");
            assertEquals("pk = :pk", result[0]);
            assertEquals("sk = :sk", result[1]);
        }

        @Test
        void pkAndSkBetweenParenthesized() {
            var result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND (sk BETWEEN :a AND :b)");
            assertEquals("pk = :pk", result[0]);
            assertEquals("(sk BETWEEN :a AND :b)", result[1]);
        }

        @Test
        void compactFormat() {
            var result = ExpressionEvaluator.splitKeyCondition("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)");
            assertEquals("(#f0 = :v0)", result[0]);
            assertEquals("(#f1 BETWEEN :v1 AND :v2)", result[1]);
        }

        @Test
        void pkOnly() {
            var result = ExpressionEvaluator.splitKeyCondition("pk = :pk");
            assertEquals("pk = :pk", result[0]);
            assertNull(result[1]);
        }

        @Test
        void pkAndSkBeginsWith() {
            var result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND begins_with(sk, :prefix)");
            assertEquals("pk = :pk", result[0]);
            assertEquals("begins_with(sk, :prefix)", result[1]);
        }

        @Test
        void pkAndSkBetweenNoParen() {
            var result = ExpressionEvaluator.splitKeyCondition("pk = :pk AND sk BETWEEN :a AND :b");
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

        // AND, OR, NOT logic

        @Test
        void andBothTrue() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"2\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("a = :a AND b = :b", i, null, v));
        }

        @Test
        void andOneFalse() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"3\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertFalse(ExpressionEvaluator.matches("a = :a AND b = :b", i, null, v));
        }

        @Test
        void orOneTrue() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}, \"b\": {\"S\": \"3\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("a = :a OR b = :b", i, null, v));
        }

        @Test
        void orBothFalse() throws Exception {
            var i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"},\n\":b\": {\"S\": \"2\"}}");
            assertFalse(ExpressionEvaluator.matches("a = :a OR b = :b", i, null, v));
        }

        @Test
        void notTrue() throws Exception {
            var i = item("{\"a\": {\"S\": \"X\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"}}");
            assertTrue(ExpressionEvaluator.matches("NOT a = :a", i, null, v));
        }

        @Test
        void notFalse() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}}");
            var v = values("{\n\":a\": {\"S\": \"1\"}}");
            assertFalse(ExpressionEvaluator.matches("NOT a = :a", i, null, v));
        }

        // Comparison operators on strings

        @Test
        void stringEquals() throws Exception {
            var i = item("{\"name\": {\"S\": \"Alice\"}}");
            var v = values("{\n\":v\": {\"S\": \"Alice\"}}");
            assertTrue(ExpressionEvaluator.matches("name = :v", i, null, v));
        }

        @Test
        void stringNotEquals() throws Exception {
            var i = item("{\"name\": {\"S\": \"Alice\"}}");
            var v = values("{\n\":v\": {\"S\": \"Bob\"}}");
            assertTrue(ExpressionEvaluator.matches("name <> :v", i, null, v));
        }

        @Test
        void stringLessThan() throws Exception {
            var i = item("{\"name\": {\"S\": \"Alice\"}}");
            var v = values("{\n\":v\": {\"S\": \"Bob\"}}");
            assertTrue(ExpressionEvaluator.matches("name < :v", i, null, v));
        }

        // Comparison operators on numbers

        @Test
        void numberEquals() throws Exception {
            var i = item("{\"age\": {\"N\": \"25\"}}");
            var v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age = :v", i, null, v));
        }

        @Test
        void numberGreaterThan() throws Exception {
            var i = item("{\"age\": {\"N\": \"30\"}}");
            var v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age > :v", i, null, v));
        }

        @Test
        void numberLessThanOrEqual() throws Exception {
            var i = item("{\"age\": {\"N\": \"25\"}}");
            var v = values("{\n\":v\": {\"N\": \"25\"}}");
            assertTrue(ExpressionEvaluator.matches("age <= :v", i, null, v));
        }

        // <> on BOOL and missing attributes

        @Test
        void boolNotEqualFalse() throws Exception {
            var i = item("{\"active\": {\"BOOL\": \"false\"}}");
            var v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertTrue(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void boolNotEqualTrue() throws Exception {
            var i = item("{\"active\": {\"BOOL\": \"true\"}}");
            var v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertFalse(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void missingAttributeNotEqual() throws Exception {
            // DynamoDB: missing <> val → true
            var i = item("{\"other\": {\"S\": \"x\"}}");
            var v = values("{\n\":v\": {\"BOOL\": \"true\"}}");
            assertTrue(ExpressionEvaluator.matches("active <> :v", i, null, v));
        }

        @Test
        void missingAttributeEquals() throws Exception {
            // DynamoDB: missing = val → false
            var i = item("{\"other\": {\"S\": \"x\"}}");
            var v = values("{\n\":v\": {\"S\": \"hello\"}}");
            assertFalse(ExpressionEvaluator.matches("name = :v", i, null, v));
        }

        // IN operator

        @Test
        void inWithNumbers() throws Exception {
            var i = item("{\"status\": {\"N\": \"2\"}}");
            var v = values("{\n\":a\": {\"N\": \"1\"},\n\":b\": {\"N\": \"2\"},\n\":c\": {\"N\": \"3\"}}");
            assertTrue(ExpressionEvaluator.matches("status IN (:a, :b, :c)", i, null, v));
        }

        @Test
        void inWithStrings() throws Exception {
            var i = item("{\"color\": {\"S\": \"red\"}}");
            var v = values("{\n\":a\": {\"S\": \"red\"},\n\":b\": {\"S\": \"blue\"}}");
            assertTrue(ExpressionEvaluator.matches("color IN (:a, :b)", i, null, v));
        }

        @Test
        void inNotMatching() throws Exception {
            var i = item("{\"color\": {\"S\": \"green\"}}");
            var v = values("{\n\":a\": {\"S\": \"red\"},\n\":b\": {\"S\": \"blue\"}}");
            assertFalse(ExpressionEvaluator.matches("color IN (:a, :b)", i, null, v));
        }

        @Test
        void inSingleValue() throws Exception {
            var i = item("{\"status\": {\"S\": \"active\"}}");
            var v = values("{\n\":a\": {\"S\": \"active\"}}");
            assertTrue(ExpressionEvaluator.matches("status IN (:a)", i, null, v));
        }

        // BETWEEN

        @Test
        void betweenStrings() throws Exception {
            var i = item("{\"sk\": {\"S\": \"B\"}}");
            var v = values("{\n\":low\": {\"S\": \"A\"},\n\":high\": {\"S\": \"C\"}}");
            assertTrue(ExpressionEvaluator.matches("sk BETWEEN :low AND :high", i, null, v));
        }

        @Test
        void betweenOutOfRange() throws Exception {
            var i = item("{\"sk\": {\"S\": \"D\"}}");
            var v = values("{\n\":low\": {\"S\": \"A\"},\n\":high\": {\"S\": \"C\"}}");
            assertFalse(ExpressionEvaluator.matches("sk BETWEEN :low AND :high", i, null, v));
        }

        // attribute_exists / attribute_not_exists

        @Test
        void attributeExistsPresent() throws Exception {
            var i = item("{\"name\": {\"S\": \"Alice\"}}");
            assertTrue(ExpressionEvaluator.matches("attribute_exists(name)", i, null, null));
        }

        @Test
        void attributeExistsMissing() throws Exception {
            var i = item("{\"other\": {\"S\": \"x\"}}");
            assertFalse(ExpressionEvaluator.matches("attribute_exists(name)", i, null, null));
        }

        @Test
        void attributeNotExistsPresent() throws Exception {
            var i = item("{\"name\": {\"S\": \"Alice\"}}");
            assertFalse(ExpressionEvaluator.matches("attribute_not_exists(name)", i, null, null));
        }

        @Test
        void attributeNotExistsMissing() throws Exception {
            var i = item("{\"other\": {\"S\": \"x\"}}");
            assertTrue(ExpressionEvaluator.matches("attribute_not_exists(name)", i, null, null));
        }

        @Test
        void attributeExistsNested() throws Exception {
            var i = item("{\"info\": {\"M\": {\"email\": {\"S\": \"a@b.com\"}}}}");
            assertTrue(ExpressionEvaluator.matches("attribute_exists(info.email)", i, null, null));
        }

        @Test
        void attributeExistsNestedMissing() throws Exception {
            var i = item("{\"info\": {\"M\": {\"name\": {\"S\": \"Alice\"}}}}");
            assertFalse(ExpressionEvaluator.matches("attribute_exists(info.email)", i, null, null));
        }

        // begins_with

        @Test
        void beginsWithMatch() throws Exception {
            var i = item("{\"sk\": {\"S\": \"USER#123\"}}");
            var v = values("{\n\":prefix\": {\"S\": \"USER#\"}}");
            assertTrue(ExpressionEvaluator.matches("begins_with(sk, :prefix)", i, null, v));
        }

        @Test
        void beginsWithNoMatch() throws Exception {
            var i = item("{\"sk\": {\"S\": \"ORDER#123\"}}");
            var v = values("{\n\":prefix\": {\"S\": \"USER#\"}}");
            assertFalse(ExpressionEvaluator.matches("begins_with(sk, :prefix)", i, null, v));
        }

        // contains

        @Test
        void containsString() throws Exception {
            var i = item("{\"desc\": {\"S\": \"hello world\"}}");
            var v = values("{\n\":sub\": {\"S\": \"world\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(desc, :sub)", i, null, v));
        }

        @Test
        void containsList() throws Exception {
            var i = item("{\"tags\": {\"L\": [{\"S\": \"a\"}, {\"S\": \"b\"}, {\"S\": \"c\"}]}}");
            var v = values("{\n\":val\": {\"S\": \"b\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(tags, :val)", i, null, v));
        }

        @Test
        void containsStringSet() throws Exception {
            var i = item("{\"tags\": {\"SS\": [\"a\", \"b\", \"c\"]}}");
            var v = values("{\n\":val\": {\"S\": \"b\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(tags, :val)", i, null, v));
        }

        @Test
        void containsNumberSet() throws Exception {
            var i = item("{\"nums\": {\"NS\": [\"1\", \"2\", \"3\"]}}");
            var v = values("{\n\":val\": {\"N\": \"2\"}}");
            assertTrue(ExpressionEvaluator.matches("contains(nums, :val)", i, null, v));
        }

        // Expression attribute names

        @Test
        void expressionAttributeNames() throws Exception {
            var i = item("{\"status\": {\"S\": \"active\"}}");
            var v = values("{\n\":v\": {\"S\": \"active\"}}");
            var n = names("{\"#s\": \"status\"}");
            assertTrue(ExpressionEvaluator.matches("#s = :v", i, n, v));
        }

        // Nested parentheses

        @Test
        void nestedParentheses() throws Exception {
            // ((a = :1 OR b = :2) AND c = :3) OR d = :4
            var i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}, \"c\": {\"S\": \"3\"}, \"d\": {\"S\": \"4\"}}");
            var v = values("{\n\":1\": {\"S\": \"1\"},\n\":2\": {\"S\": \"2\"},\n\":3\": {\"S\": \"3\"},\n\":4\": {\"S\": \"4\"}}");
            // a!=1, b!=2 so inner OR is false, AND c=3 doesn't matter (false AND true = false)
            // d=4 so outer OR is true
            assertTrue(ExpressionEvaluator.matches("((a = :1 OR b = :2) AND c = :3) OR d = :4", i, null, v));
        }

        @Test
        void nestedParenthesesAllFalse() throws Exception {
            var i = item("{\"a\": {\"S\": \"X\"}, \"b\": {\"S\": \"Y\"}, \"c\": {\"S\": \"3\"}, \"d\": {\"S\": \"Z\"}}");
            var v = values("{\n\":1\": {\"S\": \"1\"},\n\":2\": {\"S\": \"2\"},\n\":3\": {\"S\": \"3\"},\n\":4\": {\"S\": \"4\"}}");
            assertFalse(ExpressionEvaluator.matches("((a = :1 OR b = :2) AND c = :3) OR d = :4", i, null, v));
        }

        // Compact format end-to-end

        @Test
        void compactFormatEvaluation() throws Exception {
            var i = item("{\"pk\": {\"S\": \"USER#1\"}, \"sk\": {\"S\": \"B\"}}");
            var v = values("{\n\":v0\": {\"S\": \"USER#1\"},\n\":v1\": {\"S\": \"A\"},\n\":v2\": {\"S\": \"C\"}}");
            var n = names("{\"#f0\": \"pk\", \"#f1\": \"sk\"}");
            assertTrue(ExpressionEvaluator.matches("(#f0 = :v0)AND(#f1 BETWEEN :v1 AND :v2)", i, n, v));
        }

        // Null/empty expression

        @Test
        void nullExpressionMatchesAll() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}}");
            assertTrue(ExpressionEvaluator.matches(null, i, null, null));
        }

        @Test
        void blankExpressionMatchesAll() throws Exception {
            var i = item("{\"a\": {\"S\": \"1\"}}");
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
