package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.LocalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.TableDefinition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 * omitted from the request context. A {@code ForAllValues} policy must include a
 * {@code Null:false} guard to require a resolved leading key.
 * For {@code dynamodb:Attributes} evaluated under {@code ForAllValues}, under-reporting is the
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
            case "dynamodb:GetItem", "dynamodb:DeleteItem", "dynamodb:UpdateItem",
                    "dynamodb:ConditionCheckItem" -> {
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

        addPartiQLAttributes(action, body, attributes);
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

        return result(action, leadingKeys, attributes, effectiveSelect(action, body));
    }

    /** Condition keys for the separately routed 2011-12-05 JSON requests. */
    public static Result extractLegacy(String action, JsonNode body, TableDefinition table) {
        if (body == null || !body.isObject()) {
            return new Result(List.of(), List.of(), null);
        }
        String pkName = partitionKeyName(table);
        List<String> leadingKeys = new ArrayList<>();
        Set<String> attributes = new LinkedHashSet<>();
        if ("dynamodb:Query".equals(action)) {
            addLegacyKey(leadingKeys, attributes, body.get("HashKeyValue"), pkName);
            if (body.hasNonNull("RangeKeyCondition") && table != null && table.getKeySchema() != null) {
                table.getKeySchema().stream().filter(key -> "RANGE".equals(key.getKeyType()))
                        .map(KeySchemaElement::getAttributeName).forEach(attributes::add);
            }
            addAttributesToGet(attributes, body.get("AttributesToGet"));
        } else if ("dynamodb:BatchWriteItem".equals(action)) {
            for (JsonNode tableRequest : requestItemsFor(body, table)) {
                if (!tableRequest.isArray()) {
                    continue;
                }
                for (JsonNode write : tableRequest) {
                    JsonNode put = write.path("PutRequest").get("Item");
                    addAttributeNames(attributes, put);
                    addLeadingKey(leadingKeys, put, pkName);
                    JsonNode key = write.path("DeleteRequest").path("Key");
                    addLegacyKey(leadingKeys, attributes, key.get("HashKeyElement"), pkName);
                    if (key.hasNonNull("RangeKeyElement") && table != null && table.getKeySchema() != null) {
                        table.getKeySchema().stream().filter(element -> "RANGE".equals(element.getKeyType()))
                                .map(KeySchemaElement::getAttributeName).forEach(attributes::add);
                    }
                }
            }
        }
        return result(action, leadingKeys, attributes, legacySelect(action, body));
    }

    private static Result result(String action, List<String> leadingKeys, Set<String> attributes, String select) {
        return new Result(DynamoDbConditionKeyMembers.supports(action, "dynamodb:LeadingKeys")
                ? List.copyOf(leadingKeys) : List.of(),
                DynamoDbConditionKeyMembers.supports(action, "dynamodb:Attributes")
                        ? List.copyOf(attributes) : List.of(), select);
    }

    /** Transaction Get uses its modeled member paths while IAM authorizes the GetItem action. */
    public static Result extractTransactionGet(JsonNode body, TableDefinition table) {
        return extract("dynamodb:GetItem", DynamoDbConditionKeyMembers.itemBody(
                "TransactGetItems", "TransactItems.*.Get.", body), table);
    }

    /** The statement text of one PartiQL request, or {@code null} when it has none. */
    public static String partiQLStatement(JsonNode request) {
        return DynamoDbConditionKeyMembers.values("ExecuteStatement", "dynamodb:Attributes", "Statement", request)
                .stream().map(DynamoDbConditionKeys::textOrNull).filter(value -> value != null)
                .findFirst().orElse(null);
    }

    private static String effectiveSelect(String action, JsonNode body) {
        if (!DynamoDbConditionKeyMembers.supports(action, "dynamodb:Select")) {
            return null;
        }
        if ("dynamodb:PartiQLSelect".equals(action)) {
            DynamoDbPartiQLParser.Stmt.Select select = partiQLSelect(body, "dynamodb:Select");
            return select == null ? null : !select.columns().isEmpty() ? "SPECIFIC_ATTRIBUTES"
                    : select.index() == null ? "ALL_ATTRIBUTES" : "ALL_PROJECTED_ATTRIBUTES";
        }

        String operation = action.substring("dynamodb:".length());
        for (JsonNode select : DynamoDbConditionKeyMembers.values(
                operation, "dynamodb:Select", "Select", body)) {
            String value = textOrNull(select);
            if (value != null) {
                return value;
            }
        }
        for (JsonNode projection : DynamoDbConditionKeyMembers.values(
                operation, "dynamodb:Select", "ProjectionExpression", body)) {
            if (ProjectionEvaluator.hasProjection(textOrNull(projection))) {
                return "SPECIFIC_ATTRIBUTES";
            }
        }
        for (JsonNode attributes : DynamoDbConditionKeyMembers.values(
                operation, "dynamodb:Select", "AttributesToGet", body)) {
            if (attributes.isArray() && !attributes.isEmpty()) {
                return "SPECIFIC_ATTRIBUTES";
            }
        }
        return DynamoDbConditionKeyMembers.values(operation, "dynamodb:Select", "IndexName", body)
                .stream().anyMatch(index -> textOrNull(index) != null)
                ? "ALL_PROJECTED_ATTRIBUTES" : "ALL_ATTRIBUTES";
    }

    private static void addPartiQLAttributes(String action, JsonNode body, Set<String> attributes) {
        if (!"dynamodb:PartiQLSelect".equals(action)) {
            return;
        }
        DynamoDbPartiQLParser.Stmt.Select select = partiQLSelect(body, "dynamodb:Attributes");
        if (select != null) {
            select.columns().forEach(path -> attributes.add(path.root()));
            select.where().stream().flatMap(DynamoDbPartiQLParser::leaves)
                    .forEach(condition -> attributes.add(condition.path().root()));
            select.orderBy().forEach(order -> attributes.add(order.attribute()));
        }
    }

    private static DynamoDbPartiQLParser.Stmt.Select partiQLSelect(JsonNode body, String conditionKey) {
        String statement = DynamoDbConditionKeyMembers.values(
                "ExecuteStatement", conditionKey, "Statement", body).stream()
                .map(DynamoDbConditionKeys::textOrNull).filter(value -> value != null).findFirst().orElse(null);
        if (statement == null) {
            return null;
        }
        try {
            List<JsonNode> parameters = new ArrayList<>();
            body.path("Parameters").forEach(parameter ->
                    parameters.add(DynamoDbAttributeValueValidator.dropNullMembers(parameter.deepCopy())));
            return DynamoDbPartiQLParser.parse(statement, parameters)
                    instanceof DynamoDbPartiQLParser.Stmt.Select select ? select : null;
        } catch (RuntimeException ignored) {
            // Execution reports an invalid statement; do not invent condition values.
            return null;
        }
    }

    private static String legacySelect(String action, JsonNode body) {
        if (!"dynamodb:Query".equals(action)) {
            return null;
        }
        if (body.path("Count").isBoolean() && body.path("Count").booleanValue()) {
            return "COUNT";
        }
        // The legacy adapter only forwards AttributesToGet, not modern selection members.
        return hasProjectedAttributes(body) ? "SPECIFIC_ATTRIBUTES" : "ALL_ATTRIBUTES";
    }

    private static boolean hasProjectedAttributes(JsonNode body) {
        JsonNode attributes = body.get("AttributesToGet");
        return attributes != null && attributes.isArray() && !attributes.isEmpty();
    }

    private static void addLegacyKey(List<String> leadingKeys, Set<String> attributes, JsonNode value, String name) {
        if (value != null) {
            if (name != null) {
                attributes.add(name);
            }
            String scalar = scalarValue(value);
            if (scalar != null) {
                leadingKeys.add(scalar);
            }
        }
    }

    /**
     * The extracted keys. {@code leadingKeys} holds partition-key values in request order
     * (duplicates preserved), {@code attributes} holds attribute names in first-seen order
     * with duplicates removed, {@code select} includes Query/Scan defaults, and is otherwise the raw value or {@code null}.
     */
    public record Result(List<String> leadingKeys, List<String> attributes, String select) {

        /** The condition keys resolved: {@code dynamodb:LeadingKeys}, {@code dynamodb:Attributes} and {@code dynamodb:Select}. */
        public Map<String, List<String>> conditionKeys() {
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            if (!leadingKeys.isEmpty()) {
                conditions.put("dynamodb:LeadingKeys", leadingKeys);
            }
            if (!attributes.isEmpty()) {
                conditions.put("dynamodb:Attributes", attributes);
            }
            if (select != null) {
                conditions.put("dynamodb:Select", List.of(select));
            }
            return conditions;
        }
    }

    /**
     * One condition context for each mode a single-item write asks for ({@link #returnValues}): {@code context} with
     * that mode's {@code dynamodb:ReturnValues}, so every output the request can return is authorized; {@code context}
     * alone for any other request.
     */
    public static List<Map<String, List<String>>> perReturnMode(String action, JsonNode body,
                                                                Map<String, List<String>> context) {
        List<String> modes = returnValues(action, body);
        if (modes.isEmpty()) {
            return List.of(Map.copyOf(context));
        }
        List<Map<String, List<String>>> contexts = new ArrayList<>();
        for (String mode : modes) {
            Map<String, List<String>> withMode = new LinkedHashMap<>(context);
            withMode.put("dynamodb:ReturnValues", List.of(mode));
            contexts.add(Map.copyOf(withMode));
        }
        return List.copyOf(contexts);
    }

    /**
     * The request of each table a batch's {@code RequestItems} names, by its reference as written: the request with
     * that table's entry alone, so each table's keys are read from its own entry. Empty for a request with no
     * {@code RequestItems} object.
     */
    public static Map<String, ObjectNode> batchTableRequests(JsonNode body) {
        Map<String, ObjectNode> requests = new LinkedHashMap<>();
        if (body == null || !body.isObject() || !body.path("RequestItems").isObject()) {
            return requests;
        }
        body.path("RequestItems").properties().forEach(entry -> {
            ObjectNode single = ((ObjectNode) body).deepCopy();
            single.putObject("RequestItems").set(entry.getKey(), entry.getValue());
            requests.put(entry.getKey(), single);
        });
        return requests;
    }

    /**
     * The {@code dynamodb:ReturnValues} of a single-item write: each mode its {@code ReturnValues} and
     * {@code ReturnValuesOnConditionCheckFailure} ask for, or {@code NONE}; empty for any other request.
     */
    public static List<String> returnValues(String action, JsonNode body) {
        if (!("dynamodb:PutItem".equals(action) || "dynamodb:UpdateItem".equals(action)
                || "dynamodb:DeleteItem".equals(action)) || body == null || !body.isObject()) {
            return List.of();
        }
        Set<String> modes = new LinkedHashSet<>();
        for (String field : List.of("ReturnValues", "ReturnValuesOnConditionCheckFailure")) {
            String mode = body.path(field).asText("NONE");
            if (!"NONE".equals(mode)) {
                modes.add(mode);
            }
        }
        return modes.isEmpty() ? List.of("NONE") : List.copyOf(modes);
    }

    /**
     * The condition keys of one part of a request {@code enclosing} names, which AWS authorizes as {@code action}: a
     * PartiQL statement's, with {@code dynamodb:EnclosingOperation} for a transaction's, or a transaction item's with
     * {@code dynamodb:EnclosingOperation} and a write's {@code dynamodb:ReturnValues} on a failed condition.
     *
     * @param tables the table of a name, for its partition key; null when it has none
     * @param region the Region of the request, which a table's ARN must name
     */
    public static Map<String, List<String>> partConditionKeys(String action, String enclosing, JsonNode part,
                                                              Function<String, TableDefinition> tables,
                                                              String region) {
        Map<String, List<String>> context = new LinkedHashMap<>();
        if (part.has("Statement")) {
            String statement = partiQLStatement(part);
            if ("ExecuteTransaction".equals(enclosing)) {
                context.put("dynamodb:EnclosingOperation", List.of(enclosing));
            }
            context.putAll(extract(action, part, tables.apply(DynamoDbPartiQLParser.extractTable(statement)))
                    .conditionKeys());
            return context;
        }
        boolean read = "TransactGetItems".equals(enclosing);
        String tableName = part.hasNonNull("TableName") ? part.get("TableName").asText().trim() : "";
        TableDefinition table;
        try {
            table = tableName.isEmpty() ? null
                    : tables.apply(DynamoDbTableNames.resolveWithRegion(tableName, region).name());
        } catch (AwsException e) {
            // DynamoDB refuses the item itself; without a table its leading key is left out.
            table = null;
        }
        context.put("dynamodb:EnclosingOperation", List.of(enclosing));
        context.putAll((read ? extractTransactionGet(part, table) : extract(action, part, table)).conditionKeys());
        if (!read && part.hasNonNull("ReturnValuesOnConditionCheckFailure")) {
            context.put("dynamodb:ReturnValues", List.of(part.path("ReturnValuesOnConditionCheckFailure").asText()));
        }
        return context;
    }

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
     * A malformed expression yields no attribute names; normal absent-key condition rules apply.
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
