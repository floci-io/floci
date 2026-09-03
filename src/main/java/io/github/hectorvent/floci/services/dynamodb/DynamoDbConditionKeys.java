package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
                String value = DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                        textOrNull(body.get("KeyConditionExpression")),
                        body.get("ExpressionAttributeNames"),
                        body.get("ExpressionAttributeValues"),
                        pkName);
                if (value != null) {
                    leadingKeys.add(value);
                }
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
        addUpdateExpressionTargets(attributes, textOrNull(body.get("UpdateExpression")),
                body.get("ExpressionAttributeNames"));
        addExpressionAttributeNameValues(attributes, body.get("ExpressionAttributeNames"));

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
        if (table == null || table.getKeySchema() == null) {
            return null;
        }
        return table.getKeySchema().stream()
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

    /**
     * Top-level attribute names an UpdateExpression writes. Splits on the four clause
     * keywords, then on commas, and keeps the first path segment of each target. Aliases are
     * resolved through ExpressionAttributeNames.
     */
    private static void addUpdateExpressionTargets(Set<String> attributes, String updateExpression,
                                                   JsonNode exprAttrNames) {
        if (updateExpression == null || updateExpression.isBlank()) {
            return;
        }
        String[] clauses = updateExpression.split("(?i)\\b(SET|REMOVE|ADD|DELETE)\\b");
        for (String clause : clauses) {
            for (String assignment : clause.split(",")) {
                String target = assignment.trim();
                if (target.isEmpty()) {
                    continue;
                }
                int equals = target.indexOf('=');
                if (equals >= 0) {
                    target = target.substring(0, equals).trim();
                }
                // ADD / DELETE take "path value"; keep only the path.
                int space = target.indexOf(' ');
                if (space > 0) {
                    target = target.substring(0, space);
                }
                String name = firstPathSegment(target);
                if (name == null) {
                    continue;
                }
                if (name.startsWith("#") && exprAttrNames != null && exprAttrNames.has(name)) {
                    attributes.add(exprAttrNames.get(name).asText());
                } else if (!name.startsWith("#") && !name.startsWith(":")) {
                    attributes.add(name);
                }
            }
        }
    }

    private static String firstPathSegment(String path) {
        int cut = path.length();
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        if (dot >= 0) {
            cut = Math.min(cut, dot);
        }
        if (bracket >= 0) {
            cut = Math.min(cut, bracket);
        }
        String segment = path.substring(0, cut).trim();
        return segment.isEmpty() ? null : segment;
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