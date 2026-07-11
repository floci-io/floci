package io.github.hectorvent.floci.services.appsync.graphql.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransformUtil {

    private final ObjectMapper objectMapper;

    public TransformUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> toDynamoDBFilterExpression(Object input) {
        Map<String, Object> source = parseInput(input);
        if (source.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new HashMap<>();
        if (source.containsKey("expression")) {
            result.put("FilterExpression", source.get("expression"));
        }
        if (source.containsKey("expressionNames")) {
            result.put("ExpressionAttributeNames", source.get("expressionNames"));
        }
        if (source.containsKey("expressionValues")) {
            result.put("ExpressionAttributeValues", source.get("expressionValues"));
        }
        return result;
    }

    public Map<String, Object> toElasticsearchQueryDSL(Object input) {
        return parseInput(input);
    }

    public Map<String, Object> toSubscriptionFilter(Object input) {
        return parseInput(input);
    }

    public Map<String, Object> toSubscriptionFilter(Object input, List<String> filterKeys) {
        Map<String, Object> result = new HashMap<>(toSubscriptionFilter(input));
        if (filterKeys != null && !filterKeys.isEmpty()) {
            result.put("filterKeys", filterKeys);
        }
        return result;
    }

    public Map<String, Object> toSubscriptionFilter(Object input, List<String> filterKeys,
                                                     Map<String, String> headerOverrides) {
        Map<String, Object> result = new HashMap<>(toSubscriptionFilter(input, filterKeys));
        if (headerOverrides != null && !headerOverrides.isEmpty()) {
            result.put("headerOverrides", headerOverrides);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(Object input) {
        if (input == null) {
            return Collections.emptyMap();
        }
        if (input instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (input instanceof String s) {
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }
}
