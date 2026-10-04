package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SpectrumCatalog implements Resettable {

    private final AccountAwareStorageBackend<SpectrumExternalSchema> schemas;
    private final AccountAwareStorageBackend<SpectrumExternalTable> tables;

    public SpectrumCatalog(StorageFactory storageFactory) {
        schemas = storageFactory.create("redshift", "redshift-spectrum-schemas.json",
                new TypeReference<>() {});
        tables = storageFactory.create("redshift", "redshift-spectrum-tables.json",
                new TypeReference<>() {});
    }

    public synchronized void createSchema(SpectrumExternalSchema schema) {
        String key = schemaKey(schema.accountId(), schema.databaseName(), schema.schemaName());
        if (schemas.getForAccount(schema.accountId(), key).isPresent()) {
            throw new SpectrumSqlException("42P06", "Spectrum external schema already exists: " + schema.schemaName());
        }
        schemas.putForAccount(schema.accountId(), key, schema);
        schemas.flush();
    }

    public synchronized void createTable(SpectrumExternalTable table) {
        String key = tableKey(table.accountId(), table.databaseName(), table.schemaName(), table.tableName());
        if (tables.getForAccount(table.accountId(), key).isPresent()) {
            throw new SpectrumSqlException("42P07", "Spectrum external table already exists: " + table.tableName());
        }
        tables.putForAccount(table.accountId(), key, table);
        tables.flush();
    }

    public Optional<SpectrumExternalSchema> schema(String accountId, String databaseName, String schemaName) {
        return schemas.getForAccount(accountId, schemaKey(accountId, databaseName, schemaName));
    }

    /**
     * Phase one stored a schema under the Glue database name, while its tables carry the Redshift
     * database, so the schema's own {@code databaseName} cannot be matched against the session's.
     * The Redshift database scope comes from {@link #legacySchemaNames}, which requires tables in it.
     */
    public Optional<SpectrumExternalSchema> findLegacySchema(String accountId, String databaseName, String schemaName) {
        if (!legacySchemaNames(accountId, databaseName).contains(schemaName)) {
            return Optional.empty();
        }
        String prefix = accountId + ":";
        List<SpectrumExternalSchema> candidates = schemas.scanForAccount(accountId, key -> key.startsWith(prefix))
                .stream()
                .filter(schema -> accountId.equals(schema.accountId()) && schema.schemaName().equals(schemaName))
                .sorted(Comparator.comparing(SpectrumExternalSchema::databaseName))
                .toList();
        if (candidates.size() > 1) {
            // same-named schemas of other Glue databases must not lend their IAM role to this one: keep
            // those whose location covers a table of this Redshift database
            List<SpectrumExternalTable> tables = tablesForSchema(accountId, databaseName, schemaName);
            List<SpectrumExternalSchema> owning = candidates.stream()
                    .filter(schema -> tables.stream().anyMatch(table -> table.location().startsWith(schema.location())))
                    .toList();
            if (!owning.isEmpty()) {
                return Optional.of(owning.getFirst());
            }
        }
        return candidates.stream().findFirst();
    }

    public List<String> legacySchemaNames(String accountId, String databaseName) {
        String prefix = accountId + ":";
        String tablePrefix = accountId + ":" + databaseName + ":";
        List<String> knownSchemas = schemas.scanForAccount(accountId, key -> key.startsWith(prefix)).stream()
                .filter(schema -> accountId.equals(schema.accountId()))
                .map(SpectrumExternalSchema::schemaName)
                .toList();
        return tables.scanForAccount(accountId, key -> key.startsWith(tablePrefix)).stream()
                .filter(table -> accountId.equals(table.accountId()) && knownSchemas.contains(table.schemaName()))
                .map(SpectrumExternalTable::schemaName)
                .distinct()
                .sorted()
                .toList();
    }

    public Optional<SpectrumExternalTable> table(String accountId, String databaseName,
                                                 String schemaName, String tableName) {
        return tables.getForAccount(accountId, tableKey(accountId, databaseName, schemaName, tableName));
    }

    public List<SpectrumExternalTable> tablesForSchema(String accountId, String databaseName, String schemaName) {
        String prefix = accountId + ":" + databaseName + ":" + schemaName + ":";
        return tables.scanForAccount(accountId, key -> key.startsWith(prefix))
                .stream()
                .sorted(Comparator.comparing(SpectrumExternalTable::tableName))
                .toList();
    }

    @Override
    public synchronized void clear() {
        schemas.clear();
        tables.clear();
        schemas.flush();
        tables.flush();
    }

    private static String schemaKey(String accountId, String databaseName, String schemaName) {
        return accountId + ":" + databaseName + ":" + schemaName;
    }

    private static String tableKey(String accountId, String databaseName, String schemaName, String tableName) {
        return accountId + ":" + databaseName + ":" + schemaName + ":" + tableName;
    }
}
