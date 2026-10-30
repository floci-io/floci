package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.pipes.PipesFilterMatcher;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * An EventBridge V2 subscriber's {@code FilterConfiguration}: at most one EventBridge pattern for each scope of the
 * event, {@code DATA}, {@code METADATA} or {@code SYSTEM_METADATA}, every one of which an event must match.
 */
final class SubscriberFilters {

    static final String WILDCARD_MESSAGE =
            "Filter pattern must not contain wildcard matchers (wildcard, anything-but wildcard)";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SCOPES = Set.of("DATA", "METADATA", "SYSTEM_METADATA");
    private static final Set<String> NUMERIC_OPERATORS = Set.of("=", ">", ">=", "<", "<=");
    private static final int MAX_FILTERS = 50;
    private static final int MAX_PATTERN_BYTES = 4096;
    private static final int MAX_OR_COMBINATIONS = 1000;

    private SubscriberFilters() {
    }

    /**
     * Rejects a configuration AWS rejects: one with no filters or an empty pattern, two filters of one scope, a
     * pattern that is not JSON, a wildcard, a {@code METADATA} pattern of anything but exact values, a pattern
     * that is not an object, more than 1,000 {@code $or} combinations, or patterns over 4,096 bytes in all.
     */
    static void validate(JsonNode configuration) {
        if (!configuration.isObject()) {
            throw invalid("FilterConfiguration must be a structure.");
        }
        JsonNode language = configuration.get("Language");
        if (language != null && !language.isNull() && !"EVENT_BRIDGE_PATTERN".equals(language.asText())) {
            throw invalid("FilterConfiguration.Language must be EVENT_BRIDGE_PATTERN.");
        }
        JsonNode filters = configuration.get("Filters");
        if (filters == null || !filters.isArray() || filters.isEmpty() || filters.size() > MAX_FILTERS) {
            throw invalid("FilterConfiguration.Filters must hold 1 to " + MAX_FILTERS + " filters.");
        }
        Set<String> scopes = new HashSet<>();
        int bytes = 0;
        for (JsonNode filter : filters) {
            String scope = filter.path("Scope").asText(null);
            if (scope == null || !SCOPES.contains(scope)) {
                throw invalid("Filter Scope must be DATA, METADATA or SYSTEM_METADATA.");
            }
            if (!scopes.add(scope)) {
                throw invalid("FilterConfiguration may hold at most one filter for the scope " + scope + ".");
            }
            JsonNode patternText = filter.get("Pattern");
            if (patternText == null || !patternText.isTextual() || patternText.textValue().isBlank()) {
                throw invalid("Filter Pattern must be a non-empty event pattern.");
            }
            bytes += patternText.textValue().getBytes(StandardCharsets.UTF_8).length;
            JsonNode pattern = parse(patternText.textValue());
            if (!pattern.isObject()) {
                throw invalid("Filter pattern for the scope " + scope + " must be a JSON object.");
            }
            checkMatchers(pattern);
            if ("METADATA".equals(scope) && !exactValuesOnly(pattern)) {
                throw invalid("A METADATA filter pattern must give each key a list of exact values.");
            }
            if (orCombinations(pattern) > MAX_OR_COMBINATIONS) {
                throw invalid("Filter pattern produces more than " + MAX_OR_COMBINATIONS + " combinations.");
            }
        }
        if (bytes > MAX_PATTERN_BYTES) {
            throw invalid("FilterConfiguration patterns may total at most " + MAX_PATTERN_BYTES + " bytes.");
        }
    }

    /** Whether the event matches every filter of the configuration; an absent configuration matches every event. */
    static boolean matches(JsonNode configuration, PublishedEvent event) {
        if (configuration == null || configuration.isNull() || !configuration.has("Filters")) {
            return true;
        }
        for (JsonNode filter : configuration.get("Filters")) {
            JsonNode scope = switch (filter.path("Scope").asText()) {
                case "DATA" -> event.data();
                case "METADATA" -> event.metadata();
                default -> event.systemMetadata();
            };
            if (!PipesFilterMatcher.matches(parse(filter.path("Pattern").asText()), scope)) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode parse(String pattern) {
        try {
            return MAPPER.readTree(pattern);
        } catch (Exception e) {
            throw invalid("Filter pattern is not valid JSON.");
        }
    }

    /**
     * Refuses a wildcard, as AWS does, and a matcher {@link PipesFilterMatcher} cannot evaluate, which would
     * otherwise match no event or every event without a word. A matcher is an object in a field's list of values;
     * an object anywhere else is a nested pattern, so a field named after an operator is matched as a field.
     */
    private static void checkMatchers(JsonNode pattern) {
        for (Map.Entry<String, JsonNode> field : pattern.properties()) {
            JsonNode value = field.getValue();
            boolean or = "$or".equals(field.getKey());
            if (or && (!value.isArray() || value.size() < 2)) {
                throw invalid("Filter pattern $or must be a list of at least two patterns.");
            }
            if (value.isObject()) {
                checkMatchers(value);
            } else if (value.isArray()) {
                if (value.isEmpty()) {
                     throw invalid("Filter pattern field " + field.getKey() + " must contain at least one value.");
                 }
                for (JsonNode element : value) {
                    if (or && element.isObject()) {
                        checkMatchers(element);
                    } else if (or) {
                        throw invalid("Filter pattern $or must be a list of patterns.");
                    } else if (element.isObject()) {
                        checkMatcher(element);
                    } else if (element.isArray()) {
                        throw invalid("Filter pattern field " + field.getKey() + " must list values and matchers, "
                                + "not lists.");
                    }
                }
            } else {
                throw invalid("Filter pattern field " + field.getKey() + " must be a list of values or a nested "
                        + "pattern.");
            }
        }
    }

    private static void checkMatcher(JsonNode matcher) {
        JsonNode anythingBut = matcher.get("anything-but");
        if (matcher.has("wildcard") || anythingBut != null && anythingBut.has("wildcard")) {
            throw invalid(WILDCARD_MESSAGE);
        }
        String operator = matcher.size() == 1 ? matcher.fieldNames().next() : "";
        JsonNode operand = matcher.path(operator);
        boolean supported = switch (operator) {
            case "exists" -> operand.isBoolean();
            case "prefix", "suffix" -> operand.isTextual() || ignoringCase(operand);
            case "equals-ignore-case" -> operand.isTextual();
            case "numeric" -> numericConditions(operand);
            case "anything-but" -> operand.isValueNode()
                    || operand.isArray() && !operand.isEmpty() && allValues(operand)
                    || operand.size() == 1 && (operand.path("prefix").isTextual()
                            || operand.path("suffix").isTextual() || operand.path("equals-ignore-case").isTextual()
                            || operand.path("equals-ignore-case").isArray()
                                && !operand.path("equals-ignore-case").isEmpty()
                                && allValues(operand.get("equals-ignore-case")));
            default -> false;
        };
        if (!supported) {
            throw invalid("Floci does not evaluate the filter matcher " + matcher + "; it supports exact values, "
                    + "prefix, suffix, equals-ignore-case, anything-but, numeric, exists and $or.");
        }
    }

    /** Whether the operand is one or more pairs of a comparison operator and a number. */
    private static boolean numericConditions(JsonNode operand) {
        if (!operand.isArray() || operand.isEmpty() || operand.size() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < operand.size(); i += 2) {
            JsonNode operator = operand.get(i);
            if (!operator.isTextual() || !NUMERIC_OPERATORS.contains(operator.textValue())
                    || !operand.get(i + 1).isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static boolean ignoringCase(JsonNode operand) {
        return operand.isObject() && operand.size() == 1 && operand.path("equals-ignore-case").isTextual();
    }

    private static boolean allValues(JsonNode array) {
        for (JsonNode element : array) {
            if (!element.isValueNode()) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactValuesOnly(JsonNode pattern) {
        Iterator<Map.Entry<String, JsonNode>> fields = pattern.fields();
        while (fields.hasNext()) {
            JsonNode values = fields.next().getValue();
            if (!values.isArray() || values.isEmpty()) {
                return false;
            }
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The number of patterns the {@code $or} lists expand to. The branches of one {@code $or} are alternatives, so
     * their counts add up; the fields of a pattern must all match, so theirs multiply. Counting stops once it passes
     * the limit.
     */
    private static long orCombinations(JsonNode node) {
        if (!node.isObject()) {
            return 1;
        }
        long combinations = 1;
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            long fieldCombinations = 0;
            if ("$or".equals(field.getKey())) {
                for (JsonNode branch : field.getValue()) {
                    fieldCombinations += orCombinations(branch);
                    if (fieldCombinations > MAX_OR_COMBINATIONS) {
                        return fieldCombinations;
                    }
                }
            } else {
                fieldCombinations = orCombinations(field.getValue());
            }
            combinations *= Math.max(1, fieldCombinations);
            if (combinations > MAX_OR_COMBINATIONS) {
                return combinations;
            }
        }
        return combinations;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
