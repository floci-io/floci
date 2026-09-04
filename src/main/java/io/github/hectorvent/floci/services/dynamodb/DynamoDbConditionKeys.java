package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the DynamoDB IAM condition keys - {@code dynamodb:LeadingKeys},
 * {@code dynamodb:Attributes} and {@code dynamodb:Select} - from a request body.
 *
 * <p>Public and static, following the precedent of {@link DynamoDbPartiQLParser}, which the
 * IAM package already calls across the package boundary.
 *
 * <p>Everything is best effort. Whatever cannot be determined is simply absent from the
 * result: an unresolvable leading key produces an empty list, the condition key is then
 * omitted from the request context, and a policy that scopes access through it fails closed.
 * Note that this "fail closed" behavior applies to {@code dynamodb:LeadingKeys}; for
 * {@code dynamodb:Attributes} evaluated under {@code ForAllValues}, under-reporting is the
 * unsafe direction because omitting a referenced attribute could allow an unauthorized
 * operation to pass.
 */
public final class DynamoDbConditionKeys {

    private DynamoDbConditionKeys() {}

    /**
     * @param action the IAM action, e.g. {@code dynamodb:GetItem}
     * @param body   the parsed request body; may be {@code null}
     * @param table  the target table's definition, used only for the HASH key name; may be
     *               {@code null}, in which case no leading keys are produced
     */
    public static Result extract(String action, JsonNode body, TableDefinition table) {
        if (body == null || !body.isObject()) {
            return new Result(List.of(), List.of(), null);
        }
        String pkName = partitionKeyName(table);
        List<String> leadingKeys = new ArrayList<>();
        Set<String> attributes = new LinkedHashSet<>();

        switch (action == null ? "" : action) {
            case "dynamodb:GetItem", "dynamodb:DeleteItem", "dynamodb:UpdateItem" -> {
                JsonNode key = body.get("Key");
                addAttributeNames(attributes, key);
                addLeadingKey(leadingKeys, key, pkName);
            }
            case "dynamodb:PutItem" -> {
                JsonNode item = body.get("Item");
                addAttributeNames(attributes, item);
                addLeadingKey(leadingKeys, item, pkName);
            }
            case "dynamodb:Query" -> {
                String indexName = textOrNull(body.get("IndexName"));
                String queryPkName = partitionKeyName(table, indexName);
                String value = DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                        textOrNull(body.get("KeyConditionExpression")),
                        body.get("ExpressionAttributeNames"),
                        body.get("ExpressionAttributeValues"),
                        queryPkName);
                if (value == null) {
                    value = legacyKeyConditionEqualityValue(body.get("KeyConditions"), queryPkName);
                }
                if (value != null) {
                    leadingKeys.add(value);
                }
                // Legacy map forms of the same clauses; the expression forms are covered below.
                addAttributeNames(attributes, body.get("KeyConditions"));
                addAttributeNames(attributes, body.get("QueryFilter"));
            }
            case "dynamodb:BatchGetItem" -> {
                for (JsonNode tableRequest : requestItemsFor(body, table)) {
                    JsonNode keys = tableRequest.get("Keys");
                    if (keys != null && keys.isArray()) {
                        for (JsonNode key : keys) {
                            addAttributeNames(attributes, key);
                            addLeadingKey(leadingKeys, key, pkName);
                        }
                    }
                    addAttributesToGet(attributes, tableRequest.get("AttributesToGet"));
                    addProjectionAttributes(attributes,
                            textOrNull(tableRequest.get("ProjectionExpression")),
                            tableRequest.get("ExpressionAttributeNames"));
                }
            }
            case "dynamodb:BatchWriteItem" -> {
                for (JsonNode tableRequest : requestItemsFor(body, table)) {
                    if (!tableRequest.isArray()) {
                        continue;
                    }
                    for (JsonNode write : tableRequest) {
                        JsonNode put = write.path("PutRequest").get("Item");
                        addAttributeNames(attributes, put);
                        addLeadingKey(leadingKeys, put, pkName);
                        JsonNode delete = write.path("DeleteRequest").get("Key");
                        addAttributeNames(attributes, delete);
                        addLeadingKey(leadingKeys, delete, pkName);
                    }
                }
            }
            default -> {
                // Scan and everything else: no leading keys. Attributes and Select below
                // still apply where the body carries them.
            }
        }

        addAttributesToGet(attributes, body.get("AttributesToGet"));
        addProjectionAttributes(attributes, textOrNull(body.get("ProjectionExpression")),
                body.get("ExpressionAttributeNames"));
        addUpdateExpressionAttributes(attributes, textOrNull(body.get("UpdateExpression")),
                body.get("ExpressionAttributeNames"));
        addExpressionAttributeNameValues(attributes, body.get("ExpressionAttributeNames"));

        // Names an expression references are attributes the request touches too. Under-reporting
        // these is the unsafe direction: a Query projecting only allowed attributes but filtering
        // on a forbidden one would still slip past a ForAllValues:StringEquals on dynamodb:Attributes.
        JsonNode exprAttrNames = body.get("ExpressionAttributeNames");
        addExpressionAttributes(attributes, textOrNull(body.get("KeyConditionExpression")), exprAttrNames);
        addExpressionAttributes(attributes, textOrNull(body.get("FilterExpression")), exprAttrNames);
        addExpressionAttributes(attributes, textOrNull(body.get("ConditionExpression")), exprAttrNames);

        // Legacy map forms naming attributes being read or updated.
        addAttributeNames(attributes, body.get("AttributeUpdates"));
        addAttributeNames(attributes, body.get("Expected"));
        addAttributeNames(attributes, body.get("ScanFilter"));

        return new Result(List.copyOf(leadingKeys), List.copyOf(attributes),
                textOrNull(body.get("Select")));
    }

    /**
     * The extracted keys. {@code leadingKeys} holds partition-key values in request order
     * (duplicates preserved), {@code attributes} holds attribute names in first-seen order
     * with duplicates removed, {@code select} is the raw Select value or {@code null}.
     */
    public record Result(List<String> leadingKeys, List<String> attributes, String select) {}

    // -- Helpers -----------------------------------------------------------------

    private static String partitionKeyName(TableDefinition table) {
        return partitionKeyName(table, null);
    }

    private static String partitionKeyName(TableDefinition table, String indexName) {
        if (table == null) {
            return null;
        }
        if (indexName != null) {
            if (table.getGlobalSecondaryIndexes() != null) {
                for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
                    if (indexName.equals(gsi.getIndexName())) {
                        return hashKeyName(gsi.getKeySchema());
                    }
                }
            }
            if (table.getLocalSecondaryIndexes() != null) {
                for (LocalSecondaryIndex lsi : table.getLocalSecondaryIndexes()) {
                    if (indexName.equals(lsi.getIndexName())) {
                        return hashKeyName(lsi.getKeySchema());
                    }
                }
            }
        }
        return hashKeyName(table.getKeySchema());
    }

    private static String hashKeyName(List<KeySchemaElement> schema) {
        if (schema == null) {
            return null;
        }
        return schema.stream()
                .filter(key -> "HASH".equals(key.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElse(null);
    }

    /**
     * The RequestItems entries this request touches. Prefers the entry named by the resolved
     * table, because only that table's HASH key name is known; falls back to every entry when
     * nothing matches, so a stub or an aliased name still yields something.
     */
    private static List<JsonNode> requestItemsFor(JsonNode body, TableDefinition table) {
        JsonNode requestItems = body.get("RequestItems");
        if (requestItems == null || !requestItems.isObject()) {
            return List.of();
        }
        String tableName = table == null ? null : table.getTableName();
        if (tableName != null && requestItems.has(tableName)) {
            return List.of(requestItems.get(tableName));
        }
        List<JsonNode> all = new ArrayList<>();
        requestItems.elements().forEachRemaining(all::add);
        return all;
    }

    private static void addLeadingKey(List<String> leadingKeys, JsonNode attributeMap, String pkName) {
        if (attributeMap == null || !attributeMap.isObject() || pkName == null) {
            return;
        }
        String value = scalarValue(attributeMap.get(pkName));
        if (value != null) {
            leadingKeys.add(value);
        }
    }

    private static void addAttributeNames(Set<String> attributes, JsonNode attributeMap) {
        if (attributeMap == null || !attributeMap.isObject()) {
            return;
        }
        Iterator<String> names = attributeMap.fieldNames();
        while (names.hasNext()) {
            attributes.add(names.next());
        }
    }

    private static void addAttributesToGet(Set<String> attributes, JsonNode attributesToGet) {
        if (attributesToGet == null || !attributesToGet.isArray()) {
            return;
        }
        for (JsonNode attribute : attributesToGet) {
            if (attribute.isTextual()) {
                attributes.add(attribute.asText());
            }
        }
    }

    private static void addProjectionAttributes(Set<String> attributes, String projectionExpression,
                                                JsonNode exprAttrNames) {
        if (projectionExpression == null || projectionExpression.isBlank()) {
            return;
        }
        try {
            attributes.addAll(ProjectionEvaluator.topLevelAttributes(projectionExpression, exprAttrNames));
        } catch (RuntimeException e) {
            // A malformed projection is the request handler's problem to report; for condition
            // keys it just means those attribute names stay unknown.
        }
    }

    private static void addExpressionAttributeNameValues(Set<String> attributes, JsonNode exprAttrNames) {
        if (exprAttrNames == null || !exprAttrNames.isObject()) {
            return;
        }
        exprAttrNames.elements().forEachRemaining(value -> {
            if (value.isTextual()) {
                attributes.add(value.asText());
            }
        });
    }

    private static final Set<String> UPDATE_EXPRESSION_KEYWORDS = Set.of(
            "set", "remove", "add", "delete", "if_not_exists", "list_append"
    );
    private static final Pattern UPDATE_EXPRESSION_ATTRIBUTE_PATTERN =
            Pattern.compile("(?<![.#a-zA-Z0-9_:'\"])(#?[a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * Top-level attribute names an UpdateExpression reads or writes. Captures LHS targets of
     * SET/REMOVE/ADD/DELETE and RHS operands (such as attributes in arithmetic, list_append,
     * or if_not_exists).
     */
    private static void addUpdateExpressionAttributes(Set<String> attributes, String updateExpression,
                                                      JsonNode exprAttrNames) {
        if (updateExpression == null || updateExpression.isBlank()) {
            return;
        }
        Matcher matcher = UPDATE_EXPRESSION_ATTRIBUTE_PATTERN.matcher(updateExpression);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (UPDATE_EXPRESSION_KEYWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String resolved = resolveAlias(token, exprAttrNames);
            if (resolved != null && !resolved.startsWith("#")) {
                attributes.add(resolved);
            }
        }
    }

    /** The EQ value a legacy {@code KeyConditions} map pins the partition key to, or {@code null}. */
    private static String legacyKeyConditionEqualityValue(JsonNode keyConditions, String pkName) {
        if (keyConditions == null || !keyConditions.isObject() || pkName == null) {
            return null;
        }
        JsonNode condition = keyConditions.get(pkName);
        if (condition == null || !"EQ".equals(textOrNull(condition.get("ComparisonOperator")))) {
            return null;
        }
        JsonNode list = condition.get("AttributeValueList");
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        return scalarValue(list.get(0));
    }

    /**
     * Top-level attribute names a DynamoDB expression references: a FilterExpression,
     * ConditionExpression or KeyConditionExpression. Walks the parsed tree so a literal name
     * such as {@code ssn} in {@code "ssn = :x"} is captured, not only {@code #alias}es.
     * A malformed expression yields nothing, which only ever denies.
     */
    private static void addExpressionAttributes(Set<String> attributes, String expression,
                                                JsonNode exprAttrNames) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        ExpressionEvaluator.Expr root;
        try {
            root = ExpressionEvaluator.parse(expression);
        } catch (RuntimeException e) {
            return;
        }
        collectExpressionPaths(root, attributes, exprAttrNames);
    }

    private static void collectExpressionPaths(ExpressionEvaluator.Expr expr, Set<String> attributes,
                                               JsonNode exprAttrNames) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case ExpressionEvaluator.AndExpr and ->
                    and.operands().forEach(o -> collectExpressionPaths(o, attributes, exprAttrNames));
            case ExpressionEvaluator.OrExpr or ->
                    or.operands().forEach(o -> collectExpressionPaths(o, attributes, exprAttrNames));
            case ExpressionEvaluator.NotExpr not ->
                    collectExpressionPaths(not.operand(), attributes, exprAttrNames);
            case ExpressionEvaluator.CompareExpr cmp -> {
                collectOperandPath(cmp.left(), attributes, exprAttrNames);
                collectOperandPath(cmp.right(), attributes, exprAttrNames);
            }
            case ExpressionEvaluator.BetweenExpr between -> {
                collectOperandPath(between.value(), attributes, exprAttrNames);
                collectOperandPath(between.low(), attributes, exprAttrNames);
                collectOperandPath(between.high(), attributes, exprAttrNames);
            }
            case ExpressionEvaluator.InExpr in -> {
                collectOperandPath(in.value(), attributes, exprAttrNames);
                in.candidates().forEach(c -> collectOperandPath(c, attributes, exprAttrNames));
            }
            case ExpressionEvaluator.FunctionCallExpr fn ->
                    fn.args().forEach(a -> collectOperandPath(a, attributes, exprAttrNames));
            default -> { }
        }
    }

    private static void collectOperandPath(ExpressionEvaluator.Operand operand, Set<String> attributes,
                                           JsonNode exprAttrNames) {
        if (operand instanceof ExpressionEvaluator.PathOperand path && !path.segments().isEmpty()) {
            String name = resolveAlias(path.segments().getFirst(), exprAttrNames);
            if (name != null && !name.startsWith(":")) {
                attributes.add(name);
            }
        } else if (operand instanceof ExpressionEvaluator.FunctionOperand fn) {
            fn.args().forEach(a -> collectOperandPath(a, attributes, exprAttrNames));
        }
    }

    private static String resolveAlias(String segment, JsonNode exprAttrNames) {
        if (segment.startsWith("#") && exprAttrNames != null && exprAttrNames.has(segment)) {
            return exprAttrNames.get(segment).asText();
        }
        return segment;
    }

    /** Unwraps an AttributeValue to its scalar text. Only S, N and B can be key values. */
    private static String scalarValue(JsonNode attributeValue) {
        if (attributeValue == null || !attributeValue.isObject()) {
            return null;
        }
        for (String type : List.of("S", "N", "B")) {
            JsonNode payload = attributeValue.get(type);
            if (payload != null && payload.isTextual()) {
                return payload.asText();
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isEmpty() ? null : text;
    }
}