package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by the three Lambda event-source pollers to apply an event source mapping's
 * {@link EventSourceMapping.FilterCriteria} through the existing {@code PipesFilterMatcher}.
 */
final class EsmFilterCriteriaUtils {

    private EsmFilterCriteriaUtils() {
    }

    /**
     * Re-wraps a stored {@link EventSourceMapping.FilterCriteria} as the
     * {@code {"FilterCriteria":{"Filters":[{"Pattern":...}]}}} node that
     * {@code PipesFilterMatcher.applyFilterCriteria} path-matches on. Returns {@code null} when no filters
     * are configured; the matcher passes every record through for a {@code null} argument, so callers treat
     * {@code null} as "filtering off" and skip building filter nodes entirely.
     */
    static JsonNode matcherSourceParameters(ObjectMapper mapper, EventSourceMapping.FilterCriteria criteria) {
        if (criteria == null || criteria.getFilters() == null || criteria.getFilters().isEmpty()) {
            return null;
        }
        ObjectNode wrapper = mapper.createObjectNode();
        ArrayNode filters = wrapper.putObject("FilterCriteria").putArray("Filters");
        for (EventSourceMapping.Filter filter : criteria.getFilters()) {
            filters.addObject().put("Pattern", filter.getPattern());
        }
        return wrapper;
    }

    /**
     * Maps the matcher's result back to the source records it corresponds to. {@code PipesFilterMatcher}
     * preserves input order and returns the same {@link JsonNode} instances it was given, so a
     * reference-identity two-pointer walk recovers the matched source records without relying on any content
     * field, robust to duplicate payloads and to filter nodes that deliberately omit a correlating key.
     *
     * @param sources      the source records, in the same order their filter nodes were built
     * @param filterNodes  the per-record filter nodes passed to the matcher, index-aligned with {@code sources}
     * @param matchedNodes the matcher's returned subset (same instances, input order)
     */
    static <T> List<T> selectMatched(List<T> sources, List<JsonNode> filterNodes, List<JsonNode> matchedNodes) {
        List<T> matched = new ArrayList<>(matchedNodes.size());
        int j = 0;
        for (int i = 0; i < filterNodes.size() && j < matchedNodes.size(); i++) {
            if (filterNodes.get(i) == matchedNodes.get(j)) {
                matched.add(sources.get(i));
                j++;
            }
        }
        return matched;
    }
}
