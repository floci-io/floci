package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NestedExecutionInput}: the shared intrinsic function-name parse, the
 * States.JsonToString provenance check, and the child-input encoding matrix. Plain JUnit5 + Jackson,
 * so it runs in the offline sandbox (unlike the Vert.x-bound AslExecutor end-to-end path).
 */
class NestedExecutionInputTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static String j(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    // ── intrinsicFunctionName (shared with AslExecutor.evaluateIntrinsic) ──

    @Test
    void intrinsicFunctionName_parsesAndToleratesWhitespace() {
        assertEquals("States.JsonToString", NestedExecutionInput.intrinsicFunctionName("States.JsonToString($.x)"));
        assertEquals("States.JsonToString", NestedExecutionInput.intrinsicFunctionName("States.JsonToString ($.x)"));
        assertEquals("States.Format", NestedExecutionInput.intrinsicFunctionName("States.Format(States.JsonToString($.x))"));
        assertNull(NestedExecutionInput.intrinsicFunctionName("$.x"));   // not a call
        assertNull(NestedExecutionInput.intrinsicFunctionName(null));
    }

    // ── provenance ──

    @Test
    void isJsonToStringInput_detectsTopLevelJsonToString() {
        assertTrue(NestedExecutionInput.isJsonToStringInput(node(j("{'Input.$':'States.JsonToString($.x)'}"))));
        assertTrue(NestedExecutionInput.isJsonToStringInput(node(j("{'Input.$':'States.JsonToString ($.x)'}"))));
        assertFalse(NestedExecutionInput.isJsonToStringInput(node(j("{'Input.$':'$.y'}"))), "plain path");
        assertFalse(NestedExecutionInput.isJsonToStringInput(node(j("{'Input.$':'States.Format(States.JsonToString($.x))'}"))),
                "wrapped: not top-level JsonToString");
        assertFalse(NestedExecutionInput.isJsonToStringInput(node(j("{'Input':{'a':1}}"))), "literal Input, no Input.$");
        assertFalse(NestedExecutionInput.isJsonToStringInput(null));
        assertFalse(NestedExecutionInput.isJsonToStringInput(TextNode.valueOf("x")), "non-object");
    }

    // ── childInput encoding matrix (assert the CHILD's parsed $ type) ──

    @Test
    void childInput_matrix() throws Exception {
        // Row 1/2: object literal or .$ -> object  => child $ is an object
        JsonNode row12 = NestedExecutionInput.childInput(node(j("{'a':1}")), false, OM).transform(this::parse);
        assertTrue(row12.isObject());
        assertEquals(1, row12.get("a").asInt());

        // Row 3: States.JsonToString(object) -> the JSON text is used verbatim => child $ is an OBJECT (the fix)
        JsonNode row3 = parse(NestedExecutionInput.childInput(TextNode.valueOf(j("{'a':1}")), true, OM));
        assertTrue(row3.isObject(), "JsonToString(object) must yield an object in the child");
        assertEquals(1, row3.get("a").asInt());

        // Row 4: plain string "hello" (not JsonToString) => child $ is the string "hello"
        JsonNode row4 = parse(NestedExecutionInput.childInput(TextNode.valueOf("hello"), false, OM));
        assertTrue(row4.isTextual());
        assertEquals("hello", row4.asText());

        // Row 5: string that LOOKS like JSON but is NOT from JsonToString => stays a string (distinct from row 3)
        JsonNode row5 = parse(NestedExecutionInput.childInput(TextNode.valueOf(j("{'a':1}")), false, OM));
        assertTrue(row5.isTextual(), "a JSON-looking string without JsonToString provenance must stay a string");
        assertEquals(j("{'a':1}"), row5.asText());

        // Row 6: States.JsonToString(string) -> JSON text of a string is a quoted string => child $ is that string
        JsonNode row6 = parse(NestedExecutionInput.childInput(TextNode.valueOf(j("'hi'")), true, OM));
        assertTrue(row6.isTextual());
        assertEquals("hi", row6.asText());

        // Row 7: missing Input => "{}"
        assertEquals("{}", NestedExecutionInput.childInput(MissingNode.getInstance(), true, OM));
        assertEquals("{}", NestedExecutionInput.childInput(null, false, OM));
    }

    @Test
    void childInput_provenanceTrueButNonJsonValueIsQuotedNotVerbatim() throws Exception {
        // Collision-template edge: provenance says JsonToString but the winning value isn't JSON text.
        // It must be quoted (a valid JSON string), never emitted verbatim (which parseInput would drop to {}).
        JsonNode child = parse(NestedExecutionInput.childInput(TextNode.valueOf("hello"), true, OM));
        assertTrue(child.isTextual());
        assertEquals("hello", child.asText());
    }

    private JsonNode node(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode parse(String json) {
        return node(json);
    }
}
