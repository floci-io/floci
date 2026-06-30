package io.github.hectorvent.floci.services.appsync.graphql.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class DynamoDbUtil {

    private final ObjectMapper objectMapper;

    public DynamoDbUtil(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> toDynamoDB(Object value) {
        if (value == null) {
            return Collections.singletonMap("NULL", true);
        }
        if (value instanceof String s) {
            return Collections.singletonMap("S", s);
        }
        if (value instanceof Boolean b) {
            return Collections.singletonMap("BOOL", b);
        }
        if (value instanceof Number n) {
            return Collections.singletonMap("N", n.toString());
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) {
                converted.add(toDynamoDB(item));
            }
            return Collections.singletonMap("L", converted);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(entry.getKey().toString(), toDynamoDB(entry.getValue()));
            }
            return Collections.singletonMap("M", converted);
        }
        return Collections.singletonMap("S", value.toString());
    }

    public String toDynamoDBJson(Object value) {
        return toJson(toDynamoDB(value));
    }

    public Map<String, Object> toString(String value) {
        return Collections.singletonMap("S", value);
    }

    public String toStringJson(String value) {
        return toJson(toString(value));
    }

    public Map<String, Object> toNumber(Number value) {
        return Collections.singletonMap("N", value.toString());
    }

    public String toNumberJson(Number value) {
        return toJson(toNumber(value));
    }

    public Map<String, Object> toBinary(String value) {
        return Collections.singletonMap("B", value);
    }

    public String toBinaryJson(String value) {
        return toJson(toBinary(value));
    }

    public Map<String, Object> toBoolean(Boolean value) {
        return Collections.singletonMap("BOOL", value);
    }

    public String toBooleanJson(Boolean value) {
        return toJson(toBoolean(value));
    }

    public Map<String, Object> toNull() {
        return Collections.singletonMap("NULL", true);
    }

    public String toNullJson() {
        return toJson(toNull());
    }

    public Map<String, Object> toList(List<?> value) {
        List<Object> converted = new ArrayList<>(value.size());
        for (Object item : value) {
            converted.add(toDynamoDB(item));
        }
        return Collections.singletonMap("L", converted);
    }

    public String toListJson(List<?> value) {
        return toJson(toList(value));
    }

    public Map<String, Object> toMap(Map<String, Object> value) {
        Map<String, Object> converted = new LinkedHashMap<>(value.size());
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            converted.put(entry.getKey(), toDynamoDB(entry.getValue()));
        }
        return Collections.singletonMap("M", converted);
    }

    public String toMapJson(Map<String, Object> value) {
        return toJson(toMap(value));
    }

    public Map<String, Object> toMapValues(Map<String, Object> value) {
        Map<String, Object> converted = new LinkedHashMap<>(value.size());
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            converted.put(entry.getKey(), toDynamoDB(entry.getValue()));
        }
        return converted;
    }

    public String toMapValuesJson(Map<String, Object> value) {
        return toJson(toMapValues(value));
    }

    public Map<String, Object> toStringSet(List<String> value) {
        return Collections.singletonMap("SS", new ArrayList<>(value));
    }

    public String toStringSetJson(List<String> value) {
        return toJson(toStringSet(value));
    }

    public Map<String, Object> toNumberSet(List<? extends Number> value) {
        List<String> strings = new ArrayList<>(value.size());
        for (Number n : value) {
            strings.add(n.toString());
        }
        return Collections.singletonMap("NS", strings);
    }

    public String toNumberSetJson(List<? extends Number> value) {
        return toJson(toNumberSet(value));
    }

    public Map<String, Object> toBinarySet(List<String> value) {
        return Collections.singletonMap("BS", new ArrayList<>(value));
    }

    public String toBinarySetJson(List<String> value) {
        return toJson(toBinarySet(value));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
