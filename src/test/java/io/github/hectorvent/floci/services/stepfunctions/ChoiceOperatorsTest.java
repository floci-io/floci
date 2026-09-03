package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChoiceOperators}: the extracted, dependency-light Choice comparator
 * evaluator + validator. Exercises all 39 data-test operators, type strictness, RFC3339 timestamp
 * handling, missing-path/unknown-operator errors, And/Or/Not, and structural validation. Runs as
 * plain JUnit5 (no Quarkus), which is what makes it executable in this sandbox.
 *
 * <p>JSON literals are written with single quotes and converted via {@link #j} to keep them readable.
 */
class ChoiceOperatorsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Single-quoted JSON -> double-quoted, for readable literals (no test datum contains a single quote). */
    private static String j(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private static ChoiceOperators.PathResolver resolverFor(JsonNode input) {
        return path -> {
            if (path == null || path.equals("$")) {
                return input;
            }
            if (!path.startsWith("$.")) {
                return MissingNode.getInstance();
            }
            return input.at("/" + path.substring(2).replace(".", "/"));
        };
    }

    private static boolean eval(String ruleJson, String inputJson) throws Exception {
        return ChoiceOperators.evaluate(OM.readTree(ruleJson), resolverFor(OM.readTree(inputJson)));
    }

    private static List<String> validate(String ruleJson) throws Exception {
        List<String> errors = new ArrayList<>();
        ChoiceOperators.validateChoiceRule("/States/C/Choices/0", OM.readTree(ruleJson), true, errors);
        return errors;
    }

    // ──────────────────────────── Inventory ────────────────────────────

    @Test
    void inventoryIs39DataTestOperators() {
        assertEquals(39, ChoiceOperators.DATA_TEST_OPERATORS.size(),
                "expected 39 documented data-test operators");
        for (String op : List.of("NumericGreaterThanEqualsPath", "NumericLessThanEqualsPath",
                "StringGreaterThanPath", "StringLessThanEqualsPath",
                "TimestampGreaterThanEquals", "TimestampLessThanPath", "IsTimestamp")) {
            assertTrue(ChoiceOperators.DATA_TEST_OPERATORS.contains(op), "missing " + op);
        }
    }

    @Test
    void everyOperatorValidatesWithAWellFormedRule() {
        for (String op : ChoiceOperators.DATA_TEST_OPERATORS) {
            ObjectNode rule = OM.createObjectNode();
            rule.put("Variable", "$.a");
            rule.set(op, validOperandFor(op));
            rule.put("Next", "X");
            List<String> errs = new ArrayList<>();
            ChoiceOperators.validateChoiceRule("/c/0", rule, true, errs);
            assertTrue(errs.isEmpty(), op + " should validate cleanly but got: " + errs);
        }
    }

    private static JsonNode validOperandFor(String op) {
        if (ChoiceOperators.TYPE_TEST_OPERATORS.contains(op)) {
            return BooleanNode.TRUE;
        }
        if (op.endsWith("Path")) {
            return TextNode.valueOf("$.b");
        }
        if (ChoiceOperators.NUMERIC_OPERATORS.contains(op)) {
            return IntNode.valueOf(1);
        }
        if (ChoiceOperators.TIMESTAMP_OPERATORS.contains(op)) {
            return TextNode.valueOf("2016-08-18T17:33:00Z");
        }
        if (ChoiceOperators.BOOLEAN_OPERATORS.contains(op)) {
            return BooleanNode.TRUE;
        }
        return TextNode.valueOf("x"); // String family incl. StringMatches
    }

    // ──────────────────────────── Numeric ────────────────────────────

    @Test
    void numericGreaterThanEqualsPath_handoffMatrix() throws Exception {
        String rule = j("{'Variable':'$.a','NumericGreaterThanEqualsPath':'$.b'}");
        assertTrue(eval(rule, j("{'a':5,'b':3}")));   // 5 >= 3
        assertTrue(eval(rule, j("{'a':3,'b':3}")));   // 3 >= 3
        assertFalse(eval(rule, j("{'a':2,'b':3}")));  // 2 >= 3 is false
    }

    @Test
    void numericComparators() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','NumericEquals':5}"), j("{'a':5}")));
        assertTrue(eval(j("{'Variable':'$.a','NumericEquals':5}"), j("{'a':5.0}")));   // 5 == 5.0
        assertTrue(eval(j("{'Variable':'$.a','NumericLessThan':5}"), j("{'a':4}")));
        assertFalse(eval(j("{'Variable':'$.a','NumericLessThan':5}"), j("{'a':5}")));
        assertTrue(eval(j("{'Variable':'$.a','NumericLessThanEquals':5}"), j("{'a':5}")));
        assertTrue(eval(j("{'Variable':'$.a','NumericGreaterThan':5}"), j("{'a':6}")));
        assertTrue(eval(j("{'Variable':'$.a','NumericEqualsPath':'$.b'}"), j("{'a':7,'b':7}")));
    }

    // ──────────────────────────── String ────────────────────────────

    @Test
    void stringComparators() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','StringEquals':'x'}"), j("{'a':'x'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringLessThan':'b'}"), j("{'a':'a'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringGreaterThan':'a'}"), j("{'a':'b'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringLessThanEquals':'b'}"), j("{'a':'b'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringGreaterThanEquals':'b'}"), j("{'a':'b'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringGreaterThanEqualsPath':'$.b'}"), j("{'a':'m','b':'m'}")));
        assertTrue(eval(j("{'Variable':'$.a','StringMatches':'foo*baz'}"), j("{'a':'fooBARbaz'}")));
        assertFalse(eval(j("{'Variable':'$.a','StringMatches':'foo*baz'}"), j("{'a':'fooBAR'}")));
    }

    // ──────────────────────────── Boolean ────────────────────────────

    @Test
    void booleanComparators() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','BooleanEquals':true}"), j("{'a':true}")));
        assertFalse(eval(j("{'Variable':'$.a','BooleanEquals':true}"), j("{'a':false}")));
        assertTrue(eval(j("{'Variable':'$.a','BooleanEqualsPath':'$.b'}"), j("{'a':true,'b':true}")));
    }

    // ──────────────────────────── Timestamp ────────────────────────────

    @Test
    void timestampComparators_and_offsetEquivalence() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','TimestampEquals':'2016-08-18T17:33:00Z'}"),
                j("{'a':'2016-08-18T17:33:00Z'}")));
        // 17:33Z == 18:33+01:00 (same instant)
        assertTrue(eval(j("{'Variable':'$.a','TimestampEquals':'2016-08-18T18:33:00+01:00'}"),
                j("{'a':'2016-08-18T17:33:00Z'}")));
        assertTrue(eval(j("{'Variable':'$.a','TimestampLessThan':'2016-08-18T17:33:01Z'}"),
                j("{'a':'2016-08-18T17:33:00Z'}")));
        assertTrue(eval(j("{'Variable':'$.a','TimestampGreaterThanEquals':'2016-08-18T17:33:00Z'}"),
                j("{'a':'2016-08-18T17:33:00Z'}")));
        assertTrue(eval(j("{'Variable':'$.a','TimestampEqualsPath':'$.b'}"),
                j("{'a':'2016-08-18T17:33:00Z','b':'2016-08-18T17:33:00Z'}")));
    }

    // ──────────────────────────── Type predicates ────────────────────────────

    @Test
    void typeTests() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','IsNull':true}"), j("{'a':null}")));
        assertFalse(eval(j("{'Variable':'$.a','IsNull':true}"), j("{'a':5}")));
        assertTrue(eval(j("{'Variable':'$.a','IsString':true}"), j("{'a':'x'}")));
        assertTrue(eval(j("{'Variable':'$.a','IsNumeric':true}"), j("{'a':5}")));
        assertTrue(eval(j("{'Variable':'$.a','IsBoolean':true}"), j("{'a':true}")));
        assertTrue(eval(j("{'Variable':'$.a','IsTimestamp':true}"), j("{'a':'2016-08-18T17:33:00Z'}")));
        assertFalse(eval(j("{'Variable':'$.a','IsTimestamp':true}"), j("{'a':'not-a-date'}")));
    }

    @Test
    void isPresentTrueFalse_neverThrowsOnMissing() throws Exception {
        assertTrue(eval(j("{'Variable':'$.a','IsPresent':true}"), j("{'a':1}")));
        assertTrue(eval(j("{'Variable':'$.a','IsPresent':true}"), j("{'a':null}"))); // explicit null is present
        assertFalse(eval(j("{'Variable':'$.missing','IsPresent':true}"), j("{'a':1}")));
        assertTrue(eval(j("{'Variable':'$.missing','IsPresent':false}"), j("{'a':1}")));
    }

    // ──────────────────────────── Type strictness ────────────────────────────

    @Test
    void typeMismatchesAreFalseNotCoerced() throws Exception {
        assertFalse(eval(j("{'Variable':'$.a','NumericEquals':5}"), j("{'a':'5'}")));   // string vs numeric
        assertFalse(eval(j("{'Variable':'$.a','StringEquals':'5'}"), j("{'a':5}")));    // numeric vs string
        assertFalse(eval(j("{'Variable':'$.a','BooleanEquals':true}"), j("{'a':'true'}")));
        assertFalse(eval(j("{'Variable':'$.a','TimestampEquals':'2016-08-18T17:33:00Z'}"),
                j("{'a':'not-a-date'}")));
    }

    // ──────────────────────────── Missing path / unknown operator ────────────────────────────

    @Test
    void missingVariablePathThrows_forNonPresenceOperators() {
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.missing','NumericEquals':5}"), j("{'a':1}")));
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.missing','IsNull':true}"), j("{'a':1}")));
    }

    @Test
    void missingOperandPathThrows() {
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.a','NumericEqualsPath':'$.missing'}"), j("{'a':1}")));
    }

    @Test
    void unknownOperatorThrowsAtRuntime() {
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.a','NumericSpicyThan':5}"), j("{'a':1}")));
    }

    // ──────────────────────────── RFC3339 lexical gate ────────────────────────────

    @Test
    void rfc3339InvalidFormsAreRejected() throws Exception {
        for (String bad : List.of(
                "2016-08-18t17:33:00z",     // lowercase t/z
                "2024-01-01",               // bare date
                "2024-01-01T00:00Z",        // missing seconds
                "2024-01-01T00:00:00+01",   // truncated offset
                "2024-01-01T00:00:00+0100", // non-colonized offset
                "2024-01-01T00:00:00",      // no offset
                "2024-13-01T00:00:00Z")) {  // month 13
            assertFalse(eval(j("{'Variable':'$.a','IsTimestamp':true}"),
                    OM.createObjectNode().put("a", bad).toString()), "should reject: " + bad);
        }
    }

    // ──────────────────────────── Logical operators ────────────────────────────

    @Test
    void logicalOperators() throws Exception {
        String and = j("{'And':[{'Variable':'$.a','NumericGreaterThan':0},{'Variable':'$.b','StringEquals':'x'}]}");
        assertTrue(eval(and, j("{'a':1,'b':'x'}")));
        assertFalse(eval(and, j("{'a':1,'b':'y'}")));
        String or = j("{'Or':[{'Variable':'$.a','NumericGreaterThan':10},{'Variable':'$.b','StringEquals':'x'}]}");
        assertTrue(eval(or, j("{'a':1,'b':'x'}")));
        String not = j("{'Not':{'Variable':'$.a','NumericLessThan':3}}");
        assertTrue(eval(not, j("{'a':5}")));
        assertFalse(eval(not, j("{'a':2}")));
    }

    // ──────────────────────────── Validation ────────────────────────────

    @Test
    void validation_inventedComparatorBesideValidIsRejected() throws Exception {
        List<String> errs = validate(j("{'Variable':'$.a','NumericEquals':1,'NumericSpicyThan':2,'Next':'X'}"));
        assertFalse(errs.isEmpty(), "invented comparator beside a valid one must be rejected");
    }

    @Test
    void validation_rejectsBadShapes() throws Exception {
        assertFalse(validate(j("{'Variable':'$.a','NumericEquals':1,'StringEquals':'y','Next':'X'}")).isEmpty(),
                "two data-test operators");
        assertFalse(validate(j("{'And':[],'Next':'X'}")).isEmpty(), "empty And");
        assertFalse(validate(j("{'Not':[{'Variable':'$.a','IsNull':true}],'Next':'X'}")).isEmpty(),
                "Not must be an object, not array");
        assertFalse(validate(j("{'Variable':'$.a','NumericEquals':1}")).isEmpty(),
                "top-level rule missing Next");
        assertFalse(validate(j("{'And':[{'Variable':'$.a','IsNull':true,'Next':'Y'}],'Next':'X'}")).isEmpty(),
                "Next forbidden on nested operand");
        assertFalse(validate(j("{'Variable':'$.a','NumericEquals':'5','Next':'X'}")).isEmpty(),
                "numeric operator with string operand");
        assertFalse(validate(j("{'Variable':'$.a','TimestampEquals':'nope','Next':'X'}")).isEmpty(),
                "timestamp operator with non-timestamp operand");
        assertFalse(validate(j("{'Variable':'$.a','StringEqualsPath':'notapath','Next':'X'}")).isEmpty(),
                "path operator with non-path operand");
        assertFalse(validate(j("{'And':[{'Variable':'$.a','IsNull':true}],'Variable':'$.b','Next':'X'}")).isEmpty(),
                "logical rule must not carry Variable");
        assertFalse(validate(j("{'Variable':'$.a','IsNull':true,'Bogus':1,'Next':'X'}")).isEmpty(),
                "unsupported field");
    }

    @Test
    void validation_wellFormedRulesPass() throws Exception {
        assertTrue(validate(j("{'Variable':'$.a','NumericGreaterThanEqualsPath':'$.b','Next':'X'}")).isEmpty());
        assertTrue(validate(
                j("{'And':[{'Variable':'$.a','NumericEquals':1},{'Variable':'$.b','IsPresent':true}],'Next':'X'}"))
                .isEmpty());
        assertTrue(validate(j("{'Not':{'Variable':'$.a','StringEquals':'x'},'Next':'X'}")).isEmpty());
    }

    // ──────────────────────────── Authoritative inventory ────────────────────────────

    @Test
    void dataTestOperatorsMatchAuthoritativeLiteralSet() {
        // Independent literal list: must equal the production set exactly (not self-referential).
        Set<String> expected = Set.of(
                "StringEquals", "StringEqualsPath", "StringLessThan", "StringLessThanPath",
                "StringGreaterThan", "StringGreaterThanPath", "StringLessThanEquals", "StringLessThanEqualsPath",
                "StringGreaterThanEquals", "StringGreaterThanEqualsPath", "StringMatches",
                "NumericEquals", "NumericEqualsPath", "NumericLessThan", "NumericLessThanPath",
                "NumericGreaterThan", "NumericGreaterThanPath", "NumericLessThanEquals", "NumericLessThanEqualsPath",
                "NumericGreaterThanEquals", "NumericGreaterThanEqualsPath",
                "BooleanEquals", "BooleanEqualsPath",
                "TimestampEquals", "TimestampEqualsPath", "TimestampLessThan", "TimestampLessThanPath",
                "TimestampGreaterThan", "TimestampGreaterThanPath", "TimestampLessThanEquals",
                "TimestampLessThanEqualsPath", "TimestampGreaterThanEquals", "TimestampGreaterThanEqualsPath",
                "IsNull", "IsPresent", "IsNumeric", "IsString", "IsBoolean", "IsTimestamp");
        assertEquals(expected, ChoiceOperators.DATA_TEST_OPERATORS);
    }

    // ──────────────────────────── Every operator: a true and a false ────────────────────────────

    private void assertOpEval(String op, JsonNode operand, String trueInput, String falseInput) throws Exception {
        ObjectNode rule = OM.createObjectNode();
        rule.put("Variable", "$.a");
        rule.set(op, operand);
        assertTrue(ChoiceOperators.evaluate(rule, resolverFor(OM.readTree(j(trueInput)))),
                op + " should match " + trueInput);
        assertFalse(ChoiceOperators.evaluate(rule, resolverFor(OM.readTree(j(falseInput)))),
                op + " should not match " + falseInput);
    }

    @Test
    void everyOperatorEvaluatesTrueAndFalse() throws Exception {
        String TS = "2016-08-18T17:33:00Z";
        String TS0 = "2016-08-18T17:32:59Z";
        String TS1 = "2016-08-18T17:33:01Z";
        JsonNode m = TextNode.valueOf("m");
        JsonNode five = IntNode.valueOf(5);
        JsonNode t = BooleanNode.TRUE;
        JsonNode pb = TextNode.valueOf("$.b");
        JsonNode pts = TextNode.valueOf(TS);
        // String (non-path)
        assertOpEval("StringEquals", m, "{'a':'m'}", "{'a':'n'}");
        assertOpEval("StringLessThan", m, "{'a':'a'}", "{'a':'z'}");
        assertOpEval("StringGreaterThan", m, "{'a':'z'}", "{'a':'a'}");
        assertOpEval("StringLessThanEquals", m, "{'a':'m'}", "{'a':'z'}");
        assertOpEval("StringGreaterThanEquals", m, "{'a':'m'}", "{'a':'a'}");
        assertOpEval("StringMatches", TextNode.valueOf("f*"), "{'a':'foo'}", "{'a':'bar'}");
        // String (path, operand -> $.b = "m")
        assertOpEval("StringEqualsPath", pb, "{'a':'m','b':'m'}", "{'a':'n','b':'m'}");
        assertOpEval("StringLessThanPath", pb, "{'a':'a','b':'m'}", "{'a':'z','b':'m'}");
        assertOpEval("StringGreaterThanPath", pb, "{'a':'z','b':'m'}", "{'a':'a','b':'m'}");
        assertOpEval("StringLessThanEqualsPath", pb, "{'a':'m','b':'m'}", "{'a':'z','b':'m'}");
        assertOpEval("StringGreaterThanEqualsPath", pb, "{'a':'m','b':'m'}", "{'a':'a','b':'m'}");
        // Numeric (non-path)
        assertOpEval("NumericEquals", five, "{'a':5}", "{'a':6}");
        assertOpEval("NumericLessThan", five, "{'a':4}", "{'a':5}");
        assertOpEval("NumericGreaterThan", five, "{'a':6}", "{'a':5}");
        assertOpEval("NumericLessThanEquals", five, "{'a':5}", "{'a':6}");
        assertOpEval("NumericGreaterThanEquals", five, "{'a':5}", "{'a':4}");
        // Numeric (path)
        assertOpEval("NumericEqualsPath", pb, "{'a':5,'b':5}", "{'a':6,'b':5}");
        assertOpEval("NumericLessThanPath", pb, "{'a':4,'b':5}", "{'a':5,'b':5}");
        assertOpEval("NumericGreaterThanPath", pb, "{'a':6,'b':5}", "{'a':5,'b':5}");
        assertOpEval("NumericLessThanEqualsPath", pb, "{'a':5,'b':5}", "{'a':6,'b':5}");
        assertOpEval("NumericGreaterThanEqualsPath", pb, "{'a':5,'b':5}", "{'a':2,'b':5}");
        // Boolean
        assertOpEval("BooleanEquals", t, "{'a':true}", "{'a':false}");
        assertOpEval("BooleanEqualsPath", pb, "{'a':true,'b':true}", "{'a':false,'b':true}");
        // Timestamp (non-path, operand TS)
        assertOpEval("TimestampEquals", pts, "{'a':'" + TS + "'}", "{'a':'" + TS1 + "'}");
        assertOpEval("TimestampLessThan", pts, "{'a':'" + TS0 + "'}", "{'a':'" + TS + "'}");
        assertOpEval("TimestampGreaterThan", pts, "{'a':'" + TS1 + "'}", "{'a':'" + TS + "'}");
        assertOpEval("TimestampLessThanEquals", pts, "{'a':'" + TS + "'}", "{'a':'" + TS1 + "'}");
        assertOpEval("TimestampGreaterThanEquals", pts, "{'a':'" + TS + "'}", "{'a':'" + TS0 + "'}");
        // Timestamp (path, $.b = TS)
        assertOpEval("TimestampEqualsPath", pb, "{'a':'" + TS + "','b':'" + TS + "'}", "{'a':'" + TS1 + "','b':'" + TS + "'}");
        assertOpEval("TimestampLessThanPath", pb, "{'a':'" + TS0 + "','b':'" + TS + "'}", "{'a':'" + TS + "','b':'" + TS + "'}");
        assertOpEval("TimestampGreaterThanPath", pb, "{'a':'" + TS1 + "','b':'" + TS + "'}", "{'a':'" + TS + "','b':'" + TS + "'}");
        assertOpEval("TimestampLessThanEqualsPath", pb, "{'a':'" + TS + "','b':'" + TS + "'}", "{'a':'" + TS1 + "','b':'" + TS + "'}");
        assertOpEval("TimestampGreaterThanEqualsPath", pb, "{'a':'" + TS + "','b':'" + TS + "'}", "{'a':'" + TS0 + "','b':'" + TS + "'}");
        // Type predicates
        assertOpEval("IsNull", t, "{'a':null}", "{'a':5}");
        assertOpEval("IsPresent", t, "{'a':1}", "{}");
        assertOpEval("IsNumeric", t, "{'a':5}", "{'a':'x'}");
        assertOpEval("IsString", t, "{'a':'x'}", "{'a':5}");
        assertOpEval("IsBoolean", t, "{'a':true}", "{'a':5}");
        assertOpEval("IsTimestamp", t, "{'a':'" + TS + "'}", "{'a':'x'}");
    }

    // ──────────────────────────── StringMatches escapes ────────────────────────────

    @Test
    void stringMatches_escapeSemantics() throws Exception {
        // \* is a literal asterisk
        ObjectNode litStar = OM.createObjectNode();
        litStar.put("Variable", "$.a");
        litStar.put("StringMatches", "a\\*b");   // glob: a \* b
        assertTrue(ChoiceOperators.evaluate(litStar, resolverFor(OM.readTree(j("{'a':'a*b'}")))));
        assertFalse(ChoiceOperators.evaluate(litStar, resolverFor(OM.readTree(j("{'a':'aXb'}")))));
        // \\ is a literal backslash
        ObjectNode litBackslash = OM.createObjectNode();
        litBackslash.put("Variable", "$.a");
        litBackslash.put("StringMatches", "a\\\\b"); // glob: a \\ b
        assertTrue(ChoiceOperators.evaluate(litBackslash, resolverFor(OM.readTree("{\"a\":\"a\\\\b\"}"))));
        // dangling escape -> error
        ObjectNode dangling = OM.createObjectNode();
        dangling.put("Variable", "$.a");
        dangling.put("StringMatches", "abc\\");
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> ChoiceOperators.evaluate(dangling, resolverFor(OM.readTree(j("{'a':'abc'}")))));
        // illegal escape -> error, at validation time too
        assertFalse(validate(j("{'Variable':'$.a','StringMatches':'a\\\\q','Next':'X'}")).isEmpty());
    }

    // ──────────────────────────── Reference-path & Assign validation ────────────────────────────

    @Test
    void validation_rejectsBadReferencePathsAndAssign() throws Exception {
        assertFalse(validate(j("{'Variable':'not-a-path','NumericEquals':1,'Next':'X'}")).isEmpty(),
                "Variable must be a reference path");
        assertFalse(validate(j("{'Variable':'$.','NumericEquals':1,'Next':'X'}")).isEmpty(),
                "'$.' is not a valid reference path");
        assertFalse(validate(j("{'Variable':'$.a','StringEqualsPath':'$[','Next':'X'}")).isEmpty(),
                "'$[' is not a valid reference-path operand");
        assertFalse(validate(j("{'Variable':'$.a','IsNull':true,'Assign':5,'Next':'X'}")).isEmpty(),
                "Assign must be an object");
        assertTrue(validate(j("{'Variable':'$.a[0].b','NumericEquals':1,'Next':'X'}")).isEmpty(),
                "valid indexed reference path accepted");
        assertTrue(ChoiceOperators.isReferencePath("$"));
        assertTrue(ChoiceOperators.isReferencePath("$.a.b"));
        assertTrue(ChoiceOperators.isReferencePath("$[0]"));
        assertFalse(ChoiceOperators.isReferencePath("$."));
        assertFalse(ChoiceOperators.isReferencePath("$["));
        assertFalse(ChoiceOperators.isReferencePath("nope"));
    }

    // ──────────────────────────── Runtime backstop for malformed persisted rules ────────────────────────────

    @Test
    void runtimeBackstopRejectsMalformedRules() {
        // two data-test operators
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.a','StringEquals':'x','NumericEquals':1}"), j("{'a':'x'}")));
        // a valid operator plus an invented field
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.a','StringEquals':'x','BogusThan':1}"), j("{'a':'x'}")));
        // no operator at all
        assertThrows(ChoiceOperators.ChoiceEvaluationException.class,
                () -> eval(j("{'Variable':'$.a'}"), j("{'a':'x'}")));
    }
}
