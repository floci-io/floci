package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Partition> partitionStore;

    @Inject
    public GlueService(StorageFactory storageFactory) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<Map<String, Database>>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<Map<String, Table>>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<Map<String, Partition>>() {});
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
        getDatabase(databaseName); // Check if db exists
        String key = databaseName + ":" + table.getName();
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        table.setDatabaseName(databaseName);
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        return tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
    }

    public List<Table> getTables(String databaseName) {
        return tableStore.scan(k -> k.startsWith(databaseName + ":"));
    }

    public void deleteTable(String databaseName, String tableName) {
        String key = databaseName + ":" + tableName;
        tableStore.delete(key);
        // Also delete partitions
        partitionStore.scan(k -> k.startsWith(key + ":")).forEach(p -> {
            partitionStore.delete(databaseName + ":" + tableName + ":" + String.join(",", p.getValues()));
        });
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        getTable(databaseName, tableName); // Check if table exists
        String key = databaseName + ":" + tableName + ":" + String.join(",", partition.getValues());
        partition.setDatabaseName(databaseName);
        partition.setTableName(tableName);
        partitionStore.put(key, partition);
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        String prefix = databaseName + ":" + tableName + ":";
        return partitionStore.scan(k -> k.startsWith(prefix));
    }
}
