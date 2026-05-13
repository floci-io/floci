package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.UsageLine;

import java.util.HashSet;
import java.util.Set;

/**
 * Recursive evaluator for the AWS Cost Explorer {@code Expression} filter shape:
 * {@code {And, Or, Not, Dimensions, Tags, CostCategories}}.
 * <p>
 * MatchOptions supported:
 * <ul>
 *   <li>{@code EQUALS} — exact equality (default)</li>
 *   <li>{@code CASE_SENSITIVE} — paired with EQUALS, AWS treats it as the default</li>
 *   <li>{@code CASE_INSENSITIVE} — case-insensitive equality</li>
 * </ul>
 * Other AWS match options (e.g. {@code STARTS_WITH}, {@code GREATER_THAN_OR_EQUAL})
 * are accepted in the request but treated as EQUALS for now; full coverage can land
 * in a follow-up PR.
 */
final class FilterExpressionEvaluator {

    static boolean matches(JsonNode expression, UsageLine line) {
        if (expression == null || expression.isNull() || expression.isMissingNode()) {
            return true;
        }
        JsonNode and = expression.path("And");
        if (and.isArray() && !and.isEmpty()) {
            for (JsonNode child : and) {
                if (!matches(child, line)) {
                    return false;
                }
            }
            return true;
        }
        JsonNode or = expression.path("Or");
        if (or.isArray() && !or.isEmpty()) {
            for (JsonNode child : or) {
                if (matches(child, line)) {
                    return true;
                }
            }
            return false;
        }
        JsonNode not = expression.path("Not");
        if (not.isObject() && !not.isEmpty()) {
            return !matches(not, line);
        }
        JsonNode dimensions = expression.path("Dimensions");
        if (dimensions.isObject() && !dimensions.isEmpty()) {
            return matchDimensions(dimensions, line);
        }
        JsonNode tags = expression.path("Tags");
        if (tags.isObject() && !tags.isEmpty()) {
            return matchTags(tags, line);
        }
        JsonNode costCategories = expression.path("CostCategories");
        if (costCategories.isObject() && !costCategories.isEmpty()) {
            // No cost categories defined — never matches.
            return false;
        }
        return true;
    }

    private static boolean matchDimensions(JsonNode node, UsageLine line) {
        String key = node.path("Key").asText(null);
        if (key == null || key.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Dimensions filter requires a Key.", 400);
        }
        Set<String> values = readValues(node.path("Values"));
        Set<String> matchOptions = readValues(node.path("MatchOptions"));
        boolean caseInsensitive = matchOptions.contains("CASE_INSENSITIVE");
        String actual = dimensionValue(key, line);
        return valueMatches(actual, values, caseInsensitive);
    }

    private static boolean matchTags(JsonNode node, UsageLine line) {
        String key = node.path("Key").asText(null);
        if (key == null || key.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Tags filter requires a Key.", 400);
        }
        Set<String> values = readValues(node.path("Values"));
        Set<String> matchOptions = readValues(node.path("MatchOptions"));
        boolean caseInsensitive = matchOptions.contains("CASE_INSENSITIVE");
        String actual = line.tags() == null ? null : line.tags().get(key);
        return valueMatches(actual, values, caseInsensitive);
    }

    private static boolean valueMatches(String actual, Set<String> values, boolean caseInsensitive) {
        if (values.isEmpty()) {
            return actual != null;
        }
        if (actual == null) {
            return false;
        }
        if (caseInsensitive) {
            for (String v : values) {
                if (v.equalsIgnoreCase(actual)) {
                    return true;
                }
            }
            return false;
        }
        return values.contains(actual);
    }

    /** Maps a Cost Explorer dimension name to the equivalent {@link UsageLine} field. */
    static String dimensionValue(String dimension, UsageLine line) {
        return switch (dimension) {
            case "SERVICE", "SERVICE_CODE" -> line.service();
            case "REGION" -> line.region();
            case "USAGE_TYPE" -> line.usageType();
            case "OPERATION" -> line.operation();
            case "RECORD_TYPE" -> line.recordType();
            case "LINKED_ACCOUNT", "PAYER_ACCOUNT", "LINKED_ACCOUNT_NAME" -> line.linkedAccountId();
            case "RESOURCE_ID" -> line.resourceId();
            default -> null;
        };
    }

    private static Set<String> readValues(JsonNode arr) {
        Set<String> out = new HashSet<>();
        if (arr.isArray()) {
            for (JsonNode v : arr) {
                if (!v.isNull() && !v.asText().isEmpty()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    private FilterExpressionEvaluator() {}
}
