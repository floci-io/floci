package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJsonController;
import io.github.hectorvent.floci.services.dynamodb.model.*;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.*;
import java.util.function.Supplier;

/**
 * DynamoDB JSON protocol handler.
 * Called by {@link AwsJsonController} for DynamoDB-targeted requests.
 */
@ApplicationScoped
public class DynamoDbJsonHandler {

    private static final String DEFAULT_ACCOUNT_ID = "000000000000";
    private static final String SSE_TYPE_KMS = "KMS";
    private static final Set<String> VALID_SSE_TYPES = Set.of("AES256", "KMS");

    private final DynamoDbService dynamoDbService;
    private final DynamoDbStreamService dynamoDbStreamService;
    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;
    private final DynamoDbPartiQLHandler partiQLHandler;
    private final Object importLock = new Object();

    @Inject
    public DynamoDbJsonHandler(DynamoDbService dynamoDbService, DynamoDbStreamService dynamoDbStreamService,
                               KinesisService kinesisService, ObjectMapper objectMapper) {
        this.dynamoDbService = dynamoDbService;
        this.dynamoDbStreamService = dynamoDbStreamService;
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
        this.partiQLHandler = new DynamoDbPartiQLHandler(dynamoDbService, objectMapper);
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
            case "SearchVectors" -> handleSearchVectors(request, region);
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
            case "PutResourcePolicy" -> handlePutResourcePolicy(request, region);
            case "GetResourcePolicy" -> handleGetResourcePolicy(request, region);
            case "DeleteResourcePolicy" -> handleDeleteResourcePolicy(request, region);
            case "EnableKinesisStreamingDestination" -> handleEnableKinesisStreamingDestination(request, region);
            case "DisableKinesisStreamingDestination" -> handleDisableKinesisStreamingDestination(request, region);
            case "DescribeKinesisStreamingDestination" -> handleDescribeKinesisStreamingDestination(request, region);
            case "ExportTableToPointInTime" -> handleExportTable(request, region);
            case "CreateGlobalTable" -> handleCreateGlobalTable(request, region);
            case "DescribeGlobalTable" -> handleDescribeGlobalTable(request, region);
            case "UpdateGlobalTable" -> handleUpdateGlobalTable(request, region);
            case "ListGlobalTables" -> handleListGlobalTables(request, region);
            case "DescribeExport" -> handleDescribeExport(request, region);
            case "ListExports" -> handleListExports(request, region);
            case "ImportTable" -> handleImportTable(request, region);
            case "DescribeImport" -> handleDescribeImport(request, region);
            case "ListImports" -> handleListImports(request, region);
            case "ExecuteStatement" -> handleExecuteStatement(request, region);
            case "ExecuteTransaction" -> handleExecuteTransaction(request, region);
            case "BatchExecuteStatement" -> handleBatchExecuteStatement(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException", "Operation " + action + " is not supported."))
                    .build();

        };
    }

    private Response handleCreateTable(JsonNode request, String region) {
        var table = createTableFromSpec(request, region, "ACTIVE");

        var response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        return Response.ok(response).build();
    }

    /** Shared by CreateTable and ImportTable, whose TableCreationParameters use the same keys. */
    private TableDefinition createTableFromSpec(JsonNode request, String region, String initialStatus) {
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
            var gsiPosition = 0;
            for (JsonNode gsiNode : gsiArray) {
                gsiPosition++;
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
                rejectEmptyNonKeyAttributes(nonKeyAttrArray, "globalSecondaryIndexes." + gsiPosition + ".member");
                validateProjectionSpec(projectionType, nonKeyAttributes);
                GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes);
                JsonNode gsiPt = gsiNode.path("ProvisionedThroughput");
                if (!gsiPt.isMissingNode()) {
                    gsi.getProvisionedThroughput().setReadCapacityUnits(gsiPt.path("ReadCapacityUnits").asLong(0));
                    gsi.getProvisionedThroughput().setWriteCapacityUnits(gsiPt.path("WriteCapacityUnits").asLong(0));
                }
                JsonNode gsiOnDemand = gsiNode.path("OnDemandThroughput");
                if (gsiOnDemand.isObject()) {
                    if (gsiOnDemand.has("MaxReadRequestUnits")) {
                        gsi.setOnDemandMaxReadRequestUnits(gsiOnDemand.get("MaxReadRequestUnits").asInt());
                    }
                    if (gsiOnDemand.has("MaxWriteRequestUnits")) {
                        gsi.setOnDemandMaxWriteRequestUnits(gsiOnDemand.get("MaxWriteRequestUnits").asInt());
                    }
                }
                gsis.add(gsi);
            }
        }

        List<LocalSecondaryIndex> lsis = new ArrayList<>();
        JsonNode lsiArray = request.path("LocalSecondaryIndexes");
        if (!lsiArray.isMissingNode() && lsiArray.isArray()) {
            var lsiPosition = 0;
            for (JsonNode lsiNode : lsiArray) {
                lsiPosition++;
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
                rejectEmptyNonKeyAttributes(lsiNonKeyAttrArray, "localSecondaryIndexes." + lsiPosition + ".member");
                validateProjectionSpec(projectionType, lsiNonKeyAttributes);
                lsis.add(new LocalSecondaryIndex(indexName, lsiKeySchema, null, projectionType, lsiNonKeyAttributes));
            }
        }

        List<DynamoDbService.VectorIndexCreate> vectorIndexes = new ArrayList<>();
        JsonNode vectorIndexArray = request.path("VectorIndexes");
        if (vectorIndexArray.isArray()) {
            int vectorPosition = 0;
            for (JsonNode vectorIndexNode : vectorIndexArray) {
                vectorPosition++;
                String memberPath = "vectorIndexes." + vectorPosition + ".member";
                vectorIndexes.add(new DynamoDbService.VectorIndexCreate(
                        parseVectorIndex(vectorIndexNode, memberPath), memberPath));
            }
        }

        String billingMode = request.has("BillingMode")
                ? request.get("BillingMode").asText() : null;

        boolean deletionProtection = request.path("DeletionProtectionEnabled").asBoolean(false);

        // Parsed and validated before createTable below: that call and the stream-enable that
        // follows it both have side effects, so an invalid SSEType must fail before either runs
        // rather than after, or ValidationException would leave a real table (and stream) behind.
        JsonNode sseSpec = request.path("SSESpecification");
        boolean sseEnabled = !sseSpec.isMissingNode() && sseSpec.path("Enabled").asBoolean(false);
        String sseType = sseEnabled ? sseSpec.path("SSEType").asText(SSE_TYPE_KMS) : null;
        if (sseEnabled) {
            validateSseType(sseType);
        }

        if ("PAY_PER_REQUEST".equals(billingMode) && !pt.isMissingNode()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Neither ReadCapacityUnits nor WriteCapacityUnits "
                    + "can be specified when BillingMode is PAY_PER_REQUEST", 400);
        }

        JsonNode streamSpecCheck = request.path("StreamSpecification");
        if (streamSpecCheck.isObject() && !streamSpecCheck.path("StreamEnabled").asBoolean(false)
                && streamSpecCheck.hasNonNull("StreamViewType")) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Table is being created with a stream "
                    + "disabled, UpdateViewType should not be specified", 400);
        }

        TableDefinition table = dynamoDbService.createTable(tableName, keySchema, attrDefs,
                readCapacity, writeCapacity, gsis, lsis, vectorIndexes, billingMode, region);
        table.setTableStatus(initialStatus);

        table.setDeletionProtectionEnabled(deletionProtection);

        if ("PAY_PER_REQUEST".equals(billingMode)) {
            table.setBillingMode("PAY_PER_REQUEST");
            table.getProvisionedThroughput().setReadCapacityUnits(0L);
            table.getProvisionedThroughput().setWriteCapacityUnits(0L);
        } else {
            table.setBillingMode("PROVISIONED");
        }

        if (request.has("TableClass")) {
            table.setTableClass(request.get("TableClass").asText());
        }

        JsonNode onDemand = request.path("OnDemandThroughput");
        if (onDemand.isObject()) {
            if (onDemand.has("MaxReadRequestUnits")) {
                table.setOnDemandMaxReadRequestUnits(onDemand.get("MaxReadRequestUnits").asInt());
            }
            if (onDemand.has("MaxWriteRequestUnits")) {
                table.setOnDemandMaxWriteRequestUnits(onDemand.get("MaxWriteRequestUnits").asInt());
            }
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

        if (sseEnabled) {
            table.setSseEnabled(true);
            table.setSseType(sseType);
            if (SSE_TYPE_KMS.equals(sseType)) {
                JsonNode kmsMasterKeyId = sseSpec.path("KMSMasterKeyId");
                table.setKmsMasterKeyArn(!kmsMasterKeyId.isMissingNode() && !kmsMasterKeyId.isNull()
                        ? kmsMasterKeyId.asText()
                        : defaultKmsMasterKeyArn(region));
            }
        }

        // Everything above mutates the table AFTER createTable stored it, and a persistent
        // backend serializes on write rather than holding the live object. Flush once so the
        // stream, billing, class, tag and SSE settings survive a restart -- stream state
        // especially, since startup rebuilds streams from the persisted table.
        dynamoDbService.persistTable(tableName, table, region);
        return table;
    }

    private void validateSseType(String sseType) {
        if (!VALID_SSE_TYPES.contains(sseType)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + sseType
                    + "' at 'sSESpecification.sSEType' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [AES256, KMS]", 400);
        }
    }

    // AWS reports an empty list as a length constraint on the 1-based member path, before the
    // projection type check. A null NonKeyAttributes counts as not specified.
    private static void rejectEmptyNonKeyAttributes(JsonNode nonKeyAttrArray, String memberPath) {
        if (nonKeyAttrArray.isArray() && nonKeyAttrArray.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '[]' at '" + memberPath
                    + ".projection.nonKeyAttributes' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
    }

    /** Reads a VectorIndex or the CreateVectorIndexAction of a VectorIndexUpdate; same members. */
    private static VectorIndex parseVectorIndex(JsonNode node, String memberPath) {
        // An absent VectorAttribute is reported as the member itself, one that is present but
        // carries no AttributeName as the nested member. Only the request shape tells them apart.
        JsonNode vectorAttribute = node.path("VectorAttribute");
        if (vectorAttribute.isObject() && !vectorAttribute.hasNonNull("AttributeName")) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + memberPath
                    + ".vectorAttribute.attributeName' failed to satisfy constraint: "
                    + "Member must not be null", 400);
        }
        List<SearchSchemaElement> searchSchema = new ArrayList<>();
        JsonNode searchSchemaArray = node.path("SearchSchema");
        if (searchSchemaArray.isArray()) {
            for (JsonNode element : searchSchemaArray) {
                searchSchema.add(new SearchSchemaElement(
                        element.path("AttributeName").asText(null),
                        element.path("SearchSchemaElementType").asText(null)));
            }
        }
        String projectionType = node.hasNonNull("Projection")
                ? node.path("Projection").path("ProjectionType").asText("ALL") : null;
        List<String> nonKeyAttributes = new ArrayList<>();
        JsonNode nonKeyAttrArray = node.path("Projection").path("NonKeyAttributes");
        if (nonKeyAttrArray.isArray()) {
            for (JsonNode nonKeyAttr : nonKeyAttrArray) {
                nonKeyAttributes.add(nonKeyAttr.asText());
            }
        }
        rejectEmptyNonKeyAttributes(nonKeyAttrArray, memberPath);
        validateProjectionSpec(projectionType, nonKeyAttributes);
        Long dimensions = node.hasNonNull("Dimensions") ? node.get("Dimensions").asLong() : null;
        return new VectorIndex(
                node.path("IndexName").asText(null),
                node.path("VectorAttribute").path("AttributeName").asText(null),
                searchSchema,
                projectionType,
                nonKeyAttributes,
                dimensions,
                node.path("DistanceFunction").asText(null));
    }

    // INCLUDE requires the NonKeyAttributes list; every other projection type forbids it.
    private static void validateProjectionSpec(String projectionType, List<String> nonKeyAttributes) {
        if ("INCLUDE".equals(projectionType) && nonKeyAttributes.isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: "
                    + "ProjectionType is INCLUDE, but NonKeyAttributes is not specified", 400);
        }
        if (!"INCLUDE".equals(projectionType) && !nonKeyAttributes.isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: "
                    + "ProjectionType is " + projectionType + ", but NonKeyAttributes is specified", 400);
        }
    }

    private Response handleDeleteTable(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        TableDefinition table = dynamoDbService.describeTable(tableName, region);
        if (table.isDeletionProtectionEnabled()) {
            throw new AwsException("ValidationException",
                    "Table " + tableName + " is protected against deletion. To delete the table, "
                    + "DeletionProtectionEnabled must be set to false.", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        dynamoDbService.deleteTable(tableName, region);
        return Response.ok(response).build();
    }

    // ── Global tables, the 2017.11.29 API ────────────────────────────────────

    private Response handleCreateGlobalTable(JsonNode request, String region) {
        String name = request.path("GlobalTableName").asText();
        TableDefinition table = dynamoDbService.createGlobalTable(
                name, replicationGroupRegions(request), region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("GlobalTableDescription", globalTableDescription(table, name));
        return Response.ok(root).build();
    }

    private Response handleDescribeGlobalTable(JsonNode request, String region) {
        String name = request.path("GlobalTableName").asText();
        TableDefinition table = dynamoDbService.describeGlobalTable(name, region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("GlobalTableDescription", globalTableDescription(table, name));
        return Response.ok(root).build();
    }

    private Response handleUpdateGlobalTable(JsonNode request, String region) {
        String name = request.path("GlobalTableName").asText();
        List<String> adds = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        for (JsonNode update : request.path("ReplicaUpdates")) {
            String create = update.path("Create").path("RegionName").asText(null);
            String delete = update.path("Delete").path("RegionName").asText(null);
            if (create != null) {
                adds.add(create);
            }
            if (delete != null) {
                removes.add(delete);
            }
        }
        TableDefinition table = dynamoDbService.updateGlobalTable(name, adds, removes, region);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("GlobalTableDescription", globalTableDescription(table, name));
        return Response.ok(root).build();
    }

    private Response handleListGlobalTables(JsonNode request, String region) {
        String regionFilter = request.path("RegionName").asText(null);
        ArrayNode list = objectMapper.createArrayNode();
        for (TableDefinition table : dynamoDbService.listGlobalTables(regionFilter, region)) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("GlobalTableName", table.getTableName());
            entry.set("ReplicationGroup", replicaNodes(table, objectMapper.createArrayNode(), false));
            list.add(entry);
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("GlobalTables", list);
        return Response.ok(root).build();
    }

    private static List<String> replicationGroupRegions(JsonNode request) {
        List<String> regions = new ArrayList<>();
        for (JsonNode replica : request.path("ReplicationGroup")) {
            String name = replica.path("RegionName").asText(null);
            if (name != null && !name.isBlank()) {
                regions.add(name);
            }
        }
        return regions;
    }

    private ObjectNode globalTableDescription(TableDefinition table, String globalTableName) {
        ObjectNode description = objectMapper.createObjectNode();
        description.put("GlobalTableName", globalTableName);
        description.put("GlobalTableStatus", "ACTIVE");
        description.put("GlobalTableArn", table.getTableArn());
        if (table.getCreationDateTime() != null) {
            description.put("CreationDateTime", table.getCreationDateTime().getEpochSecond());
        }
        description.set("ReplicationGroup", replicaNodes(table, objectMapper.createArrayNode(), true));
        return description;
    }

    /**
     * The replica list of a global table: every tracked replica plus the home region, which AWS
     * reports as a replica of its own global table. ReplicaStatus is only carried by the
     * description shapes, not by the ListGlobalTables entry.
     */
    private ArrayNode replicaNodes(TableDefinition table, ArrayNode replicas, boolean withStatus) {
        LinkedHashSet<String> regions = new LinkedHashSet<>(
                table.getReplicaRegions() != null ? table.getReplicaRegions() : List.of());
        if (table.getGlobalTableHomeRegion() != null) {
            regions.add(table.getGlobalTableHomeRegion());
        }
        for (String replicaRegion : regions) {
            ObjectNode replica = objectMapper.createObjectNode();
            replica.put("RegionName", replicaRegion);
            if (withStatus) {
                replica.put("ReplicaStatus", "ACTIVE");
            }
            replicas.add(replica);
        }
        return replicas;
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
        DynamoDbExpressionSize.checkWrite(conditionExpression, "ConditionExpression");
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(item);
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(exprAttrValues);
        DynamoDbNumberUtils.requireStorable(exprAttrValues, true);

        JsonNode expected = request.has("Expected") ? request.get("Expected") : null;
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(legacyValues(expected));
        String conditionalOperator = request.has("ConditionalOperator")
                ? request.get("ConditionalOperator").asText() : "AND";

        if (conditionExpression != null && expected != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {Expected} Expression parameters: {ConditionExpression}", 400);
        }

        // EAN/EAV with no expression to reference them: AWS reports "can only be specified
        // when using expressions", not the "unused in expressions" wording (#2893).
        rejectExprAttrsWithoutExpression(exprAttrNames, exprAttrValues,
                conditionExpression != null, "ConditionExpression is null");

        ExpressionEvaluator.validateExpression(conditionExpression, "ConditionExpression", exprAttrNames, exprAttrValues);

        // Reject ExpressionAttributeNames/Values entries not referenced by ConditionExpression (AWS parity, #2893)
        checkUnusedEan(exprAttrNames, extractHashTokens(conditionExpression));
        checkUnusedEav(exprAttrValues, extractColonTokens(conditionExpression));

        validateItemSets(item);

        JsonNode oldItem = null;
        if ("ALL_OLD".equals(returnValues) || expected != null) {
            dynamoDbService.requireTableForItemCall(tableName, region);
            oldItem = dynamoDbService.getItem(tableName, item, region);
        }

        if (expected != null) {
            evaluateLegacyExpected(oldItem, expected, conditionalOperator, returnValuesOnConditionCheckFailure);
        }

        var previousItem = dynamoDbService.putItem(tableName, item, conditionExpression,
                exprAttrNames, exprAttrValues, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_OLD" .equals(returnValues) && oldItem != null) {
            response.set("Attributes", oldItem);
        }
        addItemCollectionMetrics(response, request, tableName, item, region);
        addWriteConsumedCapacity(response, request, tableName, region, previousItem, item);
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
            DynamoDbExpressionSize.checkRead(projectionExpression, "ProjectionExpression");
            ProjectionEvaluator.validateExpression(projectionExpression, exprAttrNames);
        }

        JsonNode item = dynamoDbService.getItem(tableName, key, region);
        var itemBytes = item != null ? DynamoDbItemSize.calculateItemSize(item) : 0;
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
        addConsumedCapacity(response, request, tableName,
                readCapacityUnits(itemBytes, request.path("ConsistentRead").asBoolean(false)), null);
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
        DynamoDbExpressionSize.checkWrite(conditionExpression, "ConditionExpression");
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(exprAttrValues);
        DynamoDbNumberUtils.requireStorable(exprAttrValues, true);
        JsonNode expectedDel = request.has("Expected") ? request.get("Expected") : null;
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(legacyValues(expectedDel));

        if (conditionExpression != null && expectedDel != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {Expected} Expression parameters: {ConditionExpression}", 400);
        }

        // EAN/EAV with no expression to reference them: AWS reports "can only be specified
        // when using expressions", not the "unused in expressions" wording (#2893).
        rejectExprAttrsWithoutExpression(exprAttrNames, exprAttrValues,
                conditionExpression != null, "ConditionExpression is null");

        ExpressionEvaluator.validateExpression(conditionExpression, "ConditionExpression", exprAttrNames, exprAttrValues);

        // Reject ExpressionAttributeNames/Values entries not referenced by ConditionExpression (AWS parity, #2893)
        checkUnusedEan(exprAttrNames, extractHashTokens(conditionExpression));
        checkUnusedEav(exprAttrValues, extractColonTokens(conditionExpression));

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
        addWriteConsumedCapacity(response, request, tableName, region, oldItem, null);
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

        // UpdateItem stops at the first invalid enum and reports one error, unlike PutItem
        // and DeleteItem which aggregate.
        String rccUpd = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (!VALID_RETURN_VALUES_UPDATE.contains(returnValues)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + returnValues + "' at 'returnValues' "
                    + "failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [NONE, ALL_OLD, ALL_NEW, UPDATED_OLD, UPDATED_NEW]", 400);
        }
        if (rccUpd != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccUpd)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + rccUpd + "' at 'returnConsumedCapacity' "
                    + "failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]", 400);
        }
        DynamoDbExpressionSize.checkWrite(updateExpression, "UpdateExpression");
        DynamoDbExpressionSize.checkWrite(conditionExpression, "ConditionExpression");
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(exprAttrValues);
        DynamoDbNumberUtils.requireStorable(exprAttrValues, true);
        var attributeUpdateValues = legacyValues(attributeUpdates);
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(attributeUpdateValues);
        DynamoDbNumberUtils.requireStorable(attributeUpdateValues, true);
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(
                legacyValues(request.has("Expected") ? request.get("Expected") : null));

        JsonNode updateData = attributeUpdates.isMissingNode() ? null : attributeUpdates;
        JsonNode expectedUpd = request.has("Expected") ? request.get("Expected") : null;
        String conditionalOperatorUpd = request.has("ConditionalOperator")
                ? request.get("ConditionalOperator").asText() : "AND";

        if (updateData != null && updateExpression != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributeUpdates} Expression parameters: {UpdateExpression}", 400);
        }

        // EAN/EAV with no expression to reference them, mirroring the PutItem guard.
        rejectExprAttrsWithoutExpression(exprAttrNames, exprAttrValues,
                updateExpression != null || conditionExpression != null,
                "UpdateExpression is null, ConditionExpression is null");

        ExpressionEvaluator.validateExpression(conditionExpression, "ConditionExpression", exprAttrNames, exprAttrValues);

        if (conditionExpression != null && expectedUpd != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {Expected} Expression parameters: {ConditionExpression}", 400);
        }

        // Validate EAN/EAV usage across UpdateExpression + ConditionExpression
        if (updateExpression != null) {
            // Pre-validate syntax: expression must start with a valid clause keyword.
            // AWS tokenizes UpdateExpression whitespace-insensitively, so collapse runs
            // of whitespace (newlines, tabs, multiple spaces) before matching keywords.
            String ue = updateExpression.trim().replaceAll("\\s+", " ");
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
            dynamoDbService.requireAddOrDeleteOperandTypes(updateExpression, exprAttrValues, true);
        }

        if (expectedUpd != null) {
            JsonNode existingForUpd = dynamoDbService.getItem(tableName, key, region);
            evaluateLegacyExpected(existingForUpd, expectedUpd, conditionalOperatorUpd, returnValuesOnConditionCheckFailure);
        }

        DynamoDbService.UpdateResult result = dynamoDbService.updateItem(
                tableName, key, updateData, updateExpression, exprAttrNames, exprAttrValues,
                returnValues, conditionExpression, region, returnValuesOnConditionCheckFailure);

        ObjectNode response = objectMapper.createObjectNode();
        if ("ALL_NEW" .equals(returnValues) && result.newItem() != null) {
            response.set("Attributes", result.newItem());
        } else if ("ALL_OLD" .equals(returnValues) && result.oldItem() != null) {
            response.set("Attributes", result.oldItem());
        } else if ("UPDATED_NEW".equals(returnValues) || "UPDATED_OLD".equals(returnValues)) {
            var updated = DynamoDbUpdatedAttributes.collect(result.touched(), "UPDATED_NEW".equals(returnValues));
            // AWS omits Attributes when no touched path has a value on that side.
            if (!updated.isEmpty()) {
                response.set("Attributes", updated);
            }
        }
        addItemCollectionMetrics(response, request, tableName, key, region);
        addWriteConsumedCapacity(response, request, tableName, region, result.oldItem(), result.newItem());
        return Response.ok(response).build();
    }

    private void evaluateLegacyExpected(JsonNode existing, JsonNode expected, String conditionalOperator,
                                          String returnValuesOnConditionCheckFailure) {
        if (expected == null) return;
        validateLegacyExpected(expected);
        boolean useOr = "OR".equals(conditionalOperator);
        boolean overall = !useOr;
        var fields = expected.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String attrName = entry.getKey();
            JsonNode condition = entry.getValue();
            JsonNode attrValue = existing != null ? existing.get(attrName) : null;
            boolean condResult;
            if (condition.has("Exists") && !condition.get("Exists").asBoolean()) {
                condResult = attrValue == null;
            } else {
                // Exists:true always carries a Value, so it evaluates as an attribute
                // comparison exactly like a bare Value: must exist AND match.
                condResult = dynamoDbService.matchesKeyConditionPublic(attrValue, normalizeLegacyCondition(condition));
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

    /**
     * Validates every entry of a legacy Expected map before any condition is evaluated.
     * The rules and their precedence mirror ExpectedAttributeValue:
     * <ul>
     *   <li>{@code AttributeValueList} is only meaningful alongside a {@code ComparisonOperator}.</li>
     *   <li>{@code Exists} cannot be combined with a {@code ComparisonOperator} — they are
     *       alternative forms.</li>
     *   <li>Without a {@code ComparisonOperator}, a {@code Value} is required unless
     *       {@code Exists: false} asks for plain absence.</li>
     *   <li>{@code Exists: false} forbids a {@code Value} — an attribute cannot be expected to
     *       hold a value while also being expected to be absent.</li>
     * </ul>
     * DynamoDB reports only the first offending entry, so validation stops at the first error.
     */
    private void validateLegacyExpected(JsonNode expected) {
        var fields = expected.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String attrName = entry.getKey();
            JsonNode condition = entry.getValue();
            boolean hasComparisonOperator = condition.has("ComparisonOperator");
            boolean hasExists = condition.has("Exists");
            boolean hasValue = condition.has("Value");

            if (condition.has("AttributeValueList") && !hasComparisonOperator) {
                throw legacyExpectedValidationError(
                        "AttributeValueList can only be used with a ComparisonOperator for Attribute: " + attrName);
            }
            if (hasExists && hasComparisonOperator) {
                throw legacyExpectedValidationError(
                        "Exists and ComparisonOperator cannot be used together for Attribute: " + attrName);
            }
            boolean expectsValue = !hasExists || condition.get("Exists").asBoolean();
            if (!hasComparisonOperator && expectsValue && !hasValue) {
                throw legacyExpectedValidationError("Value must be provided when Exists is "
                        + (hasExists ? "true" : "null") + " for Attribute: " + attrName);
            }
            if (!expectsValue && hasValue) {
                throw legacyExpectedValidationError(
                        "Value cannot be used when Exists is false for Attribute: " + attrName);
            }
        }
    }

    private AwsException legacyExpectedValidationError(String detail) {
        return new AwsException("ValidationException",
                "1 validation error detected: One or more parameter values were invalid: " + detail, 400);
    }

    /**
     * Converts a legacy Expected condition from the {"Value": {...}} form to the
     * {"AttributeValueList": [{...}]} form expected by matchesKeyCondition.
     * Operators that already use AttributeValueList (IN, BETWEEN) pass through unchanged.
     */
    private JsonNode normalizeLegacyCondition(JsonNode condition) {
        if (!condition.has("Value") || condition.has("AttributeValueList")) {
            return condition;
        }
        com.fasterxml.jackson.databind.node.ObjectNode normalized = objectMapper.createObjectNode();
        condition.fields().forEachRemaining(e -> {
            if (!"Value".equals(e.getKey())) {
                normalized.set(e.getKey(), e.getValue());
            }
        });
        com.fasterxml.jackson.databind.node.ArrayNode avl = objectMapper.createArrayNode();
        avl.add(condition.get("Value"));
        normalized.set("AttributeValueList", avl);
        return normalized;
    }

    private void addItemCollectionMetrics(ObjectNode response, JsonNode request, String tableName,
                                           JsonNode itemOrKey, String region) {
        String ricm = request.has("ReturnItemCollectionMetrics")
                ? request.get("ReturnItemCollectionMetrics").asText() : null;
        if (!"SIZE".equals(ricm) || itemOrKey == null) return;
        try {
            TableDefinition table = dynamoDbService.requireTableForItemCall(tableName, region);
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

    private static final int MAX_TOTAL_SEGMENTS = 1_000_000;

    // The AttributeValues inside a legacy container. AttributeUpdates, Expected,
    // KeyConditions and QueryFilter carry them under Value or AttributeValueList.
    private ObjectNode legacyValues(JsonNode container) {
        var values = objectMapper.createObjectNode();
        if (container == null || !container.isObject()) {
            return values;
        }
        var i = 0;
        for (var spec : container) {
            if (spec.has("Value")) {
                values.set("v" + i++, spec.get("Value"));
            }
            for (var value : spec.path("AttributeValueList")) {
                values.set("v" + i++, value);
            }
        }
        return values;
    }

    private static final Set<String> VALID_SELECT = Set.of(
            "ALL_ATTRIBUTES", "ALL_PROJECTED_ATTRIBUTES", "SPECIFIC_ATTRIBUTES", "COUNT");

    private static final String SELECT_NEEDS_PROJECTION =
            "Must specify the AttributesToGet or ProjectionExpression when choosing to get SPECIFIC_ATTRIBUTES";

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
        if (keyConditionExpr != null && queryFilter != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {QueryFilter} Expression parameters: {KeyConditionExpression}", 400);
        }
        if (filterExpr != null && queryFilter != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {QueryFilter} Expression parameters: {FilterExpression}", 400);
        }
        if (attributesToGet != null && projectionExpression != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributesToGet} Expression parameters: {ProjectionExpression}", 400);
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

        DynamoDbExpressionSize.checkRead(keyConditionExpr, "KeyConditionExpression");
        DynamoDbExpressionSize.checkRead(filterExpr, "FilterExpression");
        DynamoDbExpressionSize.checkRead(projectionExpression, "ProjectionExpression");
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(exprAttrValues);
        DynamoDbNumberUtils.requireStorable(exprAttrValues, false);
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(legacyValues(keyConditions), false);
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(legacyValues(queryFilter), false);
        ExpressionEvaluator.validateExpression(keyConditionExpr, "KeyConditionExpression", exprAttrNames, exprAttrValues);
        ExpressionEvaluator.validateExpression(filterExpr, "FilterExpression", exprAttrNames, exprAttrValues);
        ProjectionEvaluator.validateExpression(projectionExpression, exprAttrNames);

        if (select != null && !VALID_SELECT.contains(select)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + select + "' at 'select' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [SPECIFIC_ATTRIBUTES, COUNT, ALL_ATTRIBUTES, ALL_PROJECTED_ATTRIBUTES]", 400);
        }

        boolean hasAttributesToGet = attributesToGet != null && attributesToGet.size() > 0;
        if ("SPECIFIC_ATTRIBUTES".equals(select) && projectionExpression == null && !hasAttributesToGet) {
            // Query prefixes the count here, Scan does not.
            throw new AwsException("ValidationException",
                    "1 validation error detected: " + SELECT_NEEDS_PROJECTION, 400);
        }

        TableDefinition queryTable = dynamoDbService.requireTableForItemCall(tableName, region);
        DynamoDbAccessPath queryAccessPath = DynamoDbAccessPath.resolve(queryTable, indexName);
        DynamoDbAccessPathValidator.validateQuery(queryTable, queryAccessPath, keyConditions,
                keyConditionExpr, filterExpr, queryFilter, exprAttrNames, exprAttrValues);
        DynamoDbAccessPathValidator.validateSelection(queryTable, queryAccessPath, select,
                projectionExpression, attributesToGet, exprAttrNames);
        rejectConsistentReadOnGsi(request, queryAccessPath);

        // Check undefined #tokens in FilterExpression
        if (filterExpr != null) {
            for (String token : extractHashTokens(filterExpr)) {
                if (exprAttrNames == null || !exprAttrNames.has(token)) {
                    throw new AwsException("ValidationException",
                            "Invalid FilterExpression: An expression attribute name used in the document path is not defined; attribute name: " + token, 400);
                }
            }
        }
        // Reject ExpressionAttributeNames/Values entries not referenced by any expression
        // (AWS parity, #2893). Unconditional: a legacy KeyConditions request that still
        // carries EAN/EAV must be rejected, and either map can only be referenced from
        // KeyConditionExpression, FilterExpression or (names only) ProjectionExpression.
        Set<String> hashTokens = extractHashTokens(keyConditionExpr, filterExpr, projectionExpression);
        checkUnusedEan(exprAttrNames, hashTokens);
        checkUnusedEav(exprAttrValues, extractColonTokens(keyConditionExpr, filterExpr));
        if (exclusiveStartKey != null) {
            DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, queryTable, queryAccessPath, false);
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
            queryItems = applyIndexProjection(queryItems, queryTable, queryAccessPath, select);
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
        addConsumedCapacity(response, request, tableName,
                readCapacityUnits(result.scannedBytes(), request.path("ConsistentRead").asBoolean(false)), queryAccessPath);
        return Response.ok(response).build();
    }

    private static void rejectConsistentReadOnGsi(JsonNode request, DynamoDbAccessPath accessPath) {
        if (request.path("ConsistentRead").asBoolean(false) && accessPath.isGlobalSecondaryIndex()) {
            throw new AwsException("ValidationException",
                    "Consistent reads are not supported on global secondary indexes", 400);
        }
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
        String projectionExpressionScan = request.has("ProjectionExpression")
                ? request.get("ProjectionExpression").asText() : null;
        JsonNode attributesToGetScan = request.has("AttributesToGet") ? request.get("AttributesToGet") : null;
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

        var segmentErrors = new ArrayList<String>();
        if (totalSegments != null) {
            if (totalSegments < 1) {
                segmentErrors.add("Value '" + totalSegments + "' at 'totalSegments' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1");
            }
            if (MAX_TOTAL_SEGMENTS < totalSegments) {
                segmentErrors.add("Value '" + totalSegments + "' at 'totalSegments' failed to satisfy constraint: "
                        + "Member must have value less than or equal to " + MAX_TOTAL_SEGMENTS);
            }
        }
        if (segment != null && segment < 0) {
            segmentErrors.add("Value '" + segment + "' at 'segment' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 0");
        }
        if (!segmentErrors.isEmpty()) {
            var n = segmentErrors.size();
            throw new AwsException("ValidationException",
                    n + " validation error" + (n > 1 ? "s" : "") + " detected: "
                    + String.join("; ", segmentErrors), 400);
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

        DynamoDbExpressionSize.checkReadWithSize(filterExpr, "FilterExpression");
        DynamoDbExpressionSize.checkRead(projectionExpressionScan, "ProjectionExpression");
        DynamoDbAttributeValueValidator.requireNestingWithinLimit(exprAttrValues);
        DynamoDbNumberUtils.requireStorable(exprAttrValues, false);
        ExpressionEvaluator.validateExpression(filterExpr, "FilterExpression", exprAttrNames, exprAttrValues);
        ProjectionEvaluator.validateExpression(projectionExpressionScan, exprAttrNames);

        // Reject ExpressionAttributeNames/Values entries not referenced by any expression (AWS parity, #2893)
        checkUnusedEan(exprAttrNames, extractHashTokens(filterExpr, projectionExpressionScan));
        checkUnusedEav(exprAttrValues, extractColonTokens(filterExpr));

        if (select != null && !VALID_SELECT.contains(select)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + select + "' at 'select' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [SPECIFIC_ATTRIBUTES, COUNT, ALL_ATTRIBUTES, ALL_PROJECTED_ATTRIBUTES]", 400);
        }
        if (projectionExpressionScan != null && attributesToGetScan != null) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributesToGet} Expression parameters: {ProjectionExpression}", 400);
        }
        boolean hasAttributesToGetScan = attributesToGetScan != null && attributesToGetScan.size() > 0;
        if ("SPECIFIC_ATTRIBUTES".equals(select)
                && projectionExpressionScan == null && !hasAttributesToGetScan) {
            throw new AwsException("ValidationException", SELECT_NEEDS_PROJECTION, 400);
        }

        TableDefinition scanTable = dynamoDbService.requireTableForItemCall(tableName, region);
        DynamoDbAccessPath scanAccessPath = DynamoDbAccessPath.resolve(scanTable, indexNameScan);
        DynamoDbAccessPathValidator.validateSelection(scanTable, scanAccessPath, select,
                projectionExpressionScan, attributesToGetScan, exprAttrNames);
        rejectConsistentReadOnGsi(request, scanAccessPath);

        if (exclusiveStartKey != null) {
            DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, scanTable, scanAccessPath, true);
        }

        DynamoDbService.ScanResult result = dynamoDbService.scan(
                tableName, filterExpr, exprAttrNames, exprAttrValues, scanFilter, limit,
                exclusiveStartKey, indexNameScan, segment, totalSegments, region);

        List<JsonNode> scanItems = result.items();
        // Apply index projection (KEYS_ONLY / INCLUDE) when scanning a secondary index
        if (indexNameScan != null && projectionExpressionScan == null && attributesToGetScan == null) {
            scanItems = applyIndexProjection(scanItems, scanTable, scanAccessPath, select);
        }
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
        addConsumedCapacity(response, request, tableName,
                readCapacityUnits(result.scannedBytes(), request.path("ConsistentRead").asBoolean(false)), scanAccessPath);
        return Response.ok(response).build();
    }

    // The floor AWS reports for a small search. The real figure is non-deterministic there,
    // so every search reports the floor (measured in eu-west-2, 2026-09-23).
    private static final double VECTOR_SEARCH_REQUEST_BYTES = 1024;

    private static final int MAX_VECTOR_SEARCH_TOP_K = 100;

    private Response handleSearchVectors(JsonNode request, String region) {
        requireSearchVectorsMember(request, "SearchVector");
        requireSearchVectorsMember(request, "IndexName");
        String tableName = request.path("TableName").asText();
        String indexName = request.get("IndexName").asText();
        String rccSearch = request.has("ReturnConsumedCapacity")
                ? request.get("ReturnConsumedCapacity").asText() : null;
        if (rccSearch != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(rccSearch)) {
            // No "N validation errors detected" envelope here, unlike every other operation.
            throw new AwsException("ValidationException",
                    "Value '" + rccSearch + "' at 'returnConsumedCapacity' "
                    + "failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[INDEXES, TOTAL, NONE]", 400);
        }
        requireSearchVectorsMember(request, "TopK");
        int topK = request.get("TopK").asInt();
        if (topK < 1) {
            // AWS names this member TopK and quotes no value, unlike every other member of this
            // family. Measured on real DynamoDB, eu-west-2, 2026-09-23.
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'TopK' failed to satisfy "
                    + "constraint: Member must have value greater than or equal to 1", 400);
        }
        // Both bounds answer before the table and the index are resolved: AWS reports the range
        // even for a table that does not exist.
        if (topK > MAX_VECTOR_SEARCH_TOP_K) {
            throw new AwsException("ValidationException",
                    "Provided TopK value '" + topK + "' is out of valid range. "
                    + "The value must be between 1 and " + MAX_VECTOR_SEARCH_TOP_K + " inclusive", 400);
        }

        TableDefinition table = dynamoDbService.settledTable(tableName, region);
        VectorIndex index = requireSearchableVectorIndex(table, indexName);

        JsonNode exprAttrNames = request.has("ExpressionAttributeNames")
                ? request.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = request.has("ExpressionAttributeValues")
                ? request.get("ExpressionAttributeValues") : null;
        List<DynamoDbVectorSearch.Hit> hits = DynamoDbVectorSearch.search(table, index,
                dynamoDbService.liveItems(table, region),
                request.path("SearchVector"), topK,
                request.has("SearchConditionExpression")
                        ? request.get("SearchConditionExpression").asText() : null,
                exprAttrNames, exprAttrValues,
                request.has("ProjectionExpression") ? request.get("ProjectionExpression").asText() : null);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode searchResults = objectMapper.createArrayNode();
        for (DynamoDbVectorSearch.Hit hit : hits) {
            ObjectNode searchResult = objectMapper.createObjectNode();
            searchResult.set("Item", hit.item());
            searchResult.put("Score", hit.score());
            searchResults.add(searchResult);
        }
        response.set("SearchResults", searchResults);
        if (rccSearch != null && !"NONE".equals(rccSearch)) {
            ObjectNode consumedCapacity = objectMapper.createObjectNode();
            consumedCapacity.put("VectorSearchRequestBytes", VECTOR_SEARCH_REQUEST_BYTES);
            response.set("ConsumedCapacity", consumedCapacity);
        }
        return Response.ok(response).build();
    }

    /**
     * Rejects an omitted required member of SearchVectors the way the AWS frontend does: as a
     * deserialisation failure quoting the offset of the body's closing brace, which for the
     * compact JSON an SDK sends is the body length. Measured in eu-west-2, 2026-09-23.
     */
    private static void requireSearchVectorsMember(JsonNode request, String member) {
        if (!request.hasNonNull(member)) {
            throw new AwsException("ValidationException",
                    "missing field `" + member + "` at line 1 column "
                    + request.toString().length(), 400);
        }
    }

    /**
     * The vector index a search may run against.
     *
     * <p>An index still in its resource allocation phase answers as if the table did not have it,
     * which is the wording AWS uses there and what the documented readiness wait retries on.
     */
    private VectorIndex requireSearchableVectorIndex(TableDefinition table, String indexName) {
        VectorIndex index = table.findVectorIndex(indexName).orElse(null);
        if (index == null) {
            throw new AwsException("ValidationException",
                    "The table does not have the specified index: " + indexName, 400);
        }
        if ("CREATING".equals(index.getIndexStatus())) {
            if (!dynamoDbService.isVectorIndexBackfilling(index)) {
                throw new AwsException("ValidationException",
                        "The table does not have the specified index: " + indexName, 400);
            }
            throw new AwsException("ValidationException",
                    "Cannot search backfilling vector index: " + indexName, 400);
        }
        return index;
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
                DynamoDbAttributeValueValidator.requireNestingWithinLimit(
                        writeReq.path("PutRequest").get("Item"), false);
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
            TableDefinition bwTable = dynamoDbService.requireTableForItemCall(entry.getKey(), region);
            Set<String> seen = new HashSet<>();
            for (JsonNode writeReq : entry.getValue()) {
                JsonNode keyNode = writeReq.has("PutRequest")
                        ? writeReq.get("PutRequest").get("Item")
                        : writeReq.get("DeleteRequest").get("Key");
                String key = dynamoDbService.buildItemKey(bwTable, keyNode,
                        DynamoDbService.KeySurface.BATCH_WRITE);
                if (!seen.add(key)) {
                    throw new AwsException("ValidationException",
                            "Provided list of item keys contains duplicates", 400);
                }
            }
        }

        // The per-table write cost needs the stored images from before the batch runs.
        var returnCCBatch = request.path("ReturnConsumedCapacity").asText("NONE");
        if (!VALID_RETURN_CONSUMED_CAPACITY.contains(returnCCBatch)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + returnCCBatch
                    + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]", 400);
        }
        Map<String, DynamoDbWriteCapacity.Cost> costs = null;
        if (!"NONE".equals(returnCCBatch)) {
            costs = new LinkedHashMap<>();
            for (var entry : items.entrySet()) {
                TableDefinition costTable = dynamoDbService.requireTableForItemCall(entry.getKey(), region);
                var cost = DynamoDbWriteCapacity.Cost.zero();
                for (var writeReq : entry.getValue()) {
                    var newItem = writeReq.has("PutRequest")
                            ? DynamoDbNumberUtils.normalizeNumbersInItem(writeReq.get("PutRequest").get("Item"))
                            : null;
                    var keyNode = writeReq.has("PutRequest")
                            ? writeReq.get("PutRequest").get("Item")
                            : writeReq.get("DeleteRequest").get("Key");
                    var previous = dynamoDbService.getItem(entry.getKey(), keyNode, region);
                    cost = cost.plus(DynamoDbWriteCapacity.forWrite(costTable, previous, newItem));
                }
                costs.put(entry.getKey(), cost);
            }
        }

        dynamoDbService.batchWriteItem(items, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("UnprocessedItems", objectMapper.createObjectNode());
        if (costs != null) {
            var ccArray = objectMapper.createArrayNode();
            for (var entry : costs.entrySet()) {
                ccArray.add(writeCapacityNode(entry.getKey(), entry.getValue(),
                        "INDEXES".equals(returnCCBatch)));
            }
            response.set("ConsumedCapacity", ccArray);
        }
        return Response.ok(response).build();
    }

    private Response handleBatchGetItem(JsonNode request, String region) {
        JsonNode requestItems = request.get("RequestItems");
        if (requestItems == null || requestItems.isNull() || requestItems.isMissingNode()) {
            throw new AwsException("ValidationException",
                    "The requestItems parameter is required for BatchGetItem", 400);
        }
        // BatchGetItem answers an empty map from the validation framework, BatchWriteItem
        // still answers with its own sentence.
        if (!requestItems.fields().hasNext()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'RequestItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1", 400);
        }
        Map<String, JsonNode> items = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> tables = requestItems.fields();
        while (tables.hasNext()) {
            var entry = tables.next();
            items.put(entry.getKey(), entry.getValue());
        }

        // AWS weighs the two projection styles across the whole request, so one table
        // entry naming AttributesToGet and another naming ProjectionExpression conflict.
        boolean usesAttributesToGet = false;
        boolean usesProjectionExpression = false;
        for (JsonNode tableRequest : items.values()) {
            usesAttributesToGet |= tableRequest.hasNonNull("AttributesToGet");
            usesProjectionExpression |= tableRequest.hasNonNull("ProjectionExpression");
        }
        if (usesAttributesToGet && usesProjectionExpression) {
            throw new AwsException("ValidationException",
                    "Can not use both expression and non-expression parameters in the same request: "
                    + "Non-expression parameters: {AttributesToGet} Expression parameters: {ProjectionExpression}", 400);
        }

        // Per-table check: max 100 keys
        for (Map.Entry<String, JsonNode> entry : items.entrySet()) {
            var projection = entry.getValue().path("ProjectionExpression");
            if (projection.isTextual()) {
                DynamoDbExpressionSize.checkRead(projection.asText(), "ProjectionExpression");
                ProjectionEvaluator.validateExpression(projection.asText(),
                        entry.getValue().get("ExpressionAttributeNames"));
            }
            JsonNode keysNode = entry.getValue().has("Keys") ? entry.getValue().get("Keys") : null;
            if (keysNode != null && keysNode.size() > 100) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value at 'RequestItems." + entry.getKey()
                        + ".member.Keys' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 100", 400);
            }
        }

        for (Map.Entry<String, JsonNode> entry : items.entrySet()) {
            TableDefinition bgTable = dynamoDbService.requireTableForItemCall(entry.getKey(), region);
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
        JsonNode replicaUpdates = request.path("ReplicaUpdates");
        List<String> addRegions = new ArrayList<>();
        List<String> removeRegions = new ArrayList<>();
        List<String> updateRegions = new ArrayList<>();
        if (replicaUpdates.isArray()) {
            for (JsonNode update : replicaUpdates) {
                JsonNode create = update.path("Create");
                if (create.isObject()) {
                    String replicaRegion = create.path("RegionName").asText(null);
                    validateReplicaRegion(replicaRegion);
                    addRegions.add(replicaRegion);
                }
                JsonNode delete = update.path("Delete");
                if (delete.isObject()) {
                    String replicaRegion = delete.path("RegionName").asText(null);
                    validateReplicaRegion(replicaRegion);
                    removeRegions.add(replicaRegion);
                }
                JsonNode updateNode = update.path("Update");
                if (updateNode.isObject()) {
                    String replicaRegion = updateNode.path("RegionName").asText(null);
                    validateReplicaRegion(replicaRegion);
                    updateRegions.add(replicaRegion);
                }
            }
        }
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
        List<JsonNode> gsiUpdatesToApply = new ArrayList<>();
        JsonNode gsiUpdates = request.path("GlobalSecondaryIndexUpdates");
        if (!gsiUpdates.isMissingNode() && gsiUpdates.isArray()) {
            var updatePosition = 0;
            for (JsonNode update : gsiUpdates) {
                updatePosition++;
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
                    rejectEmptyNonKeyAttributes(nonKeyAttrArray,
                            "globalSecondaryIndexUpdates." + updatePosition + ".member.create");
                    validateProjectionSpec(projectionType, nonKeyAttributes);
                    GlobalSecondaryIndex newGsi = new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes);
                    JsonNode newGsiPt = createNode.path("ProvisionedThroughput");
                    if (!newGsiPt.isMissingNode()) {
                        newGsi.getProvisionedThroughput().setReadCapacityUnits(newGsiPt.path("ReadCapacityUnits").asLong(0));
                        newGsi.getProvisionedThroughput().setWriteCapacityUnits(newGsiPt.path("WriteCapacityUnits").asLong(0));
                    }
                    JsonNode newGsiOnDemand = createNode.path("OnDemandThroughput");
                    if (newGsiOnDemand.isObject()) {
                        if (newGsiOnDemand.has("MaxReadRequestUnits")) {
                            newGsi.setOnDemandMaxReadRequestUnits(newGsiOnDemand.get("MaxReadRequestUnits").asInt());
                        }
                        if (newGsiOnDemand.has("MaxWriteRequestUnits")) {
                            newGsi.setOnDemandMaxWriteRequestUnits(newGsiOnDemand.get("MaxWriteRequestUnits").asInt());
                        }
                    }
                    gsiCreates.add(newGsi);
                }
                JsonNode deleteNode = update.path("Delete");
                if (!deleteNode.isMissingNode()) {
                    gsiDeletes.add(deleteNode.path("IndexName").asText());
                }
                JsonNode updateNode = update.path("Update");
                if (updateNode.isObject()) {
                    gsiUpdatesToApply.add(updateNode);
                }
            }
        }

        if (!gsiUpdatesToApply.isEmpty()) {
            TableDefinition currentTable = dynamoDbService.describeTable(tableName, region);
            for (JsonNode updateNode : gsiUpdatesToApply) {
                String indexName = updateNode.path("IndexName").asText();
                if (gsiDeletes.contains(indexName)) {
                    throw new AwsException("ValidationException",
                            "Cannot delete and update the same index: " + indexName, 400);
                }
                if (currentTable.findGsi(indexName).isEmpty()) {
                    throw new AwsException("ValidationException",
                            "The table does not have the specified index: " + indexName, 400);
                }
            }
        }

        List<DynamoDbService.VectorIndexCreate> vectorCreates = new ArrayList<>();
        List<String> vectorDeletes = new ArrayList<>();
        JsonNode vectorIndexUpdates = request.path("VectorIndexUpdates");
        if (vectorIndexUpdates.isArray()) {
            // AWS reports a per-member constraint under the position in this array, which a
            // create shares with the deletes beside it.
            int updatePosition = 0;
            for (JsonNode update : vectorIndexUpdates) {
                updatePosition++;
                JsonNode createNode = update.path("Create");
                if (createNode.isObject()) {
                    String memberPath = "vectorIndexUpdates." + updatePosition + ".member.create";
                    vectorCreates.add(new DynamoDbService.VectorIndexCreate(
                            parseVectorIndex(createNode, memberPath), memberPath));
                }
                JsonNode deleteNode = update.path("Delete");
                if (deleteNode.isObject()) {
                    vectorDeletes.add(deleteNode.path("IndexName").asText(null));
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

        if (!addRegions.isEmpty() || !removeRegions.isEmpty() || !updateRegions.isEmpty()) {
            dynamoDbService.validateReplicaUpdates(
                    tableName, addRegions, removeRegions, updateRegions, region);
        }

        // Parsed and validated up front, same as the replica check above: updateTable and the
        // mutations that follow it have side effects (persisted throughput/GSI changes, stream
        // enable/disable), so an invalid SSEType must fail before any of them run rather than
        // after, or ValidationException would leave a partially-applied update behind.
        JsonNode sseSpec = request.path("SSESpecification");
        boolean sseSpecified = !sseSpec.isMissingNode();
        boolean sseEnabled = sseSpecified && sseSpec.path("Enabled").asBoolean(false);
        String sseType = sseEnabled ? sseSpec.path("SSEType").asText(SSE_TYPE_KMS) : null;
        if (sseEnabled) {
            validateSseType(sseType);
        }

        TableDefinition table = dynamoDbService.updateTable(tableName, readCapacity, writeCapacity,
                gsiCreates, gsiDeletes, newAttrDefs, vectorCreates, vectorDeletes,
                billingModeCheck, region);

        for (JsonNode updateNode : gsiUpdatesToApply) {
            GlobalSecondaryIndex gsi = table.findGsi(updateNode.path("IndexName").asText()).orElseThrow();
            JsonNode gsiPt = updateNode.path("ProvisionedThroughput");
            if (gsiPt.isObject()) {
                if (gsiPt.has("ReadCapacityUnits")) {
                    gsi.getProvisionedThroughput().setReadCapacityUnits(gsiPt.get("ReadCapacityUnits").asLong());
                }
                if (gsiPt.has("WriteCapacityUnits")) {
                    gsi.getProvisionedThroughput().setWriteCapacityUnits(gsiPt.get("WriteCapacityUnits").asLong());
                }
            }
            JsonNode gsiOnDemand = updateNode.path("OnDemandThroughput");
            if (gsiOnDemand.isObject()) {
                if (gsiOnDemand.has("MaxReadRequestUnits")) {
                    gsi.setOnDemandMaxReadRequestUnits(gsiOnDemand.get("MaxReadRequestUnits").asInt());
                }
                if (gsiOnDemand.has("MaxWriteRequestUnits")) {
                    gsi.setOnDemandMaxWriteRequestUnits(gsiOnDemand.get("MaxWriteRequestUnits").asInt());
                }
            }
        }

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

        if (request.has("TableClass")) {
            table.setTableClass(request.get("TableClass").asText());
        }

        JsonNode onDemand = request.path("OnDemandThroughput");
        if (onDemand.isObject()) {
            if (onDemand.has("MaxReadRequestUnits")) {
                table.setOnDemandMaxReadRequestUnits(onDemand.get("MaxReadRequestUnits").asInt());
            }
            if (onDemand.has("MaxWriteRequestUnits")) {
                table.setOnDemandMaxWriteRequestUnits(onDemand.get("MaxWriteRequestUnits").asInt());
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

        if (sseSpecified) {
            if (sseEnabled) {
                table.setSseEnabled(true);
                table.setSseType(sseType);
                if (SSE_TYPE_KMS.equals(sseType)) {
                    JsonNode kmsMasterKeyId = sseSpec.path("KMSMasterKeyId");
                    table.setKmsMasterKeyArn(!kmsMasterKeyId.isMissingNode() && !kmsMasterKeyId.isNull()
                            ? kmsMasterKeyId.asText()
                            : defaultKmsMasterKeyArn(region));
                } else {
                    table.setKmsMasterKeyArn(null);
                }
            } else {
                table.setSseEnabled(false);
                table.setSseType(null);
                table.setKmsMasterKeyArn(null);
            }
        }

        // Same flush as CreateTable: the stream and SSE mutations above land after updateTable's
        // write, so without this a retargeted view type or SSE change is rebuilt from the stale
        // persisted value on restart. Must run before applyReplicaUpdates below, which re-fetches
        // the table from storage for its own persist -- a persistent backend serializes on write
        // rather than holding the live object, so without this flush first, that re-fetch would
        // see neither the stream nor the SSE mutations above.
        dynamoDbService.persistTable(tableName, table, region);

        if (!addRegions.isEmpty() || !removeRegions.isEmpty() || !updateRegions.isEmpty()) {
            table = dynamoDbService.applyReplicaUpdates(tableName, addRegions, removeRegions, updateRegions, region);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("TableDescription", tableToNode(table));
        return Response.ok(response).build();
    }

    private static void validateReplicaRegion(String replicaRegion) {
        if (replicaRegion == null || replicaRegion.isBlank()) {
            throw new AwsException("ValidationException", "Replica RegionName must not be empty", 400);
        }
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
        JsonNode transactItemsNode = requireValidTransactRequest(request);

        for (JsonNode txItem : transactItemsNode) {
            for (JsonNode op : txItem) {
                String conditionExpression = op.has("ConditionExpression")
                        ? op.get("ConditionExpression").asText() : null;
                DynamoDbExpressionSize.checkRead(op.path("UpdateExpression").textValue(), "UpdateExpression");
                DynamoDbExpressionSize.checkReadWithSize(conditionExpression, "ConditionExpression");
                ExpressionEvaluator.validateExpression(conditionExpression, "ConditionExpression",
                        op.get("ExpressionAttributeNames"),
                        op.get("ExpressionAttributeValues"));
                DynamoDbAttributeValueValidator.requireNestingWithinLimit(op.get("Item"), false);
                dynamoDbService.requireAddOrDeleteOperandTypes(op.path("UpdateExpression").textValue(),
                        op.get("ExpressionAttributeValues"), false);
            }
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

        List<JsonNode> transactItems = new ArrayList<>();
        transactItemsNode.forEach(transactItems::add);
        try {
            dynamoDbService.cancelOnKeySchemaMismatch(transactItems, region);
        } catch (TransactionCanceledException e) {
            return transactWriteCanceled(e);
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
                    tn -> dynamoDbService.requireTableForItemCall(tn, region));
            JsonNode keyNode = op.has("Item") ? op.get("Item") : op.get("Key");
            if (keyNode == null) continue;
            String key = region + "::" + opTable + "::" + dynamoDbService.buildItemKey(txTable, keyNode);
            if (!seen.add(key)) {
                throw new AwsException("ValidationException",
                        "Transaction request cannot include multiple operations on one item", 400);
            }
        }

        // ClientRequestToken makes TransactWriteItems idempotent within ~10 minutes.
        // Forward the token and the raw request body so the service can hash the body
        // and reject replays whose parameters changed.
        String clientRequestToken = request.has("ClientRequestToken") && !request.get("ClientRequestToken").isNull()
                ? request.get("ClientRequestToken").asText()
                : null;

        try {
            DynamoDbService.TransactWriteResult result =
                    dynamoDbService.transactWriteItems(transactItems, region, clientRequestToken, request);
            ObjectNode response = objectMapper.createObjectNode();
            addTransactWriteConsumedCapacity(response, request, result);
            return Response.ok(response).build();
        } catch (TransactionCanceledException e) {
            return transactWriteCanceled(e);
        }
    }

    private static JsonNode requireValidTransactRequest(JsonNode request) {
        JsonNode transactItems = request.path("TransactItems");
        List<String> validationErrors = new ArrayList<>();
        if (transactItems.isMissingNode() || transactItems.isNull() || !transactItems.isArray()) {
            validationErrors.add("Value null at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1");
        } else if (transactItems.isEmpty()) {
            validationErrors.add("Value '[]' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1");
        } else if (transactItems.size() > 100) {
            validationErrors.add("Value '" + transactItems + "' at 'transactItems' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 100");
        }
        addReturnConsumedCapacityError(request, validationErrors);
        throwValidationErrors(validationErrors);
        return transactItems;
    }

    private static void addReturnConsumedCapacityError(JsonNode request, List<String> validationErrors) {
        String returnCC = request.has("ReturnConsumedCapacity") ? request.get("ReturnConsumedCapacity").asText() : null;
        if (returnCC != null && !VALID_RETURN_CONSUMED_CAPACITY.contains(returnCC)) {
            validationErrors.add("Value '" + returnCC + "' at 'returnConsumedCapacity' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: [INDEXES, TOTAL, NONE]");
        }
    }

    private static void requireValidReturnConsumedCapacity(JsonNode request) {
        List<String> validationErrors = new ArrayList<>();
        addReturnConsumedCapacityError(request, validationErrors);
        throwValidationErrors(validationErrors);
    }

    private static void throwValidationErrors(List<String> validationErrors) {
        if (validationErrors.isEmpty()) {
            return;
        }
        int n = validationErrors.size();
        throw new AwsException("ValidationException",
                n + " validation error" + (n > 1 ? "s" : "") + " detected: "
                + String.join("; ", validationErrors), 400);
    }

    private Response transactWriteCanceled(TransactionCanceledException e) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("__type", "TransactionCanceledException");
        body.put("message", e.getMessage());
        ArrayNode reasons = body.putArray("CancellationReasons");
        for (TransactionCanceledException.CancellationReason reason : e.getCancellationReasons()) {
            ObjectNode r = objectMapper.createObjectNode();
            r.put("Code", reason.code().isEmpty() ? "None" : reason.code());
            if (!reason.code().isEmpty()) {
                r.put("Message", reason.message() != null ? reason.message() : "The conditional request failed");
            }
            if (reason.item() != null) {
                r.set("Item", reason.item());
            }
            reasons.add(r);
        }
        return Response.status(400).entity(body).build();
    }

    private Response handleTransactGetItems(JsonNode request, String region) {
        JsonNode transactItemsNode = requireValidTransactRequest(request);

        for (JsonNode txItem : transactItemsNode) {
            DynamoDbExpressionSize.checkRead(txItem.path("Get").path("ProjectionExpression").textValue(),
                    "ProjectionExpression");
        }

        Map<String, TableDefinition> tableCache = new HashMap<>();
        Set<String> seenGet = new HashSet<>();
        for (JsonNode txItem : transactItemsNode) {
            JsonNode get = txItem.has("Get") ? txItem.get("Get") : null;
            if (get == null) continue;
            String opTable = get.path("TableName").asText();
            TableDefinition txTable = tableCache.computeIfAbsent(opTable,
                    tn -> dynamoDbService.requireTableForItemCall(tn, region));
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

        // Validate the ProjectionExpression in each Get action before executing
        for (JsonNode txItem : transactItemsNode) {
            JsonNode get = txItem.has("Get") ? txItem.get("Get") : null;
            if (get == null) continue;
            String pe = get.has("ProjectionExpression") ? get.get("ProjectionExpression").asText() : null;
            if (pe != null) {
                ProjectionEvaluator.validateExpression(pe, get.get("ExpressionAttributeNames"));
            }
        }

        List<JsonNode> transactItems = new ArrayList<>();
        transactItemsNode.forEach(transactItems::add);

        DynamoDbService.TransactGetResult result;
        try {
            result = dynamoDbService.transactGetItems(transactItems, region);
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
        for (JsonNode item : result.items()) {
            ObjectNode entry = objectMapper.createObjectNode();
            if (item != null) {
                entry.set("Item", item);
            }
            responsesArray.add(entry);
        }
        response.set("Responses", responsesArray);
        addPerTableConsumedCapacity(response, request, result.capacity(), "ReadCapacityUnits");
        return Response.ok(response).build();
    }

    /**
     * Botocore gives DynamoDB's {@code ResourceArnString} no pattern at all, only a length range,
     * so this shape is the emulator's own. Widening the partition keeps it from rejecting a legal
     * GovCloud or China table ARN.
     */
    private static final java.util.regex.Pattern DYNAMODB_TABLE_ARN_PATTERN =
            java.util.regex.Pattern.compile("^arn:" + AwsArnUtils.PARTITION_REGEX
                    + ":dynamodb:[a-z0-9-]+:\\d{12}:table/[a-zA-Z0-9._-]+$");

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

    private Response handlePutResourcePolicy(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        if (!isValidDynamoDbTableArn(resourceArn)) {
            throw new AwsException("ValidationException",
                    "Invalid ResourceArn: " + resourceArn, 400);
        }
        if (!request.hasNonNull("Policy")) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'policy' failed to satisfy constraint: "
                    + "Member must not be null", 400);
        }
        String policy = request.path("Policy").asText();
        String expectedRevisionId = request.has("ExpectedRevisionId")
                ? request.get("ExpectedRevisionId").asText() : null;

        String revisionId = dynamoDbService.putResourcePolicy(resourceArn, policy, expectedRevisionId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RevisionId", revisionId);
        return Response.ok(response).build();
    }

    private Response handleGetResourcePolicy(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        if (!isValidDynamoDbTableArn(resourceArn)) {
            throw new AwsException("ValidationException",
                    "Invalid ResourceArn: " + resourceArn, 400);
        }
        DynamoDbService.ResourcePolicyResult result = dynamoDbService.getResourcePolicy(resourceArn, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Policy", result.policy());
        response.put("RevisionId", result.revisionId());
        return Response.ok(response).build();
    }

    private Response handleDeleteResourcePolicy(JsonNode request, String region) {
        String resourceArn = request.path("ResourceArn").asText();
        if (!isValidDynamoDbTableArn(resourceArn)) {
            throw new AwsException("ValidationException",
                    "Invalid ResourceArn: " + resourceArn, 400);
        }
        String expectedRevisionId = request.has("ExpectedRevisionId")
                ? request.get("ExpectedRevisionId").asText() : null;

        String revisionId = dynamoDbService.deleteResourcePolicy(resourceArn, expectedRevisionId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RevisionId", revisionId);
        return Response.ok(response).build();
    }

    private Response handleEnableKinesisStreamingDestination(JsonNode request, String region) {
        String tableName = request.path("TableName").asText();
        String streamArn = request.path("StreamArn").asText();
        JsonNode precisionNode = request.path("EnableKinesisStreamingConfiguration")
                .path("ApproximateCreationDateTimePrecision");
        String precision = precisionNode.isMissingNode() || precisionNode.isNull()
                ? KinesisStreamingDestination.PRECISION_MILLISECOND : precisionNode.asText();
        if (!KinesisStreamingDestination.PRECISION_MILLISECOND.equals(precision)
                && !KinesisStreamingDestination.PRECISION_MICROSECOND.equals(precision)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + precision
                            + "' at 'enableKinesisStreamingConfiguration.approximateCreationDateTimePrecision' "
                            + "failed to satisfy constraint: Member must satisfy enum value set: [MILLISECOND, MICROSECOND]",
                    400);
        }

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
            existing.get().setApproximateCreationDateTimePrecision(precision);
        } else {
            table.getKinesisStreamingDestinations().add(new KinesisStreamingDestination(streamArn, precision));
        }

        // DynamoDB Streams is left as the caller configured it: Kinesis forwarding does not depend on it, and
        // turning it on here showed up as stream_enabled drift on aws_dynamodb_table that never converged.
        dynamoDbService.persistTable(resolvedTableName, table, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", resolvedTableName);
        response.put("StreamArn", streamArn);
        response.put("DestinationStatus", "ACTIVE");
        response.put("DestinationStatusDescription", "Kinesis streaming is enabled for this table");
        response.putObject("EnableKinesisStreamingConfiguration")
                .put("ApproximateCreationDateTimePrecision", precision);
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
        // Stop forwarding and discard buffered CDC records for this now-disabled destination.
        dynamoDbService.onKinesisStreamingDestinationDisabled(resolvedTableName, streamArn, region);

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
                // A binary set names its type, and AWS leaves out the space before "of".
                if (!seen.add(e.asText())) throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Input collection " + formatSetForError(bs)
                        + "of type BS contains duplicates.", 400);
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

    /**
     * Rejects ExpressionAttributeNames/Values supplied when the request carries no expression to
     * reference them. AWS answers this sub-case with "&lt;param&gt; can only be specified when using
     * expressions: &lt;nullExpressionParams&gt;" rather than the "unused in expressions" wording used
     * once an alias is merely unreferenced (#2893). {@code nullExpressionParams} must spell the absent
     * expression parameter(s) exactly as AWS reports them for the operation, e.g. "ConditionExpression is null".
     * EAV is checked before EAN to match the pre-existing PutItem guard.
     */
    private static void rejectExprAttrsWithoutExpression(JsonNode exprAttrNames, JsonNode exprAttrValues,
            boolean hasAnyExpression, String nullExpressionParams) {
        if (hasAnyExpression) {
            return;
        }
        if (exprAttrValues != null) {
            throw new AwsException("ValidationException",
                    "ExpressionAttributeValues can only be specified when using expressions: " + nullExpressionParams, 400);
        }
        if (exprAttrNames != null) {
            throw new AwsException("ValidationException",
                    "ExpressionAttributeNames can only be specified when using expressions: " + nullExpressionParams, 400);
        }
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
    private List<JsonNode> applyIndexProjection(List<JsonNode> items, TableDefinition table,
                                                 DynamoDbAccessPath accessPath, String select) {
        if ("ALL_ATTRIBUTES".equals(select) || "ALL".equals(accessPath.projectionType())) {
            return items;
        }

        Set<String> allowed = accessPath.projectedAttributeNames(table);
        return items.stream()
                .map(item -> (JsonNode) ProjectionEvaluator.trimToAttributes((ObjectNode) item, allowed))
                .toList();
    }

    /**
     * Half a read unit per 4KB read, doubled for a strongly consistent read, with the
     * half-unit minimum DynamoDB charges even when nothing is found.
     */
    static double readCapacityUnits(long bytes, boolean consistentRead) {
        var units = Math.max(1, (bytes + 4095) / 4096) * 0.5;
        return consistentRead ? units * 2 : units;
    }

    /**
     * Write-side ConsumedCapacity with the per-index arms DynamoDB reports under
     * INDEXES. oldItem and newItem are the stored images around the write; the new
     * image is normalized the way storage normalizes it so an identical overwrite
     * compares equal.
     */
    private void addWriteConsumedCapacity(ObjectNode response, JsonNode request, String tableName,
                                           String region, JsonNode oldItem, JsonNode newItem) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC)) return;
        TableDefinition table = dynamoDbService.requireTableForItemCall(tableName, region);
        var cost = DynamoDbWriteCapacity.forWrite(table, oldItem,
                newItem != null ? DynamoDbNumberUtils.normalizeNumbersInItem(newItem) : null);
        response.set("ConsumedCapacity",
                writeCapacityNode(tableName, cost, "INDEXES".equals(returnCC)));
    }

    private void addTransactWriteConsumedCapacity(ObjectNode response, JsonNode request,
                                                  DynamoDbService.TransactWriteResult result) {
        addPerTableConsumedCapacity(response, request, result.capacity(),
                result.replayed() ? "ReadCapacityUnits" : "WriteCapacityUnits");
    }

    private void addPerTableConsumedCapacity(ObjectNode response, JsonNode request,
                                             Map<String, DynamoDbWriteCapacity.Cost> capacityByTable,
                                             String unitsField) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC) || capacityByTable.isEmpty()) {
            return;
        }
        ArrayNode entries = objectMapper.createArrayNode();
        capacityByTable.forEach((tableName, cost) ->
                entries.add(writeCapacityNode(tableName, cost, "INDEXES".equals(returnCC), unitsField)));
        response.set("ConsumedCapacity", entries);
    }

    private ObjectNode writeCapacityNode(String tableName, DynamoDbWriteCapacity.Cost cost,
                                          boolean withBreakdown) {
        return writeCapacityNode(tableName, cost, withBreakdown, null);
    }

    private ObjectNode writeCapacityNode(String tableName, DynamoDbWriteCapacity.Cost cost,
                                          boolean withBreakdown, String unitsField) {
        ObjectNode cc = objectMapper.createObjectNode();
        cc.put("TableName", DynamoDbTableNames.resolve(tableName));
        cc.setAll(capacityUnitsNode(cost.total(), unitsField));
        if (withBreakdown) {
            cc.set("Table", capacityUnitsNode(cost.table(), unitsField));
            if (!cost.gsi().isEmpty()) {
                cc.set("GlobalSecondaryIndexes", capacityUnitsMap(cost.gsi(), unitsField));
            }
            if (!cost.lsi().isEmpty()) {
                cc.set("LocalSecondaryIndexes", capacityUnitsMap(cost.lsi(), unitsField));
            }
            if (!cost.vectorBytes().isEmpty()) {
                cc.set("VectorIndexes", vectorWriteBytesMap(cost.vectorBytes()));
            }
        }
        return cc;
    }

    /** A vector index meters bytes processed, so it carries no CapacityUnits of its own. */
    private ObjectNode vectorWriteBytesMap(Map<String, Double> bytesByIndex) {
        ObjectNode node = objectMapper.createObjectNode();
        bytesByIndex.forEach((indexName, bytes) -> {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("VectorWriteRequestBytes", bytes);
            node.set(indexName, entry);
        });
        return node;
    }

    private ObjectNode capacityUnitsMap(Map<String, Double> unitsByIndex, String unitsField) {
        ObjectNode node = objectMapper.createObjectNode();
        unitsByIndex.forEach((indexName, units) -> node.set(indexName, capacityUnitsNode(units, unitsField)));
        return node;
    }

    private ObjectNode capacityUnitsNode(double units, String unitsField) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CapacityUnits", units);
        if (unitsField != null) {
            node.put(unitsField, units);
        }
        return node;
    }

    private void addConsumedCapacity(ObjectNode response, JsonNode request, String tableName,
                                      double cu, DynamoDbAccessPath accessPath) {
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if ("NONE".equals(returnCC)) return;

        ObjectNode cc = objectMapper.createObjectNode();
        cc.put("TableName", DynamoDbTableNames.resolve(tableName));
        cc.put("CapacityUnits", cu);

        if ("INDEXES".equals(returnCC)) {
            ObjectNode tableCap = objectMapper.createObjectNode();
            if (accessPath != null && accessPath.isIndex()) {
                tableCap.put("CapacityUnits", 0.0);
                cc.set("Table", tableCap);
                ObjectNode indexCaps = objectMapper.createObjectNode();
                ObjectNode indexCap = objectMapper.createObjectNode();
                indexCap.put("CapacityUnits", cu);
                indexCaps.set(accessPath.indexName(), indexCap);
                cc.set(accessPath.isGlobalSecondaryIndex()
                        ? "GlobalSecondaryIndexes" : "LocalSecondaryIndexes", indexCaps);
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
        node.put("TableId", table.getTableId());
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

        // TableClassSummary always present; defaults to STANDARD.
        ObjectNode tableClassSummary = objectMapper.createObjectNode();
        tableClassSummary.put("TableClass",
                table.getTableClass() != null ? table.getTableClass() : "STANDARD");
        node.set("TableClassSummary", tableClassSummary);

        if (table.getOnDemandMaxReadRequestUnits() != null
                || table.getOnDemandMaxWriteRequestUnits() != null) {
            ObjectNode odt = objectMapper.createObjectNode();
            if (table.getOnDemandMaxReadRequestUnits() != null) {
                odt.put("MaxReadRequestUnits", table.getOnDemandMaxReadRequestUnits());
            }
            if (table.getOnDemandMaxWriteRequestUnits() != null) {
                odt.put("MaxWriteRequestUnits", table.getOnDemandMaxWriteRequestUnits());
            }
            node.set("OnDemandThroughput", odt);
        }

        ObjectNode warmThroughput = objectMapper.createObjectNode();
        warmThroughput.put("Status", "ACTIVE");
        warmThroughput.put("ReadUnitsPerSecond", 0);
        warmThroughput.put("WriteUnitsPerSecond", 0);
        node.set("WarmThroughput", warmThroughput);

        // Global-table replicas. In a single-process emulator every region is served by this same
        // table, so a replica is metadata; AWS reports each as an ACTIVE Replica and marks the table
        // a 2019.11.21 global table. A global table also lists its own home region as a replica, so it
        // is appended (once) after the tracked ones; a plain table (no replicas, no home) has neither.
        List<String> replicaRegions = table.getReplicaRegions();
        String homeRegion = table.getGlobalTableHomeRegion();
        LinkedHashSet<String> regions = new LinkedHashSet<>(
                replicaRegions != null ? replicaRegions : List.of());
        if (homeRegion != null && !homeRegion.isBlank()) {
            regions.add(homeRegion);
        }
        if (!regions.isEmpty()) {
            ArrayNode replicas = objectMapper.createArrayNode();
            for (String replicaRegion : regions) {
                ObjectNode replica = objectMapper.createObjectNode();
                replica.put("RegionName", replicaRegion);
                replica.put("ReplicaStatus", "ACTIVE");
                replicas.add(replica);
            }
            node.set("Replicas", replicas);
            node.put("GlobalTableVersion", "2019.11.21");
        }

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

        if (table.isSseEnabled()) {
            ObjectNode sseDescription = objectMapper.createObjectNode();
            sseDescription.put("Status", "ENABLED");
            String sseType = table.getSseType() != null ? table.getSseType() : SSE_TYPE_KMS;
            sseDescription.put("SSEType", sseType);
            if (SSE_TYPE_KMS.equals(sseType)) {
                sseDescription.put("KMSMasterKeyArn", table.getKmsMasterKeyArn() != null
                        ? table.getKmsMasterKeyArn()
                        : defaultKmsMasterKeyArn(AwsArnUtils.regionOrDefault(table.getTableArn(), "us-east-1")));
            }
            node.set("SSEDescription", sseDescription);
        }

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

                if (gsi.getOnDemandMaxReadRequestUnits() != null
                        || gsi.getOnDemandMaxWriteRequestUnits() != null) {
                    ObjectNode gsiOdt = objectMapper.createObjectNode();
                    if (gsi.getOnDemandMaxReadRequestUnits() != null) {
                        gsiOdt.put("MaxReadRequestUnits", gsi.getOnDemandMaxReadRequestUnits());
                    }
                    if (gsi.getOnDemandMaxWriteRequestUnits() != null) {
                        gsiOdt.put("MaxWriteRequestUnits", gsi.getOnDemandMaxWriteRequestUnits());
                    }
                    gsiNode.set("OnDemandThroughput", gsiOdt);
                }

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

        List<VectorIndex> vectorIndexes = table.getVectorIndexes();
        if (!vectorIndexes.isEmpty()) {
            ArrayNode vectorIndexArray = objectMapper.createArrayNode();
            for (VectorIndex vectorIndex : vectorIndexes) {
                vectorIndexArray.add(vectorIndexToNode(vectorIndex));
            }
            node.set("VectorIndexes", vectorIndexArray);
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

    /**
     * Renders a VectorIndexDescription. ItemCount and IndexSizeBytes are always 0. AWS refreshes
     * those two roughly every six hours, so a freshly built index reports 0 for both there too.
     */
    private ObjectNode vectorIndexToNode(VectorIndex vectorIndex) {
        ObjectNode vectorIndexNode = objectMapper.createObjectNode();
        vectorIndexNode.put("IndexName", vectorIndex.getIndexName());

        ObjectNode vectorAttribute = objectMapper.createObjectNode();
        vectorAttribute.put("AttributeName", vectorIndex.getVectorAttributeName());
        vectorIndexNode.set("VectorAttribute", vectorAttribute);

        List<SearchSchemaElement> searchSchema = vectorIndex.getSearchSchema();
        if (searchSchema != null && !searchSchema.isEmpty()) {
            ArrayNode searchSchemaArray = objectMapper.createArrayNode();
            for (SearchSchemaElement element : searchSchema) {
                ObjectNode elementNode = objectMapper.createObjectNode();
                elementNode.put("AttributeName", element.getAttributeName());
                elementNode.put("SearchSchemaElementType", element.getSearchSchemaElementType());
                searchSchemaArray.add(elementNode);
            }
            vectorIndexNode.set("SearchSchema", searchSchemaArray);
        }

        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("ProjectionType",
                vectorIndex.getProjectionType() != null ? vectorIndex.getProjectionType() : "ALL");
        if ("INCLUDE".equals(vectorIndex.getProjectionType())) {
            ArrayNode nonKeyAttributes = objectMapper.createArrayNode();
            for (String attr : vectorIndex.getNonKeyAttributes()) {
                nonKeyAttributes.add(attr);
            }
            projection.set("NonKeyAttributes", nonKeyAttributes);
        }
        vectorIndexNode.set("Projection", projection);

        vectorIndexNode.put("Dimensions", vectorIndex.getDimensions());
        vectorIndexNode.put("DistanceFunction", vectorIndex.getDistanceFunction());
        vectorIndexNode.put("IndexStatus", vectorIndex.getIndexStatus());
        if (dynamoDbService.reportsVectorIndexBackfilling(vectorIndex)) {
            vectorIndexNode.put("Backfilling", dynamoDbService.isVectorIndexBackfilling(vectorIndex));
        }
        vectorIndexNode.put("IndexSizeBytes", 0);
        vectorIndexNode.put("ItemCount", 0);
        vectorIndexNode.put("IndexArn", vectorIndex.getIndexArn());
        return vectorIndexNode;
    }

    private String defaultKmsMasterKeyArn(String region) {
        return AwsArnUtils.Arn.of("kms", region, DEFAULT_ACCOUNT_ID,
                "key/aws-managed-dynamodb").toString();
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

    private Response handleImportTable(JsonNode request, String region) {
        var response = objectMapper.createObjectNode();
        response.set("ImportTableDescription", objectMapper.valueToTree(findOrStartImport(request, region)));
        return Response.ok(response).build();
    }

    /** Held across the ClientToken lookup and the table creation so identical concurrent requests share one import. */
    private ImportTableDescription findOrStartImport(JsonNode request, String region) {
        synchronized (importLock) {
            var existing = dynamoDbService.validateImportRequest(request);
            if (existing != null) {
                return existing;
            }
            return dynamoDbService.startImport(request,
                    createTableFromSpec(request.path("TableCreationParameters"), region, "CREATING"), region);
        }
    }

    private Response handleDescribeImport(JsonNode request, String region) {
        var desc = dynamoDbService.describeImport(request.path("ImportArn").asText());

        var response = objectMapper.createObjectNode();
        response.set("ImportTableDescription", objectMapper.valueToTree(desc));
        return Response.ok(response).build();
    }

    private Response handleListImports(JsonNode request, String region) {
        var tableArn = request.hasNonNull("TableArn") ? request.get("TableArn").asText() : null;
        var pageSize = request.hasNonNull("PageSize") ? request.get("PageSize").asInt() : null;
        var nextToken = request.hasNonNull("NextToken") ? request.get("NextToken").asText() : null;

        var result = dynamoDbService.listImports(tableArn, pageSize, nextToken);

        var response = objectMapper.createObjectNode();
        var summaries = objectMapper.createArrayNode();
        for (var summary : result.importSummaryList()) {
            summaries.add(objectMapper.valueToTree(summary));
        }
        response.set("ImportSummaryList", summaries);
        if (result.nextToken() != null) {
            response.put("NextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleExecuteStatement(JsonNode request, String region) {
        requireValidReturnConsumedCapacity(request);
        String statement = request.path("Statement").asText();
        List<JsonNode> parameters = toPartiQLParams(request.path("Parameters"));
        DynamoDbPartiQLParser.Stmt stmt = DynamoDbPartiQLParser.parse(statement, parameters);
        PartiQLExecuteContext ctx = PartiQLExecuteContext.builder()
                .limit(request.has("Limit") ? request.get("Limit").asInt() : null)
                .nextToken(request.has("NextToken") ? request.get("NextToken").asText() : null)
                .consistentRead(request.path("ConsistentRead").asBoolean(false))
                .tokenBinding(partiQLHandler.tokenBinding(statement, parameters));
        DynamoDbPartiQLHandler.Result result = partiQLHandler.execute(stmt, ctx, region);
        ObjectNode response = result.body();
        String returnCC = request.path("ReturnConsumedCapacity").asText("NONE");
        if (!"NONE".equals(returnCC)) {
            response.set("ConsumedCapacity",
                    writeCapacityNode(stmt.table(), result.capacity(), "INDEXES".equals(returnCC)));
        }
        return Response.ok(response).build();
    }

    private Response handleExecuteTransaction(JsonNode request, String region) {
        JsonNode stmts = request.path("TransactStatements");
        if (stmts.isMissingNode() || !stmts.isArray() || stmts.isEmpty()) {
            throw new AwsException("ValidationException", "TransactStatements must not be empty", 400);
        }
        List<String> validationErrors = new ArrayList<>();
        addReturnConsumedCapacityError(request, validationErrors);
        throwValidationErrors(validationErrors);
        try {
            List<DynamoDbPartiQLParser.Stmt> statements = new ArrayList<>();
            for (int i = 0; i < stmts.size(); i++) {
                JsonNode s = stmts.get(i);
                List<JsonNode> parameters = toPartiQLParams(s.path("Parameters"));
                statements.add(inTransactStatement(i,
                        () -> DynamoDbPartiQLParser.parse(s.path("Statement").asText(), parameters)));
            }
            long reads = statements.stream().filter(DynamoDbPartiQLParser.Stmt.Select.class::isInstance).count();
            if (reads > 0 && reads < statements.size()) {
                throw new AwsException("ValidationException",
                        "ExecuteTransaction API does not support both read and write operations in the same request.", 400);
            }
            if (reads == statements.size()) {
                return executeTransactionReads(statements.stream()
                        .map(DynamoDbPartiQLParser.Stmt.Select.class::cast)
                        .toList(), request, region);
            }
            List<DynamoDbPartiQLHandler.TransactMember> members = new ArrayList<>();
            for (int i = 0; i < statements.size(); i++) {
                DynamoDbPartiQLParser.Stmt stmt = statements.get(i);
                members.add(inTransactStatement(i, () -> partiQLHandler.toTransactItem(stmt, region)));
            }
            List<JsonNode> transactItems = cancelOnMemberReasons(members);
            DynamoDbService.TransactWriteResult result = dynamoDbService.transactWriteItems(transactItems, region,
                    request.path("ClientRequestToken").asText(null), request);
            ObjectNode resp = objectMapper.createObjectNode();
            resp.set("Responses", objectMapper.createArrayNode());
            addTransactWriteConsumedCapacity(resp, request, result);
            return Response.ok(resp).build();
        } catch (TransactionCanceledException e) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("__type", "TransactionCanceledException");
            body.put("message", e.getMessage());
            ArrayNode reasons = body.putArray("CancellationReasons");
            for (TransactionCanceledException.CancellationReason reason : e.getCancellationReasons()) {
                ObjectNode r = objectMapper.createObjectNode();
                r.put("Code", reason.code().isEmpty() ? "None" : reason.code());
                if (!reason.code().isEmpty()) {
                    r.put("Message", reason.message() != null ? reason.message() : "The conditional request failed");
                }
                if (reason.item() != null) {
                    r.set("Item", reason.item());
                }
                reasons.add(r);
            }
            return Response.status(400).entity(body).build();
        }
    }

    private Response executeTransactionReads(List<DynamoDbPartiQLParser.Stmt.Select> selects, JsonNode request,
                                             String region) {
        List<DynamoDbPartiQLHandler.TransactMember> members = new ArrayList<>();
        for (int i = 0; i < selects.size(); i++) {
            DynamoDbPartiQLParser.Stmt.Select select = selects.get(i);
            members.add(inTransactStatement(i, () -> partiQLHandler.toTransactGetItem(select, region)));
        }
        List<JsonNode> getItems = cancelOnMemberReasons(members);

        DynamoDbService.TransactGetResult result = dynamoDbService.transactGetItems(getItems, region);
        requireOneReadPerItem(getItems, region);
        List<JsonNode> results = result.items();
        ArrayNode responses = objectMapper.createArrayNode();
        for (int i = 0; i < results.size(); i++) {
            JsonNode item = partiQLHandler.projectSelected(selects.get(i), results.get(i));
            ObjectNode entry = objectMapper.createObjectNode();
            if (item != null) {
                entry.set("Item", item);
            }
            responses.add(entry);
        }
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("Responses", responses);
        addPerTableConsumedCapacity(resp, request, result.capacity(), "ReadCapacityUnits");
        return Response.ok(resp).build();
    }

    private static List<JsonNode> cancelOnMemberReasons(List<DynamoDbPartiQLHandler.TransactMember> members) {
        List<JsonNode> items = new ArrayList<>();
        List<TransactionCanceledException.CancellationReason> reasons = new ArrayList<>();
        boolean cancelled = false;
        for (DynamoDbPartiQLHandler.TransactMember member : members) {
            cancelled |= member.reason() != null;
            items.add(member.item());
            reasons.add(member.reason() != null ? member.reason() : new TransactionCanceledException.CancellationReason("", null));
        }
        if (cancelled) {
            throw new TransactionCanceledException(reasons);
        }
        return items;
    }

    private void requireOneReadPerItem(List<JsonNode> getItems, String region) {
        Set<List<String>> items = new HashSet<>();
        for (JsonNode getItem : getItems) {
            JsonNode get = getItem.path("Get");
            String tableName = get.path("TableName").asText();
            TableDefinition table = dynamoDbService.findTable(tableName, region).orElseThrow();
            String itemKey = dynamoDbService.buildItemKey(table, get.path("Key"), true);
            if (!items.add(List.of(tableName, itemKey))) {
                throw new AwsException("ValidationException",
                        "Transaction request cannot include multiple operations on one item", 400);
            }
        }
    }

    private <T> T inTransactStatement(int index, Supplier<T> member) {
        try {
            return member.get();
        } catch (AwsException e) {
            if (!"ValidationException".equals(e.getErrorCode())) {
                throw e;
            }
            throw new AwsException("ValidationException",
                    "Validation failed in TransactStatements[" + index + "]: " + e.getMessage(), 400);
        }
    }

    private Response handleBatchExecuteStatement(JsonNode request, String region) {
        requireValidReturnConsumedCapacity(request);
        JsonNode stmts = request.path("Statements");
        if (stmts.isMissingNode() || !stmts.isArray() || stmts.isEmpty()) {
            throw new AwsException("ValidationException", "Statements must not be empty", 400);
        }
        ArrayNode responses = objectMapper.createArrayNode();
        Map<String, DynamoDbWriteCapacity.Cost> capacity = new LinkedHashMap<>();
        for (JsonNode s : stmts) {
            responses.add(batchMemberResponse(s, region, capacity));
        }
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("Responses", responses);
        addPerTableConsumedCapacity(resp, request, capacity, null);
        return Response.ok(resp).build();
    }

    private ObjectNode batchMemberResponse(JsonNode statement, String region,
                                           Map<String, DynamoDbWriteCapacity.Cost> capacity) {
        ObjectNode slot = objectMapper.createObjectNode();
        String tableName = null;
        try {
            DynamoDbPartiQLParser.Stmt stmt = parsePartiQLStatement(statement);
            tableName = stmt.table();
            if (stmt instanceof DynamoDbPartiQLParser.Stmt.Select select) {
                requireBatchSelectReadsByKey(select, region);
            }
            DynamoDbPartiQLHandler.Result result = partiQLHandler.execute(stmt, PartiQLExecuteContext.builder()
                    .consistentRead(statement.path("ConsistentRead").asBoolean(false)), region);
            JsonNode firstItem = result.body().path("Items").path(0);
            if (!firstItem.isMissingNode()) {
                slot.set("Item", firstItem);
            }
            slot.put("TableName", tableName);
            capacity.merge(tableName, result.capacity(), DynamoDbWriteCapacity.Cost::plus);
        } catch (AwsException e) {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("Code", batchMemberErrorCode(e.getErrorCode()));
            err.put("Message", e.getMessage());
            slot.set("Error", err);
            if (tableName != null && batchMemberReachedItsTable(e)) {
                slot.put("TableName", tableName);
            }
        }
        return slot;
    }

    private static String batchMemberErrorCode(String errorCode) {
        if ("ValidationException".equals(errorCode)) {
            return "ValidationError";
        }
        return errorCode.endsWith("Exception")
                ? errorCode.substring(0, errorCode.length() - "Exception".length())
                : errorCode;
    }

    private static boolean batchMemberReachedItsTable(AwsException e) {
        return e instanceof ItemNestingExceededException
                || "ConditionalCheckFailedException".equals(e.getErrorCode())
                || "DuplicateItemException".equals(e.getErrorCode());
    }

    // BatchExecuteStatement only runs SELECT statements that resolve through
    // the table's primary key: index-qualified statements and partial-key
    // WHERE clauses are rejected per statement (characterised on real AWS,
    // eu-west-1, 2026-09-02).
    private void requireBatchSelectReadsByKey(DynamoDbPartiQLParser.Stmt.Select select, String region) {
        if (select.index() != null) {
            throw batchSelectNeedsKey();
        }
        TableDefinition table = dynamoDbService.requireTableForItemCall(select.table(), region);
        DynamoDbPartiQLHandler.requireReadsWithinLimit(table, select.where());
        if (!DynamoDbPartiQLHandler.namesOnlyTheKey(table, select.where())) {
            throw batchSelectNeedsKey();
        }
    }

    private static AwsException batchSelectNeedsKey() {
        return new AwsException("ValidationException",
                "Select statements within BatchExecuteStatement must specify the primary key in the where clause.", 400);
    }

    private DynamoDbPartiQLParser.Stmt parsePartiQLStatement(JsonNode statement) {
        return DynamoDbPartiQLParser.parse(statement.path("Statement").asText(),
                toPartiQLParams(statement.path("Parameters")));
    }

    private List<JsonNode> toPartiQLParams(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) {
            return Collections.emptyList();
        }
        List<JsonNode> params = new ArrayList<>();
        for (JsonNode parameter : node) {
            DynamoDbAttributeValueValidator.requireParameterNestingWithinLimit(parameter);
            checkAttrSets(parameter);
            params.add(parameter);
        }
        return params;
    }
}
