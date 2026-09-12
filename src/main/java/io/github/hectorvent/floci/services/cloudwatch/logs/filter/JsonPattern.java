package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pattern in curly braces over a JSON log event: conditions on property selectors, each a
 * comparison with a literal, {@code IS NULL}, {@code IS TRUE}, {@code IS FALSE} or
 * {@code NOT EXISTS}, joined with {@code &&} and {@code ||}. A comparison holds when any node the
 * selector points at is a scalar that satisfies it; an object, an array or a missing property
 * satisfies none, as AWS documents.
 */
final class JsonPattern extends FilterPattern {

    private static final ObjectReader JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(JsonNode.class);

    private final Condition<JsonNode> condition;
    private final List<JsonSelector> selectors;
    private final int regexes;

    private JsonPattern(Condition<JsonNode> condition, List<JsonSelector> selectors, int regexes) {
        this.condition = condition;
        this.selectors = selectors;
        this.regexes = regexes;
    }

    static JsonPattern of(String text) {
        PatternCursor cursor = new PatternCursor(text);
        cursor.expect('{');
        List<JsonSelector> selectors = new ArrayList<>();
        int[] regexes = {0};
        Condition<JsonNode> condition = Condition.parse(cursor, c -> atom(c, selectors, regexes));
        cursor.expect('}');
        cursor.expectEnd();
        return new JsonPattern(condition, List.copyOf(selectors), regexes[0]);
    }

    private static Condition<JsonNode> atom(PatternCursor cursor, List<JsonSelector> selectors, int[] regexes) {
        JsonSelector selector = JsonSelector.read(cursor);
        if (selectors.stream().noneMatch(s -> s.text().equals(selector.text()))) {
            selectors.add(selector);
        }
        cursor.skipWhitespace();
        if (Character.isLetter(cursor.peek())) {
            String keyword = cursor.identifier().toUpperCase();
            if (keyword.equals("IS")) {
                String state = cursor.identifier().toUpperCase();
                return switch (state) {
                    case "NULL" -> root -> selector.resolve(root).stream().anyMatch(JsonNode::isNull);
                    case "TRUE" -> root -> selector.resolve(root).stream()
                            .anyMatch(node -> node.isBoolean() && node.booleanValue());
                    case "FALSE" -> root -> selector.resolve(root).stream()
                            .anyMatch(node -> node.isBoolean() && !node.booleanValue());
                    default -> throw cursor.error("IS must be followed by NULL, TRUE or FALSE");
                };
            }
            if (keyword.equals("NOT")) {
                if (!cursor.identifier().equalsIgnoreCase("EXISTS")) {
                    throw cursor.error("NOT must be followed by EXISTS");
                }
                return root -> selector.resolve(root).isEmpty();
            }
            throw cursor.error("expected a comparison operator, IS or NOT EXISTS");
        }
        Literal.Operator op = Literal.Operator.read(cursor);
        Literal literal = Literal.read(cursor, "&|)}");
        if (literal.isRegex()) {
            regexes[0]++;
        }
        return root -> selector.resolve(root).stream()
                .anyMatch(node -> node.isValueNode() && !node.isNull() && literal.test(op, node.asText()));
    }

    @Override
    public Kind kind() {
        return Kind.JSON;
    }

    @Override
    public FilterMatch match(String message) {
        JsonNode root;
        try {
            root = message == null ? null : JSON.readValue(message);
        } catch (JsonProcessingException e) {
            return FilterMatch.NONE;
        }
        if (root == null || !(root.isObject() || root.isArray()) || !condition.test(root)) {
            return FilterMatch.NONE;
        }
        Map<String, String> extracted = new LinkedHashMap<>();
        for (JsonSelector selector : selectors) {
            String value = selector.firstScalar(root);
            if (value != null) {
                extracted.put(selector.text(), value);
            }
        }
        return FilterMatch.of(extracted, reference -> {
            JsonSelector selector = JsonSelector.parse(reference);
            return selector == null ? null : selector.firstScalar(root);
        });
    }

    @Override
    public boolean declaresField(String reference) {
        return JsonSelector.parse(reference) != null;
    }

    @Override
    int regexCount() {
        return regexes;
    }
}
