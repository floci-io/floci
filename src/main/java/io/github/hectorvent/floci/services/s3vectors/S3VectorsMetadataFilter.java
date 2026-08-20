package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class S3VectorsMetadataFilter {

    private static final S3VectorsMetadataFilter MATCH_ALL = new S3VectorsMetadataFilter(metadata -> true);

    private final FilterNode root;

    private S3VectorsMetadataFilter(FilterNode root) {
        this.root = root;
    }

    static S3VectorsMetadataFilter compile(Object filter) {
        if (filter == null) {
            return MATCH_ALL;
        }
        return new S3VectorsMetadataFilter(compileFilter(filter));
    }

    boolean matches(Map<String, Object> metadata) {
        return root.matches(metadata != null ? metadata : Map.of());
    }

    private static FilterNode compileFilter(Object filter) {
        if (!(filter instanceof Map<?, ?> clauses) || clauses.isEmpty()) {
            throw validation("A metadata filter must be a non-empty object.");
        }

        List<FilterNode> nodes = new ArrayList<>();
        for (Map.Entry<?, ?> clause : clauses.entrySet()) {
            if (!(clause.getKey() instanceof String key) || key.isEmpty()) {
                throw validation("Metadata filter keys must be non-empty strings.");
            }
            Operator operator = Operator.fromWireValue(key);
            if (operator == Operator.AND || operator == Operator.OR) {
                nodes.add(compileLogical(operator, clause.getValue()));
            } else if (key.startsWith("$")) {
                throw validation("Unsupported metadata filter operator: " + key);
            } else {
                nodes.add(compileField(key, clause.getValue()));
            }
        }
        return all(nodes);
    }

    private static FilterNode compileLogical(Operator operator, Object operand) {
        if (!(operand instanceof List<?> filters) || filters.isEmpty()) {
            throw validation(operator.wireValue + " requires a non-empty array of filters.");
        }
        List<FilterNode> nodes = new ArrayList<>();
        for (Object filter : filters) {
            nodes.add(compileFilter(filter));
        }
        return operator == Operator.AND ? all(nodes) : any(nodes);
    }

    private static FilterNode compileField(String field, Object predicate) {
        if (!(predicate instanceof Map<?, ?> operators)) {
            return comparison(field, Operator.EQ, compileValueMatcher(predicate, Operator.EQ));
        }
        if (operators.isEmpty()) {
            throw validation("The predicate for metadata field " + field + " must not be empty.");
        }

        List<FilterNode> nodes = new ArrayList<>();
        for (Map.Entry<?, ?> entry : operators.entrySet()) {
            if (!(entry.getKey() instanceof String wireValue)) {
                throw validation("Metadata predicate operators must be strings.");
            }
            Operator operator = Operator.fromWireValue(wireValue);
            if (operator == null) {
                throw validation("Unsupported metadata filter operator: " + wireValue);
            }
            nodes.add(compileOperator(field, operator, entry.getValue()));
        }
        return all(nodes);
    }

    private static FilterNode compileOperator(String field, Operator operator, Object operand) {
        return switch (operator) {
            case EQ, NE -> comparison(field, operator, compileValueMatcher(operand, operator));
            case GT, GTE, LT, LTE -> {
                if (!(operand instanceof Number number)) {
                    throw validation(operator.wireValue + " requires a numeric operand.");
                }
                BigDecimal expected = decimal(number, operator.wireValue + " operand");
                yield orderedComparison(field, operator, expected);
            }
            case IN, NIN -> compileMembership(field, operator, operand);
            case EXISTS -> {
                if (!(operand instanceof Boolean expected)) {
                    throw validation("$exists requires a boolean operand.");
                }
                yield metadata -> metadata.containsKey(field) == expected;
            }
            case AND, OR -> throw validation("Unsupported field predicate operator: " + operator.wireValue);
        };
    }

    private static FilterNode comparison(String field, Operator operator, ValueMatcher expected) {
        if (operator == Operator.EQ) {
            return metadata -> metadata.containsKey(field) && valueMatches(metadata.get(field), expected);
        }
        return metadata -> !metadata.containsKey(field) || !valueMatches(metadata.get(field), expected);
    }

    private static FilterNode orderedComparison(String field, Operator operator, BigDecimal expected) {
        return switch (operator) {
            case GT -> metadata -> compareNumber(metadata, field, expected, comparison -> comparison > 0);
            case GTE -> metadata -> compareNumber(metadata, field, expected, comparison -> comparison >= 0);
            case LT -> metadata -> compareNumber(metadata, field, expected, comparison -> comparison < 0);
            case LTE -> metadata -> compareNumber(metadata, field, expected, comparison -> comparison <= 0);
            case EQ, NE, IN, NIN, EXISTS, AND, OR ->
                    throw new IllegalArgumentException("Not an ordered comparison: " + operator.wireValue);
        };
    }

    private static boolean compareNumber(Map<String, Object> metadata, String field, BigDecimal expected,
                                         ComparisonMatcher matcher) {
        if (!metadata.containsKey(field)) {
            return false;
        }
        Object actual = metadata.get(field);
        if (!(actual instanceof Number number)) {
            return false;
        }
        return matcher.matches(decimal(number, "metadata field " + field).compareTo(expected));
    }

    private static FilterNode compileMembership(String field, Operator operator, Object operand) {
        if (!(operand instanceof List<?> candidates) || candidates.isEmpty()) {
            throw validation(operator.wireValue + " requires a non-empty array of primitive values.");
        }
        List<ValueMatcher> matchers = new ArrayList<>();
        for (Object candidate : candidates) {
            matchers.add(compileValueMatcher(candidate, operator));
        }

        boolean includeMatches = operator == Operator.IN;
        return metadata -> {
            if (!metadata.containsKey(field)) {
                return !includeMatches;
            }
            Object actual = metadata.get(field);
            boolean matched = false;
            for (ValueMatcher matcher : matchers) {
                if (valueMatches(actual, matcher)) {
                    matched = true;
                    break;
                }
            }
            return includeMatches == matched;
        };
    }

    private static boolean valueMatches(Object actual, ValueMatcher expected) {
        if (actual instanceof List<?> values) {
            for (Object value : values) {
                if (expected.matches(value)) {
                    return true;
                }
            }
            return false;
        }
        return expected.matches(actual);
    }

    private static ValueMatcher compileValueMatcher(Object value, Operator operator) {
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
            throw validation(operator.wireValue + " requires a string, number, or boolean operand.");
        }
        if (value instanceof Number number) {
            BigDecimal expected = decimal(number, operator.wireValue + " operand");
            return actual -> actual instanceof Number actualNumber
                    && decimal(actualNumber, "metadata value").compareTo(expected) == 0;
        }
        return actual -> actual != null && actual.getClass() == value.getClass() && actual.equals(value);
    }

    private static FilterNode all(List<FilterNode> nodes) {
        return metadata -> {
            for (FilterNode node : nodes) {
                if (!node.matches(metadata)) {
                    return false;
                }
            }
            return true;
        };
    }

    private static FilterNode any(List<FilterNode> nodes) {
        return metadata -> {
            for (FilterNode node : nodes) {
                if (node.matches(metadata)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static BigDecimal decimal(Number number, String description) {
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw validation(description + " must be a finite number.");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @FunctionalInterface
    private interface FilterNode {
        boolean matches(Map<String, Object> metadata);
    }

    @FunctionalInterface
    private interface ValueMatcher {
        boolean matches(Object actual);
    }

    @FunctionalInterface
    private interface ComparisonMatcher {
        boolean matches(int comparison);
    }

    private enum Operator {
        EQ("$eq"),
        NE("$ne"),
        GT("$gt"),
        GTE("$gte"),
        LT("$lt"),
        LTE("$lte"),
        IN("$in"),
        NIN("$nin"),
        EXISTS("$exists"),
        AND("$and"),
        OR("$or");

        private final String wireValue;

        Operator(String wireValue) {
            this.wireValue = wireValue;
        }

        private static Operator fromWireValue(String wireValue) {
            for (Operator operator : values()) {
                if (operator.wireValue.equals(wireValue)) {
                    return operator;
                }
            }
            return null;
        }
    }
}
