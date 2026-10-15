package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads request members with the type checks of the AWS rest-json deserializer. A member of the
 * wrong JSON type is a {@code SerializationException}, a JSON null counts as absent, and a number
 * outside the int range saturates.
 */
final class LambdaWebJson {

    private static final String START_OF_LIST = "Start of list found where not expected";
    private static final String START_OF_STRUCTURE = "Start of structure or map found where not expected.";

    private LambdaWebJson() {
    }

    static JsonNode parseBody(ObjectMapper mapper, String body) {
        JsonNode document;
        try {
            document = mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
        if (document.isArray()) {
            throw serialization(START_OF_LIST);
        }
        if (!document.isObject()) {
            throw serialization("Expected null");
        }
        return document;
    }

    static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    static String text(JsonNode parent, String field) {
        JsonNode value = member(parent, field);
        return value == null ? null : textValue(value);
    }

    static Integer integer(JsonNode parent, String field) {
        JsonNode value = member(parent, field);
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber()) {
            if (value.canConvertToInt()) {
                return value.intValue();
            }
            return value.bigIntegerValue().signum() > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        if (value.isFloatingPointNumber()) {
            return (int) value.doubleValue();
        }
        throw serialization(scalarToken(value) + " can not be converted to an Integer");
    }

    /** A structure member, or null when absent. */
    static JsonNode structure(JsonNode parent, String field) {
        JsonNode value = member(parent, field);
        if (value == null || value.isObject()) {
            return value;
        }
        if (value.isArray()) {
            throw serialization(START_OF_LIST);
        }
        throw serialization("Expected null");
    }

    /** A list member, or null when absent. */
    static JsonNode list(JsonNode parent, String field) {
        JsonNode value = member(parent, field);
        if (value == null || value.isArray()) {
            return value;
        }
        if (value.isObject()) {
            throw serialization(START_OF_STRUCTURE);
        }
        throw serialization("Expected null");
    }

    static List<String> strings(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (JsonNode item : list) {
            if (present(item)) {
                out.add(textValue(item));
            }
        }
        return out;
    }

    /**
     * A map of strings, or null when absent. A null value is kept as null when the caller rejects it
     * itself and dropped otherwise, which is what the deserializer does with an absent member.
     */
    static Map<String, String> stringMap(JsonNode parent, String field, boolean keepNulls) {
        JsonNode value = member(parent, field);
        if (value == null) {
            return null;
        }
        if (value.isArray()) {
            throw serialization(START_OF_LIST);
        }
        if (!value.isObject()) {
            throw serialization("Expected map or null");
        }
        Map<String, String> out = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (present(entry.getValue())) {
                out.put(entry.getKey(), textValue(entry.getValue()));
            } else if (keepNulls) {
                out.put(entry.getKey(), null);
            }
        });
        return out;
    }

    private static JsonNode member(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return present(value) ? value : null;
    }

    private static String textValue(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isArray()) {
            throw serialization(START_OF_LIST);
        }
        if (value.isObject()) {
            throw serialization(START_OF_STRUCTURE);
        }
        throw serialization(scalarToken(value) + " can not be converted to a String");
    }

    private static String scalarToken(JsonNode value) {
        if (value.isArray()) {
            throw serialization(START_OF_LIST);
        }
        if (value.isObject()) {
            throw serialization(START_OF_STRUCTURE);
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE";
        }
        return value.isNumber() ? "NUMBER_VALUE" : "STRING_VALUE";
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }
}
