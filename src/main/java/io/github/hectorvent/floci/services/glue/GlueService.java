package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.SchemaReference;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.model.UserDefinedFunction;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.SchemaToColumnsConverter;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);
    private static final int MAX_FUNCTION_PATTERN_LENGTH = 255;
    private static final int MAX_FUNCTION_RESULTS = 100;
    static final String COLUMN_NAME = "ColumnName";
    static final String COLUMN_TYPE = "ColumnType";
    static final String ANALYZED_TIME = "AnalyzedTime";
    static final String STATISTICS_DATA = "StatisticsData";
    private static final Pattern COMPARISON_EXPRESSION = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(=|<>|<=|>=|<|>)\\s*('?[^']*'?|[^\\s]+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_EXPRESSION = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s*\\((.*)\\)\\s*",
            Pattern.CASE_INSENSITIVE);

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Table> tableVersionStore;
    private final StorageBackend<String, Map<String, Object>> columnStatisticsStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore;
    private final StorageBackend<String, UserDefinedFunction> functionStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;
    private final ResourceGroupsTaggingService resourceGroupsTaggingService;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver,
                       ResourceGroupsTaggingService resourceGroupsTaggingService) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<>() {});
        this.tableVersionStore = storageFactory.create("glue", "table_versions.json", new TypeReference<>() {});
        this.columnStatisticsStore = storageFactory.create("glue", "column_statistics.json", new TypeReference<>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<>() {});
        this.partitionColumnStatisticsStore = storageFactory.create(
                "glue", "partition_column_statistics.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("glue", "functions.json", new TypeReference<>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.resourceGroupsTaggingService = resourceGroupsTaggingService;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, Table> tableVersionStore,
                StorageBackend<String, Map<String, Object>> columnStatisticsStore,
                StorageBackend<String, Partition> partitionStore,
                StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore,
                StorageBackend<String, UserDefinedFunction> functionStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver,
                ResourceGroupsTaggingService resourceGroupsTaggingService) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.tableVersionStore = tableVersionStore;
        this.columnStatisticsStore = columnStatisticsStore;
        this.partitionStore = partitionStore;
        this.partitionColumnStatisticsStore = partitionColumnStatisticsStore;
        this.functionStore = functionStore;
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.resourceGroupsTaggingService = resourceGroupsTaggingService;
    }

    public void createDatabase(Database database) {
        createDatabase(database, null, regionResolver.getDefaultRegion());
    }

    public void createDatabase(Database database, Map<String, String> tags, String region) {
        String databaseName = normalizeName(database.getName());
        if (databaseStore.get(databaseName).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Database already exists: " + database.getName(), 400);
        }
        database.setName(databaseName);
        databaseStore.put(databaseName, database);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(databaseArn(region, databaseName)), tags, region);
        }
        LOG.infov("Created Glue Database: {0}", database.getName());
    }

    public Database getDatabase(String name) {
        return databaseStore.get(normalizeName(name))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Database not found: " + name, 400));
    }

    public List<Database> getDatabases() {
        return databaseStore.scan(k -> true);
    }

    public void updateDatabase(String name, Database database) {
        Database existing = getDatabase(name);
        String databaseName = normalizeName(database.getName());
        if (!existing.getName().equals(databaseName)) {
            throw new AwsException("InvalidInputException", "Database cannot be renamed", 400);
        }
        Database updated = new Database();
        updated.setName(databaseName);
        updated.setDescription(database.getDescription());
        updated.setLocationUri(database.getLocationUri());
        updated.setParameters(database.getParameters() == null ? null : new LinkedHashMap<>(database.getParameters()));
        updated.setCreateTime(existing.getCreateTime());
        databaseStore.put(databaseName, updated);
        LOG.infov("Updated Glue Database: {0}", databaseName);
    }

    public void deleteDatabase(String name) {
        deleteDatabase(name, regionResolver.getDefaultRegion());
    }

    public void deleteDatabase(String name, String region) {
        String databaseName = getDatabase(name).getName();
        List<String> tableNames = tableStore.scan(k -> true).stream()
                .filter(table -> databaseName.equals(table.getDatabaseName()))
                .map(Table::getName)
                .toList();
        tableNames.forEach(tableName -> deleteTable(name, tableName));
        databaseStore.delete(databaseName);
        resourceGroupsTaggingService.deleteResources(List.of(databaseArn(region, databaseName)), region);
        LOG.infov("Deleted Glue Database: {0}", name);
    }

    public void createTable(String databaseName, Table table) {
        Database database = getDatabase(databaseName);
        String key = tableKey(databaseName, table.getName());
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setName(normalizeName(table.getName()));
        table.setDatabaseName(database.getName());
        if (table.getCreateTime() == null) {
            table.setCreateTime(Instant.now());
        }
        table.setVersionId("0");
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        Table table = tableStore.get(tableKey(databaseName, tableName))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
        return withResolvedSchemaReference(table);
    }

    public List<Table> getTables(String databaseName) {
        String prefix = normalizeName(databaseName) + ":";
        List<Table> tables = tableStore.scan(k -> k.startsWith(prefix));
        List<Table> resolved = new ArrayList<>(tables.size());
        for (Table table : tables) {
            resolved.add(withResolvedSchemaReference(table));
        }
        return resolved;
    }

    public synchronized void updateTable(String databaseName, Table table, String versionId, boolean skipArchive) {
        Database database = getDatabase(databaseName);
        String key = tableKey(databaseName, table.getName());
        Table existing = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Table not found: " + databaseName + "." + table.getName(), 400));
        if (versionId != null && !versionId.equals(existing.getVersionId())) {
            throw new AwsException("ConcurrentModificationException",
                    "Update table failed due to concurrent modifications.", 400);
        }
        if (!skipArchive) {
            tableVersionStore.put(tableVersionKey(existing.getDatabaseName(), existing.getName(), existing.getVersionId()),
                    copyTable(existing));
        }
        validateSchemaReference(table);
        table.setName(normalizeName(table.getName()));
        table.setDatabaseName(database.getName());
        table.setCreateTime(existing.getCreateTime());
        table.setUpdateTime(Instant.now());
        table.setVersionId(nextVersionId(existing.getVersionId()));
        tableStore.put(key, table);
        LOG.infov("Updated Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public List<Map<String, Object>> getTableVersions(String databaseName, String tableName) {
        Table current = getTable(databaseName, tableName);
        String prefix = tableKey(databaseName, tableName) + ":";
        List<Table> versions = new ArrayList<>();
        versions.add(current);
        versions.addAll(tableVersionStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(GlueService::versionIdAsLong).reversed())
                .toList());
        return versions.stream()
                .map(table -> Map.<String, Object>of(
                        "Table", withResolvedSchemaReference(table),
                        "VersionId", table.getVersionId()))
                .toList();
    }

    public void deleteTable(String databaseName, String tableName) {
        getTable(databaseName, tableName);
        String key = tableKey(databaseName, tableName);
        tableStore.delete(key);
        tableVersionStore.keys().stream()
                .filter(versionKey -> versionKey.startsWith(key + ":"))
                .forEach(tableVersionStore::delete);
        columnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(columnStatisticsStore::delete);
        partitionStore.keys().stream()
                .filter(partitionKey -> partitionKey.startsWith(key + ":"))
                .forEach(partitionStore::delete);
        partitionColumnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public List<BatchDeleteTableError> batchDeleteTables(String databaseName, List<String> tableNames) {
        getDatabase(databaseName);
        List<BatchDeleteTableError> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String key = tableKey(databaseName, tableName);
            Optional<Table> table = tableStore.get(key);
            if (table.isEmpty()) {
                errors.add(new BatchDeleteTableError(
                        tableName,
                        new ErrorDetail("EntityNotFoundException", "Table " + tableName + " not found")));
                continue;
            }
            deleteTable(databaseName, tableName);
        }
        return errors;
    }

    public void updateColumnStatisticsForTable(
            String databaseName,
            String tableName,
            List<Map<String, Object>> columnStatistics) {
        Table table = getTable(databaseName, tableName);
        for (Map<String, Object> statistics : columnStatistics) {
            String columnNameString = requireColumnStatisticsString(statistics, COLUMN_NAME);
            requireColumnStatisticsString(statistics, COLUMN_TYPE);
            requireColumnStatisticsField(statistics, ANALYZED_TIME);
            requireColumnStatisticsField(statistics, STATISTICS_DATA);
            columnStatisticsStore.put(
                    columnStatisticsKey(table.getDatabaseName(), table.getName(), columnNameString),
                    new LinkedHashMap<>(statistics));
        }
    }

    public ColumnStatisticsResult getColumnStatisticsForTable(
            String databaseName,
            String tableName,
            List<String> columnNames) {
        Table table = getTable(databaseName, tableName);
        List<Map<String, Object>> columnStatistics = new ArrayList<>();
        List<ColumnError> errors = new ArrayList<>();
        for (String columnName : columnNames) {
            Optional<Map<String, Object>> statistics =
                    columnStatisticsStore.get(columnStatisticsKey(table.getDatabaseName(), table.getName(), columnName));
            if (statistics.isPresent()) {
                columnStatistics.add(new LinkedHashMap<>(statistics.get()));
            }
            else {
                errors.add(new ColumnError(
                        columnName,
                        new ErrorDetail("EntityNotFoundException", "Statistics do not exist for this column")));
            }
        }
        return new ColumnStatisticsResult(columnStatistics, errors);
    }

    public void deleteColumnStatisticsForTable(String databaseName, String tableName, String columnName) {
        getTable(databaseName, tableName);
        columnStatisticsStore.delete(columnStatisticsKey(databaseName, tableName, columnName));
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        Table table = getTable(databaseName, tableName);
        String key = partitionKey(databaseName, tableName, partition.getValues());
        partition.setDatabaseName(table.getDatabaseName());
        partition.setTableName(table.getName());
        if (partition.getCreationTime() == null) {
            partition.setCreationTime(Instant.now());
        }
        partitionStore.put(key, partition);
    }

    public List<BatchCreatePartitionError> batchCreatePartitions(
            String databaseName,
            String tableName,
            List<Partition> partitions) {
        getTable(databaseName, tableName);
        List<BatchCreatePartitionError> errors = new ArrayList<>();
        for (Partition partition : partitions) {
            String key = partitionKey(databaseName, tableName, partition.getValues());
            if (partitionStore.get(key).isPresent()) {
                errors.add(new BatchCreatePartitionError(
                        partition.getValues(),
                        new ErrorDetail("AlreadyExistsException", "Partition already exists.")));
                continue;
            }
            createPartition(databaseName, tableName, partition);
        }
        return errors;
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        return getPartitions(databaseName, tableName, null);
    }

    public Partition getPartition(String databaseName, String tableName, List<String> partitionValues) {
        getTable(databaseName, tableName);
        return partitionStore.get(partitionKey(databaseName, tableName, partitionValues))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Cannot find partition.", 400));
    }

    public List<Partition> batchGetPartitions(String databaseName, String tableName, List<List<String>> partitionValues) {
        getTable(databaseName, tableName);
        List<Partition> partitions = new ArrayList<>();
        for (List<String> values : partitionValues) {
            partitionStore.get(partitionKey(databaseName, tableName, values)).ifPresent(partitions::add);
        }
        return partitions;
    }

    public List<BatchUpdatePartitionError> batchUpdatePartitions(
            String databaseName,
            String tableName,
            List<BatchUpdatePartitionEntry> entries) {
        Table table = getTable(databaseName, tableName);
        List<BatchUpdatePartitionError> errors = new ArrayList<>();
        for (BatchUpdatePartitionEntry entry : entries) {
            String key = partitionKey(databaseName, tableName, entry.partitionValueList());
            Optional<Partition> existing = partitionStore.get(key);
            if (existing.isEmpty()) {
                errors.add(new BatchUpdatePartitionError(
                        entry.partitionValueList(),
                        new ErrorDetail(
                                "EntityNotFoundException",
                                "Partition [" + String.join(", ", entry.partitionValueList()) + "] not found")));
                continue;
            }

            putUpdatedPartition(table, key, existing.get(), entry.partitionInput());
        }
        return errors;
    }

    public List<Partition> getPartitions(String databaseName, String tableName, String expression) {
        Table table = getTable(databaseName, tableName);
        String prefix = tableKey(databaseName, tableName) + ":";
        return partitionStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(partition -> matchesPartitionExpression(table, partition, expression))
                .sorted(GlueService::comparePartitionValues)
                .toList();
    }

    // The partition store scan returns values in storage iteration order, which is not stable.
    // Return partitions ordered by their values so GetPartitions is deterministic across calls.
    private static int comparePartitionValues(Partition a, Partition b) {
        List<String> aValues = a.getValues() == null ? List.of() : a.getValues();
        List<String> bValues = b.getValues() == null ? List.of() : b.getValues();
        int shared = Math.min(aValues.size(), bValues.size());
        for (int i = 0; i < shared; i++) {
            int comparison = Comparator.nullsFirst(String::compareTo).compare(aValues.get(i), bValues.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(aValues.size(), bValues.size());
    }

    public void deletePartition(String databaseName, String tableName, List<String> partitionValues) {
        getPartition(databaseName, tableName, partitionValues);
        String key = partitionKey(databaseName, tableName, partitionValues);
        partitionStore.delete(key);
        partitionColumnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
    }

    public void updatePartition(String databaseName, String tableName, List<String> partitionValues, Partition partition) {
        Table table = getTable(databaseName, tableName);
        String key = partitionKey(databaseName, tableName, partitionValues);
        Partition existing = partitionStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Partition not found.", 400));
        putUpdatedPartition(table, key, existing, partition);
    }

    public void updateColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<Map<String, Object>> columnStatistics) {
        getPartition(databaseName, tableName, partitionValues);
        for (Map<String, Object> statistics : columnStatistics) {
            String columnNameString = requireColumnStatisticsString(statistics, COLUMN_NAME);
            requireColumnStatisticsString(statistics, COLUMN_TYPE);
            requireColumnStatisticsField(statistics, ANALYZED_TIME);
            requireColumnStatisticsField(statistics, STATISTICS_DATA);
            partitionColumnStatisticsStore.put(
                    partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnNameString),
                    new LinkedHashMap<>(statistics));
        }
    }

    public ColumnStatisticsResult getColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<String> columnNames) {
        getPartition(databaseName, tableName, partitionValues);
        List<Map<String, Object>> columnStatistics = new ArrayList<>();
        List<ColumnError> errors = new ArrayList<>();
        for (String columnName : columnNames) {
            partitionColumnStatisticsStore.get(partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName))
                    .ifPresentOrElse(columnStatistics::add, () -> errors.add(columnStatisticsNotFoundError(columnName)));
        }
        return new ColumnStatisticsResult(columnStatistics, errors);
    }

    public void deleteColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            String columnName) {
        getPartition(databaseName, tableName, partitionValues);
        partitionColumnStatisticsStore.delete(partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName));
    }

    public void createUserDefinedFunction(String databaseName, UserDefinedFunction function) {
        Database database = getDatabase(databaseName);
        String key = functionKey(databaseName, function.getFunctionName());
        if (functionStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Function already exists: " + databaseName + "." + function.getFunctionName(), 400);
        }
        function.setDatabaseName(database.getName());
        function.setFunctionName(normalizeName(function.getFunctionName()));
        function.setCreateTime(Instant.now());
        functionStore.put(key, function);
    }

    public UserDefinedFunction getUserDefinedFunction(String databaseName, String functionName) {
        getDatabase(databaseName);
        return functionStore.get(functionKey(databaseName, functionName))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Function not found: " + databaseName + "." + functionName, 400));
    }

    public List<UserDefinedFunction> getUserDefinedFunctions(String databaseName, String pattern) {
        return getUserDefinedFunctions(databaseName, pattern, null, null, null).functions();
    }

    public UserDefinedFunctionPage getUserDefinedFunctions(
            String databaseName,
            String pattern,
            String functionType,
            Integer maxResults,
            String nextToken) {
        String databaseNameFilter = databaseName == null ? null : getDatabase(databaseName).getName();
        Pattern compiledPattern = compileFunctionPattern(pattern);
        int offset = decodeFunctionNextToken(nextToken);
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_FUNCTION_RESULTS)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 100", 400);
        }
        List<UserDefinedFunction> functions = functionStore.scan(k -> true).stream()
                .filter(function -> databaseNameFilter == null || databaseNameFilter.equals(function.getDatabaseName()))
                .filter(function -> functionType == null || functionType.equals(function.getFunctionType()))
                .filter(function -> function.getFunctionName() != null)
                .filter(function -> compiledPattern.matcher(function.getFunctionName()).matches())
                .sorted(Comparator.comparing(
                                UserDefinedFunction::getDatabaseName,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(UserDefinedFunction::getFunctionName, Comparator.nullsFirst(String::compareTo)))
                .toList();
        if (offset > functions.size()) {
            throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
        }
        int limit = maxResults == null ? functions.size() : maxResults;
        int end = Math.min(functions.size(), offset + limit);
        String newNextToken = end < functions.size() ? Integer.toString(end) : null;
        return new UserDefinedFunctionPage(functions.subList(offset, end), newNextToken);
    }

    public void updateUserDefinedFunction(String databaseName, String functionName, UserDefinedFunction function) {
        UserDefinedFunction existing = getUserDefinedFunction(databaseName, functionName);
        function.setDatabaseName(existing.getDatabaseName());
        function.setFunctionName(existing.getFunctionName());
        function.setCreateTime(existing.getCreateTime());
        functionStore.put(functionKey(databaseName, functionName), function);
    }

    public void deleteUserDefinedFunction(String databaseName, String functionName) {
        getUserDefinedFunction(databaseName, functionName);
        functionStore.delete(functionKey(databaseName, functionName));
    }

    private void validateSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return;
        }
        // Throws EntityNotFoundException / InvalidInputException if reference is broken.
        resolveSchemaVersion(ref);
    }

    private Table withResolvedSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return table;
        }
        try {
            SchemaVersion version = resolveSchemaVersion(ref);
            List<Column> columns = SchemaToColumnsConverter.toColumns(
                    version.getDataFormat(), version.getSchemaDefinition());
            if (!columns.isEmpty()) {
                Table resolved = copyTable(table);
                resolved.getStorageDescriptor().setColumns(columns);
                return resolved;
            }
        } catch (AwsException e) {
            LOG.warnv("SchemaReference resolution failed for {0}.{1}: {2}",
                    table.getDatabaseName(), table.getName(), e.getMessage());
        }
        return table;
    }

    private SchemaVersion resolveSchemaVersion(SchemaReference ref) {
        boolean latest = ref.getSchemaVersionId() == null && ref.getSchemaVersionNumber() == null;
        return schemaRegistryService.getSchemaVersion(
                ref.getSchemaId(), ref.getSchemaVersionId(),
                ref.getSchemaVersionNumber(), latest, regionResolver.getDefaultRegion());
    }

    private static SchemaReference schemaReferenceOf(Table table) {
        StorageDescriptor sd = table != null ? table.getStorageDescriptor() : null;
        return sd != null ? sd.getSchemaReference() : null;
    }

    private static String functionKey(String databaseName, String functionName) {
        return normalizeName(databaseName) + ":" + normalizeName(functionName);
    }

    private static String tableKey(String databaseName, String tableName) {
        return normalizeName(databaseName) + ":" + normalizeName(tableName);
    }

    private static String tableVersionKey(String databaseName, String tableName, String versionId) {
        return tableKey(databaseName, tableName) + ":" + versionId;
    }

    private static String columnStatisticsKey(String databaseName, String tableName, String columnName) {
        return tableKey(databaseName, tableName) + ":" + normalizeName(columnName);
    }

    private static String partitionKey(String databaseName, String tableName, List<String> partitionValues) {
        return tableKey(databaseName, tableName) + ":" + String.join(":", partitionValues.stream()
                .map(GlueService::encodePartitionValue)
                .toList());
    }

    private static String encodePartitionValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String partitionColumnStatisticsKey(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            String columnName) {
        return partitionKey(databaseName, tableName, partitionValues) + ":" + normalizeName(columnName);
    }

    private void putUpdatedPartition(Table table, String key, Partition existing, Partition partition) {
        partition.setDatabaseName(table.getDatabaseName());
        partition.setTableName(table.getName());
        partition.setCreationTime(existing.getCreationTime());
        String updatedKey = partitionKey(table.getDatabaseName(), table.getName(), partition.getValues());
        if (!key.equals(updatedKey)) {
            partitionStore.delete(key);
        }
        partitionStore.put(updatedKey, partition);
    }

    private static String requireColumnStatisticsString(Map<String, Object> statistics, String field) {
        Object value = requireColumnStatisticsField(statistics, field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return stringValue;
    }

    private static Object requireColumnStatisticsField(Map<String, Object> statistics, String field) {
        Object value = statistics.get(field);
        if (value == null) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return value;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPartitionExpression(Table table, Partition partition, String expression) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        return matchesExpression(table, partition, expression);
    }

    private static boolean matchesExpression(Table table, Partition partition, String expression) {
        String stripped = stripParentheses(expression.trim());
        List<String> disjuncts = splitTopLevel(stripped, "OR");
        if (disjuncts.size() > 1) {
            return disjuncts.stream().anyMatch(disjunct -> matchesExpression(table, partition, disjunct));
        }
        List<String> conjuncts = splitTopLevel(stripped, "AND");
        if (conjuncts.size() > 1) {
            return conjuncts.stream().allMatch(conjunct -> matchesExpression(table, partition, conjunct));
        }
        return matchesPredicate(table, partition, stripped);
    }

    private static boolean matchesPredicate(Table table, Partition partition, String expression) {
        Matcher inMatcher = IN_EXPRESSION.matcher(expression);
        if (inMatcher.matches()) {
            String partitionValue = partitionValue(table, partition, inMatcher.group(1));
            if (partitionValue == null) {
                return false;
            }
            return splitValues(inMatcher.group(2)).stream()
                    .map(GlueService::unquote)
                    .anyMatch(partitionValue::equals);
        }
        Matcher comparisonMatcher = COMPARISON_EXPRESSION.matcher(expression);
        if (comparisonMatcher.matches()) {
            String partitionValue = partitionValue(table, partition, comparisonMatcher.group(1));
            if (partitionValue == null) {
                return false;
            }
            return compare(partitionValue, comparisonMatcher.group(2), unquote(comparisonMatcher.group(3)));
        }
        throw new AwsException("InvalidInputException", "Unsupported partition expression: " + expression, 400);
    }

    private static String partitionValue(Table table, Partition partition, String partitionKeyName) {
        int index = partitionKeyIndex(table, partitionKeyName);
        if (index < 0 || partition.getValues() == null || partition.getValues().size() <= index) {
            return null;
        }
        return partition.getValues().get(index);
    }

    private static boolean compare(String actual, String operator, String expected) {
        int comparison = compareValues(actual, expected);
        return switch (operator) {
            case "=" -> comparison == 0;
            case "<>" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> true;
        };
    }

    private static int compareValues(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        }
        catch (NumberFormatException e) {
            return left.compareTo(right);
        }
    }

    private static String stripParentheses(String expression) {
        String stripped = expression;
        while (stripped.startsWith("(") && stripped.endsWith(")") && matchingOuterParentheses(stripped)) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private static boolean matchingOuterParentheses(String expression) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                depth++;
            }
            else if (current == ')') {
                depth--;
                if (depth == 0 && index < expression.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static List<String> splitTopLevel(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                depth++;
            }
            else if (current == ')') {
                depth--;
            }
            else if (depth == 0 && matchesOperator(expression, index, operator)) {
                parts.add(expression.substring(start, index).trim());
                index += operator.length() - 1;
                start = index + 1;
            }
        }
        if (parts.isEmpty()) {
            return List.of(expression);
        }
        parts.add(expression.substring(start).trim());
        return parts;
    }

    private static boolean matchesOperator(String expression, int index, String operator) {
        if (!expression.regionMatches(true, index, operator, 0, operator.length())) {
            return false;
        }
        return isBoundary(expression, index - 1) && isBoundary(expression, index + operator.length());
    }

    private static boolean isBoundary(String expression, int index) {
        return index < 0 || index >= expression.length() || Character.isWhitespace(expression.charAt(index));
    }

    private static List<String> splitValues(String values) {
        List<String> result = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < values.length(); index++) {
            char current = values.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            else if (current == ',' && !quoted) {
                result.add(values.substring(start, index).trim());
                start = index + 1;
            }
        }
        result.add(values.substring(start).trim());
        return result;
    }

    private static String unquote(String value) {
        String stripped = value.trim();
        if (stripped.length() >= 2 && stripped.startsWith("'") && stripped.endsWith("'")) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static int partitionKeyIndex(Table table, String partitionKeyName) {
        List<Column> partitionKeys = table.getPartitionKeys();
        if (partitionKeys == null) {
            return -1;
        }
        for (int index = 0; index < partitionKeys.size(); index++) {
            if (partitionKeyName.equals(partitionKeys.get(index).getName())) {
                return index;
            }
        }
        return -1;
    }

    private static ColumnError columnStatisticsNotFoundError(String columnName) {
        return new ColumnError(
                columnName,
                new ErrorDetail("EntityNotFoundException", "Statistics do not exist for this column"));
    }

    private String databaseArn(String region, String databaseName) {
        return regionResolver.buildArn("glue", region, "database/" + databaseName);
    }

    private static Pattern compileFunctionPattern(String pattern) {
        if (pattern == null) {
            return Pattern.compile(".*");
        }
        if (pattern.length() > MAX_FUNCTION_PATTERN_LENGTH) {
            throw new AwsException("InvalidInputException", "Invalid function pattern: pattern is too long", 400);
        }
        try {
            return Pattern.compile(pattern);
        }
        catch (PatternSyntaxException e) {
            throw new AwsException("InvalidInputException", "Invalid function pattern: " + pattern, 400);
        }
    }

    private static int decodeFunctionNextToken(String nextToken) {
        if (nextToken == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        }
        catch (NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
        }
    }

    public record UserDefinedFunctionPage(List<UserDefinedFunction> functions, String nextToken) {}

    public record BatchDeleteTableError(
            @JsonProperty("TableName") String tableName,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    public record BatchCreatePartitionError(
            @JsonProperty("PartitionValues") List<String> partitionValues,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    public record BatchUpdatePartitionEntry(
            @JsonProperty("PartitionValueList") List<String> partitionValueList,
            @JsonProperty("PartitionInput") Partition partitionInput) {}

    public record BatchUpdatePartitionError(
            @JsonProperty("PartitionValueList") List<String> partitionValueList,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    public record ErrorDetail(
            @JsonProperty("ErrorCode") String errorCode,
            @JsonProperty("ErrorMessage") String errorMessage) {}

    public record ColumnStatisticsResult(
            @JsonProperty("ColumnStatisticsList") List<Map<String, Object>> columnStatisticsList,
            @JsonProperty("Errors") List<ColumnError> errors) {}

    public record ColumnError(
            @JsonProperty("ColumnName") String columnName,
            @JsonProperty("Error") ErrorDetail error) {}

    private static Table copyTable(Table source) {
        Table copy = new Table();
        copy.setName(source.getName());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setDescription(source.getDescription());
        copy.setOwner(source.getOwner());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setLastAccessTime(source.getLastAccessTime());
        copy.setPartitionKeys(copyColumns(source.getPartitionKeys()));
        copy.setStorageDescriptor(copyStorageDescriptor(source.getStorageDescriptor()));
        copy.setTableType(source.getTableType());
        copy.setViewOriginalText(source.getViewOriginalText());
        copy.setViewExpandedText(source.getViewExpandedText());
        copy.setVersionId(source.getVersionId());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static String nextVersionId(String versionId) {
        if (versionId == null) {
            return "1";
        }
        try {
            return Long.toString(Math.addExact(Long.parseLong(versionId), 1));
        }
        catch (ArithmeticException | NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid table VersionId: " + versionId, 400);
        }
    }

    private static long versionIdAsLong(Table table) {
        return Long.parseLong(table.getVersionId());
    }

    private static StorageDescriptor copyStorageDescriptor(StorageDescriptor source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor copy = new StorageDescriptor();
        copy.setColumns(copyColumns(source.getColumns()));
        copy.setLocation(source.getLocation());
        copy.setInputFormat(source.getInputFormat());
        copy.setOutputFormat(source.getOutputFormat());
        copy.setCompressed(source.getCompressed());
        copy.setNumberOfBuckets(source.getNumberOfBuckets());
        copy.setSerdeInfo(copySerDeInfo(source.getSerdeInfo()));
        copy.setParameters(copyMap(source.getParameters()));
        copy.setSchemaReference(copySchemaReference(source.getSchemaReference()));
        return copy;
    }

    private static StorageDescriptor.SerDeInfo copySerDeInfo(StorageDescriptor.SerDeInfo source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor.SerDeInfo copy = new StorageDescriptor.SerDeInfo();
        copy.setName(source.getName());
        copy.setSerializationLibrary(source.getSerializationLibrary());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static SchemaReference copySchemaReference(SchemaReference source) {
        if (source == null) {
            return null;
        }
        SchemaReference copy = new SchemaReference();
        SchemaId schemaId = source.getSchemaId();
        if (schemaId != null) {
            copy.setSchemaId(new SchemaId(
                    schemaId.getRegistryName(), schemaId.getSchemaName(), schemaId.getSchemaArn()));
        }
        copy.setSchemaVersionId(source.getSchemaVersionId());
        copy.setSchemaVersionNumber(source.getSchemaVersionNumber());
        return copy;
    }

    private static List<Column> copyColumns(List<Column> source) {
        if (source == null) {
            return null;
        }
        List<Column> copy = new ArrayList<>(source.size());
        for (Column column : source) {
            Column columnCopy = new Column();
            columnCopy.setName(column.getName());
            columnCopy.setType(column.getType());
            columnCopy.setComment(column.getComment());
            columnCopy.setParameters(copyMap(column.getParameters()));
            copy.add(columnCopy);
        }
        return copy;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source != null ? new LinkedHashMap<>(source) : null;
    }

}
