package io.github.hectorvent.floci.services.glue;

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
import io.github.hectorvent.floci.services.glue.model.TableVersion;
import io.github.hectorvent.floci.services.glue.model.UserDefinedFunction;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.SchemaToColumnsConverter;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);
    private static final int MAX_FUNCTION_PATTERN_LENGTH = 255;
    private static final int MAX_FUNCTION_RESULTS = 100;

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, TableVersion> tableVersionStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final StorageBackend<String, Map<String, Object>> tableColumnStatisticsStore;
    private final StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore;
    private final StorageBackend<String, UserDefinedFunction> functionStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<>() {});
        this.tableVersionStore = storageFactory.create("glue", "table_versions.json", new TypeReference<>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<>() {});
        this.tableColumnStatisticsStore = storageFactory.create("glue", "table_column_statistics.json", new TypeReference<>() {});
        this.partitionColumnStatisticsStore = storageFactory.create("glue", "partition_column_statistics.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("glue", "functions.json", new TypeReference<>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, TableVersion> tableVersionStore,
                StorageBackend<String, Partition> partitionStore,
                StorageBackend<String, Map<String, Object>> tableColumnStatisticsStore,
                StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore,
                StorageBackend<String, UserDefinedFunction> functionStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.tableVersionStore = tableVersionStore;
        this.partitionStore = partitionStore;
        this.tableColumnStatisticsStore = tableColumnStatisticsStore;
        this.partitionColumnStatisticsStore = partitionColumnStatisticsStore;
        this.functionStore = functionStore;
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
    }

    public void createDatabase(Database database) {
        String databaseName = normalizeDatabaseName(database.getName());
        if (databaseStore.get(databaseName).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Database already exists: " + database.getName(), 400);
        }
        database.setName(databaseName);
        databaseStore.put(databaseName, database);
        LOG.infov("Created Glue Database: {0}", databaseName);
    }

    public Database getDatabase(String name) {
        String databaseName = normalizeDatabaseName(name);
        return databaseStore.get(databaseName)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Database not found: " + name, 400));
    }

    public List<Database> getDatabases() {
        return databaseStore.scan(k -> true);
    }

    public void deleteDatabase(String name) {
        String databaseName = normalizeDatabaseName(name);
        getDatabase(databaseName);
        List<String> tableNames = tableStore.scan(k -> true).stream()
                .filter(table -> databaseName.equals(table.getDatabaseName()))
                .map(Table::getName)
                .toList();
        tableNames.forEach(tableName -> deleteTable(databaseName, tableName));
        functionStore.scan(k -> true).stream()
                .filter(function -> databaseName.equals(function.getDatabaseName()))
                .map(UserDefinedFunction::getFunctionName)
                .forEach(functionName -> functionStore.delete(functionKey(databaseName, functionName)));
        databaseStore.delete(databaseName);
        LOG.infov("Deleted Glue Database: {0}", databaseName);
    }

    public void createTable(String databaseName, Table table) {
        databaseName = normalizeDatabaseName(databaseName);
        getDatabase(databaseName);
        String key = tableKey(databaseName, table.getName());
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setDatabaseName(databaseName);
        if (table.getCreateTime() == null) {
            table.setCreateTime(Instant.now());
        }
        table.setVersionId("0");
        tableStore.put(key, table);
        putTableVersion(key, table, nextTableVersionId(key));
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        String key = tableKey(normalizedDatabaseName, tableName);
        Table table = tableStore.get(key)
                .orElseThrow(() -> new AwsException(
                        "EntityNotFoundException",
                        "Table not found: " + normalizedDatabaseName + "." + tableName,
                        400));
        return withResolvedSchemaReference(table);
    }

    public List<Table> getTables(String databaseName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        List<Table> tables = tableStore.scan(k -> k.startsWith(normalizedDatabaseName + ":"));
        List<Table> resolved = new ArrayList<>(tables.size());
        for (Table table : tables) {
            resolved.add(withResolvedSchemaReference(table));
        }
        return resolved;
    }

    public void updateTable(String databaseName, Table table) {
        updateTable(databaseName, table, false);
    }

    public void updateTable(String databaseName, Table table, boolean skipArchive) {
        updateTable(databaseName, table, skipArchive, null);
    }

    public synchronized void updateTable(String databaseName, Table table, boolean skipArchive, String versionId) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        getDatabase(normalizedDatabaseName);
        String key = tableKey(normalizedDatabaseName, table.getName());
        Table existing = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Table not found: " + normalizedDatabaseName + "." + table.getName(), 400));
        if (versionId != null && !versionId.equals(existing.getVersionId())) {
            throw new AwsException("ConcurrentModificationException", "Update table failed due to concurrent modifications.", 400);
        }
        validateSchemaReference(table);
        table.setDatabaseName(normalizedDatabaseName);
        table.setCreateTime(existing.getCreateTime());
        table.setUpdateTime(Instant.now());
        table.setVersionId(nextTableUpdateVersionId(existing.getVersionId()));
        tableStore.put(key, table);
        long nextVersionId = nextTableVersionId(key);
        if (skipArchive) {
            latestTableVersionKey(key).ifPresent(tableVersionStore::delete);
        }
        putTableVersion(key, table, nextVersionId);
        LOG.infov("Updated Glue Table: {0}.{1}", normalizedDatabaseName, table.getName());
    }

    public List<TableVersion> getTableVersions(String databaseName, String tableName) {
        databaseName = normalizeDatabaseName(databaseName);
        String key = tableKey(databaseName, tableName);
        getTable(databaseName, tableName);
        return tableVersionStore.scan(k -> k.startsWith(key + ":")).stream()
                .sorted(Comparator.comparing(TableVersion::getVersionId).reversed())
                .toList();
    }

    public void deleteTable(String databaseName, String tableName) {
        databaseName = normalizeDatabaseName(databaseName);
        String key = tableKey(databaseName, tableName);
        getTable(databaseName, tableName);
        tableStore.delete(key);
        partitionStore.keys().stream()
                .filter(k -> k.startsWith(key + ":"))
                .forEach(partitionStore::delete);
        tableVersionStore.keys().stream()
                .filter(k -> k.startsWith(key + ":"))
                .forEach(tableVersionStore::delete);
        tableColumnStatisticsStore.keys().stream()
                .filter(k -> k.startsWith(key + ":"))
                .forEach(tableColumnStatisticsStore::delete);
        partitionColumnStatisticsStore.keys().stream()
                .filter(k -> k.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public List<Map<String, Object>> batchDeleteTables(String databaseName, List<String> tableNames) {
        databaseName = normalizeDatabaseName(databaseName);
        getDatabase(databaseName);
        List<Map<String, Object>> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String key = tableKey(databaseName, tableName);
            if (tableStore.get(key).isEmpty()) {
                errors.add(Map.of(
                        "TableName", tableName,
                        "ErrorDetail", Map.of(
                                "ErrorCode", "EntityNotFoundException",
                                "ErrorMessage", "Table " + tableName + " not found")));
                continue;
            }
            deleteTable(databaseName, tableName);
        }
        return errors;
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        databaseName = normalizeDatabaseName(databaseName);
        getTable(databaseName, tableName);
        String key = databaseName + ":" + tableName + ":" + String.join(",", partition.getValues());
        if (partitionStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Partition already exists: " + databaseName + "." + tableName + " " + partition.getValues(), 400);
        }
        partition.setDatabaseName(databaseName);
        partition.setTableName(tableName);
        partitionStore.put(key, partition);
    }

    public void batchCreatePartitions(String databaseName, String tableName, List<Partition> partitions) {
        databaseName = normalizeDatabaseName(databaseName);
        for (Partition partition : partitions) {
            createPartition(databaseName, tableName, partition);
        }
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        return getPartitions(databaseName, tableName, null);
    }

    public Partition getPartition(String databaseName, String tableName, List<String> values) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        getTable(normalizedDatabaseName, tableName);
        String key = partitionKey(normalizedDatabaseName, tableName, values);
        return partitionStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Partition not found: " + normalizedDatabaseName + "." + tableName + " " + values, 400));
    }

    public List<Partition> batchGetPartitions(String databaseName, String tableName, List<List<String>> partitionValues) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        getTable(normalizedDatabaseName, tableName);
        return partitionValues.stream()
                .map(values -> partitionStore.get(partitionKey(normalizedDatabaseName, tableName, values)))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<Partition> getPartitions(String databaseName, String tableName, String expression) {
        databaseName = normalizeDatabaseName(databaseName);
        Table table = getTable(databaseName, tableName);
        String prefix = databaseName + ":" + tableName + ":";
        List<Partition> partitions = partitionStore.scan(k -> k.startsWith(prefix));
        return GluePartitionExpressionEvaluator.filter(table, partitions, expression);
    }

    public void updatePartition(String databaseName, String tableName, List<String> oldValues, Partition partition) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        getTable(normalizedDatabaseName, tableName);
        String oldKey = partitionKey(normalizedDatabaseName, tableName, oldValues);
        Partition existing = partitionStore.get(oldKey)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Partition not found: " + normalizedDatabaseName + "." + tableName + " " + oldValues, 400));
        if (partition.getValues() == null) {
            partition.setValues(existing.getValues());
        }
        partition.setDatabaseName(normalizedDatabaseName);
        partition.setTableName(tableName);
        String newKey = partitionKey(normalizedDatabaseName, tableName, partition.getValues());
        if (!oldKey.equals(newKey)) {
            partitionStore.delete(oldKey);
        }
        partitionStore.put(newKey, partition);
    }

    public void batchUpdatePartitions(String databaseName, String tableName, Map<List<String>, Partition> partitions) {
        databaseName = normalizeDatabaseName(databaseName);
        for (Map.Entry<List<String>, Partition> entry : partitions.entrySet()) {
            updatePartition(databaseName, tableName, entry.getKey(), entry.getValue());
        }
    }

    public void deletePartition(String databaseName, String tableName, List<String> values) {
        databaseName = normalizeDatabaseName(databaseName);
        getTable(databaseName, tableName);
        String key = partitionKey(databaseName, tableName, values);
        if (partitionStore.get(key).isEmpty()) {
            throw new AwsException("EntityNotFoundException",
                    "Partition not found: " + databaseName + "." + tableName + " " + values, 400);
        }
        partitionStore.delete(key);
        partitionColumnStatisticsStore.keys().stream()
                .filter(k -> k.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
    }

    public List<Map<String, Object>> getTableColumnStatistics(String databaseName, String tableName, List<String> columnNames) {
        databaseName = normalizeDatabaseName(databaseName);
        getTable(databaseName, tableName);
        String normalizedDatabaseName = databaseName;
        return columnNames.stream()
                .map(columnName -> tableColumnStatisticsStore.get(columnStatisticsKey(normalizedDatabaseName, tableName, columnName)))
                .flatMap(Optional::stream)
                .toList();
    }

    public void updateTableColumnStatistics(String databaseName, String tableName, List<Map<String, Object>> statistics) {
        databaseName = normalizeDatabaseName(databaseName);
        getTable(databaseName, tableName);
        for (Map<String, Object> statistic : statistics) {
            tableColumnStatisticsStore.put(
                    columnStatisticsKey(databaseName, tableName, columnName(statistic)),
                    statistic);
        }
    }

    public void deleteTableColumnStatistics(String databaseName, String tableName, String columnName) {
        databaseName = normalizeDatabaseName(databaseName);
        getTable(databaseName, tableName);
        tableColumnStatisticsStore.delete(columnStatisticsKey(databaseName, tableName, columnName));
    }

    public List<Map<String, Object>> getPartitionColumnStatistics(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<String> columnNames) {
        databaseName = normalizeDatabaseName(databaseName);
        getPartition(databaseName, tableName, partitionValues);
        String normalizedDatabaseName = databaseName;
        return columnNames.stream()
                .map(columnName -> partitionColumnStatisticsStore.get(partitionColumnStatisticsKey(
                        normalizedDatabaseName, tableName, partitionValues, columnName)))
                .flatMap(Optional::stream)
                .toList();
    }

    public void updatePartitionColumnStatistics(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<Map<String, Object>> statistics) {
        databaseName = normalizeDatabaseName(databaseName);
        getPartition(databaseName, tableName, partitionValues);
        for (Map<String, Object> statistic : statistics) {
            partitionColumnStatisticsStore.put(
                    partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName(statistic)),
                    statistic);
        }
    }

    public void deletePartitionColumnStatistics(String databaseName, String tableName, List<String> partitionValues, String columnName) {
        databaseName = normalizeDatabaseName(databaseName);
        getPartition(databaseName, tableName, partitionValues);
        partitionColumnStatisticsStore.delete(partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName));
    }

    public void createUserDefinedFunction(String databaseName, UserDefinedFunction function) {
        databaseName = normalizeDatabaseName(databaseName);
        getDatabase(databaseName);
        String key = functionKey(databaseName, function.getFunctionName());
        if (functionStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Function already exists: " + databaseName + "." + function.getFunctionName(), 400);
        }
        function.setDatabaseName(databaseName);
        function.setCreateTime(Instant.now());
        functionStore.put(key, function);
    }

    public UserDefinedFunction getUserDefinedFunction(String databaseName, String functionName) {
        String normalizedDatabaseName = normalizeDatabaseName(databaseName);
        getDatabase(normalizedDatabaseName);
        return functionStore.get(functionKey(normalizedDatabaseName, functionName))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Function not found: " + normalizedDatabaseName + "." + functionName, 400));
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
        databaseName = databaseName == null ? null : normalizeDatabaseName(databaseName);
        if (databaseName != null) {
            getDatabase(databaseName);
        }
        String normalizedDatabaseName = databaseName;
        Pattern compiledPattern = compileFunctionPattern(pattern);
        int offset = decodeFunctionNextToken(nextToken);
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_FUNCTION_RESULTS)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 100", 400);
        }
        List<UserDefinedFunction> functions = functionStore.scan(k -> true).stream()
                .filter(function -> normalizedDatabaseName == null || normalizedDatabaseName.equals(function.getDatabaseName()))
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
        databaseName = normalizeDatabaseName(databaseName);
        UserDefinedFunction existing = getUserDefinedFunction(databaseName, functionName);
        function.setDatabaseName(databaseName);
        function.setFunctionName(functionName);
        function.setCreateTime(existing.getCreateTime());
        functionStore.put(functionKey(databaseName, functionName), function);
    }

    public void deleteUserDefinedFunction(String databaseName, String functionName) {
        databaseName = normalizeDatabaseName(databaseName);
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
        return databaseName + ":" + functionName;
    }

    private static String tableKey(String databaseName, String tableName) {
        return databaseName + ":" + tableName;
    }

    private static String partitionKey(String databaseName, String tableName, List<String> values) {
        return databaseName + ":" + tableName + ":" + String.join(",", values);
    }

    private static String normalizeDatabaseName(String databaseName) {
        return databaseName.toLowerCase(Locale.ROOT);
    }

    private static String columnStatisticsKey(String databaseName, String tableName, String columnName) {
        return databaseName + ":" + tableName + ":" + columnName;
    }

    private static String partitionColumnStatisticsKey(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            String columnName) {
        return partitionKey(databaseName, tableName, partitionValues) + ":" + columnName;
    }

    private static String columnName(Map<String, Object> statistic) {
        Object columnName = statistic.get("ColumnName");
        if (columnName == null) {
            throw new AwsException("InvalidInputException", "ColumnStatistics is missing ColumnName", 400);
        }
        return columnName.toString();
    }

    private void putTableVersion(String tableKey, Table table, long versionId) {
        tableVersionStore.put(tableVersionKey(tableKey, versionId), new TableVersion(copyTable(table), Long.toString(versionId)));
    }

    private long nextTableVersionId(String tableKey) {
        return tableVersionStore.keys().stream()
                .filter(k -> k.startsWith(tableKey + ":"))
                .mapToLong(GlueService::versionIdFromTableVersionKey)
                .max()
                .orElse(0) + 1;
    }

    private Optional<String> latestTableVersionKey(String tableKey) {
        return tableVersionStore.keys().stream()
                .filter(k -> k.startsWith(tableKey + ":"))
                .max(Comparator.comparingLong(GlueService::versionIdFromTableVersionKey));
    }

    private static String tableVersionKey(String tableKey, long versionId) {
        return tableKey + ":" + versionId;
    }

    private static long versionIdFromTableVersionKey(String key) {
        return Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
    }

    private static String nextTableUpdateVersionId(String versionId) {
        return Long.toString(versionId == null ? 0 : Long.parseLong(versionId) + 1);
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
        copy.setParameters(copyMap(source.getParameters()));
        copy.setVersionId(source.getVersionId());
        return copy;
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
