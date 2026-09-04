package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProjectionExpression evaluation over list indices. Paths sharing a
 * prefix must merge into one reconstructed structure, and projected list indices must
 * compact to ascending source order, matching real DynamoDB. Mirrors the paritysuite
 * dynamodb-conformance tier1 projection cases.
 */
class ProjectionEvaluatorTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static ObjectNode listItem() {
        return readValue("""
                {
                  "pk": {"S": "p"},
                  "l": {"L": [
                    {"M": {
                      "a": {"S": "a0"},
                      "b": {"S": "b0"},
                      "m": {"M": {"x": {"S": "x0"}, "y": {"S": "y0"}}},
                      "n": {"L": [
                        {"M": {"p": {"S": "p00"}, "q": {"S": "q00"}}},
                        {"M": {"p": {"S": "p01"}, "q": {"S": "q01"}}}
                      ]}
                    }},
                    {"M": {"a": {"S": "a1"}, "b": {"S": "b1"}}},
                    {"M": {"a": {"S": "a2"}, "b": {"S": "b2"}, "c": {"S": "c2"}}}
                  ]}
                }
                """);
    }

    @Test
    void projectsMultipleListIndicesCompactedAndIndexOrdered() {
        var item = mapper.createObjectNode();
        item.set("mylist", readValue("""
                {"L": [{"S": "zero"}, {"S": "one"}, {"S": "two"}]}
                """));

        var result = ProjectionEvaluator.project(item, "#l[2], #l[0]",
                readValue("""
                        {"#l": "mylist"}
                        """));

        var list = result.get("mylist").get("L");
        assertEquals(2, list.size(), "both requested elements must survive");
        assertEquals("zero", list.get(0).get("S").asText());
        assertEquals("two", list.get(1).get("S").asText());
    }

    @Test
    void mergesTwoScalarPathsUnderOneListIndex() {
        var result = ProjectionEvaluator.project(listItem(), "l[0].a, l[0].b", null);

        var list = result.get("l").get("L");
        assertEquals(1, list.size());
        var element = list.get(0).get("M");
        assertEquals("a0", element.get("a").get("S").asText());
        assertEquals("b0", element.get("b").get("S").asText());
        assertNull(element.get("m"), "unprojected sibling must be dropped");
    }

    @Test
    void mergesScalarSiblingAndNestedMapSiblingUnderOneListIndex() {
        var result = ProjectionEvaluator.project(listItem(), "l[0].a, l[0].m.x", null);

        var element = result.get("l").get("L").get(0).get("M");
        assertEquals("a0", element.get("a").get("S").asText());
        assertEquals("x0", element.get("m").get("M").get("x").get("S").asText());
        assertNull(element.get("m").get("M").get("y"), "only x was projected inside m");
        assertNull(element.get("b"), "unprojected sibling must be dropped");
    }

    @Test
    void mergesTwoPathsSharingAnElementOfANestedList() {
        var result = ProjectionEvaluator.project(listItem(), "l[0].n[0].p, l[0].n[0].q", null);

        var outer = result.get("l").get("L");
        assertEquals(1, outer.size());
        var inner = outer.get(0).get("M").get("n").get("L");
        assertEquals(1, inner.size(), "both paths share inner index 0");
        assertEquals("p00", inner.get(0).get("M").get("p").get("S").asText());
        assertEquals("q00", inner.get(0).get("M").get("q").get("S").asText());
    }

    @Test
    void keepsDistinctInnerListIndicesSeparateUnderOneSharedOuterIndex() {
        var result = ProjectionEvaluator.project(listItem(), "l[0].n[0].p, l[0].n[1].q", null);

        var inner = result.get("l").get("L").get(0).get("M").get("n").get("L");
        assertEquals(2, inner.size());
        assertEquals("p00", inner.get(0).get("M").get("p").get("S").asText());
        assertNull(inner.get(0).get("M").get("q"));
        assertEquals("q01", inner.get(1).get("M").get("q").get("S").asText());
        assertNull(inner.get(1).get("M").get("p"));
    }

    @Test
    void mergesSharedIndexAndKeepsDistinctIndexSeparateCompacted() {
        var result = ProjectionEvaluator.project(listItem(), "l[0].a, l[0].b, l[2].c", null);

        var list = result.get("l").get("L");
        assertEquals(2, list.size(), "index 0 (merged) and index 2 compact to two elements");
        assertEquals("a0", list.get(0).get("M").get("a").get("S").asText());
        assertEquals("b0", list.get(0).get("M").get("b").get("S").asText());
        assertEquals("c2", list.get(1).get("M").get("c").get("S").asText());
        assertNull(list.get(1).get("M").get("a"));
    }

    private static ObjectNode readValue(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
