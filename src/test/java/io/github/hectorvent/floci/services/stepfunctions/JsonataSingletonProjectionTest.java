package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonataSingletonProjectionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonataEvaluator evaluator = new JsonataEvaluator(mapper);

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void explicitlyWrappedObjectProjectionCanBeIndexed(int count) throws Exception {
        JsonNode states = mapper.readTree("{\"input\":{\"Items\":[" + "{},".repeat(count)
                .replaceFirst(",$", "") + "]}}");
        JsonNode array = evaluator.evaluate("{% [$states.input.Items.{'ok':true}] %}", states);
        assertTrue(array.isArray());
        assertEquals(count, array.size());

        JsonNode first = evaluator.evaluate("{% [$states.input.Items.{'ok':true}][0].ok %}", states);
        JsonNode last = evaluator.evaluate("{% [$states.input.Items.{'ok':true}][-1].ok %}", states);
        if (count == 0) {
            assertTrue(first.isMissingNode());
            assertTrue(last.isMissingNode());
        } else {
            assertEquals(mapper.readTree("true"), first);
            assertEquals(first, last);
        }
    }

    @Test
    void standaloneIndexingStillReturnsAScalarAndNestedArraysKeepTheirShape() throws Exception {
        JsonNode states = mapper.readTree("{\"input\":{\"Items\":[{}]}}");
        assertEquals(mapper.readTree("{\"ok\":true}"),
                evaluator.evaluate("{% [$states.input.Items.{'ok':true}][0] %}", states));
        assertEquals(mapper.readTree("true"),
                evaluator.evaluate("{% ([$states.input.Items.{'ok':true}][0]).ok %}", states));
        assertEquals(mapper.readTree("[[{\"ok\":true}]]"),
                evaluator.evaluate("{% [[$states.input.Items.{'ok':true}]] %}", states));
        assertEquals(mapper.readTree("7"), evaluator.evaluate("{% [7,8][0] %}", states));
    }
}
