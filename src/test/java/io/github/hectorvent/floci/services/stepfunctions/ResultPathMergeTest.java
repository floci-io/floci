package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ResultPathMerge}: the extracted ResultPath merge, including the AWS
 * {@code States.ResultPathMatchFailure} case (previously a silent input-discard) and the documented
 * residuals. Plain JUnit5 + Jackson, so it runs in the offline sandbox.
 */
class ResultPathMergeTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static String j(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private JsonNode n(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nullResultPathKeepsInput() {
        JsonNode input = n(j("{'a':1}"));
        assertEquals(input, ResultPathMerge.merge(input, null, n(j("{'b':2}")), OM));
        assertEquals(input, ResultPathMerge.merge(input, "null", n(j("{'b':2}")), OM), "literal 'null' keeps input");
    }

    @Test
    void dollarReplacesWithResult() {
        JsonNode result = n(j("{'b':2}"));
        assertEquals(result, ResultPathMerge.merge(n(j("{'a':1}")), "$", result, OM));
    }

    @Test
    void objectInputMergesAtPath() {
        JsonNode out = ResultPathMerge.merge(n(j("{'a':1}")), "$.r", n(j("{'b':2}")), OM);
        assertEquals(1, out.get("a").asInt());
        assertEquals(2, out.get("r").get("b").asInt());
    }

    @Test
    void objectInputMergesAtNestedPath() {
        JsonNode out = ResultPathMerge.merge(n("{}"), "$.a.b", n("5"), OM);
        assertEquals(5, out.get("a").get("b").asInt());
    }

    @Test
    void nonObjectInputWithObjectMemberPathRaisesResultPathMatchFailure() {
        // The fix: string / array / number input + a $.field ResultPath is a match failure, not a silent discard.
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n(j("'foo'")), "$.x", n(j("'r'")), OM));
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n("[1,2]"), "$.x", n(j("'r'")), OM));
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n("5"), "$.x", n(j("'r'")), OM));
    }

    // Note: the $[...] and scalar-intermediate residuals are intentionally NOT asserted here (per the
    // scope decision they are documented in ResultPathMerge, not encoded as correct behavior).
}
