package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Calculates DynamoDB item size and enforces the 400KB limit.
 */
final class DynamoDbItemSize {

    static final int MAX_ITEM_SIZE = 400 * 1024; // 400 KB = 409,600 bytes

    private DynamoDbItemSize() {}

    /**
     * Validates the item fits within 400KB. Throws ValidationException if not.
     */
    static void validateSize(JsonNode item) {
        int size = calculateItemSize(item);
        if (size > MAX_ITEM_SIZE) {
            throw new AwsException("ValidationException",
                    "Item size has exceeded the maximum allowed size", 400);
        }
    }

    static int calculateItemSize(JsonNode item) {
        if (item == null || !item.isObject()) return 0;
        int total = 0;
        var fields = item.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            total += utf8Length(entry.getKey());
            total += attributeValueSize(entry.getValue());
        }
        return total;
    }

    private static int attributeValueSize(JsonNode attr) {
        if (attr == null) return 0;
        if (attr.has("S")) return utf8Length(attr.get("S").asText());
        if (attr.has("N")) return attr.get("N").asText().length();
        if (attr.has("B")) return binarySize(attr.get("B").asText());
        if (attr.has("BOOL")) return 1;
        if (attr.has("NULL")) return 1;
        if (attr.has("SS")) {
            int size = 0;
            for (JsonNode e : attr.get("SS")) size += utf8Length(e.asText()) + 1;
            return size;
        }
        if (attr.has("NS")) {
            int size = 0;
            for (JsonNode e : attr.get("NS")) size += e.asText().length() + 1;
            return size;
        }
        if (attr.has("BS")) {
            int size = 0;
            for (JsonNode e : attr.get("BS")) size += binarySize(e.asText()) + 1;
            return size;
        }
        if (attr.has("L")) {
            int size = 0;
            for (JsonNode e : attr.get("L")) size += attributeValueSize(e) + 3;
            return size;
        }
        if (attr.has("M")) {
            int size = 0;
            var fields = attr.get("M").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                size += utf8Length(entry.getKey()) + 1 + attributeValueSize(entry.getValue()) + 3;
            }
            return size;
        }
        return 0;
    }

    private static int utf8Length(String s) {
        if (s == null) return 0;
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int binarySize(String base64) {
        if (base64 == null || base64.isEmpty()) return 0;
        try {
            return Base64.getDecoder().decode(base64).length;
        } catch (Exception e) {
            // Fallback: estimate from base64 length
            return (int) (base64.length() * 3L / 4);
        }
    }
}
