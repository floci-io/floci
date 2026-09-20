package io.github.hectorvent.floci.services.cloudwatch.logs.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.Map;

/**
 * A pattern in curly braces over a JSON log event: conditions on property selectors, each a
 * comparison with a literal, {@code IS NULL}, {@code IS TRUE}, {@code IS FALSE} or
 * {@code NOT EXISTS}, joined with {@code &&} and {@code ||}. A comparison holds when any node the
 * selector points at is a scalar that satisfies it; an object, an array or a missing property
 * satisfies no scalar comparison. Wildcard inequality excludes a match if any selected value
 * equals the literal.
 */
final class JsonPattern extends FilterPattern {

    private static final ObjectReader JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(JsonNode.class);

    /** AWS's quota on wildcard selectors in one filter pattern. */
    private static final int MAX_WILDCARDS = 3;

    private final Condition<JsonNode> condition;
    private final int regexes;

    private JsonPattern(Condition<JsonNode> condition, int regexes) {
        this.condition = condition;
        this.regexes = regexes;
    }

    static JsonPattern of(String text) {
        PatternCursor cursor = new PatternCursor(text);
        cursor.expect('{');
        int[] regexes = {0};
        int[] wildcards = {0};
        Condition<JsonNode> condition = Condition.parse(cursor, c -> atom(c, regexes, wildcards));
        if (wildcards[0] > MAX_WILDCARDS) {
            throw new FilterPatternException("Invalid filter pattern: at most " + MAX_WILDCARDS
                    + " wildcard selectors are allowed in a filter pattern");
        }
        cursor.expect('}');
        cursor.expectEnd();
        return new JsonPattern(condition, regexes[0]);
    }

    private static Condition<JsonNode> atom(PatternCursor cursor, int[] regexes, int[] wildcards) {
        JsonSelector selector = JsonSelector.read(cursor);
        wildcards[0] += selector.wildcards();
        cursor.skipWhitespace();
        if (Character.isLetter(cursor.peek())) {
            String keyword = cursor.identifier().toUpperCase();
            if (keyword.equals("IS")) {
                String state = cursor.identifier().toUpperCase();
                return switch (state) {
                    case "NULL" -> root -> selector.resolve(root).stream()
                            .anyMatch(node -> isNull(node, selector.wildcards() == 0));
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
        literal.validateOperator(op);
        if (literal.isRegex()) {
            regexes[0]++;
        }
        if (op == Literal.Operator.NE && selector.wildcards() > 0) {
            return root -> selector.wildcardParentExists(root) && selector.resolve(root).stream()
                    .noneMatch(node -> literal.test(Literal.Operator.EQ, node));
        }
        return root -> selector.resolve(root).stream().anyMatch(node -> literal.test(op, node));
    }

    private static boolean isNull(JsonNode node, boolean includeArrayElements) {
        if (node.isNull()) {
            return true;
        }
        if (includeArrayElements && node.isArray()) {
            for (JsonNode element : node) {
                if (element.isNull()) {
                    return true;
                }
            }
        }
        return false;
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
        if (root == null || !root.isObject() || !condition.test(root)) {
            return FilterMatch.NONE;
        }
        return FilterMatch.of(Map.of(), reference -> {
            JsonSelector selector = JsonSelector.parse(reference);
            return selector == null ? null : selector.firstScalar(root);
        });
    }

    @Override
    public boolean declaresField(String reference) {
        return JsonSelector.parse(reference) != null;
    }

    @Override
    public boolean declaresSingleValueField(String reference) {
        JsonSelector selector = JsonSelector.parse(reference);
        return selector != null && selector.wildcards() == 0;
    }

    @Override
    public int regexCount() {
        return regexes;
    }
}
