package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * EventBridge event pattern matching over a JSON document, as an EventBridge V2 subscriber filter
 * evaluates it against one scope of an event. A field's pattern is a list of alternatives, any of
 * which matches, or a nested pattern for an object; {@code $or} takes a list of patterns, any of
 * which matches. A field whose value is an array matches when any of its elements does.
 */
final class EventPatternMatcher {

    private EventPatternMatcher() {
    }

    static boolean matches(JsonNode pattern, JsonNode actual) {
        if (!pattern.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = pattern.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if ("$or".equals(field.getKey())) {
                if (!anyMatches(field.getValue(), actual)) {
                    return false;
                }
                continue;
            }
            JsonNode value = actual == null || !actual.isObject() ? null : actual.get(field.getKey());
            JsonNode expected = field.getValue();
            if (expected.isObject()) {
                boolean nestedMatches = value == null ? matches(expected, null) : matchesNested(expected, value);
                if (!nestedMatches) {
                    return false;
                }
            } else if (expected.isArray()) {
                if (!matchesAlternatives(expected, value)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMatches(JsonNode patterns, JsonNode actual) {
        for (JsonNode pattern : patterns) {
            if (matches(pattern, actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesNested(JsonNode pattern, JsonNode value) {
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (matches(pattern, element)) {
                    return true;
                }
            }
            return false;
        }
        return matches(pattern, value);
    }

    /** Whether the field, absent as null, matches any of the alternatives. */
    private static boolean matchesAlternatives(JsonNode alternatives, JsonNode value) {
        for (JsonNode alternative : alternatives) {
            if (alternative.isObject() && alternative.has("exists")) {
                if (alternative.get("exists").asBoolean() == (value != null)) {
                    return true;
                }
                continue;
            }
            if (value == null) {
                continue;
            }
            if (value.isArray()) {
                for (JsonNode element : value) {
                    if (matchesOne(alternative, element)) {
                        return true;
                    }
                }
            } else if (matchesOne(alternative, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOne(JsonNode alternative, JsonNode value) {
        if (!alternative.isObject()) {
            return equalsExactly(alternative, value);
        }
        if (alternative.has("prefix")) {
            return matchesAffix(alternative.get("prefix"), value, true);
        }
        if (alternative.has("suffix")) {
            return matchesAffix(alternative.get("suffix"), value, false);
        }
        if (alternative.has("equals-ignore-case")) {
            return value.isTextual() && value.textValue().equalsIgnoreCase(alternative.get("equals-ignore-case").asText());
        }
        if (alternative.has("numeric")) {
            return matchesNumeric(alternative.get("numeric"), value);
        }
        if (alternative.has("anything-but")) {
            return !matchesExcluded(alternative.get("anything-but"), value);
        }
        return false;
    }

    private static boolean matchesExcluded(JsonNode excluded, JsonNode value) {
        if (excluded.isArray()) {
            for (JsonNode each : excluded) {
                if (equalsExactly(each, value)) {
                    return true;
                }
            }
            return false;
        }
        if (excluded.isObject()) {
            if (excluded.has("prefix")) {
                return matchesAffix(excluded.get("prefix"), value, true);
            }
            if (excluded.has("suffix")) {
                return matchesAffix(excluded.get("suffix"), value, false);
            }
            if (excluded.has("equals-ignore-case")) {
                JsonNode names = excluded.get("equals-ignore-case");
                for (JsonNode name : names.isArray() ? names : List.of(names)) {
                    if (value.isTextual() && value.textValue().equalsIgnoreCase(name.asText())) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
        return equalsExactly(excluded, value);
    }

    /** A prefix or suffix, given as a string or as {@code {"equals-ignore-case": ...}}. */
    private static boolean matchesAffix(JsonNode affix, JsonNode value, boolean prefix) {
        if (!value.isTextual()) {
            return false;
        }
        String text = value.textValue();
        if (affix.isObject() && affix.has("equals-ignore-case")) {
            String wanted = affix.get("equals-ignore-case").asText();
            return text.length() >= wanted.length() && (prefix
                    ? text.regionMatches(true, 0, wanted, 0, wanted.length())
                    : text.regionMatches(true, text.length() - wanted.length(), wanted, 0, wanted.length()));
        }
        return prefix ? text.startsWith(affix.asText()) : text.endsWith(affix.asText());
    }

    private static boolean matchesNumeric(JsonNode conditions, JsonNode value) {
        if (!value.isNumber() || !conditions.isArray() || conditions.size() % 2 != 0) {
            return false;
        }
        BigDecimal actual = value.decimalValue();
        for (int i = 0; i < conditions.size(); i += 2) {
            int compared = actual.compareTo(conditions.get(i + 1).decimalValue());
            boolean holds = switch (conditions.get(i).asText()) {
                case "=" -> compared == 0;
                case ">" -> compared > 0;
                case ">=" -> compared >= 0;
                case "<" -> compared < 0;
                case "<=" -> compared <= 0;
                default -> false;
            };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsExactly(JsonNode expected, JsonNode value) {
        if (expected.isNull()) {
            return value.isNull();
        }
        if (expected.isNumber()) {
            return value.isNumber() && expected.decimalValue().compareTo(value.decimalValue()) == 0;
        }
        if (expected.isTextual()) {
            return value.isTextual() && expected.textValue().equals(value.textValue());
        }
        if (expected.isBoolean()) {
            return value.isBoolean() && expected.booleanValue() == value.booleanValue();
        }
        return false;
    }
}
