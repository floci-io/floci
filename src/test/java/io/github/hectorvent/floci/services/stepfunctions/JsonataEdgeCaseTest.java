package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests exercising the dashjoin jsonata-java library directly
 * (bypassing the project's JsonataEvaluator wrapper).
 */
class JsonataEdgeCaseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------
    // 1. Object-constructor expression: {"name": $states.input.name}
    // ---------------------------------------------------------------
    @Test
    void objectConstructor_returnsMap() throws Exception {
        var input = Map.of("input", Map.of("name", "Alice", "age", 30));

        var expr = jsonata("{\"name\": $states.input.name, \"age\": $states.input.age}");
        var frame = expr.createFrame();
        frame.bind("states", input);

        Object result = expr.evaluate(null, frame);


        assertNotNull(result, "Expected a non-null result from object constructor");
        assertInstanceOf(Map.class, result, "Object constructor should yield a Map");

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("Alice", map.get("name"));
        assertEquals(30, ((Number) map.get("age")).intValue());
    }

    // ---------------------------------------------------------------
    // 2. Missing field: does it return null, throw, or something else?
    // ---------------------------------------------------------------
    @Test
    void missingField_returnsNullNotThrows() throws Exception {
        var input = Map.of("input", Map.of("name", "Alice"));

        var expr = jsonata("$states.input.nonexistent");
        var frame = expr.createFrame();
        frame.bind("states", input);

        Object result = expr.evaluate(null, frame);

        // System.out.println("[Test 2] missing field result class: " + (result == null ? "null (Java null)" : result.getClass().getName()));
        // System.out.println("[Test 2] missing field result value: " + result);

        // Key question: is it Java null?
        assertNull(result, "Accessing a missing field should return Java null");
    }

    @Test
    void missingVariable_returnsNull() throws Exception {
        // No binding at all for $states
        var expr = jsonata("$states.input.name");
        var frame = expr.createFrame();

        Object result = expr.evaluate(null, frame);

        // System.out.println("[Test 2b] unbound variable result class: " + (result == null ? "null (Java null)" : result.getClass().getName()));
        // System.out.println("[Test 2b] unbound variable result value: " + result);

        assertNull(result, "Accessing an unbound variable should return Java null");
    }

    // ---------------------------------------------------------------
    // 3. Nested object binding with deep path access
    // ---------------------------------------------------------------
    @Test
    void deepNestedAccess_works() throws Exception {
        // Build a deeply nested structure
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("input", Map.of(
                "user", Map.of(
                        "address", Map.of(
                                "city", "Springfield",
                                "zip", "62704"
                        ),
                        "tags", java.util.List.of("admin", "active")
                )
        ));

        // Deep path access
        var expr1 = jsonata("$states.input.user.address.city");
        var frame1 = expr1.createFrame();
        frame1.bind("states", nested);
        Object city = expr1.evaluate(null, frame1);

        // System.out.println("[Test 3a] deep path city: " + city);
        assertEquals("Springfield", city);

        // Access into an array element
        var expr2 = jsonata("$states.input.user.tags[0]");
        var frame2 = expr2.createFrame();
        frame2.bind("states", nested);
        Object firstTag = expr2.evaluate(null, frame2);

        // System.out.println("[Test 3b] deep path tags[0]: " + firstTag);
        assertEquals("admin", firstTag);

        // Construct an object from deep paths
        var expr3 = jsonata("{\"city\": $states.input.user.address.city, \"firstTag\": $states.input.user.tags[0]}");
        var frame3 = expr3.createFrame();
        frame3.bind("states", nested);
        Object combined = expr3.evaluate(null, frame3);

        // System.out.println("[Test 3c] combined object: " + combined);
        assertInstanceOf(Map.class, combined);
        @SuppressWarnings("unchecked")
        Map<String, Object> combinedMap = (Map<String, Object>) combined;
        assertEquals("Springfield", combinedMap.get("city"));
        assertEquals("admin", combinedMap.get("firstTag"));
    }

    // ---------------------------------------------------------------
    // Bonus: what does convertValue look like round-tripping?
    // ---------------------------------------------------------------
    @Test
    void convertValue_jacksonToMapRoundTrip() throws Exception {
        var jsonNode = mapper.readTree("""
                {"input": {"name": "Bob", "scores": [10, 20, 30]}}
                """);

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = mapper.convertValue(jsonNode, Map.class);

        var expr = jsonata("$sum($states.input.scores)");
        var frame = expr.createFrame();
        frame.bind("states", asMap);
        Object result = expr.evaluate(null, frame);

        // System.out.println("[Test 4] sum of scores: " + result);
        assertEquals(60, ((Number) result).intValue());
    }
}
