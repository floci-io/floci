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

    @Inject
    public DynamoDbService(StorageFactory storageFactory, RegionResolver regionResolver,
                           DynamoDbStreamService streamService) {
        this(storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}),
             storageFactory.create("dynamodb", "dynamodb-items.json",
                new TypeReference<Map<String, Map<String, JsonNode>>>() {}),
             regionResolver, streamService);
    }

    /** Package-private constructor for testing. */
    DynamoDbService(StorageBackend<String, TableDefinition> tableStore) {
        this(tableStore, null, new RegionResolver("us-east-1", "000000000000"), null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore, RegionResolver regionResolver) {
        this(tableStore, null, regionResolver, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver) {
        this(tableStore, itemStore, regionResolver, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService) {
        this.tableStore = tableStore;
        this.itemStore = itemStore;
        this.regionResolver = regionResolver;
        this.streamService = streamService;
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
        putItem(tableName, item, null, null, null, regionResolver.getDefaultRegion());
    }

    public void putItem(String tableName, JsonNode item, String region) {
        putItem(tableName, item, null, null, null, region);
    }

    public void putItem(String tableName, JsonNode item,
                         String conditionExpression,
                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                         String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, item);
        var tableItems = itemsByTable.computeIfAbsent(storageKey, k -> new ConcurrentSkipListMap<>());

        JsonNode existing = tableItems.get(itemKey);

        if (conditionExpression != null) {
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues);
        }

        tableItems.put(itemKey, item);
        persistItems(storageKey);
        LOG.debugv("Put item in {0}: key={1}", tableName, itemKey);

        if (streamService != null) {
            streamService.captureEvent(tableName,
                    existing == null ? "INSERT" : "MODIFY", existing, item, table, region);
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
        return deleteItem(tableName, key, null, null, null, regionResolver.getDefaultRegion());
    }

    public JsonNode deleteItem(String tableName, JsonNode key, String region) {
        return deleteItem(tableName, key, null, null, null, region);
    }

    public JsonNode deleteItem(String tableName, JsonNode key,
                                String conditionExpression,
                                JsonNode exprAttrNames, JsonNode exprAttrValues,
                                String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var items = itemsByTable.get(storageKey);
        if (items == null) return null;

        if (conditionExpression != null) {
            JsonNode existing = items.get(itemKey);
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues);
        }

        JsonNode removed = items.remove(itemKey);
        persistItems(storageKey);
        LOG.debugv("Deleted item from {0}: key={1}", tableName, itemKey);

        if (streamService != null && removed != null) {
            streamService.captureEvent(tableName, "REMOVE", removed, null, table, region);
        }

        return removed;
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues) {
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                          expressionAttrValues, returnValues, null, regionResolver.getDefaultRegion());
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String region) {
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                          expressionAttrValues, returnValues, null, region);
    }

    public UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String conditionExpression, String region) {
        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        String itemKey = buildItemKey(table, key);
        var items = itemsByTable.computeIfAbsent(storageKey, k -> new ConcurrentSkipListMap<>());

        // Get existing item or create new one from key
        JsonNode existing = items.get(itemKey);

        if (conditionExpression != null) {
            evaluateCondition(existing, conditionExpression, expressionAttrNames, expressionAttrValues);
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

        // Filter out items without GSI key if querying GSI (sparse index)
        if (indexName != null) {
            String finalPkName = pkName;
            results = results.stream().filter(item -> item.has(finalPkName)).toList();
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
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues);
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
                if (removed != null && streamService != null) {
                    streamService.captureEvent(table.getTableName(), "REMOVE", removed, null, table, region);
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
                                    JsonNode exprAttrNames, JsonNode exprAttrValues) {
        if (!matchesFilterExpression(existingItem, conditionExpression, exprAttrNames, exprAttrValues)) {
            throw new AwsException("ConditionalCheckFailedException",
                    "The conditional request failed", 400);
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
                // DELETE is for sets — skip for now
                break;
            } else {
                break;
            }
        }
    }

    private String applySetClause(ObjectNode item, String clause,
                                   JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // Parse comma-separated assignments: "attr = :val, #name = :val2"
        // Stop when we hit another clause keyword (REMOVE, ADD, DELETE) or end
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
            String valuePart;
            int commaIdx = findNextComma(rest);
            if (commaIdx >= 0) {
                valuePart = rest.substring(0, commaIdx).trim();
                rest = rest.substring(commaIdx + 1).trim();
            } else {
                // Check for next clause keyword
                int nextClause = findNextClauseKeyword(rest);
                if (nextClause >= 0) {
                    valuePart = rest.substring(0, nextClause).trim();
                    rest = rest.substring(nextClause).trim();
                } else {
                    valuePart = rest.trim();
                    rest = "";
                }
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
                    if (item.has(checkAttr)) {
                        // attrRef exists — evaluate to its current value
                        resolved = item.get(checkAttr);
                    } else if (fallbackExpr.startsWith(":") && exprAttrValues != null) {
                        resolved = exprAttrValues.get(fallbackExpr);
                    } else {
                        // fallback is itself an attribute reference
                        resolved = item.get(resolveAttributeName(fallbackExpr, exprAttrNames));
                    }
                    if (resolved != null) {
                        item.set(attrName, resolved);
                    }
                }
            } else if (valuePart.startsWith("list_append(")) {
                String[] args = extractFunctionArgs(valuePart);
                if (args.length == 2) {
                    String arg1 = args[0].trim();
                    String arg2 = args[1].trim();
                    JsonNode list1 = arg1.startsWith(":") && exprAttrValues != null
                        ? exprAttrValues.get(arg1)
                        : item.get(resolveAttributeName(arg1, exprAttrNames));
                    JsonNode list2 = arg2.startsWith(":") && exprAttrValues != null
                        ? exprAttrValues.get(arg2)
                        : item.get(resolveAttributeName(arg2, exprAttrNames));
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
            } else if (valuePart.startsWith(":") && exprAttrValues != null) {
                JsonNode value = exprAttrValues.get(valuePart);
                if (value != null) {
                    item.set(attrName, value);
                }
            } else if (!valuePart.isEmpty()) {
                // Plain attribute reference: SET a = b  or  SET a = #alias
                String refAttr = resolveAttributeName(valuePart, exprAttrNames);
                JsonNode refValue = item.get(refAttr);
                if (refValue != null) {
                    item.set(attrName, refValue);
                }
            }

            clause = rest;
        }
        return clause;
    }

    private String applyRemoveClause(ObjectNode item, String clause, JsonNode exprAttrNames) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("ADD ") || upper.startsWith("DELETE ")) {
                break;
            }

            int commaIdx = findNextComma(clause);
            String attrPart;
            if (commaIdx >= 0) {
                attrPart = clause.substring(0, commaIdx).trim();
                clause = clause.substring(commaIdx + 1).trim();
            } else {
                int nextClause = findNextClauseKeyword(clause);
                if (nextClause >= 0) {
                    attrPart = clause.substring(0, nextClause).trim();
                    clause = clause.substring(nextClause).trim();
                } else {
                    attrPart = clause.trim();
                    clause = "";
                }
            }

            String attrName = resolveAttributeName(attrPart, exprAttrNames);
            item.remove(attrName);
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

            // Advance past this assignment
            int commaIdx = findNextComma(clause);
            if (commaIdx >= 0) {
                clause = clause.substring(commaIdx + 1).trim();
            } else {
                int nextClause = findNextClauseKeyword(clause);
                clause = nextClause >= 0 ? clause.substring(nextClause).trim() : "";
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

    String resolveAttributeName(String nameOrPlaceholder, JsonNode exprAttrNames) {
        nameOrPlaceholder = trimBalancedOuterParens(nameOrPlaceholder).trim();
        if (nameOrPlaceholder.startsWith("#") && exprAttrNames != null) {
            JsonNode resolved = exprAttrNames.get(nameOrPlaceholder);
            if (resolved != null) {
                return resolved.asText();
            }
        }
        return nameOrPlaceholder;
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
        int idx = upper.indexOf(keyword);
        // Ensure it's at word boundary (start of string or preceded by space)
        if (idx > 0 && upper.charAt(idx - 1) != ' ') return -1;
        return idx;
    }

    // --- Filter expression evaluation ---

    private boolean matchesFilterExpression(JsonNode item, String filterExpression,
                                             JsonNode exprAttrNames, JsonNode exprAttrValues) {
        filterExpression = trimBalancedOuterParens(filterExpression);
        // Handle AND/OR combined expressions
        // Split on AND first (simple approach — handles most common cases)
        String[] andParts = filterExpression.split("\\s+[Aa][Nn][Dd]\\s+");
        for (String part : andParts) {
            if (!evaluateSingleCondition(item, part.trim(), exprAttrNames, exprAttrValues)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateSingleCondition(JsonNode item, String condition,
                                             JsonNode exprAttrNames, JsonNode exprAttrValues) {
        condition = trimBalancedOuterParens(condition);
        // Parse: "attrPath = :val", "attrPath <> :val", "attrPath > :val", etc.
        // Also: "attribute_exists(attrPath)", "attribute_not_exists(attrPath)",
        //        "begins_with(attrPath, :val)", "contains(attrPath, :val)"
        String condLower = condition.toLowerCase();

        if (condLower.startsWith("attribute_exists")) {
            String attr = extractFunctionArg(condition);
            String resolvedPath = resolveAttributePath(attr, exprAttrNames);
            return item != null && resolveNestedAttribute(item, resolvedPath) != null;
        }
        if (condLower.startsWith("attribute_not_exists")) {
            String attr = extractFunctionArg(condition);
            String resolvedPath = resolveAttributePath(attr, exprAttrNames);
            return item == null || resolveNestedAttribute(item, resolvedPath) == null;
        }
        if (condLower.startsWith("begins_with")) {
            String[] args = extractFunctionArgs(condition);
            if (args.length == 2) {
                String attrName = resolveAttributeName(args[0], exprAttrNames);
                String prefix = resolveExprValue(args[1], exprAttrValues);
                String actual = item != null ? extractScalarValue(item.get(attrName)) : null;
                return actual != null && prefix != null && actual.startsWith(prefix);
            }
            return false;
        }
        if (condLower.startsWith("contains")) {
            String[] args = extractFunctionArgs(condition);
            if (args.length == 2) {
                String attrName = resolveAttributeName(args[0], exprAttrNames);
                if (item == null) return false;
                JsonNode attrNode = item.get(attrName);
                if (attrNode == null) return false;
                // Resolve the raw AttributeValue node for type-aware comparisons
                JsonNode searchAttrValue = exprAttrValues != null
                        ? exprAttrValues.get(args[1].trim()) : null;
                if (searchAttrValue == null) return false;
                // List type: type-aware element membership check
                if (attrNode.has("L")) {
                    for (JsonNode element : attrNode.get("L")) {
                        if (attributeValuesEqual(element, searchAttrValue)) return true;
                    }
                    return false;
                }
                // SS (String Set): operand must be S type
                if (attrNode.has("SS")) {
                    if (!searchAttrValue.has("S")) return false;
                    String target = searchAttrValue.get("S").asText();
                    for (JsonNode element : attrNode.get("SS")) {
                        if (target.equals(element.asText())) return true;
                    }
                    return false;
                }
                // NS (Number Set): operand must be N type, compare numerically
                if (attrNode.has("NS")) {
                    if (!searchAttrValue.has("N")) return false;
                    try {
                        java.math.BigDecimal target = new java.math.BigDecimal(searchAttrValue.get("N").asText());
                        for (JsonNode element : attrNode.get("NS")) {
                            if (target.compareTo(new java.math.BigDecimal(element.asText())) == 0) return true;
                        }
                    } catch (NumberFormatException ignored) {}
                    return false;
                }
                // BS (Binary Set): operand must be B type
                if (attrNode.has("BS")) {
                    if (!searchAttrValue.has("B")) return false;
                    String target = searchAttrValue.get("B").asText();
                    for (JsonNode element : attrNode.get("BS")) {
                        if (target.equals(element.asText())) return true;
                    }
                    return false;
                }
                // String type: operand must be S type, check substring
                if (attrNode.has("S") && searchAttrValue.has("S")) {
                    return attrNode.get("S").asText().contains(searchAttrValue.get("S").asText());
                }
                return false;
            }
            return false;
        }

        // Comparison operators: =, <>, <, <=, >, >=
        String[] operators = {"<>", "<=", ">=", "=", "<", ">"};
        for (String op : operators) {
            int opIdx = condition.indexOf(op);
            if (opIdx > 0) {
                String left = condition.substring(0, opIdx).trim();
                String right = condition.substring(opIdx + op.length()).trim();

                String attrName = resolveAttributeName(left, exprAttrNames);
                String expected = resolveExprValue(right, exprAttrValues);
                String actual = item != null ? extractScalarValue(item.get(attrName)) : null;

                if (actual == null || expected == null) return false;

                return switch (op) {
                    case "=" -> actual.equals(expected);
                    case "<>" -> !actual.equals(expected);
                    case "<" -> compareValues(actual, expected) < 0;
                    case "<=" -> compareValues(actual, expected) <= 0;
                    case ">" -> compareValues(actual, expected) > 0;
                    case ">=" -> compareValues(actual, expected) >= 0;
                    default -> false;
                };
            }
        }

        return true; // Unknown condition — pass through
    }

    private String resolveExprValue(String placeholder, JsonNode exprAttrValues) {
        placeholder = trimBalancedOuterParens(placeholder).trim();
        if (placeholder.startsWith(":") && exprAttrValues != null) {
            return extractScalarValue(exprAttrValues.get(placeholder));
        }
        return placeholder;
    }

    private boolean attributeValuesEqual(JsonNode a, JsonNode b) {
        if (a == null || b == null) return a == b;
        // Scalar types: S, B, BOOL, NULL
        for (String type : new String[]{"S", "B", "BOOL", "NULL"}) {
            if (a.has(type) && b.has(type)) {
                return a.get(type).asText().equals(b.get(type).asText());
            }
            if (a.has(type) || b.has(type)) return false; // type mismatch
        }
        // Numeric comparison with normalization
        if (a.has("N") && b.has("N")) {
            try {
                return new java.math.BigDecimal(a.get("N").asText())
                        .compareTo(new java.math.BigDecimal(b.get("N").asText())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (a.has("N") || b.has("N")) return false;
        // Map type: compare all entries recursively
        if (a.has("M") && b.has("M")) {
            JsonNode aMap = a.get("M");
            JsonNode bMap = b.get("M");
            if (aMap.size() != bMap.size()) return false;
            var fields = aMap.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!bMap.has(entry.getKey())) return false;
                if (!attributeValuesEqual(entry.getValue(), bMap.get(entry.getKey()))) return false;
            }
            return true;
        }
        // List type: compare element by element
        if (a.has("L") && b.has("L")) {
            JsonNode aList = a.get("L");
            JsonNode bList = b.get("L");
            if (aList.size() != bList.size()) return false;
            for (int i = 0; i < aList.size(); i++) {
                if (!attributeValuesEqual(aList.get(i), bList.get(i))) return false;
            }
            return true;
        }
        // Different types are never equal
        return false;
    }

    private int compareValues(String a, String b) {
        // Try numeric comparison first
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static final String DOT_ESCAPE = "\uFF0E";

    private String resolveAttributePath(String path, JsonNode exprAttrNames) {
        // Resolve each segment of a dotted path, e.g. "passengerInformation.#name"
        // ExpressionAttributeNames may resolve to names containing dots (e.g. "#a" -> "foo.bar").
        // Escape those dots so resolveNestedAttribute treats them as single keys.
        String[] segments = path.split("\\.");
        StringBuilder resolved = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) resolved.append(".");
            String original = segments[i];
            String resolvedSegment = resolveAttributeName(original, exprAttrNames);
            if (original.startsWith("#") && resolvedSegment != null) {
                resolvedSegment = resolvedSegment.replace(".", DOT_ESCAPE);
            }
            resolved.append(resolvedSegment);
        }
        return resolved.toString();
    }

    private JsonNode resolveNestedAttribute(JsonNode item, String path) {
        // Navigate a dotted path through DynamoDB's {"M": {...}} structure
        String[] segments = path.split("\\.");
        JsonNode current = item;
        for (int i = 0; i < segments.length; i++) {
            if (current == null) return null;
            String segment = segments[i].replace(DOT_ESCAPE, ".");
            if (i == 0) {
                // First segment: resolve against the top-level item map
                current = current.get(segment);
            } else {
                // Subsequent segments: descend into DynamoDB Map type
                if (current.has("M")) {
                    current = current.get("M").get(segment);
                } else {
                    current = current.get(segment);
                }
            }
        }
        return current;
    }

    private String extractFunctionArg(String funcCall) {
        int open = funcCall.indexOf('(');
        int close = funcCall.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return funcCall.substring(open + 1, close).trim();
        }
        return funcCall;
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
        expression = trimBalancedOuterParens(expression);
        List<JsonNode> results = new ArrayList<>();

        // Parse simple expressions: "pk = :val" or "pk = :val AND sk begins_with :prefix"
        String[] parts = expression.split("\\s+[Aa][Nn][Dd]\\s+", 2);
        String pkExpression = trimBalancedOuterParens(parts[0].trim());
        String skExpression = parts.length > 1 ? trimBalancedOuterParens(parts[1].trim()) : null;

        // Extract pk attr name from expression (may use #alias)
        String pkAttrInExpr = pkExpression.split("\\s*=\\s*")[0].trim();
        String resolvedPkName = resolveAttributeName(pkAttrInExpr, exprAttrNames);

        // Extract pk value
        String pkPlaceholder = extractPlaceholder(pkExpression);
        String pkValue = pkPlaceholder != null && expressionAttrValues != null
                ? extractScalarValue(expressionAttrValues.get(pkPlaceholder))
                : null;

        for (JsonNode item : items.values()) {
            if (!item.has(resolvedPkName)) continue;
            if (pkValue != null && !matchesAttributeValue(item.get(resolvedPkName), pkValue)) {
                continue;
            }

            if (skExpression != null && skName != null) {
                if (!matchesSkExpression(item.get(skName), skExpression, expressionAttrValues)) {
                    continue;
                }
            }

            results.add(item);
        }

        return results;
    }

    private boolean matchesSkExpression(JsonNode skValue, String expression, JsonNode exprValues) {
        expression = trimBalancedOuterParens(expression);
        String actual = extractScalarValue(skValue);
        if (actual == null) return false;

        String exprLower = expression.toLowerCase();
        if (exprLower.contains("begins_with")) {
            String placeholder = extractPlaceholder(expression);
            String prefix = placeholder != null && exprValues != null
                    ? extractScalarValue(exprValues.get(placeholder)) : null;
            return prefix != null && actual.startsWith(prefix);
        }

        if (exprLower.contains(" between ")) {
            int betweenIdx = exprLower.indexOf(" between ");
            int andIdx = exprLower.indexOf(" and ", betweenIdx + " between ".length());
            if (andIdx < 0) return false;

            String lowerExpr = expression.substring(betweenIdx + " between ".length(), andIdx).trim();
            String upperExpr = expression.substring(andIdx + " and ".length()).trim();
            String lowerPlaceholder = lowerExpr.startsWith(":") ? lowerExpr.split("\\s+")[0] : null;
            String upperPlaceholder = upperExpr.startsWith(":") ? upperExpr.split("\\s+")[0] : null;
            String lower = lowerPlaceholder != null && exprValues != null
                    ? extractScalarValue(exprValues.get(lowerPlaceholder)) : null;
            String upper = upperPlaceholder != null && exprValues != null
                    ? extractScalarValue(exprValues.get(upperPlaceholder)) : null;
            if (lower == null || upper == null) return false;
            return compareValues(actual, lower) >= 0 && compareValues(actual, upper) <= 0;
        }

        // Detect comparison operator
        String[] operators = {"<>", "<=", ">=", "=", "<", ">"};
        for (String op : operators) {
            int opIdx = expression.indexOf(op);
            if (opIdx > 0) {
                String right = expression.substring(opIdx + op.length()).trim();
                String placeholder = right.startsWith(":") ? right.split("\\s+")[0] : null;
                String expected = placeholder != null && exprValues != null
                        ? extractScalarValue(exprValues.get(placeholder)) : null;
                if (expected == null) return false;
                return switch (op) {
                    case "=" -> actual.equals(expected);
                    case "<>" -> !actual.equals(expected);
                    case ">=" -> actual.compareTo(expected) >= 0;
                    case "<=" -> actual.compareTo(expected) <= 0;
                    case ">" -> actual.compareTo(expected) > 0;
                    case "<" -> actual.compareTo(expected) < 0;
                    default -> true;
                };
            }
        }
        return true;
    }

    private String extractPlaceholder(String expression) {
        // Find :placeholder in expression
        int idx = expression.indexOf(':');
        if (idx < 0) return null;
        int end = idx + 1;
        while (end < expression.length() && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) {
            end++;
        }
        return expression.substring(idx, end);
    }

    private String trimBalancedOuterParens(String expression) {
        if (expression == null) return null;
        String trimmed = expression.trim();
        while (trimmed.length() >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")
                && hasBalancedOuterParens(trimmed)) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private boolean hasBalancedOuterParens(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < expression.length() - 1) {
                    return false;
                }
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private AwsException resourceNotFoundException(String tableName) {
        return new AwsException("ResourceNotFoundException",
                "Requested resource not found: Table: " + tableName + " not found", 400);
    }

    public record UpdateResult(JsonNode newItem, JsonNode oldItem) {}
    public record ScanResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}
    public record QueryResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}
}
