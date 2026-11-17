package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Partition;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.redshift.RedshiftService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
import java.util.function.BiFunction;

@ApplicationScoped
public class SpectrumQueryPreparation {
    private static final Logger LOG = Logger.getLogger(SpectrumQueryPreparation.class);
    private static final String DUPLICATE_SCHEMA = "42P06";
    private final ExternalCatalogRegistry registry;
    private final KinesisSchemaRegistry kinesisSchemas;
    private final ExternalTableMaterializer materializer;
    private final ExternalStatementParser parser;
    private final GlueService glue;
    private final ExternalMetadataWriter metadata;
    private final ExternalCatalogLocks catalogLocks;
    /** The Region of an account's cluster, by its identifier; null when it isn't known. */
    private final BiFunction<String, String, String> clusterRegion;
    /** What to bind once the statement that created each schema has committed, by backend. */
    private final Map<BackendSql, List<Runnable>> pendingBindings = new ConcurrentHashMap<>();

    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                                    ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata) {
        this(registry, materializer, parser, glue, metadata, new ExternalCatalogLocks());
    }

    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                                    ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata,
                                    ExternalCatalogLocks catalogLocks) {
        this(registry, new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory("000000000000")),
                materializer, parser, glue, metadata, catalogLocks, (accountId, clusterIdentifier) -> null);
    }

    @Inject
    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, KinesisSchemaRegistry kinesisSchemas,
                                    ExternalTableMaterializer materializer, ExternalStatementParser parser,
                                    GlueService glue, ExternalMetadataWriter metadata,
                                    ExternalCatalogLocks catalogLocks, Instance<RedshiftService> redshift) {
        this(registry, kinesisSchemas, materializer, parser, glue, metadata, catalogLocks,
                (accountId, clusterIdentifier) -> redshift.get().clusterRegion(accountId, clusterIdentifier).orElse(null));
    }

    SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                             ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata,
                             ExternalCatalogLocks catalogLocks, BiFunction<String, String, String> clusterRegion) {
        this(registry, new KinesisSchemaRegistry(AccountAwareStorageBackend.<KinesisSchemaBinding>inMemory("000000000000")),
                materializer, parser, glue, metadata, catalogLocks, clusterRegion);
    }

    SpectrumQueryPreparation(ExternalCatalogRegistry registry, KinesisSchemaRegistry kinesisSchemas,
                             ExternalTableMaterializer materializer, ExternalStatementParser parser,
                             GlueService glue, ExternalMetadataWriter metadata, ExternalCatalogLocks catalogLocks,
                             BiFunction<String, String, String> clusterRegion) {
        this.clusterRegion = clusterRegion;
        this.registry = registry;
        this.kinesisSchemas = kinesisSchemas;
        this.materializer = materializer;
        this.parser = parser;
        this.glue = glue;
        this.metadata = metadata;
        this.catalogLocks = catalogLocks;
    }

    /**
     * The Region whose Glue catalog the session's cluster reads: the cluster's own, as on AWS. Null when the
     * cluster isn't a provisioned one Floci knows, which leaves the Region the call already runs in, such as a
     * Data API request's.
     */
    private String catalogRegion(SpectrumSession session) {
        String clusterIdentifier = session.clusterIdentifier();
        return clusterIdentifier == null ? null : clusterRegion.apply(session.accountId(), clusterIdentifier);
    }

    public boolean prepare(String sql, SpectrumSession session, BackendSql backend) {
        return prepare(sql, session, backend, List.of());
    }

    /**
     * Prepares a statement that has bind parameters. {@code parameters} is the text of each bound value, a null
     * where it is unusable; null {@code parameters} means the statement is only being parsed, with no values yet.
     */
    public boolean prepare(String sql, SpectrumSession session, BackendSql backend, List<String> parameters) {
        try {
            return RequestScopes.callAs(session.accountId(), catalogRegion(session),
                    () -> prepareInScope(sql, session, backend, parameters));
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
        Set<String> kinesisSchemaNames = kinesisBindings(session).keySet();
        return !ExternalReferenceScanner.scan(sql, schemas).isEmpty()
                || !ExternalReferenceScanner.scan(sql, kinesisSchemaNames).isEmpty()
                || ExternalReferenceScanner.writeTarget(sql, externalSchemaNames(schemas, kinesisSchemaNames)).isPresent();
    }

    public boolean handlesDdl(String sql, SpectrumSession session) {
        ExternalStatement statement = parser.parse(sql).orElse(null);
        return switch (statement) {
            case ExternalStatement.CreateSchema ignored -> true;
            case ExternalStatement.CreateKinesisSchema ignored -> true;
            case ExternalStatement.CreateTable table -> session.inTransaction() || bindings(session).containsKey(table.schemaName());
            case ExternalStatement.AddPartitions partitions -> bindings(session).containsKey(partitions.schemaName());
            case ExternalStatement.DropTable table -> bindings(session).containsKey(table.schemaName());
            case ExternalStatement.DropSchema schema -> bindings(session).containsKey(schema.schemaName())
                    || kinesisBindings(session).containsKey(schema.schemaName());
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

    private boolean prepareInScope(String sql, SpectrumSession session, BackendSql backend, List<String> parameters) {
        Optional<ExternalStatement> statement = parser.parse(sql);
        if (statement.orElse(null) instanceof ExternalStatement.CreateSchema schema) {
            return createSchema(schema, session, backend);
        }
        if (statement.orElse(null) instanceof ExternalStatement.CreateKinesisSchema schema) {
            return createKinesisSchema(schema, session, backend);
        }
        Map<String, KinesisSchemaBinding> kinesis = kinesisBindings(session);
        if (statement.orElse(null) instanceof ExternalStatement.DropSchema schema
                && kinesis.containsKey(schema.schemaName())) {
            rejectTransaction(session);
            dropKinesisSchema(schema, kinesis.get(schema.schemaName()), session, backend);
            return true;
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
        // A stream is read through a materialized view, so a plain statement on a relation of the schema is
        // refused rather than left to fail as a missing table in PostgreSQL. The scanner does not tell a stream
        // from any other relation, and Redshift keeps no user tables in such a schema.
        Set<ExternalReferenceScanner.Reference> streams = ExternalReferenceScanner.scan(sql, kinesis.keySet());
        if (!streams.isEmpty()) {
            ExternalReferenceScanner.Reference stream = streams.iterator().next();
            throw new SpectrumSqlException("0A000", "\"" + stream.schema() + "." + stream.table()
                    + "\" is in a Kinesis external schema, whose streams are only read through a streaming materialized view");
        }
        Optional<ExternalReferenceScanner.Reference> write = ExternalReferenceScanner.writeTarget(sql,
                externalSchemaNames(bindings.keySet(), kinesis.keySet()));
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
                ExternalTableMaterializer.Outcome outcome = materializer.ensureCurrent(backend, session, binding, reference.table(), sql, parameters);
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

    private static Set<String> externalSchemaNames(Set<String> glueSchemas, Set<String> kinesisSchemas) {
        Set<String> names = new HashSet<>(glueSchemas);
        names.addAll(kinesisSchemas);
        return names;
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
                + PostgresWire.quoteLiteral(schemaName) + " AND pg_catalog.pg_has_role(current_user, n.nspowner, 'USAGE')) THEN "
                + "RAISE EXCEPTION 'Must be owner of external schema' USING ERRCODE = '42501'; END IF; END";
        backend.execute("DO " + PostgresWire.quoteLiteral(body));
    }

    private boolean createSchema(ExternalStatement.CreateSchema schema, SpectrumSession session, BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("0A000", "CREATE EXTERNAL SCHEMA inside a transaction is not supported");
        }
        if (schemaExists(session, schema.schemaName())) {
            return existingSchemaOutcome(schema.schemaName(), schema.ifNotExists());
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
        createBoundSchema(backend, schema.schemaName(), schema.ifNotExists(),
                () -> metadata.refresh(backend, session.accountId(), binding), () -> registry.bind(binding));
        return true;
    }

    private boolean createKinesisSchema(ExternalStatement.CreateKinesisSchema schema, SpectrumSession session,
                                        BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("0A000", "CREATE EXTERNAL SCHEMA inside a transaction is not supported");
        }
        if (schemaExists(session, schema.schemaName())) {
            return existingSchemaOutcome(schema.schemaName(), schema.ifNotExists());
        }
        KinesisSchemaBinding binding = new KinesisSchemaBinding(session.accountId(), session.clusterKey(),
                session.databaseName(), schema.schemaName(), schema.region(), schema.iamRoleArn());
        createBoundSchema(backend, schema.schemaName(), schema.ifNotExists(),
                () -> metadata.refreshKinesis(backend, binding), () -> kinesisSchemas.bind(binding));
        return true;
    }

    /** A name is taken when either kind of external schema already holds it. */
    private boolean schemaExists(SpectrumSession session, String schemaName) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(), schemaName).isPresent()
                || kinesisSchemas.find(session.accountId(), session.clusterKey(), session.databaseName(),
                schemaName).isPresent();
    }

    private static boolean existingSchemaOutcome(String schemaName, boolean ifNotExists) {
        if (ifNotExists) {
            return true;
        }
        throw new SpectrumSqlException("42P06", "schema \"" + schemaName + "\" already exists");
    }

    /**
     * Creates the local schema, publishes its metadata, and binds it once committed; removes it again on failure.
     * A local schema of that name that no binding owns is left alone under IF NOT EXISTS.
     */
    private void createBoundSchema(BackendSql backend, String schemaName, boolean ifNotExists,
                                   Runnable refreshMetadata, Runnable bind) {
        try {
            backend.execute("CREATE SCHEMA " + ExternalTableMaterializer.quote(schemaName));
        } catch (SpectrumReadException exception) {
            if (ifNotExists && DUPLICATE_SCHEMA.equals(exception.sqlState())) {
                return;
            }
            throw exception;
        }
        try {
            refreshMetadata.run();
            bindWhenCommitted(backend, bind);
        } catch (RuntimeException exception) {
            try {
                backend.execute("DROP SCHEMA " + ExternalTableMaterializer.quote(schemaName) + " CASCADE");
            } catch (RuntimeException cleanupFailure) {
                LOG.warnv(cleanupFailure, "Could not clean up failed external schema {0}", schemaName);
            }
            throw exception;
        }
    }

    private void dropKinesisSchema(ExternalStatement.DropSchema statement, KinesisSchemaBinding binding,
                                   SpectrumSession session, BackendSql backend) {
        requireSchemaOwner(backend, binding.schemaName());
        // One implicit PostgreSQL transaction keeps the local schema and its SVV metadata in sync.
        backend.execute("DROP SCHEMA " + (statement.ifExists() ? "IF EXISTS " : "")
                + ExternalTableMaterializer.quote(binding.schemaName()) + (statement.cascade() ? " CASCADE" : " RESTRICT")
                + "; " + metadata.purgeSql(binding.schemaName()));
        // a concurrent drop and re-create of the name may have bound a newer schema meanwhile
        if (binding.equals(kinesisSchemas.find(session.accountId(), session.clusterKey(), session.databaseName(),
                binding.schemaName()).orElse(null))) {
            kinesisSchemas.unbind(session.accountId(), session.clusterKey(), session.databaseName(),
                    binding.schemaName());
        }
    }

    private Map<String, KinesisSchemaBinding> kinesisBindings(SpectrumSession session) {
        Map<String, KinesisSchemaBinding> bindings = new LinkedHashMap<>();
        for (KinesisSchemaBinding binding : kinesisSchemas.list(session.accountId(), session.clusterKey(),
                session.databaseName())) {
            bindings.put(binding.schemaName(), binding);
        }
        return bindings;
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
        kinesisSchemas.removeCluster(accountId, clusterKey);
        materializer.forgetCluster(clusterKey);
    }

    public void invalidateRuntime(String clusterKey) {
        materializer.forgetCluster(clusterKey);
    }

    /**
     * A backend that shares the client's transaction can still roll the schema back, so the binding is held
     * until {@link #finishCycle(BackendSql, boolean)} sees the commit. Any other backend has committed already.
     */
    private void bindWhenCommitted(BackendSql backend, Runnable bind) {
        if (backend.permitsCachePublication()) {
            bind.run();
            return;
        }
        pendingBindings.computeIfAbsent(backend, ignored -> new CopyOnWriteArrayList<>()).add(bind);
    }

    /** The transaction rolled back: drop everything staged for it. */
    public void finishCycle(BackendSql backend) {
        pendingBindings.remove(backend);
        materializer.finishCycle(backend);
    }

    public void finishCycle(BackendSql backend, boolean committed) {
        List<Runnable> completed = pendingBindings.remove(backend);
        if (committed && completed != null) {
            completed.forEach(Runnable::run);
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
