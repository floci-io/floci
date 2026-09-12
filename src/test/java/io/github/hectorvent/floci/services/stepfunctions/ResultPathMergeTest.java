package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ResultPathMerge}: the extracted ResultPath merge, including the AWS
 * {@code States.ResultPathMatchFailure} case (previously a silent input-discard), bracket-index
 * ResultPaths (previously an unimplemented residual), and the remaining documented residual.
 * Plain JUnit5 + Jackson, so it runs in the offline sandbox.
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

    @Test
    void arrayIndexResultPathMergesIntoIndexedElement() {
        JsonNode input = n(j("[{'a':1},{'id':7},{'c':3},4]"));
        JsonNode out = ResultPathMerge.merge(input, "$[1].payload", n(j("{'subject_status':1}")), OM);
        assertEquals(n(j("[{'a':1},{'id':7,'payload':{'subject_status':1}},{'c':3},4]")), out);
    }

    @Test
    void bracketIndexMidPathMergesIntoNestedElement() {
        JsonNode input = n(j("{'a':[{'other':1}]}"));
        JsonNode out = ResultPathMerge.merge(input, "$.a[0].b", n("5"), OM);
        assertEquals(5, out.get("a").get(0).get("b").asInt());
        assertEquals(1, out.get("a").get(0).get("other").asInt());
    }

    @Test
    void arrayIndexOutOfBoundsRaisesResultPathMatchFailure() {
        JsonNode input = n(j("[{'a':1},{'id':7},{'c':3},4]"));
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(input, "$[10].payload", n(j("{'x':1}")), OM));
    }

    @Test
    void arrayIndexResultPathAgainstNonArrayInputRaisesResultPathMatchFailure() {
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n(j("{'a':1}")), "$[0].x", n(j("{'x':1}")), OM));
    }

    @Test
    void quotedBracketFieldNameMergesLikeADottedField() {
        JsonNode out = ResultPathMerge.merge(n(j("{'abc':{}}")), "$.abc.['def ghi']", n("5"), OM);
        assertEquals(5, out.get("abc").get("def ghi").asInt());
    }

    @Test
    void quotedBracketFieldNameAsRootSegmentRequiresObjectInput() {
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n("[1,2]"), "$.['def ghi']", n("5"), OM));
    }

    @Test
    void unterminatedQuotedBracketSegmentRaisesResultPathMatchFailure() {
        assertThrows(ResultPathMerge.ResultPathMatchException.class,
                () -> ResultPathMerge.merge(n(j("{'a':1}")), "$.['unterminated", n("5"), OM));
    }

    // Note: a $.a.b path whose $.a is a scalar (the pre-existing, documented residual) is
    // intentionally NOT asserted here; it is unchanged by this fix and remains a silent overwrite.
}
