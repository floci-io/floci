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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);
    private static final int MAX_FUNCTION_PATTERN_LENGTH = 255;
    private static final int MAX_FUNCTION_RESULTS = 100;

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final StorageBackend<String, UserDefinedFunction> functionStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("glue", "functions.json", new TypeReference<>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, Partition> partitionStore,
                StorageBackend<String, UserDefinedFunction> functionStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.partitionStore = partitionStore;
        this.functionStore = functionStore;
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
    }

    public void createDatabase(Database database) {
        if (databaseStore.get(database.getName()).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Database already exists: " + database.getName(), 400);
        }
        databaseStore.put(database.getName(), database);
        LOG.infov("Created Glue Database: {0}", database.getName());
    }

    public Database getDatabase(String name) {
        return databaseStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Database not found: " + name, 400));
    }

    public List<Database> getDatabases() {
        return databaseStore.scan(k -> true);
    }

    public void deleteDatabase(String name) {
        getDatabase(name);
        List<String> tableNames = tableStore.scan(k -> true).stream()
                .filter(table -> name.equals(table.getDatabaseName()))
                .map(Table::getName)
                .toList();
        tableNames.forEach(tableName -> deleteTable(name, tableName));
        databaseStore.delete(name);
        LOG.infov("Deleted Glue Database: {0}", name);
    }

    public void createTable(String databaseName, Table table) {
        getDatabase(databaseName);
        String key = databaseName + ":" + table.getName();
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setDatabaseName(databaseName);
        if (table.getCreateTime() == null) {
            table.setCreateTime(Instant.now());
        }
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        Table table = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
        return withResolvedSchemaReference(table);
    }

    public List<Table> getTables(String databaseName) {
        List<Table> tables = tableStore.scan(k -> k.startsWith(databaseName + ":"));
        List<Table> resolved = new ArrayList<>(tables.size());
        for (Table table : tables) {
            resolved.add(withResolvedSchemaReference(table));
        }
        return resolved;
    }

    public void updateTable(String databaseName, Table table) {
        getDatabase(databaseName);
        String key = databaseName + ":" + table.getName();
        Table existing = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Table not found: " + databaseName + "." + table.getName(), 400));
        validateSchemaReference(table);
        table.setDatabaseName(databaseName);
        table.setCreateTime(existing.getCreateTime());
        table.setUpdateTime(Instant.now());
        tableStore.put(key, table);
        LOG.infov("Updated Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public List<Map<String, Object>> getTableVersions() {
        return List.of();
    }

    public void deleteTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        tableStore.delete(key);
        partitionStore.scan(k -> k.startsWith(key + ":")).forEach(p -> {
            partitionStore.delete(databaseName + ":" + tableName + ":" + String.join(",", p.getValues()));
        });
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        getTable(databaseName, tableName);
        String key = databaseName + ":" + tableName + ":" + String.join(",", partition.getValues());
        partition.setDatabaseName(databaseName);
        partition.setTableName(tableName);
        partitionStore.put(key, partition);
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        String prefix = databaseName + ":" + tableName + ":";
        return partitionStore.scan(k -> k.startsWith(prefix));
    }

    public void createUserDefinedFunction(String databaseName, UserDefinedFunction function) {
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
        if (databaseName != null) {
            getDatabase(databaseName);
        }
        Pattern compiledPattern = compileFunctionPattern(pattern);
        int offset = decodeFunctionNextToken(nextToken);
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_FUNCTION_RESULTS)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 100", 400);
        }
        List<UserDefinedFunction> functions = functionStore.scan(k -> true).stream()
                .filter(function -> databaseName == null || databaseName.equals(function.getDatabaseName()))
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
        function.setDatabaseName(databaseName);
        function.setFunctionName(functionName);
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
        return databaseName + ":" + functionName;
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
            copy.add(columnCopy);
        }
        return copy;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source != null ? new LinkedHashMap<>(source) : null;
    }
}
