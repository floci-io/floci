package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Api;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Call;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** The 2011-12-05 wire shapes for table discovery, queries and batch writes. */
final class DynamoDbLegacyProtocol {

    private final DynamoDbOperations operations;
    private final Scope scope;
    private final Map<String, JsonNode> tables = new HashMap<>();

    DynamoDbLegacyProtocol(DynamoDbOperations operations, Scope scope) {
        this.operations = operations;
        this.scope = scope;
    }

    Reply execute(String action, JsonNode request) throws Exception {
        return switch (action) {
            case "DescribeTable" -> describe(request);
            case "Query" -> query(request);
            case "BatchWriteItem" -> batchWrite(request);
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: DynamoDB_20111205." + action, 400);
        };
    }

    private Reply describe(JsonNode request) throws Exception {
        Reply reply = call("DescribeTable", request);
        if (reply.status() >= 300) {
            return reply;
        }
        ObjectNode table = fields(reply.body().path("Table"), "TableName", "TableStatus", "CreationDateTime",
                "ItemCount", "TableSizeBytes", "ProvisionedThroughput");
        table.set("KeySchema", legacySchema(reply.body().path("Table")));
        ObjectNode body = object();
        body.set("Table", table);
        return body(reply, body);
    }

    private Reply query(JsonNode request) throws Exception {
        String tableName = tableName(request);
        JsonNode table = table(tableName);
        String hashName = keyName(table, "HASH");
        String rangeName = keyName(table, "RANGE");
        if (rangeName == null) {
            throw invalid("Query requires a table with a hash and range key");
        }
        ObjectNode converted = fields(request, "TableName", "AttributesToGet", "Limit",
                "ConsistentRead", "ScanIndexForward");
        ObjectNode conditions = object();
        ObjectNode hashCondition = object().put("ComparisonOperator", "EQ");
        hashCondition.putArray("AttributeValueList").add(requiredObject(request, "HashKeyValue"));
        conditions.set(hashName, hashCondition);
        if (request.hasNonNull("RangeKeyCondition")) {
            conditions.set(rangeName, requiredObject(request, "RangeKeyCondition"));
        }
        converted.set("KeyConditions", conditions);
        if (request.hasNonNull("ExclusiveStartKey")) {
            converted.set("ExclusiveStartKey", modernKey(request.get("ExclusiveStartKey"), table));
        }
        if (request.hasNonNull("Count")) {
            if (!request.get("Count").isBoolean()) {
                throw invalid("Count must be a boolean");
            }
            if (request.get("Count").booleanValue()) {
                converted.put("Select", "COUNT");
            }
        }
        converted.put("ReturnConsumedCapacity", "TOTAL");
        Reply reply = call("Query", converted);
        if (reply.status() >= 300) {
            return reply;
        }
        ObjectNode result = fields(reply.body(), "Items", "Count");
        JsonNode lastKey = reply.body().get("LastEvaluatedKey");
        if (lastKey != null && !lastKey.isEmpty()) {
            result.set("LastEvaluatedKey", legacyKey(lastKey, table));
        }
        JsonNode capacity = reply.body().path("ConsumedCapacity").get("CapacityUnits");
        if (capacity != null) {
            result.set("ConsumedCapacityUnits", capacity);
        }
        return body(reply, result);
    }

    private Reply batchWrite(JsonNode request) throws Exception {
        ObjectNode converted = object();
        converted.set("RequestItems", writes(requiredObject(request, "RequestItems"), false));
        converted.put("ReturnConsumedCapacity", "TOTAL");
        Reply reply = call("BatchWriteItem", converted);
        if (reply.status() >= 300) {
            return reply;
        }
        ObjectNode result = object();
        ObjectNode capacities = object();
        for (JsonNode capacity : reply.body().path("ConsumedCapacity")) {
            ObjectNode units = object();
            units.set("ConsumedCapacityUnits", capacity.get("CapacityUnits"));
            capacities.set(capacity.path("TableName").asText(), units);
        }
        result.set("Responses", capacities);
        JsonNode unprocessed = reply.body().get("UnprocessedItems");
        if (unprocessed != null) {
            result.set("UnprocessedItems", writes(unprocessed, true));
        }
        return body(reply, result);
    }

    private ObjectNode writes(JsonNode requestItems, boolean legacy) throws Exception {
        ObjectNode result = object();
        Iterator<Map.Entry<String, JsonNode>> entries = requestItems.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!entry.getValue().isArray()) {
                throw invalid("RequestItems values must be arrays");
            }
            JsonNode table = table(entry.getKey());
            ArrayNode writes = result.putArray(entry.getKey());
            for (JsonNode write : entry.getValue()) {
                if (!write.isObject() || write.hasNonNull("PutRequest") == write.hasNonNull("DeleteRequest")) {
                    throw invalid("A write must contain exactly one PutRequest or DeleteRequest");
                }
                ObjectNode converted = object();
                if (write.hasNonNull("DeleteRequest")) {
                    JsonNode key = requiredObject(requiredObject(write, "DeleteRequest"), "Key");
                    ObjectNode delete = object();
                    delete.set("Key", legacy ? legacyKey(key, table) : modernKey(key, table));
                    converted.set("DeleteRequest", delete);
                } else {
                    ObjectNode put = object();
                    put.set("Item", requiredObject(requiredObject(write, "PutRequest"), "Item"));
                    converted.set("PutRequest", put);
                }
                writes.add(converted);
            }
        }
        return result;
    }

    private JsonNode table(String name) throws Exception {
        JsonNode table = tables.get(name);
        if (table == null) {
            table = call("DescribeTable", object().put("TableName", name)).successBody().path("Table");
            tables.put(name, table);
        }
        return table;
    }

    private Reply call(String action, JsonNode request) throws Exception {
        DynamoDbRequestShapes.check(action, request);
        return operations.execute(new Call(scope, Api.DYNAMODB, action, request));
    }

    private static ObjectNode modernKey(JsonNode key, JsonNode table) {
        ObjectNode converted = object();
        converted.set(keyName(table, "HASH"), requiredObject(key, "HashKeyElement"));
        String rangeName = keyName(table, "RANGE");
        if (rangeName != null) {
            converted.set(rangeName, requiredObject(key, "RangeKeyElement"));
        } else if (key.hasNonNull("RangeKeyElement")) {
            throw invalid("The provided key does not match the table schema");
        }
        return converted;
    }

    private static ObjectNode legacyKey(JsonNode key, JsonNode table) {
        ObjectNode converted = object();
        converted.set("HashKeyElement", key.get(keyName(table, "HASH")));
        String rangeName = keyName(table, "RANGE");
        if (rangeName != null) {
            converted.set("RangeKeyElement", key.get(rangeName));
        }
        return converted;
    }

    private static ObjectNode legacySchema(JsonNode table) {
        ObjectNode result = object();
        for (JsonNode key : table.path("KeySchema")) {
            String name = key.path("AttributeName").asText();
            for (JsonNode definition : table.path("AttributeDefinitions")) {
                if (name.equals(definition.path("AttributeName").asText())) {
                    result.set("HASH".equals(key.path("KeyType").asText()) ? "HashKeyElement" : "RangeKeyElement",
                            fields(definition, "AttributeName", "AttributeType"));
                }
            }
        }
        return result;
    }

    private static String keyName(JsonNode table, String type) {
        for (JsonNode key : table.path("KeySchema")) {
            if (type.equals(key.path("KeyType").asText())) {
                return key.path("AttributeName").asText();
            }
        }
        return null;
    }

    private static String tableName(JsonNode request) {
        JsonNode value = request.get("TableName");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("TableName is required");
        }
        return value.asText();
    }

    private static JsonNode requiredObject(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isObject()) {
            throw invalid(name + " must be an object");
        }
        return value;
    }

    private static ObjectNode fields(JsonNode source, String... names) {
        ObjectNode result = object();
        for (String name : names) {
            if (source.hasNonNull(name)) {
                result.set(name, source.get(name).deepCopy());
            }
        }
        return result;
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static Reply body(Reply reply, ObjectNode body) {
        return new Reply(reply.status(), body, reply.headers());
    }

    private static AwsException invalid(String message) {
        return new AwsException("com.amazon.coral.validate#ValidationException", message, 400);
    }
}
