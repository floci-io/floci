package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * EventBridge event pattern matching, shared by EventBridge Pipes, the Lambda event source mappings and the
 * EventBridge V2 subscribers. A field's pattern is a list of alternatives, any of which matches, or a nested
 * pattern for an object; {@code $or} takes a list of patterns, any of which matches. A field whose value is an
 * array matches when any of its elements does.
 *
 * <p>A pattern the matcher cannot evaluate throws {@link IllegalArgumentException}: whether that is refused up
 * front or treated as matching nothing is the calling service's choice.
 */
@ApplicationScoped
public class PipesFilterMatcher {

    private static final Logger LOG = Logger.getLogger(PipesFilterMatcher.class);

    private final ObjectMapper objectMapper;

    @Inject
    public PipesFilterMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<JsonNode> applyFilterCriteria(List<JsonNode> records, JsonNode sourceParameters) {
        if (sourceParameters == null) {
            return records;
        }
        JsonNode filters = sourceParameters.path("FilterCriteria").path("Filters");
        if (filters.isMissingNode() || !filters.isArray() || filters.isEmpty()) {
            return records;
        }
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode record : records) {
            if (matchesAnyFilter(record, filters)) {
                matched.add(record);
            }
        }
        return matched;
    }

    private boolean matchesAnyFilter(JsonNode record, JsonNode filters) {
        for (JsonNode filter : filters) {
            JsonNode patternNode = filter.path("Pattern");
            if (patternNode.isMissingNode()) {
                continue;
            }
            String patternStr = patternNode.isTextual() ? patternNode.asText() : patternNode.toString();
            if (matchesRecord(record, patternStr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a source record matches the pattern. A string field the pattern nests an object under is parsed as
     * JSON first, as for an SQS {@code body} or a Kinesis {@code data}. A pattern that is not valid matches nothing.
     */
    boolean matchesRecord(JsonNode record, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        try {
            return matches(objectMapper.readTree(pattern), record, objectMapper);
        } catch (IllegalArgumentException | IOException e) {
            LOG.warnv("Invalid filter pattern {0}: {1}", pattern, e.getMessage());
            return false;
        }
    }

    /**
     * Whether the document matches the pattern, as EventBridge matches it.
     *
     * @throws IllegalArgumentException if the pattern is not one the matcher can evaluate
     */
    public static boolean matches(JsonNode pattern, JsonNode actual) {
        return matches(pattern, actual, null);
    }

    /** {@code decoder}, when given, parses a string field the pattern nests an object under. */
    private static boolean matches(JsonNode pattern, JsonNode actual, ObjectMapper decoder) {
        if (!pattern.isObject()) {
            throw new IllegalArgumentException("a pattern must be a JSON object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = pattern.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if ("$or".equals(field.getKey())) {
                if (!anyMatches(field.getValue(), actual, decoder)) {
                    return false;
                }
                continue;
            }
            JsonNode value = actual == null || !actual.isObject() ? null : actual.get(field.getKey());
            JsonNode expected = field.getValue();
            if (expected.isObject()) {
                boolean nestedMatches = value == null
                        ? matches(expected, null, decoder)
                        : matchesNested(expected, decoded(value, decoder), decoder);
                if (!nestedMatches) {
                    return false;
                }
            } else if (expected.isArray()) {
                if (!matchesAlternatives(field.getKey(), expected, value)) {
                    return false;
                }
            } else {
                throw new IllegalArgumentException("field " + field.getKey()
                        + " must be a list of values or a nested pattern");
            }
        }
        return true;
    }

    private static boolean anyMatches(JsonNode patterns, JsonNode actual, ObjectMapper decoder) {
        if (!patterns.isArray()) {
            throw new IllegalArgumentException("$or must be a list of patterns");
        }
        for (JsonNode pattern : patterns) {
            if (matches(pattern, actual, decoder)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode decoded(JsonNode value, ObjectMapper decoder) {
        if (decoder == null || !value.isTextual()) {
            return value;
        }
        try {
            JsonNode parsed = decoder.readTree(value.textValue());
            if (parsed != null && (parsed.isObject() || parsed.isArray())) {
                return parsed;
            }
        } catch (IOException ignored) {
            // Not JSON: matched as the string it is, which a nested pattern never matches.
        }
        return value;
    }

    private static boolean matchesNested(JsonNode pattern, JsonNode value, ObjectMapper decoder) {
        if (value.isArray()) {
            for (JsonNode element : value) {
                if (matches(pattern, element, decoder)) {
                    return true;
                }
            }
            return false;
        }
        return matches(pattern, value, decoder);
    }

    /** Whether the field, absent as null, matches any of the alternatives. */
    private static boolean matchesAlternatives(String field, JsonNode alternatives, JsonNode value) {
        for (JsonNode alternative : alternatives) {
            if (alternative.isArray()) {
                throw new IllegalArgumentException("field " + field + " must list values and matchers, not lists");
            }
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
        throw new IllegalArgumentException("unsupported matcher " + alternative);
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
            throw new IllegalArgumentException("unsupported anything-but matcher " + excluded);
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
        if (!conditions.isArray() || conditions.isEmpty() || conditions.size() % 2 != 0) {
            throw new IllegalArgumentException("numeric takes pairs of a comparison and a number");
        }
        if (!value.isNumber()) {
            return false;
        }
        BigDecimal actual = value.decimalValue();
        for (int i = 0; i < conditions.size(); i += 2) {
            JsonNode operand = conditions.get(i + 1);
            if (!operand.isNumber()) {
                throw new IllegalArgumentException("numeric operand " + operand + " is not a number");
            }
            int compared = actual.compareTo(operand.decimalValue());
            boolean holds = switch (conditions.get(i).asText()) {
                case "=" -> compared == 0;
                case ">" -> compared > 0;
                case ">=" -> compared >= 0;
                case "<" -> compared < 0;
                case "<=" -> compared <= 0;
                default -> throw new IllegalArgumentException("unsupported numeric comparison " + conditions.get(i));
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
