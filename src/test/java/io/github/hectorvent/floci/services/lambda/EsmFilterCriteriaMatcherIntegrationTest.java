package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that a stored {@link EventSourceMapping.FilterCriteria}, wrapped by
 * {@link EsmFilterCriteriaUtils#matcherSourceParameters}, actually drives the REAL
 * {@link PipesFilterMatcher}, not just that the wrapper has the right field names. Without this, the
 * wrapper could be shaped wrong (silently disabling filtering) and unit tests inspecting only its fields
 * would stay green. Uses supported EventBridge operators the matcher implements; known matcher deviations
 * (boolean literals, event-array intersection, numeric anything-but) are documented, not covered here, per
 * the reuse-the-matcher scope decision.
 */
class EsmFilterCriteriaMatcherIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PipesFilterMatcher MATCHER = new PipesFilterMatcher(MAPPER);

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

    /** An SQS-style record: body is a JSON string the matcher re-parses when a pattern nests an object under it. */
    private static ObjectNode sqsRecord(String messageId, String bodyJson) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("messageId", messageId);
        n.put("body", bodyJson);
        return n;
    }

    private List<String> matchedIds(EventSourceMapping.FilterCriteria fc, List<ObjectNode> records) {
        JsonNode wrapper = EsmFilterCriteriaUtils.matcherSourceParameters(MAPPER, fc);
        List<JsonNode> filterNodes = new ArrayList<>(records);
        List<JsonNode> matched = MATCHER.applyFilterCriteria(filterNodes, wrapper);
        // Recover source records via the shared correlation helper, then read their ids.
        List<ObjectNode> matchedRecords = EsmFilterCriteriaUtils.selectMatched(records, filterNodes, matched);
        List<String> ids = new ArrayList<>();
        for (ObjectNode r : matchedRecords) {
            ids.add(r.path("messageId").asText());
        }
        return ids;
    }

    @Test
    void storedFilterCriteriaFiltersThroughRealMatcher() {
        List<ObjectNode> records = List.of(
                sqsRecord("m1", "{\"type\":\"order\"}"),
                sqsRecord("m2", "{\"type\":\"refund\"}"),
                sqsRecord("m3", "{\"type\":\"order\"}"));
        // Wrap the stored criteria and run it through the real matcher.
        assertEquals(List.of("m1", "m3"),
                matchedIds(criteria("{\"body\":{\"type\":[\"order\"]}}"), records));
    }

    @Test
    void multipleFiltersAreOred() {
        List<ObjectNode> records = List.of(
                sqsRecord("m1", "{\"type\":\"order\"}"),
                sqsRecord("m2", "{\"type\":\"refund\"}"),
                sqsRecord("m3", "{\"type\":\"chargeback\"}"));
        assertEquals(List.of("m1", "m2"),
                matchedIds(criteria("{\"body\":{\"type\":[\"order\"]}}", "{\"body\":{\"type\":[\"refund\"]}}"), records));
    }

    @Test
    void topLevelMetadataPatternMatches() {
        List<ObjectNode> records = List.of(
                sqsRecord("m1", "{\"ignored\":true}"),
                sqsRecord("m2", "{\"ignored\":true}"));
        // pattern on a top-level record field (messageId), not the body
        assertEquals(List.of("m2"), matchedIds(criteria("{\"messageId\":[\"m2\"]}"), records));
    }

    @Test
    void noRecordMatchesYieldsEmpty() {
        List<ObjectNode> records = List.of(
                sqsRecord("m1", "{\"type\":\"order\"}"),
                sqsRecord("m2", "{\"type\":\"refund\"}"));
        assertTrue(matchedIds(criteria("{\"body\":{\"type\":[\"nonexistent\"]}}"), records).isEmpty());
    }

    @Test
    void nonJsonBodyIsDroppedByObjectPattern() {
        // A body that isn't JSON cannot satisfy an object pattern → dropped (AWS drops on format mismatch).
        List<ObjectNode> records = List.of(
                sqsRecord("m1", "not json at all"),
                sqsRecord("m2", "{\"type\":\"order\"}"));
        assertEquals(List.of("m2"), matchedIds(criteria("{\"body\":{\"type\":[\"order\"]}}"), records));
    }
}
