package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * DynamoDB number validation and normalization.
 * Numbers must have <= 38 significant digits, be within range, and are stored
 * in normalized form (no leading zeros, no trailing decimal zeros, -0 -> 0).
 */
final class DynamoDbNumberUtils {

    private static final BigDecimal MAX_ABS = new BigDecimal("1E+126");
    private static final BigDecimal MIN_ABS_NONZERO = new BigDecimal("1E-130");
    static final String NUMBER_OVERFLOW =
            "Number overflow. Attempting to store a number with magnitude larger than supported range";
    static final String NUMBER_UNDERFLOW =
            "Number underflow. Attempting to store a number with magnitude smaller than supported range";
    static final String TOO_MANY_DIGITS = "Attempting to store more than 38 significant digits in a Number";
    static final String PARAMETER_NOT_NUMERIC = "The parameter cannot be converted to a numeric value";

    private DynamoDbNumberUtils() {}

    // DynamoDB measures a PartiQL number in 32-bit arithmetic, so an exponent near the limit wraps
    // around and reads as an underflow.
    static String partiQLNumberProblem(BigDecimal number) {
        int magnitude = number.precision() - number.scale() + 1;
        if (127 < magnitude) {
            return NUMBER_OVERFLOW;
        }
        if (magnitude < -128) {
            return NUMBER_UNDERFLOW;
        }
        if (38 < number.stripTrailingZeros().precision()) {
            return TOO_MANY_DIGITS;
        }
        return null;
    }

    // DynamoDB reads a PartiQL parameter as BigDecimal does, but only with an exponent that fits in
    // an int. Unlike a literal, the parameter is not rounded to 38 digits.
    static BigDecimal requirePartiQLParameterNumber(String text) {
        if (text.isEmpty()) {
            throw new AwsException("ValidationException", PARAMETER_NOT_NUMERIC, 400);
        }
        BigDecimal number;
        try {
            int exponent = Math.max(text.indexOf('e'), text.indexOf('E'));
            if (0 <= exponent) {
                Integer.parseInt(text.substring(exponent + 1));
            }
            number = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", PARAMETER_NOT_NUMERIC + ": " + text, 400);
        }
        String problem = partiQLNumberProblem(number);
        if (problem != null) {
            throw new AwsException("ValidationException", problem, 400);
        }
        return number;
    }

    // Members are equal by value here, so 1 and 1.0 are duplicates.
    static void requirePartiQLParameterNumberSet(JsonNode members) {
        Set<BigDecimal> seen = new TreeSet<>();
        for (JsonNode member : members) {
            if (!seen.add(requirePartiQLParameterNumber(member.asText()))) {
                throw new AwsException("ValidationException", "Input collection contains duplicates", 400);
            }
        }
    }

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

    static void forEachNumber(JsonNode values, Consumer<String> check) {
        for (JsonNode value : values) {
            JsonNode number = value.get("N");
            if (number != null && number.isTextual()) {
                check.accept(number.asText());
            }
            for (JsonNode member : value.path("NS")) {
                if (member.isTextual()) {
                    check.accept(member.asText());
                }
            }
            forEachNumber(value.path("L"), check);
            forEachNumber(value.path("M"), check);
        }
    }

    static void requireItemNumber(String text, String prefix) {
        String problem = itemNumberProblem(text);
        if (problem != null) {
            throw new AwsException("ValidationException", prefix + problem, 400);
        }
    }

    // An item call reads the exponent as a long and checks the range without wrapping around,
    // unlike a PartiQL parameter. It counts the digits before it checks the range.
    private static String itemNumberProblem(String text) {
        int marker = exponentMarker(text);
        BigDecimal mantissa;
        long exponent;
        try {
            mantissa = new BigDecimal(marker < 0 ? text : text.substring(0, marker));
            exponent = marker < 0 ? 0 : Long.parseLong(text.substring(marker + 1));
        } catch (NumberFormatException e) {
            return PARAMETER_NOT_NUMERIC + ": " + text;
        }
        if (38 < mantissa.stripTrailingZeros().precision()) {
            return TOO_MANY_DIGITS;
        }
        long magnitude = (long) mantissa.precision() - mantissa.scale() + 1;
        if (127 - magnitude < exponent) {
            return NUMBER_OVERFLOW;
        }
        if (exponent < -128 - magnitude) {
            return NUMBER_UNDERFLOW;
        }
        return null;
    }

    private static int exponentMarker(String text) {
        int lower = text.indexOf('e');
        int upper = text.indexOf('E');
        if (lower < 0 || upper < 0) {
            return Math.max(lower, upper);
        }
        return Math.min(lower, upper);
    }

    // AWS answers with the overflow wording for any arithmetic result it cannot store,
    // a magnitude too small or too many significant digits included.
    static void checkArithmeticResult(BigDecimal result) {
        BigDecimal stripped = result.stripTrailingZeros();
        BigDecimal abs = stripped.abs();
        if (stripped.precision() > 38 || abs.compareTo(MAX_ABS) >= 0
                || (abs.signum() > 0 && abs.compareTo(MIN_ABS_NONZERO) < 0)) {
            throw new AwsException("ValidationException", NUMBER_OVERFLOW, 400);
        }
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
     * Recursively normalizes all N-type number attribute values in the given item and
     * puts set members in DynamoDB's order. Returns a new ObjectNode. Input must be a
     * DynamoDB item where attribute values are DynamoDB typed values (e.g. {S: "x"}, {N: "42"}).
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

        // A well-formed AttributeValue has exactly one type member. Rebuilding a
        // malformed one (e.g. {"S":"x","N":"1"}) around its "N" member would launder
        // it into a valid value before validation can reject it — pass it through.
        if (attr.isObject() && attr.size() != 1) {
            return attr;
        }

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
            result.set("NS", DynamoDbSetOrder.sorted("NS", ns));
            return result;
        }

        if (attr.has("SS") || attr.has("BS")) {
            String setType = attr.has("SS") ? "SS" : "BS";
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.set(setType, DynamoDbSetOrder.sorted(setType, attr.get(setType)));
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
