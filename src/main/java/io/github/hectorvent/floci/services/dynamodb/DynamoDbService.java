package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

@ApplicationScoped
public class DynamoDbService {

    private static final Logger LOG = Logger.getLogger(DynamoDbService.class);

    private final StorageBackend<String, TableDefinition> tableStore;
    private final StorageBackend<String, Map<String, JsonNode>> itemStore;
    // Items stored per table: storageKey -> Map<itemKey, item>
    // itemKey is "pk" or "pk#sk" depending on table schema
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<String, JsonNode>> itemsByTable = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private DynamoDbStreamService streamService;
    private KinesisStreamingForwarder kinesisForwarder;

    @Inject
    public DynamoDbService(StorageFactory storageFactory, RegionResolver regionResolver,
                           DynamoDbStreamService streamService,
                           KinesisStreamingForwarder kinesisForwarder) {
        this(storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}),
             storageFactory.create("dynamodb", "dynamodb-items.json",
                new TypeReference<Map<String, Map<String, JsonNode>>>() {}),
             regionResolver, streamService, kinesisForwarder);
    }

    /** Package-private constructor for testing. */
    DynamoDbService(StorageBackend<String, TableDefinition> tableStore) {
        this(tableStore, null, new RegionResolver("us-east-1", "000000000000"), null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore, RegionResolver regionResolver) {
        this(tableStore, null, regionResolver, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver) {
        this(tableStore, itemStore, regionResolver, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder) {
        this.tableStore = tableStore;
        this.itemStore = itemStore;
        this.regionResolver = regionResolver;
        this.streamService = streamService;
        this.kinesisForwarder = kinesisForwarder;
        loadPersistedItems();
    }

    private void loadPersistedItems() {
        if (itemStore == null) return;
        for (String key : itemStore.keys()) {
            itemStore.get(key).ifPresent(items ->
                itemsByTable.put(key, new ConcurrentSkipListMap<>(items)));
        }
    }

    private void persistItems(String storageKey) {
        if (itemStore == null) return;
        var items = itemsByTable.get(storageKey);
        if (items != null) {
            itemStore.put(storageKey, new HashMap<>(items));
        } else {
            itemStore.delete(storageKey);
        }
    }

    public TableDefinition createTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        Long readCapacity, Long writeCapacity) {
        return createTable(tableName, keySchema, attributeDefinitions, readCapacity, writeCapacity,
                           List.of(), List.of(), regionResolver.getDefaultRegion());
    }

    public TableDefinition createTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        Long readCapacity, Long writeCapacity, String region) {
        return createTable(tableName, keySchema, attributeDefinitions, readCapacity, writeCapacity,
                           List.of(), List.of(), region);
    }

    public TableDefinition createTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsis, String region) {
        return createTable(tableName, keySchema, attributeDefinitions, readCapacity, writeCapacity,
                           gsis, List.of(), region);
    }

    public TableDefinition createTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsis,
                                        List<LocalSecondaryIndex> lsis,
                                        String region) {
        String storageKey = regionKey(region, tableName);
        if (tableStore.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Table already exists: " + tableName, 400);
        }

        TableDefinition table = new TableDefinition(tableName, keySchema, attributeDefinitions,
                region, regionResolver.getAccountId());
        if (readCapacity != null && writeCapacity != null) {
            table.getProvisionedThroughput().setReadCapacityUnits(readCapacity);
            table.getProvisionedThroughput().setWriteCapacityUnits(writeCapacity);
        }

        if (gsis != null && !gsis.isEmpty()) {
            for (GlobalSecondaryIndex gsi : gsis) {
                gsi.setIndexArn(table.getTableArn() + "/index/" + gsi.getIndexName());
            }
            table.setGlobalSecondaryIndexes(new ArrayList<>(gsis));
        }

        if (lsis != null && !lsis.isEmpty()) {
            String tablePk = table.getPartitionKeyName();
            for (LocalSecondaryIndex lsi : lsis) {
                String lsiPk = lsi.getPartitionKeyName();
                if (!tablePk.equals(lsiPk)) {
                    throw new AwsException("ValidationException",
                            "LocalSecondaryIndex partition key must match table partition key", 400);
                }
                lsi.setIndexArn(table.getTableArn() + "/index/" + lsi.getIndexName());
            }
            table.setLocalSecondaryIndexes(new ArrayList<>(lsis));
        }

        tableStore.put(storageKey, table);
        itemsByTable.put(storageKey, new ConcurrentSkipListMap<>());
        LOG.infov("Created table: {0} in region {1}", tableName, region);
        return table;
    }

    public TableDefinition describeTable(String tableName) {
        return describeTable(tableName, regionResolver.getDefaultRegion());
    }

    public TableDefinition describeTable(String tableName, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        // Update dynamic counts
        var items = itemsByTable.get(storageKey);
        if (items != null) {
            table.setItemCount(items.size());
        }
        return table;
    }

    public void persistTable(String tableName, TableDefinition table, String region) {
        tableStore.put(regionKey(region, tableName), table);
    }

    public void deleteTable(String tableName) {
        deleteTable(tableName, regionResolver.getDefaultRegion());
    }

    public void deleteTable(String tableName, String region) {
        String storageKey = regionKey(region, tableName);
        if (tableStore.get(storageKey).isEmpty()) {
            throw resourceNotFoundException(tableName);
        }
        tableStore.delete(storageKey);
        itemsByTable.remove(storageKey);
        if (itemStore != null) {
            itemStore.delete(storageKey);
        }
        if (streamService != null) {
            streamService.deleteStream(tableName, region);
        }
        LOG.infov("Deleted table: {0}", tableName);
    }

    public List<String> listTables() {
        return listTables(regionResolver.getDefaultRegion());
    }

    public List<String> listTables(String region) {
        String prefix = region + "::";
        return tableStore.scan(k -> k.startsWith(prefix)).stream()
                .map(TableDefinition::getTableName)
                .toList();
    }

    public void putItem(String tableName, JsonNode item) {
        putItem(tableName, item, null, null, null, regionResolver.getDefaultRegion(), "NONE");
    }

    public void putItem(String tableName, JsonNode item, String region) {
        putItem(tableName, item, null, null, null, region, "NONE");
    }

    public void putItem(String tableName, JsonNode item, String region, String returnValuesOnConditionCheckFailure) {
        putItem(tableName, item, null, null, null, region, returnValuesOnConditionCheckFailure);
    }

    public void putItem(String tableName, JsonNode item,
                         String conditionExpression,
                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                         String region, String returnValuesOnConditionCheckFailure) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, item);
        var tableItems = itemsByTable.computeIfAbsent(storageKey, k -> new ConcurrentSkipListMap<>());

        JsonNode existing = tableItems.get(itemKey);

        if (conditionExpression != null) {
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
        }

        tableItems.put(itemKey, item);
        persistItems(storageKey);
        LOG.debugv("Put item in {0}: key={1}", tableName, itemKey);

        String eventName = existing == null ? "INSERT" : "MODIFY";
        if (streamService != null) {
            streamService.captureEvent(tableName, eventName, existing, item, table, region);
        }
        if (kinesisForwarder != null) {
            kinesisForwarder.forward(eventName, existing, item, table, region);
        }
    }

    public JsonNode getItem(String tableName, JsonNode key) {
        return getItem(tableName, key, regionResolver.getDefaultRegion());
    }

    public JsonNode getItem(String tableName, JsonNode key, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var items = itemsByTable.get(storageKey);
        if (items == null) return null;
        JsonNode item = items.get(itemKey);
        if (item != null && isExpired(item, table)) return null;
        return item;
    }

    public JsonNode deleteItem(String tableName, JsonNode key) {
        return deleteItem(tableName, key, null, null, null, regionResolver.getDefaultRegion(), "NONE");
    }

    public JsonNode deleteItem(String tableName, JsonNode key, String region) {
        return deleteItem(tableName, key, null, null, null, region, "NONE");
    }

    public JsonNode deleteItem(String tableName, JsonNode key, String region, String returnValuesOnConditionCheckFailure) {
        return deleteItem(tableName, key, null, null, null, region, "ALL_OLD");
    }

    public JsonNode deleteItem(String tableName, JsonNode key,
                                String conditionExpression,
                                JsonNode exprAttrNames, JsonNode exprAttrValues,
                                String region, String returnValuesOnConditionCheckFailure) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var items = itemsByTable.get(storageKey);
        if (items == null) return null;

        if (conditionExpression != null) {
            JsonNode existing = items.get(itemKey);
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
        }

        JsonNode removed = items.remove(itemKey);
        persistItems(storageKey);
        LOG.debugv("Deleted item from {0}: key={1}", tableName, itemKey);

        if (removed != null) {
            if (streamService != null) {
                streamService.captureEvent(tableName, "REMOVE", removed, null, table, region);
            }
            if (kinesisForwarder != null) {
                kinesisForwarder.forward("REMOVE", removed, null, table, region);
            }
        }

        return removed;
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                String updateExpression,
                                JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                String returnValues) {
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                          expressionAttrValues, returnValues, null, regionResolver.getDefaultRegion(), "NONE");
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String region) {
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                          expressionAttrValues, returnValues, null, region, "NONE");
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String region, 
                                    String returnValuesOnConditionCheckFailure) {
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                          expressionAttrValues, returnValues, null, region, returnValuesOnConditionCheckFailure);
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String conditionExpression, String region, 
                                    String returnValuesOnConditionCheckFailure) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var items = itemsByTable.computeIfAbsent(storageKey, k -> new ConcurrentSkipListMap<>());

        // Get existing item or create new one from key
        JsonNode existing = items.get(itemKey);

        if (conditionExpression != null) {
            evaluateCondition(existing, conditionExpression, expressionAttrNames, expressionAttrValues, returnValuesOnConditionCheckFailure);
        }

        ObjectNode item;
        if (existing != null) {
            item = existing.deepCopy();
        } else {
            item = key.deepCopy();
        }

        // Apply UpdateExpression (modern format: "SET #n = :val, age = :age REMOVE attr")
        if (updateExpression != null) {
            applyUpdateExpression(item, updateExpression, expressionAttrNames, expressionAttrValues);
        }
        // Apply attribute updates (legacy format: AttributeUpdates)
        else if (attributeUpdates != null && attributeUpdates.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = attributeUpdates.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String attrName = entry.getKey();
                JsonNode update = entry.getValue();
                String action = update.has("Action") ? update.get("Action").asText() : "PUT";
                JsonNode value = update.get("Value");

                switch (action) {
                    case "PUT" -> { if (value != null) item.set(attrName, value); }
                    case "DELETE" -> item.remove(attrName);
                    case "ADD" -> {
                        // Simple ADD for numeric values
                        if (value != null) item.set(attrName, value);
                    }
                }
            }
        }

        items.put(itemKey, item);
        persistItems(storageKey);

        if (streamService != null) {
            streamService.captureEvent(tableName, "MODIFY", existing, item, table, region);
        }
        if (kinesisForwarder != null) {
            kinesisForwarder.forward("MODIFY", existing, item, table, region);
        }

        return new UpdateResult(item, existing);
    }

    public QueryResult query(String tableName, JsonNode keyConditions,
                              JsonNode expressionAttrValues, String keyConditionExpression,
                              String filterExpression, Integer limit) {
        return query(tableName, keyConditions, expressionAttrValues, keyConditionExpression,
                     filterExpression, limit, null, null, null, null, regionResolver.getDefaultRegion());
    }

    public QueryResult query(String tableName, JsonNode keyConditions,
                              JsonNode expressionAttrValues, String keyConditionExpression,
                              String filterExpression, Integer limit, String region) {
        return query(tableName, keyConditions, expressionAttrValues, keyConditionExpression,
                     filterExpression, limit, null, null, null, null, region);
    }

    public QueryResult query(String tableName, JsonNode keyConditions,
                              JsonNode expressionAttrValues, String keyConditionExpression,
                              String filterExpression, Integer limit, Boolean scanIndexForward, String indexName,
                              JsonNode exclusiveStartKey, JsonNode exprAttrNames, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        var items = itemsByTable.get(storageKey);
        if (items == null) return new QueryResult(List.of(), 0, null);

        // Resolve key names: use GSI or table keys
        String pkName;
        String skName;
        if (indexName != null) {
            var gsi = table.findGsi(indexName);
            if (gsi.isPresent()) {
                pkName = gsi.get().getPartitionKeyName();
                skName = gsi.get().getSortKeyName();
            } else {
                var lsi = table.findLsi(indexName)
                        .orElseThrow(() -> new AwsException("ValidationException",
                                "The table does not have the specified index: " + indexName, 400));
                pkName = lsi.getPartitionKeyName();
                skName = lsi.getSortKeyName();
            }
        } else {
            pkName = table.getPartitionKeyName();
            skName = table.getSortKeyName();
        }

        List<JsonNode> results = new ArrayList<>();

        if (keyConditions != null) {
            // Legacy KeyConditions format
            JsonNode pkCondition = keyConditions.get(pkName);
            String pkValue = extractComparisonValue(pkCondition);

            for (JsonNode item : items.values()) {
                if (!item.has(pkName)) continue;
                if (matchesAttributeValue(item.get(pkName), pkValue)) {
                    if (skName != null && keyConditions.has(skName)) {
                        JsonNode skCondition = keyConditions.get(skName);
                        if (matchesKeyCondition(item.get(skName), skCondition)) {
                            results.add(item);
                        }
                    } else {
                        results.add(item);
                    }
                }
            }
        } else if (keyConditionExpression != null) {
            // Modern expression format with exprAttrNames support
            results = queryWithExpression(items, pkName, skName, keyConditionExpression,
                                          expressionAttrValues, exprAttrNames);
        }

        // Filter out items without GSI key attributes (sparse index behavior).
        // DynamoDB excludes items from a GSI if any key attribute is null/missing.
        if (indexName != null) {
            String finalPkName = pkName;
            String finalSkName = skName;
            results = results.stream()
                    .filter(item -> item.has(finalPkName) && hasNonNullAttribute(item, finalPkName))
                    .filter(item -> finalSkName == null || (item.has(finalSkName) && hasNonNullAttribute(item, finalSkName)))
                    .toList();
        }

        // Filter out TTL-expired items
        results = results.stream().filter(item -> !isExpired(item, table)).toList();

        // Sort by sort key if present
        if (skName != null) {
            String finalSkName = skName;
            results = new ArrayList<>(results);
            results.sort((a, b) -> {
                String aVal = extractScalarValue(a.get(finalSkName));
                String bVal = extractScalarValue(b.get(finalSkName));
                if (aVal == null && bVal == null) return 0;
                if (aVal == null) return -1;
                if (bVal == null) return 1;
                return compareValues(aVal, bVal);
            });
            if (Boolean.FALSE.equals(scanIndexForward)) {
                Collections.reverse(results);
            }
        }

        // Apply ExclusiveStartKey offset
        if (exclusiveStartKey != null) {
            TableDefinition finalTable = table;
            String startItemKey = buildItemKeyFromNode(exclusiveStartKey, pkName, skName);
            int startIdx = -1;
            for (int i = 0; i < results.size(); i++) {
                String thisKey = buildItemKeyFromNode(results.get(i), pkName, skName);
                if (thisKey.equals(startItemKey)) {
                    startIdx = i;
                    break;
                }
            }
            if (startIdx >= 0) {
                results = new ArrayList<>(results.subList(startIdx + 1, results.size()));
            }
        }

        List<JsonNode> evaluatedItems = results;
        JsonNode lastEvaluatedKey = null;

        if (limit != null && limit > 0 && evaluatedItems.size() > limit) {
            JsonNode lastItem = evaluatedItems.get(limit - 1);
            lastEvaluatedKey = buildKeyNode(table, lastItem, pkName, skName);
            evaluatedItems = new ArrayList<>(evaluatedItems.subList(0, limit));
        }

        int scannedCount = evaluatedItems.size();

        if (filterExpression != null) {
            evaluatedItems = evaluatedItems.stream()
                    .filter(item -> matchesFilterExpression(item, filterExpression,
                            exprAttrNames, expressionAttrValues))
                    .toList();
        }

        return new QueryResult(evaluatedItems, scannedCount, lastEvaluatedKey);
    }

    public ScanResult scan(String tableName, String filterExpression,
                            JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                            JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey) {
        return scan(tableName, filterExpression, expressionAttrNames, expressionAttrValues,
                    scanFilter, limit, exclusiveStartKey, regionResolver.getDefaultRegion());
    }

    public ScanResult scan(String tableName, String filterExpression,
                            JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                            JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        var items = itemsByTable.get(storageKey);
        if (items == null) return new ScanResult(List.of(), 0, null);

        // ConcurrentSkipListMap keeps items sorted by item key — no sort needed.
        // Use tailMap for O(log n) pagination instead of O(n) linear search.
        String pkName = table.getPartitionKeyName();
        String skName = table.getSortKeyName();

        var source = exclusiveStartKey != null
                ? items.tailMap(buildItemKeyFromNode(exclusiveStartKey, pkName, skName), false).values()
                : items.values();

        int totalScanned = 0;
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode item : source) {
            totalScanned++;
            if (isExpired(item, table)) {
                continue;
            }
            if (filterExpression != null
                    && !matchesFilterExpression(item, filterExpression, expressionAttrNames, expressionAttrValues)) {
                continue;
            }
            if (scanFilter != null && !matchesScanFilter(item, scanFilter)) {
                continue;
            }
            results.add(item);
        }

        JsonNode lastEvaluatedKey = null;
        if (limit != null && limit > 0 && results.size() > limit) {
            JsonNode lastItem = results.get(limit - 1);
            lastEvaluatedKey = buildKeyNode(table, lastItem, pkName, skName);
            results = results.subList(0, limit);
        }

        return new ScanResult(results, totalScanned, lastEvaluatedKey);
    }

    private boolean matchesScanFilter(JsonNode item, JsonNode scanFilter) {
        Iterator<Map.Entry<String, JsonNode>> fields = scanFilter.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String attrName = entry.getKey();
            JsonNode condition = entry.getValue();
            JsonNode attrValue = item.get(attrName);
            if (!matchesKeyCondition(attrValue, condition)) {
                return false;
            }
        }
        return true;
    }

    // --- Batch Operations ---

    public record BatchWriteResult(Map<String, List<JsonNode>> unprocessedItems) {}

    public BatchWriteResult batchWriteItem(Map<String, List<JsonNode>> requestItems, String region) {
        for (Map.Entry<String, List<JsonNode>> entry : requestItems.entrySet()) {
            String tableName = entry.getKey();
            for (JsonNode writeRequest : entry.getValue()) {
                if (writeRequest.has("PutRequest")) {
                    JsonNode item = writeRequest.get("PutRequest").get("Item");
                    putItem(tableName, item, region);
                } else if (writeRequest.has("DeleteRequest")) {
                    JsonNode key = writeRequest.get("DeleteRequest").get("Key");
                    deleteItem(tableName, key, region);
                }
            }
        }
        return new BatchWriteResult(Map.of());
    }

    public record BatchGetResult(Map<String, List<JsonNode>> responses, Map<String, JsonNode> unprocessedKeys) {}

    public BatchGetResult batchGetItem(Map<String, JsonNode> requestItems, String region) {
        Map<String, List<JsonNode>> responses = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : requestItems.entrySet()) {
            String tableName = entry.getKey();
            JsonNode tableRequest = entry.getValue();
            JsonNode keys = tableRequest.get("Keys");
            List<JsonNode> tableItems = new ArrayList<>();
            if (keys != null && keys.isArray()) {
                for (JsonNode key : keys) {
                    JsonNode item = getItem(tableName, key, region);
                    if (item != null) {
                        tableItems.add(item);
                    }
                }
            }
            responses.put(tableName, tableItems);
        }
        return new BatchGetResult(responses, Map.of());
    }

    // --- Transact Operations ---

    public void transactWriteItems(List<JsonNode> transactItems, String region) {
        // First pass: evaluate all conditions and collect failures
        List<String> cancellationReasons = new ArrayList<>();
        boolean hasFailed = false;

        for (JsonNode transactItem : transactItems) {
            String failReason = evaluateTransactCondition(transactItem, region);
            if (failReason != null) {
                hasFailed = true;
                cancellationReasons.add(failReason);
            } else {
                cancellationReasons.add("");
            }
        }

        if (hasFailed) {
            throw new TransactionCanceledException(cancellationReasons);
        }

        // Second pass: apply all writes
        for (JsonNode transactItem : transactItems) {
            if (transactItem.has("Put")) {
                JsonNode put = transactItem.get("Put");
                String tableName = put.path("TableName").asText();
                JsonNode item = put.get("Item");
                putItem(tableName, item, region);
            } else if (transactItem.has("Delete")) {
                JsonNode del = transactItem.get("Delete");
                String tableName = del.path("TableName").asText();
                JsonNode key = del.get("Key");
                deleteItem(tableName, key, region);
            } else if (transactItem.has("Update")) {
                JsonNode upd = transactItem.get("Update");
                String tableName = upd.path("TableName").asText();
                JsonNode key = upd.get("Key");
                String updateExpression = upd.has("UpdateExpression") ? upd.get("UpdateExpression").asText() : null;
                JsonNode exprAttrNames = upd.has("ExpressionAttributeNames") ? upd.get("ExpressionAttributeNames") : null;
                JsonNode exprAttrValues = upd.has("ExpressionAttributeValues") ? upd.get("ExpressionAttributeValues") : null;
                updateItem(tableName, key, null, updateExpression, exprAttrNames, exprAttrValues,
                           "NONE", null, region);
            }
            // ConditionCheck-only items are handled in the first pass only
        }
    }

    private String evaluateTransactCondition(JsonNode transactItem, String region) {
        JsonNode target;
        if (transactItem.has("Put")) {
            target = transactItem.get("Put");
        } else if (transactItem.has("Delete")) {
            target = transactItem.get("Delete");
        } else if (transactItem.has("Update")) {
            target = transactItem.get("Update");
        } else if (transactItem.has("ConditionCheck")) {
            target = transactItem.get("ConditionCheck");
        } else {
            return null;
        }

        String conditionExpression = target.has("ConditionExpression")
                ? target.get("ConditionExpression").asText() : null;
        if (conditionExpression == null) {
            return null;
        }
        String returnValuesOnConditionCheckFailure = target.has("ReturnValuesOnConditionCheckFailure")
                ? target.get("ReturnValuesOnConditionCheckFailure").asText() : null;

        String tableName = target.path("TableName").asText();
        JsonNode key = transactItem.has("Put") ? target.get("Item") : target.get("Key");
        JsonNode exprAttrNames = target.has("ExpressionAttributeNames") ? target.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = target.has("ExpressionAttributeValues") ? target.get("ExpressionAttributeValues") : null;

        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var tableItems = itemsByTable.get(storageKey);
        JsonNode existing = tableItems != null ? tableItems.get(itemKey) : null;

        try {
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            return null;
        } catch (AwsException e) {
            return e.getMessage();
        }
    }

    public List<JsonNode> transactGetItems(List<JsonNode> transactItems, String region) {
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode transactItem : transactItems) {
            if (transactItem.has("Get")) {
                JsonNode get = transactItem.get("Get");
                String tableName = get.path("TableName").asText();
                JsonNode key = get.get("Key");
                results.add(getItem(tableName, key, region));
            } else {
                results.add(null);
            }
        }
        return results;
    }

    // --- UpdateTable ---

    public TableDefinition updateTable(String tableName, Long readCapacity, Long writeCapacity, String region) {
        return updateTable(tableName, readCapacity, writeCapacity, List.of(), List.of(), List.of(), region);
    }

    public TableDefinition updateTable(String tableName, Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsiCreates, List<String> gsiDeletes,
                                        List<AttributeDefinition> newAttrDefs, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        if (readCapacity != null) {
            table.getProvisionedThroughput().setReadCapacityUnits(readCapacity);
        }
        if (writeCapacity != null) {
            table.getProvisionedThroughput().setWriteCapacityUnits(writeCapacity);
        }

        for (String indexName : gsiDeletes) {
            table.getGlobalSecondaryIndexes().removeIf(g -> indexName.equals(g.getIndexName()));
        }

        for (GlobalSecondaryIndex gsi : gsiCreates) {
            gsi.setIndexArn(table.getTableArn() + "/index/" + gsi.getIndexName());
            table.getGlobalSecondaryIndexes().add(gsi);
        }

        if (newAttrDefs != null && !newAttrDefs.isEmpty()) {
            List<AttributeDefinition> existing = table.getAttributeDefinitions();
            for (AttributeDefinition newDef : newAttrDefs) {
                boolean found = existing.stream()
                        .anyMatch(e -> e.getAttributeName().equals(newDef.getAttributeName()));
                if (!found) {
                    existing.add(newDef);
                }
            }
        }

        tableStore.put(storageKey, table);
        LOG.infov("Updated table: {0} in region {1}", tableName, region);
        return table;
    }

    // --- TTL ---

    public void updateTimeToLive(String tableName, String ttlAttributeName, boolean enabled, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));
        table.setTtlAttributeName(ttlAttributeName);
        table.setTtlEnabled(enabled);
        tableStore.put(storageKey, table);
        LOG.infov("Updated TTL for table {0}: enabled={1}, attr={2}", tableName, enabled, ttlAttributeName);
    }

    public TableDefinition updateContinuousBackups(String tableName, boolean enabled,
                                                   Integer recoveryPeriodInDays, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));
        table.setPointInTimeRecoveryEnabled(enabled);
        table.setPointInTimeRecoveryRecoveryPeriodInDays(
                recoveryPeriodInDays != null ? recoveryPeriodInDays : table.getPointInTimeRecoveryRecoveryPeriodInDays());
        tableStore.put(storageKey, table);
        LOG.infov("Updated PITR for table {0}: enabled={1}, recoveryPeriodInDays={2}",
                tableName, enabled, table.getPointInTimeRecoveryRecoveryPeriodInDays());
        return table;
    }

    static boolean isExpired(JsonNode item, TableDefinition table) {
        if (!table.isTtlEnabled() || table.getTtlAttributeName() == null) return false;
        JsonNode attr = item.get(table.getTtlAttributeName());
        if (attr == null || !attr.has("N")) return false;
        try {
            return Long.parseLong(attr.get("N").asText()) < Instant.now().getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    void deleteExpiredItems() {
        int totalDeleted = 0;
        for (String storageKey : tableStore.keys()) {
            TableDefinition table = tableStore.get(storageKey).orElse(null);
            if (table == null || !table.isTtlEnabled() || table.getTtlAttributeName() == null) {
                continue;
            }
            var items = itemsByTable.get(storageKey);
            if (items == null) continue;

            List<String> expiredKeys = items.entrySet().stream()
                    .filter(e -> isExpired(e.getValue(), table))
                    .map(Map.Entry::getKey)
                    .toList();

            if (expiredKeys.isEmpty()) continue;

            String region = storageKey.split("::", 2)[0];
            for (String itemKey : expiredKeys) {
                JsonNode removed = items.remove(itemKey);
                if (removed != null) {
                    if (streamService != null) {
                        streamService.captureEvent(table.getTableName(), "REMOVE", removed, null, table, region);
                    }
                    if (kinesisForwarder != null) {
                        kinesisForwarder.forward("REMOVE", removed, null, table, region);
                    }
                }
            }
            persistItems(storageKey);
            totalDeleted += expiredKeys.size();
        }
        if (totalDeleted > 0) {
            LOG.infov("TTL sweeper removed {0} expired items", totalDeleted);
        }
    }

    // --- Tag Operations ---

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        if (table.getTags() == null) {
            table.setTags(new HashMap<>());
        }
        table.getTags().putAll(tags);
        String storageKey = regionKey(region, table.getTableName());
        tableStore.put(storageKey, table);
        LOG.debugv("Tagged resource: {0}", resourceArn);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        if (table.getTags() != null) {
            for (String key : tagKeys) {
                table.getTags().remove(key);
            }
            String storageKey = regionKey(region, table.getTableName());
            tableStore.put(storageKey, table);
        }
        LOG.debugv("Untagged resource: {0}", resourceArn);
    }

    public Map<String, String> listTagsOfResource(String resourceArn, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        return table.getTags() != null ? table.getTags() : Map.of();
    }

    private TableDefinition findTableByArn(String arn, String region) {
        String prefix = region + "::";
        return tableStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(t -> arn.equals(t.getTableArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Requested resource not found: " + arn, 400));
    }

    // --- Condition expression evaluation ---

    private void evaluateCondition(JsonNode existingItem, String conditionExpression,
                                    JsonNode exprAttrNames, JsonNode exprAttrValues, String returnValuesOnConditionCheckFailure) {
        if (!matchesFilterExpression(existingItem, conditionExpression, exprAttrNames, exprAttrValues)) {
            if ("ALL_OLD".equals(returnValuesOnConditionCheckFailure)){                
                throw new ConditionalCheckFailedException(existingItem);
            }
            else {
                throw new ConditionalCheckFailedException(null);
            }
        }
    }

    // --- UpdateExpression parsing ---

    private void applyUpdateExpression(ObjectNode item, String expression,
                                        JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // Parse SET and REMOVE clauses from expressions like:
        // "SET #n = :newName, age = :newAge REMOVE oldField"
        String remaining = expression.trim();

        while (!remaining.isEmpty()) {
            String upper = remaining.toUpperCase();
            if (upper.startsWith("SET ")) {
                remaining = remaining.substring(4).trim();
                remaining = applySetClause(item, remaining, exprAttrNames, exprAttrValues);
            } else if (upper.startsWith("REMOVE ")) {
                remaining = remaining.substring(7).trim();
                remaining = applyRemoveClause(item, remaining, exprAttrNames);
            } else if (upper.startsWith("ADD ")) {
                remaining = remaining.substring(4).trim();
                remaining = applyAddClause(item, remaining, exprAttrNames, exprAttrValues);
            } else if (upper.startsWith("DELETE ")) {
                remaining = remaining.substring(7).trim();
                remaining = applyDeleteClause(item, remaining, exprAttrNames, exprAttrValues);
            } else {
                break;
            }
        }
    }

    private String applySetClause(ObjectNode item, String clause,
                                   JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // Parse comma-separated assignments: "attr = :val, #name = :val2"
        // Stop when we hit another clause keyword (REMOVE, ADD, DELETE) or end
        LOG.debugv("applySetClause: clause={0}, exprAttrNames={1}, exprAttrValues={2}",
                   clause, exprAttrNames, exprAttrValues);
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("REMOVE ") || upper.startsWith("ADD ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Parse "attrPath = valueExpr"
            int eqIdx = clause.indexOf('=');
            if (eqIdx < 0) break;

            String attrPath = clause.substring(0, eqIdx).trim();
            String attrName = resolveAttributeName(attrPath, exprAttrNames);

            String rest = clause.substring(eqIdx + 1).trim();

            // Find the value placeholder or expression
            // IMPORTANT: Check for clause keywords FIRST, then commas
            // This ensures we don't include REMOVE/ADD/DELETE clauses in value parts
            String valuePart;
            int nextClause = findNextClauseKeyword(rest);
            int commaIdx = findNextComma(rest);

            // If there's a clause keyword, use the earlier of comma or keyword
            if (nextClause >= 0 && (commaIdx < 0 || nextClause < commaIdx)) {
                valuePart = rest.substring(0, nextClause).trim();
                rest = rest.substring(nextClause).trim();
            } else if (commaIdx >= 0) {
                valuePart = rest.substring(0, commaIdx).trim();
                rest = rest.substring(commaIdx + 1).trim();
            } else {
                valuePart = rest.trim();
                rest = "";
            }


            // Resolve the value
            if (valuePart.startsWith("if_not_exists(")) {
                // if_not_exists(attrRef, fallbackExpr) evaluates to:
                //   attrRef's current value  — when attrRef exists in the item
                //   fallbackExpr             — otherwise
                // The result is always assigned to attrName.
                String[] args = extractFunctionArgs(valuePart);
                if (args.length == 2) {
                    String checkAttr = resolveAttributeName(args[0].trim(), exprAttrNames);
                    String fallbackExpr = args[1].trim();
                    JsonNode resolved;
                    if (hasValueAtPath(item, checkAttr, exprAttrNames)) {
                        // attrRef exists — evaluate to its current value
                        resolved = getValueAtPath(item, checkAttr, exprAttrNames);
                    } else if (fallbackExpr.startsWith(":") && exprAttrValues != null) {
                        resolved = exprAttrValues.get(fallbackExpr);
                    } else {
                        // fallback is itself an attribute reference
                        resolved = getValueAtPath(item, resolveAttributeName(fallbackExpr, exprAttrNames), exprAttrNames);
                    }
                    if (resolved != null) {
                        setValueAtPath(item, attrPath, resolved, exprAttrNames);
                    }
                }
            } else if (valuePart.toLowerCase().startsWith("list_append(")) {
                int open = valuePart.indexOf('(');
                int close = valuePart.lastIndexOf(')');
                if (open >= 0 && close > open) {
                    String inner = valuePart.substring(open + 1, close);
                    int commaPos = findNextComma(inner);
                    if (commaPos >= 0) {
                        String arg1 = inner.substring(0, commaPos).trim();
                        String arg2 = inner.substring(commaPos + 1).trim();
                        JsonNode list1 = evaluateSetExpr(item, arg1, exprAttrNames, exprAttrValues);
                        JsonNode list2 = evaluateSetExpr(item, arg2, exprAttrNames, exprAttrValues);
                        if (list1 != null && list2 != null && list1.has("L") && list2.has("L")) {
                            com.fasterxml.jackson.databind.node.ArrayNode merged =
                                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                            list1.get("L").forEach(merged::add);
                            list2.get("L").forEach(merged::add);
                            com.fasterxml.jackson.databind.node.ObjectNode result =
                                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                            result.set("L", merged);
                            item.set(attrName, result);
                        }
                    }
                }
            } else if (valuePart.startsWith(":") && exprAttrValues != null) {
                JsonNode value = exprAttrValues.get(valuePart);
                LOG.debugv("applySetClause: looked up valuePart={0} in exprAttrValues, got value={1}",
                           valuePart, value);
                if (value != null) {
                    setValueAtPath(item, attrPath, value, exprAttrNames);
                    LOG.debugv("applySetClause: set attrPath={0} to value={1}", attrPath, value);
                } else {
                    LOG.debugv("applySetClause: value was null for valuePart={0}, NOT setting attribute", valuePart);
                }
            } else if (!valuePart.isEmpty()) {
                // Plain attribute reference: SET a = b  or  SET a = #alias
                String refAttr = resolveAttributeName(valuePart, exprAttrNames);
                JsonNode refValue = getValueAtPath(item, refAttr, exprAttrNames);
                if (refValue != null) {
                    setValueAtPath(item, attrPath, refValue, exprAttrNames);
                }
            }

            clause = rest;
        }
        return clause;
    }

    private JsonNode evaluateSetExpr(ObjectNode item, String expr,
                                     JsonNode exprAttrNames, JsonNode exprAttrValues) {
        if (expr.toLowerCase().startsWith("if_not_exists(")) {
            String[] args = extractFunctionArgs(expr);
            if (args.length == 2) {
                String checkAttr = resolveAttributeName(args[0].trim(), exprAttrNames);
                String fallbackExpr = args[1].trim();
                if (item.has(checkAttr)) {
                    return item.get(checkAttr);
                } else if (fallbackExpr.startsWith(":") && exprAttrValues != null) {
                    return exprAttrValues.get(fallbackExpr);
                } else {
                    return item.get(resolveAttributeName(fallbackExpr, exprAttrNames));
                }
            }
            return null;
        } else if (expr.startsWith(":") && exprAttrValues != null) {
            return exprAttrValues.get(expr);
        } else {
            return item.get(resolveAttributeName(expr, exprAttrNames));
        }
    }

    private String applyRemoveClause(ObjectNode item, String clause, JsonNode exprAttrNames) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("ADD ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Split on the earlier of the next clause keyword or the next comma.
            // Prefer the keyword when it comes first so intra-clause commas in a
            // following clause (e.g. "REMOVE a SET b = :b, c = :c") don't bleed
            // into this helper's attribute parsing.
            int commaIdx = findNextComma(clause);
            int nextClause = findNextClauseKeyword(clause);
            String attrPart;
            if (nextClause >= 0 && (commaIdx < 0 || nextClause < commaIdx)) {
                attrPart = clause.substring(0, nextClause).trim();
                clause = clause.substring(nextClause).trim();
            } else if (commaIdx >= 0) {
                attrPart = clause.substring(0, commaIdx).trim();
                clause = clause.substring(commaIdx + 1).trim();
            } else {
                attrPart = clause.trim();
                clause = "";
            }

            removeValueAtPath(item, attrPart, exprAttrNames);
        }
        return clause;
    }

    private String applyAddClause(ObjectNode item, String clause,
                                  JsonNode exprAttrNames, JsonNode exprAttrValues) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("REMOVE ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Parse "attr :val"
            String[] parts = clause.split("\\s+", 3);
            if (parts.length < 2) break;

            String attrName = resolveAttributeName(parts[0], exprAttrNames);
            String valuePlaceholder = parts[1].replaceAll(",.*", "").trim();

            if (valuePlaceholder.startsWith(":") && exprAttrValues != null) {
                JsonNode addValue = exprAttrValues.get(valuePlaceholder);
                if (addValue != null) {
                    JsonNode existingValue = item.get(attrName);
                    JsonNode newValue = applyAddOperation(existingValue, addValue);
                    item.set(attrName, newValue);
                }
            }

            // Advance past this assignment. Prefer the next clause keyword when
            // it precedes the next comma so intra-clause commas in a following
            // SET (e.g. "ADD a :v SET b = :b, c = :c") don't swallow the keyword.
            int commaIdx = findNextComma(clause);
            int nextClause = findNextClauseKeyword(clause);
            if (nextClause >= 0 && (commaIdx < 0 || nextClause < commaIdx)) {
                clause = clause.substring(nextClause).trim();
            } else if (commaIdx >= 0) {
                clause = clause.substring(commaIdx + 1).trim();
            } else {
                clause = "";
            }
        }
        return clause;
    }

    /**
     * Implements DynamoDB ADD operation semantics:
     * - For numbers (N): adds the value to the existing number, or sets it if attribute doesn't exist
     * - For sets (SS, NS, BS): adds elements to the existing set, or creates the set if it doesn't exist
     */
    private JsonNode applyAddOperation(JsonNode existingValue, JsonNode addValue) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

        // Handle number addition
        if (addValue.has("N")) {
            String addNumStr = addValue.get("N").asText();
            if (existingValue == null || !existingValue.has("N")) {
                // Attribute doesn't exist — set to the add value
                return addValue;
            }
            // Add the numbers
            String existingNumStr = existingValue.get("N").asText();
            try {
                java.math.BigDecimal existingNum = new java.math.BigDecimal(existingNumStr);
                java.math.BigDecimal addNum = new java.math.BigDecimal(addNumStr);
                result.put("N", existingNum.add(addNum).toPlainString());
                return result;
            } catch (NumberFormatException e) {
                // Fall back to just setting the value
                return addValue;
            }
        }

        // Handle string set (SS) addition
        if (addValue.has("SS")) {
            if (existingValue == null || !existingValue.has("SS")) {
                return addValue;
            }
            java.util.Set<String> combined = new java.util.LinkedHashSet<>();
            existingValue.get("SS").forEach(n -> combined.add(n.asText()));
            addValue.get("SS").forEach(n -> combined.add(n.asText()));
            var arrayNode = result.putArray("SS");
            combined.forEach(arrayNode::add);
            return result;
        }

        // Handle number set (NS) addition
        if (addValue.has("NS")) {
            if (existingValue == null || !existingValue.has("NS")) {
                return addValue;
            }
            java.util.Set<String> combined = new java.util.LinkedHashSet<>();
            existingValue.get("NS").forEach(n -> combined.add(n.asText()));
            addValue.get("NS").forEach(n -> combined.add(n.asText()));
            var arrayNode = result.putArray("NS");
            combined.forEach(arrayNode::add);
            return result;
        }

        // Handle binary set (BS) addition
        if (addValue.has("BS")) {
            if (existingValue == null || !existingValue.has("BS")) {
                return addValue;
            }
            java.util.Set<String> combined = new java.util.LinkedHashSet<>();
            existingValue.get("BS").forEach(n -> combined.add(n.asText()));
            addValue.get("BS").forEach(n -> combined.add(n.asText()));
            var arrayNode = result.putArray("BS");
            combined.forEach(arrayNode::add);
            return result;
        }

        // Unsupported type for ADD — just set the value
        return addValue;
    }

    private String applyDeleteClause(ObjectNode item, String clause,
                                     JsonNode exprAttrNames, JsonNode exprAttrValues) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("REMOVE ") || upper.startsWith("ADD ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Parse "attr :val"
            String[] parts = clause.split("\\s+", 3);
            if (parts.length < 2) break;

            String attrName = resolveAttributeName(parts[0], exprAttrNames);
            String valuePlaceholder = parts[1].replaceAll(",.*", "").trim();

            if (valuePlaceholder.startsWith(":") && exprAttrValues != null) {
                JsonNode deleteValue = exprAttrValues.get(valuePlaceholder);
                if (deleteValue != null) {
                    JsonNode existingValue = item.get(attrName);
                    if (existingValue != null) {
                        JsonNode newValue = applyDeleteOperation(existingValue, deleteValue);
                        if (newValue == null) {
                            item.remove(attrName);
                        } else {
                            item.set(attrName, newValue);
                        }
                    }
                }
            }

            // Advance past this assignment. Prefer the next clause keyword when
            // it precedes the next comma so intra-clause commas in a following
            // SET (e.g. "DELETE s :v SET b = :b, c = :c") don't swallow the keyword.
            int commaIdx = findNextComma(clause);
            int nextClause = findNextClauseKeyword(clause);
            if (nextClause >= 0 && (commaIdx < 0 || nextClause < commaIdx)) {
                clause = clause.substring(nextClause).trim();
            } else if (commaIdx >= 0) {
                clause = clause.substring(commaIdx + 1).trim();
            } else {
                clause = "";
            }
        }
        return clause;
    }

    /**
     * Implements DynamoDB DELETE operation semantics:
     * removes the specified elements from a set attribute (SS, NS, BS).
     * Returns null if the resulting set is empty (caller should remove the attribute).
     * Returns the existing value unchanged if types don't match or the value isn't a set.
     */
    private JsonNode applyDeleteOperation(JsonNode existingValue, JsonNode deleteValue) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

        if (deleteValue.has("SS") && existingValue.has("SS")) {
            java.util.Set<String> toRemove = new java.util.LinkedHashSet<>();
            deleteValue.get("SS").forEach(n -> toRemove.add(n.asText()));
            java.util.List<String> remaining = new java.util.ArrayList<>();
            existingValue.get("SS").forEach(n -> {
                if (!toRemove.contains(n.asText())) remaining.add(n.asText());
            });
            if (remaining.isEmpty()) return null;
            var arrayNode = result.putArray("SS");
            remaining.forEach(arrayNode::add);
            return result;
        }

        if (deleteValue.has("NS") && existingValue.has("NS")) {
            java.util.Set<String> toRemove = new java.util.LinkedHashSet<>();
            deleteValue.get("NS").forEach(n -> toRemove.add(n.asText()));
            java.util.List<String> remaining = new java.util.ArrayList<>();
            existingValue.get("NS").forEach(n -> {
                if (!toRemove.contains(n.asText())) remaining.add(n.asText());
            });
            if (remaining.isEmpty()) return null;
            var arrayNode = result.putArray("NS");
            remaining.forEach(arrayNode::add);
            return result;
        }

        if (deleteValue.has("BS") && existingValue.has("BS")) {
            java.util.Set<String> toRemove = new java.util.LinkedHashSet<>();
            deleteValue.get("BS").forEach(n -> toRemove.add(n.asText()));
            java.util.List<String> remaining = new java.util.ArrayList<>();
            existingValue.get("BS").forEach(n -> {
                if (!toRemove.contains(n.asText())) remaining.add(n.asText());
            });
            if (remaining.isEmpty()) return null;
            var arrayNode = result.putArray("BS");
            remaining.forEach(arrayNode::add);
            return result;
        }

        // DELETE on non-set types or mismatched set types is a no-op per DynamoDB spec
        return existingValue;
    }

    String resolveAttributeName(String nameOrPlaceholder, JsonNode exprAttrNames) {
        nameOrPlaceholder = nameOrPlaceholder.trim();
        if (nameOrPlaceholder.startsWith("#") && exprAttrNames != null) {
            JsonNode resolved = exprAttrNames.get(nameOrPlaceholder);
            if (resolved != null) {
                return resolved.asText();
            }
        }
        return nameOrPlaceholder;
    }

    /**
     * Sets a value at a potentially nested attribute path.
     * Supports paths like "attr", "parent.child", "#alias.nested" etc.
     * For nested paths, navigates into the DynamoDB Map structure (M field).
     */
    private void setValueAtPath(ObjectNode item, String path, JsonNode value, JsonNode exprAttrNames) {
        // Resolve any # placeholders in the path segments
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            segments[i] = resolveAttributeName(segments[i].trim(), exprAttrNames);
        }

        if (segments.length == 1) {
            // Simple top-level attribute
            item.set(segments[0], value);
            return;
        }

        // Navigate to the parent of the target attribute
        ObjectNode current = item;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            JsonNode child = current.get(segment);

            if (child == null || !child.has("M")) {
                // Create nested map structure if it doesn't exist
                ObjectNode newMap = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                ObjectNode wrapper = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                wrapper.set("M", newMap);
                current.set(segment, wrapper);
                current = newMap;
            } else {
                // Navigate into existing map
                JsonNode mapContent = child.get("M");
                if (mapContent instanceof ObjectNode) {
                    current = (ObjectNode) mapContent;
                } else {
                    // Cannot navigate further, structure mismatch
                    return;
                }
            }
        }

        // Set the final attribute
        current.set(segments[segments.length - 1], value);
    }

    /**
     * Gets a value at a potentially nested attribute path.
     * Returns null if the path doesn't exist.
     */
    private JsonNode getValueAtPath(JsonNode item, String path, JsonNode exprAttrNames) {
        // Resolve any # placeholders in the path segments
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            segments[i] = resolveAttributeName(segments[i].trim(), exprAttrNames);
        }

        JsonNode current = item;
        for (int i = 0; i < segments.length; i++) {
            if (current == null) return null;

            String segment = segments[i];
            JsonNode child = current.get(segment);

            if (child == null) return null;

            if (i < segments.length - 1) {
                // Need to navigate deeper - check for Map structure
                if (child.has("M")) {
                    current = child.get("M");
                } else {
                    return null;
                }
            } else {
                // This is the final segment
                return child;
            }
        }
        return current;
    }

    /**
     * Checks if a value exists at a potentially nested attribute path.
     */
    private boolean hasValueAtPath(JsonNode item, String path, JsonNode exprAttrNames) {
        return getValueAtPath(item, path, exprAttrNames) != null;
    }

    /**
     * Removes a value at a potentially nested attribute path.
     * Supports paths like "attr", "parent.child", "#alias.nested" etc.
     * For nested paths, navigates into the DynamoDB Map structure (M field)
     * and removes the final key from its parent.
     */
    private void removeValueAtPath(ObjectNode item, String path, JsonNode exprAttrNames) {
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            segments[i] = resolveAttributeName(segments[i].trim(), exprAttrNames);
        }

        if (segments.length == 1) {
            item.remove(segments[0]);
            return;
        }

        // Navigate to the parent of the target attribute
        ObjectNode current = item;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            JsonNode child = current.get(segment);

            if (child == null || !child.has("M")) {
                // Path doesn't exist, nothing to remove
                return;
            }

            JsonNode mapContent = child.get("M");
            if (mapContent instanceof ObjectNode) {
                current = (ObjectNode) mapContent;
            } else {
                return;
            }
        }

        current.remove(segments[segments.length - 1]);
    }

    private int findNextComma(String s) {
        // Find next comma that is not inside a function call
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    private int findNextClauseKeyword(String s) {
        // Find the start of the next clause keyword (SET, REMOVE, ADD, DELETE)
        String upper = s.toUpperCase();
        int[] positions = {
            indexOfKeyword(upper, "SET "),
            indexOfKeyword(upper, "REMOVE "),
            indexOfKeyword(upper, "ADD "),
            indexOfKeyword(upper, "DELETE ")
        };
        int min = -1;
        for (int pos : positions) {
            if (pos >= 0 && (min < 0 || pos < min)) {
                min = pos;
            }
        }
        return min;
    }

    private int indexOfKeyword(String upper, String keyword) {
        // Find the next occurrence of keyword at a word boundary (start of string
        // or preceded by whitespace). Loop past non-boundary hits so attribute
        // names that contain a keyword as a substring (e.g. "oldSET" before a
        // real "SET " clause) don't shadow a later valid match.
        //
        // Go AWS SDK v2 expression.Builder emits newline-separated clauses, so
        // the boundary check accepts any whitespace (space, tab, CR, LF), not
        // just literal space.
        int from = 0;
        while (from <= upper.length()) {
            int idx = upper.indexOf(keyword, from);
            if (idx < 0) return -1;
            if (idx == 0 || Character.isWhitespace(upper.charAt(idx - 1))) return idx;
            from = idx + 1;
        }
        return -1;
    }

    // --- Filter expression evaluation ---

    private boolean matchesFilterExpression(JsonNode item, String filterExpression,
                                             JsonNode exprAttrNames, JsonNode exprAttrValues) {
        return ExpressionEvaluator.matches(filterExpression, item, exprAttrNames, exprAttrValues);
    }

    private boolean attributeValuesEqual(JsonNode a, JsonNode b) {
        return ExpressionEvaluator.attributeValuesEqual(a, b);
    }

    /**
     * Returns true if the item has the given attribute with a non-null DynamoDB value.
     * An attribute is considered null if it is the DynamoDB NULL type ({@code {"NULL": true}}).
     */
    private static boolean hasNonNullAttribute(JsonNode item, String attrName) {
        JsonNode attr = item.get(attrName);
        if (attr == null) return false;
        return !attr.has("NULL");
    }

    private int compareValues(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private String[] extractFunctionArgs(String funcCall) {
        int open = funcCall.indexOf('(');
        int close = funcCall.lastIndexOf(')');
        if (open >= 0 && close > open) {
            String inner = funcCall.substring(open + 1, close);
            String[] args = inner.split(",", 2);
            for (int i = 0; i < args.length; i++) {
                args[i] = args[i].trim();
            }
            return args;
        }
        return new String[]{funcCall};
    }

    // --- Helper methods ---

    private static String regionKey(String region, String tableName) {
        return region + "::" + tableName;
    }

    String buildItemKey(TableDefinition table, JsonNode item) {
        String pkName = table.getPartitionKeyName();
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr == null) {
            throw new AwsException("ValidationException",
                    "One of the required keys was not given a value", 400);
        }

        String pk = extractScalarValue(pkAttr);
        String skName = table.getSortKeyName();
        if (skName != null) {
            JsonNode skAttr = item.get(skName);
            if (skAttr != null) {
                return pk + "#" + extractScalarValue(skAttr);
            }
        }
        return pk;
    }

    private String buildItemKeyFromNode(JsonNode item, String pkName, String skName) {
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr == null) return "";
        String pk = extractScalarValue(pkAttr);
        if (skName != null) {
            JsonNode skAttr = item.get(skName);
            if (skAttr != null) {
                return pk + "#" + extractScalarValue(skAttr);
            }
        }
        return pk != null ? pk : "";
    }

    JsonNode buildKeyNode(TableDefinition table, JsonNode item, String pkName, String skName) {
        com.fasterxml.jackson.databind.node.ObjectNode keyNode =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr != null) {
            keyNode.set(pkName, pkAttr);
        }
        if (skName != null) {
            JsonNode skAttr = item.get(skName);
            if (skAttr != null) {
                keyNode.set(skName, skAttr);
            }
        }
        return keyNode;
    }

    private String extractScalarValue(JsonNode attrValue) {
        if (attrValue == null) return null;
        if (attrValue.has("S")) return attrValue.get("S").asText();
        if (attrValue.has("N")) return attrValue.get("N").asText();
        if (attrValue.has("B")) return attrValue.get("B").asText();
        if (attrValue.has("BOOL")) return attrValue.get("BOOL").asText();
        return attrValue.asText();
    }

    private boolean matchesAttributeValue(JsonNode attrValue, String expected) {
        if (attrValue == null || expected == null) return false;
        String actual = extractScalarValue(attrValue);
        return expected.equals(actual);
    }

    private String extractComparisonValue(JsonNode condition) {
        if (condition == null) return null;
        JsonNode attrValueList = condition.get("AttributeValueList");
        if (attrValueList != null && attrValueList.isArray() && !attrValueList.isEmpty()) {
            return extractScalarValue(attrValueList.get(0));
        }
        return null;
    }

    // NE, CONTAINS, NOT_CONTAINS, IN, NULL, NOT_NULL not yet supported
    private boolean matchesKeyCondition(JsonNode attrValue, JsonNode condition) {
        if (condition == null) return true;
        String op = condition.has("ComparisonOperator") ? condition.get("ComparisonOperator").asText() : "EQ";
        String compareValue = extractComparisonValue(condition);
        String actual = extractScalarValue(attrValue);
        if (actual == null) return false;

        return switch (op) {
            case "EQ" -> actual.equals(compareValue);
            case "BEGINS_WITH" -> actual.startsWith(compareValue);
            case "GT" -> actual.compareTo(compareValue) > 0;
            case "GE" -> actual.compareTo(compareValue) >= 0;
            case "LT" -> actual.compareTo(compareValue) < 0;
            case "LE" -> actual.compareTo(compareValue) <= 0;
            case "BETWEEN" -> {
                JsonNode list = condition.get("AttributeValueList");
                if (list.size() >= 2) {
                    String low = extractScalarValue(list.get(0));
                    String high = extractScalarValue(list.get(1));
                    yield actual.compareTo(low) >= 0 && actual.compareTo(high) <= 0;
                }
                yield false;
            }
            default -> true;
        };
    }

    private List<JsonNode> queryWithExpression(ConcurrentSkipListMap<String, JsonNode> items,
                                                String pkName, String skName,
                                                String expression,
                                                JsonNode expressionAttrValues,
                                                JsonNode exprAttrNames) {
        List<JsonNode> results = new ArrayList<>();

        // Use token-based splitting that correctly handles BETWEEN...AND and compact format
        String[] keyParts = ExpressionEvaluator.splitKeyCondition(expression);
        String pkExpression = keyParts[0];
        String skExpression = keyParts[1];

        // Extract pk attr name from expression (may use #alias)
        // Strip outer parens for PK extraction (e.g. "(#f0 = :v0)" → "#f0 = :v0")
        String pkExprStripped = pkExpression.trim();
        while (pkExprStripped.startsWith("(") && pkExprStripped.endsWith(")")) {
            pkExprStripped = pkExprStripped.substring(1, pkExprStripped.length() - 1).trim();
        }
        String pkAttrInExpr = pkExprStripped.split("\\s*=\\s*")[0].trim();
        String resolvedPkName = resolveAttributeName(pkAttrInExpr, exprAttrNames);

        // Extract pk value placeholder
        int colonIdx = pkExprStripped.indexOf(':');
        String pkPlaceholder = null;
        if (colonIdx >= 0) {
            int end = colonIdx + 1;
            while (end < pkExprStripped.length() && (Character.isLetterOrDigit(pkExprStripped.charAt(end)) || pkExprStripped.charAt(end) == '_')) {
                end++;
            }
            pkPlaceholder = pkExprStripped.substring(colonIdx, end);
        }
        String pkValue = pkPlaceholder != null && expressionAttrValues != null
                ? extractScalarValue(expressionAttrValues.get(pkPlaceholder))
                : null;

        for (JsonNode item : items.values()) {
            if (!item.has(resolvedPkName)) continue;
            if (pkValue != null && !matchesAttributeValue(item.get(resolvedPkName), pkValue)) {
                continue;
            }

            if (skExpression != null && skName != null) {
                if (!ExpressionEvaluator.matches(skExpression, item, exprAttrNames, expressionAttrValues)) {
                    continue;
                }
            }

            results.add(item);
        }

        return results;
    }

    private AwsException resourceNotFoundException(String tableName) {
        return new AwsException("ResourceNotFoundException",
                "Requested resource not found: Table: " + tableName + " not found", 400);
    }

    public record UpdateResult(JsonNode newItem, JsonNode oldItem) {}
    public record ScanResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}
    public record QueryResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}
}
