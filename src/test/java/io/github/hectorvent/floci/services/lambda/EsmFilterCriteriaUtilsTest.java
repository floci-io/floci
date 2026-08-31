package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsmFilterCriteriaUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EventSourceMapping.FilterCriteria criteria(String... patterns) {
        EventSourceMapping.FilterCriteria fc = new EventSourceMapping.FilterCriteria();
        List<EventSourceMapping.Filter> filters = new ArrayList<>();
        for (String p : patterns) {
            EventSourceMapping.Filter f = new EventSourceMapping.Filter();
            f.setPattern(p);
            filters.add(f);
        }
        fc.setFilters(filters);
        return fc;
    }

    @Test
    void nullOrEmptyCriteriaYieldsNull() {
        assertNull(EsmFilterCriteriaUtils.matcherSourceParameters(MAPPER, null));
        assertNull(EsmFilterCriteriaUtils.matcherSourceParameters(MAPPER, new EventSourceMapping.FilterCriteria()));
        EventSourceMapping.FilterCriteria emptyList = new EventSourceMapping.FilterCriteria();
        emptyList.setFilters(new ArrayList<>());
        assertNull(EsmFilterCriteriaUtils.matcherSourceParameters(MAPPER, emptyList));
    }

    @Test
    void wrapsFiltersInExactPascalCaseShape() {
        JsonNode node = EsmFilterCriteriaUtils.matcherSourceParameters(
                MAPPER, criteria("{\"data\":{\"type\":[\"buy\"]}}", "{\"partitionKey\":[\"pk-1\"]}"));
        assertNotNull(node);
        // The matcher path-matches on FilterCriteria.Filters[].Pattern (PascalCase). A drifted key
        // would silently pass every record through, so pin the exact shape here.
        assertTrue(node.has("FilterCriteria"));
        JsonNode filters = node.path("FilterCriteria").path("Filters");
        assertTrue(filters.isArray());
        assertEquals(2, filters.size());
        assertTrue(filters.get(0).has("Pattern"));
        assertEquals("{\"data\":{\"type\":[\"buy\"]}}", filters.get(0).path("Pattern").asText());
        assertEquals("{\"partitionKey\":[\"pk-1\"]}", filters.get(1).path("Pattern").asText());
    }

    @Test
    void selectMatchedRecoversSourcesByOrder() {
        List<String> sources = List.of("a", "b", "c", "d");
        ObjectNode n0 = MAPPER.createObjectNode();
        ObjectNode n1 = MAPPER.createObjectNode();
        ObjectNode n2 = MAPPER.createObjectNode();
        ObjectNode n3 = MAPPER.createObjectNode();
        List<JsonNode> filterNodes = List.of(n0, n1, n2, n3);
        List<JsonNode> matched = List.of(n1, n3); // matcher returns a subset, same instances, input order
        assertEquals(List.of("b", "d"), EsmFilterCriteriaUtils.selectMatched(sources, filterNodes, matched));
    }

    @Test
    void selectMatchedUsesIdentityNotEqualityForDuplicatePayloads() {
        List<String> sources = List.of("first", "second");
        ObjectNode a = MAPPER.createObjectNode();
        a.put("body", "same");
        ObjectNode b = MAPPER.createObjectNode();
        b.put("body", "same");
        assertEquals(a, b);      // equal by value...
        assertNotSame(a, b);     // ...but distinct instances
        List<JsonNode> filterNodes = List.of(a, b);
        // Only the second instance matched; identity (not value-equality) must pick "second".
        assertEquals(List.of("second"), EsmFilterCriteriaUtils.selectMatched(sources, filterNodes, List.of(b)));
    }

    @Test
    void selectMatchedEmptyWhenNothingMatched() {
        List<String> sources = List.of("a", "b");
        List<JsonNode> filterNodes = List.of(MAPPER.createObjectNode(), MAPPER.createObjectNode());
        assertTrue(EsmFilterCriteriaUtils.selectMatched(sources, filterNodes, List.of()).isEmpty());
    }
}
