package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/**
 * DynamoDB number validation and normalization.
 * Numbers must have <= 38 significant digits, be within range, and are stored
 * in normalized form (no leading zeros, no trailing decimal zeros, -0 -> 0).
 */
final class DynamoDbNumberUtils {

    private static final BigDecimal MAX_ABS = new BigDecimal("1E+126");
    private static final BigDecimal MIN_ABS_NONZERO = new BigDecimal("1E-130");

    private DynamoDbNumberUtils() {}

    /**
     * Validates and normalizes a DynamoDB number string.
     * Throws ValidationException if the number is out of range or has too many significant digits.
     * Returns the normalized string (no leading zeros, no trailing decimal zeros).
     */
    static String validateAndNormalize(String numStr) {
        if (numStr == null || numStr.isEmpty()) {
            throw new AwsException("ValidationException",
                    "The parameter cannot be converted to a numeric value", 400);
        }
        BigDecimal bd;
        try {
            bd = new BigDecimal(numStr);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException",
                    "The parameter cannot be converted to a numeric value: " + numStr, 400);
        }

        BigDecimal stripped = bd.stripTrailingZeros();

        // Check significant digits after normalization
        if (stripped.precision() > 38) {
            throw new AwsException("ValidationException",
                    "Number too many significant digits; first supported is 1E-130 last supported is 1E+126", 400);
        }

        // Check range (only for nonzero values)
        BigDecimal abs = stripped.abs();
        if (abs.compareTo(BigDecimal.ZERO) > 0) {
            if (abs.compareTo(MAX_ABS) >= 0) {
                throw new AwsException("ValidationException",
                        "Number overflow. Attempting to store a value that is too large. "
                        + "The maximum allowed positive value is 9.9999999999999999999999999999999999999E+125", 400);
            }
            if (abs.compareTo(MIN_ABS_NONZERO) < 0) {
                throw new AwsException("ValidationException",
                        "Number underflow. Attempting to store a value that is too small. "
                        + "The minimum allowed positive value is 1E-130", 400);
            }
        }

        return toNormalizedString(stripped);
    }

    private static String toNormalizedString(BigDecimal bd) {
        // -0 -> 0
        if (bd.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        // Use plain string to expand scientific notation to decimal form when reasonable
        return bd.toPlainString();
    }

    /**
     * Recursively normalizes all N-type number attribute values in the given item.
     * Returns a new ObjectNode with normalized values. Input must be a DynamoDB item
     * where attribute values are DynamoDB typed values (e.g. {S: "x"}, {N: "42"}).
     */
    static ObjectNode normalizeNumbersInItem(JsonNode item) {
        if (item == null || !item.isObject()) return (ObjectNode) item;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        item.fields().forEachRemaining(entry -> {
            result.set(entry.getKey(), normalizeAttrValue(entry.getValue()));
        });
        return result;
    }

    private static JsonNode normalizeAttrValue(JsonNode attr) {
        if (attr == null) return null;

        if (attr.has("N")) {
            String raw = attr.get("N").asText();
            String normalized = validateAndNormalize(raw);
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.put("N", normalized);
            return result;
        }

        if (attr.has("NS")) {
            // Normalize and validate each element
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            ArrayNode ns = JsonNodeFactory.instance.arrayNode();
            for (JsonNode elem : attr.get("NS")) {
                ns.add(validateAndNormalize(elem.asText()));
            }
            result.set("NS", ns);
            return result;
        }

        if (attr.has("M")) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.set("M", normalizeNumbersInItem(attr.get("M")));
            return result;
        }

        if (attr.has("L")) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            ArrayNode list = JsonNodeFactory.instance.arrayNode();
            for (JsonNode elem : attr.get("L")) {
                list.add(normalizeAttrValue(elem));
            }
            result.set("L", list);
            return result;
        }

        return attr;
    }
}
