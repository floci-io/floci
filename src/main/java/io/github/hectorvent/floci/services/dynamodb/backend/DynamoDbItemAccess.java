package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;

import java.util.List;

/** Typed item operations for Java consumers that work with DynamoDB items directly. */
public interface DynamoDbItemAccess {

    record ScanPage(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}

    void putItem(Scope scope, String tableName, JsonNode item, String conditionExpression,
                 JsonNode expressionAttributeNames, JsonNode expressionAttributeValues);

    JsonNode getItem(Scope scope, String tableName, JsonNode key);

    void deleteItem(Scope scope, String tableName, JsonNode key, String conditionExpression,
                    JsonNode expressionAttributeNames, JsonNode expressionAttributeValues);

    /** Returns only the image ReturnValues asked for: the new item for ALL_NEW, the old item for ALL_OLD, otherwise null. */
    JsonNode updateItem(Scope scope, String tableName, JsonNode key, JsonNode attributeUpdates,
                        String updateExpression, JsonNode expressionAttributeNames,
                        JsonNode expressionAttributeValues, String returnValues, String conditionExpression);

    ScanPage scan(Scope scope, String tableName, String filterExpression, JsonNode expressionAttributeNames,
                  JsonNode expressionAttributeValues, JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey);
}
