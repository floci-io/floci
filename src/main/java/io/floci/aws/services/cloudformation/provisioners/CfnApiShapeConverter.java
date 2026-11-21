package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts resolved CloudFormation property names to their API request names. */
final class CfnApiShapeConverter {

    static final Rules OMIT_NULL_PROPERTIES = new Rules(Map.of(), Set.of(), Set.of(), true);

    private CfnApiShapeConverter() {
    }

    static JsonNode convert(JsonNode node, Rules rules) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> {
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (rules.omitNullProperties() && value.isNull()) {
                    return;
                }
                String apiName = rules.names().getOrDefault(name, lowerFirst(name));
                if (rules.integerTextFields().contains(name) && value.isTextual()) {
                    result.put(apiName, value.asInt());
                } else if (rules.opaqueFields().contains(name)) {
                    result.set(apiName, value.deepCopy());
                } else {
                    result.set(apiName, convert(value, rules));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(convert(item, rules)));
            return result;
        }
        return node;
    }

    /** Capacity provider state uses Java maps and omits empty scalar values as well as nulls. */
    static Object convertToJava(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                Object value = convertToJava(field.getValue());
                if (value != null) {
                    result.put(lowerFirst(field.getKey()), value);
                }
            });
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode item : node) {
                Object value = convertToJava(item);
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }

    static String lowerFirst(String name) {
        return name == null || name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    record Rules(Map<String, String> names, Set<String> opaqueFields, Set<String> integerTextFields,
                 boolean omitNullProperties) {
    }
}
