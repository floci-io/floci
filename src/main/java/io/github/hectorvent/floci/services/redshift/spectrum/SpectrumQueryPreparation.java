package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class SpectrumQueryPreparation {
    private static final Logger LOG = Logger.getLogger(SpectrumQueryPreparation.class);
    private final ExternalCatalogRegistry registry;
    private final ExternalTableMaterializer materializer;
    private final ExternalStatementParser parser;
    private final GlueService glue;
    private final ExternalMetadataWriter metadata;
    private final ExternalCatalogLocks catalogLocks;
    private final Map<BackendSql, List<ExternalSchemaBinding>> pendingBindings = new ConcurrentHashMap<>();

    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                                    ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata) {
        this(registry, materializer, parser, glue, metadata, new ExternalCatalogLocks());
    }

    @Inject
    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                                    ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata,
                                    ExternalCatalogLocks catalogLocks) {
        this.registry = registry;
        this.materializer = materializer;
        this.parser = parser;
        this.glue = glue;
        this.metadata = metadata;
        this.catalogLocks = catalogLocks;
    }

    public boolean prepare(String sql, SpectrumSession session, BackendSql backend) {
        try {
            return RequestScopes.callAs(session.accountId(), () -> prepareInScope(sql, session, backend));
        } catch (AwsException exception) {
            String state = switch (exception.getErrorCode()) {
                case "AlreadyExistsException" -> "42P07";
                case "EntityNotFoundException" -> "42P01";
                case "AccessDenied", "AccessDeniedException" -> "42501";
                case "InvalidInputException" -> "42601";
                default -> "58030";
            };
            throw new SpectrumSqlException(state, exception.getMessage());
        }
    }

    public boolean referencesExternal(String sql, SpectrumSession session) {
        Set<String> schemas = bindings(session).keySet();
        return !ExternalReferenceScanner.scan(sql, schemas).isEmpty()
                || ExternalReferenceScanner.writeTarget(sql, schemas).isPresent();
    }

    public boolean handlesDdl(String sql, SpectrumSession session) {
        ExternalStatement statement = parser.parse(sql).orElse(null);
        return switch (statement) {
            case ExternalStatement.CreateSchema ignored -> true;
            case ExternalStatement.CreateTable table -> session.inTransaction() || bindings(session).containsKey(table.schemaName());
            case ExternalStatement.AddPartitions partitions -> bindings(session).containsKey(partitions.schemaName());
            case ExternalStatement.DropTable table -> bindings(session).containsKey(table.schemaName());
            case ExternalStatement.DropSchema schema -> bindings(session).containsKey(schema.schemaName());
            case null -> false;
        };
    }

    public String commandTag(String sql) {
        return switch (parser.parse(sql).orElse(null)) {
            case ExternalStatement.AddPartitions ignored -> "ALTER TABLE";
            case ExternalStatement.DropTable ignored -> "DROP TABLE";
            case ExternalStatement.DropSchema ignored -> "DROP SCHEMA";
            case null, default -> "CREATE EXTERNAL";
        };
    }

    private boolean prepareInScope(String sql, SpectrumSession session, BackendSql backend) {
        Optional<ExternalStatement> statement = parser.parse(sql);
        if (statement.orElse(null) instanceof ExternalStatement.CreateSchema schema) {
            return createSchema(schema, session, backend);
        }
        Map<String, ExternalSchemaBinding> bindings = bindings(session);
        if (statement.orElse(null) instanceof ExternalStatement.CreateTable && session.inTransaction()) {
            // Legacy-catalog tables are saved immediately too, so a rollback would leave them behind.
            throw new SpectrumSqlException("25001", "CREATE EXTERNAL TABLE cannot run inside a transaction block");
        }
        if (statement.orElse(null) instanceof ExternalStatement.CreateTable table && bindings.containsKey(table.schemaName())) {
            ExternalSchemaBinding binding = bindings.get(table.schemaName());
            try (ExternalCatalogLocks.Guard ignored = catalogLocks.write(binding)) {
                requireCurrentBinding(session, binding);
                glue.createTable(binding.glueDatabase(), GlueTableBuilder.toGlueTable(table));
                metadata.refresh(backend, session.accountId(), binding);
            }
            return true;
        }
        switch (statement.orElse(null)) {
            case ExternalStatement.AddPartitions partitions when bindings.containsKey(partitions.schemaName()) -> {
                rejectTransaction(session);
                addPartitions(partitions, bindings.get(partitions.schemaName()), session, backend);
                return true;
            }
            case ExternalStatement.DropTable table when bindings.containsKey(table.schemaName()) -> {
                rejectTransaction(session);
                dropTable(table, bindings.get(table.schemaName()), session, backend);
                return true;
            }
            case ExternalStatement.DropSchema schema when bindings.containsKey(schema.schemaName()) -> {
                rejectTransaction(session);
                dropSchema(schema, bindings.get(schema.schemaName()), session, backend);
                return true;
            }
            case null, default -> { }
        }
        Optional<ExternalReferenceScanner.Reference> write = ExternalReferenceScanner.writeTarget(sql, bindings.keySet());
        if (write.isPresent()) {
            throw new SpectrumSqlException("0A000", "Writes to external relation \""
                    + write.get().schema() + "." + write.get().table() + "\" are not supported");
        }
        Set<String> refreshed = new HashSet<>();
        for (ExternalReferenceScanner.Reference reference : ExternalReferenceScanner.scan(sql, bindings.keySet())) {
            ExternalSchemaBinding binding = bindings.get(reference.schema());
            try (ExternalCatalogLocks.Guard ignored = catalogLocks.read(binding,
                    session.inTransaction() || !backend.permitsCachePublication())) {
                requireCurrentBinding(session, binding);
                ExternalTableMaterializer.Outcome outcome = materializer.ensureCurrent(backend, session, binding, reference.table(), sql);
                if (outcome == ExternalTableMaterializer.Outcome.NOT_EXTERNAL) {
                    throw new SpectrumSqlException("42P01", "external relation \"" + reference.schema()
                            + "." + reference.table() + "\" does not exist");
                }
                if (outcome == ExternalTableMaterializer.Outcome.LOADED && refreshed.add(binding.schemaName())) {
                    metadata.refresh(backend, session.accountId(), binding);
                }
            }
        }
        return false;
    }

    private static void rejectTransaction(SpectrumSession session) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("25001", "External catalog changes cannot run inside a transaction block");
        }
    }

    private void addPartitions(ExternalStatement.AddPartitions statement, ExternalSchemaBinding binding,
                               SpectrumSession session, BackendSql backend) {
        try (ExternalCatalogLocks.Guard ignored = catalogLocks.write(binding)) {
            requireCurrentBinding(session, binding);
            addPartitionsLocked(statement, binding, session, backend);
        }
    }

    private void addPartitionsLocked(ExternalStatement.AddPartitions statement, ExternalSchemaBinding binding,
                                     SpectrumSession session, BackendSql backend) {
        Table table = glue.getTable(binding.glueDatabase(), statement.tableName());
        if (table.getPartitionKeys() == null || table.getPartitionKeys().isEmpty()) {
            throw new SpectrumSqlException("22023", "External table has no partition keys");
        }
        List<Partition> requested = statement.partitions().stream()
                .map(spec -> GlueTableBuilder.toGluePartition(table, spec)).toList();
        Set<List<String>> values = new HashSet<>();
        for (Partition partition : glue.getPartitions(binding.glueDatabase(), statement.tableName())) {
            values.add(partition.getValues());
        }
        List<Partition> additions = new ArrayList<>();
        for (Partition partition : requested) {
            if (values.add(partition.getValues())) {
                additions.add(partition);
            } else if (!statement.ifNotExists()) {
                throw new SpectrumSqlException("42710", "Partition already exists: " + partition.getValues());
            }
        }
        requireSchemaOwner(backend, binding.schemaName());
        List<Partition> created = new ArrayList<>();
        try {
            for (Partition partition : additions) {
                glue.createPartition(binding.glueDatabase(), statement.tableName(), partition);
                created.add(partition);
            }
            metadata.refresh(backend, session.accountId(), binding);
        } catch (RuntimeException failure) {
            for (Partition partition : created.reversed()) {
                try {
                    glue.deletePartition(binding.glueDatabase(), statement.tableName(), partition.getValues());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    LOG.warnv(cleanupFailure, "Unable to undo partition {0} of {1}.{2}",
                            partition.getValues(), binding.glueDatabase(), statement.tableName());
                }
            }
            throw failure;
        } finally {
            materializer.invalidateTable(session.clusterKey(), session.databaseName(), binding.schemaName(), statement.tableName());
        }
    }

    private void dropTable(ExternalStatement.DropTable statement, ExternalSchemaBinding binding,
                           SpectrumSession session, BackendSql backend) {
        try (ExternalCatalogLocks.Guard ignored = catalogLocks.write(binding)) {
            requireCurrentBinding(session, binding);
            dropTableLocked(statement, binding, session, backend);
        }
    }

    private void dropTableLocked(ExternalStatement.DropTable statement, ExternalSchemaBinding binding,
                                 SpectrumSession session, BackendSql backend) {
        boolean exists = true;
        try {
            glue.getTable(binding.glueDatabase(), statement.tableName());
        } catch (AwsException exception) {
            if (!statement.ifExists() || !"EntityNotFoundException".equals(exception.getErrorCode())) {
                throw exception;
            }
            exists = false;
        }
        requireSchemaOwner(backend, binding.schemaName());
        // Cold tables have no PostgreSQL relation. Check any cached relation's dependencies before deleting Glue metadata.
        backend.execute("DROP TABLE IF EXISTS " + ExternalTableMaterializer.quote(binding.schemaName()) + "."
                + ExternalTableMaterializer.quote(statement.tableName()) + (statement.cascade() ? " CASCADE" : " RESTRICT"));
        materializer.forget(session.clusterKey(), session.databaseName(), binding.schemaName(), statement.tableName());
        if (exists) {
            glue.deleteTable(binding.glueDatabase(), statement.tableName());
        }
        metadata.refresh(backend, session.accountId(), binding);
    }

    private void dropSchema(ExternalStatement.DropSchema statement, ExternalSchemaBinding binding,
                            SpectrumSession session, BackendSql backend) {
        try (ExternalCatalogLocks.Guard ignored = catalogLocks.write(binding)) {
            requireCurrentBinding(session, binding);
            requireSchemaOwner(backend, binding.schemaName());
            if (!statement.cascade() && hasGlueTables(binding)) {
                throw new SpectrumSqlException("2BP01", "Cannot drop external schema because it contains external tables");
            }
            // One implicit PostgreSQL transaction keeps the local schema and its SVV metadata in sync.
            backend.execute("DROP SCHEMA " + (statement.ifExists() ? "IF EXISTS " : "")
                    + ExternalTableMaterializer.quote(binding.schemaName()) + (statement.cascade() ? " CASCADE" : " RESTRICT")
                    + "; " + metadata.purgeSql(binding.schemaName()));
            registry.unbind(session.accountId(), session.clusterKey(), session.databaseName(), binding.schemaName());
            materializer.forgetSchema(session.clusterKey(), session.databaseName(), binding.schemaName());
        }
    }

    private boolean hasGlueTables(ExternalSchemaBinding binding) {
        try {
            return !glue.getTables(binding.glueDatabase()).isEmpty();
        } catch (AwsException exception) {
            if (!"EntityNotFoundException".equals(exception.getErrorCode())) {
                throw exception;
            }
            return false;
        }
    }

    private void requireCurrentBinding(SpectrumSession session, ExternalSchemaBinding binding) {
        if (!binding.equals(bindings(session).get(binding.schemaName()))) {
            throw new SpectrumSqlException("42P01", "External schema binding changed; retry the statement");
        }
    }

    private static void requireSchemaOwner(BackendSql backend, String schemaName) {
        String body = "BEGIN IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = "
                + sqlLiteral(schemaName) + " AND pg_catalog.pg_has_role(current_user, n.nspowner, 'USAGE')) THEN "
                + "RAISE EXCEPTION 'Must be owner of external schema' USING ERRCODE = '42501'; END IF; END";
        backend.execute("DO " + sqlLiteral(body));
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private boolean createSchema(ExternalStatement.CreateSchema schema, SpectrumSession session, BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("0A000", "CREATE EXTERNAL SCHEMA inside a transaction is not supported");
        }
        Optional<ExternalSchemaBinding> existing = registry.find(session.accountId(), session.clusterKey(),
                session.databaseName(), schema.schemaName());
        if (existing.isPresent()) {
            if (schema.ifNotExists()) {
                return true;
            }
            throw new SpectrumSqlException("42P06", "schema \"" + schema.schemaName() + "\" already exists");
        }
        try {
            glue.getDatabase(schema.glueDatabase());
        } catch (AwsException exception) {
            if (!"EntityNotFoundException".equals(exception.getErrorCode())) {
                throw exception;
            }
            if (!schema.createDatabaseIfNotExists()) {
                // Preserve the legacy catalog path for schemas without a Glue database.
                return false;
            }
            Database database = new Database();
            database.setName(schema.glueDatabase());
            glue.createDatabase(database);
        }
        ExternalSchemaBinding binding = new ExternalSchemaBinding(session.accountId(), session.clusterKey(),
                session.databaseName(), schema.schemaName(), schema.glueDatabase(), schema.iamRoleArn());
        backend.execute("CREATE SCHEMA " + ExternalTableMaterializer.quote(schema.schemaName()));
        try {
            metadata.refresh(backend, session.accountId(), binding);
            bindWhenCommitted(backend, binding);
        } catch (RuntimeException exception) {
            try {
                backend.execute("DROP SCHEMA " + ExternalTableMaterializer.quote(schema.schemaName()) + " CASCADE");
            } catch (RuntimeException cleanupFailure) {
                LOG.warnv(cleanupFailure, "Could not clean up failed external schema {0}", schema.schemaName());
            }
            throw exception;
        }
        return true;
    }

    private Map<String, ExternalSchemaBinding> bindings(SpectrumSession session) {
        Map<String, ExternalSchemaBinding> bindings = new LinkedHashMap<>();
        for (ExternalSchemaBinding binding : registry.list(session.accountId(), session.clusterKey(), session.databaseName())) {
            bindings.put(binding.schemaName(), binding);
        }
        return bindings;
    }

    public void forgetRuntime(String accountId, String clusterKey) {
        registry.removeCluster(accountId, clusterKey);
        materializer.forgetCluster(clusterKey);
    }

    public void invalidateRuntime(String clusterKey) {
        materializer.forgetCluster(clusterKey);
    }

    /**
     * A backend that shares the client's transaction can still roll the schema back, so the binding is held
     * until {@link #finishCycle(BackendSql, boolean)} sees the commit. Any other backend has committed already.
     */
    private void bindWhenCommitted(BackendSql backend, ExternalSchemaBinding binding) {
        if (backend.permitsCachePublication()) {
            registry.bind(binding);
            return;
        }
        pendingBindings.computeIfAbsent(backend, ignored -> new CopyOnWriteArrayList<>()).add(binding);
    }

    /** The transaction rolled back: drop everything staged for it. */
    public void finishCycle(BackendSql backend) {
        pendingBindings.remove(backend);
        materializer.finishCycle(backend);
    }

    public void finishCycle(BackendSql backend, boolean committed) {
        List<ExternalSchemaBinding> completed = pendingBindings.remove(backend);
        if (committed && completed != null) {
            completed.forEach(registry::bind);
        }
        materializer.finishCycle(backend, committed);
    }

    /**
     * A rollback to a savepoint leaves the transaction open, so only the loads are dropped: they may have been
     * made after the savepoint, and their fingerprints would no longer match the backend tables.
     */
    public void discardPendingLoads(BackendSql backend) {
        materializer.finishCycle(backend);
    }
}
