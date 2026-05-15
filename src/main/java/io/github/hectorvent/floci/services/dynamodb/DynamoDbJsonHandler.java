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
import io.github.hectorvent.floci.services.dynamodb.TransactionCanceledException;
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
            case "ExportTableToPointInTime" -> handleExportTable(request, region);
            case "DescribeExport" -> handleDescribeExport(request, region);
            case "ListExports" -> handleListExports(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException", "Operation " + action + " is not supported."))
                    .build();

        };
    }

    private Response handleCreateTable(JsonNode request, String region) {
        // Distinguish missing (undefined) from empty ("") for TableName
        String tableNameRaw = (request.has("TableName") && !request.path("TableName").isNull())
                ? request.path("TableName").asText() : null;
        String tableName = DynamoDbTableNames.requireShortName(tableNameRaw);

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
                JsonNode lsiNonKeyAttrArray = lsiNode.path("Projection").path("NonKeyAttributes");
                List<String> lsiNonKeyAttributes = new ArrayList<>();
                if (!lsiNonKeyAttrArray.isMissingNode() && lsiNonKeyAttrArray.isArray()) {
                    lsiNonKeyAttrArray.forEach(a -> lsiNonKeyAttributes.add(a.asText()));
                }
                lsis.add(new LocalSecondaryIndex(indexName, lsiKeySchema, null, projectionType, lsiNonKeyAttributes));
            }
        }

        String billingMode = request.has("BillingMode")
                ? request.get("BillingMode").asText() : null;

        boolean deletionProtection = request.path("DeletionProtectionEnabled").asBoolean(false);

        TableDefinition table = dynamoDbService.createTable(tableName, keySchema, attrDefs,
                readCapacity, writeCapacity, gsis, lsis, region);

        table.setDeletionProtectionEnabled(deletionProtection);

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
        if (table.isDeletionProtectionEnabled()) {
            throw new AwsException("ResourceInUseException",
                    "Table " + tableName + " can't be deleted while DeletionProtectionEnabled is set to true", 400);
        }
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
        Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;
        String exclusiveStart = request.has("ExclusiveStartTableName")
                ? request.get("ExclusiveStartTableName").asText() : null;

        if (limit != null) {
            if (limit < 1) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + limit
                        + "' at 'limit' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1", 400);
            }
            if (limit > 100) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + limit
                        + "' at 'limit' failed to satisfy constraint: "
                        + "Member must have value less than or equal to 100", 400);
            }
        }

        DynamoDbService.ListTablesResult result = dynamoDbService.listTables(region, limit, exclusiveStart);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tableNames = objectMapper.createArrayNode();
        result.tableNames().forEach(tableNames::add);
        response.set("TableNames", tableNames);
        if (result.lastEvaluatedTableName() != null) {
            response.put("LastEvaluatedTableName", result.lastEvaluatedTableName());
        }
        return Response.ok(response).build();
    }

    private static final Set<String> VALID_RETURN_VALUES_PUT = Set.of("NONE", "ALL_OLD");
    private static final Set<String> VALID_RETURN_CONSUMED_CAPACITY = Set.of("INDEXES", "TOTAL", "NONE");
    private static final Set<String> VALID_RETURN_ITEM_COLLECTION_METRICS = Set.of("SIZE", "NONE");

    private Response handlePutItem(JsonNode request, String region) {
        String tableName = request.has("TableName") ? request.path("TableName").asText() : null;
        DynamoDbTableNames.resolve(tableName); // validate tableName before other params
        JsonNode item = request.path("Item");
        String returnValues = request.path("ReturnValues").asText("NONE");
        String returnValuesOnConditionCheckFailure = request.path("ReturnValuesOnConditionCheckFailure").asText("NONE");
        String conditionExpression = request.has("ConditionExpression")
                ? request.get("ConditionExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;

        List<String> validationErrors = new ArrayList<>();
        String rcc = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rcc != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rcc)) {
            validationErrors.add("Value '" + rcc + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]");
        }
        String ricm = request.has("ReturnItemCollectionMetrics") ? request.get("ReturnItemCollectionMetrics").asText() : null;
        if (ricm != null && !VALID_RETURN_ITEM_COLLECTION_METRICS.contains(ricm)) {
            validationErrors.add("Value '" + ricm + "' at 'returnItemCollectionMetrics' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [SIZE, NONE]");
        }
        if (!VALID_RETURN_VALUES_PUT.contains(returnValues)) {
            validationErrors.add("Value '" + returnValues + "' at 'returnValues' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [NONE, ALL_OLD]");
        }
        if (!validationErrors.isEmpty()) {
            int n = validationErrors.size();
            throw new AwsException("ValidationException",
                    n + " validation error" + (n > 1 ? "s" : "") + " detected: "
                    + String.join("; ", validationErrors), 400);
        }

        JsonNode expected = request.has("Expected") ? request.get("Expected") : null;
        String conditionalOperator = request.has("ConditionalOperator")
                ? request.get("ConditionalOperator").asText() : "AND";

        if (conditionExpression != null && expected != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {Expected} Expression parameters: {ConditionExpression}", 400);
        }

        if (exprAttrValues != null && conditionExpression == null) {
            throw new AwsException("ValidationException",
                    "ExpressionAttributeValues can only be specified when using expressions: ConditionExpression is null", 400);
        }

        validateItemSets(item);

        JsonNode oldItem = null;
        if ("ALL_OLD".equals(returnValues) || expected != null) {
            dynamoDbService.describeTable(tableName, region);
            oldItem = dynamoDbService.getItem(tableName, item, region);
        }

        if (expected != null) {
            evaluateLegacyExpected(oldItem, expected, conditionalOperator, returnValuesOnConditionCheckFailure);
        }

        dynamoDbService.putItem(tableName, item, conditionExpression, exprAttrNames, exprAttrValues, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_OLD" .equals(returnValues) && oldItem != null) {
            response.set("Attributes", oldItem);
        }
        addItemCollectionMetrics(response, request, tableName, item, region);
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private Response handleGetItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        JsonNode key = request.path("Key");
        String projectionExpression = request.has("ProjectionExpression")
                ? request.get("ProjectionExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode attributesToGet = request.has("AttributesToGet") ? request.get("AttributesToGet") : null;

        if (attributesToGet != null && projectionExpression != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributesToGet} Expression parameters: {ProjectionExpression}", 400);
        }

        // Validate enums before table lookup
        String rccGet = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rccGet != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccGet)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + rccGet + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]", 400);
        }

        // Validate ProjectionExpression syntax and reserved words before item lookup
        if (projectionExpression != null) {
            ProjectionEvaluator.validateSyntax(projectionExpression, "ProjectionExpression");
            DynamoDbReservedWords.check(projectionExpression, "ProjectionExpression");
        }

        JsonNode item = dynamoDbService.getItem(tableName, key, region);
        if (item != null && projectionExpression != null) {
            item = ProjectionEvaluator.project(item, projectionExpression, exprAttrNames);
        } else if (item != null && attributesToGet != null) {
            Set<String> keep = new HashSet<>();
            attributesToGet.forEach(n -> keep.add(n.asText()));
            item = ProjectionEvaluator.trimToAttributes((ObjectNode) item, keep);
        }

        ObjectNode response = objectMapper.createObjectNode();
        if (item != null) {
            response.set("Item", item);
        }
        addConsumedCapacity(response, request, tableName, item != null ? 1 : 0, false);
        return Response.ok(response).build();
    }

    private static final Set<String> VALID_RETURN_VALUES_DELETE = Set.of("NONE", "ALL_OLD");
    private static final Set<String> VALID_RETURN_VALUES_UPDATE =
            Set.of("NONE", "ALL_OLD", "ALL_NEW", "UPDATED_OLD", "UPDATED_NEW");

    private Response handleDeleteItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        DynamoDbTableNames.resolve(tableName); // validate tableName first
        JsonNode key = request.path("Key");
        String returnValues = request.path("ReturnValues").asText("NONE");
        String returnValuesOnConditionCheckFailure = request.path("ReturnValuesOnConditionCheckFailure").asText("NONE");
        String conditionExpression = request.has("ConditionExpression")
                ? request.get("ConditionExpression").asText() : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;

        // Validate ReturnValues + ReturnConsumedCapacity together
        List<String> delValidationErrors = new ArrayList<>();
        String rccDel = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rccDel != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccDel)) {
            delValidationErrors.add("Value '" + rccDel + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]");
        }
        if (!VALID_RETURN_VALUES_DELETE.contains(returnValues)) {
            delValidationErrors.add("Value '" + returnValues + "' at 'returnValues' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [NONE, ALL_OLD]");
        }
        if (!delValidationErrors.isEmpty()) {
            int n = delValidationErrors.size();
            throw new AwsException("ValidationException",
                    n + " validation error" + (n > 1 ? "s" : "") + " detected: "
                    + String.join("; ", delValidationErrors), 400);
        }

        JsonNode expectedDel = request.has("Expected") ? request.get("Expected") : null;
        String condOpDel = request.has("ConditionalOperator")
                ? request.get("ConditionalOperator").asText() : "AND";
        if (expectedDel != null) {
            JsonNode existingForDel = dynamoDbService.getItem(tableName, key, region);
            evaluateLegacyExpected(existingForDel, expectedDel, condOpDel, returnValuesOnConditionCheckFailure);
        }

        JsonNode oldItem = dynamoDbService.deleteItem(tableName, key, conditionExpression,
                exprAttrNames, exprAttrValues, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_OLD" .equals(returnValues) && oldItem != null) {
            response.set("Attributes", oldItem);
        }
        addItemCollectionMetrics(response, request, tableName, key, region);
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private Response handleUpdateItem(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        DynamoDbTableNames.resolve(tableName); // validate tableName first
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

        // Validate ReturnValues + ReturnConsumedCapacity together before table lookup
        List<String> updValidationErrors = new ArrayList<>();
        String rccUpd = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rccUpd != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccUpd)) {
            updValidationErrors.add("Value '" + rccUpd + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]");
        }
        if (!VALID_RETURN_VALUES_UPDATE.contains(returnValues)) {
            updValidationErrors.add("Value '" + returnValues + "' at 'returnValues' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [NONE, ALL_OLD, ALL_NEW, UPDATED_OLD, UPDATED_NEW]");
        }
        if (!updValidationErrors.isEmpty()) {
            int n = updValidationErrors.size();
            throw new AwsException("ValidationException",
                    n + " validation error" + (n > 1 ? "s" : "") + " detected: "
                    + String.join("; ", updValidationErrors), 400);
        }

        JsonNode updateData = attributeUpdates.isMissingNode() ? null : attributeUpdates;

        if (updateData != null && updateExpression != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributeUpdates} Expression parameters: {UpdateExpression}", 400);
        }

        // Validate EAN/EAV usage across UpdateExpression + ConditionExpression
        if (updateExpression != null) {
            // Pre-validate syntax: expression must start with a valid clause keyword
            String ue = updateExpression.trim();
            if (!ue.isEmpty()) {
                String upper = ue.toUpperCase();
                if (!upper.startsWith("SET ") && !upper.startsWith("REMOVE ") && !upper.startsWith("ADD ") && !upper.startsWith("DELETE ")) {
                    String[] parts = ue.split("\\s+", 3);
                    String token = parts[0];
                    String near = parts.length >= 2
                            ? (token + " " + parts[1]).substring(0, Math.min(token.length() + 1 + parts[1].length(), 20))
                            : token;
                    throw new AwsException("ValidationException",
                            "Invalid UpdateExpression: Syntax error; token: \"" + token + "\", near: \"" + near + "\"", 400);
                }
            }
            Set<String> colonTokens = extractColonTokens(updateExpression);
            for (String token : colonTokens) {
                if (exprAttrValues == null || !exprAttrValues.has(token)) {
                    throw new AwsException("ValidationException",
                            "Invalid UpdateExpression: An expression attribute value used in expression is not defined; attribute value: " + token, 400);
                }
            }
            Set<String> hashTokens = extractHashTokens(updateExpression, conditionExpression);
            checkUnusedEan(exprAttrNames, hashTokens);
            Set<String> colonTokensAll = extractColonTokens(updateExpression, conditionExpression);
            checkUnusedEav(exprAttrValues, colonTokensAll);
        }

        DynamoDbService.UpdateResult result = dynamoDbService.updateItem(
                tableName, key, updateData, updateExpression, exprAttrNames, exprAttrValues,
                returnValues, conditionExpression, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_NEW" .equals(returnValues) && result.newItem() != null) {
            response.set("Attributes", result.newItem());
        } else if ("ALL_OLD" .equals(returnValues) && result.oldItem() != null) {
            response.set("Attributes", result.oldItem());
        } else if ("UPDATED_NEW".equals(returnValues) && result.newItem() != null) {
            // When oldItem is null (new item created), diff against the key so key
            // attributes are excluded - matching AWS behavior where UPDATED_NEW
            // returns only the attributes set by the expression.
            JsonNode baseline = result.oldItem() != null ? result.oldItem() : key;
            response.set("Attributes", getChangedAttributes(result.newItem(), baseline));
        } else if ("UPDATED_OLD".equals(returnValues) && result.oldItem() != null) {
            response.set("Attributes", getChangedAttributes(result.oldItem(), result.newItem()));
        }
        addItemCollectionMetrics(response, request, tableName, key, region);
        addConsumedCapacity(response, request, tableName, 1, true);
        return Response.ok(response).build();
    }

    private void evaluateLegacyExpected(JsonNode existing, JsonNode expected, String conditionalOperator,
                                          String returnValuesOnConditionCheckFailure) {
        if (expected == null) return;
        boolean useOr = "OR".equals(conditionalOperator);
        boolean overall = !useOr;
        var fields = expected.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String attrName = entry.getKey();
            JsonNode condition = entry.getValue();
            JsonNode attrValue = existing != null ? existing.get(attrName) : null;
            boolean condResult;
            if (condition.has("Exists")) {
                boolean exists = condition.get("Exists").asBoolean();
                condResult = exists ? attrValue != null : attrValue == null;
            } else {
                condResult = dynamoDbService.matchesKeyConditionPublic(attrValue, condition);
            }
            if (useOr) overall = overall || condResult;
            else overall = overall && condResult;
        }
        if (!overall) {
            if ("ALL_OLD".equals(returnValuesOnConditionCheckFailure)) {
                throw new ConditionalCheckFailedException(existing);
            } else {
                throw new ConditionalCheckFailedException(null);
            }
        }
    }

    private void addItemCollectionMetrics(ObjectNode response, JsonNode request, String tableName,
                                           JsonNode itemOrKey, String region) {
        String ricm = request.has("ReturnItemCollectionMetrics")
                ? request.get("ReturnItemCollectionMetrics").asText() : null;
        if (!"SIZE".equals(ricm) || itemOrKey == null) return;
        try {
            TableDefinition table = dynamoDbService.describeTable(tableName, region);
            String pkName = table.getPartitionKeyName();
            JsonNode pkValue = itemOrKey.get(pkName);
            if (pkValue == null) return;
            ObjectNode metrics = objectMapper.createObjectNode();
            ObjectNode collKey = objectMapper.createObjectNode();
            collKey.set(pkName, pkValue);
            metrics.set("ItemCollectionKey", collKey);
            ArrayNode sizeRange = objectMapper.createArrayNode();
            sizeRange.add(0.0);
            sizeRange.add(1.0);
            metrics.set("SizeEstimateRangeGB", sizeRange);
            response.set("ItemCollectionMetrics", metrics);
        } catch (Exception ignored) {}
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

    private static final Set<String> VALID_SELECT = Set.of(
            "ALL_ATTRIBUTES", "ALL_PROJECTED_ATTRIBUTES", "SPECIFIC_ATTRIBUTES", "COUNT");

    private Response handleQuery(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        DynamoDbTableNames.resolve(tableName); // validate tableName first
        JsonNode keyConditions = request.has("KeyConditions") ? request.get("KeyConditions") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;
        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        String keyConditionExpr = request.has("KeyConditionExpression")
                ? request.get("KeyConditionExpression").asText() : null;
        String filterExpr = request.has("FilterExpression")
                ? request.get("FilterExpression").asText() : null;
        JsonNode queryFilter = request.has("QueryFilter") ? request.get("QueryFilter") : null;
        JsonNode attributesToGet = request.has("AttributesToGet") ? request.get("AttributesToGet") : null;
        Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;
        Boolean scanIndexForward = request.has("ScanIndexForward")
                ? request.get("ScanIndexForward").asBoolean() : null;
        String indexName = request.has("IndexName") ? request.get("IndexName").asText() : null;
        JsonNode exclusiveStartKey = request.has("ExclusiveStartKey")
                ? request.get("ExclusiveStartKey") : null;
        String select = request.has("Select") ? request.get("Select").asText() : null;
        String projectionExpression = request.has("ProjectionExpression")
                ? request.get("ProjectionExpression").asText() : null;

        // Validate Limit
        if (limit != null && limit < 1) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'Limit' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 1", 400);
        }

        // Validate ReturnConsumedCapacity before Select
        String rccQuery = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rccQuery != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccQuery)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + rccQuery + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]", 400);
        }

        if (keyConditionExpr != null && keyConditions != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {KeyConditions} Expression parameters: {KeyConditionExpression}", 400);
        }
        if (filterExpr != null && queryFilter != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {QueryFilter} Expression parameters: {FilterExpression}", 400);
        }

        if (keyConditionExpr == null && keyConditions == null) {
            throw new AwsException("ValidationException",
                    "Either KeyConditions or KeyConditionExpression must be provided", 400);
        }

        // Validate empty KCE
        if (keyConditionExpr != null && keyConditionExpr.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Invalid KeyConditionExpression: The expression can not be empty;", 400);
        }

        if (select != null && !VALID_SELECT.contains(select)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + select + "' at 'select' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [SPECIFIC_ATTRIBUTES, COUNT, ALL_ATTRIBUTES, ALL_PROJECTED_ATTRIBUTES]", 400);
        }

        if ("SPECIFIC_ATTRIBUTES".equals(select) && projectionExpression == null) {
            throw new AwsException("ValidationException",
                    "Select type SPECIFIC_ATTRIBUTES requires the ProjectionExpression to be provided.", 400);
        }

        // ConsistentRead on GSI check
        boolean consistentRead = request.path("ConsistentRead").asBoolean(false);
        if (consistentRead && indexName != null) {
            TableDefinition queryTable = dynamoDbService.describeTable(tableName, region);
            if (queryTable.findGsi(indexName).isPresent()) {
                throw new AwsException("ValidationException",
                        "Consistent reads are not supported on global secondary indexes", 400);
            }
        }

        // Validate EAN/EAV usage when using expression format
        if (keyConditionExpr != null) {
            // Check undefined #tokens in FilterExpression
            if (filterExpr != null) {
                for (String token : extractHashTokens(filterExpr)) {
                    if (exprAttrNames == null || !exprAttrNames.has(token)) {
                        throw new AwsException("ValidationException",
                                "Invalid FilterExpression: An expression attribute name used in the document path is not defined; attribute name: " + token, 400);
                    }
                }
            }
            // Check unused EAN across all expressions (KCE + FE + PE)
            Set<String> hashTokens = extractHashTokens(keyConditionExpr, filterExpr, projectionExpression);
            checkUnusedEan(exprAttrNames, hashTokens);
        }

        DynamoDbService.QueryResult result = dynamoDbService.query(tableName, keyConditions,
                exprAttrValues, keyConditionExpr, filterExpr, limit, scanIndexForward, indexName,
                exclusiveStartKey, exprAttrNames, region);

        List<JsonNode> queryItems = result.items();
        // Apply QueryFilter (legacy API)
        if (queryFilter != null) {
            final JsonNode qf = queryFilter;
            queryItems = queryItems.stream()
                    .filter(i -> dynamoDbService.matchesScanFilterPublic(i, qf))
                    .toList();
        }
        // Apply index projection (KEYS_ONLY / INCLUDE) when querying a secondary index
        if (indexName != null && projectionExpression == null && attributesToGet == null) {
            TableDefinition queryTable = dynamoDbService.describeTable(tableName, region);
            queryItems = applyIndexProjection(queryItems, indexName, queryTable, select);
        }
        if (projectionExpression != null) {
            queryItems = queryItems.stream()
                    .map(i -> (JsonNode) ProjectionEvaluator.project(i, projectionExpression, exprAttrNames))
                    .toList();
        } else if (attributesToGet != null) {
            Set<String> keep = new HashSet<>();
            attributesToGet.forEach(n -> keep.add(n.asText()));
            queryItems = queryItems.stream()
                    .map(i -> (JsonNode) ProjectionEvaluator.trimToAttributes((ObjectNode) i, keep))
                    .toList();
        }

        ObjectNode response = objectMapper.createObjectNode();
        if ("COUNT".equals(select)) {
            response.put("Count", result.items().size());
            response.put("ScannedCount", result.scannedCount());
        } else {
            ArrayNode itemsArray = objectMapper.createArrayNode();
            queryItems.forEach(itemsArray::add);
            response.set("Items", itemsArray);
            response.put("Count", queryItems.size());
            response.put("ScannedCount", result.scannedCount());
        }
        if (result.lastEvaluatedKey() != null) {
            response.set("LastEvaluatedKey", result.lastEvaluatedKey());
        }
        addConsumedCapacity(response, request, tableName, queryItems.size(), false);
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
        String select = request.has("Select") ? request.get("Select").asText() : null;
        String indexNameScan = request.has("IndexName") ? request.get("IndexName").asText() : null;
        Integer segment = request.has("Segment") ? request.get("Segment").asInt() : null;
        Integer totalSegments = request.has("TotalSegments") ? request.get("TotalSegments").asInt() : null;

        if (filterExpr != null && scanFilter != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {ScanFilter} Expression parameters: {FilterExpression}", 400);
        }

        if (limit != null && limit < 1) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + limit + "' at 'limit' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 1", 400);
        }

        if (segment != null && totalSegments == null) {
            throw new AwsException("ValidationException",
                    "The TotalSegments parameter is required but was not present in the request when Segment parameter is present", 400);
        }
        if (totalSegments != null && segment == null) {
            throw new AwsException("ValidationException",
                    "The Segment parameter is required but was not present in the request when parameter TotalSegments is present", 400);
        }
        if (segment != null && totalSegments != null && segment >= totalSegments) {
            throw new AwsException("ValidationException",
                    "The Segment parameter is zero-based and must be less than parameter TotalSegments: "
                    + "Segment: " + segment + " is not less than TotalSegments: " + totalSegments, 400);
        }

        String projectionExpressionScan = request.has("ProjectionExpression")
                ? request.get("ProjectionExpression").asText() : null;

        DynamoDbService.ScanResult result = dynamoDbService.scan(
                tableName, filterExpr, exprAttrNames, exprAttrValues, scanFilter, limit, exclusiveStartKey, region);

        List<JsonNode> scanItems = result.items();
        // Apply parallel scan segment partitioning
        if (segment != null && totalSegments != null && totalSegments > 1) {
            final int seg = segment, total = totalSegments;
            final List<JsonNode> allItems = scanItems;
            scanItems = new ArrayList<>();
            for (int si = 0; si < allItems.size(); si++) {
                if (si % total == seg) scanItems.add(allItems.get(si));
            }
        }
        // Apply index projection (KEYS_ONLY / INCLUDE) when scanning a secondary index
        if (indexNameScan != null && projectionExpressionScan == null) {
            TableDefinition scanTable = dynamoDbService.describeTable(tableName, region);
            scanItems = applyIndexProjection(scanItems, indexNameScan, scanTable, select);
        }
        JsonNode attributesToGetScan = request.has("AttributesToGet") ? request.get("AttributesToGet") : null;
        if (projectionExpressionScan != null) {
            scanItems = scanItems.stream()
                    .map(i -> (JsonNode) ProjectionEvaluator.project(i, projectionExpressionScan, exprAttrNames))
                    .toList();
        } else if (attributesToGetScan != null) {
            Set<String> keep = new HashSet<>();
            attributesToGetScan.forEach(n -> keep.add(n.asText()));
            scanItems = scanItems.stream()
                    .map(i -> (JsonNode) ProjectionEvaluator.trimToAttributes((ObjectNode) i, keep))
                    .toList();
        }

        ObjectNode response = objectMapper.createObjectNode();
        if ("COUNT".equals(select)) {
            response.put("Count", result.items().size());
            response.put("ScannedCount", result.scannedCount());
        } else {
            ArrayNode itemsArray = objectMapper.createArrayNode();
            scanItems.forEach(itemsArray::add);
            response.set("Items", itemsArray);
            response.put("Count", scanItems.size());
            response.put("ScannedCount", result.scannedCount());
        }
        if (result.lastEvaluatedKey() != null) {
            response.set("LastEvaluatedKey", result.lastEvaluatedKey());
        }
        addConsumedCapacity(response, request, tableName, scanItems.size(), false);
        return Response.ok(response).build();
    }

    private Response handleBatchWriteItem(JsonNode request, String region) {
        JsonNode requestItems = request.get("RequestItems");
        if (requestItems == null || requestItems.isNull() || requestItems.isMissingNode()
                || !requestItems.fields().hasNext()) {
            throw new AwsException("ValidationException",
                    "The requestItems parameter is required for BatchWriteItem", 400);
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

        // Per-table check: max 25 write requests
        for (Map.Entry<String, List<JsonNode>> entry : items.entrySet()) {
            if (entry.getValue().size() > 25) {
                // Build a representation of the requestItems
                StringBuilder sb = new StringBuilder("{");
                sb.append(entry.getKey()).append("=[");
                List<String> reprs = new ArrayList<>();
                for (JsonNode w : entry.getValue()) {
                    reprs.add(w.toString());
                }
                sb.append(String.join(", ", reprs)).append("]}");
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + sb + "' at 'requestItems' failed to satisfy constraint: "
                        + "Map value must satisfy constraint: [Member must have length less than or equal to 25, "
                        + "Member must have length greater than or equal to 1]", 400);
            }
        }

        for (Map.Entry<String, List<JsonNode>> entry : items.entrySet()) {
            TableDefinition bwTable = dynamoDbService.describeTable(entry.getKey(), region);
            Set<String> seen = new HashSet<>();
            for (JsonNode writeReq : entry.getValue()) {
                JsonNode keyNode = writeReq.has("PutRequest")
                        ? writeReq.get("PutRequest").get("Item")
                        : writeReq.get("DeleteRequest").get("Key");
                String key = dynamoDbService.buildItemKey(bwTable, keyNode);
                if (!seen.add(key)) {
                    throw new AwsException("ValidationException",
                            "Provided list of item keys contains duplicates", 400);
                }
            }
        }

        dynamoDbService.batchWriteItem(items, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("UnprocessedItems", objectMapper.createObjectNode());
        addBatchConsumedCapacity(response, request, items, true);
        return Response.ok(response).build();
    }

    private Response handleBatchGetItem(JsonNode request, String region) {
        JsonNode requestItems = request.get("RequestItems");
        if (requestItems == null || requestItems.isNull() || requestItems.isMissingNode()
                || !requestItems.fields().hasNext()) {
            throw new AwsException("ValidationException",
                    "The requestItems parameter is required for BatchGetItem", 400);
        }
        Map<String, JsonNode> items = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> tables = requestItems.fields();
        while (tables.hasNext()) {
            var entry = tables.next();
            items.put(entry.getKey(), entry.getValue());
        }

        // Per-table check: max 100 keys
        for (Map.Entry<String, JsonNode> entry : items.entrySet()) {
            JsonNode keysNode = entry.getValue().has("Keys") ? entry.getValue().get("Keys") : null;
            if (keysNode != null && keysNode.size() > 100) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'RequestItems." + entry.getKey()
                        + ".member.Keys' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 100", 400);
            }
        }

        for (Map.Entry<String, JsonNode> entry : items.entrySet()) {
            TableDefinition bgTable = dynamoDbService.describeTable(entry.getKey(), region);
            JsonNode keys = entry.getValue().get("Keys");
            if (keys == null || !keys.isArray()) continue;
            Set<String> seen = new HashSet<>();
            for (JsonNode key : keys) {
                String itemKey = dynamoDbService.buildItemKey(bgTable, key);
                if (!seen.add(itemKey)) {
                    throw new AwsException("ValidationException",
                            "Provided list of item keys contains duplicates", 400);
                }
            }
        }

        DynamoDbService.BatchGetResult result = dynamoDbService.batchGetItem(items, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode responses = objectMapper.createObjectNode();
        for (Map.Entry<String, List<JsonNode>> entry : result.responses().entrySet()) {
            String tblName = entry.getKey();
            JsonNode tableRequest = requestItems.get(tblName);
            String projExpr = tableRequest != null && tableRequest.has("ProjectionExpression")
                    ? tableRequest.get("ProjectionExpression").asText() : null;
            JsonNode exprNames = tableRequest != null && tableRequest.has("ExpressionAttributeNames")
                    ? tableRequest.get("ExpressionAttributeNames") : null;
            JsonNode attrToGet = tableRequest != null && tableRequest.has("AttributesToGet")
                    ? tableRequest.get("AttributesToGet") : null;
            ArrayNode tableItems = objectMapper.createArrayNode();
            for (JsonNode item : entry.getValue()) {
                JsonNode projected;
                if (projExpr != null) {
                    projected = ProjectionEvaluator.project(item, projExpr, exprNames);
                } else if (attrToGet != null) {
                    Set<String> keep = new HashSet<>();
                    attrToGet.forEach(n -> keep.add(n.asText()));
                    projected = ProjectionEvaluator.trimToAttributes((ObjectNode) item, keep);
                } else {
                    projected = item;
                }
                tableItems.add(projected);
            }
            responses.set(tblName, tableItems);
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

        String billingModeCheck = request.has("BillingMode") ? request.get("BillingMode").asText() : null;
        if ("PAY_PER_REQUEST".equals(billingModeCheck) && !pt.isMissingNode()) {
            throw new AwsException("ValidationException",
                    "ProvisionedThroughput cannot be specified when BillingMode is PAY_PER_REQUEST", 400);
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

        JsonNode deletionProtectionNode = request.path("DeletionProtectionEnabled");
        if (!deletionProtectionNode.isMissingNode()) {
            table.setDeletionProtectionEnabled(deletionProtectionNode.asBoolean());
        }

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
                        table.getTableName(), table.getTableArn(), viewType, region);
                table.setStreamEnabled(true);
                table.setStreamArn(sd.getStreamArn());
                table.setStreamViewType(viewType);
            } else {
                dynamoDbStreamService.disableStream(table.getTableName(), region);
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
        if (ttlAttributeName == null || ttlAttributeName.isBlank()) {
            throw new AwsException("ValidationException",
                    "TimeToLiveSpecification.AttributeName must not be empty", 400);
        }
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
        if (transactItemsNode.isMissingNode() || transactItemsNode.isNull() || !transactItemsNode.isArray()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
        if (transactItemsNode.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '[]' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
        if (transactItemsNode.size() > 100) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + transactItemsNode + "' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 100", 400);
        }

        // Check 4MB total item size limit
        final int MAX_TRANSACTION_BYTES = 4 * 1024 * 1024;
        int totalSize = 0;
        for (JsonNode txItem : transactItemsNode) {
            JsonNode item = txItem.has("Put") ? txItem.get("Put").get("Item")
                         : txItem.has("Update") ? txItem.get("Update").get("Key")
                         : txItem.has("Delete") ? txItem.get("Delete").get("Key")
                         : txItem.has("ConditionCheck") ? txItem.get("ConditionCheck").get("Key")
                         : null;
            if (item != null) totalSize += DynamoDbItemSize.calculateItemSize(item);
        }
        if (totalSize > MAX_TRANSACTION_BYTES) {
            throw new AwsException("ValidationException",
                    "Transaction failed: The total size of all items in the transaction request cannot exceed 4 MB", 400);
        }

        Map<String, TableDefinition> tableCache = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode txItem : transactItemsNode) {
            JsonNode op = txItem.has("Put") ? txItem.get("Put")
                        : txItem.has("Delete") ? txItem.get("Delete")
                        : txItem.has("Update") ? txItem.get("Update")
                        : txItem.has("ConditionCheck") ? txItem.get("ConditionCheck") : null;
            if (op == null) continue;
            String opTable = op.path("TableName").asText();
            TableDefinition txTable = tableCache.computeIfAbsent(opTable,
                    tn -> dynamoDbService.describeTable(tn, region));
            JsonNode keyNode = op.has("Item") ? op.get("Item") : op.get("Key");
            if (keyNode == null) continue;
            String key = region + "::" + opTable + "::" + dynamoDbService.buildItemKey(txTable, keyNode);
            if (!seen.add(key)) {
                throw new AwsException("ValidationException",
                        "Transaction request cannot include multiple operations on one item", 400);
            }
        }

        List<JsonNode> transactItems = new ArrayList<>();
        transactItemsNode.forEach(transactItems::add);

        // ClientRequestToken makes TransactWriteItems idempotent within ~10 minutes.
        // Forward the token and the raw request body so the service can hash the body
        // and reject replays whose parameters changed.
        String clientRequestToken = request.has("ClientRequestToken") && !request.get("ClientRequestToken").isNull()
                ? request.get("ClientRequestToken").asText()
                : null;

        try {
            dynamoDbService.transactWriteItems(transactItems, region, clientRequestToken, request);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (TransactionCanceledException e) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("__type", "TransactionCanceledException");
            body.put("message", e.getMessage());
            ArrayNode reasons = body.putArray("CancellationReasons");
            for (TransactionCanceledException.CancellationReason reason : e.getCancellationReasons()) {
                ObjectNode r = objectMapper.createObjectNode();
                r.put("Code", reason.code().isEmpty() ? "None" : reason.code());
                r.put("Message", reason.code().isEmpty() ? "" : "The conditional request failed");
                if (reason.item() != null) {
                    r.set("Item", reason.item());
                }
                reasons.add(r);
            }
            return Response.status(400).entity(body).build();
        }
    }

    private Response handleTransactGetItems(JsonNode request, String region) {
        JsonNode transactItemsNode = request.path("TransactItems");
        if (transactItemsNode.isMissingNode() || transactItemsNode.isNull() || !transactItemsNode.isArray()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
        if (transactItemsNode.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '[]' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
        if (transactItemsNode.size() > 100) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + transactItemsNode + "' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 100", 400);
        }

        Map<String, TableDefinition> tableCache = new HashMap<>();
        Set<String> seenGet = new HashSet<>();
        for (JsonNode txItem : transactItemsNode) {
            JsonNode get = txItem.has("Get") ? txItem.get("Get") : null;
            if (get == null) continue;
            String opTable = get.path("TableName").asText();
            TableDefinition txTable = tableCache.computeIfAbsent(opTable,
                    tn -> dynamoDbService.describeTable(tn, region));
            JsonNode keyNode = get.get("Key");
            if (keyNode == null) continue;
            try {
                String key = region + "::" + opTable + "::" + dynamoDbService.buildItemKey(txTable, keyNode);
                if (!seenGet.add(key)) {
                    throw new AwsException("ValidationException",
                            "Transaction request cannot include multiple operations on one item", 400);
                }
            } catch (AwsException e) {
                if ("Transaction request cannot include multiple operations on one item".equals(e.getMessage())) {
                    throw e;
                }
                // Per-action validation error - will be surfaced via TransactionCanceledException in transactGetItems
            }
        }

        // Validate ProjectionExpression syntax in each Get action before executing
        for (JsonNode txItem : transactItemsNode) {
            JsonNode get = txItem.has("Get") ? txItem.get("Get") : null;
            if (get == null) continue;
            String pe = get.has("ProjectionExpression") ? get.get("ProjectionExpression").asText() : null;
            if (pe != null) {
                ProjectionEvaluator.validateSyntax(pe, "ProjectionExpression");
            }
        }

        List<JsonNode> transactItems = new ArrayList<>();
        transactItemsNode.forEach(transactItems::add);

        List<JsonNode> results;
        try {
            results = dynamoDbService.transactGetItems(transactItems, region);
        } catch (TransactionCanceledException e) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("__type", "TransactionCanceledException");
            body.put("message", e.getMessage());
            ArrayNode reasons = body.putArray("CancellationReasons");
            for (TransactionCanceledException.CancellationReason reason : e.getCancellationReasons()) {
                ObjectNode r = objectMapper.createObjectNode();
                r.put("Code", reason.code().isEmpty() ? "None" : reason.code());
                if (!reason.code().isEmpty()) {
                    r.put("Message", "");
                }
                if (reason.item() != null) {
                    r.set("Item", reason.item());
                }
                reasons.add(r);
            }
            return Response.status(400).entity(body).build();
        }

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

    private static final java.util.regex.Pattern DYNAMODB_TABLE_ARN_PATTERN =
            java.util.regex.Pattern.compile(
                    "^arn:aws:dynamodb:[a-z0-9-]+:\\d{12}:table/[a-zA-Z0-9._-]+$");

    private static boolean isValidDynamoDbTableArn(String arn) {
        return arn != null && DYNAMODB_TABLE_ARN_PATTERN.matcher(arn).matches();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        if (!isValidDynamoDbTableArn(resourceArn)) {
            throw new AwsException("ValidationException",
                    "Invalid ResourceArn: " + resourceArn, 400);
        }
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
        Map<String, String> tags;
        try {
            tags = dynamoDbService.listTagsOfResource(resourceArn, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw new AwsException("AccessDeniedException",
                        "User is not authorized to perform: dynamodb:ListTagsOfResource on resource: "
                        + resourceArn, 400);
            }
            throw e;
        }

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
        String resolvedTableName = table.getTableName();

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
                    resolvedTableName, table.getTableArn(), "NEW_AND_OLD_IMAGES", region);
            table.setStreamEnabled(true);
            table.setStreamArn(sd.getStreamArn());
            table.setStreamViewType("NEW_AND_OLD_IMAGES");
        }

        dynamoDbService.persistTable(resolvedTableName, table, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", resolvedTableName);
        response.put("StreamArn", streamArn);
        response.put("DestinationStatus", "ACTIVE");
        response.put("DestinationStatusDescription", "Kinesis streaming is enabled for this table");
        return Response.ok(response).build();
    }

    private Response handleDisableKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        String streamArn = request.path("StreamArn").asText();

        TableDefinition table = dynamoDbService.describeTable(tableName, region);
        String resolvedTableName = table.getTableName();

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
        dynamoDbService.persistTable(resolvedTableName, table, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", resolvedTableName);
        response.put("StreamArn", streamArn);
        response.put("DestinationStatus", "DISABLED");
        response.put("DestinationStatusDescription", "Kinesis streaming is disabled for this table");
        return Response.ok(response).build();
    }

    private Response handleDescribeKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", table.getTableName());

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
    private void validateItemSets(JsonNode item) {
        if (item == null || !item.isObject()) return;
        item.fields().forEachRemaining(entry -> {
            JsonNode attr = entry.getValue();
            checkAttrSets(attr);
        });
    }

    private void checkAttrSets(JsonNode attr) {
        if (attr == null) return;
        if (attr.has("NULL") && !attr.get("NULL").asBoolean()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Null attribute value types must have the value of true", 400);
        }
        if (attr.has("SS")) {
            JsonNode ss = attr.get("SS");
            if (ss.isEmpty()) throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: An string set  may not be empty", 400);
            Set<String> seen = new HashSet<>();
            for (JsonNode e : ss) {
                if (!seen.add(e.asText())) throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Input collection " + formatSetForError(ss) + " contains duplicates.", 400);
            }
        } else if (attr.has("NS")) {
            JsonNode ns = attr.get("NS");
            if (ns.isEmpty()) throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: An number set  may not be empty", 400);
            Set<String> seen = new HashSet<>();
            for (JsonNode e : ns) {
                if (!seen.add(e.asText())) throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Input collection " + formatSetForError(ns) + " contains duplicates.", 400);
            }
        } else if (attr.has("BS")) {
            JsonNode bs = attr.get("BS");
            if (bs.isEmpty()) throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Binary sets should not be empty", 400);
            Set<String> seen = new HashSet<>();
            for (JsonNode e : bs) {
                if (!seen.add(e.asText())) throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Input collection " + formatSetForError(bs) + " contains duplicates.", 400);
            }
        } else if (attr.has("M")) {
            attr.get("M").fields().forEachRemaining(e -> checkAttrSets(e.getValue()));
        } else if (attr.has("L")) {
            attr.get("L").forEach(this::checkAttrSets);
        }
    }

    // ── Expression token utilities ──

    private static void extractPrefixedTokens(String expr, char prefix, Set<String> out) {
        if (expr == null) return;
        int i = 0;
        while (i < expr.length()) {
            if (expr.charAt(i) == prefix) {
                int start = i++;
                while (i < expr.length() && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) i++;
                if (i > start + 1) out.add(expr.substring(start, i));
            } else {
                i++;
            }
        }
    }

    private static Set<String> extractHashTokens(String... expressions) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String expr : expressions) extractPrefixedTokens(expr, '#', tokens);
        return tokens;
    }

    private static Set<String> extractColonTokens(String... expressions) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String expr : expressions) extractPrefixedTokens(expr, ':', tokens);
        return tokens;
    }

    private static void checkUnusedEan(JsonNode exprAttrNames, Set<String> usedHashTokens) {
        if (exprAttrNames == null) return;
        List<String> unused = new ArrayList<>();
        exprAttrNames.fieldNames().forEachRemaining(k -> {
            if (!usedHashTokens.contains(k)) unused.add(k);
        });
        if (!unused.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Value provided in ExpressionAttributeNames unused in expressions: keys: {"
                    + String.join(", ", unused) + "}", 400);
        }
    }

    private static void checkUnusedEav(JsonNode exprAttrValues, Set<String> usedColonTokens) {
        if (exprAttrValues == null) return;
        List<String> unused = new ArrayList<>();
        exprAttrValues.fieldNames().forEachRemaining(k -> {
            if (!usedColonTokens.contains(k)) unused.add(k);
        });
        if (!unused.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Value provided in ExpressionAttributeValues unused in expressions: keys: {"
                    + String.join(", ", unused) + "}", 400);
        }
    }

    private String formatSetForError(JsonNode arr) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (JsonNode e : arr) {
            if (!first) sb.append(", ");
            sb.append(e.asText());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    // Filters items to only include the attributes projected into the given index.
    // Returns items unchanged when projectionType is ALL or no matching index is found.
    private List<JsonNode> applyIndexProjection(List<JsonNode> items, String indexName,
                                                 TableDefinition table, String select) {
        if (indexName == null || "ALL_ATTRIBUTES".equals(select)) return items;

        String projectionType = "ALL";
        List<String> nonKeyAttributes = new ArrayList<>();
        Set<String> indexKeyNames = new HashSet<>();

        for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
            if (gsi.getIndexName().equals(indexName)) {
                projectionType = gsi.getProjectionType() != null ? gsi.getProjectionType() : "ALL";
                nonKeyAttributes = gsi.getNonKeyAttributes() != null ? gsi.getNonKeyAttributes() : List.of();
                gsi.getKeySchema().forEach(k -> indexKeyNames.add(k.getAttributeName()));
                break;
            }
        }
        for (LocalSecondaryIndex lsi : table.getLocalSecondaryIndexes()) {
            if (lsi.getIndexName().equals(indexName)) {
                projectionType = lsi.getProjectionType() != null ? lsi.getProjectionType() : "ALL";
                nonKeyAttributes = lsi.getNonKeyAttributes() != null ? lsi.getNonKeyAttributes() : List.of();
                lsi.getKeySchema().forEach(k -> indexKeyNames.add(k.getAttributeName()));
                break;
            }
        }

        if ("ALL".equals(projectionType)) return items;

        Set<String> allowed = new HashSet<>(indexKeyNames);
        allowed.add(table.getPartitionKeyName());
        String sortKeyName = table.getSortKeyName();
        if (sortKeyName != null) allowed.add(sortKeyName);
        if ("INCLUDE".equals(projectionType)) allowed.addAll(nonKeyAttributes);

        return items.stream().map(item -> {
            ObjectNode filtered = objectMapper.createObjectNode();
            item.fields().forEachRemaining(e -> {
                if (allowed.contains(e.getKey())) filtered.set(e.getKey(), e.getValue());
            });
            return (JsonNode) filtered;
        }).toList();
    }

    private void addConsumedCapacity(ObjectNode response, JsonNode request, String tableName,
                                      int itemCount, boolean isWrite) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC)) return;

        double cu = isWrite ? Math.max(1.0, itemCount) : Math.max(0.5, itemCount * 0.5);

        ObjectNode cc = objectMapper.createObjectNode();
        cc.put("TableName", DynamoDbTableNames.resolve(tableName));
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
            cc.put("TableName", DynamoDbTableNames.resolve(tableName));
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
        node.put("DeletionProtectionEnabled", table.isDeletionProtectionEnabled());

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

    private Response handleExportTable(JsonNode request, String region) {
        Map<String, Object> params = new java.util.HashMap<>();
        request.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().isTextual()
                ? e.getValue().asText() : e.getValue()));

        io.github.hectorvent.floci.services.dynamodb.model.ExportDescription desc =
                dynamoDbService.exportTable(params, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ExportDescription", objectMapper.valueToTree(desc));
        return Response.ok(response).build();
    }

    private Response handleDescribeExport(JsonNode request, String region) {
        String exportArn = request.path("ExportArn").asText();
        io.github.hectorvent.floci.services.dynamodb.model.ExportDescription desc =
                dynamoDbService.describeExport(exportArn);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ExportDescription", objectMapper.valueToTree(desc));
        return Response.ok(response).build();
    }

    private Response handleListExports(JsonNode request, String region) {
        String tableArn = request.has("TableArn") ? request.get("TableArn").asText() : null;
        Integer maxResults = request.has("MaxResults") ? request.get("MaxResults").asInt() : null;
        String nextToken = request.has("NextToken") && !request.get("NextToken").isNull()
                ? request.get("NextToken").asText() : null;

        DynamoDbService.ListExportsResult result = dynamoDbService.listExports(tableArn, maxResults, nextToken);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = objectMapper.createArrayNode();
        for (io.github.hectorvent.floci.services.dynamodb.model.ExportSummary s : result.exportSummaries()) {
            summaries.add(objectMapper.valueToTree(s));
        }
        response.set("ExportSummaries", summaries);
        if (result.nextToken() != null) {
            response.put("NextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }
}
