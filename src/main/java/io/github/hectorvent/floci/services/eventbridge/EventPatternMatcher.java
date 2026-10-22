package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Validates EventBridge event patterns and matches them against an event envelope, following
 * <a href="https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-create-pattern-operators.html">the
 * comparison operators</a> and
 * <a href="https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-event-patterns-arrays.html">array
 * matching</a>. Every key of a pattern is a path into the event. A leaf matches when any of its
 * values matches the event value or, for an array in the event, any element of it. A nested
 * pattern over an array of objects matches when a single element matches all of it.
 */
final class EventPatternMatcher {

    private static final Set<String> ANYTHING_BUT_STRING_OPERATORS =
            Set.of("prefix", "suffix", "equals-ignore-case", "wildcard");
    private static final Set<String> NUMERIC_COMPARATORS = Set.of("=", "<", "<=", ">", ">=");
    private static final int WILDCARD = -1;

    private final ObjectMapper objectMapper;

    EventPatternMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a pattern and rejects one AWS would refuse: malformed JSON, a root that is not an
     * object, a field that is neither an object nor an array, or a match expression with an
     * unknown operator or an operand of the wrong shape.
     */
    JsonNode requireValid(String eventPattern) {
        JsonNode pattern;
        try {
            pattern = objectMapper.readTree(eventPattern);
        } catch (JsonProcessingException e) {
            throw invalid("Event pattern is not valid JSON: " + e.getOriginalMessage());
        }
        if (pattern == null || !pattern.isObject()) {
            throw invalid("Event pattern must be a JSON object.");
        }
        validateObject(pattern);
        return pattern;
    }

    boolean matches(JsonNode event, JsonNode pattern) {
        return pattern != null && pattern.isObject() && matchesObject(event, pattern);
    }

    // ──────────────────────────── Validation ────────────────────────────

    private static void validateObject(JsonNode pattern) {
        for (Map.Entry<String, JsonNode> field : pattern.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (isOrOperator(key, value)) {
                for (JsonNode clause : value) {
                    validateObject(clause);
                }
            } else if (value.isObject()) {
                validateObject(value);
            } else if (value.isArray()) {
                for (JsonNode element : value) {
                    validateMatch(key, element);
                }
            } else {
                throw invalid("\"" + key + "\" must be an object or an array");
            }
        }
    }

    private static void validateMatch(String key, JsonNode element) {
        if (element.isArray()) {
            throw invalid("Match value for \"" + key + "\" must be a string, number, true, false, or null");
        }
        if (element.isNumber() && !isFiniteNumber(element)) {
            throw invalid("Number out of range for \"" + key + "\": " + element.asText());
        }
        if (!element.isObject()) {
            return;
        }
        if (element.size() != 1) {
            throw invalid("Only one key allowed in a match expression for \"" + key + "\"");
        }
        String operator = element.fieldNames().next();
        JsonNode operand = element.get(operator);
        boolean valid = switch (operator) {
            case "prefix", "suffix" -> operand.isTextual() || isIgnoreCaseOperand(operand);
            case "equals-ignore-case" -> operand.isTextual();
            case "wildcard" -> operand.isTextual() && isValidWildcard(operand.asText());
            case "cidr" -> operand.isTextual() && CidrCanonicalizer.canonicalize(operand.asText()).isPresent();
            case "exists" -> operand.isBoolean();
            case "numeric" -> isValidNumericCondition(operand);
            case "anything-but" -> isValidAnythingBut(operand);
            default -> throw invalid("Unrecognized match type " + operator + " for \"" + key + "\"");
        };
        if (!valid) {
            throw invalid("Invalid " + operator + " match expression for \"" + key + "\"");
        }
    }

    private static boolean isValidAnythingBut(JsonNode operand) {
        if (operand.isArray()) {
            for (JsonNode value : operand) {
                if (value.isContainerNode() || (value.isNumber() && !isFiniteNumber(value))) {
                    return false;
                }
            }
            return true;
        }
        if (!operand.isObject()) {
            return !operand.isNumber() || isFiniteNumber(operand);
        }
        if (operand.size() != 1) {
            return false;
        }
        String operator = operand.fieldNames().next();
        if (!ANYTHING_BUT_STRING_OPERATORS.contains(operator)) {
            return false;
        }
        JsonNode excluded = operand.get(operator);
        if (!excluded.isArray()) {
            return isValidStringOperand(operator, excluded);
        }
        for (JsonNode value : excluded) {
            if (!isValidStringOperand(operator, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidStringOperand(String operator, JsonNode operand) {
        return operand.isTextual() && (!"wildcard".equals(operator) || isValidWildcard(operand.asText()));
    }

    private static boolean isValidNumericCondition(JsonNode condition) {
        if (!condition.isArray() || condition.isEmpty() || condition.size() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < condition.size(); i += 2) {
            JsonNode comparator = condition.get(i);
            if (!comparator.isTextual() || !NUMERIC_COMPARATORS.contains(comparator.asText())
                    || !isFiniteNumber(condition.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * EventBridge compares numbers as IEEE 754 doubles, so a JSON number beyond that range, such as
     * 1e400, which Jackson reads as an infinite double, is not a number it can match.
     */
    private static boolean isFiniteNumber(JsonNode node) {
        return node.isNumber() && Double.isFinite(node.doubleValue());
    }

    /** A wildcard is valid when every backslash escapes {@code *} or {@code \} and no two wildcards touch. */
    private static boolean isValidWildcard(String pattern) {
        int[] units = wildcardUnits(pattern);
        if (units == null) {
            return false;
        }
        for (int i = 1; i < units.length; i++) {
            if (units[i] == WILDCARD && units[i - 1] == WILDCARD) {
                return false;
            }
        }
        return true;
    }

    private static AwsException invalid(String reason) {
        return new AwsException("InvalidEventPatternException", "Event pattern is not valid. Reason: " + reason, 400);
    }

    // ──────────────────────────── Matching ────────────────────────────

    private boolean matchesObject(JsonNode actual, JsonNode pattern) {
        if (actual != null && actual.isArray()) {
            if (actual.isEmpty()) {
                return matchesObject(null, pattern);
            }
            for (JsonNode element : actual) {
                if (matchesObject(element, pattern)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonNode> field : pattern.properties()) {
            String key = field.getKey();
            JsonNode expected = field.getValue();
            if (isOrOperator(key, expected)) {
                if (!matchesAnyClause(actual, expected)) {
                    return false;
                }
                continue;
            }
            JsonNode value = actual != null && actual.isObject() ? actual.get(key) : null;
            boolean fieldMatches;
            if (expected.isArray()) {
                fieldMatches = matchesAnyValue(expected, value);
            } else if (expected.isObject()) {
                fieldMatches = matchesObject(nestedValue(value), expected);
            } else {
                fieldMatches = false;
            }
            if (!fieldMatches) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAnyClause(JsonNode actual, JsonNode clauses) {
        for (JsonNode clause : clauses) {
            if (matchesObject(actual, clause)) {
                return true;
            }
        }
        return false;
    }

    /** A JSON object or array carried as a string is matched as the structure it encodes. */
    private JsonNode nestedValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return value;
        }
        try {
            JsonNode parsed = objectMapper.readTree(value.asText());
            return parsed != null && parsed.isContainerNode() ? parsed : value;
        } catch (JsonProcessingException ignored) {
            // Not JSON: a plain string, which has no nested fields for the pattern to find.
            return value;
        }
    }

    private static boolean matchesAnyValue(JsonNode expectedValues, JsonNode value) {
        for (JsonNode expected : expectedValues) {
            if (matchesValue(expected, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesValue(JsonNode expected, JsonNode value) {
        if (expected.isObject() && expected.size() == 1 && expected.has("exists")) {
            JsonNode exists = expected.get("exists");
            return exists.isBoolean() && exists.booleanValue() == hasValue(value);
        }
        if (value == null) {
            return false;
        }
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (matchesValue(expected, element)) {
                    return true;
                }
            }
            return false;
        }
        return expected.isObject() ? matchesOperator(expected, value) : matchesLiteral(expected, value);
    }

    /**
     * Exists matching only sees leaf values: an object is an intermediate node, and an array carries
     * a value only through its elements, so neither an object nor an empty array is present.
     */
    private static boolean hasValue(JsonNode value) {
        if (value == null || value.isObject()) {
            return false;
        }
        if (!value.isArray()) {
            return true;
        }
        for (JsonNode element : value) {
            if (hasValue(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesLiteral(JsonNode expected, JsonNode value) {
        if (expected.isNull()) {
            return value.isNull();
        }
        if (expected.isTextual()) {
            return value.isTextual() && expected.asText().equals(value.asText());
        }
        if (expected.isBoolean()) {
            return value.isBoolean() && expected.booleanValue() == value.booleanValue();
        }
        if (expected.isNumber()) {
            // AWS normalizes numbers before comparing: 300, 300.0 and 3.0e2 are all equal.
            return isFiniteNumber(expected) && isFiniteNumber(value)
                    && expected.decimalValue().compareTo(value.decimalValue()) == 0;
        }
        return false;
    }

    private static boolean matchesOperator(JsonNode expression, JsonNode value) {
        if (expression.size() != 1) {
            return false;
        }
        String operator = expression.fieldNames().next();
        JsonNode operand = expression.get(operator);
        return switch (operator) {
            case "prefix" -> matchesAffix(operand, value, true);
            case "suffix" -> matchesAffix(operand, value, false);
            case "equals-ignore-case", "wildcard", "cidr" -> operand.isTextual() && value.isTextual()
                    && matchesString(operator, operand.asText(), value.asText());
            case "numeric" -> isFiniteNumber(value) && matchesNumeric(operand, value.decimalValue());
            case "anything-but" -> matchesAnythingBut(operand, value);
            default -> false;
        };
    }

    private static boolean matchesAffix(JsonNode operand, JsonNode value, boolean prefix) {
        boolean ignoreCase = isIgnoreCaseOperand(operand);
        JsonNode affixNode = ignoreCase ? operand.get("equals-ignore-case") : operand;
        if (!affixNode.isTextual() || !value.isTextual()) {
            return false;
        }
        String text = value.asText();
        String affix = affixNode.asText();
        int offset = prefix ? 0 : text.length() - affix.length();
        return offset >= 0 && text.regionMatches(ignoreCase, offset, affix, 0, affix.length());
    }

    private static boolean isIgnoreCaseOperand(JsonNode operand) {
        return operand.isObject() && operand.size() == 1 && operand.path("equals-ignore-case").isTextual();
    }

    private static boolean matchesString(String operator, String operand, String value) {
        return switch (operator) {
            case "prefix" -> value.startsWith(operand);
            case "suffix" -> value.endsWith(operand);
            case "equals-ignore-case" -> value.equalsIgnoreCase(operand);
            case "wildcard" -> matchesWildcard(operand, value);
            case "cidr" -> CidrCanonicalizer.contains(operand, value);
            default -> false;
        };
    }

    private static boolean matchesNumeric(JsonNode condition, BigDecimal value) {
        if (!isValidNumericCondition(condition)) {
            return false;
        }
        for (int i = 0; i < condition.size(); i += 2) {
            int comparison = value.compareTo(condition.get(i + 1).decimalValue());
            boolean holds = switch (condition.get(i).asText()) {
                case "=" -> comparison == 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                default -> false;
            };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    /**
     * A missing or JSON-null value never matches. A list excludes each of its values; the object
     * form excludes the strings that one nested prefix, suffix, equals-ignore-case or wildcard
     * operator (or a list of them) would match.
     */
    private static boolean matchesAnythingBut(JsonNode operand, JsonNode value) {
        if (operand.isObject()) {
            if (operand.size() != 1 || !value.isTextual()) {
                return false;
            }
            String operator = operand.fieldNames().next();
            if (!ANYTHING_BUT_STRING_OPERATORS.contains(operator)) {
                return false;
            }
            JsonNode excluded = operand.get(operator);
            if (excluded.isTextual()) {
                return !matchesString(operator, excluded.asText(), value.asText());
            }
            if (!excluded.isArray()) {
                return false;
            }
            for (JsonNode each : excluded) {
                if (!each.isTextual() || matchesString(operator, each.asText(), value.asText())) {
                    return false;
                }
            }
            return true;
        }
        if (value.isNull() || value.isContainerNode()) {
            return false;
        }
        if (operand.isArray()) {
            for (JsonNode each : operand) {
                if (matchesLiteral(each, value)) {
                    return false;
                }
            }
            return true;
        }
        return !matchesLiteral(operand, value);
    }

    /**
     * Anchored, case-sensitive glob: each unescaped {@code *} matches any run of characters,
     * {@code \*} and {@code \\} match a literal asterisk and backslash.
     */
    private static boolean matchesWildcard(String pattern, String value) {
        int[] units = wildcardUnits(pattern);
        if (units == null) {
            return false;
        }
        int unit = 0;
        int index = 0;
        int starUnit = -1;
        int starIndex = -1;
        while (index < value.length()) {
            if (unit < units.length && units[unit] == WILDCARD) {
                starUnit = unit++;
                starIndex = index;
            } else if (unit < units.length && units[unit] == value.charAt(index)) {
                unit++;
                index++;
            } else if (starUnit >= 0) {
                unit = starUnit + 1;
                index = ++starIndex;
            } else {
                return false;
            }
        }
        while (unit < units.length && units[unit] == WILDCARD) {
            unit++;
        }
        return unit == units.length;
    }

    /** The pattern as literal characters and {@link #WILDCARD} markers, or null for an unsupported escape. */
    private static int[] wildcardUnits(String pattern) {
        int[] units = new int[pattern.length()];
        int count = 0;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i++);
            if (c == '\\') {
                if (i == pattern.length() || (pattern.charAt(i) != '*' && pattern.charAt(i) != '\\')) {
                    return null;
                }
                units[count++] = pattern.charAt(i++);
            } else {
                units[count++] = c == '*' ? WILDCARD : c;
            }
        }
        return Arrays.copyOf(units, count);
    }

    /**
     * {@code $or} is an operator when its value is a non-empty array of objects; anything else is
     * an ordinary field of that name.
     */
    private static boolean isOrOperator(String key, JsonNode value) {
        if (!"$or".equals(key) || !value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode clause : value) {
            if (!clause.isObject()) {
                return false;
            }
        }
        return true;
    }
}
