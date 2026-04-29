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
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.SchemaToColumnsConverter;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<Map<String, Database>>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<Map<String, Table>>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<Map<String, Partition>>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, Partition> partitionStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.partitionStore = partitionStore;
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

    public void createTable(String databaseName, Table table) {
        getDatabase(databaseName);
        String key = databaseName + ":" + table.getName();
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setDatabaseName(databaseName);
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        Table table = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
        resolveSchemaReference(table);
        return table;
    }

    public List<Table> getTables(String databaseName) {
        List<Table> tables = tableStore.scan(k -> k.startsWith(databaseName + ":"));
        for (Table t : tables) {
            resolveSchemaReference(t);
        }
        return tables;
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

    private void validateSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return;
        }
        boolean latest = ref.getSchemaVersionId() == null && ref.getSchemaVersionNumber() == null;
        // Throws EntityNotFoundException / InvalidInputException if reference is broken.
        schemaRegistryService.getSchemaVersion(
                ref.getSchemaId(), ref.getSchemaVersionId(),
                ref.getSchemaVersionNumber(), latest, regionResolver.getDefaultRegion());
    }

    private void resolveSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return;
        }
        try {
            boolean latest = ref.getSchemaVersionId() == null && ref.getSchemaVersionNumber() == null;
            SchemaVersion version = schemaRegistryService.getSchemaVersion(
                    ref.getSchemaId(), ref.getSchemaVersionId(),
                    ref.getSchemaVersionNumber(), latest, regionResolver.getDefaultRegion());
            List<Column> columns = SchemaToColumnsConverter.toColumns(
                    version.getDataFormat(), version.getSchemaDefinition());
            if (!columns.isEmpty()) {
                table.getStorageDescriptor().setColumns(columns);
            }
        } catch (AwsException e) {
            LOG.warnv("SchemaReference resolution failed for {0}.{1}: {2}",
                    table.getDatabaseName(), table.getName(), e.getMessage());
        }
    }

    private static SchemaReference schemaReferenceOf(Table table) {
        StorageDescriptor sd = table != null ? table.getStorageDescriptor() : null;
        return sd != null ? sd.getSchemaReference() : null;
    }
}
