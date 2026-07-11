package io.github.hectorvent.floci.services.appsync.graphql.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class TransformUtilTest {

    private final TransformUtil transform = new TransformUtil(new ObjectMapper());

    @Test
    void toDynamoDBFilterExpression_null() {
        Map<String, Object> result = transform.toDynamoDBFilterExpression(null);
        assertThat(result, anEmptyMap());
    }

    @Test
    void toDynamoDBFilterExpression_simple() {
        Map<String, Object> input = new HashMap<>();
        input.put("expression", "#pk = :pk");
        input.put("expressionNames", Map.of("#pk", "id"));
        input.put("expressionValues", Map.of(":pk", Map.of("S", "123")));

        Map<String, Object> result = transform.toDynamoDBFilterExpression(input);
        assertThat(result, hasEntry("FilterExpression", "#pk = :pk"));
        assertThat(result, hasKey("ExpressionAttributeNames"));
        assertThat(result, hasKey("ExpressionAttributeValues"));
    }

    @Test
    void toDynamoDBFilterExpression_jsonString() {
        String json = "{\"expression\":\"#pk = :pk\"}";
        Map<String, Object> result = transform.toDynamoDBFilterExpression(json);
        assertThat(result, hasEntry("FilterExpression", "#pk = :pk"));
    }

    @Test
    void toDynamoDBFilterExpression_emptyMap() {
        Map<String, Object> result = transform.toDynamoDBFilterExpression(Map.of());
        assertThat(result, anEmptyMap());
    }

    @Test
    void toElasticsearchQueryDSL_null() {
        Map<String, Object> result = transform.toElasticsearchQueryDSL(null);
        assertThat(result, anEmptyMap());
    }

    @Test
    void toElasticsearchQueryDSL_map() {
        Map<String, Object> input = Map.of("query", Map.of("match_all", Map.of()));
        Map<String, Object> result = transform.toElasticsearchQueryDSL(input);
        assertThat(result, is(input));
    }

    @Test
    void toElasticsearchQueryDSL_jsonString() {
        String json = "{\"query\":{\"match_all\":{}}}";
        Map<String, Object> result = transform.toElasticsearchQueryDSL(json);
        assertThat(result, hasKey("query"));
    }

    @Test
    void toSubscriptionFilter_null() {
        Map<String, Object> result = transform.toSubscriptionFilter(null);
        assertThat(result, anEmptyMap());
    }

    @Test
    void toSubscriptionFilter_map() {
        Map<String, Object> input = Map.of("filter", Map.of("field", "value"));
        Map<String, Object> result = transform.toSubscriptionFilter(input);
        assertThat(result, is(input));
    }

    @Test
    void toSubscriptionFilter_jsonString() {
        String json = "{\"filter\":{\"field\":\"value\"}}";
        Map<String, Object> result = transform.toSubscriptionFilter(json);
        assertThat(result, hasKey("filter"));
    }

    @Test
    void toSubscriptionFilter_withFilterKeys() {
        Map<String, Object> input = Map.of("filter", Map.of("field", "value"));
        Map<String, Object> result = transform.toSubscriptionFilter(input, java.util.List.of("key1"));
        assertThat(result, hasKey("filterKeys"));
    }

    @Test
    void toSubscriptionFilter_withHeaderOverrides() {
        Map<String, Object> input = Map.of("filter", Map.of("field", "value"));
        Map<String, Object> result = transform.toSubscriptionFilter(
                input, java.util.List.of("key1"), Map.of("header", "value"));
        assertThat(result, hasKey("headerOverrides"));
    }
}
