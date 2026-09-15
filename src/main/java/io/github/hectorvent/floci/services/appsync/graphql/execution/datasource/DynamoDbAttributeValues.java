package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts DynamoDB attribute-value JSON ({@code {"S":"x"}, {"N":"1"}, ...}) into the plain values
 * AppSync exposes as {@code $ctx.result}. Numbers become {@link Long} when integral and in range,
 * otherwise {@link BigDecimal}; sets become lists; binary stays base64 text.
 */
public final class DynamoDbAttributeValues {

    private DynamoDbAttributeValues() {}

    /** Converts a whole item (attribute name → attribute value). Returns null for a null item. */
    public static Map<String, Object> toPlainItem(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.put(field.getKey(), toPlain(field.getValue()));
        }
        return out;
    }

    /** Converts one attribute value. Unknown or malformed descriptors are returned as-is (as text). */
    public static Object toPlain(JsonNode attributeValue) {
        if (attributeValue == null || attributeValue.isNull() || !attributeValue.isObject()
                || attributeValue.size() != 1) {
            return attributeValue == null ? null : attributeValue.toString();
        }
        Map.Entry<String, JsonNode> descriptor = attributeValue.fields().next();
        JsonNode value = descriptor.getValue();
        return switch (descriptor.getKey()) {
            case "S", "B" -> value.asText();
            case "N" -> toNumber(value.asText());
            case "BOOL" -> value.asBoolean();
            case "NULL" -> null;
            case "SS", "BS" -> {
                List<Object> list = new ArrayList<>();
                value.forEach(v -> list.add(v.asText()));
                yield list;
            }
            case "NS" -> {
                List<Object> list = new ArrayList<>();
                value.forEach(v -> list.add(toNumber(v.asText())));
                yield list;
            }
            case "L" -> {
                List<Object> list = new ArrayList<>();
                value.forEach(v -> list.add(toPlain(v)));
                yield list;
            }
            case "M" -> toPlainItem(value);
            default -> attributeValue.toString();
        };
    }

    static Object toNumber(String text) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException e) {
            return text;
        }
        if (decimal.scale() <= 0 || decimal.stripTrailingZeros().scale() <= 0) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException ignored) {
                // out of long range, fall through
            }
        }
        return decimal;
    }
}
