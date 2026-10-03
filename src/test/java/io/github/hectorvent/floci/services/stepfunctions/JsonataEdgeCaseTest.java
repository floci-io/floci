package io.github.hectorvent.floci.services.stepfunctions;

import com.dashjoin.jsonata.Jsonata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, Map<String, Object>> input = Map.of("input", Map.of("name", "Alice", "age", 30));

        Jsonata expr = jsonata("{\"name\": $states.input.name, \"age\": $states.input.age}");
        Jsonata.Frame frame = expr.createFrame();
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
        Map<String, Map<String, String>> input = Map.of("input", Map.of("name", "Alice"));

        Jsonata expr = jsonata("$states.input.nonexistent");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", input);

        Object result = expr.evaluate(null, frame);

        assertNull(result, "Accessing a missing field should return Java null");
    }

    @Test
    void missingVariable_returnsNull() throws Exception {
        // No binding at all for $states
        Jsonata expr = jsonata("$states.input.name");
        Jsonata.Frame frame = expr.createFrame();

        Object result = expr.evaluate(null, frame);

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
        Jsonata expr1 = jsonata("$states.input.user.address.city");
        Jsonata.Frame frame1 = expr1.createFrame();
        frame1.bind("states", nested);
        Object city = expr1.evaluate(null, frame1);

        assertEquals("Springfield", city);

        // Access into an array element
        Jsonata expr2 = jsonata("$states.input.user.tags[0]");
        Jsonata.Frame frame2 = expr2.createFrame();
        frame2.bind("states", nested);
        Object firstTag = expr2.evaluate(null, frame2);

        assertEquals("admin", firstTag);

        // Construct an object from deep paths
        Jsonata expr3 = jsonata("{\"city\": $states.input.user.address.city, \"firstTag\": $states.input.user.tags[0]}");
        Jsonata.Frame frame3 = expr3.createFrame();
        frame3.bind("states", nested);
        Object combined = expr3.evaluate(null, frame3);

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
        JsonNode jsonNode = mapper.readTree("""
                {"input": {"name": "Bob", "scores": [10, 20, 30]}}
                """);

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = mapper.convertValue(jsonNode, Map.class);

        Jsonata expr = jsonata("$sum($states.input.scores)");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", asMap);
        Object result = expr.evaluate(null, frame);

        assertEquals(60, ((Number) result).intValue());
    }

    // ---------------------------------------------------------------
    // 5. Singleton sequence: 1-element array accessed via path
    // ---------------------------------------------------------------
    @Test
    void singleElementArray_propertyAccess_preservedNotReduced() throws Exception {
        // JSONata singleton-sequence rule: when path navigation through an array yields
        // a 1-element sequence, it is reduced to the single element.
        // BUT direct property access on an object (not an array) should NOT reduce.
        //
        // $states.result.Items where Items = [{...}] — $states.result is a plain object,
        // so .Items should return the List as-is, NOT the single element.
        Map<String, Map<String, Object>> input = Map.of("result", Map.of(
                "Items", List.of(Map.of("id", "item1", "name", "Widget One")),
                "Count", 1
        ));

        Jsonata expr = jsonata("$states.result.Items");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", input);
        Object result = expr.evaluate(null, frame);

        // The result must be the List itself, not the single element within it.
        assertInstanceOf(List.class, result,
                "1-element Items array must be returned as a List (no singleton reduction)");
        assertEquals(1, ((List<?>) result).size());
    }

    @Test
    void multiElementArray_propertyAccess_returnsList() throws Exception {
        Map<String, Map<String, List<Map<String, String>>>> input = Map.of("result", Map.of(
                "Items", List.of(
                        Map.of("id", "item1"),
                        Map.of("id", "item2")
                )
        ));

        Jsonata expr = jsonata("$states.result.Items");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", input);
        Object result = expr.evaluate(null, frame);

        assertInstanceOf(List.class, result,
                "Multi-element Items array must remain a List");
        assertEquals(2, ((List<?>) result).size());
    }

    // ---------------------------------------------------------------
    // 6. Path-mapping transformation: 1-element sequence from object construction
    //    e.g. items.{"field": value} — this CAN singleton-reduce in JSONata spec
    // ---------------------------------------------------------------
    @Test
    void objectMapping_singleElement_behaviorDocumented() throws Exception {
        // JSONata spec: when you do array.{key: value}, you get a sequence.
        // A 1-element sequence IS singleton-reduced to the single element.
        // This is correct JSONata behavior (and what AWS does too).
        // To force an array, callers should use [$states.result.Items.{"id": id}]
        // or $toArray(...) if available.
        Map<String, Map<String, List<Map<String, String>>>> input = Map.of("result", Map.of(
                "Items", List.of(Map.of("id", "item1", "name", "Widget One"))
        ));

        Jsonata expr = jsonata("$states.result.Items.{\"id\": id, \"name\": name}");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", input);
        Object result = expr.evaluate(null, frame);

        // 1-element object-mapping sequence IS singleton-reduced to a plain object (Map).
        // This matches both the JSONata spec and real AWS Step Functions behavior.
        // Callers that need an array must wrap in []: [$states.result.Items.{"id": id}]
        assertInstanceOf(Map.class, result, "1-element object-mapping should be singleton-reduced to a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = (Map<String, Object>) result;
        assertEquals("item1", mapped.get("id"));
        assertEquals("Widget One", mapped.get("name"));
    }

    @Test
    void objectMapping_singleElement_wrappedInArray_forcesArray() throws Exception {
        // Workaround: wrap the mapping expression in [] to force array output.
        // [$states.result.Items.{"id": id}] — even for 1-element sequences, result is an array.
        Map<String, Map<String, List<Map<String, String>>>> input = Map.of("result", Map.of(
                "Items", List.of(Map.of("id", "item1", "name", "Widget One"))
        ));

        Jsonata expr = jsonata("[$states.result.Items.{\"id\": id, \"name\": name}]");
        Jsonata.Frame frame = expr.createFrame();
        frame.bind("states", input);
        Object result = expr.evaluate(null, frame);

        assertInstanceOf(List.class, result,
                "Wrapping in [] must force array output even for 1-element sequence");
        assertEquals(1, ((List<?>) result).size());
    }
}
