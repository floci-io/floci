package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonataEvaluatorTest {

    private JsonataEvaluator evaluator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluator = new JsonataEvaluator(objectMapper);
    }

    @Test
    void isExpression_valid() {
        assertTrue(JsonataEvaluator.isExpression("{% $states.input.x %}"));
        assertTrue(JsonataEvaluator.isExpression("{%$states.input%}"));
        assertTrue(JsonataEvaluator.isExpression("{% $states.input %}"));
    }

    @Test
    void isExpression_invalid() {
        assertFalse(JsonataEvaluator.isExpression(null));
        assertFalse(JsonataEvaluator.isExpression("hello"));
        assertFalse(JsonataEvaluator.isExpression("{% incomplete"));
        assertFalse(JsonataEvaluator.isExpression("incomplete %}"));
        // Not a pure expression — AWS does not support string interpolation
        assertFalse(JsonataEvaluator.isExpression("Hello {% name %} welcome"));
    }

    @Test
    void unwrap_stripsDelimitersAndTrims() {
        assertEquals("$states.input.x", JsonataEvaluator.unwrap("{% $states.input.x %}"));
        assertEquals("1 + 2", JsonataEvaluator.unwrap("{%1 + 2%}"));
    }

    @Test
    void evaluate_simpleArithmetic() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% 1 + 2 %}", statesVar);
        assertEquals(3, result.asInt());
    }

    @Test
    void evaluate_stringConcatenation() {
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.evaluate("{% 'hello' & ' ' & 'world' %}", statesVar);
        assertEquals("hello world", result.asText());
    }

    @Test
    void evaluate_statesInputAccess() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.name %}", statesVar);
        assertEquals("Alice", result.asText());
    }

    @Test
    void evaluate_statesResultAccess() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"x": 1}, "result": {"value": 42}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.result.value %}", statesVar);
        assertEquals(42, result.asInt());
    }

    @Test
    void evaluate_booleanExpression() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"score": 85}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.score > 50 %}", statesVar);
        assertTrue(result.asBoolean());
    }

    @Test
    void evaluate_returnsNullForMissingField() throws Exception {
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice"}}
                """);
        JsonNode result = evaluator.evaluate("{% $states.input.missing %}", statesVar);
        assertTrue(result.isNull());
    }

    @Test
    void resolveTemplate_nonExpressionStringPassesThrough() throws Exception {
        JsonNode template = objectMapper.readTree("\"plain text\"");
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertEquals("plain text", result.asText());
    }

    @Test
    void resolveTemplate_evaluatesExpressionInString() throws Exception {
        JsonNode template = objectMapper.readTree("\"{% 1 + 1 %}\"");
        JsonNode statesVar = objectMapper.createObjectNode();
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertEquals(2, result.asInt());
    }

    @Test
    void resolveTemplate_walksObjectAndEvaluatesExpressions() throws Exception {
        JsonNode template = objectMapper.readTree("""
                {
                    "greeting": "{% 'Hello ' & $states.input.name %}",
                    "static": "unchanged",
                    "count": 42
                }
                """);
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Bob"}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertTrue(result.isObject());
        assertEquals("Hello Bob", result.get("greeting").asText());
        assertEquals("unchanged", result.get("static").asText());
        assertEquals(42, result.get("count").asInt());
    }

    @Test
    void resolveTemplate_walksArrayAndEvaluatesExpressions() throws Exception {
        JsonNode template = objectMapper.readTree("""
                ["{% $states.input.a %}", "static", "{% $states.input.b %}"]
                """);
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"a": 1, "b": 2}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertTrue(result.isArray());
        assertEquals(1, result.get(0).asInt());
        assertEquals("static", result.get(1).asText());
        assertEquals(2, result.get(2).asInt());
    }

    @Test
    void resolveTemplate_nonPureExpressionPassesThrough() throws Exception {
        // AWS does not support string interpolation — non-pure expressions pass through as-is
        JsonNode template = objectMapper.readTree("\"Hello {% $states.input.name %}, you are {% $states.input.age %} years old\"");
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertTrue(result.isTextual());
        assertEquals("Hello {% $states.input.name %}, you are {% $states.input.age %} years old", result.asText());
    }

    @Test
    void resolveTemplate_pureExpressionReturnsObject() throws Exception {
        JsonNode template = objectMapper.readTree("\"{% $states.input %}\"");
        JsonNode statesVar = objectMapper.readTree("""
                {"input": {"name": "Alice", "age": 30}}
                """);
        JsonNode result = evaluator.resolveTemplate(template, statesVar);
        assertTrue(result.isObject());
        assertEquals("Alice", result.get("name").asText());
    }

    @Test
    void resolveTemplate_primitivesPassThrough() throws Exception {
        JsonNode statesVar = objectMapper.createObjectNode();

        assertEquals(42, evaluator.resolveTemplate(objectMapper.readTree("42"), statesVar).asInt());
        assertTrue(evaluator.resolveTemplate(objectMapper.readTree("true"), statesVar).asBoolean());
        assertTrue(evaluator.resolveTemplate(NullNode.getInstance(), statesVar).isNull());
    }
}
