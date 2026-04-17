package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsJsonController;
import io.github.hectorvent.floci.services.dynamodb.model.*;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * DynamoDB JSON protocol handler.
 * Called by {@link AwsJsonController} for DynamoDB-targeted requests.
 */
@ApplicationScoped
public class DynamoDbJsonHandler {

    private final DynamoDbService dynamoDbService;
    private final DynamoDbStreamService dynamoDbStreamService;
    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;

    @Inject
    public DynamoDbJsonHandler(DynamoDbService dynamoDbService, DynamoDbStreamService dynamoDbStreamService,
                               KinesisService kinesisService, ObjectMapper objectMapper) {
        this.dynamoDbService = dynamoDbService;
        this.dynamoDbStreamService = dynamoDbStreamService;
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateTable" -> handleCreateTable(request, region);
            case "DeleteTable" -> handleDeleteTable(request, region);
            case "DescribeTable" -> handleDescribeTable(request, region);
            case "ListTables" -> handleListTables(request, region);
            case "PutItem" -> handlePutItem(request, region);
            case "GetItem" -> handleGetItem(request, region);
            case "DeleteItem" -> handleDeleteItem(request, region);
            case "UpdateItem" -> handleUpdateItem(request, region);
            case "Query" -> handleQuery(request, region);
            case "Scan" -> handleScan(request, region);
            case "BatchWriteItem" -> handleBatchWriteItem(request, region);
            case "BatchGetItem" -> handleBatchGetItem(request, region);
            case "UpdateTable" -> handleUpdateTable(request, region);
            case "DescribeTimeToLive" -> handleDescribeTimeToLive(request, region);
            case "UpdateTimeToLive" -> handleUpdateTimeToLive(request, region);
            case "DescribeContinuousBackups" -> handleDescribeContinuousBackups(request, region);
            case "UpdateContinuousBackups" -> handleUpdateContinuousBackups(request, region);
            case "TransactWriteItems" -> handleTransactWriteItems(request, region);
            case "TransactGetItems" -> handleTransactGetItems(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsOfResource" -> handleListTagsOfResource(request, region);
            case "EnableKinesisStreamingDestination" -> handleEnableKinesisStreamingDestination(request, region);
            case "DisableKinesisStreamingDestination" -> handleDisableKinesisStreamingDestination(request, region);
            case "DescribeKinesisStreamingDestination" -> handleDescribeKinesisStreamingDestination(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException", "Operation " + action + " is not supported."))
                    .build();

        };
    }

    private Response handleCreateTable(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();

        List<KeySchemaElement> keySchema = new ArrayList<>();
        request.path("KeySchema").forEach(ks ->
                keySchema.add(new KeySchemaElement(
                        ks.path("AttributeName").asText(),
                        ks.path("KeyType").asText())));

        List<AttributeDefinition> attrDefs = new ArrayList<>();
        request.path("AttributeDefinitions").forEach(ad ->
                attrDefs.add(new AttributeDefinition(
                        ad.path("AttributeName").asText(),
                        ad.path("AttributeType").asText())));

        Long readCapacity = null;
        Long writeCapacity = null;
        JsonNode pt = request.path("ProvisionedThroughput");
        if (!pt.isMissingNode()) {
            readCapacity = pt.path("ReadCapacityUnits").asLong(5);
            writeCapacity = pt.path("WriteCapacityUnits").asLong(5);
        }

        List<GlobalSecondaryIndex> gsis = new ArrayList<>();
        JsonNode gsiArray = request.path("GlobalSecondaryIndexes");
        if (!gsiArray.isMissingNode() && gsiArray.isArray()) {
            for (JsonNode gsiNode : gsiArray) {
                String indexName = gsiNode.path("IndexName").asText();
                List<KeySchemaElement> gsiKeySchema = new ArrayList<>();
                gsiNode.path("KeySchema").forEach(ks ->
                        gsiKeySchema.add(new KeySchemaElement(
                                ks.path("AttributeName").asText(),
                                ks.path("KeyType").asText())));
                String projectionType = gsiNode.path("Projection").path("ProjectionType").asText("ALL");
                JsonNode nonKeyAttrArray = gsiNode.path("Projection").path("NonKeyAttributes");
                List<String> nonKeyAttributes = new ArrayList<>();
                if (!nonKeyAttrArray.isMissingNode() && nonKeyAttrArray.isArray()){
                    for (JsonNode nonKeyAttr : nonKeyAttrArray){
                        nonKeyAttributes.add(nonKeyAttr.asText());
                    }
                }
                GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes);
                JsonNode gsiPt = gsiNode.path("ProvisionedThroughput");
                if (!gsiPt.isMissingNode()) {
                    gsi.getProvisionedThroughput().setReadCapacityUnits(gsiPt.path("ReadCapacityUnits").asLong(0));
                    gsi.getProvisionedThroughput().setWriteCapacityUnits(gsiPt.path("WriteCapacityUnits").asLong(0));
                }
                gsis.add(gsi);
            }
        }

        List<LocalSecondaryIndex> lsis = new ArrayList<>();
        JsonNode lsiArray = request.path("LocalSecondaryIndexes");
        if (!lsiArray.isMissingNode() && lsiArray.isArray()) {
            for (JsonNode lsiNode : lsiArray) {
                String indexName = lsiNode.path("IndexName").asText();
                List<KeySchemaElement> lsiKeySchema = new ArrayList<>();
                lsiNode.path("KeySchema").forEach(ks ->
                        lsiKeySchema.add(new KeySchemaElement(
                                ks.path("AttributeName").asText(),
                                ks.path("KeyType").asText())));
                String projectionType = lsiNode.path("Projection").path("ProjectionType").asText("ALL");
                lsis.add(new LocalSecondaryIndex(indexName, lsiKeySchema, null, projectionType));
            }
        }

        String billingMode = request.has("BillingMode")
                ? request.get("BillingMode").asText() : null;

        TableDefinition table = dynamoDbService.createTable(tableName, keySchema, attrDefs,
                readCapacity, writeCapacity, gsis, lsis, region);

        if ("PAY_PER_REQUEST".equals(billingMode)) {
            table.setBillingMode("PAY_PER_REQUEST");
            table.getProvisionedThroughput().setReadCapacityUnits(0L);
            table.getProvisionedThroughput().setWriteCapacityUnits(0L);
        } else {
            table.setBillingMode("PROVISIONED");
        }

        // Store tags from CreateTable request
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                table.getTags().put(tag.path("Key").asText(), tag.path("Value").asText());
            }
        }

        JsonNode streamSpec = request.path("StreamSpecification");
        if (!streamSpec.isMissingNode() && streamSpec.path("StreamEnabled").asBoolean(false)) {
            String viewType = streamSpec.path("StreamViewType").asText("NEW_AND_OLD_IMAGES");
            StreamDescription sd = dynamoDbStreamService.enableStream(
                    tableName, table.getTableArn(), viewType, region);
            table.setStreamEnabled(true);
            table.setStreamArn(sd.getStreamArn());
            table.setStreamViewType(viewType);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        return Response.ok(response).build();
    }

    private Response handleDeleteTable(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);
        dynamoDbService.deleteTable(tableName, region);

        table.setTableStatus("DELETING");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        return Response.ok(response).build();
    }

    private Response handleDescribeTable(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Table", tableToNode(table));
        return Response.ok(response).build();
    }

    private Response handleListTables(JsonNode request, String region) {
        List<String> tables = dynamoDbService.listTables(region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tableNames = objectMapper.createArrayNode();
        tables.forEach(tableNames::add);
        response.set("TableNames", tableNames);
        return Response.ok(response).build();
    }

    private Response handlePutItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode item = request.path("Item");
        String returnValues = request.path("ReturnValues").asText("NONE");
        String returnValuesOnConditionCheckFailure = request.path("ReturnValuesOnConditionCheckFailure").asText("NONE");
        String conditionExpression = request.has("ConditionExpression")
                ? request.get("ConditionExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;

        JsonNode oldItem = null;
        if ("ALL_OLD" .equals(returnValues)) {
            dynamoDbService.describeTable(tableName, region);
            oldItem = dynamoDbService.getItem(tableName, item, region);
        }

        dynamoDbService.putItem(tableName, item, conditionExpression, exprAttrNames, exprAttrValues, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_OLD" .equals(returnValues) && oldItem != null) {
            response.set("Attributes", oldItem);
        }
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private Response handleGetItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode key = request.path("Key");

        JsonNode item = dynamoDbService.getItem(tableName, key, region);

        ObjectNode response = objectMapper.createObjectNode();
        if (item != null) {
            response.set("Item", item);
        }
        addConsumedCapacity(response, request, tableName, item != null ? 1 : 0, false);
        return Response.ok(response).build();
    }

    private Response handleDeleteItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode key = request.path("Key");
        String returnValues = request.path("ReturnValues").asText("NONE");        
        String returnValuesOnConditionCheckFailure = request.path("ReturnValuesOnConditionCheckFailure").asText("NONE");
        String conditionExpression = request.has("ConditionExpression")
                ? request.get("ConditionExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;

        JsonNode oldItem = dynamoDbService.deleteItem(tableName, key, conditionExpression,
                exprAttrNames, exprAttrValues, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_OLD" .equals(returnValues) && oldItem != null) {
            response.set("Attributes", oldItem);
        }
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private Response handleUpdateItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode key = request.path("Key");
        JsonNode attributeUpdates = request.path("AttributeUpdates");
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;
        String updateExpression = request.has("UpdateExpression")
                ? request.get("UpdateExpression").asText() : null;
        String conditionExpression = request.has("ConditionExpression")
                ? request.get("ConditionExpression").asText() : null;
        String returnValues = request.path("ReturnValues").asText("NONE");
        String returnValuesOnConditionCheckFailure = request.path("ReturnValuesOnConditionCheckFailure").asText("NONE");

        JsonNode updateData = attributeUpdates.isMissingNode() ? null : attributeUpdates;

        DynamoDbService.UpdateResult result = dynamoDbService.updateItem(
                tableName, key, updateData, updateExpression, exprAttrNames, exprAttrValues,
                returnValues, conditionExpression, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_NEW" .equals(returnValues) && result.newItem() != null) {
            response.set("Attributes", result.newItem());
        } else if ("ALL_OLD" .equals(returnValues) && result.oldItem() != null) {
            response.set("Attributes", result.oldItem());
        } else if ("UPDATED_NEW".equals(returnValues) && result.oldItem() != null && result.newItem() != null) {
            response.set("Attributes", getChangedAttributes(result.newItem(), result.oldItem()));
        } else if ("UPDATED_OLD".equals(returnValues) && result.oldItem() != null && result.newItem() != null) {
            response.set("Attributes", getChangedAttributes(result.oldItem(), result.newItem()));
        }
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private JsonNode getChangedAttributes(JsonNode preferredItem, JsonNode secondaryItem){
        ObjectNode changedAttributes = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = preferredItem.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String attrName = entry.getKey();
            JsonNode value = entry.getValue();

            if (secondaryItem.has(attrName)){
                JsonNode secondaryValue = secondaryItem.get(attrName);
                if (!value.equals(secondaryValue)){
                    changedAttributes.put(attrName, value);
                }
            }
            else {
                changedAttributes.put(attrName, value);
            }
        }
        return changedAttributes;
    }

    private Response handleQuery(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode keyConditions = request.has("KeyConditions") ? request.get("KeyConditions") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        String keyConditionExpr = request.has("KeyConditionExpression")
                ? request.get("KeyConditionExpression").asText() : null;
        String filterExpr = request.has("FilterExpression")
                ? request.get("FilterExpression").asText() : null;
        Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;
        Boolean scanIndexForward = request.has("ScanIndexForward")
                ? request.get("ScanIndexForward").asBoolean() : null;
        String indexName = request.has("IndexName") ? request.get("IndexName").asText() : null;
        JsonNode exclusiveStartKey = request.has("ExclusiveStartKey")
                ? request.get("ExclusiveStartKey") : null;

        DynamoDbService.QueryResult result = dynamoDbService.query(tableName, keyConditions,
                exprAttrValues, keyConditionExpr, filterExpr, limit, scanIndexForward, indexName,
                exclusiveStartKey, exprAttrNames, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        result.items().forEach(itemsArray::add);
        response.set("Items", itemsArray);
        response.put("Count", result.items().size());
        response.put("ScannedCount", result.scannedCount());
        if (result.lastEvaluatedKey() != null) {
            response.set("LastEvaluatedKey", result.lastEvaluatedKey());
        }
        addConsumedCapacity(response, request, tableName, result.items().size(), false);
        return Response.ok(response).build();
    }

    private Response handleScan(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        String filterExpr = request.has("FilterExpression")
                ? request.get("FilterExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;
        JsonNode scanFilter = request.has("ScanFilter")
                ? request.get("ScanFilter") : null;
        Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;
        JsonNode exclusiveStartKey = request.has("ExclusiveStartKey")
                ? request.get("ExclusiveStartKey") : null;

        DynamoDbService.ScanResult result = dynamoDbService.scan(
                tableName, filterExpr, exprAttrNames, exprAttrValues, scanFilter, limit, exclusiveStartKey, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode itemsArray = objectMapper.createArrayNode();
        result.items().forEach(itemsArray::add);
        response.set("Items", itemsArray);
        response.put("Count", result.items().size());
        response.put("ScannedCount", result.scannedCount());
        if (result.lastEvaluatedKey() != null) {
            response.set("LastEvaluatedKey", result.lastEvaluatedKey());
        }
        addConsumedCapacity(response, request, tableName, result.items().size(), false);
        return Response.ok(response).build();
    }

    private Response handleBatchWriteItem(JsonNode request, String region) {
        JsonNode requestItems = request.get("RequestItems");
        if (requestItems == null || requestItems.isNull() || requestItems.isMissingNode()) {
            return Response.ok(objectMapper.createObjectNode()
                    .set("UnprocessedItems", objectMapper.createObjectNode())).build();
        }
        Map<String, List<JsonNode>> items = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> tables = requestItems.fields();
        while (tables.hasNext()) {
            var entry = tables.next();
            List<JsonNode> writes = new ArrayList<>();
            for (JsonNode writeReq : entry.getValue()) {
                writes.add(writeReq);
            }
            items.put(entry.getKey(), writes);
        }

        dynamoDbService.batchWriteItem(items, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("UnprocessedItems", objectMapper.createObjectNode());
        addBatchConsumedCapacity(response, request, items, true);
        return Response.ok(response).build();
    }

    private Response handleBatchGetItem(JsonNode request, String region) {
        JsonNode requestItems = request.get("RequestItems");
        if (requestItems == null || requestItems.isNull() || requestItems.isMissingNode()) {
            ObjectNode response = objectMapper.createObjectNode();
            response.set("Responses", objectMapper.createObjectNode());
            response.set("UnprocessedKeys", objectMapper.createObjectNode());
            return Response.ok(response).build();
        }
        Map<String, JsonNode> items = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> tables = requestItems.fields();
        while (tables.hasNext()) {
            var entry = tables.next();
            items.put(entry.getKey(), entry.getValue());
        }

        DynamoDbService.BatchGetResult result = dynamoDbService.batchGetItem(items, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode responses = objectMapper.createObjectNode();
        for (Map.Entry<String, List<JsonNode>> entry : result.responses().entrySet()) {
            ArrayNode tableItems = objectMapper.createArrayNode();
            entry.getValue().forEach(tableItems::add);
            responses.set(entry.getKey(), tableItems);
        }
        response.set("Responses", responses);
        response.set("UnprocessedKeys", objectMapper.createObjectNode());
        addBatchConsumedCapacity(response, request, items, false);
        return Response.ok(response).build();
    }

    private Response handleUpdateTable(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        Long readCapacity = null;
        Long writeCapacity = null;
        JsonNode pt = request.path("ProvisionedThroughput");
        if (!pt.isMissingNode()) {
            readCapacity = pt.has("ReadCapacityUnits") ? pt.get("ReadCapacityUnits").asLong() : null;
            writeCapacity = pt.has("WriteCapacityUnits") ? pt.get("WriteCapacityUnits").asLong() : null;
        }

        List<GlobalSecondaryIndex> gsiCreates = new ArrayList<>();
        List<String> gsiDeletes = new ArrayList<>();
        JsonNode gsiUpdates = request.path("GlobalSecondaryIndexUpdates");
        if (!gsiUpdates.isMissingNode() && gsiUpdates.isArray()) {
            for (JsonNode update : gsiUpdates) {
                JsonNode createNode = update.path("Create");
                if (!createNode.isMissingNode()) {
                    String indexName = createNode.path("IndexName").asText();
                    List<KeySchemaElement> gsiKeySchema = new ArrayList<>();
                    createNode.path("KeySchema").forEach(ks ->
                            gsiKeySchema.add(new KeySchemaElement(
                                    ks.path("AttributeName").asText(),
                                    ks.path("KeyType").asText())));
                    String projectionType = createNode.path("Projection").path("ProjectionType").asText("ALL");
                    JsonNode nonKeyAttrArray = createNode.path("Projection").path("NonKeyAttributes");
                    List<String> nonKeyAttributes = new ArrayList<>();
                    if (!nonKeyAttrArray.isMissingNode() && nonKeyAttrArray.isArray()){
                        for (JsonNode nonKeyAttr : nonKeyAttrArray){
                            nonKeyAttributes.add(nonKeyAttr.asText());
                        }
                    }
                    GlobalSecondaryIndex newGsi = new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes);
                    JsonNode newGsiPt = createNode.path("ProvisionedThroughput");
                    if (!newGsiPt.isMissingNode()) {
                        newGsi.getProvisionedThroughput().setReadCapacityUnits(newGsiPt.path("ReadCapacityUnits").asLong(0));
                        newGsi.getProvisionedThroughput().setWriteCapacityUnits(newGsiPt.path("WriteCapacityUnits").asLong(0));
                    }
                    gsiCreates.add(newGsi);
                }
                JsonNode deleteNode = update.path("Delete");
                if (!deleteNode.isMissingNode()) {
                    gsiDeletes.add(deleteNode.path("IndexName").asText());
                }
            }
        }

        List<AttributeDefinition> newAttrDefs = new ArrayList<>();
        JsonNode attrDefsNode = request.path("AttributeDefinitions");
        if (!attrDefsNode.isMissingNode() && attrDefsNode.isArray()) {
            for (JsonNode ad : attrDefsNode) {
                newAttrDefs.add(new AttributeDefinition(
                        ad.path("AttributeName").asText(),
                        ad.path("AttributeType").asText()));
            }
        }

        TableDefinition table = dynamoDbService.updateTable(tableName, readCapacity, writeCapacity,
                gsiCreates, gsiDeletes, newAttrDefs, region);

        String billingMode = request.has("BillingMode")
                ? request.get("BillingMode").asText() : null;
        if (billingMode != null) {
            table.setBillingMode(billingMode);
            if ("PAY_PER_REQUEST".equals(billingMode)) {
                table.getProvisionedThroughput().setReadCapacityUnits(0L);
                table.getProvisionedThroughput().setWriteCapacityUnits(0L);
            }
        }

        JsonNode streamSpec = request.path("StreamSpecification");
        if (!streamSpec.isMissingNode()) {
            boolean streamEnabled = streamSpec.path("StreamEnabled").asBoolean(false);
            if (streamEnabled) {
                String viewType = streamSpec.path("StreamViewType").asText("NEW_AND_OLD_IMAGES");
                StreamDescription sd = dynamoDbStreamService.enableStream(
                        tableName, table.getTableArn(), viewType, region);
                table.setStreamEnabled(true);
                table.setStreamArn(sd.getStreamArn());
                table.setStreamViewType(viewType);
            } else {
                dynamoDbStreamService.disableStream(tableName, region);
                table.setStreamEnabled(false);
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        return Response.ok(response).build();
    }

    private Response handleDescribeTimeToLive(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode ttlDesc = objectMapper.createObjectNode();
        if (table.isTtlEnabled() && table.getTtlAttributeName() != null) {
            ttlDesc.put("TimeToLiveStatus", "ENABLED");
            ttlDesc.put("AttributeName", table.getTtlAttributeName());
        } else {
            ttlDesc.put("TimeToLiveStatus", "DISABLED");
        }
        response.set("TimeToLiveDescription", ttlDesc);
        return Response.ok(response).build();
    }

    private Response handleUpdateTimeToLive(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode spec = request.path("TimeToLiveSpecification");
        String ttlAttributeName = spec.path("AttributeName").asText();
        boolean enabled = spec.path("Enabled").asBoolean(false);

        dynamoDbService.updateTimeToLive(tableName, ttlAttributeName, enabled, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode ttlSpec = objectMapper.createObjectNode();
        ttlSpec.put("AttributeName", ttlAttributeName);
        ttlSpec.put("Enabled", enabled);
        response.set("TimeToLiveSpecification", ttlSpec);
        return Response.ok(response).build();
    }

    private Response handleDescribeContinuousBackups(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ContinuousBackupsDescription", continuousBackupsDescriptionNode(table));
        return Response.ok(response).build();
    }

    private Response handleUpdateContinuousBackups(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode spec = request.path("PointInTimeRecoverySpecification");
        boolean enabled = spec.path("PointInTimeRecoveryEnabled").asBoolean(false);
        Integer recoveryPeriodInDays = spec.has("RecoveryPeriodInDays")
                ? spec.path("RecoveryPeriodInDays").asInt()
                : null;
        if (recoveryPeriodInDays != null && (recoveryPeriodInDays < 1 || recoveryPeriodInDays > 35)) {
            throw new AwsException("ValidationException",
                    "RecoveryPeriodInDays must be between 1 and 35", 400);
        }

        TableDefinition table = dynamoDbService.updateContinuousBackups(
                tableName, enabled, recoveryPeriodInDays, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ContinuousBackupsDescription", continuousBackupsDescriptionNode(table));
        return Response.ok(response).build();
    }

    private Response handleTransactWriteItems(JsonNode request, String region) {
        JsonNode transactItemsNode = request.path("TransactItems");
        List<JsonNode> transactItems = new ArrayList<>();
        if (transactItemsNode.isArray()) {
            transactItemsNode.forEach(transactItems::add);
        }

        try {
            dynamoDbService.transactWriteItems(transactItems, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (TransactionCanceledException e) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("__type", "TransactionCanceledException");
            body.put("message", e.getMessage());
            ArrayNode reasons = body.putArray("CancellationReasons");
            for (String reason : e.getCancellationReasons()) {
                ObjectNode r = objectMapper.createObjectNode();
                r.put("Code", reason.isEmpty() ? "None" : "ConditionalCheckFailed");
                r.put("Message", reason);
                reasons.add(r);
            }
            return Response.status(400).entity(body).build();
        }
    }

    private Response handleTransactGetItems(JsonNode request, String region) {
        JsonNode transactItemsNode = request.path("TransactItems");
        List<JsonNode> transactItems = new ArrayList<>();
        if (transactItemsNode.isArray()) {
            transactItemsNode.forEach(transactItems::add);
        }

        List<JsonNode> results = dynamoDbService.transactGetItems(transactItems, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode responsesArray = objectMapper.createArrayNode();
        for (JsonNode item : results) {
            ObjectNode entry = objectMapper.createObjectNode();
            if (item != null) {
                entry.set("Item", item);
            }
            responsesArray.add(entry);
        }
        response.set("Responses", responsesArray);
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                tags.put(tag.path("Key").asText(), tag.path("Value").asText());
            }
        }
        dynamoDbService.tagResource(resourceArn, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        List<String> tagKeys = new ArrayList<>();
        JsonNode keysNode = request.path("TagKeys");
        if (keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                tagKeys.add(key.asText());
            }
        }
        dynamoDbService.untagResource(resourceArn, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsOfResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        Map<String, String> tags = dynamoDbService.listTagsOfResource(resourceArn, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", entry.getKey());
            tagNode.put("Value", entry.getValue());
            tagsArray.add(tagNode);
        }
        response.set("Tags", tagsArray);
        return Response.ok(response).build();
    }

    private Response handleEnableKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        String streamArn = request.path("StreamArn").asText();

        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        String streamName = streamArn.substring(streamArn.lastIndexOf('/') + 1);
        try {
            kinesisService.describeStream(streamName, region);
        } catch (AwsException e) {
            throw new AwsException("ResourceNotFoundException",
                    "Kinesis stream not found: " + streamArn, 400);
        }

        Optional<KinesisStreamingDestination> existing = table.findKinesisStreamingDestination(streamArn);
        if (existing.isPresent() && "ACTIVE".equals(existing.get().getDestinationStatus())) {
            throw new AwsException("ValidationException",
                    "Table already has an active Kinesis streaming destination with this stream ARN", 400);
        }

        if (existing.isPresent()) {
            existing.get().setDestinationStatus("ACTIVE");
            existing.get().setDestinationStatusDescription("Kinesis streaming is enabled for this table");
        } else {
            table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(streamArn));
        }

        if (!table.isStreamEnabled()) {
            StreamDescription sd = dynamoDbStreamService.enableStream(
                    tableName, table.getTableArn(), "NEW_AND_OLD_IMAGES", region);
            table.setStreamEnabled(true);
            table.setStreamArn(sd.getStreamArn());
            table.setStreamViewType("NEW_AND_OLD_IMAGES");
        }

        dynamoDbService.persistTable(tableName, table, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", tableName);
        response.put("StreamArn", streamArn);
        response.put("DestinationStatus", "ACTIVE");
        response.put("DestinationStatusDescription", "Kinesis streaming is enabled for this table");
        return Response.ok(response).build();
    }

    private Response handleDisableKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        String streamArn = request.path("StreamArn").asText();

        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        Optional<KinesisStreamingDestination> existing = table.findKinesisStreamingDestination(streamArn);
        if (existing.isEmpty()) {
            throw new AwsException("ResourceNotFoundException",
                    "Kinesis streaming destination not found for stream: " + streamArn, 400);
        }

        if ("DISABLED".equals(existing.get().getDestinationStatus())) {
            throw new AwsException("ValidationException",
                    "Kinesis streaming destination is already disabled for stream: " + streamArn, 400);
        }

        existing.get().setDestinationStatus("DISABLED");
        existing.get().setDestinationStatusDescription("Kinesis streaming is disabled for this table");
        dynamoDbService.persistTable(tableName, table, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", tableName);
        response.put("StreamArn", streamArn);
        response.put("DestinationStatus", "DISABLED");
        response.put("DestinationStatusDescription", "Kinesis streaming is disabled for this table");
        return Response.ok(response).build();
    }

    private Response handleDescribeKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", tableName);

        ArrayNode destinations = objectMapper.createArrayNode();
        for (KinesisStreamingDestination dest : table.getKinesisStreamingDestinations()) {
            ObjectNode destNode = objectMapper.createObjectNode();
            destNode.put("StreamArn", dest.getStreamArn());
            destNode.put("DestinationStatus", dest.getDestinationStatus());
            destNode.put("DestinationStatusDescription", dest.getDestinationStatusDescription());
            destNode.put("ApproximateCreationDateTimePrecision",
                    dest.getApproximateCreationDateTimePrecision());
            destinations.add(destNode);
        }
        response.set("KinesisDataStreamDestinations", destinations);
        return Response.ok(response).build();
    }

    /**
     * Builds a ConsumedCapacity node if the request includes ReturnConsumedCapacity.
     * Uses simple estimates: 0.5 RCU per item read, 1.0 WCU per item written.
     */
    private void addConsumedCapacity(ObjectNode response, JsonNode request, String tableName,
                                      int itemCount, boolean isWrite) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC)) return;

        double cu = isWrite ? Math.max(1.0, itemCount) : Math.max(0.5, itemCount * 0.5);

        ObjectNode cc = objectMapper.createObjectNode();
        cc.put("TableName", tableName);
        cc.put("CapacityUnits", cu);

        if ("INDEXES".equals(returnCC)) {
            ObjectNode tableCap = objectMapper.createObjectNode();
            String indexName = request.path("IndexName").asText(null);
            if (indexName != null) {
                tableCap.put("CapacityUnits", 0.0);
                cc.set("Table", tableCap);
                ObjectNode gsiCaps = objectMapper.createObjectNode();
                ObjectNode indexCap = objectMapper.createObjectNode();
                indexCap.put("CapacityUnits", cu);
                gsiCaps.set(indexName, indexCap);
                cc.set("GlobalSecondaryIndexes", gsiCaps);
            } else {
                tableCap.put("CapacityUnits", cu);
                cc.set("Table", tableCap);
            }
        }

        response.set("ConsumedCapacity", cc);
    }

    /**
     * Builds a list-style ConsumedCapacity for batch operations.
     */
    private void addBatchConsumedCapacity(ObjectNode response, JsonNode request,
                                           Map<String, ?> tableItems, boolean isWrite) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC)) return;

        ArrayNode ccArray = objectMapper.createArrayNode();
        for (String tableName : tableItems.keySet()) {
            ObjectNode cc = objectMapper.createObjectNode();
            cc.put("TableName", tableName);
            cc.put("CapacityUnits", isWrite ? 1.0 : 0.5);
            if ("INDEXES".equals(returnCC)) {
                ObjectNode tableCap = objectMapper.createObjectNode();
                tableCap.put("CapacityUnits", isWrite ? 1.0 : 0.5);
                cc.set("Table", tableCap);
            }
            ccArray.add(cc);
        }
        response.set("ConsumedCapacity", ccArray);
    }

    private ObjectNode tableToNode(TableDefinition table) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("TableName", table.getTableName());
        node.put("TableStatus", table.getTableStatus());
        node.put("TableArn", table.getTableArn());
        node.put("CreationDateTime", table.getCreationDateTime().getEpochSecond());
        node.put("ItemCount", table.getItemCount());
        node.put("TableSizeBytes", table.getTableSizeBytes());
        node.put("DeletionProtectionEnabled", false);

        if ("PAY_PER_REQUEST".equals(table.getBillingMode())) {
            ObjectNode billing = objectMapper.createObjectNode();
            billing.put("BillingMode", "PAY_PER_REQUEST");
            billing.put("LastUpdateToPayPerRequestDateTime",
                    table.getCreationDateTime().getEpochSecond());
            node.set("BillingModeSummary", billing);
        }

        ObjectNode warmThroughput = objectMapper.createObjectNode();
        warmThroughput.put("Status", "ACTIVE");
        warmThroughput.put("ReadUnitsPerSecond", 0);
        warmThroughput.put("WriteUnitsPerSecond", 0);
        node.set("WarmThroughput", warmThroughput);

        ArrayNode keySchemaArray = objectMapper.createArrayNode();
        for (var ks : table.getKeySchema()) {
            ObjectNode ksNode = objectMapper.createObjectNode();
            ksNode.put("AttributeName", ks.getAttributeName());
            ksNode.put("KeyType", ks.getKeyType());
            keySchemaArray.add(ksNode);
        }
        node.set("KeySchema", keySchemaArray);

        ArrayNode attrDefsArray = objectMapper.createArrayNode();
        for (var ad : table.getAttributeDefinitions()) {
            ObjectNode adNode = objectMapper.createObjectNode();
            adNode.put("AttributeName", ad.getAttributeName());
            adNode.put("AttributeType", ad.getAttributeType());
            attrDefsArray.add(adNode);
        }
        node.set("AttributeDefinitions", attrDefsArray);

        ObjectNode ptNode = objectMapper.createObjectNode();
        ptNode.put("ReadCapacityUnits", table.getProvisionedThroughput().getReadCapacityUnits());
        ptNode.put("WriteCapacityUnits", table.getProvisionedThroughput().getWriteCapacityUnits());
        ptNode.put("NumberOfDecreasesToday", table.getProvisionedThroughput().getNumberOfDecreasesToday());
        node.set("ProvisionedThroughput", ptNode);

        List<GlobalSecondaryIndex> gsis = table.getGlobalSecondaryIndexes();
        if (gsis != null && !gsis.isEmpty()) {
            ArrayNode gsiArray = objectMapper.createArrayNode();
            for (GlobalSecondaryIndex gsi : gsis) {
                ObjectNode gsiNode = objectMapper.createObjectNode();
                gsiNode.put("IndexName", gsi.getIndexName());
                gsiNode.put("IndexArn", gsi.getIndexArn());
                gsiNode.put("IndexStatus", "ACTIVE");

                ArrayNode gsiKeySchema = objectMapper.createArrayNode();
                for (var ks : gsi.getKeySchema()) {
                    ObjectNode ksNode = objectMapper.createObjectNode();
                    ksNode.put("AttributeName", ks.getAttributeName());
                    ksNode.put("KeyType", ks.getKeyType());
                    gsiKeySchema.add(ksNode);
                }
                gsiNode.set("KeySchema", gsiKeySchema);

                ObjectNode projection = objectMapper.createObjectNode();
                projection.put("ProjectionType",
                        gsi.getProjectionType() != null ? gsi.getProjectionType() : "ALL");
                if ("INCLUDE".equals(gsi.getProjectionType())){
                    ArrayNode nonKeyAttributes = objectMapper.createArrayNode();
                    for (var attr : gsi.getNonKeyAttributes()){
                        nonKeyAttributes.add(attr);
                    }
                    projection.put("NonKeyAttributes", nonKeyAttributes);
                }
                gsiNode.set("Projection", projection);

                ObjectNode gsiPt = objectMapper.createObjectNode();
                gsiPt.put("ReadCapacityUnits", gsi.getProvisionedThroughput().getReadCapacityUnits());
                gsiPt.put("WriteCapacityUnits", gsi.getProvisionedThroughput().getWriteCapacityUnits());
                gsiPt.put("NumberOfDecreasesToday", gsi.getProvisionedThroughput().getNumberOfDecreasesToday());
                gsiNode.set("ProvisionedThroughput", gsiPt);
                gsiNode.put("IndexSizeBytes", gsi.getIndexSizeBytes());
                gsiNode.put("ItemCount", gsi.getItemCount());

                gsiArray.add(gsiNode);
            }
            node.set("GlobalSecondaryIndexes", gsiArray);
        }

        List<LocalSecondaryIndex> lsis = table.getLocalSecondaryIndexes();
        if (lsis != null && !lsis.isEmpty()) {
            ArrayNode lsiArray = objectMapper.createArrayNode();
            for (LocalSecondaryIndex lsi : lsis) {
                ObjectNode lsiNode = objectMapper.createObjectNode();
                lsiNode.put("IndexName", lsi.getIndexName());
                lsiNode.put("IndexArn", lsi.getIndexArn());

                ArrayNode lsiKeySchema = objectMapper.createArrayNode();
                for (var ks : lsi.getKeySchema()) {
                    ObjectNode ksNode = objectMapper.createObjectNode();
                    ksNode.put("AttributeName", ks.getAttributeName());
                    ksNode.put("KeyType", ks.getKeyType());
                    lsiKeySchema.add(ksNode);
                }
                lsiNode.set("KeySchema", lsiKeySchema);

                ObjectNode projection = objectMapper.createObjectNode();
                projection.put("ProjectionType",
                        lsi.getProjectionType() != null ? lsi.getProjectionType() : "ALL");
                lsiNode.set("Projection", projection);

                lsiNode.put("IndexSizeBytes", lsi.getIndexSizeBytes());
                lsiNode.put("ItemCount", lsi.getItemCount());

                lsiArray.add(lsiNode);
            }
            node.set("LocalSecondaryIndexes", lsiArray);
        }

        if (table.getStreamArn() != null) {
            ObjectNode streamSpecNode = objectMapper.createObjectNode();
            streamSpecNode.put("StreamEnabled", table.isStreamEnabled());
            streamSpecNode.put("StreamViewType", table.getStreamViewType());
            node.set("StreamSpecification", streamSpecNode);
            node.put("LatestStreamArn", table.getStreamArn());
            String label = table.getStreamArn().contains("/stream/")
                    ? table.getStreamArn().substring(table.getStreamArn().lastIndexOf("/stream/") + 8)
                    : "";
            node.put("LatestStreamLabel", label);
        }

        return node;
    }

    private ObjectNode continuousBackupsDescriptionNode(TableDefinition table) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ContinuousBackupsStatus", "ENABLED");

        ObjectNode pitrNode = objectMapper.createObjectNode();
        pitrNode.put("PointInTimeRecoveryStatus",
                table.isPointInTimeRecoveryEnabled() ? "ENABLED" : "DISABLED");
        if (table.isPointInTimeRecoveryEnabled()) {
            pitrNode.put("RecoveryPeriodInDays", table.getPointInTimeRecoveryRecoveryPeriodInDays());
        }
        node.set("PointInTimeRecoveryDescription", pitrNode);
        return node;
    }
}
