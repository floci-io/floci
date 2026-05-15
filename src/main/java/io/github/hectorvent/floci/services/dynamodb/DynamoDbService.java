package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ExportDescription;
import io.github.hectorvent.floci.services.dynamodb.model.ExportSummary;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class DynamoDbService {

    private static final Logger LOG = Logger.getLogger(DynamoDbService.class);

    private final StorageBackend<String, TableDefinition> tableStore;
    private final StorageBackend<String, Map<String, JsonNode>> itemStore;
    private final StorageBackend<String, ExportDescription> exportStore;
    // Items stored per table: storageKey -> Map<itemKey, item>
    // itemKey is "pk" or "pk#sk" depending on table schema
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<String, JsonNode>> itemsByTable = new ConcurrentHashMap<>();
    // Per-item locks: storageKey -> itemKey -> ReentrantLock. Locks are created lazily
    // on first access and cleared with the table (see deleteTable); transactWriteItems
    // relies on ReentrantLock's re-entrancy so the inner put/update/delete calls do
    // not deadlock after the outer transaction already took each participant's lock.
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ReentrantLock>> itemLocks = new ConcurrentHashMap<>();
    // ClientRequestToken idempotency for TransactWriteItems. AWS retains tokens for
    // ~10 minutes; floci uses the same window. The cache entry stores a hash of the
    // request body so a replay with the same token but different parameters can be
    // rejected with IdempotentParameterMismatchException.
    private final ConcurrentHashMap<String, IdempotencyEntry> txIdempotency = new ConcurrentHashMap<>();
    private static final long TX_IDEMPOTENCY_TTL_NANOS = java.time.Duration.ofMinutes(10).toNanos();

    private record IdempotencyEntry(String requestHash, long insertedAtNanos) {}
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private DynamoDbStreamService streamService;
    private KinesisStreamingForwarder kinesisForwarder;
    private S3Service s3Service;

    @Inject
    public DynamoDbService(StorageFactory storageFactory, RegionResolver regionResolver,
                           DynamoDbStreamService streamService,
                           KinesisStreamingForwarder kinesisForwarder,
                           S3Service s3Service,
                           ObjectMapper objectMapper) {
        this(storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}),
             storageFactory.create("dynamodb", "dynamodb-items.json",
                new TypeReference<Map<String, Map<String, JsonNode>>>() {}),
             storageFactory.create("dynamodb", "dynamodb-exports.json",
                new TypeReference<Map<String, ExportDescription>>() {}),
             regionResolver, streamService, kinesisForwarder, s3Service, objectMapper);
    }

    /** Package-private constructor for testing. */
    DynamoDbService(StorageBackend<String, TableDefinition> tableStore) {
        this(tableStore, null, null, new RegionResolver("us-east-1", "000000000000"), null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore, RegionResolver regionResolver) {
        this(tableStore, null, null, regionResolver, null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver) {
        this(tableStore, itemStore, null, regionResolver, null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder) {
        this(tableStore, itemStore, null, regionResolver, streamService, kinesisForwarder, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    StorageBackend<String, ExportDescription> exportStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder,
                    S3Service s3Service,
                    ObjectMapper objectMapper) {
        this.tableStore = tableStore;
        this.itemStore = itemStore;
        this.exportStore = exportStore;
        this.regionResolver = regionResolver;
        this.streamService = streamService;
        this.kinesisForwarder = kinesisForwarder;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
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
        // Enforce at the service boundary: CreateTable persists its input as the
        // canonical table name and derives TableArn from it. An ARN-form input
        // would produce ARN-on-ARN TableArn values. Handler-layer rejection alone
        // would leave non-HTTP callers able to bypass the guard.
        DynamoDbTableNames.requireShortName(tableName);
        String storageKey = regionKey(region, tableName);
        if (tableStore.get(storageKey).isPresent()) {
            throw new AwsException("ResourceInUseException",
                    "Table already exists: " + tableName, 400);
        }

        if (keySchema == null || keySchema.isEmpty()) {
            throw new AwsException("ValidationException",
                    "No defined attribute for index key schema: hash or range", 400);
        }

        // Validate KeyType values
        for (int i = 0; i < keySchema.size(); i++) {
            String keyType = keySchema.get(i).getKeyType();
            if (!"HASH".equals(keyType) && !"RANGE".equals(keyType)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + keyType
                        + "' at 'keySchema." + (i + 1) + ".member.keyType' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [HASH, RANGE]", 400);
            }
        }

        // Validate keySchema size <= 2
        if (keySchema.size() > 2) {
            String repr = "[" + keySchema.stream()
                    .map(k -> "KeySchemaElement(attributeName=" + k.getAttributeName() + ", keyType=" + k.getKeyType() + ")")
                    .collect(java.util.stream.Collectors.joining(", ")) + "]";
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + repr + "' at 'keySchema' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 2", 400);
        }

        // Validate no duplicate attribute names in keySchema
        Set<String> keySchemaAttrNames = new HashSet<>();
        for (KeySchemaElement k : keySchema) {
            if (!keySchemaAttrNames.add(k.getAttributeName())) {
                throw new AwsException("ValidationException",
                        "Invalid KeySchema: Some index key attribute have no definition", 400);
            }
        }

        // Validate AttributeType values
        if (attributeDefinitions != null) {
            for (int i = 0; i < attributeDefinitions.size(); i++) {
                String attrType = attributeDefinitions.get(i).getAttributeType();
                if (!"B".equals(attrType) && !"N".equals(attrType) && !"S".equals(attrType)) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Value '" + attrType
                            + "' at 'attributeDefinitions." + (i + 1) + ".member.attributeType' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [B, N, S]", 400);
                }
            }
        }

        Set<String> referencedAttrs = new HashSet<>();
        keySchema.forEach(k -> referencedAttrs.add(k.getAttributeName()));
        if (gsis != null) {
            gsis.forEach(g -> g.getKeySchema().forEach(k -> referencedAttrs.add(k.getAttributeName())));
        }
        if (lsis != null) {
            lsis.forEach(l -> l.getKeySchema().forEach(k -> referencedAttrs.add(k.getAttributeName())));
        }
        if (attributeDefinitions != null) {
            for (AttributeDefinition ad : attributeDefinitions) {
                if (!referencedAttrs.contains(ad.getAttributeName())) {
                    throw new AwsException("ValidationException",
                            "Invalid attribute: " + ad.getAttributeName()
                            + " is defined in AttributeDefinitions but is not used in any key schema", 400);
                }
            }
        }

        boolean tableHasSortKey = keySchema.stream().anyMatch(k -> "RANGE".equals(k.getKeyType()));
        if (lsis != null && !lsis.isEmpty() && !tableHasSortKey) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Table KeySchema does not have a range key, "
                    + "which is required when specifying a LocalSecondaryIndex", 400);
        }

        // Validate no duplicate index names
        Set<String> indexNames = new HashSet<>();
        if (gsis != null) {
            for (GlobalSecondaryIndex gsi : gsis) {
                if (!indexNames.add(gsi.getIndexName())) {
                    throw new AwsException("ValidationException",
                            "One or more parameter values were invalid: Duplicate index name: " + gsi.getIndexName(), 400);
                }
            }
        }
        if (lsis != null) {
            for (LocalSecondaryIndex lsi : lsis) {
                if (!indexNames.add(lsi.getIndexName())) {
                    throw new AwsException("ValidationException",
                            "One or more parameter values were invalid: Duplicate index name: " + lsi.getIndexName(), 400);
                }
            }
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
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        // Update dynamic counts
        var items = itemsByTable.get(storageKey);
        if (items != null) {
            table.setItemCount(items.size());
        }
        return table;
    }

    public void persistTable(String tableName, TableDefinition table, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        tableStore.put(regionKey(region, canonicalTableName), table);
    }

    public void deleteTable(String tableName) {
        deleteTable(tableName, regionResolver.getDefaultRegion());
    }

    public void deleteTable(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        if (tableStore.get(storageKey).isEmpty()) {
            throw resourceNotFoundException(canonicalTableName);
        }
        tableStore.delete(storageKey);
        itemsByTable.remove(storageKey);
        itemLocks.remove(storageKey);
        if (itemStore != null) {
            itemStore.delete(storageKey);
        }
        if (streamService != null) {
            streamService.deleteStream(canonicalTableName, region);
        }
        LOG.infov("Deleted table: {0}", canonicalTableName);
    }

    public List<String> listTables() {
        return listTables(regionResolver.getDefaultRegion());
    }

    public List<String> listTables(String region) {
        String prefix = region + "::";
        return tableStore.scan(k -> k.startsWith(prefix)).stream()
                .map(TableDefinition::getTableName)
                .sorted()
                .toList();
    }

    public ListTablesResult listTables(String region, Integer limit, String exclusiveStartTableName) {
        List<String> all = listTables(region);

        int startIdx = 0;
        if (exclusiveStartTableName != null && !exclusiveStartTableName.isBlank()) {
            int pos = Collections.binarySearch(all, exclusiveStartTableName);
            if (pos >= 0) {
                startIdx = pos + 1;
            } else {
                startIdx = -(pos + 1);
            }
        }

        List<String> page = all.subList(startIdx, all.size());
        String lastEvaluatedTableName = null;
        if (limit != null && limit > 0 && page.size() > limit) {
            lastEvaluatedTableName = page.get(limit - 1);
            page = page.subList(0, limit);
        }
        return new ListTablesResult(List.copyOf(page), lastEvaluatedTableName);
    }

    public record ListTablesResult(List<String> tableNames, String lastEvaluatedTableName) {}

    public void putItem(String tableName, JsonNode item) {
        putItem(tableName, item, null, null, null, regionResolver.getDefaultRegion(), "NONE");
    }

    public void putItem(String tableName, JsonNode item, String region) {
        putItem(tableName, item, null, null, null, region, "NONE");
    }

    public void putItem(String tableName, JsonNode item,
                         String conditionExpression,
                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                         String region, String returnValuesOnConditionCheckFailure) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        // Validate and normalize all number attributes before storage
        final JsonNode normalizedItem = DynamoDbNumberUtils.normalizeNumbersInItem(item);
        DynamoDbItemSize.validateSize(normalizedItem);
        String itemKey = buildItemKey(table, normalizedItem);

        withItemLock(storageKey, itemKey, () -> {
            var tableItems = itemsByTable.computeIfAbsent(storageKey, k -> new ConcurrentSkipListMap<>());

            JsonNode existing = tableItems.get(itemKey);

            if (conditionExpression != null) {
                evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            }

            tableItems.put(itemKey, normalizedItem);
            persistItems(storageKey);
            LOG.debugv("Put item in {0}: key={1}", canonicalTableName, itemKey);
            LOG.tracev("Put item in {0}: key={1} item={2}", canonicalTableName, itemKey, item);

            String eventName = existing == null ? "INSERT" : "MODIFY";
            if (streamService != null) {
                streamService.captureEvent(canonicalTableName, eventName, existing, item, table, region);
            }
            if (kinesisForwarder != null) {
                kinesisForwarder.forward(eventName, existing, item, table, region);
            }
        });
    }

    public JsonNode getItem(String tableName, JsonNode key) {
        return getItem(tableName, key, regionResolver.getDefaultRegion());
    }

    public JsonNode getItem(String tableName, JsonNode key, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        String itemKey = buildItemKey(table, key, true);
        var items = itemsByTable.get(storageKey);
        if (items == null) {
            LOG.tracev("Got item from {0}: key={1} item=<not found>", canonicalTableName, itemKey);
            return null;
        }
        JsonNode item = items.get(itemKey);
        if (item != null && isExpired(item, table)) {
            LOG.tracev("Got item from {0}: key={1} item=<expired>", canonicalTableName, itemKey);
            return null;
        }
        LOG.tracev("Got item from {0}: key={1} item={2}", canonicalTableName, itemKey, item);
        return item;
    }

    public JsonNode deleteItem(String tableName, JsonNode key) {
        return deleteItem(tableName, key, null, null, null, regionResolver.getDefaultRegion(), "NONE");
    }

    public JsonNode deleteItem(String tableName, JsonNode key, String region) {
        return deleteItem(tableName, key, null, null, null, region, "NONE");
    }

    public JsonNode deleteItem(String tableName, JsonNode key,
                                String conditionExpression,
                                JsonNode exprAttrNames, JsonNode exprAttrValues,
                                String region, String returnValuesOnConditionCheckFailure) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        String itemKey = buildItemKey(table, key, true);

        return withItemLock(storageKey, itemKey, () -> {
            var items = itemsByTable.get(storageKey);
            if (items == null) return null;

            if (conditionExpression != null) {
                JsonNode existing = items.get(itemKey);
                evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            }

            JsonNode removed = items.remove(itemKey);
            persistItems(storageKey);
            LOG.debugv("Deleted item from {0}: key={1}", canonicalTableName, itemKey);
            LOG.tracev("Deleted item from {0}: key={1} removed={2}", canonicalTableName, itemKey, removed);

            if (removed != null) {
                if (streamService != null) {
                    streamService.captureEvent(canonicalTableName, "REMOVE", removed, null, table, region);
                }
                if (kinesisForwarder != null) {
                    kinesisForwarder.forward("REMOVE", removed, null, table, region);
                }
            }

            return removed;
        });
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
                                    String returnValues, String conditionExpression, String region,
                                    String returnValuesOnConditionCheckFailure) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        String itemKey = buildItemKey(table, key, true);

        return withItemLock(storageKey, itemKey, () -> {
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
                        case "DELETE" -> {
                            if (value == null) {
                                item.remove(attrName);
                            } else {
                                // Remove elements from a set
                                JsonNode curAttr = item.get(attrName);
                                if (curAttr != null) {
                                    for (String setType : new String[]{"SS", "NS", "BS"}) {
                                        if (curAttr.has(setType) && value.has(setType)) {
                                            Set<String> removeSet = new HashSet<>();
                                            for (JsonNode v : value.get(setType)) removeSet.add(v.asText());
                                            com.fasterxml.jackson.databind.node.ArrayNode newArr = objectMapper.createArrayNode();
                                            for (JsonNode v : curAttr.get(setType)) {
                                                if (!removeSet.contains(v.asText())) newArr.add(v);
                                            }
                                            if (newArr.isEmpty()) {
                                                item.remove(attrName);
                                            } else {
                                                ((com.fasterxml.jackson.databind.node.ObjectNode) curAttr).set(setType, newArr);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        case "ADD" -> {
                            if (value != null) {
                                JsonNode curAttr = item.get(attrName);
                                if (value.has("N")) {
                                    java.math.BigDecimal delta = new java.math.BigDecimal(value.get("N").asText());
                                    java.math.BigDecimal current = curAttr != null && curAttr.has("N")
                                            ? new java.math.BigDecimal(curAttr.get("N").asText()) : java.math.BigDecimal.ZERO;
                                    com.fasterxml.jackson.databind.node.ObjectNode numNode = objectMapper.createObjectNode();
                                    numNode.put("N", current.add(delta).stripTrailingZeros().toPlainString());
                                    item.set(attrName, numNode);
                                } else {
                                    // Add elements to a set
                                    for (String setType : new String[]{"SS", "NS", "BS"}) {
                                        if (value.has(setType)) {
                                            if (curAttr == null || !curAttr.has(setType)) {
                                                item.set(attrName, value);
                                            } else {
                                                Set<String> existingSet = new LinkedHashSet<>();
                                                for (JsonNode v : curAttr.get(setType)) existingSet.add(v.asText());
                                                for (JsonNode v : value.get(setType)) existingSet.add(v.asText());
                                                com.fasterxml.jackson.databind.node.ArrayNode newArr = objectMapper.createArrayNode();
                                                for (String s : existingSet) newArr.add(s);
                                                ((com.fasterxml.jackson.databind.node.ObjectNode) curAttr).set(setType, newArr);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Reject any attempt to modify a key attribute
            String pkName = table.getPartitionKeyName();
            JsonNode origPk = key.get(pkName);
            JsonNode newPk = item.get(pkName);
            if (origPk != null && newPk != null && !origPk.equals(newPk)) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Cannot update attribute " + pkName
                        + ". This attribute is part of the key", 400);
            }
            String skName = table.getSortKeyName();
            if (skName != null) {
                JsonNode origSk = key.get(skName);
                JsonNode newSk = item.get(skName);
                if (origSk != null && newSk != null && !origSk.equals(newSk)) {
                    throw new AwsException("ValidationException",
                            "One or more parameter values were invalid: Cannot update attribute " + skName
                            + ". This attribute is part of the key", 400);
                }
            }

            items.put(itemKey, item);
            persistItems(storageKey);
            LOG.tracev("Updated item in {0}: key={1} updateExpression={2} item={3}",
                    canonicalTableName, itemKey, updateExpression, item);

            if (streamService != null) {
                streamService.captureEvent(canonicalTableName, "MODIFY", existing, item, table, region);
            }
            if (kinesisForwarder != null) {
                kinesisForwarder.forward("MODIFY", existing, item, table, region);
            }

            return new UpdateResult(item, existing);
        });
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
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

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
                JsonNode aAttr = a.get(finalSkName);
                JsonNode bAttr = b.get(finalSkName);
                if (aAttr == null && bAttr == null) return 0;
                if (aAttr == null) return -1;
                if (bAttr == null) return 1;
                return ExpressionEvaluator.compareAttributeValues(aAttr, bAttr);
            });
            if (Boolean.FALSE.equals(scanIndexForward)) {
                Collections.reverse(results);
            }
        }

        // Apply ExclusiveStartKey offset
        if (exclusiveStartKey != null) {
            String tablePkName = table.getPartitionKeyName();
            String tableSkName = table.getSortKeyName();
            boolean hasTableKeys = exclusiveStartKey.has(tablePkName);

            String startItemKey = hasTableKeys
                    ? buildItemKeyFromNode(exclusiveStartKey, tablePkName, tableSkName)
                    : buildItemKeyFromNode(exclusiveStartKey, pkName, skName);

            int startIdx = -1;
            for (int i = 0; i < results.size(); i++) {
                String thisKey = hasTableKeys
                        ? buildItemKeyFromNode(results.get(i), tablePkName, tableSkName)
                        : buildItemKeyFromNode(results.get(i), pkName, skName);
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

        // Apply Limit (stops at N items)
        if (limit != null && limit > 0 && evaluatedItems.size() > limit) {
            JsonNode lastItem = evaluatedItems.get(limit - 1);
            lastEvaluatedKey = buildKeyNode(table, lastItem, pkName, skName, indexName != null);
            evaluatedItems = new ArrayList<>(evaluatedItems.subList(0, limit));
        }

        // Apply 1MB response size limit (DynamoDB stops reading when scanned data exceeds 1MB)
        if (lastEvaluatedKey == null) {
            final int MAX_RESPONSE_BYTES = 1024 * 1024;
            int accSize = 0;
            for (int i = 0; i < evaluatedItems.size(); i++) {
                int sz = DynamoDbItemSize.calculateItemSize(evaluatedItems.get(i));
                if (accSize > 0 && accSize + sz > MAX_RESPONSE_BYTES) {
                    lastEvaluatedKey = buildKeyNode(table, evaluatedItems.get(i - 1), pkName, skName, indexName != null);
                    evaluatedItems = new ArrayList<>(evaluatedItems.subList(0, i));
                    break;
                }
                accSize += sz;
            }
        }

        int scannedCount = evaluatedItems.size();

        if (filterExpression != null) {
            evaluatedItems = evaluatedItems.stream()
                    .filter(item -> matchesFilterExpression(item, filterExpression,
                            exprAttrNames, expressionAttrValues))
                    .toList();
        }

        LOG.tracev("Query on {0}: returned={1} scanned={2}",
                canonicalTableName, evaluatedItems.size(), scannedCount);
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
        DynamoDbReservedWords.check(filterExpression, "FilterExpression");
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

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

        // Apply 1MB response size limit
        if (lastEvaluatedKey == null) {
            final int MAX_RESPONSE_BYTES = 1024 * 1024;
            int accSize = 0;
            for (int i = 0; i < results.size(); i++) {
                int sz = DynamoDbItemSize.calculateItemSize(results.get(i));
                if (accSize > 0 && accSize + sz > MAX_RESPONSE_BYTES) {
                    lastEvaluatedKey = buildKeyNode(table, results.get(i - 1), pkName, skName);
                    results = new ArrayList<>(results.subList(0, i));
                    break;
                }
                accSize += sz;
            }
        }

        LOG.tracev("Scan on {0}: returned={1} scanned={2}",
                canonicalTableName, results.size(), totalScanned);
        return new ScanResult(results, totalScanned, lastEvaluatedKey);
    }

    public boolean matchesScanFilterPublic(JsonNode item, JsonNode scanFilter) {
        return matchesScanFilter(item, scanFilter);
    }

    public boolean matchesKeyConditionPublic(JsonNode attrValue, JsonNode condition) {
        return matchesKeyCondition(attrValue, condition);
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
            String tableName = canonicalTableName(region, entry.getKey());
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
            String tableNameOrArn = entry.getKey();
            String tableName = canonicalTableName(region, tableNameOrArn);
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
            responses.put(tableNameOrArn, tableItems);
        }
        return new BatchGetResult(responses, Map.of());
    }

    // --- Transact Operations ---

    /**
     * Backward-compatible overload for callers that do not pass a ClientRequestToken.
     * The 4-arg variant is what {@link DynamoDbJsonHandler#handleTransactWriteItems}
     * uses so the caller's ClientRequestToken is honoured.
     */
    public void transactWriteItems(List<JsonNode> transactItems, String region) {
        transactWriteItems(transactItems, region, null, null);
    }

    public void transactWriteItems(List<JsonNode> transactItems, String region,
                                    String clientRequestToken, JsonNode rawRequest) {
        // Idempotency check via ClientRequestToken — AWS contract:
        //   * Same token + identical request body  → no-op success (silently dedupe).
        //   * Same token + different request body  → IdempotentParameterMismatchException.
        //   * No token, or expired token           → proceed normally.
        if (clientRequestToken != null && !clientRequestToken.isEmpty() && rawRequest != null) {
            String cacheKey = region + "::" + clientRequestToken;
            String requestHash = sha256(rawRequest.toString());
            long nowNanos = System.nanoTime();

            IdempotencyEntry existing = txIdempotency.get(cacheKey);
            if (existing != null && nowNanos - existing.insertedAtNanos() <= TX_IDEMPOTENCY_TTL_NANOS) {
                if (existing.requestHash().equals(requestHash)) {
                    LOG.debugv("transactWriteItems: idempotent replay for token={0}", clientRequestToken);
                    return;
                }
                throw new AwsException("IdempotentParameterMismatchException",
                        "Request parameters do not match those of an in-flight or recent transaction using the same ClientRequestToken",
                        400);
            }

            // Register the token. compute() is used so a concurrent replay with the same body
            // collapses onto the same entry without double-applying writes.
            IdempotencyEntry registered = txIdempotency.compute(cacheKey, (k, v) -> {
                if (v != null && nowNanos - v.insertedAtNanos() <= TX_IDEMPOTENCY_TTL_NANOS) {
                    return v;
                }
                return new IdempotencyEntry(requestHash, nowNanos);
            });
            if (!registered.requestHash().equals(requestHash)) {
                throw new AwsException("IdempotentParameterMismatchException",
                        "Request parameters do not match those of an in-flight or recent transaction using the same ClientRequestToken",
                        400);
            }
            if (registered.insertedAtNanos() != nowNanos) {
                // Lost the race to a concurrent identical request — treat as a replay.
                LOG.debugv("transactWriteItems: concurrent identical replay for token={0}", clientRequestToken);
                return;
            }

            // Best-effort eviction of stale entries.
            txIdempotency.entrySet().removeIf(e -> nowNanos - e.getValue().insertedAtNanos() > TX_IDEMPOTENCY_TTL_NANOS);
        }


        // Acquire every participant's item lock in a deterministic (storageKey, itemKey)
        // order before evaluating conditions or applying writes. Total-ordered acquisition
        // prevents deadlock across concurrent transactions; ReentrantLock lets the inner
        // putItem/updateItem/deleteItem calls re-enter the same lock for free.
        //
        // Ordering uses a tuple comparator — not a delimited string — so user-supplied
        // bytes in an item's PK/SK value cannot collide two distinct participants
        // into the same ordering key.
        TreeMap<TransactParticipant, ReentrantLock> toAcquire = new TreeMap<>(PARTICIPANT_ORDER);
        for (JsonNode transactItem : transactItems) {
            TransactParticipant p = resolveParticipant(transactItem, region);
            if (p == null) continue;
            toAcquire.putIfAbsent(p, lockFor(p.storageKey, p.itemKey));
        }

        List<ReentrantLock> acquired = new ArrayList<>(toAcquire.size());
        try {
            for (ReentrantLock lock : toAcquire.values()) {
                lock.lock();
                acquired.add(lock);
            }

            // First pass: evaluate all conditions and collect failures.
            List<TransactionCanceledException.CancellationReason> cancellationReasons = new ArrayList<>();
            boolean hasFailed = false;
            for (JsonNode transactItem : transactItems) {
                TransactionCanceledException.CancellationReason failReason = evaluateTransactCondition(transactItem, region);
                if (failReason != null) {
                    hasFailed = true;
                    cancellationReasons.add(failReason);
                } else {
                    cancellationReasons.add(new TransactionCanceledException.CancellationReason("", null));
                }
            }

            if (hasFailed) {
                throw new TransactionCanceledException(cancellationReasons);
            }

            // Second pass: apply all writes. Inner methods re-acquire their own locks,
            // which is a no-op thanks to ReentrantLock.
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
                    //there is no ConditionExpression, so setting returnValuesOnConditionCheckFailure = "NONE"
                    updateItem(tableName, key, null, updateExpression, exprAttrNames, exprAttrValues,
                               "NONE", null, region, "NONE");
                }
                // ConditionCheck-only items are handled in the first pass only
            }
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    private record TransactParticipant(String storageKey, String itemKey) {}

    private static final Comparator<TransactParticipant> PARTICIPANT_ORDER =
            Comparator.comparing(TransactParticipant::storageKey)
                    .thenComparing(TransactParticipant::itemKey);

    private TransactParticipant resolveParticipant(JsonNode transactItem, String region) {
        JsonNode target;
        boolean isPut = false;
        if (transactItem.has("Put")) {
            target = transactItem.get("Put");
            isPut = true;
        } else if (transactItem.has("Delete")) {
            target = transactItem.get("Delete");
        } else if (transactItem.has("Update")) {
            target = transactItem.get("Update");
        } else if (transactItem.has("ConditionCheck")) {
            target = transactItem.get("ConditionCheck");
        } else {
            return null;
        }

        String tableName = canonicalTableName(region, target.path("TableName").asText());
        JsonNode keyOrItem = isPut ? target.get("Item") : target.get("Key");
        if (keyOrItem == null) {
            return null;
        }

        String storageKey = regionKey(region, tableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));
        String itemKey = buildItemKey(table, keyOrItem);
        return new TransactParticipant(storageKey, itemKey);
    }

    private TransactionCanceledException.CancellationReason evaluateTransactCondition(JsonNode transactItem, String region) {
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
        String canonicalTableName = canonicalTableName(region, tableName);
        JsonNode key = transactItem.has("Put") ? target.get("Item") : target.get("Key");
        JsonNode exprAttrNames = target.has("ExpressionAttributeNames") ? target.get("ExpressionAttributeNames") : null;
        JsonNode exprAttrValues = target.has("ExpressionAttributeValues") ? target.get("ExpressionAttributeValues") : null;

        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        String itemKey = buildItemKey(table, key);
        var tableItems = itemsByTable.get(storageKey);
        JsonNode existing = tableItems != null ? tableItems.get(itemKey) : null;

        try {
            evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            return null;
        } catch (ConditionalCheckFailedException e) {
            return new TransactionCanceledException.CancellationReason("ConditionalCheckFailed", e.getItem());
        } catch (AwsException e) {
            return new TransactionCanceledException.CancellationReason(e.getMessage(), null);
        }
    }

    public List<JsonNode> transactGetItems(List<JsonNode> transactItems, String region) {
        List<JsonNode> results = new ArrayList<>();
        List<TransactionCanceledException.CancellationReason> cancelReasons = new ArrayList<>();
        boolean hasCancelled = false;

        for (JsonNode transactItem : transactItems) {
            if (transactItem.has("Get")) {
                JsonNode get = transactItem.get("Get");
                String tableName = get.path("TableName").asText();
                JsonNode key = get.get("Key");
                try {
                    results.add(getItem(tableName, key, region));
                    cancelReasons.add(new TransactionCanceledException.CancellationReason("", null));
                } catch (AwsException e) {
                    if ("ValidationException".equals(e.getErrorCode())) {
                        hasCancelled = true;
                        results.add(null);
                        cancelReasons.add(new TransactionCanceledException.CancellationReason("ValidationError", null));
                    } else {
                        throw e;
                    }
                }
            } else {
                results.add(null);
                cancelReasons.add(new TransactionCanceledException.CancellationReason("", null));
            }
        }

        if (hasCancelled) {
            throw new TransactionCanceledException(cancelReasons);
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
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));

        if (readCapacity != null && readCapacity <= 0) {
            throw new AwsException("ValidationException",
                    "The parameter 'ProvisionedThroughput.ReadCapacityUnits' must be greater than 0", 400);
        }
        if (writeCapacity != null && writeCapacity <= 0) {
            throw new AwsException("ValidationException",
                    "The parameter 'ProvisionedThroughput.WriteCapacityUnits' must be greater than 0", 400);
        }
        if (readCapacity != null && writeCapacity != null
                && "PROVISIONED".equals(table.getBillingMode())
                && readCapacity.equals(table.getProvisionedThroughput().getReadCapacityUnits())
                && writeCapacity.equals(table.getProvisionedThroughput().getWriteCapacityUnits())) {
            throw new AwsException("ValidationException",
                    "The provisioned throughput for the table will not change. "
                    + "The requested value equals the current value.", 400);
        }

        for (GlobalSecondaryIndex newGsi : gsiCreates) {
            if (table.findGsi(newGsi.getIndexName()).isPresent()) {
                throw new AwsException("ValidationException",
                        "GSI " + newGsi.getIndexName() + " already exists", 400);
            }
        }

        Set<String> knownAttrs = table.getAttributeDefinitions().stream()
                .map(AttributeDefinition::getAttributeName)
                .collect(java.util.stream.Collectors.toSet());
        if (newAttrDefs != null) newAttrDefs.forEach(ad -> knownAttrs.add(ad.getAttributeName()));
        for (GlobalSecondaryIndex newGsi : gsiCreates) {
            for (KeySchemaElement k : newGsi.getKeySchema()) {
                if (!knownAttrs.contains(k.getAttributeName())) {
                    throw new AwsException("ValidationException",
                            "Attribute: " + k.getAttributeName() + " is not defined in AttributeDefinitions", 400);
                }
            }
        }

        for (String gsiName : gsiDeletes) {
            if (table.findGsi(gsiName).isEmpty()) {
                throw new AwsException("ResourceNotFoundException",
                        "Global secondary index " + gsiName + " does not exist on the table", 400);
            }
        }

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
        LOG.infov("Updated table: {0} in region {1}", canonicalTableName, region);
        return table;
    }

    // --- TTL ---

    public void updateTimeToLive(String tableName, String ttlAttributeName, boolean enabled, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        table.setTtlAttributeName(ttlAttributeName);
        table.setTtlEnabled(enabled);
        tableStore.put(storageKey, table);
        LOG.infov("Updated TTL for table {0}: enabled={1}, attr={2}", canonicalTableName, enabled, ttlAttributeName);
    }

    public TableDefinition updateContinuousBackups(String tableName, boolean enabled,
                                                   Integer recoveryPeriodInDays, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        table.setPointInTimeRecoveryEnabled(enabled);
        table.setPointInTimeRecoveryRecoveryPeriodInDays(
                recoveryPeriodInDays != null ? recoveryPeriodInDays : table.getPointInTimeRecoveryRecoveryPeriodInDays());
        tableStore.put(storageKey, table);
        LOG.infov("Updated PITR for table {0}: enabled={1}, recoveryPeriodInDays={2}",
                canonicalTableName, enabled, table.getPointInTimeRecoveryRecoveryPeriodInDays());
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
        Map<String, TableDefinition> allTables;
        if (tableStore instanceof AccountAwareStorageBackend<TableDefinition> aware) {
            allTables = aware.scanAllAccountsAsMap();
        } else {
            allTables = new HashMap<>();
            tableStore.keys().forEach(k -> tableStore.get(k).ifPresent(v -> allTables.put(k, v)));
        }
        for (Map.Entry<String, TableDefinition> entry : allTables.entrySet()) {
            String storageKey = entry.getKey();
            TableDefinition table = entry.getValue();
            if (!table.isTtlEnabled() || table.getTtlAttributeName() == null) {
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

    private String canonicalTableName(String region, String tableName) {
        return DynamoDbTableNames.resolveWithRegion(tableName, region).name();
    }

    // --- Condition expression evaluation ---

    private void evaluateCondition(JsonNode existingItem, String conditionExpression,
                                    JsonNode exprAttrNames, JsonNode exprAttrValues, String returnValuesOnConditionCheckFailure) {
        DynamoDbReservedWords.check(conditionExpression, "ConditionExpression");
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
        if (expression.isBlank()) {
            throw new AwsException("ValidationException",
                    "Invalid UpdateExpression: The expression can not be empty;", 400);
        }
        DynamoDbReservedWords.check(expression, "UpdateExpression");
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
                // Unknown keyword — syntax error
                String[] parts = remaining.split("\\s+", 3);
                String token = parts[0];
                String near = parts.length >= 2
                        ? (token + " " + parts[1]).substring(0, Math.min(token.length() + 1 + parts[1].length(), 20))
                        : token;
                throw new AwsException("ValidationException",
                        "Invalid UpdateExpression: Syntax error; token: \"" + token + "\", near: \"" + near + "\"", 400);
            }
        }
    }

    private String applySetClause(ObjectNode item, String clause,
                                   JsonNode exprAttrNames, JsonNode exprAttrValues) {
        // Parse comma-separated assignments: "attr = :val, #name = :val2"
        // Stop when we hit another clause keyword (REMOVE, ADD, DELETE) or end
        LOG.debugv("applySetClause: clause={0}, exprAttrNames={1}, exprAttrValues={2}",
                   clause, exprAttrNames, exprAttrValues);

        // Snapshot the item before applying any assignments so cross-attribute
        // references within the same SET expression (e.g. "SET b = a, a = :v")
        // resolve to pre-update values, matching real DynamoDB semantics
        // (actions are applied atomically and attribute references read original values).
        ObjectNode snapshot = item.deepCopy();

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

            // Strip balanced outer parentheses so producers that wrap the RHS
            // (e.g. ElectroDB emits "SET c = (c - :v)") behave the same as the
            // unwrapped form. DynamoDB grammar accepts parentheses around any
            // SET-action RHS.
            valuePart = stripOuterParens(valuePart);

            // Resolve the value
            // Check for arithmetic expressions (operand + operand, operand - operand)
            // before handling individual expression types, since the left operand can be
            // a function like if_not_exists(...).
            //
            // RHS reads use `snapshot` (pre-update item state) so multiple comma-separated
            // assignments behave atomically: each clause sees the original values, not the
            // intermediate state produced by previous clauses in the same expression.
            int arithmeticIdx = findArithmeticOperator(valuePart);
            if (arithmeticIdx >= 0) {
                String leftExpr = valuePart.substring(0, arithmeticIdx).trim();
                char operator = valuePart.charAt(arithmeticIdx);
                String rightExpr = valuePart.substring(arithmeticIdx + 1).trim();
                JsonNode leftVal = evaluateSetExpr(snapshot, leftExpr, exprAttrNames, exprAttrValues);
                JsonNode rightVal = evaluateSetExpr(snapshot, rightExpr, exprAttrNames, exprAttrValues);
                if (leftVal == null || rightVal == null || !leftVal.has("N") || !rightVal.has("N")) {
                    throw new AwsException("ValidationException",
                            "Invalid UpdateExpression: Incorrect operand type for operator or function", 400);
                }
                try {
                    java.math.BigDecimal left = new java.math.BigDecimal(leftVal.get("N").asText());
                    java.math.BigDecimal right = new java.math.BigDecimal(rightVal.get("N").asText());
                    java.math.BigDecimal result = (operator == '+') ? left.add(right) : left.subtract(right);
                    ObjectNode numNode = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    numNode.put("N", result.toPlainString());
                    setValueAtPath(item, attrPath, numNode, exprAttrNames);
                } catch (NumberFormatException e) {
                    throw new AwsException("ValidationException",
                            "The parameter cannot be converted to a numeric value", 400);
                }
            } else if (valuePart.startsWith("if_not_exists(")) {
                // if_not_exists(attrRef, fallbackExpr) evaluates to:
                //   attrRef's current value  — when attrRef exists in the item
                //   fallbackExpr             — otherwise
                // The result is always assigned to attrName.
                String[] args = extractFunctionArgs(valuePart);
                if (args.length == 2) {
                    String checkAttr = resolveAttributeName(args[0].trim(), exprAttrNames);
                    String fallbackExpr = args[1].trim();
                    JsonNode resolved;
                    if (hasValueAtPath(snapshot, checkAttr, exprAttrNames)) {
                        // attrRef exists — evaluate to its current value
                        resolved = getValueAtPath(snapshot, checkAttr, exprAttrNames);
                    } else if (fallbackExpr.startsWith(":") && exprAttrValues != null) {
                        resolved = exprAttrValues.get(fallbackExpr);
                    } else {
                        // fallback is itself an attribute reference
                        resolved = getValueAtPath(snapshot, resolveAttributeName(fallbackExpr, exprAttrNames), exprAttrNames);
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
                        JsonNode list1 = evaluateSetExpr(snapshot, arg1, exprAttrNames, exprAttrValues);
                        JsonNode list2 = evaluateSetExpr(snapshot, arg2, exprAttrNames, exprAttrValues);
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
                JsonNode refValue = getValueAtPath(snapshot, refAttr, exprAttrNames);
                if (refValue != null) {
                    setValueAtPath(item, attrPath, refValue, exprAttrNames);
                }
            }

            clause = rest;
        }
        return clause;
    }

    /**
     * Strip balanced outer parentheses from a SET-action RHS expression, repeatedly,
     * so that producers wrapping arithmetic or function calls in parens (e.g.
     * "(c - :v)" or "((if_not_exists(c, :d) - :v))") parse identically to the
     * unwrapped form. Rejects forms like "(a) - (b)" where the outer parens do
     * not actually enclose the whole expression.
     */
    private static String stripOuterParens(String expr) {
        String s = expr.trim();
        while (s.length() >= 2 && s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')') {
            int depth = 0;
            boolean wraps = true;
            for (int i = 0; i < s.length() - 1; i++) {
                if (s.charAt(i) == '(') depth++;
                else if (s.charAt(i) == ')') depth--;
                if (depth == 0) { wraps = false; break; }
            }
            if (!wraps) break;
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private JsonNode evaluateSetExpr(ObjectNode item, String expr,
                                     JsonNode exprAttrNames, JsonNode exprAttrValues) {
        if (expr.toLowerCase().startsWith("if_not_exists(")) {
            String[] args = extractFunctionArgs(expr);
            if (args.length == 2) {
                String checkAttr = resolveAttributeName(args[0].trim(), exprAttrNames);
                String fallbackExpr = args[1].trim();
                if (hasValueAtPath(item, checkAttr, exprAttrNames)) {
                    return getValueAtPath(item, checkAttr, exprAttrNames);
                } else if (fallbackExpr.startsWith(":") && exprAttrValues != null) {
                    return exprAttrValues.get(fallbackExpr);
                } else {
                    return getValueAtPath(item, resolveAttributeName(fallbackExpr, exprAttrNames), exprAttrNames);
                }
            }
            return null;
        } else if (expr.startsWith(":") && exprAttrValues != null) {
            return exprAttrValues.get(expr);
        } else {
            return getValueAtPath(item, resolveAttributeName(expr, exprAttrNames), exprAttrNames);
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

    // Tokenizes a DynamoDB path like "a.b[0].c" or "#l[5]" into a list of
    // String (attr name) and Integer (list index) tokens.
    private List<Object> parsePath(String path, JsonNode exprAttrNames) {
        List<Object> tokens = new ArrayList<>();
        for (String dotSeg : path.split("\\.")) {
            dotSeg = dotSeg.trim();
            if (dotSeg.isEmpty()) continue;
            int brk = dotSeg.indexOf('[');
            if (brk < 0) {
                tokens.add(resolveAttributeName(dotSeg, exprAttrNames));
            } else {
                String namePart = dotSeg.substring(0, brk);
                if (!namePart.isEmpty()) tokens.add(resolveAttributeName(namePart, exprAttrNames));
                String rest = dotSeg.substring(brk);
                int p = 0;
                while (p < rest.length() && rest.charAt(p) == '[') {
                    int close = rest.indexOf(']', p);
                    if (close < 0) break;
                    tokens.add(Integer.parseInt(rest.substring(p + 1, close)));
                    p = close + 1;
                }
            }
        }
        return tokens;
    }

    // Pads arr with NULL elements up to idx-1, then sets/appends value at idx.
    private void padAndSet(com.fasterxml.jackson.databind.node.ArrayNode arr, int idx, JsonNode value) {
        com.fasterxml.jackson.databind.node.ObjectNode nullNode =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        nullNode.put("NULL", true);
        while (arr.size() < idx) arr.add(nullNode.deepCopy());
        if (idx < arr.size()) arr.set(idx, value);
        else arr.add(value);
    }

    private void setValueAtPath(ObjectNode item, String path, JsonNode value, JsonNode exprAttrNames) {
        List<Object> tokens = parsePath(path, exprAttrNames);
        if (tokens.isEmpty()) return;

        if (tokens.size() == 1) {
            if (tokens.get(0) instanceof String attrName) item.set(attrName, value);
            return;
        }

        JsonNode container = item;
        for (int i = 0; i < tokens.size() - 1; i++) {
            Object tok = tokens.get(i);
            Object nextTok = tokens.get(i + 1);
            boolean last = (i == tokens.size() - 2);

            if (tok instanceof String attrName) {
                if (!(container instanceof ObjectNode obj)) return;
                JsonNode child = obj.get(attrName);
                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (child == null) {
                            ObjectNode newMap = objectMapper.createObjectNode();
                            newMap.set(finalAttr, value);
                            ObjectNode wrapper = objectMapper.createObjectNode();
                            wrapper.set("M", newMap);
                            obj.set(attrName, wrapper);
                        } else if (!child.has("M")) {
                            throw new AwsException("ValidationException",
                                    "The document path provided in the update expression is invalid for update", 400);
                        } else {
                            ((ObjectNode) child.get("M")).set(finalAttr, value);
                        }
                    } else if (nextTok instanceof Integer finalIdx) {
                        if (child == null || !child.has("L")) throw new AwsException("ValidationException",
                                "The document path provided in the update expression is invalid for update", 400);
                        padAndSet((com.fasterxml.jackson.databind.node.ArrayNode) child.get("L"), finalIdx, value);
                    }
                    return;
                }
                if (nextTok instanceof String) {
                    if (child == null) {
                        ObjectNode newMap = objectMapper.createObjectNode();
                        ObjectNode wrapper = objectMapper.createObjectNode();
                        wrapper.set("M", newMap);
                        obj.set(attrName, wrapper);
                        container = newMap;
                    } else if (!child.has("M")) {
                        throw new AwsException("ValidationException",
                                "The document path provided in the update expression is invalid for update", 400);
                    } else {
                        container = child.get("M");
                    }
                } else if (nextTok instanceof Integer) {
                    if (child == null || !child.has("L")) throw new AwsException("ValidationException",
                            "The document path provided in the update expression is invalid for update", 400);
                    container = child.get("L");
                }
            } else if (tok instanceof Integer listIdx) {
                if (!(container instanceof com.fasterxml.jackson.databind.node.ArrayNode arr)) return;
                if (listIdx >= arr.size()) throw new AwsException("ValidationException",
                        "The document path provided in the update expression is invalid for update", 400);
                JsonNode element = arr.get(listIdx);
                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (!element.has("M")) throw new AwsException("ValidationException",
                                "The document path provided in the update expression is invalid for update", 400);
                        ((ObjectNode) element.get("M")).set(finalAttr, value);
                    } else if (nextTok instanceof Integer finalIdx) {
                        if (!element.has("L")) throw new AwsException("ValidationException",
                                "The document path provided in the update expression is invalid for update", 400);
                        padAndSet((com.fasterxml.jackson.databind.node.ArrayNode) element.get("L"), finalIdx, value);
                    }
                    return;
                }
                if (nextTok instanceof String) {
                    if (!element.has("M")) throw new AwsException("ValidationException",
                            "The document path provided in the update expression is invalid for update", 400);
                    container = element.get("M");
                } else if (nextTok instanceof Integer) {
                    if (!element.has("L")) throw new AwsException("ValidationException",
                            "The document path provided in the update expression is invalid for update", 400);
                    container = element.get("L");
                }
            }
        }
    }

    private JsonNode getValueAtPath(JsonNode item, String path, JsonNode exprAttrNames) {
        List<Object> tokens = parsePath(path, exprAttrNames);
        if (tokens.isEmpty()) return null;
        JsonNode current = item;
        for (int i = 0; i < tokens.size(); i++) {
            if (current == null) return null;
            Object tok = tokens.get(i);
            boolean isLast = (i == tokens.size() - 1);
            if (tok instanceof String attrName) {
                JsonNode child = current.get(attrName);
                if (child == null) return null;
                if (isLast) return child;
                Object nextTok = tokens.get(i + 1);
                if (nextTok instanceof String) {
                    if (!child.has("M")) return null;
                    current = child.get("M");
                } else if (nextTok instanceof Integer) {
                    if (!child.has("L")) return null;
                    current = child.get("L");
                } else return null;
            } else if (tok instanceof Integer listIdx) {
                if (!current.isArray() || listIdx >= current.size()) return null;
                JsonNode element = current.get(listIdx);
                if (element == null) return null;
                if (isLast) return element;
                Object nextTok = tokens.get(i + 1);
                if (nextTok instanceof String) {
                    if (!element.has("M")) return null;
                    current = element.get("M");
                } else if (nextTok instanceof Integer) {
                    if (!element.has("L")) return null;
                    current = element.get("L");
                } else return null;
            }
        }
        return current;
    }

    private boolean hasValueAtPath(JsonNode item, String path, JsonNode exprAttrNames) {
        return getValueAtPath(item, path, exprAttrNames) != null;
    }

    private void removeValueAtPath(ObjectNode item, String path, JsonNode exprAttrNames) {
        List<Object> tokens = parsePath(path, exprAttrNames);
        if (tokens.isEmpty()) return;

        if (tokens.size() == 1) {
            if (tokens.get(0) instanceof String attrName) item.remove(attrName);
            return;
        }

        JsonNode container = item;
        for (int i = 0; i < tokens.size() - 1; i++) {
            Object tok = tokens.get(i);
            Object nextTok = tokens.get(i + 1);
            boolean last = (i == tokens.size() - 2);

            if (tok instanceof String attrName) {
                if (!(container instanceof ObjectNode obj)) return;
                JsonNode child = obj.get(attrName);
                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (child == null || !child.has("M")) return;
                        ((ObjectNode) child.get("M")).remove(finalAttr);
                    } else if (nextTok instanceof Integer finalIdx) {
                        if (child == null || !child.has("L")) return;
                        com.fasterxml.jackson.databind.node.ArrayNode lArr =
                                (com.fasterxml.jackson.databind.node.ArrayNode) child.get("L");
                        if (finalIdx < lArr.size()) lArr.remove(finalIdx);
                    }
                    return;
                }
                if (nextTok instanceof String) {
                    if (child == null || !child.has("M")) return;
                    container = child.get("M");
                } else if (nextTok instanceof Integer) {
                    if (child == null || !child.has("L")) return;
                    container = child.get("L");
                }
            } else if (tok instanceof Integer listIdx) {
                if (!(container instanceof com.fasterxml.jackson.databind.node.ArrayNode arr)) return;
                if (listIdx >= arr.size()) return;
                JsonNode element = arr.get(listIdx);
                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (!element.has("M")) return;
                        ((ObjectNode) element.get("M")).remove(finalAttr);
                    } else if (nextTok instanceof Integer finalIdx) {
                        if (!element.has("L")) return;
                        com.fasterxml.jackson.databind.node.ArrayNode lArr =
                                (com.fasterxml.jackson.databind.node.ArrayNode) element.get("L");
                        if (finalIdx < lArr.size()) lArr.remove(finalIdx);
                    }
                    return;
                }
                if (nextTok instanceof String) {
                    if (!element.has("M")) return;
                    container = element.get("M");
                } else if (nextTok instanceof Integer) {
                    if (!element.has("L")) return;
                    container = element.get("L");
                }
            }
        }
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

    /**
     * Finds the index of an arithmetic operator (+ or -) that is outside
     * function parentheses. Returns -1 if none found.
     */
    private int findArithmeticOperator(String expr) {
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == '+' || c == '-')) {
                // Ensure this is a binary operator, not a sign at the start or after '('
                if (i > 0) {
                    return i;
                }
            }
        }
        return -1;
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

    private ReentrantLock lockFor(String storageKey, String itemKey) {
        return itemLocks
                .computeIfAbsent(storageKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(itemKey, k -> new ReentrantLock());
    }

    private void withItemLock(String storageKey, String itemKey, Runnable body) {
        ReentrantLock lock = lockFor(storageKey, itemKey);
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    private <T> T withItemLock(String storageKey, String itemKey, Supplier<T> body) {
        ReentrantLock lock = lockFor(storageKey, itemKey);
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    String buildItemKey(TableDefinition table, JsonNode item) {
        return buildItemKey(table, item, false);
    }

    String buildItemKey(TableDefinition table, JsonNode item, boolean isKeyArg) {
        String pkName = table.getPartitionKeyName();
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr == null) {
            if (isKeyArg) {
                throw new AwsException("ValidationException",
                        "The provided key element does not match the schema", 400);
            }
            throw new AwsException("ValidationException",
                    "One of the required keys was not given a value", 400);
        }
        validateKeyAttributeValue(pkAttr, pkName);

        String pk = extractScalarValue(pkAttr);
        String skName = table.getSortKeyName();
        if (skName != null) {
            JsonNode skAttr = item.get(skName);
            if (skAttr == null) {
                if (isKeyArg) {
                    throw new AwsException("ValidationException",
                            "The provided key element does not match the schema", 400);
                }
                throw new AwsException("ValidationException",
                        "One of the required keys was not given a value", 400);
            }
            validateKeyAttributeValue(skAttr, skName);
            return pk + "#" + extractScalarValue(skAttr);
        }
        return pk;
    }

    private void validateKeyAttributeValue(JsonNode attr, String keyName) {
        if (attr != null && attr.has("S") && attr.get("S").asText().isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: "
                    + "The AttributeValue for a key attribute cannot contain an empty string value. Key: " + keyName, 400);
        }
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
        return buildKeyNode(table, item, pkName, skName, false);
    }

    JsonNode buildKeyNode(TableDefinition table, JsonNode item,
                          String pkName, String skName, boolean isIndexQuery) {
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
        if (isIndexQuery) {
            String tablePk = table.getPartitionKeyName();
            String tableSk = table.getSortKeyName();
            if (!tablePk.equals(pkName) && item.get(tablePk) != null) {
                keyNode.set(tablePk, item.get(tablePk));
            }
            if (tableSk != null && !tableSk.equals(skName) && item.get(tableSk) != null) {
                keyNode.set(tableSk, item.get(tableSk));
            }
        }
        return keyNode;
    }

    private String extractScalarValue(JsonNode attrValue) {
        if (attrValue == null) return null;
        if (attrValue.has("S")) return attrValue.get("S").asText();
        if (attrValue.has("N")) {
            String raw = attrValue.get("N").asText();
            try { return DynamoDbNumberUtils.validateAndNormalize(raw); }
            catch (Exception e) { return raw; }
        }
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

    private boolean matchesKeyCondition(JsonNode attrValue, JsonNode condition) {
        if (condition == null) return true;
        String op = condition.has("ComparisonOperator") ? condition.get("ComparisonOperator").asText() : "EQ";
        JsonNode avl = condition.get("AttributeValueList");
        JsonNode compareAttr = avl != null && avl.isArray() && !avl.isEmpty() ? avl.get(0) : null;
        String compareValue = extractScalarValue(compareAttr);
        String actual = extractScalarValue(attrValue);

        return switch (op) {
            case "EQ" -> {
                if (attrValue == null) yield false;
                yield ExpressionEvaluator.compareAttributeValues(attrValue, compareAttr != null ? compareAttr : attrValue) == 0
                        && actual != null && actual.equals(compareValue);
            }
            case "NE" -> {
                if (attrValue == null) yield true;
                if (actual == null || compareValue == null) yield true;
                yield !actual.equals(compareValue);
            }
            case "NULL" -> attrValue == null;
            case "NOT_NULL" -> attrValue != null;
            case "BEGINS_WITH" -> actual != null && compareValue != null && actual.startsWith(compareValue);
            case "CONTAINS" -> {
                if (attrValue == null || compareAttr == null) yield false;
                if (attrValue.has("S") && compareAttr.has("S")) {
                    yield attrValue.get("S").asText().contains(compareAttr.get("S").asText());
                }
                if (attrValue.has("SS") && compareAttr.has("S")) {
                    String target = compareAttr.get("S").asText();
                    for (JsonNode elem : attrValue.get("SS")) { if (target.equals(elem.asText())) yield true; }
                    yield false;
                }
                if (attrValue.has("NS") && compareAttr.has("N")) {
                    String target = compareAttr.get("N").asText();
                    for (JsonNode elem : attrValue.get("NS")) { if (target.equals(elem.asText())) yield true; }
                    yield false;
                }
                yield false;
            }
            case "NOT_CONTAINS" -> {
                if (attrValue == null || compareAttr == null) yield true;
                if (attrValue.has("S") && compareAttr.has("S"))
                    yield !attrValue.get("S").asText().contains(compareAttr.get("S").asText());
                if (attrValue.has("SS") && compareAttr.has("S")) {
                    String target = compareAttr.get("S").asText();
                    for (JsonNode elem : attrValue.get("SS")) { if (target.equals(elem.asText())) yield false; }
                    yield true;
                }
                yield true;
            }
            case "IN" -> {
                if (actual == null || avl == null) yield false;
                for (JsonNode v : avl) { if (actual.equals(extractScalarValue(v))) yield true; }
                yield false;
            }
            case "GT" -> actual != null && compareValue != null
                    && ExpressionEvaluator.compareAttributeValues(attrValue, compareAttr) > 0;
            case "GE" -> actual != null && compareValue != null
                    && ExpressionEvaluator.compareAttributeValues(attrValue, compareAttr) >= 0;
            case "LT" -> actual != null && compareValue != null
                    && ExpressionEvaluator.compareAttributeValues(attrValue, compareAttr) < 0;
            case "LE" -> actual != null && compareValue != null
                    && ExpressionEvaluator.compareAttributeValues(attrValue, compareAttr) <= 0;
            case "BETWEEN" -> {
                if (actual == null || avl == null || avl.size() < 2) yield false;
                yield ExpressionEvaluator.compareAttributeValues(attrValue, avl.get(0)) >= 0
                        && ExpressionEvaluator.compareAttributeValues(attrValue, avl.get(1)) <= 0;
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

        // Validate the PK attribute in the expression matches the actual table/index PK
        if (!resolvedPkName.equals(pkName)) {
            throw new AwsException("ValidationException",
                    "Query condition missed key schema element: " + pkName, 400);
        }

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
        return new AwsException("ResourceNotFoundException", "Requested resource not found", 400);
    }

    public record UpdateResult(JsonNode newItem, JsonNode oldItem) {}
    public record ScanResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}
    public record QueryResult(List<JsonNode> items, int scannedCount, JsonNode lastEvaluatedKey) {}

    // --- Export Operations ---

    public ExportDescription exportTable(Map<String, Object> request, String region) {
        String tableArn = (String) request.get("TableArn");
        String s3Bucket = (String) request.get("S3Bucket");
        String s3Prefix = request.containsKey("S3Prefix") ? (String) request.get("S3Prefix") : null;
        String exportFormat = request.containsKey("ExportFormat") ? (String) request.get("ExportFormat") : "DYNAMODB_JSON";
        String exportType = request.containsKey("ExportType") ? (String) request.get("ExportType") : "FULL_EXPORT";
        String clientToken = request.containsKey("ClientToken") ? (String) request.get("ClientToken") : null;
        String s3SseAlgorithm = request.containsKey("S3SseAlgorithm") ? (String) request.get("S3SseAlgorithm") : null;
        String s3BucketOwner = request.containsKey("S3BucketOwner") ? (String) request.get("S3BucketOwner") : null;

        if ("INCREMENTAL_EXPORT".equals(exportType)) {
            throw new AwsException("ValidationException",
                    "ExportType INCREMENTAL_EXPORT is not supported", 400);
        }
        if ("ION".equals(exportFormat)) {
            throw new AwsException("ValidationException",
                    "ExportFormat ION is not supported", 400);
        }

        DynamoDbTableNames.ResolvedTableRef ref = DynamoDbTableNames.resolveWithRegion(tableArn, region);
        String tableName = ref.name();
        String tableRegion = ref.region() != null ? ref.region() : region;
        String storageKey = regionKey(tableRegion, tableName);

        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(tableName));

        long now = Instant.now().getEpochSecond();
        String exportId = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "");
        String exportArn = AwsArnUtils.Arn.of("dynamodb", tableRegion, regionResolver.getAccountId(), "table/" + table.getTableName() + "/export/" + exportId).toString();

        ExportDescription desc = new ExportDescription();
        desc.setExportArn(exportArn);
        desc.setExportStatus("IN_PROGRESS");
        desc.setTableArn(table.getTableArn());
        desc.setTableId(table.getTableName());
        desc.setS3Bucket(s3Bucket);
        desc.setS3Prefix(s3Prefix);
        desc.setExportFormat(exportFormat);
        desc.setExportType("FULL_EXPORT");
        desc.setExportTime(now);
        desc.setStartTime(now);
        desc.setClientToken(clientToken);
        desc.setS3SseAlgorithm(s3SseAlgorithm);
        desc.setS3BucketOwner(s3BucketOwner);

        if (exportStore != null) {
            exportStore.put(exportArn, desc);
        }

        ExportDescription finalDesc = desc;
        ConcurrentSkipListMap<String, JsonNode> tableItems = itemsByTable.get(storageKey);
        List<JsonNode> snapshot = tableItems != null
                ? List.copyOf(tableItems.values())
                : List.of();

        Thread.ofVirtual().start(() -> runExport(finalDesc, snapshot, exportArn));

        return desc;
    }

    private void runExport(ExportDescription desc, List<JsonNode> snapshot, String exportArn) {
        try {
            String s3Bucket = desc.getS3Bucket();
            String s3Prefix = desc.getS3Prefix() != null ? desc.getS3Prefix() : "";
            String exportId = exportArn.substring(exportArn.lastIndexOf('/') + 1);
            String dataFileUuid = UUID.randomUUID().toString();
            String dataKey = (s3Prefix.isEmpty() ? "" : s3Prefix + "/")
                    + "AWSDynamoDB/" + exportId + "/data/" + dataFileUuid + ".json.gz";
            String manifestFilesKey = (s3Prefix.isEmpty() ? "" : s3Prefix + "/")
                    + "AWSDynamoDB/" + exportId + "/manifest-files.json";
            String manifestSummaryKey = (s3Prefix.isEmpty() ? "" : s3Prefix + "/")
                    + "AWSDynamoDB/" + exportId + "/manifest-summary.json";

            byte[] gzipData = buildGzipNdjson(snapshot);

            try {
                s3Service.putObject(s3Bucket, dataKey, gzipData, "application/octet-stream", Map.of());
            } catch (AwsException e) {
                if ("NoSuchBucket".equals(e.getErrorCode())) {
                    desc.setExportStatus("FAILED");
                    desc.setFailureCode("S3NoSuchBucket");
                    desc.setFailureMessage("The specified bucket does not exist: " + s3Bucket);
                    desc.setEndTime(Instant.now().getEpochSecond());
                    if (exportStore != null) {
                        exportStore.put(exportArn, desc);
                    }
                    return;
                }
                throw e;
            }

            String md5 = computeMd5Hex(gzipData);
            String etag = md5;

            String manifestFilesContent = dataKey + "\n";
            s3Service.putObject(s3Bucket, manifestFilesKey,
                    manifestFilesContent.getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());

            long billedSize = gzipData.length;
            long itemCount = snapshot.size();

            String manifestSummaryContent = buildManifestSummary(
                    desc, exportId, dataKey, itemCount, billedSize, md5, etag, manifestSummaryKey);
            s3Service.putObject(s3Bucket, manifestSummaryKey,
                    manifestSummaryContent.getBytes(StandardCharsets.UTF_8),
                    "application/json", Map.of());

            long endTime = Instant.now().getEpochSecond();
            desc.setExportStatus("COMPLETED");
            desc.setEndTime(endTime);
            desc.setItemCount(itemCount);
            desc.setBilledSizeBytes(billedSize);
            desc.setExportManifest(manifestSummaryKey);

            if (exportStore != null) {
                exportStore.put(exportArn, desc);
            }
            LOG.infov("Export completed: {0}, items={1}", exportArn, itemCount);

        } catch (Exception e) {
            LOG.errorv(e, "Export failed: {0}", exportArn);
            desc.setExportStatus("FAILED");
            desc.setFailureCode("UNKNOWN");
            desc.setFailureMessage(e.getMessage());
            desc.setEndTime(Instant.now().getEpochSecond());
            if (exportStore != null) {
                exportStore.put(exportArn, desc);
            }
        }
    }

    private byte[] buildGzipNdjson(List<JsonNode> items) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            for (JsonNode item : items) {
                ObjectNode line = objectMapper.createObjectNode();
                line.set("Item", item);
                byte[] lineBytes = objectMapper.writeValueAsBytes(line);
                gzip.write(lineBytes);
                gzip.write('\n');
            }
        }
        return baos.toByteArray();
    }

    private String computeMd5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /**
     * SHA-256 hex digest of a string, used by ClientRequestToken idempotency to compare
     * request bodies. SHA-256 is required (not MD5) for the dedup contract because a
     * collision would allow a request with different parameters to be silently treated
     * as a replay; SHA-256 is supported on every JVM via {@link MessageDigest}.
     */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS to be available on every JVM; this should never trigger.
            throw new IllegalStateException("SHA-256 is required but not available on this JVM", e);
        }
    }

    private String buildManifestSummary(ExportDescription desc, String exportId,
                                         String dataKey, long itemCount, long billedSize,
                                         String md5, String etag, String manifestSummaryKey) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.put("version", "2020-06-30");
            root.put("exportArn", desc.getExportArn());
            root.put("startTime", Instant.ofEpochSecond(desc.getStartTime()).toString());
            root.put("endTime", Instant.now().toString());
            root.put("tableArn", desc.getTableArn());
            root.put("tableId", desc.getTableId());
            root.put("exportTime", Instant.ofEpochSecond(desc.getExportTime()).toString());
            root.put("s3Bucket", desc.getS3Bucket());
            root.putNull("s3Prefix");
            if (desc.getS3Prefix() != null) {
                root.put("s3Prefix", desc.getS3Prefix());
            }
            root.put("s3SseAlgorithm", desc.getS3SseAlgorithm() != null ? desc.getS3SseAlgorithm() : "AES256");
            root.putNull("s3SseKmsKeyId");
            root.put("exportFormat", desc.getExportFormat());
            root.put("billedSizeBytes", billedSize);
            root.put("itemCount", itemCount);

            com.fasterxml.jackson.databind.node.ArrayNode outputFiles = root.putArray("outputFiles");
            com.fasterxml.jackson.databind.node.ObjectNode fileEntry = outputFiles.addObject();
            fileEntry.put("itemCount", itemCount);
            fileEntry.put("md5Checksum", md5);
            fileEntry.put("etag", etag);
            fileEntry.put("dataFileS3Key", dataKey);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public ExportDescription describeExport(String exportArn) {
        if (exportStore == null) {
            throw new AwsException("ExportNotFoundException",
                    "Export not found: " + exportArn, 400);
        }
        return exportStore.get(exportArn)
                .orElseThrow(() -> new AwsException("ExportNotFoundException",
                        "Export not found: " + exportArn, 400));
    }

    public record ListExportsResult(List<ExportSummary> exportSummaries, String nextToken) {}

    public ListExportsResult listExports(String tableArn, Integer maxResults, String nextToken) {
        if (exportStore == null) {
            return new ListExportsResult(List.of(), null);
        }
        int limit = maxResults != null ? Math.min(maxResults, 25) : 25;

        List<ExportDescription> all = exportStore.keys().stream()
                .map(k -> exportStore.get(k).orElse(null))
                .filter(d -> d != null)
                .filter(d -> tableArn == null || tableArn.equals(d.getTableArn()))
                .sorted(Comparator.comparing(ExportDescription::getExportArn).reversed())
                .toList();

        int startIdx = 0;
        if (nextToken != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getExportArn().equals(nextToken)) {
                    startIdx = i + 1;
                    break;
                }
            }
        }

        List<ExportDescription> page = all.subList(startIdx, Math.min(startIdx + limit, all.size()));
        String newNextToken = (startIdx + limit < all.size()) ? all.get(startIdx + limit - 1).getExportArn() : null;

        List<ExportSummary> summaries = page.stream()
                .map(ExportSummary::new)
                .toList();

        return new ListExportsResult(summaries, newNextToken);
    }
}
