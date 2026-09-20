package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Calculates DynamoDB item size and enforces the 400KB limit.
 */
final class DynamoDbItemSize {

    static final int MAX_ITEM_SIZE = 400 * 1024; // 400 KB = 409,600 bytes

    static final String UPDATE_EXCEEDED = "Item size to update has exceeded the maximum allowed size";

    // UpdateItem measures what the statement writes, name and value, not the item it stores,
    // and charges these on top. Every other surface measures the finished item.
    private static final int UPDATE_COST = 3;
    private static final int SET_OR_ADD_COST = 19;
    private static final int REMOVE_OR_DELETE_COST = 2;
    private static final int LIST_INDEX_COST = 1;

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

    static boolean updatedItemWithinLimit(JsonNode item) {
        return calculateItemSize(item) <= MAX_ITEM_SIZE;
    }

    static boolean updateItemWithinLimit(JsonNode item, Set<String> writtenAttributes, int actionCost) {
        int total = 0;
        int written = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            int size = utf8Length(entry.getKey()) + attributeValueSize(entry.getValue());
            total += size;
            if (writtenAttributes.contains(entry.getKey())) {
                written += size;
            }
        }
        return total <= MAX_ITEM_SIZE && written + actionCost <= MAX_ITEM_SIZE;
    }

    static int updateExpressionCost(String expression) {
        int cost = UPDATE_COST;
        String remaining = expression.trim().replaceAll("\\s+", " ");
        while (!remaining.isEmpty()) {
            String keyword = remaining.substring(0, Math.max(remaining.indexOf(' '), 0)).toUpperCase(Locale.ROOT);
            String body = remaining.substring(keyword.length()).trim();
            int nextClause = DynamoDbService.findNextClauseKeyword(body);
            String actions = nextClause < 0 ? body : body.substring(0, nextClause);
            remaining = nextClause < 0 ? "" : body.substring(nextClause);
            while (!actions.isBlank()) {
                int comma = DynamoDbService.findNextComma(actions);
                cost += actionCost(keyword, comma < 0 ? actions : actions.substring(0, comma));
                actions = comma < 0 ? "" : actions.substring(comma + 1);
            }
        }
        return cost;
    }

    static int attributeUpdatesCost(JsonNode attributeUpdates) {
        int cost = UPDATE_COST;
        if (attributeUpdates == null || !attributeUpdates.isObject()) {
            return cost;
        }
        for (JsonNode update : attributeUpdates) {
            cost += "DELETE".equals(update.path("Action").asText("PUT"))
                    ? REMOVE_OR_DELETE_COST : SET_OR_ADD_COST;
        }
        return cost;
    }

    private static int actionCost(String clause, String action) {
        return switch (clause) {
            case "SET" -> SET_OR_ADD_COST + (writesListElement(action) ? LIST_INDEX_COST : 0);
            case "ADD" -> SET_OR_ADD_COST;
            default -> REMOVE_OR_DELETE_COST;
        };
    }

    private static boolean writesListElement(String action) {
        int bracket = action.indexOf('[');
        int assignment = action.indexOf('=');
        return bracket >= 0 && (assignment < 0 || bracket < assignment);
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

    static int attributeValueSize(JsonNode attr) {
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
