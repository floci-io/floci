package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code AMAZON_DYNAMODB} data source.
 *
 * <p>Translates AppSync's request objects, {@code {operation: "GetItem", key: …}} and friends,
 * into the native DynamoDB API and back, going through {@link DynamoDbJsonHandler} so the whole of
 * Floci's DynamoDB behaviour applies: expressions, conditions, indexes, pagination.
 *
 * <p>The translation that matters is on the way out. A resolver's request carries DynamoDB
 * attribute values, because {@code util.dynamodb.toMapValues} put them there, but the result AppSync
 * hands back is <em>plain JSON</em>, {@code {"id": "7"}}, never {@code {"id": {"S": "7"}}}. A
 * resolver returning the raw response would give every field the wrong shape, so items are
 * unmarshalled here.
 *
 * <p>{@code nextToken} is likewise AppSync's own: an opaque string, made here by base64-encoding
 * the {@code LastEvaluatedKey} and decoded again on the way in.
 */
@ApplicationScoped
public class DynamoDbDataSourceInvoker implements AppSyncDataSourceInvoker {

    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final ObjectMapper objectMapper;

    @Inject
    public DynamoDbDataSourceInvoker(DynamoDbJsonHandler dynamoDbJsonHandler, ObjectMapper objectMapper) {
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.AMAZON_DYNAMODB;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        JsonNode appSyncRequest = objectMapper.valueToTree(request == null ? Map.of() : request);
        String tableName = tableName(dataSource, appSyncRequest);
        String operation = appSyncRequest.path("operation").asText("");
        return switch (operation) {
            case "GetItem" -> getItem(tableName, appSyncRequest, region);
            case "PutItem" -> putItem(tableName, appSyncRequest, region);
            case "UpdateItem" -> updateItem(tableName, appSyncRequest, region);
            case "DeleteItem" -> deleteItem(tableName, appSyncRequest, region);
            case "Query" -> queryOrScan("Query", tableName, appSyncRequest, region);
            case "Scan" -> queryOrScan("Scan", tableName, appSyncRequest, region);
            case "" -> throw new AwsException("InternalFailureException",
                    "The resolver's DynamoDB request has no \"operation\"", 500);
            default -> throw new AwsException("InternalFailureException",
                    "Floci's AppSync DynamoDB data source does not implement the " + operation
                            + " operation yet", 500);
        };
    }

    // ── Operations ───────────────────────────────────────────────────────────

    private Object getItem(String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        if (request.path("consistentRead").asBoolean(false)) {
            native0.put("ConsistentRead", true);
        }
        applyProjection(native0, request.path("projection"));
        JsonNode response = call("GetItem", native0, region);
        // A miss is a null field, not an error: AppSync resolves the field to null.
        return unmarshalItem(response.get("Item"));
    }

    private Object putItem(String tableName, JsonNode request, String region) {
        ObjectNode item = objectMapper.createObjectNode();
        item.setAll((ObjectNode) objectMapper.valueToTree(fields(request.path("attributeValues"))));
        item.setAll((ObjectNode) objectMapper.valueToTree(fields(request.path("key"))));
        ObjectNode native0 = base(tableName);
        native0.set("Item", item);
        applyCondition(native0, request.path("condition"));
        call("PutItem", native0, region);
        // AppSync answers with the item as written, which is the merged key and attributes.
        return unmarshalItem(item);
    }

    private Object updateItem(String tableName, JsonNode request, String region) {
        JsonNode update = request.path("update");
        if (!update.hasNonNull("expression")) {
            throw new AwsException("InternalFailureException",
                    "An UpdateItem request needs update.expression", 500);
        }
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        native0.put("UpdateExpression", update.path("expression").asText());
        applyExpressionMaps(native0, update);
        applyCondition(native0, request.path("condition"));
        native0.put("ReturnValues", "ALL_NEW");
        JsonNode response = call("UpdateItem", native0, region);
        return unmarshalItem(response.get("Attributes"));
    }

    private Object deleteItem(String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        native0.set("Key", request.path("key"));
        applyCondition(native0, request.path("condition"));
        native0.put("ReturnValues", "ALL_OLD");
        JsonNode response = call("DeleteItem", native0, region);
        // The deleted item, as AppSync reports it, so a mutation can return what it removed.
        return unmarshalItem(response.get("Attributes"));
    }

    private Object queryOrScan(String action, String tableName, JsonNode request, String region) {
        ObjectNode native0 = base(tableName);
        if ("Query".equals(action)) {
            JsonNode query = request.path("query");
            if (!query.hasNonNull("expression")) {
                throw new AwsException("InternalFailureException",
                        "A Query request needs query.expression", 500);
            }
            native0.put("KeyConditionExpression", query.path("expression").asText());
            applyExpressionMaps(native0, query);
        }
        applyFilter(native0, request.path("filter"));
        if (request.hasNonNull("index")) {
            native0.put("IndexName", request.path("index").asText());
        }
        if (request.hasNonNull("limit")) {
            native0.put("Limit", request.path("limit").asInt());
        }
        if (request.has("scanIndexForward")) {
            native0.put("ScanIndexForward", request.path("scanIndexForward").asBoolean(true));
        }
        if (request.path("consistentRead").asBoolean(false)) {
            native0.put("ConsistentRead", true);
        }
        if (request.hasNonNull("nextToken")) {
            native0.set("ExclusiveStartKey", decodeNextToken(request.path("nextToken").asText()));
        }
        applyProjection(native0, request.path("projection"));

        JsonNode response = call(action, native0, region);
        List<Object> items = new ArrayList<>();
        for (JsonNode item : response.path("Items")) {
            items.add(unmarshalItem(item));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("scannedCount", response.path("ScannedCount").asInt(items.size()));
        result.put("nextToken", encodeNextToken(response.get("LastEvaluatedKey")));
        return result;
    }

    // ── Request assembly ─────────────────────────────────────────────────────

    private ObjectNode base(String tableName) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("TableName", tableName);
        return request;
    }

    /**
     * The table a resolver's request runs against. AppSync takes it from the data source's
     * {@code dynamodbConfig}, and a request may not override it, so this is the one place the
     * mapping between the two spellings of that config member matters.
     */
    private String tableName(DataSource dataSource, JsonNode request) {
        Map<String, Object> config = dataSource.getDynamodbConfig();
        Object tableName = config == null ? null : config.get("tableName");
        if (tableName == null || String.valueOf(tableName).isBlank()) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName() + " has no dynamodbConfig.tableName", 500);
        }
        return String.valueOf(tableName);
    }

    private void applyProjection(ObjectNode target, JsonNode projection) {
        if (projection == null || !projection.isObject()) {
            return;
        }
        if (projection.hasNonNull("expression")) {
            target.put("ProjectionExpression", projection.path("expression").asText());
        }
        applyExpressionMaps(target, projection);
    }

    private void applyFilter(ObjectNode target, JsonNode filter) {
        if (filter == null || !filter.isObject() || !filter.hasNonNull("expression")) {
            return;
        }
        target.put("FilterExpression", filter.path("expression").asText());
        applyExpressionMaps(target, filter);
    }

    private void applyCondition(ObjectNode target, JsonNode condition) {
        if (condition == null || !condition.isObject() || !condition.hasNonNull("expression")) {
            return;
        }
        target.put("ConditionExpression", condition.path("expression").asText());
        applyExpressionMaps(target, condition);
    }

    /**
     * Merges {@code expressionNames} / {@code expressionValues} into the native request. Merged
     * rather than set, because a Query can carry both a key condition and a filter and each brings
     * its own placeholders into the one shared map.
     */
    private void applyExpressionMaps(ObjectNode target, JsonNode source) {
        mergeInto(target, "ExpressionAttributeNames", source.path("expressionNames"));
        mergeInto(target, "ExpressionAttributeValues", source.path("expressionValues"));
    }

    private void mergeInto(ObjectNode target, String field, JsonNode addition) {
        if (addition == null || !addition.isObject() || addition.isEmpty()) {
            return;
        }
        ObjectNode existing = target.has(field) ? (ObjectNode) target.get(field) : objectMapper.createObjectNode();
        existing.setAll((ObjectNode) addition);
        target.set(field, existing);
    }

    private Map<String, JsonNode> fields(JsonNode node) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        }
        return fields;
    }

    // ── Response translation ─────────────────────────────────────────────────

    private JsonNode call(String action, ObjectNode request, String region) {
        try {
            Response response = dynamoDbJsonHandler.handle(action, request, region);
            Object entity = response.getEntity();
            if (entity == null) {
                return objectMapper.createObjectNode();
            }
            if (entity instanceof String text) {
                return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
            }
            return objectMapper.valueToTree(entity);
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "The DynamoDB data source call failed: " + e.getMessage(), 500);
        }
    }

    /** Attribute values to plain JSON, which is the only shape a GraphQL field can be built from. */
    private Object unmarshalItem(JsonNode item) {
        if (item == null || item.isNull() || !item.isObject()) {
            return null;
        }
        Map<String, Object> plain = new LinkedHashMap<>();
        item.fields().forEachRemaining(entry -> plain.put(entry.getKey(), unmarshal(entry.getValue())));
        return plain;
    }

    private Object unmarshal(JsonNode value) {
        if (value == null || value.isNull() || !value.isObject()) {
            return null;
        }
        if (value.has("NULL")) {
            return null;
        }
        if (value.has("S")) {
            return value.get("S").asText();
        }
        if (value.has("N")) {
            String number = value.get("N").asText();
            // Integral where it can be, so an ID field is 7 rather than 7.0 in the response.
            // Deliberately not a conditional expression: one with Double and Long branches is
            // subject to binary numeric promotion, which unboxes both and makes every number a
            // double, including the ints that GraphQL Int fields are built from.
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.valueOf(number);
                }
                return Long.valueOf(number);
            } catch (NumberFormatException e) {
                return number;
            }
        }
        if (value.has("BOOL")) {
            return value.get("BOOL").asBoolean();
        }
        if (value.has("B")) {
            return value.get("B").asText();
        }
        if (value.has("SS")) {
            return toList(value.get("SS"));
        }
        if (value.has("NS")) {
            List<Object> numbers = new ArrayList<>();
            value.get("NS").forEach(element -> numbers.add(Double.valueOf(element.asText())));
            return numbers;
        }
        if (value.has("BS")) {
            return toList(value.get("BS"));
        }
        if (value.has("L")) {
            List<Object> list = new ArrayList<>();
            value.get("L").forEach(element -> list.add(unmarshal(element)));
            return list;
        }
        if (value.has("M")) {
            return unmarshalItem(value.get("M"));
        }
        return null;
    }

    private List<Object> toList(JsonNode array) {
        List<Object> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private String encodeNextToken(JsonNode lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isNull() || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(lastEvaluatedKey.toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode decodeNextToken(String nextToken) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(nextToken);
            return objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "The nextToken handed to the DynamoDB data source is not one Floci issued", 500);
        }
    }
}
