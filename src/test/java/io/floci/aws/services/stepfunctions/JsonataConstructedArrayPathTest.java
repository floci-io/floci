package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonataConstructedArrayPathTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonataEvaluator evaluator = new JsonataEvaluator(mapper);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void indexedConstructedObjectPathHasNoValueAndExistsIsFalse(int count) throws Exception {
        JsonNode states = JsonataConstructedArrayFixtures.states(mapper, count);
        assertTrue(evaluator.evaluate("{% [$states.input.Items.{'ok':true}][0].ok %}", states).isMissingNode());
        assertFalse(evaluator.evaluate("{% $exists([$states.input.Items.{'ok':true}][0].ok) %}", states).asBoolean());
        assertEquals(mapper.readTree("{}"),
                evaluator.evaluate("{% {'value':[$states.input.Items.{'ok':true}][0].ok} %}", states));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void groupingTheConstructedArrayAllowsThePropertyRead(int count) throws Exception {
        JsonNode states = JsonataConstructedArrayFixtures.states(mapper, count);
        JsonNode grouped = evaluator.evaluate("{% ([$states.input.Items.{'ok':true}])[0].ok %}", states);
        if (count == 0) {
            assertTrue(grouped.isMissingNode());
        } else {
            assertTrue(grouped.asBoolean());
            assertEquals(mapper.readTree("{\"ok\":true}"),
                    evaluator.evaluate("{% [$states.input.Items.{'ok':true}][0] %}", states));
        }
        assertEquals(count, evaluator.evaluate("{% [$states.input.Items.{'ok':true}] %}", states).size());
    }

    @Test
    void forcedArraysAndMultipleResultsKeepTheirPathSemantics() throws Exception {
        JsonNode states = mapper.readTree("{\"input\":{\"Items\":[{\"a\":1},{\"a\":2}]}}");
        assertEquals("[1]", evaluator.evaluate("{% [$states.input.Items][0][].a %}", states).toString());
        assertEquals("[1,2]", evaluator.evaluate("{% [$states.input.Items][[0,1]].a %}", states).toString());
        assertEquals(mapper.readTree("[\"1\",\"2\"]"),
                evaluator.evaluate("{% [[1,2],[3]][0].$string() %}", states));
        assertEquals("[[{\"a\":1},{\"a\":2}]]",
                evaluator.evaluate("{% [[$states.input.Items]] %}", states).toString());
    }

    @Test
    void numericAndBooleanSingletonPathsAlsoHaveNoValue() throws Exception {
        JsonNode states = JsonataConstructedArrayFixtures.states(mapper, 1);
        assertTrue(evaluator.evaluate("{% [5][0].$string() %}", states).isMissingNode());
        assertFalse(evaluator.evaluate("{% $exists([true][0].x) %}", states).asBoolean());
    }

}
