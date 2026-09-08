package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.AndExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.BetweenExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.CompareExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.Expr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.FunctionCallExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.FunctionOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.InExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.NotExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.Operand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.OrExpr;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PathOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.PlaceholderOperand;
import io.github.hectorvent.floci.services.dynamodb.ExpressionEvaluator.TokenType;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DynamoDbAccessPathValidator {

    private static final String KEY_TYPE_MISMATCH =
            "One or more parameter values were invalid: Condition parameter type does not match schema type";
    private static final Set<TokenType> SORT_KEY_COMPARATORS = Set.of(
            TokenType.EQ, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE);
    private static final Set<String> LEGACY_SORT_KEY_COMPARATORS = Set.of(
            "EQ", "LT", "LE", "GT", "GE", "BETWEEN", "BEGINS_WITH");

    private DynamoDbAccessPathValidator() {}

    static String validateQuery(TableDefinition table, DynamoDbAccessPath accessPath, JsonNode keyConditions,
                                String keyConditionExpression, String filterExpression,
                                JsonNode queryFilter, JsonNode expressionAttributeNames,
                                JsonNode expressionAttributeValues) {
        String partitionKeyValuePlaceholder = null;
        if (keyConditionExpression != null) {
            partitionKeyValuePlaceholder = validateKeyConditionExpression(
                    table, accessPath, keyConditionExpression,
                    expressionAttributeNames, expressionAttributeValues);
        } else {
            validateLegacyKeyConditions(table, accessPath, keyConditions);
        }
        validateFilterExpression(accessPath, filterExpression, expressionAttributeNames);
        validateLegacyQueryFilter(accessPath, queryFilter);
        return partitionKeyValuePlaceholder;
    }

    static void validateSelection(TableDefinition table, DynamoDbAccessPath accessPath,
                                  String select, String projectionExpression,
                                  JsonNode attributesToGet, JsonNode expressionAttributeNames) {
        if ("ALL_PROJECTED_ATTRIBUTES".equals(select) && !accessPath.isIndex()) {
            throw validationException("Select type ALL_PROJECTED_ATTRIBUTES is not supported for query on a table");
        }
        if (projectionExpression != null && select != null && !"SPECIFIC_ATTRIBUTES".equals(select)) {
            throw validationException("Cannot use both Select and ProjectionExpression unless Select is SPECIFIC_ATTRIBUTES");
        }
        if (attributesToGet != null && select != null && !"SPECIFIC_ATTRIBUTES".equals(select)) {
            throw validationException("Cannot use both Select and AttributesToGet unless Select is SPECIFIC_ATTRIBUTES");
        }
        if (!accessPath.isGlobalSecondaryIndex() || "ALL".equals(accessPath.projectionType())) {
            return;
        }
        if ("ALL_ATTRIBUTES".equals(select)) {
            throw validationException("Select type ALL_ATTRIBUTES is not supported for global secondary index "
                    + accessPath.indexName() + " because its projection type is not ALL");
        }

        Set<String> requestedAttributes = new HashSet<>();
        if (projectionExpression != null) {
            requestedAttributes.addAll(ProjectionEvaluator.topLevelAttributes(
                    projectionExpression, expressionAttributeNames));
        }
        if (attributesToGet != null) {
            attributesToGet.forEach(attribute -> requestedAttributes.add(attribute.asText()));
        }

        Set<String> projectedAttributes = accessPath.projectedAttributeNames(table);
        requestedAttributes.removeAll(projectedAttributes);
        if (!requestedAttributes.isEmpty()) {
            throw validationException("One or more parameter values were invalid: Global secondary index "
                    + accessPath.indexName() + " does not project "
                    + String.join(", ", requestedAttributes.stream().sorted().toList()));
        }
    }

    private static String validateKeyConditionExpression(TableDefinition table,
                                                         DynamoDbAccessPath accessPath,
                                                         String expression, JsonNode names,
                                                         JsonNode values) {
        Expr root = parseExpression(expression, "KeyConditionExpression");

        List<Expr> conditions = root instanceof AndExpr and
                ? and.operands() : List.of(root);
        List<String> partitionKeys = accessPath.partitionKeyNames();
        Set<String> partitionKeySet = Set.copyOf(partitionKeys);
        List<String> sortKeys = accessPath.sortKeyNames();
        Set<String> conditionedPartitionKeys = new HashSet<>();
        String partitionKeyValuePlaceholder = null;
        Set<String> conditionedAttributes = new HashSet<>();
        Map<String, Boolean> sortKeyEqualities = new HashMap<>();

        for (Expr condition : conditions) {
            String attribute = conditionAttribute(condition, names);
            if (attribute != null && !conditionedAttributes.add(attribute)) {
                throw new AwsException("ValidationException",
                        "KeyConditionExpressions must only contain one condition per key", 400);
            }
            if (attribute != null && partitionKeySet.contains(attribute)) {
                if (!isPartitionKeyEquality(condition)) {
                    throw new AwsException("ValidationException", "Query key condition not supported", 400);
                }
                conditionedPartitionKeys.add(attribute);
                if (attribute.equals(partitionKeys.getFirst())) {
                    partitionKeyValuePlaceholder = ((PlaceholderOperand) ((CompareExpr) condition).right()).name();
                }
            } else if (attribute != null && sortKeys.contains(attribute)) {
                if (!isSupportedSortKeyCondition(condition)) {
                    throw new AwsException("ValidationException", "Query key condition not supported", 400);
                }
                sortKeyEqualities.put(attribute, isEqualityCondition(condition));
            } else {
                throw new AwsException("ValidationException", "Query key condition not supported", 400);
            }
            validateConditionValueTypes(table, attribute, condition, values);
        }

        if (!conditionedPartitionKeys.containsAll(partitionKeySet)) {
            String missing = partitionKeys.stream()
                    .filter(pk -> !conditionedPartitionKeys.contains(pk))
                    .findFirst().orElseThrow();
            throw new AwsException("ValidationException",
                    "Query condition missed key schema element: " + missing, 400);
        }
        validateCompositeSortKeyConditions(sortKeys, sortKeyEqualities);
        return partitionKeyValuePlaceholder;
    }

    private static void validateCompositeSortKeyConditions(List<String> sortKeys,
                                                            Map<String, Boolean> equalities) {
        for (int laterIndex = 1; laterIndex < sortKeys.size(); laterIndex++) {
            String laterSortKey = sortKeys.get(laterIndex);
            if (!equalities.containsKey(laterSortKey)) {
                continue;
            }
            for (int priorIndex = 0; priorIndex < laterIndex; priorIndex++) {
                String priorSortKey = sortKeys.get(priorIndex);
                if (!Boolean.TRUE.equals(equalities.get(priorSortKey))) {
                    throw validationException("RANGE key attributes " + priorSortKey
                            + " must have equality conditions specified in the query because a condition is present "
                            + "on key attribute " + laterSortKey);
                }
            }
        }
    }

    private static boolean isEqualityCondition(Expr condition) {
        return condition instanceof CompareExpr compare && compare.op() == TokenType.EQ;
    }

    private static boolean isPartitionKeyEquality(Expr condition) {
        return condition instanceof CompareExpr compare
                && compare.op() == TokenType.EQ
                && compare.right() instanceof PlaceholderOperand;
    }

    private static boolean isSupportedSortKeyCondition(Expr condition) {
        if (condition instanceof CompareExpr compare) {
            return SORT_KEY_COMPARATORS.contains(compare.op())
                    && compare.right() instanceof PlaceholderOperand;
        }
        if (condition instanceof BetweenExpr between) {
            return between.low() instanceof PlaceholderOperand
                    && between.high() instanceof PlaceholderOperand;
        }
        if (condition instanceof FunctionCallExpr function) {
            return "begins_with".equals(function.functionName())
                    && function.args().size() == 2
                    && function.args().get(1) instanceof PlaceholderOperand;
        }
        return false;
    }

    private static String conditionAttribute(Expr condition, JsonNode names) {
        Operand operand = switch (condition) {
            case CompareExpr compare -> compare.left();
            case BetweenExpr between -> between.value();
            case FunctionCallExpr function when "begins_with".equals(function.functionName())
                    && !function.args().isEmpty() -> function.args().getFirst();
            default -> null;
        };
        if (!(operand instanceof PathOperand path) || path.segments().size() != 1) {
            return null;
        }
        return topLevelAttribute(path, names);
    }

    private static void validateConditionValueTypes(TableDefinition table, String attribute,
                                                    Expr condition, JsonNode values) {
        String expectedType = attributeType(table, attribute);
        for (String placeholder : conditionValuePlaceholders(condition)) {
            if (values == null || !values.has(placeholder)) {
                throw validationException("Invalid KeyConditionExpression: An expression attribute value used "
                        + "in expression is not defined; attribute value: " + placeholder);
            }
            if (!isValidKeyValue(values.get(placeholder), expectedType)) {
                throw validationException(KEY_TYPE_MISMATCH);
            }
        }
    }

    private static List<String> conditionValuePlaceholders(Expr condition) {
        return switch (condition) {
            case CompareExpr compare when compare.right() instanceof PlaceholderOperand placeholder ->
                    List.of(placeholder.name());
            case BetweenExpr between
                    when between.low() instanceof PlaceholderOperand low
                    && between.high() instanceof PlaceholderOperand high ->
                    List.of(low.name(), high.name());
            case FunctionCallExpr function
                    when function.args().size() == 2
                    && function.args().get(1) instanceof PlaceholderOperand placeholder ->
                    List.of(placeholder.name());
            default -> List.of();
        };
    }

    private static void validateLegacyKeyConditions(TableDefinition table,
                                                    DynamoDbAccessPath accessPath,
                                                    JsonNode keyConditions) {
        List<String> partitionKeys = accessPath.partitionKeyNames();
        for (String partitionKey : partitionKeys) {
            if (keyConditions == null || !keyConditions.has(partitionKey)) {
                throw new AwsException("ValidationException",
                        "Query condition missed key schema element: " + partitionKey, 400);
            }
        }

        Set<String> partitionKeySet = Set.copyOf(partitionKeys);
        List<String> sortKeys = accessPath.sortKeyNames();
        Map<String, Boolean> sortKeyEqualities = new HashMap<>();
        keyConditions.fields().forEachRemaining(entry -> {
            String attribute = entry.getKey();
            String operator = entry.getValue().path("ComparisonOperator").asText();
            int values = entry.getValue().path("AttributeValueList").size();
            if (partitionKeySet.contains(attribute)) {
                if (!"EQ".equals(operator) || values != 1) {
                    throw new AwsException("ValidationException", "Query key condition not supported", 400);
                }
            } else if (!sortKeys.contains(attribute)
                    || !LEGACY_SORT_KEY_COMPARATORS.contains(operator)
                    || ("BETWEEN".equals(operator) ? values != 2 : values != 1)) {
                throw new AwsException("ValidationException", "Query key condition not supported", 400);
            } else {
                sortKeyEqualities.put(attribute, "EQ".equals(operator));
            }
            String expectedType = attributeType(table, attribute);
            entry.getValue().path("AttributeValueList").forEach(value -> {
                if (!isValidKeyValue(value, expectedType)) {
                    throw validationException(KEY_TYPE_MISMATCH);
                }
            });
        });
        validateCompositeSortKeyConditions(sortKeys, sortKeyEqualities);
    }

    private static String attributeType(TableDefinition table, String attribute) {
        List<AttributeDefinition> definitions = table.getAttributeDefinitions();
        if (definitions == null) {
            throw validationException(KEY_TYPE_MISMATCH);
        }
        return definitions.stream()
                .filter(definition -> attribute.equals(definition.getAttributeName()))
                .map(AttributeDefinition::getAttributeType)
                .findFirst()
                .orElseThrow(() -> validationException(KEY_TYPE_MISMATCH));
    }

    private static boolean isValidKeyValue(JsonNode value, String expectedType) {
        if (value == null || !value.isObject() || value.size() != 1 || !value.has(expectedType)) {
            return false;
        }
        JsonNode payload = value.get(expectedType);
        if (payload == null || !payload.isTextual() || payload.textValue().isEmpty()) {
            return false;
        }
        if ("N".equals(expectedType)) {
            DynamoDbNumberUtils.validateAndNormalize(payload.textValue());
        }
        if ("B".equals(expectedType)) {
            return payload.textValue().matches(
                    "(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");
        }
        return true;
    }

    private static void validateFilterExpression(DynamoDbAccessPath accessPath,
                                                 String expression, JsonNode names) {
        if (expression == null) {
            return;
        }
        Expr root = parseExpression(expression, "FilterExpression");

        Set<String> referenced = new HashSet<>();
        collectAttributes(root, names, referenced);
        for (String keyAttribute : accessPath.keyAttributeNames()) {
            if (referenced.contains(keyAttribute)) {
                throw validationException("Filter Expression can only contain non-primary key attributes: "
                        + "Primary key attribute: " + keyAttribute);
            }
        }
    }

    private static void validateLegacyQueryFilter(DynamoDbAccessPath accessPath, JsonNode queryFilter) {
        if (queryFilter == null) {
            return;
        }
        for (String keyAttribute : accessPath.keyAttributeNames()) {
            if (queryFilter.has(keyAttribute)) {
                throw validationException("QueryFilter can only contain non-primary key attributes: "
                        + "Primary key attribute: " + keyAttribute);
            }
        }
    }

    private static void collectAttributes(Expr expression, JsonNode names, Set<String> attributes) {
        switch (expression) {
            case AndExpr and -> and.operands().forEach(item -> collectAttributes(item, names, attributes));
            case OrExpr or -> or.operands().forEach(item -> collectAttributes(item, names, attributes));
            case NotExpr not -> collectAttributes(not.operand(), names, attributes);
            case CompareExpr compare -> {
                collectAttributes(compare.left(), names, attributes);
                collectAttributes(compare.right(), names, attributes);
            }
            case BetweenExpr between -> {
                collectAttributes(between.value(), names, attributes);
                collectAttributes(between.low(), names, attributes);
                collectAttributes(between.high(), names, attributes);
            }
            case InExpr in -> {
                collectAttributes(in.value(), names, attributes);
                in.candidates().forEach(item -> collectAttributes(item, names, attributes));
            }
            case FunctionCallExpr function ->
                    function.args().forEach(item -> collectAttributes(item, names, attributes));
        }
    }

    private static void collectAttributes(Operand operand, JsonNode names, Set<String> attributes) {
        if (operand instanceof PathOperand path) {
            String attribute = topLevelAttribute(path, names);
            if (attribute != null) {
                attributes.add(attribute);
            }
        } else if (operand instanceof FunctionOperand function) {
            function.args().forEach(item -> collectAttributes(item, names, attributes));
        }
    }

    private static String topLevelAttribute(Operand operand, JsonNode names) {
        if (!(operand instanceof PathOperand path) || path.segments().isEmpty()) {
            return null;
        }
        String segment = path.segments().getFirst();
        if (segment.startsWith("#") && names != null && names.has(segment)) {
            return names.get(segment).asText();
        }
        return segment;
    }

    private static Expr parseExpression(String expression, String expressionType) {
        try {
            return ExpressionEvaluator.parse(expression);
        } catch (IllegalArgumentException e) {
            String detail = e.getMessage();
            String message = "Invalid " + expressionType + ": Syntax error";
            if (detail != null && detail.startsWith("token:")) {
                message += "; " + detail;
            }
            throw validationException(message);
        }
    }

    private static AwsException validationException(String message) {
        return new AwsException("ValidationException", message, 400);
    }
    // DynamoDB requires an ExclusiveStartKey to exactly match the paged key schema's
    // attribute names and scalar types. Query and Scan report different messages for faults.
    static void validateExclusiveStartKey(JsonNode exclusiveStartKey, TableDefinition table,
                                          DynamoDbAccessPath accessPath, boolean isScan) {
        if (exclusiveStartKey == null || exclusiveStartKey.isNull()) return;
        Set<String> expected = new HashSet<>();
        expected.add(table.getPartitionKeyName());
        String sortKey = table.getSortKeyName();
        if (sortKey != null) {
            expected.add(sortKey);
        }
        if (accessPath.isIndex()) {
            expected.addAll(accessPath.keyAttributeNames());
        }
        Set<String> actual = new HashSet<>();
        exclusiveStartKey.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected) || hasInvalidKeyValue(exclusiveStartKey, table, expected)) {
            throw invalidStartingKey(isScan);
        }
    }

    private static boolean hasInvalidKeyValue(JsonNode exclusiveStartKey, TableDefinition table,
                                              Set<String> expected) {
        if (table.getAttributeDefinitions() == null) {
            return true;
        }
        for (String attribute : expected) {
            String expectedType = table.getAttributeDefinitions().stream()
                    .filter(definition -> attribute.equals(definition.getAttributeName()))
                    .map(AttributeDefinition::getAttributeType)
                    .findFirst()
                    .orElse(null);
            if (expectedType == null || hasInvalidScalarValue(exclusiveStartKey.get(attribute), expectedType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInvalidScalarValue(JsonNode value, String expectedType) {
        if (value == null || !value.isObject() || value.size() != 1 || !value.has(expectedType)) {
            return true;
        }
        JsonNode payload = value.get(expectedType);
        if (payload == null || !payload.isTextual() || payload.textValue().isEmpty()) {
            return true;
        }
        if ("N".equals(expectedType)) {
            DynamoDbNumberUtils.validateAndNormalize(payload.textValue());
        }
        if ("B".equals(expectedType)) {
            return !payload.textValue().matches(
                    "(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");
        }
        return false;
    }

    private static AwsException invalidStartingKey(boolean isScan) {
        return new AwsException("ValidationException", isScan
                ? "The provided starting key is invalid: The provided key element does not match the schema"
                : "The provided starting key is invalid", 400);
    }
}
