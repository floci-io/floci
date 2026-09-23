package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.ExportDescription;
import io.github.hectorvent.floci.services.dynamodb.model.ExportSummary;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.ImportSummary;
import io.github.hectorvent.floci.services.dynamodb.model.ImportTableDescription;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.VectorIndex;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import io.github.hectorvent.floci.services.s3.S3Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;

@ApplicationScoped
public class DynamoDbService implements ResourceProvider {

    private static final String LOCAL_REPLICA_UPDATE_ERROR =
            "Cannot add, delete, or update the local region through ReplicaUpdates. "
                    + "Use CreateTable, DeleteTable, or UpdateTable as required.";

    private static final Logger LOG = Logger.getLogger(DynamoDbService.class);

    /**
     * View type applied when a stream is requested without an explicit StreamViewType.
     *
     * <p>Not an AWS default: the CloudFormation schema marks StreamViewType required inside
     * StreamSpecification, and the DynamoDB API documents no default either. This mirrors the
     * lenient handling {@code DynamoDbJsonHandler} already applies on CreateTable/UpdateTable
     * ({@code path("StreamViewType").asText("NEW_AND_OLD_IMAGES")}), so an under-specified
     * template gets a working stream instead of a rejection, and every entry point agrees.
     */
    private static final String DEFAULT_STREAM_VIEW_TYPE = "NEW_AND_OLD_IMAGES";

    private final StorageBackend<String, TableDefinition> tableStore;
    private final StorageBackend<String, Map<String, JsonNode>> itemStore;
    private final StorageBackend<String, ExportDescription> exportStore;
    private final StorageBackend<String, ImportTableDescription> importStore;
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
    private static final long TX_IDEMPOTENCY_TTL_NANOS = Duration.ofMinutes(10).toNanos();

    private static final int MAX_MULTI_ATTRIBUTE_KEY_PART_SIZE = 4;

    private static final int MAX_VECTOR_INDEXES_PER_TABLE = 5;
    private static final int MAX_VECTOR_DIMENSIONS = 4096;
    private static final int MIN_VECTOR_INDEX_NAME_LENGTH = 3;
    // The order AWS prints the enum constraint in, which is neither alphabetical nor the order
    // the API reference lists.
    private static final List<String> VECTOR_DISTANCE_FUNCTIONS = List.of(
            DynamoDbVectorScoring.DOT_PRODUCT,
            DynamoDbVectorScoring.COSINE,
            DynamoDbVectorScoring.EUCLIDEAN);

    /**
     * A vector index a request adds, with the request-model path AWS reports its per-member
     * constraints under.
     *
     * <p>CreateTable and UpdateTable name different members, and an UpdateTable create shares the
     * {@code VectorIndexUpdates} array with the deletes beside it, so only the caller that read
     * the request can build the path.
     */
    public record VectorIndexCreate(VectorIndex index, String memberPath) {}

    private record IdempotencyEntry(String requestHash, long insertedAtNanos,
                                    CompletableFuture<Map<String, DynamoDbWriteCapacity.Cost>> replayCapacity) {}
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private DynamoDbStreamService streamService;
    private KinesisStreamingForwarder kinesisForwarder;
    private S3Service s3Service;
    private final int vectorIndexAllocationSeconds;
    private final int vectorIndexBackfillSeconds;
    private static final long MAX_DYNAMODB_LIST_INDEX = 4_294_967_294L;

    @Inject
    public DynamoDbService(StorageFactory storageFactory, RegionResolver regionResolver,
                           DynamoDbStreamService streamService,
                           KinesisStreamingForwarder kinesisForwarder,
                           S3Service s3Service,
                           ObjectMapper objectMapper,
                           EmulatorConfig config) {
        this(storageFactory.create("dynamodb", "dynamodb-tables.json",
                new TypeReference<Map<String, TableDefinition>>() {}),
             storageFactory.create("dynamodb", "dynamodb-items.json",
                new TypeReference<Map<String, Map<String, JsonNode>>>() {}),
             storageFactory.create("dynamodb", "dynamodb-exports.json",
                new TypeReference<Map<String, ExportDescription>>() {}),
             storageFactory.create("dynamodb", "dynamodb-imports.json",
                new TypeReference<Map<String, ImportTableDescription>>() {}),
             regionResolver, streamService, kinesisForwarder, s3Service, objectMapper,
             config.services().dynamodb().vectorIndexAllocationSeconds(),
             config.services().dynamodb().vectorIndexBackfillSeconds());
    }

    /** Package-private constructor for testing. */
    DynamoDbService(StorageBackend<String, TableDefinition> tableStore) {
        this(tableStore, null, null, null, new RegionResolver("us-east-1", "000000000000"), null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore, RegionResolver regionResolver) {
        this(tableStore, null, null, null, regionResolver, null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver) {
        this(tableStore, itemStore, null, null, regionResolver, null, null, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder) {
        this(tableStore, itemStore, null, null, regionResolver, streamService, kinesisForwarder, null, null);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    StorageBackend<String, ExportDescription> exportStore,
                    StorageBackend<String, ImportTableDescription> importStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder,
                    S3Service s3Service,
                    ObjectMapper objectMapper) {
        this(tableStore, itemStore, exportStore, importStore, regionResolver, streamService,
             kinesisForwarder, s3Service, objectMapper, 0, 0);
    }

    DynamoDbService(StorageBackend<String, TableDefinition> tableStore,
                    StorageBackend<String, Map<String, JsonNode>> itemStore,
                    StorageBackend<String, ExportDescription> exportStore,
                    StorageBackend<String, ImportTableDescription> importStore,
                    RegionResolver regionResolver,
                    DynamoDbStreamService streamService,
                    KinesisStreamingForwarder kinesisForwarder,
                    S3Service s3Service,
                    ObjectMapper objectMapper,
                    int vectorIndexAllocationSeconds,
                    int vectorIndexBackfillSeconds) {
        this.tableStore = tableStore;
        this.itemStore = itemStore;
        this.exportStore = exportStore;
        this.importStore = importStore;
        this.regionResolver = regionResolver;
        this.streamService = streamService;
        this.kinesisForwarder = kinesisForwarder;
        this.s3Service = s3Service;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.vectorIndexAllocationSeconds = vectorIndexAllocationSeconds;
        this.vectorIndexBackfillSeconds = vectorIndexBackfillSeconds;
        loadPersistedItems();
        recoverInterruptedJobs();
    }

    private void loadPersistedItems() {
        if (itemStore == null) return;
        Map<String, TableDefinition> persistedTables = persistedTablesByScopedKey();
        // No request scope at startup, so itemStore.keys() would only see the default account.
        // scanAllAccountsRaw() returns every account's items already in the "accountId/
        // region::tableName" key format itemsByTable expects.
        if (itemStore instanceof AccountAwareStorageBackend<Map<String, JsonNode>> aware) {
            aware.scanAllAccountsRaw().forEach((rawKey, items) ->
                itemsByTable.put(rawKey, rekeyPersistedItems(persistedTables.get(rawKey), items)));
            return;
        }
        for (String key : itemStore.keys()) {
            String scopedKey = scopedItemsKey(key);
            itemStore.get(key).ifPresent(items -> itemsByTable.put(
                    scopedKey, rekeyPersistedItems(persistedTables.get(scopedKey), items)));
        }
    }

    private Map<String, TableDefinition> persistedTablesByScopedKey() {
        if (tableStore instanceof AccountAwareStorageBackend<TableDefinition> aware) {
            return aware.scanAllAccountsRaw();
        }
        Map<String, TableDefinition> persistedTables = new HashMap<>();
        for (String key : tableStore.keys()) {
            tableStore.get(key).ifPresent(table -> persistedTables.put(scopedItemsKey(key), table));
        }
        return persistedTables;
    }

    private ConcurrentSkipListMap<String, JsonNode> rekeyPersistedItems(
            TableDefinition table, Map<String, JsonNode> persistedItems) {
        if (table == null) {
            return new ConcurrentSkipListMap<>(persistedItems);
        }
        ConcurrentSkipListMap<String, JsonNode> rekeyedItems = new ConcurrentSkipListMap<>();
        for (JsonNode item : persistedItems.values()) {
            rekeyedItems.put(buildItemKeyFromNode(
                    item, table.getPartitionKeyName(), table.getSortKeyName()), item);
        }
        return rekeyedItems;
    }

    private void persistItems(String storageKey) {
        if (itemStore == null) return;
        var items = currentItems(storageKey, false);
        if (items != null) {
            itemStore.put(storageKey, new HashMap<>(items));
        } else {
            itemStore.delete(storageKey);
        }
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
        return createTable(tableName, keySchema, attributeDefinitions, readCapacity, writeCapacity,
                           gsis, lsis, List.of(), null, region);
    }

    public TableDefinition createTable(String tableName,
                                        List<KeySchemaElement> keySchema,
                                        List<AttributeDefinition> attributeDefinitions,
                                        Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsis,
                                        List<LocalSecondaryIndex> lsis,
                                        List<VectorIndexCreate> vectorIndexes,
                                        String billingMode,
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
                    .collect(Collectors.joining(", ")) + "]";
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

        List<VectorIndexCreate> vectorIndexCreates = vectorIndexes != null ? vectorIndexes : List.of();
        validateVectorIndexMembers(vectorIndexCreates);
        validateVectorIndexes(vectorIndexCreates, List.of(), attributeDefinitions, billingMode);

        Set<String> referencedAttrs = new HashSet<>();
        keySchema.forEach(k -> referencedAttrs.add(k.getAttributeName()));
        if (gsis != null) {
            gsis.forEach(g -> g.getKeySchema().forEach(k -> referencedAttrs.add(k.getAttributeName())));
        }
        if (lsis != null) {
            lsis.forEach(l -> l.getKeySchema().forEach(k -> referencedAttrs.add(k.getAttributeName())));
        }
        // A SearchSchema attribute counts as used, so the unused-definition rejection below does
        // not fire for one that only a vector index reads.
        vectorIndexCreates.forEach(v -> referencedAttrs.addAll(v.index().getSearchSchemaAttributeNames()));
        Set<String> definedAttrs = attributeDefinitions == null
                ? Set.of()
                : attributeDefinitions.stream()
                        .map(AttributeDefinition::getAttributeName)
                        .collect(Collectors.toSet());
        if (!definedAttrs.containsAll(referencedAttrs)) {
            throw new AwsException("ValidationException",
                    "Invalid KeySchema: Some index key attribute have no definition", 400);
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
        for (VectorIndexCreate create : vectorIndexCreates) {
            if (!indexNames.add(create.index().getIndexName())) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Duplicate index name: "
                        + create.index().getIndexName(), 400);
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
                validateGsiKeySchemaArity(gsi);
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
                if (lsi.getSortKeyNames().size() != 1) {
                    throw new AwsException("ValidationException",
                            "One or more parameter values were invalid: Local Secondary Index "
                            + lsi.getIndexName() + " must have a sort key consisting of exactly "
                            + "one scalar attribute", 400);
                }
                lsi.setIndexArn(table.getTableArn() + "/index/" + lsi.getIndexName());
            }
            table.setLocalSecondaryIndexes(new ArrayList<>(lsis));
        }

        // A new VectorIndex is already ACTIVE with no creation timestamp, which is what AWS
        // reports for one created with the table: Backfilling is never reported for it, and
        // only the UpdateTable path runs the two timed phases.
        if (!vectorIndexCreates.isEmpty()) {
            List<VectorIndex> created = new ArrayList<>();
            for (VectorIndexCreate create : vectorIndexCreates) {
                VectorIndex vectorIndex = create.index();
                vectorIndex.setIndexArn(table.getTableArn() + "/index/" + vectorIndex.getIndexName());
                created.add(vectorIndex);
            }
            table.setVectorIndexes(created);
        }

        tableStore.put(storageKey, table);
        itemsByTable.put(scopedItemsKey(storageKey), new ConcurrentSkipListMap<>());
        LOG.infov("Created table: {0} in region {1}", tableName, region);
        return table;
    }

    private void validateGsiKeySchemaArity(GlobalSecondaryIndex gsi) {
        long hashCount = gsi.getKeySchema().stream().filter(k -> "HASH".equals(k.getKeyType())).count();
        long rangeCount = gsi.getKeySchema().stream().filter(k -> "RANGE".equals(k.getKeyType())).count();
        if (hashCount > MAX_MULTI_ATTRIBUTE_KEY_PART_SIZE) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Global secondary index "
                    + gsi.getIndexName() + " must not have more than "
                    + MAX_MULTI_ATTRIBUTE_KEY_PART_SIZE + " partition key (HASH) attributes", 400);
        }
        if (rangeCount > MAX_MULTI_ATTRIBUTE_KEY_PART_SIZE) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Global secondary index "
                    + gsi.getIndexName() + " must not have more than "
                    + MAX_MULTI_ATTRIBUTE_KEY_PART_SIZE + " sort key (RANGE) attributes", 400);
        }
    }

    /**
     * Validates each vector index a request adds against its own request model, the
     * "N validation errors detected" family. AWS answers these before every other check on the
     * request, including the online index limit an UpdateTable is held to.
     *
     * @param newIndexes the indexes the request adds, each with its request-model member path
     */
    private static void validateVectorIndexMembers(List<VectorIndexCreate> newIndexes) {
        // The required members are reported before the length and range constraints.
        for (VectorIndexCreate create : newIndexes) {
            VectorIndex index = create.index();
            String memberPath = create.memberPath();
            requireVectorIndexMember(index.getDimensions(), memberPath + ".dimensions");
            requireVectorIndexMember(index.getVectorAttributeName(), memberPath + ".vectorAttribute");
            requireVectorIndexMember(index.getProjectionType(), memberPath + ".projection");
            requireVectorIndexMember(index.getDistanceFunction(), memberPath + ".distanceFunction");
            String indexName = index.getIndexName();
            if (indexName == null || indexName.length() < MIN_VECTOR_INDEX_NAME_LENGTH) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + indexName + "' at '"
                        + memberPath + ".indexName' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to " + MIN_VECTOR_INDEX_NAME_LENGTH, 400);
            }
            String distanceFunction = index.getDistanceFunction();
            if (!VECTOR_DISTANCE_FUNCTIONS.contains(distanceFunction)) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + distanceFunction + "' at '"
                        + memberPath + ".distanceFunction' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: " + VECTOR_DISTANCE_FUNCTIONS, 400);
            }
            Long dimensions = index.getDimensions();
            if (dimensions < 1) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + dimensions + "' at '"
                        + memberPath + ".dimensions' failed to satisfy constraint: "
                        + "Member must have value greater than or equal to 1", 400);
            }
        }
    }

    /**
     * Validates the vector indexes a request adds against the table they join, the
     * "One or more parameter values were invalid" family. Runs after
     * {@link #validateVectorIndexMembers}, so every index here carries its required members.
     *
     * @param newIndexes           the indexes the request adds
     * @param existingIndexes      the indexes already on the table, empty on CreateTable
     * @param attributeDefinitions every definition a SearchSchema element may resolve against
     * @param billingMode          the billing mode the table will have, null when the request
     *                             leaves it at the PROVISIONED default
     */
    private static void validateVectorIndexes(List<VectorIndexCreate> newIndexes,
                                              List<VectorIndex> existingIndexes,
                                              List<AttributeDefinition> attributeDefinitions,
                                              String billingMode) {
        if (newIndexes.isEmpty()) {
            return;
        }

        if (existingIndexes.size() + newIndexes.size() > MAX_VECTOR_INDEXES_PER_TABLE) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: VectorIndex count exceeds "
                    + "the per-table limit of " + MAX_VECTOR_INDEXES_PER_TABLE, 400);
        }

        if (!"PAY_PER_REQUEST".equals(billingMode)) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Vector indexes are only supported "
                    + "for PAY_PER_REQUEST tables", 400);
        }

        Set<String> definedAttrs = attributeDefinitions == null
                ? Set.of()
                : attributeDefinitions.stream()
                        .map(AttributeDefinition::getAttributeName)
                        .collect(Collectors.toSet());

        Map<String, Long> dimensionsByVectorAttribute = new HashMap<>();
        for (VectorIndex existing : existingIndexes) {
            dimensionsByVectorAttribute.put(existing.getVectorAttributeName(), existing.getDimensions());
        }

        for (VectorIndexCreate create : newIndexes) {
            VectorIndex index = create.index();
            Long dimensions = index.getDimensions();
            if (dimensions > MAX_VECTOR_DIMENSIONS) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Number of dimensions must be "
                        + "between 1 and " + MAX_VECTOR_DIMENSIONS + " inclusive.", 400);
            }
            for (String searchSchemaAttr : index.getSearchSchemaAttributeNames()) {
                if (!definedAttrs.contains(searchSchemaAttr)) {
                    throw new AwsException("ValidationException",
                            "One or more parameter values were invalid: One element in SearchSchema "
                            + "is not defined in attribute definitions", 400);
                }
            }
            String vectorAttribute = index.getVectorAttributeName();
            Long seen = dimensionsByVectorAttribute.putIfAbsent(vectorAttribute, dimensions);
            if (seen != null && !seen.equals(dimensions)) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Conflicting attribute definition "
                        + "for '" + vectorAttribute + "'. All VectorIndexes on the same vector "
                        + "attribute must use the same dimensions.", 400);
            }
        }
    }

    private static void requireVectorIndexMember(Object value, String memberPath) {
        if (value == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + memberPath
                    + "' failed to satisfy constraint: Member must not be null", 400);
        }
    }

    public TableDefinition describeTable(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        settleVectorIndexes(storageKey, table);

        // Update dynamic counts
        var items = itemsByTable.get(scopedItemsKey(storageKey));
        if (items != null) {
            table.setItemCount(items.size());
        }
        return table;
    }

    /**
     * Advances a vector index added by UpdateTable through its two phases on read, the way
     * {@code AcmService} settles a pending certificate. Nothing else moves the index along, so
     * a caller polling DescribeTable is what makes it progress.
     *
     * <p>The table leaves UPDATING at the allocation/backfill boundary, together with the switch
     * from {@code Backfilling: false} to {@code Backfilling: true}, which is what AWS reports.
     */
    private void settleVectorIndexes(String storageKey, TableDefinition table) {
        List<VectorIndex> indexes = table.getVectorIndexes();
        if (indexes.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        boolean changed = false;
        boolean anySettling = false;
        boolean anyAllocating = false;
        for (VectorIndex index : indexes) {
            if (index.getCreationStartedAt() == null || !"CREATING".equals(index.getIndexStatus())) {
                continue;
            }
            anySettling = true;
            Instant backfillStart = index.getCreationStartedAt().plusSeconds(vectorIndexAllocationSeconds);
            Instant activeAt = backfillStart.plusSeconds(vectorIndexBackfillSeconds);
            if (!now.isBefore(activeAt)) {
                index.setIndexStatus("ACTIVE");
                index.setCreationStartedAt(null);
                changed = true;
            } else if (now.isBefore(backfillStart)) {
                anyAllocating = true;
            }
        }
        // Only a table this method is driving may leave UPDATING here: another caller's
        // UPDATING is not this method's to clear.
        if (anySettling && !anyAllocating && "UPDATING".equals(table.getTableStatus())) {
            table.setTableStatus("ACTIVE");
            changed = true;
        }
        if (changed) {
            tableStore.put(storageKey, table);
        }
    }

    /**
     * Whether the index is past resource allocation and still building, which is the only state
     * where AWS reports {@code Backfilling} at all. A settle must have run first.
     */
    public boolean isVectorIndexBackfilling(VectorIndex index) {
        return reportsVectorIndexBackfilling(index) && !Instant.now().isBefore(
                index.getCreationStartedAt().plusSeconds(vectorIndexAllocationSeconds));
    }

    /**
     * Whether {@code Backfilling} is reported for this index at all. A vector index created with
     * the table never reports it; one added by UpdateTable does until it reaches ACTIVE.
     */
    public boolean reportsVectorIndexBackfilling(VectorIndex index) {
        return index.getCreationStartedAt() != null && "CREATING".equals(index.getIndexStatus());
    }

    /**
     * The table a SearchVectors runs against, with its vector indexes settled. Skips the
     * {@link #describeTable} item-count refresh, which is {@code O(items)} and which the
     * SearchVectors response never reports.
     */
    public TableDefinition settledTable(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        settleVectorIndexes(storageKey, table);
        return table;
    }

    /**
     * Every live item of the table, expired ones dropped. SearchVectors reads the whole table:
     * it has no page boundary and no cursor, so it cannot go through {@link #scan}, which stops
     * at the 1 MB response cap.
     */
    public List<JsonNode> liveItems(TableDefinition table, String region) {
        String storageKey = regionKey(region, table.getTableName());
        ConcurrentSkipListMap<String, JsonNode> items = itemsByTable.get(scopedItemsKey(storageKey));
        if (items == null) {
            return List.of();
        }
        return items.values().stream()
                .filter(item -> !isExpired(item, table))
                .toList();
    }

    /**
     * The stored table definition without the {@link #describeTable} item-count refresh,
     * which is {@code O(items)} on the item map. For callers that only need static metadata
     * such as the key schema — notably IAM condition-key resolution, which runs on the
     * request hot path before the request is even authorized.
     *
     * @return the definition, or empty when no such table exists in the region
     */
    public Optional<TableDefinition> findTable(String tableName, String region) {
        String storageKey = regionKey(region, canonicalTableName(region, tableName));
        return tableStore.get(storageKey);
    }

    public void persistTable(String tableName, TableDefinition table, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        tableStore.put(regionKey(region, canonicalTableName), table);
    }

    /**
     * Turns on the table's stream and persists the result, so DescribeTable reports
     * StreamSpecification / LatestStreamArn and event source mappings can find the stream.
     *
     * <p>Callers that reach DynamoDB through the service rather than the JSON handler — notably
     * CloudFormation provisioning — need this: the handler enables the stream inline on
     * CreateTable/UpdateTable, and without an equivalent entry point a table created by any other
     * path is left streamless. A null {@code viewType} falls back to
     * {@link #DEFAULT_STREAM_VIEW_TYPE}, matching the leniency the JSON handler already applies
     * rather than any documented AWS default.
     *
     * @return the updated table, or the unchanged table when no stream service is wired.
     */
    public TableDefinition enableStream(String tableName, String viewType, String region) {
        TableDefinition table = describeTable(tableName, region);
        if (streamService == null) {
            return table;
        }
        String effectiveViewType = (viewType == null || viewType.isBlank())
                ? DEFAULT_STREAM_VIEW_TYPE
                : viewType;
        StreamDescription sd = streamService.enableStream(
                table.getTableName(), table.getTableArn(), effectiveViewType, region);
        table.setStreamEnabled(true);
        table.setStreamArn(sd.getStreamArn());
        table.setStreamViewType(effectiveViewType);
        persistTable(tableName, table, region);
        return table;
    }

    /**
     * Turns the table's stream off and persists the result, so it stops producing records.
     *
     * <p>The counterpart to {@link #enableStream}, for the same service-layer callers. The stream
     * ARN is retained on the table, matching what UpdateTable does through the JSON handler: AWS
     * keeps reporting {@code LatestStreamArn} for a disabled stream.
     *
     * @return the updated table, or the unchanged table when no stream service is wired.
     */
    public TableDefinition disableStream(String tableName, String region) {
        TableDefinition table = describeTable(tableName, region);
        if (streamService == null) {
            return table;
        }
        streamService.disableStream(table.getTableName(), region);
        table.setStreamEnabled(false);
        persistTable(tableName, table, region);
        return table;
    }

    public void deleteTable(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        var table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        requireNotCreating(table);
        settleVectorIndexes(storageKey, table);
        if (table.getVectorIndexes().stream().anyMatch(v -> "CREATING".equals(v.getIndexStatus()))) {
            throw new AwsException("ResourceInUseException",
                    "Attempt to change a resource which is still in use: Cannot delete table while "
                    + "indexes are being created, updated, or deleted.", 400);
        }
        tableStore.delete(storageKey);
        itemsByTable.remove(scopedItemsKey(storageKey));
        itemLocks.remove(scopedItemsKey(storageKey));
        if (itemStore != null) {
            itemStore.delete(storageKey);
        }
        if (streamService != null) {
            streamService.deleteStream(canonicalTableName, region);
        }
        if (kinesisForwarder != null) {
            // Discard any buffered CDC records and stop draining: the destination stream is gone.
            kinesisForwarder.onTableDeleted(regionResolver.getAccountId(), region, canonicalTableName);
        }
        LOG.infov("Deleted table: {0}", canonicalTableName);
    }

    /**
     * Notify the CDC forwarder that a Kinesis streaming destination was disabled so it discards any
     * buffered records for it and stops draining. {@code tableName} must be the resolved (canonical)
     * table name, the same value the forward path keys destination state on. No-op without a forwarder.
     */
    public void onKinesisStreamingDestinationDisabled(String tableName, String streamArn, String region) {
        if (kinesisForwarder != null) {
            kinesisForwarder.onDestinationDisabled(regionResolver.getAccountId(), region, tableName, streamArn);
        }
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

    public void putItem(String tableName, JsonNode item, String region) {
        putItem(tableName, item, null, null, null, region, "NONE");
    }

    /** Returns the previous item stored under the same key, or null when the put inserted. */
    public JsonNode putItem(String tableName, JsonNode item,
                         String conditionExpression,
                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                         String region, String returnValuesOnConditionCheckFailure) {
        var canonicalTableName = canonicalTableName(region, tableName);
        requireActiveTable(regionKey(region, canonicalTableName));
        return putItemInternal(tableName, item, conditionExpression, exprAttrNames, exprAttrValues,
                        region, returnValuesOnConditionCheckFailure, true);
    }

    private JsonNode putItemInternal(String tableName, JsonNode item,
                                  String conditionExpression,
                                  JsonNode exprAttrNames, JsonNode exprAttrValues,
                                  String region, String returnValuesOnConditionCheckFailure,
                                  boolean shouldPersist) {
        return putItemInternal(tableName, item, conditionExpression, exprAttrNames, exprAttrValues,
                               region, returnValuesOnConditionCheckFailure, shouldPersist, null);
    }

    private JsonNode putItemInternal(String tableName, JsonNode item,
                                  String conditionExpression,
                                  JsonNode exprAttrNames, JsonNode exprAttrValues,
                                  String region, String returnValuesOnConditionCheckFailure,
                                  boolean shouldPersist,
                                  Consumer<Runnable> deferredStreamEvents) {
        return putItemInternal(tableName, item, conditionExpression, exprAttrNames, exprAttrValues,
                region, returnValuesOnConditionCheckFailure, shouldPersist, deferredStreamEvents, null);
    }

    private JsonNode putItemInternal(String tableName, JsonNode item,
                                  String conditionExpression,
                                  JsonNode exprAttrNames, JsonNode exprAttrValues,
                                  String region, String returnValuesOnConditionCheckFailure,
                                  boolean shouldPersist,
                                  Consumer<Runnable> deferredStreamEvents,
                                  Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(DynamoDbService::itemCallResourceNotFound);

        // Validate and normalize all number attributes before storage
        final JsonNode normalizedItem = DynamoDbNumberUtils.normalizeNumbersInItem(item);
        DynamoDbItemSize.validateSize(normalizedItem);
        String itemKey = buildItemKey(table, normalizedItem);
        validateIndexKeyTypes(table, normalizedItem, false);

        return withItemLock(storageKey, itemKey, () -> {
            var tableItems = itemsFor(storageKey, stagedItems, true);

            JsonNode existing = tableItems.get(itemKey);

            if (conditionExpression != null) {
                evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            }
            requireItemNestingWithinLimit(normalizedItem);

            tableItems.put(itemKey, normalizedItem);
            if (shouldPersist) {
                persistItems(storageKey);
            }
            LOG.debugv("Put item in {0}: key={1}", canonicalTableName, itemKey);
            LOG.tracev("Put item in {0}: key={1} item={2}", canonicalTableName, itemKey, item);

            String eventName = existing == null ? "INSERT" : "MODIFY";
            // Captured in request scope on purpose: this event may be deferred to the batch drain,
            // and resolving the account inside the lambda would fall back to the default account,
            // which is the ambient-account bug this commit exists to remove.
            String ownerAccountId = regionResolver.getAccountId();
            Runnable streamEvent = () -> {
                if (streamService != null) {
                    streamService.captureEvent(canonicalTableName, eventName, existing, item, table, region);
                }
                if (kinesisForwarder != null) {
                    kinesisForwarder.forward(eventName, existing, item, table, region, ownerAccountId);
                }
            };
            if (deferredStreamEvents != null) {
                deferredStreamEvents.accept(streamEvent);
            } else {
                streamEvent.run();
            }
            return existing;
        });
    }

    public JsonNode getItem(String tableName, JsonNode key, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = requireActiveTable(storageKey);

        String itemKey = buildItemKey(table, key, true);
        var items = currentItems(storageKey, false);
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

    public JsonNode deleteItem(String tableName, JsonNode key, String region) {
        return deleteItem(tableName, key, null, null, null, region, "NONE");
    }

    public JsonNode deleteItem(String tableName, JsonNode key,
                                String conditionExpression,
                                JsonNode exprAttrNames, JsonNode exprAttrValues,
                                String region, String returnValuesOnConditionCheckFailure) {
        return deleteItemInternal(tableName, key, conditionExpression, exprAttrNames, exprAttrValues,
                                  region, returnValuesOnConditionCheckFailure, true);
    }

    private JsonNode deleteItemInternal(String tableName, JsonNode key,
                                         String conditionExpression,
                                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                                         String region, String returnValuesOnConditionCheckFailure,
                                         boolean shouldPersist) {
        return deleteItemInternal(tableName, key, conditionExpression, exprAttrNames, exprAttrValues,
                                  region, returnValuesOnConditionCheckFailure, shouldPersist, null);
    }

    private JsonNode deleteItemInternal(String tableName, JsonNode key,
                                         String conditionExpression,
                                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                                         String region, String returnValuesOnConditionCheckFailure,
                                         boolean shouldPersist,
                                         Consumer<Runnable> deferredStreamEvents) {
        return deleteItemInternal(tableName, key, conditionExpression, exprAttrNames, exprAttrValues,
                region, returnValuesOnConditionCheckFailure, shouldPersist, deferredStreamEvents, null);
    }

    private JsonNode deleteItemInternal(String tableName, JsonNode key,
                                         String conditionExpression,
                                         JsonNode exprAttrNames, JsonNode exprAttrValues,
                                         String region, String returnValuesOnConditionCheckFailure,
                                         boolean shouldPersist,
                                         Consumer<Runnable> deferredStreamEvents,
                                         Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = requireActiveTable(storageKey);

        String itemKey = buildItemKey(table, key, true);

        return withItemLock(storageKey, itemKey, () -> {
            var items = itemsFor(storageKey, stagedItems, false);
            if (items == null) return null;

            if (conditionExpression != null) {
                JsonNode existing = items.get(itemKey);
                evaluateCondition(existing, conditionExpression, exprAttrNames, exprAttrValues, returnValuesOnConditionCheckFailure);
            }

            JsonNode removed = items.remove(itemKey);
            if (shouldPersist) {
                persistItems(storageKey);
            }
            LOG.debugv("Deleted item from {0}: key={1}", canonicalTableName, itemKey);
            LOG.tracev("Deleted item from {0}: key={1} removed={2}", canonicalTableName, itemKey, removed);

            if (removed != null) {
                // Captured in request scope on purpose: this event may be deferred to the batch drain,
                // and resolving the account inside the lambda would fall back to the default account,
                // which is the ambient-account bug this commit exists to remove.
                String ownerAccountId = regionResolver.getAccountId();
                Runnable streamEvent = () -> {
                    if (streamService != null) {
                        streamService.captureEvent(canonicalTableName, "REMOVE", removed, null, table, region);
                    }
                    if (kinesisForwarder != null) {
                        kinesisForwarder.forward("REMOVE", removed, null, table, region, ownerAccountId);
                    }
                };
                if (deferredStreamEvents != null) {
                    deferredStreamEvents.accept(streamEvent);
                } else {
                    streamEvent.run();
                }
            }

            return removed;
        });
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
        return updateItem(tableName, key, attributeUpdates, updateExpression, expressionAttrNames,
                expressionAttrValues, returnValues, conditionExpression, region,
                returnValuesOnConditionCheckFailure, UpdateSizeRule.UPDATE_ITEM);
    }

    UpdateResult updateItem(String tableName, JsonNode key, JsonNode attributeUpdates,
                                    String updateExpression,
                                    JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                    String returnValues, String conditionExpression, String region,
                                    String returnValuesOnConditionCheckFailure,
                                    UpdateSizeRule sizeRule) {
        return updateItemInternal(tableName, key, attributeUpdates, updateExpression,
                expressionAttrNames, expressionAttrValues, returnValues,
                conditionExpression, region, returnValuesOnConditionCheckFailure, true, sizeRule);
    }

    private UpdateResult updateItemInternal(String tableName, JsonNode key, JsonNode attributeUpdates,
                                             String updateExpression,
                                             JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                             String returnValues, String conditionExpression, String region,
                                             String returnValuesOnConditionCheckFailure,
                                             boolean shouldPersist,
                                             UpdateSizeRule sizeRule) {
        return updateItemInternal(tableName, key, attributeUpdates, updateExpression,
                expressionAttrNames, expressionAttrValues, returnValues,
                conditionExpression, region, returnValuesOnConditionCheckFailure, shouldPersist, null,
                sizeRule);
    }

    private UpdateResult updateItemInternal(String tableName, JsonNode key, JsonNode attributeUpdates,
                                             String updateExpression,
                                             JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                             String returnValues, String conditionExpression, String region,
                                             String returnValuesOnConditionCheckFailure,
                                             boolean shouldPersist,
                                             Consumer<Runnable> deferredStreamEvents,
                                             UpdateSizeRule sizeRule) {
        return updateItemInternal(tableName, key, attributeUpdates, updateExpression,
                expressionAttrNames, expressionAttrValues, returnValues,
                conditionExpression, region, returnValuesOnConditionCheckFailure, shouldPersist,
                deferredStreamEvents, null, sizeRule);
    }

    private UpdateResult updateItemInternal(String tableName, JsonNode key, JsonNode attributeUpdates,
                                             String updateExpression,
                                             JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                                             String returnValues, String conditionExpression, String region,
                                             String returnValuesOnConditionCheckFailure,
                                             boolean shouldPersist,
                                             Consumer<Runnable> deferredStreamEvents,
                                             Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems,
                                             UpdateSizeRule sizeRule) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = requireActiveTable(storageKey);

        String itemKey = buildItemKey(table, key, true);

        return withItemLock(storageKey, itemKey, () -> {
            var items = itemsFor(storageKey, stagedItems, true);

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

            var touchedPaths = new ArrayList<String>();
            // Apply UpdateExpression (modern format: "SET #n = :val, age = :age REMOVE attr")
            if (updateExpression != null) {
                applyUpdateExpression(item, updateExpression, expressionAttrNames, expressionAttrValues, touchedPaths);
            }
            // Apply attribute updates (legacy format: AttributeUpdates)
            else if (attributeUpdates != null && attributeUpdates.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = attributeUpdates.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String attrName = entry.getKey();
                    touchedPaths.add(attrName);
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
                                            ArrayNode newArr = objectMapper.createArrayNode();
                                            for (JsonNode v : curAttr.get(setType)) {
                                                if (!removeSet.contains(v.asText())) newArr.add(v);
                                            }
                                            if (newArr.isEmpty()) {
                                                item.remove(attrName);
                                            } else {
                                                ((ObjectNode) curAttr).set(setType, newArr);
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
                                    BigDecimal delta = new BigDecimal(value.get("N").asText());
                                    BigDecimal current = curAttr != null && curAttr.has("N")
                                            ? new BigDecimal(curAttr.get("N").asText()) : BigDecimal.ZERO;
                                    var sum = current.add(delta);
                                    DynamoDbNumberUtils.checkArithmeticResult(sum);
                                    ObjectNode numNode = objectMapper.createObjectNode();
                                    numNode.put("N", sum.stripTrailingZeros().toPlainString());
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
                                                ArrayNode newArr = objectMapper.createArrayNode();
                                                for (String s : existingSet) newArr.add(s);
                                                ((ObjectNode) curAttr).set(setType, newArr);
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
            validateKeyNotModified(table, key, item);
            requireItemNestingWithinLimit(item);

            // AWS validates index key values against the item the update produces.
            validateIndexKeyTypes(table, item, true);

            if (sizeRule == UpdateSizeRule.UPDATE_ITEM) {
                requireUpdateItemWithinSizeLimit(item,
                        writtenAttributes(touchedPaths, updateExpression, expressionAttrNames),
                        updateExpression != null
                                ? DynamoDbItemSize.updateExpressionCost(updateExpression)
                                : DynamoDbItemSize.attributeUpdatesCost(attributeUpdates));
            } else {
                requireUpdatedItemWithinSizeLimit(item);
            }

            items.put(itemKey, item);
            if (shouldPersist) {
                persistItems(storageKey);
            }
            LOG.tracev("Updated item in {0}: key={1} updateExpression={2} item={3}",
                    canonicalTableName, itemKey, updateExpression, item);

            // Captured in request scope on purpose: this event may be deferred to the batch drain,
            // and resolving the account inside the lambda would fall back to the default account,
            // which is the ambient-account bug this commit exists to remove.
            String ownerAccountId = regionResolver.getAccountId();
            Runnable streamEvent = () -> {
                if (streamService != null) {
                    streamService.captureEvent(canonicalTableName, "MODIFY", existing, item, table, region);
                }
                if (kinesisForwarder != null) {
                    kinesisForwarder.forward("MODIFY", existing, item, table, region, ownerAccountId);
                }
            };
            if (deferredStreamEvents != null) {
                deferredStreamEvents.accept(streamEvent);
            } else {
                streamEvent.run();
            }

            List<TouchedPath> touched = new ArrayList<>();
            for (String path : touchedPaths) {
                List<Object> tokens = pathTokens(path, updateExpression, expressionAttrNames);
                touched.add(new TouchedPath(tokens,
                        existing == null ? null : valueAtTokens(existing, tokens), valueAtTokens(item, tokens)));
            }
            return new UpdateResult(item, existing, touched);
        });
    }

    private List<Object> pathTokens(String path, String updateExpression, JsonNode expressionAttrNames) {
        return updateExpression != null ? parsePath(path, expressionAttrNames) : List.<Object>of(path);
    }

    private Set<String> writtenAttributes(List<String> touchedPaths, String updateExpression,
                                           JsonNode expressionAttrNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String path : touchedPaths) {
            List<Object> tokens = pathTokens(path, updateExpression, expressionAttrNames);
            if (!tokens.isEmpty() && tokens.get(0) instanceof String name) {
                names.add(name);
            }
        }
        return names;
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
        TableDefinition table = requireActiveTable(storageKey);

        DynamoDbAccessPath accessPath = DynamoDbAccessPath.resolve(table, indexName);
        String partitionKeyValuePlaceholder = DynamoDbAccessPathValidator.validateQuery(
                table, accessPath, keyConditions, keyConditionExpression, filterExpression,
                null, exprAttrNames, expressionAttrValues);
        validateExclusiveStartKeyWithinQuery(exclusiveStartKey, table, accessPath, keyConditions,
                keyConditionExpression, expressionAttrValues, exprAttrNames);
        String pkName = accessPath.partitionKeyName();
        List<String> pkNames = accessPath.partitionKeyNames();
        String skName = accessPath.sortKeyName();
        List<String> sortKeyNames = accessPath.sortKeyNames();

        var items = itemsByTable.get(scopedItemsKey(storageKey));
        if (items == null) return new QueryResult(List.of(), 0, 0, null, List.of());

        List<JsonNode> results = new ArrayList<>();

        if (keyConditions != null) {
            for (JsonNode item : items.values()) {
                boolean partitionKeyMatches = pkNames.stream().allMatch(name -> item.has(name)
                        && matchesAttributeValue(item.get(name), extractComparisonValue(keyConditions.get(name))));
                if (!partitionKeyMatches) continue;
                if (skName != null && keyConditions.has(skName)) {
                    JsonNode skCondition = keyConditions.get(skName);
                    if (matchesKeyCondition(item.get(skName), skCondition)) {
                        results.add(item);
                    }
                } else {
                    results.add(item);
                }
            }
        } else if (keyConditionExpression != null) {
            results = queryWithExpression(items, pkName, partitionKeyValuePlaceholder,
                    keyConditionExpression, expressionAttrValues, exprAttrNames);
        }

        // Filter out items without GSI key attributes (sparse index behavior).
        // DynamoDB excludes items from a GSI if any key attribute is null/missing.
        if (accessPath.isIndex()) {
            Set<String> indexKeys = accessPath.keyAttributeNames();
            results = results.stream()
                    .filter(item -> indexKeys.stream().allMatch(
                            key -> item.has(key) && hasNonNullAttribute(item, key)))
                    .toList();
        }

        // Filter out TTL-expired items
        results = results.stream().filter(item -> !isExpired(item, table)).toList();

        // Sort by the full (possibly composite) sort key, comparing each attribute in key-schema
        // order. Using only the first sort-key attribute would ignore the remaining components
        // (e.g. a "requestStateQuery RANGE, createdAt RANGE" index would never order by createdAt),
        // which in turn breaks ScanIndexForward=false. See floci-io/floci#1675.
        if (!sortKeyNames.isEmpty()) {
            List<String> finalSortKeyNames = sortKeyNames;
            results = new ArrayList<>(results);
            results.sort((a, b) -> {
                for (String name : finalSortKeyNames) {
                    JsonNode aAttr = a.get(name);
                    JsonNode bAttr = b.get(name);
                    int cmp;
                    if (aAttr == null && bAttr == null) cmp = 0;
                    else if (aAttr == null) cmp = -1;
                    else if (bAttr == null) cmp = 1;
                    else cmp = ExpressionEvaluator.compareAttributeValues(aAttr, bAttr);
                    if (cmp != 0) return cmp;
                }
                return 0;
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
                    : buildItemKeyFromNode(exclusiveStartKey, pkName, sortKeyNames);

            int startIdx = -1;
            for (int i = 0; i < results.size(); i++) {
                String thisKey = hasTableKeys
                        ? buildItemKeyFromNode(results.get(i), tablePkName, tableSkName)
                        : buildItemKeyFromNode(results.get(i), pkName, sortKeyNames);
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

        // Stop at whichever boundary the read reaches first: the 1 MB cap or Limit.
        // The size check comes first for each item — per the Query API reference,
        // "if the processed dataset size exceeds 1 MB before DynamoDB reaches this
        // limit, it stops the operation", so an item that would cross the 1 MB cap
        // is not read and does not count toward Limit. DynamoDB does not look ahead
        // either way: a read that stops at a boundary always returns a
        // LastEvaluatedKey, even when the boundary happens to be the last item of
        // the result set ("the absence of LastEvaluatedKey is the only way to know
        // that you have reached the end of the result set").
        final int MAX_RESPONSE_BYTES = 1024 * 1024;
        int accSize = 0;
        int included = -1; // index one past the last included item; -1 = no boundary hit
        for (int i = 0; i < evaluatedItems.size(); i++) {
            int sz = readItemSize(evaluatedItems.get(i), accessPath, table);
            if (accSize > 0 && accSize + sz > MAX_RESPONSE_BYTES) {
                included = i; // the 1 MB cap stops the read BEFORE this item
                break;
            }
            accSize += sz;
            if (limit != null && limit > 0 && i + 1 >= limit) {
                included = i + 1; // the Limit cap stops the read AFTER this item
                break;
            }
        }
        if (included > 0) {
            lastEvaluatedKey = buildKeyNode(table, evaluatedItems.get(included - 1), pkName, sortKeyNames, indexName != null);
            evaluatedItems = new ArrayList<>(evaluatedItems.subList(0, included));
        }

        int scannedCount = evaluatedItems.size();
        List<JsonNode> scannedItems = evaluatedItems;

        if (filterExpression != null) {
            evaluatedItems = evaluatedItems.stream()
                    .filter(item -> matchesFilterExpression(item, filterExpression,
                            exprAttrNames, expressionAttrValues))
                    .toList();
        }

        LOG.tracev("Query on {0}: returned={1} scanned={2}",
                canonicalTableName, evaluatedItems.size(), scannedCount);
        return new QueryResult(evaluatedItems, scannedCount, accSize, lastEvaluatedKey, scannedItems);
    }

    public ScanResult scan(String tableName, String filterExpression,
                            JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                            JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey, String region) {
        return scan(tableName, filterExpression, expressionAttrNames, expressionAttrValues,
                scanFilter, limit, exclusiveStartKey, null, region);
    }

    public ScanResult scan(String tableName, String filterExpression,
                            JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                            JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey,
                            String indexName, String region) {
        return scan(tableName, filterExpression, expressionAttrNames, expressionAttrValues,
                scanFilter, limit, exclusiveStartKey, indexName, null, null, region);
    }

    public ScanResult scan(String tableName, String filterExpression,
                            JsonNode expressionAttrNames, JsonNode expressionAttrValues,
                            JsonNode scanFilter, Integer limit, JsonNode exclusiveStartKey,
                            String indexName, Integer segment, Integer totalSegments, String region) {
        DynamoDbReservedWords.check(filterExpression, "FilterExpression");
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = requireActiveTable(storageKey);

        DynamoDbAccessPath accessPath = DynamoDbAccessPath.resolve(table, indexName);

        var items = itemsByTable.get(scopedItemsKey(storageKey));
        if (items == null) return new ScanResult(List.of(), 0, 0, null, List.of());

        // ConcurrentSkipListMap keeps items sorted by base item key — no sort needed.
        // Use tailMap for O(log n) pagination instead of O(n) linear search.
        String pkName = table.getPartitionKeyName();
        String skName = table.getSortKeyName();

        // When scanning a secondary index, the LastEvaluatedKey must carry the index key
        // attributes in addition to the base table key. Cursor navigation still uses the
        // (unique) base table key, which is a total order even when index sort keys tie.
        boolean indexScan = accessPath.isIndex();
        String lekPkName = pkName;
        String lekSkName = skName;
        if (indexScan) {
            lekPkName = accessPath.partitionKeyName();
            lekSkName = accessPath.sortKeyName();
        }

        var source = exclusiveStartKey != null
                ? items.tailMap(buildItemKeyFromNode(exclusiveStartKey, pkName, skName), false).values()
                : items.values();

        final int MAX_RESPONSE_BYTES = 1024 * 1024;
        int totalScanned = 0;
        int accSize = 0;
        List<JsonNode> results = new ArrayList<>();
        List<JsonNode> scannedItems = new ArrayList<>();
        JsonNode lastEvaluatedKey = null;
        JsonNode lastScanned = null;
        for (JsonNode item : source) {
            // Sparse index behavior: a scan of a secondary index reads the index
            // itself, and base-table items missing any index key attribute do not
            // exist in the index — they are never read, never counted, and can
            // never anchor a cursor.
            if (indexScan && !(hasNonNullAttribute(item, lekPkName)
                    && (lekSkName == null || hasNonNullAttribute(item, lekSkName)))) {
                continue;
            }
            if (segment != null && totalSegments != null && totalSegments > 1) {
                JsonNode pkAttr = item.get(lekPkName);
                if (computeSegment(pkAttr, totalSegments) != segment) {
                    continue;
                }
            }
            // Stop at whichever boundary the read reaches first: the 1 MB cap or
            // Limit. The size check comes first — per the API reference, "if the
            // processed dataset size exceeds 1 MB before DynamoDB reaches this
            // limit, it stops the operation" — so an item that would cross the cap
            // is not read, does not count toward ScannedCount, and the cursor
            // anchors to the previous scanned item (which, with a filter, may well
            // be an item that was not returned).
            int sz = readItemSize(item, accessPath, table);
            if (accSize > 0 && accSize + sz > MAX_RESPONSE_BYTES) {
                lastEvaluatedKey = buildKeyNode(table, lastScanned, lekPkName, lekSkName, indexScan);
                break;
            }
            accSize += sz;
            totalScanned++;
            scannedItems.add(item);
            lastScanned = item;
            if (!isExpired(item, table)) {
                boolean matched = (filterExpression == null
                        || matchesFilterExpression(item, filterExpression, expressionAttrNames, expressionAttrValues))
                        && (scanFilter == null || matchesScanFilter(item, scanFilter));
                if (matched) results.add(item);
            }
            // Limit caps SCANNED items (those read), not matched items. DynamoDB does
            // not look ahead when it stops at the Limit boundary: it always surfaces a
            // cursor, even when the boundary happens to be the last item of the result
            // set. The client only learns it reached the end when a follow-up request
            // comes back without a LastEvaluatedKey.
            if (limit != null && limit > 0 && totalScanned >= limit) {
                lastEvaluatedKey = buildKeyNode(table, item, lekPkName, lekSkName, indexScan);
                break;
            }
        }

        LOG.tracev("Scan on {0}: returned={1} scanned={2}",
                canonicalTableName, results.size(), totalScanned);
        return new ScanResult(results, totalScanned, accSize, lastEvaluatedKey, scannedItems);
    }

    // A read served by a KEYS_ONLY or INCLUDE index is sized on the projection the
    // index stores, not on the full base item. Characterised on real AWS (us-east-1,
    // 2026-09-05): querying a 20KB item through a KEYS_ONLY GSI costs 0.5 units.
    private int readItemSize(JsonNode item, DynamoDbAccessPath accessPath, TableDefinition table) {
        if (!accessPath.isIndex() || "ALL".equals(accessPath.projectionType())) {
            return DynamoDbItemSize.calculateItemSize(item);
        }
        return DynamoDbItemSize.calculateItemSize(ProjectionEvaluator.trimToAttributes(
                (ObjectNode) item, accessPath.projectedAttributeNames(table)));
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
        // Pre-validate all write requests before applying any mutation to memory or streams
        for (Map.Entry<String, List<JsonNode>> entry : requestItems.entrySet()) {
            String tableName = canonicalTableName(region, entry.getKey());
            String storageKey = regionKey(region, tableName);
            TableDefinition table = requireActiveTable(storageKey);
            Set<String> seenKeys = new HashSet<>();
            for (JsonNode writeRequest : entry.getValue()) {
                String itemKey;
                if (writeRequest.has("PutRequest")) {
                    JsonNode item = writeRequest.get("PutRequest").get("Item");
                    if (item == null) {
                        throw new AwsException("ValidationException", "Item is required for PutRequest", 400);
                    }
                    JsonNode normalizedItem = DynamoDbNumberUtils.normalizeNumbersInItem(item);
                    DynamoDbItemSize.validateSize(normalizedItem);
                    itemKey = buildItemKey(table, normalizedItem, KeySurface.BATCH_WRITE);
                    validateIndexKeyTypes(table, normalizedItem, false);
                } else if (writeRequest.has("DeleteRequest")) {
                    JsonNode key = writeRequest.get("DeleteRequest").get("Key");
                    if (key == null) {
                        throw new AwsException("ValidationException", "Key is required for DeleteRequest", 400);
                    }
                    itemKey = buildItemKey(table, key, KeySurface.BATCH_WRITE);
                } else {
                    continue;
                }
                if (!seenKeys.add(itemKey)) {
                    throw new AwsException("ValidationException",
                            "Provided list of item keys contains duplicates", 400);
                }
            }
        }

        Set<String> affectedStorageKeys = new LinkedHashSet<>();
        try {
            for (Map.Entry<String, List<JsonNode>> entry : requestItems.entrySet()) {
                String tableName = canonicalTableName(region, entry.getKey());
                String storageKey = regionKey(region, tableName);
                for (JsonNode writeRequest : entry.getValue()) {
                    if (writeRequest.has("PutRequest")) {
                        JsonNode item = writeRequest.get("PutRequest").get("Item");
                        putItemInternal(tableName, item, null, null, null, region, "NONE", false);
                        affectedStorageKeys.add(storageKey);
                    } else if (writeRequest.has("DeleteRequest")) {
                        JsonNode key = writeRequest.get("DeleteRequest").get("Key");
                        deleteItemInternal(tableName, key, null, null, null, region, "NONE", false);
                        affectedStorageKeys.add(storageKey);
                    }
                }
            }
        } finally {
            for (String storageKey : affectedStorageKeys) {
                persistItems(storageKey);
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

    public TransactWriteResult transactWriteItems(List<JsonNode> transactItems, String region,
                                                  String clientRequestToken, JsonNode rawRequest) {
        // Idempotency check via ClientRequestToken — AWS contract:
        //   * Same token + identical request body  → no-op success (silently dedupe).
        //   * Same token + different request body  → IdempotentParameterMismatchException.
        //   * No token, or expired token           → proceed normally.
        if (clientRequestToken == null || clientRequestToken.isEmpty() || rawRequest == null) {
            return new TransactWriteResult(applyTransactWrite(transactItems, region).writeCapacity(), false);
        }
        String cacheKey = regionResolver.getAccountId() + "::" + region + "::" + clientRequestToken;
        String requestHash = sha256(rawRequest.toString());
        for (;;) {
            long nowNanos = System.nanoTime();
            IdempotencyEntry fresh = new IdempotencyEntry(requestHash, nowNanos, new CompletableFuture<>());
            // compute() is used so a concurrent replay with the same body collapses onto the
            // same entry without double-applying writes.
            IdempotencyEntry registered = txIdempotency.compute(cacheKey, (k, v) ->
                    v != null && nowNanos - v.insertedAtNanos() <= TX_IDEMPOTENCY_TTL_NANOS ? v : fresh);
            if (!registered.requestHash().equals(requestHash)) {
                throw new AwsException("IdempotentParameterMismatchException",
                        "Request parameters do not match those of an in-flight or recent transaction using the same ClientRequestToken",
                        400);
            }
            if (registered == fresh) {
                // Best-effort eviction of stale entries.
                txIdempotency.entrySet().removeIf(e -> nowNanos - e.getValue().insertedAtNanos() > TX_IDEMPOTENCY_TTL_NANOS);
                try {
                    AppliedTransactWrite applied = applyTransactWrite(transactItems, region);
                    fresh.replayCapacity().complete(applied.replayCapacity());
                    return new TransactWriteResult(applied.writeCapacity(), false);
                } catch (RuntimeException e) {
                    // AWS keeps no token for a call that fails, so a retry runs the transaction again.
                    txIdempotency.remove(cacheKey, fresh);
                    fresh.replayCapacity().completeExceptionally(e);
                    throw e;
                }
            }
            // A replay that arrives while the first call is still running waits for its result.
            LOG.debugv("transactWriteItems: idempotent replay for token={0}", clientRequestToken);
            try {
                return new TransactWriteResult(registered.replayCapacity().join(), true);
            } catch (CompletionException e) {
                LOG.debugv("transactWriteItems: first call for token={0} failed, running it again", clientRequestToken);
            }
        }
    }

    private record AppliedTransactWrite(Map<String, DynamoDbWriteCapacity.Cost> writeCapacity,
                                        Map<String, DynamoDbWriteCapacity.Cost> replayCapacity) {}

    private AppliedTransactWrite applyTransactWrite(List<JsonNode> transactItems, String region) {
        // Acquire every participant's item lock in a deterministic (storageKey, itemKey)
        // order before evaluating conditions or applying writes. Total-ordered acquisition
        // prevents deadlock across concurrent transactions; ReentrantLock lets the inner
        // putItem/updateItem/deleteItem calls re-enter the same lock for free.
        //
        // Ordering compares storageKey and itemKey as separate tuple fields. The item key's
        // PK/SK segments are escaped before they are joined, so delimiter bytes inside a
        // user-supplied key cannot collapse distinct transaction participants.
        TreeMap<TransactParticipant, ReentrantLock> toAcquire = new TreeMap<>(PARTICIPANT_ORDER);
        Set<TransactParticipant> seenParticipants = new HashSet<>();
        List<TransactParticipant> memberParticipants = new ArrayList<>();
        for (JsonNode transactItem : transactItems) {
            TransactParticipant p = resolveParticipant(transactItem, region);
            memberParticipants.add(p);
            if (p == null) continue;
            if (!seenParticipants.add(p)) {
                throw new AwsException("ValidationException",
                        "Transaction request cannot include multiple operations on one item", 400);
            }
            toAcquire.putIfAbsent(p, lockFor(p.storageKey, p.itemKey));
        }

        List<ReentrantLock> acquired = new ArrayList<>(toAcquire.size());
        try {
            for (ReentrantLock lock : toAcquire.values()) {
                lock.lock();
                acquired.add(lock);
            }

            List<Runnable> pendingStreamEvents = new ArrayList<>();
            Set<String> affectedStorageKeys = new LinkedHashSet<>();
            Map<String, ConcurrentSkipListMap<String, JsonNode>> staged = new HashMap<>();
            for (TransactParticipant participant : toAcquire.keySet()) {
                ConcurrentSkipListMap<String, JsonNode> stagedTable =
                        staged.computeIfAbsent(participant.storageKey(), ignored -> new ConcurrentSkipListMap<>());
                ConcurrentSkipListMap<String, JsonNode> live = itemsByTable.get(scopedItemsKey(participant.storageKey()));
                if (live != null) {
                    JsonNode existing = live.get(participant.itemKey());
                    if (existing != null) {
                        stagedTable.put(participant.itemKey(), existing);
                    }
                }
            }

            List<TransactionCanceledException.CancellationReason> cancellationReasons = new ArrayList<>();
            boolean hasFailed = false;
            for (JsonNode transactItem : transactItems) {
                var failReason = transactUpdateValueTooDeep(transactItem);
                if (failReason == null) {
                    failReason = evaluateTransactCondition(transactItem, region, staged);
                }
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

            for (int i = 0; i < transactItems.size(); i++) {
                try {
                    validateTransactItem(transactItems.get(i), region, staged);
                } catch (KeySchemaMismatchException | ItemNestingExceededException
                        | UpdatedItemTooLargeException e) {
                    throw cancelledByMember(transactItems.size(), i, e.getMessage());
                }
            }
            List<JsonNode> oldImages = stagedImages(memberParticipants, staged);
            for (JsonNode transactItem : transactItems) {
                if (transactItem.has("Put")) {
                    JsonNode put = transactItem.get("Put");
                    String tableName = put.path("TableName").asText();
                    String storageKey = regionKey(region, canonicalTableName(region, tableName));
                    putItemInternal(tableName, put.get("Item"), null, null, null, region, "NONE", false,
                            pendingStreamEvents::add, staged);
                    affectedStorageKeys.add(storageKey);
                } else if (transactItem.has("Delete")) {
                    JsonNode del = transactItem.get("Delete");
                    String tableName = del.path("TableName").asText();
                    String storageKey = regionKey(region, canonicalTableName(region, tableName));
                    deleteItemInternal(tableName, del.get("Key"), null, null, null, region, "NONE", false,
                            pendingStreamEvents::add, staged);
                    affectedStorageKeys.add(storageKey);
                } else if (transactItem.has("Update")) {
                    JsonNode upd = transactItem.get("Update");
                    String tableName = upd.path("TableName").asText();
                    String storageKey = regionKey(region, canonicalTableName(region, tableName));
                    updateItemInternal(tableName, upd.get("Key"), null,
                            upd.has("UpdateExpression") ? upd.get("UpdateExpression").asText() : null,
                            upd.has("ExpressionAttributeNames") ? upd.get("ExpressionAttributeNames") : null,
                            upd.has("ExpressionAttributeValues") ? upd.get("ExpressionAttributeValues") : null,
                            "NONE", null, region, "NONE", false, pendingStreamEvents::add, staged,
                            UpdateSizeRule.FINISHED_ITEM);
                    affectedStorageKeys.add(storageKey);
                }
            }
            List<JsonNode> newImages = stagedImages(memberParticipants, staged);
            Map<String, DynamoDbWriteCapacity.Cost> writeCapacity =
                    transactCapacity(transactItems, memberParticipants, oldImages, newImages, false);
            Map<String, DynamoDbWriteCapacity.Cost> replayCapacity =
                    transactCapacity(transactItems, memberParticipants, oldImages, newImages, true);

            // Each participant remains protected by the locks acquired above. Only participant
            // entries are committed, so concurrent writes to other items are never overwritten.
            for (TransactParticipant participant : toAcquire.keySet()) {
                ConcurrentSkipListMap<String, JsonNode> live = itemsByTable.computeIfAbsent(
                        scopedItemsKey(participant.storageKey()), ignored -> new ConcurrentSkipListMap<>());
                ConcurrentSkipListMap<String, JsonNode> stagedTable = staged.get(participant.storageKey());
                JsonNode value = stagedTable.get(participant.itemKey());
                if (value == null) {
                    live.remove(participant.itemKey());
                } else {
                    live.put(participant.itemKey(), value);
                }
            }
            for (String storageKey : affectedStorageKeys) {
                persistItems(storageKey);
            }
            for (Runnable streamEvent : pendingStreamEvents) {
                streamEvent.run();
            }
            return new AppliedTransactWrite(writeCapacity, replayCapacity);
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    private static List<JsonNode> stagedImages(List<TransactParticipant> participants,
                                               Map<String, ConcurrentSkipListMap<String, JsonNode>> staged) {
        List<JsonNode> images = new ArrayList<>();
        for (TransactParticipant participant : participants) {
            images.add(participant == null ? null : staged.get(participant.storageKey()).get(participant.itemKey()));
        }
        return images;
    }

    private Map<String, DynamoDbWriteCapacity.Cost> transactCapacity(List<JsonNode> transactItems,
            List<TransactParticipant> participants, List<JsonNode> oldImages, List<JsonNode> newImages,
            boolean replay) {
        Map<String, DynamoDbWriteCapacity.Cost> byTable = new LinkedHashMap<>();
        for (int i = 0; i < transactItems.size(); i++) {
            TransactParticipant participant = participants.get(i);
            if (participant == null) {
                continue;
            }
            JsonNode oldItem = oldImages.get(i);
            JsonNode newItem = newImages.get(i);
            DynamoDbWriteCapacity.Cost cost;
            if (replay) {
                cost = DynamoDbTransactCapacity.read(oldItem, newItem);
            } else if (transactItems.get(i).has("ConditionCheck")) {
                cost = DynamoDbTransactCapacity.conditionCheck(oldItem);
            } else {
                cost = DynamoDbTransactCapacity.write(
                        requireActiveTable(participant.storageKey()), oldItem, newItem);
            }
            byTable.merge(participant.tableName(), cost, DynamoDbWriteCapacity.Cost::plus);
        }
        return byTable;
    }

    private record TransactParticipant(String storageKey, String itemKey, String tableName) {}

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
        TableDefinition table = requireActiveTable(storageKey);
        String itemKey = buildItemKey(table, keyOrItem);
        return new TransactParticipant(storageKey, itemKey, tableName);
    }

    // AWS checks the depth of a transact Update's values as part of that member, so a too
    // deep value cancels the transaction instead of failing the request up front.
    private TransactionCanceledException.CancellationReason transactUpdateValueTooDeep(JsonNode transactItem) {
        var values = transactItem.path("Update").get("ExpressionAttributeValues");
        if (DynamoDbAttributeValueValidator.nestingWithinLimit(values)) {
            return null;
        }
        return new TransactionCanceledException.CancellationReason("ValidationError", null,
                "Nesting Levels have exceeded supported limits");
    }

    // AWS checks every member's key against the table schema, in order, before it looks for
    // duplicate items or evaluates a condition. The first mismatch cancels with that member's
    // reason alone. An empty key value still fails the whole request.
    void cancelOnKeySchemaMismatch(List<JsonNode> transactItems, String region) {
        for (int i = 0; i < transactItems.size(); i++) {
            try {
                validateTransactMemberKey(transactItems.get(i), region);
            } catch (KeySchemaMismatchException e) {
                throw cancelledByMember(transactItems.size(), i, e.getMessage());
            }
        }
    }

    private void validateTransactMemberKey(JsonNode transactItem, String region) {
        boolean isPut = transactItem.has("Put");
        JsonNode target = isPut ? transactItem.get("Put")
                : transactItem.has("Update") ? transactItem.get("Update")
                : transactItem.has("Delete") ? transactItem.get("Delete")
                : transactItem.get("ConditionCheck");
        JsonNode key = target == null ? null : target.get(isPut ? "Item" : "Key");
        if (key == null) {
            return;
        }
        String tableName = canonicalTableName(region, target.path("TableName").asText());
        TableDefinition table = requireActiveTable(regionKey(region, tableName));
        List<String> keyNames = table.getSortKeyName() == null
                ? List.of(table.getPartitionKeyName())
                : List.of(table.getPartitionKeyName(), table.getSortKeyName());
        for (String keyName : keyNames) {
            if (!key.has(keyName)) {
                throw new KeySchemaMismatchException(isPut
                        ? "One or more parameter values were invalid: Missing the key " + keyName + " in the item"
                        : "The provided key element does not match the schema");
            }
        }
        buildItemKey(table, key, isPut ? KeySurface.ITEM_BODY : KeySurface.KEY_ARGUMENT);
        if (isPut) {
            validateIndexKeyTypes(table, key, false);
        }
    }

    private static void requireItemNestingWithinLimit(JsonNode item) {
        if (!DynamoDbAttributeValueValidator.nestingWithinLimit(item)) {
            throw new ItemNestingExceededException();
        }
    }

    private static TransactionCanceledException cancelledByMember(int memberCount, int failedMember,
                                                                   String message) {
        List<TransactionCanceledException.CancellationReason> reasons = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            reasons.add(i == failedMember
                    ? new TransactionCanceledException.CancellationReason("ValidationError", null, message)
                    : new TransactionCanceledException.CancellationReason("", null));
        }
        return new TransactionCanceledException(reasons);
    }

    private TransactionCanceledException.CancellationReason evaluateTransactCondition(JsonNode transactItem, String region) {
        return evaluateTransactCondition(transactItem, region, null);
    }

    private TransactionCanceledException.CancellationReason evaluateTransactCondition(
            JsonNode transactItem, String region,
            Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems) {
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
        TableDefinition table = requireActiveTable(storageKey);

        String itemKey = buildItemKey(table, key);
        var tableItems = itemsFor(storageKey, stagedItems, false);
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

    private void validateKeyNotModified(TableDefinition table, JsonNode key, JsonNode item) {
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
    }

    private void validateTransactItem(JsonNode transactItem, String region) {
        validateTransactItem(transactItem, region, null);
    }

    private void validateTransactItem(JsonNode transactItem, String region,
                                      Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems) {
        if (transactItem.has("Put")) {
            JsonNode put = transactItem.get("Put");
            String tableName = canonicalTableName(region, put.path("TableName").asText());
            String storageKey = regionKey(region, tableName);
            TableDefinition table = requireActiveTable(storageKey);
            JsonNode item = put.get("Item");
            if (item == null) {
                throw new AwsException("ValidationException", "Item is required for Put", 400);
            }
            JsonNode normalizedItem = DynamoDbNumberUtils.normalizeNumbersInItem(item);
            DynamoDbItemSize.validateSize(normalizedItem);
            buildItemKey(table, normalizedItem);
            validateIndexKeyTypes(table, normalizedItem, false);
            requireItemNestingWithinLimit(normalizedItem);
        } else if (transactItem.has("Delete")) {
            JsonNode del = transactItem.get("Delete");
            String tableName = canonicalTableName(region, del.path("TableName").asText());
            String storageKey = regionKey(region, tableName);
            TableDefinition table = requireActiveTable(storageKey);
            JsonNode key = del.get("Key");
            if (key == null) {
                throw new AwsException("ValidationException", "Key is required for Delete", 400);
            }
            buildItemKey(table, key, true);
        } else if (transactItem.has("Update")) {
            JsonNode upd = transactItem.get("Update");
            String tableName = canonicalTableName(region, upd.path("TableName").asText());
            String storageKey = regionKey(region, tableName);
            TableDefinition table = requireActiveTable(storageKey);
            JsonNode key = upd.get("Key");
            if (key == null) {
                throw new AwsException("ValidationException", "Key is required for Update", 400);
            }
            String itemKey = buildItemKey(table, key, true);
            var items = itemsFor(storageKey, stagedItems, false);
            JsonNode existing = items != null ? items.get(itemKey) : null;
            ObjectNode item = existing != null ? existing.deepCopy() : key.deepCopy();

            String updateExpression = upd.has("UpdateExpression") ? upd.get("UpdateExpression").asText() : null;
            JsonNode exprAttrNames = upd.has("ExpressionAttributeNames") ? upd.get("ExpressionAttributeNames") : null;
            JsonNode exprAttrValues = upd.has("ExpressionAttributeValues") ? upd.get("ExpressionAttributeValues") : null;
            if (updateExpression != null) {
                applyUpdateExpression(item, updateExpression, exprAttrNames, exprAttrValues);
            }
            validateKeyNotModified(table, key, item);
            requireItemNestingWithinLimit(item);
            validateIndexKeyTypes(table, item, true);
            requireUpdatedItemWithinSizeLimit(item);
        }
    }

    private static void requireUpdatedItemWithinSizeLimit(JsonNode item) {
        if (!DynamoDbItemSize.updatedItemWithinLimit(item)) {
            throw new UpdatedItemTooLargeException();
        }
    }

    private static void requireUpdateItemWithinSizeLimit(JsonNode item, Set<String> writtenAttributes,
                                                          int actionCost) {
        if (!DynamoDbItemSize.updateItemWithinLimit(item, writtenAttributes, actionCost)) {
            throw new UpdatedItemTooLargeException();
        }
    }

    public TransactGetResult transactGetItems(List<JsonNode> transactItems, String region) {
        List<JsonNode> results = new ArrayList<>();
        Map<String, DynamoDbWriteCapacity.Cost> capacity = new LinkedHashMap<>();
        List<TransactionCanceledException.CancellationReason> cancelReasons = new ArrayList<>();
        boolean hasCancelled = false;

        for (JsonNode transactItem : transactItems) {
            if (transactItem.has("Get")) {
                JsonNode get = transactItem.get("Get");
                String tableName = get.path("TableName").asText();
                JsonNode key = get.get("Key");
                try {
                    JsonNode item = getItem(tableName, key, region);
                    results.add(projectTransactGet(item, get));
                    capacity.merge(canonicalTableName(region, tableName), DynamoDbTransactCapacity.read(item, null),
                            DynamoDbWriteCapacity.Cost::plus);
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

        return new TransactGetResult(results, capacity);
    }

    /**
     * GetItem and BatchGetItem keep an empty Item when the projection matches nothing.
     * TransactGetItems omits it.
     */
    private JsonNode projectTransactGet(JsonNode item, JsonNode get) {
        String projectionExpression = get.path("ProjectionExpression").textValue();
        if (item == null || projectionExpression == null) {
            return item;
        }
        ObjectNode projected = ProjectionEvaluator.project(item, projectionExpression,
                get.has("ExpressionAttributeNames") ? get.get("ExpressionAttributeNames") : null);
        return projected.isEmpty() ? null : projected;
    }

    // --- UpdateTable ---

    public TableDefinition updateTable(String tableName, Long readCapacity, Long writeCapacity, String region) {
        return updateTable(tableName, readCapacity, writeCapacity, List.of(), List.of(), List.of(), region);
    }

    public TableDefinition updateTable(String tableName, Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsiCreates, List<String> gsiDeletes,
                                        List<AttributeDefinition> newAttrDefs, String region) {
        return updateTable(tableName, readCapacity, writeCapacity, gsiCreates, gsiDeletes,
                           newAttrDefs, List.of(), List.of(), null, region);
    }

    public TableDefinition updateTable(String tableName, Long readCapacity, Long writeCapacity,
                                        List<GlobalSecondaryIndex> gsiCreates, List<String> gsiDeletes,
                                        List<AttributeDefinition> newAttrDefs,
                                        List<VectorIndexCreate> vectorCreates, List<String> vectorDeletes,
                                        String billingMode, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        requireNotCreating(table);
        settleVectorIndexes(storageKey, table);

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

        // A new index's key attributes must all appear in the request's own AttributeDefinitions.
        // The definitions already stored on the table do not satisfy this, even for an attribute
        // the table key uses.
        Set<String> requestAttrs = new HashSet<>();
        if (newAttrDefs != null) {
            newAttrDefs.forEach(ad -> requestAttrs.add(ad.getAttributeName()));
        }
        for (GlobalSecondaryIndex newGsi : gsiCreates) {
            for (KeySchemaElement k : newGsi.getKeySchema()) {
                if (!requestAttrs.contains(k.getAttributeName())) {
                    throw new AwsException("ValidationException",
                            "Attribute: " + k.getAttributeName() + " is not defined in AttributeDefinitions", 400);
                }
            }
            validateGsiKeySchemaArity(newGsi);
        }

        for (String gsiName : gsiDeletes) {
            if (table.findGsi(gsiName).isEmpty()) {
                throw new AwsException("ResourceNotFoundException",
                        "Global secondary index " + gsiName + " does not exist on the table", 400);
            }
        }

        List<VectorIndexCreate> vectorIndexCreates = vectorCreates != null ? vectorCreates : List.of();
        List<String> vectorIndexDeletes = vectorDeletes != null ? vectorDeletes : List.of();
        List<AttributeDefinition> knownAttrDefs = new ArrayList<>(table.getAttributeDefinitions());
        if (newAttrDefs != null) {
            knownAttrDefs.addAll(newAttrDefs);
        }
        validateVectorIndexUpdates(table, vectorIndexCreates, vectorIndexDeletes, knownAttrDefs,
                billingMode != null ? billingMode : table.getBillingMode());

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

        for (String indexName : vectorIndexDeletes) {
            table.getVectorIndexes().removeIf(v -> indexName.equals(v.getIndexName()));
        }

        for (VectorIndexCreate create : vectorIndexCreates) {
            VectorIndex vectorIndex = create.index();
            vectorIndex.setIndexArn(table.getTableArn() + "/index/" + vectorIndex.getIndexName());
            vectorIndex.setIndexStatus("CREATING");
            vectorIndex.setCreationStartedAt(Instant.now());
            table.getVectorIndexes().add(vectorIndex);
        }
        if (!vectorIndexCreates.isEmpty()) {
            table.setTableStatus("UPDATING");
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
        pruneUnusedAttributeDefinitions(table);

        tableStore.put(storageKey, table);
        LOG.infov("Updated table: {0} in region {1}", canonicalTableName, region);
        return table;
    }

    /**
     * Validates the {@code VectorIndexUpdates} of an UpdateTable request against the table's
     * current lifecycle state.
     *
     * <p>AWS allows one online index operation per table at a time. That limit binds the table,
     * not the request, so two creates in one request and a create issued while an earlier index
     * still builds are refused the same way. Deleting an index that is still in its resource
     * allocation phase is refused with a different error, and the same delete is accepted once
     * the index reaches backfilling.
     */
    private void validateVectorIndexUpdates(TableDefinition table, List<VectorIndexCreate> creates,
                                            List<String> deletes,
                                            List<AttributeDefinition> attributeDefinitions,
                                            String billingMode) {
        validateVectorIndexMembers(creates);
        // A table that holds a vector index may not leave PAY_PER_REQUEST, whether or not the
        // request touches its indexes.
        if (!table.getVectorIndexes().isEmpty() && !"PAY_PER_REQUEST".equals(billingMode)) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Vector indexes are only supported "
                    + "for PAY_PER_REQUEST tables", 400);
        }
        if (creates.isEmpty() && deletes.isEmpty()) {
            return;
        }
        for (VectorIndexCreate create : creates) {
            if (table.findVectorIndex(create.index().getIndexName()).isPresent()) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid: Duplicate index name: "
                        + create.index().getIndexName(), 400);
            }
        }
        int deletesOfBuildingIndexes = 0;
        for (String indexName : deletes) {
            VectorIndex target = table.findVectorIndex(indexName)
                    .orElseThrow(() -> new AwsException("ValidationException",
                            "The table does not have the specified index: " + indexName, 400));
            if (!"CREATING".equals(target.getIndexStatus())) {
                continue;
            }
            if (!isVectorIndexBackfilling(target)) {
                throw new AwsException("ResourceInUseException",
                        "Attempt to change a resource which is still in use: Index creation is in "
                        + "resource allocation phase. Retry deletion during backfilling phase or "
                        + "when the index is active. Table: " + table.getTableName()
                        + " Index: " + indexName, 400);
            }
            deletesOfBuildingIndexes++;
        }
        long building = table.getVectorIndexes().stream()
                .filter(v -> "CREATING".equals(v.getIndexStatus()))
                .count();
        // Deleting an index that is still backfilling takes over the slot that index already
        // holds, so it does not need one of its own.
        long onlineOperations = building + creates.size() + deletes.size() - deletesOfBuildingIndexes;
        if (onlineOperations > 1) {
            throw new AwsException("LimitExceededException",
                    "Subscriber limit exceeded: Only 1 online index can be created or deleted "
                    + "simultaneously per table", 400);
        }
        validateVectorIndexes(creates, table.getVectorIndexes(), attributeDefinitions, billingMode);
    }

    /**
     * UpdateTable keeps only the attribute definitions a key schema still uses. A definition sent
     * with the request but used by no key is dropped in the same response, and deleting an index
     * drops the definitions only that index used. CreateTable rejects an unused definition instead.
     * AWS documents neither rule. Both are observed behaviour.
     */
    private void pruneUnusedAttributeDefinitions(TableDefinition table) {
        Set<String> used = new HashSet<>();
        for (KeySchemaElement k : table.getKeySchema()) {
            used.add(k.getAttributeName());
        }
        for (LocalSecondaryIndex lsi : table.getLocalSecondaryIndexes()) {
            for (KeySchemaElement k : lsi.getKeySchema()) {
                used.add(k.getAttributeName());
            }
        }
        for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
            for (KeySchemaElement k : gsi.getKeySchema()) {
                used.add(k.getAttributeName());
            }
        }
        for (VectorIndex vectorIndex : table.getVectorIndexes()) {
            used.addAll(vectorIndex.getSearchSchemaAttributeNames());
        }
        List<AttributeDefinition> kept = table.getAttributeDefinitions().stream()
                .filter(ad -> used.contains(ad.getAttributeName()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (kept.size() != table.getAttributeDefinitions().size()) {
            table.setAttributeDefinitions(kept);
        }
    }

    // --- Global-table replicas ---

    /**
     * Applies global-table replica changes (the {@code ReplicaUpdates} of UpdateTable, and the
     * legacy {@code dynamodb.Table.replicationRegions} replica custom resource). This single-process
     * emulator serves every region from the same table, so a replica is tracked as metadata and
     * surfaced by DescribeTable as an ACTIVE Replica; no cross-region copy is performed. Regions are
     * de-duplicated and adding an existing / removing an absent one is a no-op, keeping
     * CloudFormation re-applies and cleanup idempotent. Update remains strict because it represents
     * a settings change and has no target when the replica is absent.
     */
    public TableDefinition applyReplicaUpdates(String tableName, List<String> addRegions,
                                               List<String> removeRegions, String region) {
        return applyReplicaUpdates(tableName, addRegions, removeRegions, Collections.emptyList(), region);
    }

    public TableDefinition applyReplicaUpdates(String tableName, List<String> addRegions,
                                               List<String> removeRegions, List<String> updateRegions, String region) {
        TableDefinition table = validateReplicaUpdatesAndGetTable(
                tableName, addRegions, removeRegions, updateRegions, region);
        List<String> replicas = new ArrayList<>(table.getReplicaRegions());
        if (removeRegions != null) {
            replicas.removeAll(removeRegions);
        }
        if (addRegions != null) {
            for (String r : addRegions) {
                if (r != null && !r.isBlank() && !replicas.contains(r)) {
                    replicas.add(r);
                }
            }
        }
        table.setReplicaRegions(replicas);
        // Adding a replica turns the table into a global table, whose home region is the one the
        // update runs in. Once set it stays, matching AWS keeping the table a global table. This is
        // what lets DescribeTable list the home region as an ACTIVE replica alongside the others.
        if (table.getGlobalTableHomeRegion() == null && !replicas.isEmpty()) {
            table.setGlobalTableHomeRegion(region);
        }
        String canonicalTableName = canonicalTableName(region, tableName);
        tableStore.put(regionKey(region, canonicalTableName), table);
        LOG.infov("Updated replicas for table {0} in region {1}: {2}", canonicalTableName, region, replicas);
        return table;
    }

    /**
     * Marks a table as a global table homed in {@code region} even when it has no other replica yet,
     * so DescribeTable lists its home region as an ACTIVE replica. Used for a single-region
     * {@code AWS::DynamoDB::GlobalTable}, which AWS still reports as a global table. Idempotent.
     */
    public TableDefinition ensureGlobalTable(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        if (table.getGlobalTableHomeRegion() == null) {
            table.setGlobalTableHomeRegion(region);
            tableStore.put(storageKey, table);
        }
        return table;
    }

    /**
     * CreateGlobalTable, the 2017.11.29 API. It builds a global table out of a table that already
     * exists, so the table is resolved first and an absent one is TableNotFoundException rather
     * than the ResourceNotFoundException the item operations raise.
     *
     * <p>The model states the conditions a replica must meet, and the one this emulator can check
     * is the stream: the table must have DynamoDB Streams enabled with both the new and the old
     * image. The rest concern tables in other regions, which a single-process emulator serves from
     * this same table, so there is nothing separate to compare.
     */
    public TableDefinition createGlobalTable(String globalTableName, List<String> replicaRegions,
                                             String region) {
        String canonicalTableName = canonicalTableName(region, globalTableName);
        TableDefinition table = tableStore.get(regionKey(region, canonicalTableName))
                .orElseThrow(() -> new AwsException("TableNotFoundException",
                        "Table not found: " + canonicalTableName, 400));
        if (table.getGlobalTableHomeRegion() != null) {
            throw new AwsException("GlobalTableAlreadyExistsException",
                    "Global table already exists: " + globalTableName, 400);
        }
        requireStreamWithBothImages(table, canonicalTableName);
        List<String> adds = replicaRegions == null ? List.of() : replicaRegions.stream()
                .filter(r -> r != null && !r.isBlank() && !r.equals(region))
                .toList();
        applyReplicaUpdates(globalTableName, adds, List.of(), region);
        return ensureGlobalTable(globalTableName, region);
    }

    /** DescribeGlobalTable. A table that is not a global table has no description to give. */
    public TableDefinition describeGlobalTable(String globalTableName, String region) {
        String canonicalTableName = canonicalTableName(region, globalTableName);
        TableDefinition table = tableStore.get(regionKey(region, canonicalTableName))
                .filter(t -> t.getGlobalTableHomeRegion() != null)
                .orElseThrow(() -> new AwsException("GlobalTableNotFoundException",
                        "Global table not found: " + globalTableName, 400));
        return table;
    }

    /**
     * UpdateGlobalTable's replica adds and removes, on a table that is already a global table.
     *
     * <p>The membership checks live here rather than in {@link #applyReplicaUpdates}, which stays
     * idempotent for the UpdateTable and CloudFormation paths that re-apply an unchanged set. The
     * model draws the same line: UpdateGlobalTable declares ReplicaAlreadyExistsException and
     * ReplicaNotFoundException, and UpdateTable declares neither, so delegating without checking
     * first is exactly what would drop them.
     */
    public TableDefinition updateGlobalTable(String globalTableName, List<String> addRegions,
                                             List<String> removeRegions, String region) {
        TableDefinition table = describeGlobalTable(globalTableName, region);
        Set<String> replicas = currentReplicaRegions(table);
        if (addRegions != null) {
            for (String add : addRegions) {
                if (replicas.contains(add)) {
                    throw new AwsException("ReplicaAlreadyExistsException",
                            "Replica already exists in region " + add + ".", 400);
                }
            }
        }
        if (removeRegions != null) {
            for (String remove : removeRegions) {
                if (!replicas.contains(remove)) {
                    throw new AwsException("ReplicaNotFoundException",
                            "Replica not found in region " + remove + ".", 400);
                }
            }
        }
        return applyReplicaUpdates(globalTableName, addRegions, removeRegions, region);
    }

    /** The tracked replicas plus the home region, which is a replica of its own global table. */
    private static Set<String> currentReplicaRegions(TableDefinition table) {
        Set<String> regions = new LinkedHashSet<>(
                table.getReplicaRegions() != null ? table.getReplicaRegions() : List.of());
        if (table.getGlobalTableHomeRegion() != null) {
            regions.add(table.getGlobalTableHomeRegion());
        }
        return regions;
    }

    /** ListGlobalTables, optionally narrowed to the tables replicated into one region. */
    public List<TableDefinition> listGlobalTables(String regionFilter, String region) {
        return tableStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(t -> t.getGlobalTableHomeRegion() != null)
                .filter(t -> regionFilter == null || regionFilter.isBlank()
                        || regionFilter.equals(t.getGlobalTableHomeRegion())
                        || t.getReplicaRegions().contains(regionFilter))
                .sorted(Comparator.comparing(TableDefinition::getTableName))
                .toList();
    }

    /**
     * The one replica precondition a single-process emulator can actually test. A global table
     * replicates by reading the stream, so a table without one, or with a view that omits either
     * image, cannot be a replica source.
     */
    private static void requireStreamWithBothImages(TableDefinition table, String tableName) {
        if (!table.isStreamEnabled() || !"NEW_AND_OLD_IMAGES".equals(table.getStreamViewType())) {
            throw new AwsException("ValidationException",
                    "Table " + tableName + " must have DynamoDB Streams enabled with "
                            + "StreamViewType NEW_AND_OLD_IMAGES to join a global table.", 400);
        }
    }

    public void validateReplicaUpdates(String tableName, List<String> addRegions,
                                       List<String> removeRegions, List<String> updateRegions, String region) {
        validateReplicaUpdatesAndGetTable(tableName, addRegions, removeRegions, updateRegions, region);
    }

    private TableDefinition validateReplicaUpdatesAndGetTable(
            String tableName, List<String> addRegions, List<String> removeRegions,
            List<String> updateRegions, String region) {
        validateReplicaRegions(addRegions, region);
        validateReplicaRegions(removeRegions, region);
        validateReplicaRegions(updateRegions, region);
        String canonicalTableName = canonicalTableName(region, tableName);
        String storageKey = regionKey(region, canonicalTableName);
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(() -> resourceNotFoundException(canonicalTableName));
        if (updateRegions != null) {
            for (String replicaRegion : updateRegions) {
                if (!table.getReplicaRegions().contains(replicaRegion)) {
                    throw new AwsException("ValidationException",
                            "Replica " + replicaRegion + " does not exist", 400);
                }
            }
        }
        return table;
    }

    private static void validateReplicaRegions(List<String> replicaRegions, String localRegion) {
        if (replicaRegions == null) {
            return;
        }
        for (String replicaRegion : replicaRegions) {
            if (replicaRegion == null || replicaRegion.isBlank()) {
                throw new AwsException("ValidationException", "Replica RegionName must not be empty", 400);
            }
            if (replicaRegion.equals(localRegion)) {
                throw new AwsException("ValidationException", LOCAL_REPLICA_UPDATE_ERROR, 400);
            }
        }
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

    record ExpiredTableScan(String rawKey, String accountId, String storageKey, String region,
                             TableDefinition table, List<String> itemKeys) {}

    void deleteExpiredItems() {
        deleteScannedItems(scanExpiredItems());
    }

    List<ExpiredTableScan> scanExpiredItems() {
        List<ExpiredTableScan> scans = new ArrayList<>();
        // Runs with no request scope and must sweep every account's tables, not just the
        // default one — scanAllAccountsRaw()'s key matches itemsByTable's directly.
        Map<String, TableDefinition> allTables;
        if (tableStore instanceof AccountAwareStorageBackend<TableDefinition> aware) {
            allTables = aware.scanAllAccountsRaw();
        } else {
            allTables = new HashMap<>();
            tableStore.keys().forEach(k -> tableStore.get(k).ifPresent(v -> allTables.put(scopedItemsKey(k), v)));
        }
        for (Map.Entry<String, TableDefinition> entry : allTables.entrySet()) {
            String rawKey = entry.getKey();
            TableDefinition table = entry.getValue();
            if (!table.isTtlEnabled() || table.getTtlAttributeName() == null) {
                continue;
            }
            var items = itemsByTable.get(rawKey);
            if (items == null) {
                continue;
            }

            List<String> expiredKeys = items.entrySet().stream()
                    .filter(e -> isExpired(e.getValue(), table))
                    .map(Map.Entry::getKey)
                    .toList();

            if (expiredKeys.isEmpty()) continue;

            int slash = rawKey.indexOf('/');
            String accountId = slash >= 0 ? rawKey.substring(0, slash) : null;
            String storageKey = slash >= 0 ? rawKey.substring(slash + 1) : rawKey;
            String region = storageKey.split("::", 2)[0];
            scans.add(new ExpiredTableScan(rawKey, accountId, storageKey, region, table, expiredKeys));
        }
        return scans;
    }

    void deleteScannedItems(List<ExpiredTableScan> scans) {
        int totalDeleted = 0;
        for (ExpiredTableScan scan : scans) {
            ConcurrentSkipListMap<String, JsonNode> items = itemsByTable.get(scan.rawKey());
            if (items == null) {
                continue;
            }

            int deletedForTable = 0;
            for (String itemKey : scan.itemKeys()) {
                JsonNode removed = withScopedItemLock(scan.rawKey(), itemKey, () -> {
                    JsonNode current = items.get(itemKey);
                    if (current == null || !isExpired(current, scan.table())) {
                        return null;
                    }
                    return items.remove(itemKey);
                });
                if (removed == null) {
                    continue;
                }
                deletedForTable++;
                if (streamService != null) {
                    streamService.captureEvent(scan.table().getTableName(), "REMOVE", removed, null,
                            scan.table(), scan.region());
                }
                if (kinesisForwarder != null) {
                    // Out of request scope here: pass the table owner's account explicitly so the CDC
                    // record lands in the owner's stream, not the default account's same-named stream.
                    kinesisForwarder.forward("REMOVE", removed, null, scan.table(), scan.region(), scan.accountId());
                }
            }
            if (deletedForTable > 0) {
                persistItemsForAccount(scan.accountId(), scan.storageKey(), items);
                totalDeleted += deletedForTable;
            }
        }
        if (totalDeleted > 0) {
            LOG.infov("TTL sweeper removed {0} expired items", totalDeleted);
        }
    }

    /** Like {@link #persistItems}, but for callers with no ambient request account to rely on. */
    private void persistItemsForAccount(String accountId, String storageKey, Map<String, JsonNode> items) {
        if (itemStore == null) return;
        if (accountId != null && itemStore instanceof AccountAwareStorageBackend<Map<String, JsonNode>> aware) {
            aware.putForAccount(accountId, storageKey, new HashMap<>(items));
        } else {
            itemStore.put(storageKey, new HashMap<>(items));
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

    /** Result of a successful GetResourcePolicy call: the raw policy document and its revision id. */
    public record ResourcePolicyResult(String policy, String revisionId) {}

    public String putResourcePolicy(String resourceArn, String policy, String expectedRevisionId, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        if (expectedRevisionId != null
                && !expectedRevisionId.equals(table.getResourcePolicyRevisionId())) {
            throw new AwsException("PolicyNotFoundException",
                    "Policy with revision id " + expectedRevisionId + " does not exist for: " + resourceArn, 400);
        }
        String revisionId = UUID.randomUUID().toString();
        table.setResourcePolicy(policy);
        table.setResourcePolicyRevisionId(revisionId);
        String storageKey = regionKey(region, table.getTableName());
        tableStore.put(storageKey, table);
        LOG.debugv("Put resource policy: {0}", resourceArn);
        return revisionId;
    }

    public ResourcePolicyResult getResourcePolicy(String resourceArn, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        if (table.getResourcePolicy() == null) {
            throw new AwsException("PolicyNotFoundException",
                    "No resource policy found for: " + resourceArn, 400);
        }
        return new ResourcePolicyResult(table.getResourcePolicy(), table.getResourcePolicyRevisionId());
    }

    public String deleteResourcePolicy(String resourceArn, String expectedRevisionId, String region) {
        TableDefinition table = findTableByArn(resourceArn, region);
        if (table.getResourcePolicy() == null) {
            throw new AwsException("PolicyNotFoundException",
                    "No resource policy found for: " + resourceArn, 400);
        }
        if (expectedRevisionId != null
                && !expectedRevisionId.equals(table.getResourcePolicyRevisionId())) {
            throw new AwsException("PolicyNotFoundException",
                    "Policy with revision id " + expectedRevisionId + " does not exist for: " + resourceArn, 400);
        }
        String revisionId = table.getResourcePolicyRevisionId();
        table.setResourcePolicy(null);
        table.setResourcePolicyRevisionId(null);
        String storageKey = regionKey(region, table.getTableName());
        tableStore.put(storageKey, table);
        LOG.debugv("Deleted resource policy: {0}", resourceArn);
        return revisionId;
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
        applyUpdateExpression(item, expression, exprAttrNames, exprAttrValues, new ArrayList<>());
    }

    // Every action's target path is added to touched, which UPDATED_NEW and UPDATED_OLD read.
    private void applyUpdateExpression(ObjectNode item, String expression,
                                        JsonNode exprAttrNames, JsonNode exprAttrValues,
                                        List<String> touched) {
        // Parse SET and REMOVE clauses from expressions like:
        // "SET #n = :newName, age = :newAge REMOVE oldField"
        if (expression.isBlank()) {
            throw new AwsException("ValidationException",
                    "Invalid UpdateExpression: The expression can not be empty;", 400);
        }
        // AWS tokenizes UpdateExpression whitespace-insensitively, so collapse runs of
        // whitespace (newlines, tabs, multiple spaces) so clause-keyword dispatch and the
        // comma-separated action parsing below work regardless of formatting. Normalize
        // before the reserved-word check too: its function-call lookahead skips only
        // literal spaces, so on a raw "if_not_exists\n(...)" it would read the function
        // name as a bare identifier instead.
        String remaining = expression.trim().replaceAll("\\s+", " ");
        DynamoDbReservedWords.check(remaining, "UpdateExpression");

        while (!remaining.isEmpty()) {
            String upper = remaining.toUpperCase();
            if (upper.startsWith("SET ")) {
                remaining = remaining.substring(4).trim();
                remaining = applySetClause(item, remaining, exprAttrNames, exprAttrValues, touched);
            } else if (upper.startsWith("REMOVE ")) {
                remaining = remaining.substring(7).trim();
                remaining = applyRemoveClause(item, remaining, exprAttrNames, touched);
            } else if (upper.startsWith("ADD ")) {
                remaining = remaining.substring(4).trim();
                remaining = applyAddClause(item, remaining, exprAttrNames, exprAttrValues, touched);
            } else if (upper.startsWith("DELETE ")) {
                remaining = remaining.substring(7).trim();
                remaining = applyDeleteClause(item, remaining, exprAttrNames, exprAttrValues, touched);
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
                                   JsonNode exprAttrNames, JsonNode exprAttrValues, List<String> touched) {
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
            touched.add(attrPath);

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
                    BigDecimal left = new BigDecimal(leftVal.get("N").asText());
                    BigDecimal right = new BigDecimal(rightVal.get("N").asText());
                    BigDecimal result = (operator == '+') ? left.add(right) : left.subtract(right);
                    DynamoDbNumberUtils.checkArithmeticResult(result);
                    ObjectNode numNode = JsonNodeFactory.instance.objectNode();
                    numNode.put("N", result.toPlainString());
                    setValueAtPath(item, attrPath, numNode, exprAttrNames);
                } catch (NumberFormatException e) {
                    throw new AwsException("ValidationException",
                            "The parameter cannot be converted to a numeric value", 400);
                }
            } else if (startsWithFunctionCall(valuePart, "if_not_exists")) {
                // if_not_exists(attrRef, fallbackExpr) evaluates to:
                //   attrRef's current value  — when attrRef exists in the item
                //   fallbackExpr             — otherwise
                // The result is always assigned to attrPath.
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
            } else if (startsWithFunctionCall(valuePart.toLowerCase(), "list_append")) {
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
                        if (list1 == null || list2 == null) {
                            throw new AwsException("ValidationException",
                                    "The provided expression refers to an attribute that does not exist in the item", 400);
                        }
                        if (!list1.has("L") || !list2.has("L")) {
                            throw new AwsException("ValidationException",
                                    "An operand in the update expression has an incorrect data type", 400);
                        }
                        ArrayNode merged =
                                JsonNodeFactory.instance.arrayNode();
                        list1.get("L").forEach(merged::add);
                        list2.get("L").forEach(merged::add);
                        ObjectNode result =
                                JsonNodeFactory.instance.objectNode();
                        result.set("L", merged);
                        setValueAtPath(item, attrPath, result, exprAttrNames);
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
        if (startsWithFunctionCall(expr.toLowerCase(), "if_not_exists")) {
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

    private String applyRemoveClause(ObjectNode item, String clause, JsonNode exprAttrNames,
                                     List<String> touched) {
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

            touched.add(attrPart);
            removeValueAtPath(item, attrPart, exprAttrNames);
        }
        return clause;
    }

    private String applyAddClause(ObjectNode item, String clause,
                                  JsonNode exprAttrNames, JsonNode exprAttrValues, List<String> touched) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("REMOVE ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Parse "attr :val"
            String[] parts = clause.split("\\s+", 3);
            if (parts.length < 2) break;

            String attrPath = parts[0];
            touched.add(attrPath);
            String valuePlaceholder = parts[1].replaceAll(",.*", "").trim();

            if (valuePlaceholder.startsWith(":") && exprAttrValues != null) {
                JsonNode addValue = exprAttrValues.get(valuePlaceholder);
                if (addValue != null) {
                    JsonNode existingValue = getValueAtPath(item, attrPath, exprAttrNames);
                    JsonNode newValue = applyAddOperation(existingValue, addValue);
                    setValueAtPath(item, attrPath, newValue, exprAttrNames);
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
        requireSameTypeAsOperand(existingValue, addValue, List.of("N", "SS", "NS", "BS"));
        ObjectNode result = JsonNodeFactory.instance.objectNode();

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
                BigDecimal existingNum = new BigDecimal(existingNumStr);
                BigDecimal addNum = new BigDecimal(addNumStr);
                var sum = existingNum.add(addNum);
                DynamoDbNumberUtils.checkArithmeticResult(sum);
                result.put("N", sum.toPlainString());
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
            Set<String> combined = new LinkedHashSet<>();
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
            Set<String> combined = new LinkedHashSet<>();
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
            Set<String> combined = new LinkedHashSet<>();
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
                                     JsonNode exprAttrNames, JsonNode exprAttrValues, List<String> touched) {
        while (!clause.isEmpty()) {
            String upper = clause.toUpperCase();
            if (upper.startsWith("SET ") || upper.startsWith("REMOVE ") || upper.startsWith("ADD ") || upper.startsWith("DELETE ")) {
                break;
            }

            // Parse "attr :val"
            String[] parts = clause.split("\\s+", 3);
            if (parts.length < 2) break;

            String attrPath = parts[0];
            touched.add(attrPath);
            String valuePlaceholder = parts[1].replaceAll(",.*", "").trim();

            if (valuePlaceholder.startsWith(":") && exprAttrValues != null) {
                JsonNode deleteValue = exprAttrValues.get(valuePlaceholder);
                if (deleteValue != null) {
                    JsonNode existingValue = getValueAtPath(item, attrPath, exprAttrNames);
                    if (existingValue != null) {
                        JsonNode newValue = applyDeleteOperation(existingValue, deleteValue);
                        if (newValue == null) {
                            removeValueAtPath(item, attrPath, exprAttrNames);
                        } else {
                            setValueAtPath(item, attrPath, newValue, exprAttrNames);
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

    private static final Map<String, String> OPERAND_TYPE_NAMES = Map.of(
            "S", "STRING", "N", "NUMBER", "B", "Binary", "BOOL", "BOOL", "NULL", "NULL", "L", "LIST", "M", "MAP");

    void requireAddOrDeleteOperandTypes(String updateExpression, JsonNode exprAttrValues, boolean inValidationEnvelope) {
        if (updateExpression == null || exprAttrValues == null) {
            return;
        }
        String remaining = updateExpression.trim().replaceAll("\\s+", " ");
        while (!remaining.isEmpty()) {
            String keyword = remaining.substring(0, Math.max(remaining.indexOf(' '), 0)).toUpperCase();
            String body = remaining.substring(keyword.length()).trim();
            int nextClause = findNextClauseKeyword(body);
            String actions = nextClause < 0 ? body : body.substring(0, nextClause);
            remaining = nextClause < 0 ? "" : body.substring(nextClause);
            Set<String> allowed = switch (keyword) {
                case "ADD" -> Set.of("N", "SS", "NS", "BS");
                case "DELETE" -> Set.of("SS", "NS", "BS");
                default -> null;
            };
            while (allowed != null && !actions.isBlank()) {
                int comma = findNextComma(actions);
                String[] words = (comma < 0 ? actions : actions.substring(0, comma)).trim().split(" ");
                actions = comma < 0 ? "" : actions.substring(comma + 1);
                JsonNode operand = exprAttrValues.get(words[words.length - 1]);
                if (operand == null) {
                    continue;
                }
                String type = DynamoDbAttributeValueValidator.typeOf(operand);
                if (!allowed.contains(type)) {
                    throw new AwsException("ValidationException", (inValidationEnvelope ? "1 validation error detected: " : "")
                            + "Invalid UpdateExpression: Incorrect operand type for operator or function;"
                            + " operator: " + keyword + ", operand type: " + OPERAND_TYPE_NAMES.get(type)
                            + ", typeSet: ALLOWED_FOR_ADD_OPERAND", 400);
                }
            }
        }
    }

    private static void requireSameTypeAsOperand(JsonNode existingValue, JsonNode operand, List<String> types) {
        for (String type : types) {
            if (operand.has(type) && existingValue != null && !existingValue.has(type)) {
                throw new AwsException("ValidationException",
                        "An operand in the update expression has an incorrect data type", 400);
            }
        }
    }

    /**
     * Implements DynamoDB DELETE operation semantics:
     * removes the specified elements from a set attribute (SS, NS, BS).
     * Returns null if the resulting set is empty (caller should remove the attribute).
     * Returns the existing value unchanged if the value to delete isn't a set.
     */
    private JsonNode applyDeleteOperation(JsonNode existingValue, JsonNode deleteValue) {
        requireSameTypeAsOperand(existingValue, deleteValue, List.of("SS", "NS", "BS"));
        ObjectNode result = JsonNodeFactory.instance.objectNode();

        if (deleteValue.has("SS") && existingValue.has("SS")) {
            Set<String> toRemove = new LinkedHashSet<>();
            deleteValue.get("SS").forEach(n -> toRemove.add(n.asText()));
            List<String> remaining = new ArrayList<>();
            existingValue.get("SS").forEach(n -> {
                if (!toRemove.contains(n.asText())) remaining.add(n.asText());
            });
            if (remaining.isEmpty()) return null;
            var arrayNode = result.putArray("SS");
            remaining.forEach(arrayNode::add);
            return result;
        }

        if (deleteValue.has("NS") && existingValue.has("NS")) {
            Set<String> toRemove = new LinkedHashSet<>();
            deleteValue.get("NS").forEach(n -> toRemove.add(n.asText()));
            List<String> remaining = new ArrayList<>();
            existingValue.get("NS").forEach(n -> {
                if (!toRemove.contains(n.asText())) remaining.add(n.asText());
            });
            if (remaining.isEmpty()) return null;
            var arrayNode = result.putArray("NS");
            remaining.forEach(arrayNode::add);
            return result;
        }

        if (deleteValue.has("BS") && existingValue.has("BS")) {
            Set<String> toRemove = new LinkedHashSet<>();
            deleteValue.get("BS").forEach(n -> toRemove.add(n.asText()));
            List<String> remaining = new ArrayList<>();
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
    // String (attr name) and Long (list index) tokens.
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
                if (!namePart.isEmpty()) {
                    tokens.add(resolveAttributeName(namePart, exprAttrNames));
                }

                String rest = dotSeg.substring(brk);
                int p = 0;

                while (p < rest.length() && rest.charAt(p) == '[') {
                    int close = rest.indexOf(']', p);
                    if (close < 0) break;

                    String indexText = rest.substring(p + 1, close);

                    final long index;
                    try {
                        index = Long.parseLong(indexText);
                    } catch (NumberFormatException e) {
                        throw new AwsException(
                                "ValidationException",
                                "Invalid list index: " + indexText,
                                400);
                    }

                    if (index < 0 || index > MAX_DYNAMODB_LIST_INDEX) {
                        throw new AwsException(
                                "ValidationException",
                                "1 validation error detected: Invalid UpdateExpression: "
                                        + "List index is not within the allowable range; index: ["
                                        + indexText
                                        + "]. The maximum allowed index is 4294967294",
                                400);
                    }

                    tokens.add(index);
                    p = close + 1;
                }
            }
        }
        return tokens;
    }
    /**
     * Replaces the element at idx when the index is within the list bounds;
     * otherwise appends the value to the end of the list.
    */

    private void setOrAppend(ArrayNode arr, long idx, JsonNode value) {
        if (idx < arr.size()) {
            arr.set((int) idx, value);
        } else {
            arr.add(value);
        }
    }
    
    private void setValueAtPath(ObjectNode item, String path, JsonNode value, JsonNode exprAttrNames) {
        List<Object> tokens = parsePath(path, exprAttrNames);
        if (tokens.isEmpty()) return;

        if (tokens.size() == 1) {
            if (tokens.get(0) instanceof String attrName) {
                item.set(attrName, value);
            }
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
                        // AWS requires every intermediate of a document path to already
                        // exist as the right type; only the final element may be new.
                        if (child == null || !child.has("M")) {
                            throw new AwsException(
                                    "ValidationException",
                                    "The document path provided in the update expression is invalid for update",
                                    400);
                        }
                        ((ObjectNode) child.get("M")).set(finalAttr, value);
                    } else if (nextTok instanceof Long finalIdx) {
                        if (child == null || !child.has("L")) {
                            throw new AwsException(
                                    "ValidationException",
                                    "The document path provided in the update expression is invalid for update",
                                    400);
                        }

                        setOrAppend((ArrayNode) child.get("L"), finalIdx, value);
                    }

                    return;
                }

                if (nextTok instanceof String) {
                    if (child == null || !child.has("M")) {
                        throw new AwsException(
                                "ValidationException",
                                "The document path provided in the update expression is invalid for update",
                                400);
                    }
                    container = child.get("M");

                } else if (nextTok instanceof Long) {
                    if (child == null || !child.has("L")) {
                        throw new AwsException(
                                "ValidationException",
                                "The document path provided in the update expression is invalid for update",
                                400);
                    }

                    container = child.get("L");
                }

            } else if (tok instanceof Long listIdx) {
                if (!(container instanceof ArrayNode arr)) return;

                if (listIdx >= arr.size()) {
                    throw new AwsException(
                            "ValidationException",
                            "The document path provided in the update expression is invalid for update",
                            400);
                }

                JsonNode element = arr.get(listIdx.intValue());

                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (!element.has("M")) {
                            throw new AwsException(
                                    "ValidationException",
                                    "The document path provided in the update expression is invalid for update",
                                    400);
                        }

                        ((ObjectNode) element.get("M")).set(finalAttr, value);

                    } else if (nextTok instanceof Long finalIdx) {
                        if (!element.has("L")) {
                            throw new AwsException(
                                    "ValidationException",
                                    "The document path provided in the update expression is invalid for update",
                                    400);
                        }

                        setOrAppend((ArrayNode) element.get("L"), finalIdx, value);
                    }

                    return;
                }

                if (nextTok instanceof String) {
                    if (!element.has("M")) {
                        throw new AwsException(
                                "ValidationException",
                                "The document path provided in the update expression is invalid for update",
                                400);
                    }

                    container = element.get("M");

                } else if (nextTok instanceof Long) {
                    if (!element.has("L")) {
                        throw new AwsException(
                                "ValidationException",
                                "The document path provided in the update expression is invalid for update",
                                400);
                    }

                    container = element.get("L");
                }
            }
        }
    }

    private JsonNode getValueAtPath(JsonNode item, String path, JsonNode exprAttrNames) {
        return valueAtTokens(item, parsePath(path, exprAttrNames));
    }

    private JsonNode valueAtTokens(JsonNode item, List<Object> tokens) {
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
                } else if (nextTok instanceof Long) {
                    if (!child.has("L")) return null;
                    current = child.get("L");
                } else return null;
            } else if (tok instanceof Long listIdx) {
                if (!current.isArray() || listIdx >= current.size()) return null;
                JsonNode element = current.get(listIdx.intValue());
                if (element == null) return null;
                if (isLast) return element;
                Object nextTok = tokens.get(i + 1);
                if (nextTok instanceof String) {
                    if (!element.has("M")) return null;
                    current = element.get("M");
                } else if (nextTok instanceof Long) {
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
            if (tokens.get(0) instanceof String attrName) {
                item.remove(attrName);
            }
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
                    } else if (nextTok instanceof Long finalIdx) {
                        if (child == null || !child.has("L")) return;

                        ArrayNode lArr = (ArrayNode) child.get("L");
                        if (finalIdx < lArr.size()) {
                            lArr.remove(finalIdx.intValue());
                        }
                    }
                    return;
                }

                if (nextTok instanceof String) {
                    if (child == null || !child.has("M")) return;
                    container = child.get("M");
                } else if (nextTok instanceof Long) {
                    if (child == null || !child.has("L")) return;
                    container = child.get("L");
                }

            } else if (tok instanceof Long listIdx) {
                if (!(container instanceof ArrayNode arr)) return;
                if (listIdx >= arr.size()) return;

                JsonNode element = arr.get(listIdx.intValue());

                if (last) {
                    if (nextTok instanceof String finalAttr) {
                        if (!element.has("M")) return;
                        ((ObjectNode) element.get("M")).remove(finalAttr);
                    } else if (nextTok instanceof Long finalIdx) {
                        if (!element.has("L")) return;

                        ArrayNode lArr = (ArrayNode) element.get("L");
                        if (finalIdx < lArr.size()) {
                            lArr.remove(finalIdx.intValue());
                        }
                    }
                    return;
                }

                if (nextTok instanceof String) {
                    if (!element.has("M")) return;
                    container = element.get("M");
                } else if (nextTok instanceof Long) {
                    if (!element.has("L")) return;
                    container = element.get("L");
                }
            }
        }
    }

    static int findNextComma(String s) {
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

    static int findNextClauseKeyword(String s) {
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

    private static int indexOfKeyword(String upper, String keyword) {
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

    /**
     * Whether {@code value} opens with a call to {@code functionName}, tolerating optional
     * whitespace between the function name and the opening parenthesis. DynamoDB's
     * UpdateExpression grammar is whitespace-insensitive there, but SDKs commonly emit the
     * spaced form (e.g. PynamoDB always generates {@code "list_append (x, y)"}), which a plain
     * {@code startsWith(functionName + "(")} rejects.
     */
    private static boolean startsWithFunctionCall(String value, String functionName) {
        return value.startsWith(functionName)
                && value.substring(functionName.length()).stripLeading().startsWith("(");
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

    // itemsByTable/itemLocks are plain fields, unlike tableStore/itemStore, so they get no
    // automatic account prefixing — without this, two accounts with a same-named table would
    // share one item map. Matches scanAllAccountsRaw()'s key format, so a raw key from there
    // can be used directly as an itemsByTable/itemLocks key.
    private String scopedItemsKey(String storageKey) {
        return regionResolver.getAccountId() + "/" + storageKey;
    }

    private ConcurrentSkipListMap<String, JsonNode> currentItems(String storageKey, boolean create) {
        return itemsFor(storageKey, null, create);
    }

    private ConcurrentSkipListMap<String, JsonNode> itemsFor(
            String storageKey,
            Map<String, ConcurrentSkipListMap<String, JsonNode>> stagedItems,
            boolean create) {
        if (stagedItems != null) {
            if (create) {
                return stagedItems.computeIfAbsent(storageKey, ignored -> new ConcurrentSkipListMap<>());
            }
            return stagedItems.get(storageKey);
        }
        return create
                ? itemsByTable.computeIfAbsent(scopedItemsKey(storageKey), k -> new ConcurrentSkipListMap<>())
                : itemsByTable.get(scopedItemsKey(storageKey));
    }

    private ReentrantLock lockFor(String storageKey, String itemKey) {
        return lockForScopedKey(scopedItemsKey(storageKey), itemKey);
    }

    // The sweeper has no request scope, so it locks with the raw account-scoped key instead of scopedItemsKey.
    private ReentrantLock lockForScopedKey(String scopedKey, String itemKey) {
        return itemLocks
                .computeIfAbsent(scopedKey, k -> new ConcurrentHashMap<>())
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

    private <T> T withScopedItemLock(String scopedKey, String itemKey, Supplier<T> body) {
        ReentrantLock lock = lockForScopedKey(scopedKey, itemKey);
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    // AWS words a key rejection by the surface the key arrived on. A PutItem item body
    // names the mismatched types, a Key argument and a BatchWriteItem entry report a
    // schema mismatch instead. An empty key value is worded the same on every surface.
    enum KeySurface { ITEM_BODY, KEY_ARGUMENT, BATCH_WRITE }

    String buildItemKey(TableDefinition table, JsonNode item) {
        return buildItemKey(table, item, KeySurface.ITEM_BODY);
    }

    String buildItemKey(TableDefinition table, JsonNode item, boolean isKeyArg) {
        return buildItemKey(table, item, isKeyArg ? KeySurface.KEY_ARGUMENT : KeySurface.ITEM_BODY);
    }

    String buildItemKey(TableDefinition table, JsonNode item, KeySurface surface) {
        String pkName = table.getPartitionKeyName();
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr == null) {
            throw missingKeyException(surface);
        }
        validateKeyAttributeValue(table, pkAttr, pkName, surface);
        validateKeySize(pkAttr, true);

        String pk = encodeKeySegment(extractScalarValue(pkAttr));
        String skName = table.getSortKeyName();
        if (skName != null) {
            JsonNode skAttr = item.get(skName);
            if (skAttr == null) {
                throw missingKeyException(surface);
            }
            validateKeyAttributeValue(table, skAttr, skName, surface);
            validateKeySize(skAttr, false);
            return pk + "#" + encodeKeySegment(extractScalarValue(skAttr));
        }
        return pk;
    }

    // '#' separates composite key segments in the in-memory map. Escape it and the escape
    // character inside each segment so legal string key values cannot produce the same map key.
    static String encodeKeySegment(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('#') < 0 && value.indexOf('\\') < 0) {
            return value;
        }
        return value.replace("\\", "\\\\").replace("#", "\\#");
    }

    private void validateKeySize(JsonNode attr, boolean partitionKey) {
        int limit = partitionKey ? 2048 : 1024;
        if ((attr.has("S") || attr.has("B")) && DynamoDbItemSize.attributeValueSize(attr) > limit) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid: Size of "
                    + (partitionKey ? "hashkey" : "rangekey")
                    + " has exceeded the maximum size limit of " + limit + " bytes", 400);
        }
    }

    private AwsException missingKeyException(KeySurface surface) {
        if (surface == KeySurface.ITEM_BODY) {
            return new AwsException("ValidationException",
                    "One of the required keys was not given a value", 400);
        }
        return new AwsException("ValidationException",
                "The provided key element does not match the schema", 400);
    }

    // A wire-format AttributeValue must be a JSON object with exactly one type member.
    // AWS enforces this for every attribute value; floci additionally relies on it
    // wherever a value becomes part of a storage or index key.
    private void validateAttributeValueShape(JsonNode attr) {
        if (!attr.isObject()) {
            // AWS's protocol layer rejects non-object values before validation runs.
            throw new AwsException("SerializationException", "Unexpected value type in payload", 400);
        }
        if (attr.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Supplied AttributeValue is empty, must contain exactly one of the supported datatypes", 400);
        }
        if (attr.size() > 1) {
            throw new AwsException("ValidationException",
                    "Supplied AttributeValue has more than one datatypes set, "
                    + "must contain exactly one of the supported datatypes", 400);
        }
    }

    private void validateKeyAttributeValue(TableDefinition table, JsonNode attr, String keyName,
                                            KeySurface surface) {
        if (attr == null) {
            return;
        }
        validateAttributeValueShape(attr);
        String expectedType = keyAttributeType(table, keyName);
        if (expectedType != null && !attr.has(expectedType)) {
            if (surface == KeySurface.ITEM_BODY) {
                throw new KeySchemaMismatchException(
                        "One or more parameter values were invalid: Type mismatch for key " + keyName
                        + " expected: " + expectedType + " actual: " + attr.fieldNames().next());
            }
            throw new KeySchemaMismatchException("The provided key element does not match the schema");
        }
        if (attr.has("S") && attr.get("S").asText().isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values are not valid. "
                    + "The AttributeValue for a key attribute cannot contain an empty string value. Key: "
                    + keyName, 400);
        }
        if (attr.has("B") && attr.get("B").asText().isEmpty()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values are not valid. "
                    + "The AttributeValue for a key attribute cannot contain an empty binary value. Key: "
                    + keyName, 400);
        }
    }

    private String keyAttributeType(TableDefinition table, String keyName) {
        if (table.getAttributeDefinitions() == null) {
            return null;
        }
        return table.getAttributeDefinitions().stream()
                .filter(def -> keyName.equals(def.getAttributeName()))
                .map(AttributeDefinition::getAttributeType)
                .findFirst().orElse(null);
    }

    // AWS validates GSI/LSI key attribute values on every write that produces the item,
    // but only when the attribute is present — sparse indexes allow it to be absent.
    private void validateIndexKeyTypes(TableDefinition table, JsonNode item, boolean isUpdate) {
        if (table.getGlobalSecondaryIndexes() != null) {
            for (GlobalSecondaryIndex gsi : table.getGlobalSecondaryIndexes()) {
                validateIndexKeySchema(table, item, gsi.getIndexName(), gsi.getKeySchema(), isUpdate);
            }
        }
        if (table.getLocalSecondaryIndexes() != null) {
            for (LocalSecondaryIndex lsi : table.getLocalSecondaryIndexes()) {
                validateIndexKeySchema(table, item, lsi.getIndexName(), lsi.getKeySchema(), isUpdate);
            }
        }
        for (VectorIndex vectorIndex : table.getVectorIndexes()) {
            validateVectorIndexWrite(table, item, vectorIndex, isUpdate);
        }
    }

    // A vector index constrains the shape of the vector attribute and, through its search
    // schema, the HASH attribute it partitions on. An item carrying neither is written and
    // left out of the index; one carrying a vector of the wrong shape is refused.
    private void validateVectorIndexWrite(TableDefinition table, JsonNode item,
                                          VectorIndex index, boolean isUpdate) {
        JsonNode vector = item.get(index.getVectorAttributeName());
        if (vector != null) {
            validateVectorAttribute(vector, index);
        }
        String hashAttribute = index.getHashAttributeName();
        if (hashAttribute != null) {
            validateIndexKeySchema(table, item, index.getIndexName(),
                    List.of(new KeySchemaElement(hashAttribute, "HASH")), isUpdate);
        }
    }

    // AWS punctuates these three inconsistently, which is reproduced here character for
    // character: "Actual: S." carries a period and "Actual: 2" and "list" do not.
    private void validateVectorAttribute(JsonNode vector, VectorIndex index) {
        String attributeName = index.getVectorAttributeName();
        String indexName = index.getIndexName();
        validateAttributeValueShape(vector);
        JsonNode components = vector.get("L");
        if (components == null || !components.isArray()) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid. Invalid type for parameter "
                    + attributeName + ", Expected: 32-bit floating point number list IndexName: "
                    + indexName, 400);
        }
        Long dimensions = index.getDimensions();
        if (components.size() != dimensions) {
            throw new AwsException("ValidationException",
                    "One or more parameter values were invalid. Invalid size for parameter "
                    + attributeName + ", Expected: " + dimensions
                    + ", Actual: " + components.size() + " IndexName: " + indexName, 400);
        }
        for (int i = 0; i < components.size(); i++) {
            JsonNode component = components.get(i);
            validateAttributeValueShape(component);
            if (!component.has("N")) {
                throw new AwsException("ValidationException",
                        "One or more parameter values were invalid. Invalid type for parameter "
                        + attributeName + "[" + i + "], Expected: 32-bit floating point number, "
                        + "Actual: " + component.fieldNames().next() + ". IndexName: " + indexName, 400);
            }
        }
    }

    private void validateIndexKeySchema(TableDefinition table, JsonNode item,
                                        String indexName, List<KeySchemaElement> keySchema,
                                        boolean isUpdate) {
        if (keySchema == null) {
            return;
        }
        for (KeySchemaElement element : keySchema) {
            String attrName = element.getAttributeName();
            JsonNode attr = item.get(attrName);
            if (attr == null) {
                continue;
            }
            validateAttributeValueShape(attr);
            String expectedType = keyAttributeType(table, attrName);
            if (expectedType != null && !attr.has(expectedType)) {
                throw new KeySchemaMismatchException(
                        "One or more parameter values were invalid: Type mismatch for Index Key " + attrName
                        + " Expected: " + expectedType + " Actual: " + attr.fieldNames().next()
                        + " IndexName: " + indexName);
            }
            if (attr.has("S") && attr.get("S").asText().isEmpty()) {
                throw emptyIndexKeyValue("empty string value", indexName, attrName, isUpdate);
            }
            if (attr.has("B") && attr.get("B").asText().isEmpty()) {
                throw emptyIndexKeyValue("empty binary value", indexName, attrName, isUpdate);
            }
        }
    }

    // AWS uses different wording for UpdateItem than for writes of a whole item.
    private static AwsException emptyIndexKeyValue(String what, String indexName, String attrName,
                                                   boolean isUpdate) {
        if (isUpdate) {
            return new AwsException("ValidationException",
                    "One or more parameter values are not valid. The update expression attempted to "
                    + "update a secondary index key to a value that is not supported. "
                    + "The AttributeValue for a key attribute cannot contain an " + what + ".", 400);
        }
        return new AwsException("ValidationException",
                "One or more parameter values are not valid. A value specified for a secondary "
                + "index key is not supported. The AttributeValue for a key attribute cannot "
                + "contain an " + what + ". IndexName: " + indexName + ", IndexKey: " + attrName, 400);
    }

    private String buildItemKeyFromNode(JsonNode item, String pkName, String skName) {
        return buildItemKeyFromNode(item, pkName, skName == null ? List.of() : List.of(skName));
    }

    // Builds a cursor-matching string from the partition key plus every sort-key component in
    // key-schema order. A composite (multi-RANGE) index cursor must incorporate all components,
    // otherwise rows sharing the first RANGE value collapse to the same key and pagination can
    // skip or duplicate items. See floci-io/floci#1675.
    private String buildItemKeyFromNode(JsonNode item, String pkName, List<String> skNames) {
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr == null) return "";
        StringBuilder key = new StringBuilder(encodeKeySegment(extractScalarValue(pkAttr)));
        for (String skName : skNames) {
            JsonNode skAttr = item.get(skName);
            if (skAttr != null) {
                key.append("#").append(encodeKeySegment(extractScalarValue(skAttr)));
            }
        }
        return key.toString();
    }

    JsonNode buildKeyNode(TableDefinition table, JsonNode item, String pkName, String skName) {
        return buildKeyNode(table, item, pkName, skName, false);
    }

    JsonNode buildKeyNode(TableDefinition table, JsonNode item,
                          String pkName, String skName, boolean isIndexQuery) {
        return buildKeyNode(table, item, pkName,
                skName == null ? List.of() : List.of(skName), isIndexQuery);
    }

    // Emits a LastEvaluatedKey carrying the partition key plus every sort-key component in
    // key-schema order (a composite index has more than one), and, for index queries, the base
    // table key so the cursor uniquely identifies a row. Emitting only the first RANGE attribute
    // loses composite key identity. See floci-io/floci#1675.
    JsonNode buildKeyNode(TableDefinition table, JsonNode item,
                          String pkName, List<String> skNames, boolean isIndexQuery) {
        ObjectNode keyNode =
                JsonNodeFactory.instance.objectNode();
        JsonNode pkAttr = item.get(pkName);
        if (pkAttr != null) {
            keyNode.set(pkName, pkAttr);
        }
        for (String skName : skNames) {
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
            if (tableSk != null && !skNames.contains(tableSk) && item.get(tableSk) != null) {
                keyNode.set(tableSk, item.get(tableSk));
            }
        }
        return keyNode;
    }

    int computeSegment(JsonNode pkAttr, int totalSegments) {
        if (totalSegments <= 1 || pkAttr == null) {
            return 0;
        }
        String scalar = extractScalarValue(pkAttr);
        if (scalar == null) {
            return 0;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(scalar.getBytes(StandardCharsets.UTF_8));
            long val = ((long) (digest[0] & 0xFF) << 24)
                    | ((long) (digest[1] & 0xFF) << 16)
                    | ((long) (digest[2] & 0xFF) << 8)
                    | ((long) (digest[3] & 0xFF));
            return (int) (val % totalSegments);
        } catch (NoSuchAlgorithmException e) {
            return Math.floorMod(scalar.hashCode(), totalSegments);
        }
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
                if (attrValue == null || compareAttr == null) {
                    yield false;
                }
                // Deep equality, so set/list/map values compare by content rather than
                // collapsing to an empty scalar and matching everything.
                yield attributeValuesEqual(attrValue, compareAttr);
            }
            case "NE" -> {
                if (attrValue == null || compareAttr == null) {
                    yield true;
                }
                yield !attributeValuesEqual(attrValue, compareAttr);
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

    private void validateExclusiveStartKeyWithinQuery(JsonNode exclusiveStartKey, TableDefinition table,
                                                      DynamoDbAccessPath accessPath, JsonNode keyConditions,
                                                      String keyConditionExpression, JsonNode expressionAttrValues,
                                                      JsonNode expressionAttrNames) {
        if (exclusiveStartKey == null || exclusiveStartKey.isNull()) {
            return;
        }
        DynamoDbAccessPathValidator.validateExclusiveStartKey(exclusiveStartKey, table, accessPath, false);

        boolean withinBounds = keyConditionExpression != null
                ? ExpressionEvaluator.matches(keyConditionExpression, exclusiveStartKey,
                        expressionAttrNames, expressionAttrValues)
                : keyConditions.properties().stream().allMatch(entry ->
                        matchesKeyCondition(exclusiveStartKey.get(entry.getKey()), entry.getValue()));
        if (!withinBounds) {
            throw new AwsException("ValidationException",
                    "The provided starting key is outside query boundaries based on provided condition", 400);
        }
    }

    private List<JsonNode> queryWithExpression(ConcurrentSkipListMap<String, JsonNode> items,
                                                String partitionKeyName,
                                                String partitionKeyValuePlaceholder,
                                                String expression,
                                                JsonNode expressionAttrValues,
                                                JsonNode exprAttrNames) {
        List<JsonNode> results = new ArrayList<>();
        String partitionKeyValue = expressionAttrValues != null && partitionKeyValuePlaceholder != null
                ? extractScalarValue(expressionAttrValues.get(partitionKeyValuePlaceholder))
                : null;
        for (JsonNode item : items.values()) {
            if (partitionKeyValue != null
                    && !matchesAttributeValue(item.get(partitionKeyName), partitionKeyValue)) {
                continue;
            }
            if (ExpressionEvaluator.matches(expression, item, exprAttrNames, expressionAttrValues)) {
                results.add(item);
            }
        }
        return results;
    }

    private AwsException resourceNotFoundException(String tableName) {
        return new AwsException("ResourceNotFoundException",
                "Requested resource not found: Table: " + tableName + " not found", 400);
    }

    /** Item calls never name the table, and they treat a CREATING table as absent. */
    private static AwsException itemCallResourceNotFound() {
        return new AwsException("ResourceNotFoundException", "Requested resource not found", 400);
    }

    private TableDefinition requireActiveTable(String storageKey) {
        TableDefinition table = tableStore.get(storageKey)
                .orElseThrow(DynamoDbService::itemCallResourceNotFound);
        if ("CREATING".equals(table.getTableStatus())) {
            throw itemCallResourceNotFound();
        }
        return table;
    }

    /** Resolves a table for an item call, so a miss reports the message AWS uses there. */
    public TableDefinition requireTableForItemCall(String tableName, String region) {
        String canonicalTableName = canonicalTableName(region, tableName);
        return requireActiveTable(regionKey(region, canonicalTableName));
    }

    public record UpdateResult(JsonNode newItem, JsonNode oldItem, List<TouchedPath> touched) {}

    // One path the update expression acted on, with the value there before and after.
    public record TouchedPath(List<Object> tokens, JsonNode oldValue, JsonNode newValue) {}

    public record TransactWriteResult(Map<String, DynamoDbWriteCapacity.Cost> capacity, boolean replayed) {}

    public record TransactGetResult(List<JsonNode> items, Map<String, DynamoDbWriteCapacity.Cost> capacity) {}

    // scannedBytes carries the pre-filter size of the read items: DynamoDB bills a
    // Query or Scan on what it read, not on what survived the filter or projection.
    public record ScanResult(List<JsonNode> items, int scannedCount, long scannedBytes, JsonNode lastEvaluatedKey,
                            List<JsonNode> scannedItems) {}
    public record QueryResult(List<JsonNode> items, int scannedCount, long scannedBytes, JsonNode lastEvaluatedKey,
                              List<JsonNode> scannedItems) {}

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
        var exportId = newJobId();
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

        ConcurrentSkipListMap<String, JsonNode> tableItems = itemsByTable.get(scopedItemsKey(storageKey));
        List<JsonNode> snapshot = tableItems != null
                ? List.copyOf(tableItems.values())
                : List.of();

        // The worker gets its own copy so the object serialized into this response never
        // changes underneath the handler, and the request account so its writes land there.
        var accountId = regionResolver.getAccountId();
        var worker = objectMapper.convertValue(desc, ExportDescription.class);
        Thread.ofVirtual().start(() -> RequestScopes.runAs(accountId, () -> runExport(worker, snapshot, exportArn)));

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
            ObjectNode root = objectMapper.createObjectNode();
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

            ArrayNode outputFiles = root.putArray("outputFiles");
            ObjectNode fileEntry = outputFiles.addObject();
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
        var all = exportStore.scan(k -> true).stream()
                .filter(d -> tableArn == null || tableArn.equals(d.getTableArn()))
                .toList();
        requirePageSize(maxResults, "maxResults");
        var page = pageByArn(all, ExportDescription::getExportArn, maxResults, nextToken);
        return new ListExportsResult(page.items().stream().map(ExportSummary::new).toList(), page.nextToken());
    }

    private record Page<T>(List<T> items, String nextToken) {}

    private static final int MAX_JOB_PAGE_SIZE = 25;

    private static void requirePageSize(Integer pageSize, String field) {
        if (pageSize == null) {
            return;
        }
        if (pageSize < 1) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + pageSize + "' at '" + field
                    + "' failed to satisfy constraint: Member must have value greater than or equal to 1", 400);
        }
        if (MAX_JOB_PAGE_SIZE < pageSize) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + pageSize + "' at '" + field
                    + "' failed to satisfy constraint: Member must have value less than or equal to "
                    + MAX_JOB_PAGE_SIZE, 400);
        }
    }

    private static <T> Page<T> pageByArn(List<T> all, Function<T, String> arnOf, Integer pageSize, String nextToken) {
        var limit = pageSize != null ? pageSize : MAX_JOB_PAGE_SIZE;
        var sorted = all.stream().sorted(Comparator.comparing(arnOf).reversed()).toList();
        var startIdx = 0;
        if (nextToken != null) {
            for (var i = 0; i < sorted.size(); i++) {
                if (nextToken.equals(arnOf.apply(sorted.get(i)))) {
                    startIdx = i + 1;
                    break;
                }
            }
        }
        var end = Math.min(startIdx + limit, sorted.size());
        var newNextToken = end < sorted.size() ? arnOf.apply(sorted.get(end - 1)) : null;
        return new Page<>(sorted.subList(startIdx, end), newNextToken);
    }

    private static String newJobId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    // --- Import Operations ---

    /**
     * Checks an ImportTable request before the target table exists, so a rejected request
     * leaves nothing behind. Returns the import already started with the same ClientToken,
     * or null when this is a new import.
     */
    public ImportTableDescription validateImportRequest(JsonNode request) {
        if (request.path("S3BucketSource").path("S3Bucket").asText("").isBlank()) {
            throw new AwsException("ValidationException", "S3BucketSource.S3Bucket is required", 400);
        }
        var inputFormat = request.path("InputFormat").asText("");
        switch (inputFormat) {
            case "DYNAMODB_JSON" -> { }
            case "" -> throw new AwsException("ValidationException", "InputFormat is required", 400);
            case "CSV", "ION" -> throw new AwsException("ValidationException",
                    "Unsupported InputFormat: " + inputFormat + ". Floci imports DYNAMODB_JSON only", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + inputFormat
                    + "' at 'inputFormat' failed to satisfy constraint: Member must satisfy enum value set: [ION, CSV, DYNAMODB_JSON]", 400);
        }
        var compression = request.path("InputCompressionType").asText("NONE");
        switch (compression) {
            case "NONE", "GZIP" -> { }
            case "ZSTD" -> throw new AwsException("ValidationException",
                    "Unsupported InputCompressionType: ZSTD. Floci accepts NONE or GZIP", 400);
            default -> throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + compression
                    + "' at 'inputCompressionType' failed to satisfy constraint: Member must satisfy enum value set: [GZIP, ZSTD, NONE]", 400);
        }
        DynamoDbTableNames.requireShortName(request.path("TableCreationParameters").path("TableName").asText(null));
        var clientToken = request.path("ClientToken").asText(null);
        if (clientToken != null && clientToken.isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + clientToken
                    + "' at 'clientToken' failed to satisfy constraint: Member must have length greater than or equal to 1", 400);
        }
        if (clientToken == null || importStore == null) {
            return null;
        }
        var existing = importStore.scan(k -> true).stream()
                .filter(d -> clientToken.equals(d.getClientToken()))
                .findFirst()
                .orElse(null);
        if (existing != null && !sameImportParameters(existing, request)) {
            throw new AwsException("ImportConflictException",
                    "There was a conflict when importing from the specified S3 source. This can occur when "
                    + "the current import conflicts with a previous import request that had the same client token.", 400);
        }
        return existing;
    }

    private static boolean sameImportParameters(ImportTableDescription existing, JsonNode request) {
        var options = request.hasNonNull("InputFormatOptions") ? request.get("InputFormatOptions") : null;
        return Objects.equals(existing.getS3BucketSource(), request.get("S3BucketSource"))
                && Objects.equals(existing.getInputFormat(), request.path("InputFormat").asText())
                && Objects.equals(existing.getInputFormatOptions(), options)
                && Objects.equals(existing.getInputCompressionType(), request.path("InputCompressionType").asText("NONE"))
                && Objects.equals(existing.getTableCreationParameters(), request.get("TableCreationParameters"));
    }

    public ImportTableDescription startImport(JsonNode request, TableDefinition table, String region) {
        var tableName = table.getTableName();
        var accountId = regionResolver.getAccountId();
        var importArn = AwsArnUtils.Arn.of("dynamodb", region, accountId,
                "table/" + tableName + "/import/" + newJobId()).toString();

        var desc = new ImportTableDescription();
        desc.setImportArn(importArn);
        desc.setImportStatus("IN_PROGRESS");
        desc.setTableArn(table.getTableArn());
        desc.setTableId(table.getTableId());
        desc.setClientToken(request.path("ClientToken").asText(null));
        desc.setS3BucketSource(request.get("S3BucketSource"));
        desc.setInputFormat(request.path("InputFormat").asText());
        if (request.hasNonNull("InputFormatOptions")) {
            desc.setInputFormatOptions(request.get("InputFormatOptions"));
        }
        desc.setInputCompressionType(request.path("InputCompressionType").asText("NONE"));
        desc.setTableCreationParameters(request.get("TableCreationParameters"));
        desc.setCloudWatchLogGroupArn(AwsArnUtils.Arn.of("logs", region, accountId,
                "log-group:/aws-dynamodb/imports:*").toString());
        desc.setStartTime(Instant.now().getEpochSecond());
        if (importStore != null) {
            importStore.put(importArn, desc);
        }

        // The worker gets its own copy so the object serialized into this response never
        // changes underneath the handler, and the request account so its writes land there.
        var worker = objectMapper.convertValue(desc, ImportTableDescription.class);
        Thread.ofVirtual().start(() -> RequestScopes.runAs(accountId, () -> runImport(worker, tableName, region)));
        return desc;
    }

    void runImport(ImportTableDescription desc, String tableName, String region) {
        var source = desc.getS3BucketSource();
        var bucket = source.path("S3Bucket").asText();
        var prefix = source.path("S3KeyPrefix").asText("");
        var bucketOwner = source.path("S3BucketOwner").asText(null);
        try {
            // AWS reads another account's bucket only when its policy grants it. floci has no bucket policies.
            if (bucketOwner != null && !bucketOwner.equals(regionResolver.getAccountId())) {
                throw new AwsException("AccessDenied",
                        "Access Denied (Service: Amazon S3; Status Code: 403; Error Code: AccessDenied)", 403);
            }
            var objects = s3Service.listObjects(bucket, prefix, null, Integer.MAX_VALUE);
            if (objects.isEmpty()) {
                failImport(desc, "S3NoSuchKey", "No objects found under s3://" + bucket + "/" + prefix);
            } else {
                for (var object : objects) {
                    try {
                        importObject(desc, tableName, region, bucket, object.getKey());
                    } catch (IOException | RuntimeException e) {
                        desc.setErrorCount(desc.getErrorCount() + 1);
                        LOG.warnv("Import {0} skipped object {1}: {2}", desc.getImportArn(), object.getKey(), e.getMessage());
                    }
                }
                desc.setImportStatus("COMPLETED");
            }
        } catch (AwsException e) {
            switch (e.getErrorCode()) {
                case "NoSuchBucket" -> failImport(desc, "S3NoSuchBucket", "The specified bucket does not exist: " + bucket);
                case "AccessDenied" -> failImport(desc, "S3AccessDenied", e.getMessage());
                default -> {
                    LOG.errorv(e, "Import failed: {0}", desc.getImportArn());
                    failImport(desc, "S3" + e.getErrorCode(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.errorv(e, "Import failed: {0}", desc.getImportArn());
            failImport(desc, "UNKNOWN", e.getMessage());
        }
        persistItems(regionKey(region, tableName));
        desc.setEndTime(Instant.now().getEpochSecond());
        activateTable(tableName, region);
        if (importStore != null) {
            importStore.put(desc.getImportArn(), desc);
        }
        LOG.infov("Import {0} {1}: imported={2}, errors={3}", desc.getImportArn(),
                desc.getImportStatus(), desc.getImportedItemCount(), desc.getErrorCount());
    }

    private void importObject(ImportTableDescription desc, String tableName, String region,
                              String bucket, String key) throws IOException {
        var size = s3Service.getObjectMetadata(bucket, key, null).getSize();
        desc.setProcessedSizeBytes(desc.getProcessedSizeBytes() + size);
        try (var raw = s3Service.openObjectStream(bucket, key, null);
             var reader = new BufferedReader(new InputStreamReader(decompress(desc, raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                desc.setProcessedItemCount(desc.getProcessedItemCount() + 1);
                try {
                    var item = objectMapper.readTree(line).path("Item");
                    if (!item.isObject()) {
                        throw new IOException("line has no Item object");
                    }
                    putItemInternal(tableName, item, null, null, null, region, "NONE", false, event -> { });
                    desc.setImportedItemCount(desc.getImportedItemCount() + 1);
                } catch (IOException | RuntimeException e) {
                    desc.setErrorCount(desc.getErrorCount() + 1);
                    LOG.debugv("Import {0} skipped a line of {1}: {2}", desc.getImportArn(), key, e.getMessage());
                }
            }
        }
    }

    private static InputStream decompress(ImportTableDescription desc, InputStream raw) throws IOException {
        return "GZIP".equals(desc.getInputCompressionType()) ? new GZIPInputStream(raw) : raw;
    }

    private static void requireNotCreating(TableDefinition table) {
        if ("CREATING".equals(table.getTableStatus())) {
            throw new AwsException("ResourceInUseException",
                    "Attempt to change a resource which is still in use: Table is being created: "
                    + table.getTableName(), 400);
        }
    }

    private static void failImport(ImportTableDescription desc, String code, String message) {
        desc.setImportStatus("FAILED");
        desc.setFailureCode(code);
        desc.setFailureMessage(message);
    }

    private void activateTable(String tableName, String region) {
        var storageKey = regionKey(region, tableName);
        tableStore.get(storageKey).ifPresent(table -> {
            if ("CREATING".equals(table.getTableStatus())) {
                table.setTableStatus("ACTIVE");
                tableStore.put(storageKey, table);
            }
        });
    }

    /**
     * A job interrupted by a restart would otherwise stay IN_PROGRESS forever, since no
     * worker survives the process. An import also leaves its table stuck in CREATING.
     */
    private void recoverInterruptedJobs() {
        var now = Instant.now().getEpochSecond();
        if (exportStore instanceof AccountAwareStorageBackend<ExportDescription> exports) {
            for (var entry : exports.scanAllAccountEntries(k -> true)) {
                var desc = entry.value();
                if (!"IN_PROGRESS".equals(desc.getExportStatus())) {
                    continue;
                }
                desc.setExportStatus("FAILED");
                desc.setFailureCode("InterruptedByRestart");
                desc.setFailureMessage("The emulator restarted before the export finished");
                desc.setEndTime(now);
                exports.putForAccount(entry.accountId(), entry.key(), desc);
            }
        }
        if (importStore instanceof AccountAwareStorageBackend<ImportTableDescription> imports) {
            for (var entry : imports.scanAllAccountEntries(k -> true)) {
                var desc = entry.value();
                if (!"IN_PROGRESS".equals(desc.getImportStatus())) {
                    continue;
                }
                failImport(desc, "InterruptedByRestart", "The emulator restarted before the import finished");
                desc.setEndTime(now);
                imports.putForAccount(entry.accountId(), entry.key(), desc);
            }
        }
        if (tableStore instanceof AccountAwareStorageBackend<TableDefinition> tables) {
            for (var entry : tables.scanAllAccountEntries(k -> true)) {
                var table = entry.value();
                if ("CREATING".equals(table.getTableStatus())) {
                    table.setTableStatus("ACTIVE");
                    tables.putForAccount(entry.accountId(), entry.key(), table);
                }
            }
        }
    }

    public ImportTableDescription describeImport(String importArn) {
        return Optional.ofNullable(importStore)
                .flatMap(store -> store.get(importArn))
                .orElseThrow(() -> new AwsException("ImportNotFoundException",
                        "The specified import was not found.", 400));
    }

    public record ListImportsResult(List<ImportSummary> importSummaryList, String nextToken) {}

    public ListImportsResult listImports(String tableArn, Integer pageSize, String nextToken) {
        if (importStore == null) {
            return new ListImportsResult(List.of(), null);
        }
        var all = importStore.scan(k -> true).stream()
                .filter(d -> tableArn == null || tableArn.equals(d.getTableArn()))
                .toList();
        requirePageSize(pageSize, "pageSize");
        var page = pageByArn(all, ImportTableDescription::getImportArn, pageSize, nextToken);
        return new ListImportsResult(page.items().stream().map(ImportSummary::new).toList(), page.nextToken());
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (TableDefinition table : tableStore.scan(k -> true)) {
            if (table.getTableArn() == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(table.getTableArn());
            resources.add(new ExplorerResource(
                    table.getTableArn(), "dynamodb:table", "dynamodb",
                    parsed.region(), parsed.accountId(),
                    table.getCreationDateTime() != null ? table.getCreationDateTime() : Instant.now(),
                    table.getTags() != null ? table.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("dynamodb:table", "dynamodb", true));
    }
}
