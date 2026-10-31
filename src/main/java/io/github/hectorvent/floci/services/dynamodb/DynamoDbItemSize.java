package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.VectorIndex;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Calculates DynamoDB item size and enforces the 400KB limit.
 */
final class DynamoDbItemSize {

    static final int MAX_ITEM_SIZE = 400 * 1024; // 400 KB = 409,600 bytes

    static final String UPDATE_EXCEEDED = "Item size to update has exceeded the maximum allowed size";

    /** What a vector index reads per vector component, whatever the written decimal text costs. */
    static final int VECTOR_COMPONENT_BYTES = 4;

    // An UpdateExpression is also measured as a statement: these per action, the target paths
    // with a shared prefix counted once, and every operand a SET reads or an ADD writes.
    // Every other surface, AttributeUpdates included, measures only the finished item.
    private static final int UPDATE_COST = 3;
    private static final int SET_OR_ADD_COST = 19;
    private static final int REMOVE_OR_DELETE_COST = 1;
    private static final int TARGET_CONTAINER_COST = 4;
    private static final int OPERAND_PATH_COST = 1;
    private static final int OPERAND_PATH_ELEMENT_COST = 14;
    private static final int PLUS_COST = 34;
    private static final int MINUS_COST = 37;
    private static final int LIST_APPEND_COST = 37;
    private static final int IF_NOT_EXISTS_COST = 39;
    private static final int CONTAINER_COST = 3;
    private static final int MAX_NUMBER_PAIRS = 20;

    private DynamoDbItemSize() {}

    /**
     * Validates the item fits within 400KB. Throws ValidationException if not.
     */
    static void validateSize(JsonNode item) {
        int size = calculateItemSize(item);
        if (size > MAX_ITEM_SIZE) {
            throw new AwsException("ValidationException",
                    "Item size has exceeded the maximum allowed size", 400);
        }
    }

    static boolean updatedItemWithinLimit(JsonNode item) {
        return calculateItemSize(item) <= MAX_ITEM_SIZE;
    }

    static boolean updateItemWithinLimit(JsonNode item, int statementSize) {
        return calculateItemSize(item) <= MAX_ITEM_SIZE && statementSize <= MAX_ITEM_SIZE;
    }

    static int updateStatementSize(String expression, Function<String, List<Object>> paths, JsonNode values) {
        int size = UPDATE_COST;
        PathTree targets = new PathTree();
        String remaining = expression.trim().replaceAll("\\s+", " ");
        while (!remaining.isEmpty()) {
            String keyword = remaining.substring(0, Math.max(remaining.indexOf(' '), 0)).toUpperCase(Locale.ROOT);
            String body = remaining.substring(keyword.length()).trim();
            int nextClause = DynamoDbService.findNextClauseKeyword(body);
            String actions = nextClause < 0 ? body : body.substring(0, nextClause);
            remaining = nextClause < 0 ? "" : body.substring(nextClause);
            while (!actions.isBlank()) {
                int comma = DynamoDbService.findNextComma(actions);
                String action = (comma < 0 ? actions : actions.substring(0, comma)).trim();
                size += actionSize(keyword, action, targets, paths, values);
                actions = comma < 0 ? "" : actions.substring(comma + 1);
            }
        }
        return size + targets.size();
    }

    private static int actionSize(String clause, String action, PathTree targets,
                                  Function<String, List<Object>> paths, JsonNode values) {
        return switch (clause) {
            case "SET" -> {
                int assignment = action.indexOf('=');
                targets.add(paths.apply(action.substring(0, assignment)));
                yield SET_OR_ADD_COST + operandSize(action.substring(assignment + 1), paths, values);
            }
            case "ADD" -> {
                String[] parts = action.split(" ", 2);
                targets.add(paths.apply(parts[0]));
                yield SET_OR_ADD_COST + operandSize(parts[1], paths, values);
            }
            default -> {
                targets.add(paths.apply(action.split(" ", 2)[0]));
                yield REMOVE_OR_DELETE_COST;
            }
        };
    }

    private static int operandSize(String operand, Function<String, List<Object>> paths, JsonNode values) {
        String expr = DynamoDbService.stripOuterParens(operand);
        int operator = DynamoDbService.findArithmeticOperator(expr);
        if (operator >= 0) {
            return (expr.charAt(operator) == '+' ? PLUS_COST : MINUS_COST)
                    + operandSize(expr.substring(0, operator), paths, values)
                    + operandSize(expr.substring(operator + 1), paths, values);
        }
        String lower = expr.toLowerCase(Locale.ROOT);
        if (DynamoDbService.startsWithFunctionCall(lower, "list_append")) {
            return LIST_APPEND_COST + argumentsSize(expr, paths, values);
        }
        if (DynamoDbService.startsWithFunctionCall(lower, "if_not_exists")) {
            return IF_NOT_EXISTS_COST + argumentsSize(expr, paths, values);
        }
        if (expr.startsWith(":")) {
            return values == null ? 0 : attributeValueSize(values.get(expr));
        }
        int size = OPERAND_PATH_COST;
        for (Object element : paths.apply(expr)) {
            size += OPERAND_PATH_ELEMENT_COST + pathElementSize(element);
        }
        return size;
    }

    private static int argumentsSize(String call, Function<String, List<Object>> paths, JsonNode values) {
        String arguments = call.substring(call.indexOf('(') + 1, call.lastIndexOf(')'));
        int size = 0;
        while (!arguments.isBlank()) {
            int comma = DynamoDbService.findNextComma(arguments);
            size += operandSize(comma < 0 ? arguments : arguments.substring(0, comma), paths, values);
            arguments = comma < 0 ? "" : arguments.substring(comma + 1);
        }
        return size;
    }

    // A list index counts its decimal digits.
    private static int pathElementSize(Object element) {
        return element instanceof Long index ? Long.toString(index).length() : utf8Length((String) element);
    }

    private static final class PathTree {

        private final Map<Object, PathTree> children = new LinkedHashMap<>();

        void add(List<Object> path) {
            PathTree node = this;
            for (Object element : path) {
                node = node.children.computeIfAbsent(element, ignored -> new PathTree());
            }
        }

        int size() {
            int size = 0;
            for (Map.Entry<Object, PathTree> child : children.entrySet()) {
                PathTree nested = child.getValue();
                size += pathElementSize(child.getKey())
                        + (nested.children.isEmpty() ? 0 : TARGET_CONTAINER_COST + nested.size());
            }
            return size;
        }
    }

    static int billedItemSize(TableDefinition table, JsonNode item) {
        return billedItemSize(table, item, calculateItemSize(item));
    }

    /**
     * The size a read or write of the base table item is billed at. Once a vector index is
     * ACTIVE, the attribute it reads costs its name plus four bytes per dimension there, even
     * when an item written before the index holds something other than a vector. The 400KB
     * limit and every secondary index copy still count what the attribute holds.
     */
    static int billedItemSize(TableDefinition table, JsonNode item, int storedSize) {
        List<VectorIndex> indexes = table.getVectorIndexes();
        if (item == null || indexes.isEmpty()) {
            return storedSize;
        }
        int size = storedSize;
        Set<String> counted = new HashSet<>();
        for (VectorIndex index : indexes) {
            String name = index.getVectorAttributeName();
            JsonNode vector = item.get(name);
            if (vector != null && "ACTIVE".equals(index.getIndexStatus()) && counted.add(name)) {
                size += (int) (VECTOR_COMPONENT_BYTES * index.getDimensions()) - attributeValueSize(vector);
            }
        }
        return size;
    }

    static int calculateItemSize(JsonNode item) {
        if (item == null || !item.isObject()) return 0;
        int total = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            total += utf8Length(entry.getKey());
            total += attributeValueSize(entry.getValue());
        }
        return total;
    }

    static int attributeValueSize(JsonNode attr) {
        if (attr == null) return 0;
        if (attr.has("S")) return utf8Length(attr.get("S").asText());
        if (attr.has("N")) {
            return numberSize(attr.get("N").asText());
        }
        if (attr.has("B")) return binarySize(attr.get("B").asText());
        if (attr.has("BOOL")) return 1;
        if (attr.has("NULL")) return 1;
        if (attr.has("SS")) {
            int size = 0;
            for (JsonNode e : attr.get("SS")) size += utf8Length(e.asText());
            return size;
        }
        if (attr.has("NS")) {
            int size = 0;
            for (JsonNode e : attr.get("NS")) {
                size += numberSize(e.asText());
            }
            return size;
        }
        if (attr.has("BS")) {
            int size = 0;
            for (JsonNode e : attr.get("BS")) size += binarySize(e.asText());
            return size;
        }
        if (attr.has("L")) {
            int size = CONTAINER_COST;
            for (JsonNode e : attr.get("L")) size += 1 + attributeValueSize(e);
            return size;
        }
        if (attr.has("M")) {
            int size = CONTAINER_COST;
            Iterator<Map.Entry<String, JsonNode>> fields = attr.get("M").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                size += utf8Length(entry.getKey()) + 1 + attributeValueSize(entry.getValue());
            }
            return size;
        }
        return 0;
    }

    // DynamoDB stores a number as base-100 digits paired from the decimal point, plus one byte,
    // plus one more for a negative value below the 20-pair maximum.
    static int numberSize(String number) {
        BigDecimal value;
        try {
            value = new BigDecimal(number).stripTrailingZeros();
        } catch (NumberFormatException | ArithmeticException ignored) {
            // A number BigDecimal cannot hold is never stored, so its size never matters.
            return number.length();
        }
        if (value.signum() == 0) {
            return 1;
        }
        long lowestPower = -(long) value.scale();
        long highestPower = value.precision() + lowestPower - 1;
        long pairs = Math.floorDiv(highestPower, 2) - Math.floorDiv(lowestPower, 2) + 1;
        return (int) (1 + pairs + (value.signum() < 0 && pairs < MAX_NUMBER_PAIRS ? 1 : 0));
    }

    static int utf8Length(String s) {
        if (s == null) return 0;
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int binarySize(String base64) {
        if (base64 == null || base64.isEmpty()) return 0;
        try {
            return Base64.getDecoder().decode(base64).length;
        } catch (Exception e) {
            // Fallback: estimate from base64 length
            return (int) (base64.length() * 3L / 4);
        }
    }
}
