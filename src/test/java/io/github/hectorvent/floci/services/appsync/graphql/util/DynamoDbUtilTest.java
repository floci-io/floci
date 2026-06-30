package io.github.hectorvent.floci.services.appsync.graphql.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbUtilTest {

    private final DynamoDbUtil dynamoDb = new DynamoDbUtil(new ObjectMapper());

    @Test
    void toDynamoDB_string() {
        Map<String, Object> result = dynamoDb.toDynamoDB("hello");
        assertThat(result, hasEntry("S", "hello"));
    }

    @Test
    void toDynamoDB_number() {
        Map<String, Object> result = dynamoDb.toDynamoDB(42);
        assertThat(result, hasEntry("N", "42"));
    }

    @Test
    void toDynamoDB_long() {
        Map<String, Object> result = dynamoDb.toDynamoDB(123456789L);
        assertThat(result, hasEntry("N", "123456789"));
    }

    @Test
    void toDynamoDB_double() {
        Map<String, Object> result = dynamoDb.toDynamoDB(3.14);
        assertThat(result, hasEntry("N", "3.14"));
    }

    @Test
    void toDynamoDB_boolean_true() {
        Map<String, Object> result = dynamoDb.toDynamoDB(true);
        assertThat(result, hasEntry("BOOL", true));
    }

    @Test
    void toDynamoDB_boolean_false() {
        Map<String, Object> result = dynamoDb.toDynamoDB(false);
        assertThat(result, hasEntry("BOOL", false));
    }

    @Test
    void toDynamoDB_null() {
        Map<String, Object> result = dynamoDb.toDynamoDB(null);
        assertThat(result, hasEntry("NULL", true));
    }

    @Test
    void toDynamoDB_list() {
        Map<String, Object> result = dynamoDb.toDynamoDB(List.of("a", "b"));
        assertThat(result, hasKey("L"));
        List<?> l = (List<?>) result.get("L");
        assertThat(l, hasSize(2));
        assertThat((Map<?, ?>) l.get(0), hasEntry("S", "a"));
        assertThat((Map<?, ?>) l.get(1), hasEntry("S", "b"));
    }

    @Test
    void toDynamoDB_map() {
        Map<String, Object> result = dynamoDb.toDynamoDB(Map.of("key", "value"));
        assertThat(result, hasKey("M"));
        Map<?, ?> m = (Map<?, ?>) result.get("M");
        assertThat((Map<?, ?>) m.get("key"), hasEntry("S", "value"));
    }

    @Test
    void toDynamoDB_nestedMap() {
        Map<String, Object> inner = Map.of("b", "c");
        Map<String, Object> outer = Map.of("a", inner);
        Map<String, Object> result = dynamoDb.toDynamoDB(outer);
        assertThat(result, hasKey("M"));
        Map<?, ?> m = (Map<?, ?>) result.get("M");
        assertThat(m, hasKey("a"));
        Map<?, ?> innerResult = (Map<?, ?>) ((Map<?, ?>) m.get("a")).get("M");
        Map<?, ?> bResult = (Map<?, ?>) innerResult.get("b");
        assertThat((String) bResult.get("S"), is("c"));
    }

    @Test
    void toDynamoDB_listOfMaps() {
        List<Map<String, Object>> list = List.of(Map.of("k", "v"));
        Map<String, Object> result = dynamoDb.toDynamoDB(list);
        assertThat(result, hasKey("L"));
        List<?> l = (List<?>) result.get("L");
        assertThat(l, hasSize(1));
        assertThat((Map<?, ?>) ((Map<?, ?>) l.get(0)).get("M"), hasKey("k"));
    }

    @Test
    void toDynamoDB_unknownType() {
        Map<String, Object> result = dynamoDb.toDynamoDB(new Object());
        assertThat((String) result.get("S"), startsWith("java.lang.Object"));
    }

    @Test
    void toString_basic() {
        Map<String, Object> result = dynamoDb.toString("hello");
        assertThat(result, hasEntry("S", "hello"));
    }

    @Test
    void toString_empty() {
        Map<String, Object> result = dynamoDb.toString("");
        assertThat(result, hasEntry("S", ""));
    }

    @Test
    void toString_null() {
        Map<String, Object> result = dynamoDb.toString(null);
        assertThat(result, hasKey("S"));
        assertThat(result.get("S"), is(nullValue()));
    }

    @Test
    void toNumber_int() {
        Map<String, Object> result = dynamoDb.toNumber(42);
        assertThat(result, hasEntry("N", "42"));
    }

    @Test
    void toNumber_long() {
        Map<String, Object> result = dynamoDb.toNumber(123456789L);
        assertThat(result, hasEntry("N", "123456789"));
    }

    @Test
    void toNumber_double() {
        Map<String, Object> result = dynamoDb.toNumber(3.14);
        assertThat(result, hasEntry("N", "3.14"));
    }

    @Test
    void toBoolean_true() {
        Map<String, Object> result = dynamoDb.toBoolean(true);
        assertThat(result, hasEntry("BOOL", true));
    }

    @Test
    void toBoolean_false() {
        Map<String, Object> result = dynamoDb.toBoolean(false);
        assertThat(result, hasEntry("BOOL", false));
    }

    @Test
    void toNull() {
        Map<String, Object> result = dynamoDb.toNull();
        assertThat(result, hasEntry("NULL", true));
    }

    @Test
    void toList_strings() {
        Map<String, Object> result = dynamoDb.toList(List.of("a", "b"));
        assertThat(result, hasKey("L"));
        List<?> l = (List<?>) result.get("L");
        assertThat(l, hasSize(2));
    }

    @Test
    void toList_numbers() {
        Map<String, Object> result = dynamoDb.toList(List.of(1, 2, 3));
        assertThat(result, hasKey("L"));
        List<?> l = (List<?>) result.get("L");
        assertThat(l, hasSize(3));
    }

    @Test
    void toList_empty() {
        Map<String, Object> result = dynamoDb.toList(List.of());
        assertThat(result, hasKey("L"));
        List<?> l = (List<?>) result.get("L");
        assertThat(l, empty());
    }

    @Test
    void toMap_flat() {
        Map<String, Object> result = dynamoDb.toMap(Map.of("k", "v"));
        assertThat(result, hasKey("M"));
        Map<?, ?> m = (Map<?, ?>) result.get("M");
        assertThat((String) ((Map<?, ?>) m.get("k")).get("S"), is("v"));
    }

    @Test
    void toMap_empty() {
        Map<String, Object> result = dynamoDb.toMap(Map.of());
        assertThat(result, hasKey("M"));
        Map<?, ?> m = (Map<?, ?>) result.get("M");
        assertThat(m, anEmptyMap());
    }

    @Test
    void toStringSet() {
        Map<String, Object> result = dynamoDb.toStringSet(List.of("a", "b"));
        assertThat(result, hasEntry("SS", List.of("a", "b")));
    }

    @Test
    void toStringSet_empty() {
        Map<String, Object> result = dynamoDb.toStringSet(List.of());
        assertThat(result, hasEntry("SS", List.of()));
    }

    @Test
    void toNumberSet() {
        Map<String, Object> result = dynamoDb.toNumberSet(List.of(1, 2, 3));
        assertThat(result, hasKey("NS"));
        List<?> ns = (List<?>) result.get("NS");
        assertThat(ns, hasSize(3));
        assertThat(ns, contains("1", "2", "3"));
    }

    @Test
    void toNumberSet_empty() {
        Map<String, Object> result = dynamoDb.toNumberSet(List.of());
        assertThat(result, hasEntry("NS", List.of()));
    }

    @Test
    void toBinary() {
        Map<String, Object> result = dynamoDb.toBinary("hello");
        assertThat(result, hasKey("B"));
    }

    @Test
    void toBinary_empty() {
        Map<String, Object> result = dynamoDb.toBinary("");
        assertThat(result, hasKey("B"));
    }

    @Test
    void toBinarySet() {
        Map<String, Object> result = dynamoDb.toBinarySet(List.of("a", "b"));
        assertThat(result, hasKey("BS"));
        List<?> bs = (List<?>) result.get("BS");
        assertThat(bs, hasSize(2));
    }

    @Test
    void toBinarySet_empty() {
        Map<String, Object> result = dynamoDb.toBinarySet(List.of());
        assertThat(result, hasEntry("BS", List.of()));
    }

    @Test
    void toDynamoDBJson_valid() {
        String json = dynamoDb.toDynamoDBJson("hello");
        assertThat(json, containsString("\"S\""));
        assertThat(json, containsString("hello"));
    }

    @Test
    void toDynamoDBJson_map() {
        String json = dynamoDb.toDynamoDBJson(Map.of("key", "value"));
        assertThat(json, containsString("\"M\""));
        assertThat(json, containsString("key"));
    }

    @Test
    void toStringJson() {
        String result = dynamoDb.toStringJson("hello");
        assertThat(result, containsString("\"S\""));
        assertThat(result, containsString("hello"));
    }

    @Test
    void toNumberJson() {
        String result = dynamoDb.toNumberJson(42);
        assertThat(result, containsString("\"N\""));
        assertThat(result, containsString("42"));
    }

    @Test
    void toBooleanJson() {
        String result = dynamoDb.toBooleanJson(true);
        assertThat(result, containsString("\"BOOL\""));
        assertThat(result, containsString("true"));
    }

    @Test
    void toNullJson() {
        String result = dynamoDb.toNullJson();
        assertThat(result, containsString("\"NULL\""));
        assertThat(result, containsString("true"));
    }

    @Test
    void toListJson() {
        String result = dynamoDb.toListJson(List.of("a"));
        assertThat(result, containsString("\"L\""));
    }

    @Test
    void toMapJson() {
        String result = dynamoDb.toMapJson(Map.of("k", "v"));
        assertThat(result, containsString("\"M\""));
        assertThat(result, containsString("k"));
    }

    @Test
    void toMapValues() {
        Map<String, Object> result = dynamoDb.toMapValues(Map.of("k", "v"));
        assertThat(result, hasKey("k"));
        assertThat((Map<?, ?>) result.get("k"), hasEntry("S", "v"));
    }

    @Test
    void toMapValuesJson() {
        String result = dynamoDb.toMapValuesJson(Map.of("k", "v"));
        assertThat(result, containsString("k"));
        assertThat(result, containsString("\"S\""));
    }

    @Test
    void toStringSetJson() {
        String result = dynamoDb.toStringSetJson(List.of("a"));
        assertThat(result, containsString("\"SS\""));
    }

    @Test
    void toNumberSetJson() {
        String result = dynamoDb.toNumberSetJson(List.of(1));
        assertThat(result, containsString("\"NS\""));
    }

    @Test
    void toBinarySetJson() {
        String result = dynamoDb.toBinarySetJson(List.of("a"));
        assertThat(result, containsString("\"BS\""));
    }
}
