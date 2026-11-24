package io.floci.aws.services.redshift.spectrum;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SqlParameterParser;
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
import java.util.regex.Pattern;

@ApplicationScoped
public class SpectrumQueryPreparation {
    private static final Logger LOG = Logger.getLogger(SpectrumQueryPreparation.class);
    private static final String DUPLICATE_SCHEMA = "42P06";
    private static final String UNDEFINED_FUNCTION = "42883";
    private static final String INVALID_SCHEMA_NAME = "3F000";
    /** Fallback when checking an unqualified DROP FUNCTION without a live backend session. */
    private static final String DEFAULT_FUNCTION_SCHEMA = "public";
    static final String CURRENT_SCHEMA_SQL = "SELECT pg_catalog.current_schema()";
    private static final Pattern JOIN = Pattern.compile("\\bJOIN\\b", Pattern.CASE_INSENSITIVE);
    // Each batch is inserted on its own, so a view that groups or aggregates would keep a partial row per batch.
    private static final Pattern AGGREGATE = Pattern.compile(
            "\\b(?:GROUP\\s+BY|HAVING|DISTINCT)\\b|\\b(?:COUNT|SUM|AVG|MIN|MAX|MEDIAN|LISTAGG|STDDEV\\w*|VAR_\\w+|VARIANCE"
                    + "|BOOL_AND|BOOL_OR|ARRAY_AGG|APPROXIMATE|PERCENTILE_\\w+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private final ExternalCatalogRegistry registry;
    private final KinesisSchemaRegistry kinesisSchemas;
    private final MskSchemaRegistry mskSchemas;
    private final ExternalFunctionRegistry functions;
    private final KinesisStreamingViews views;
    private final MskStreamingViews mskViews;
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
                                    MskSchemaRegistry mskSchemas, ExternalFunctionRegistry functions,
                                    ExternalTableMaterializer materializer, ExternalStatementParser parser,
                                    GlueService glue, ExternalMetadataWriter metadata,
                                    ExternalCatalogLocks catalogLocks, KinesisStreamingViews views,
                                    MskStreamingViews mskViews, Instance<RedshiftService> redshift) {
        this(registry, kinesisSchemas, mskSchemas, functions, materializer, parser, glue, metadata, catalogLocks,
                views, mskViews,
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
        this(registry, kinesisSchemas,
                new MskSchemaRegistry(AccountAwareStorageBackend.<MskSchemaBinding>inMemory("000000000000")),
                inMemoryFunctions(), materializer, parser, glue, metadata, catalogLocks,
                KinesisStreamingViews.inMemory(), clusterRegion);
    }

    SpectrumQueryPreparation(ExternalCatalogRegistry registry, KinesisSchemaRegistry kinesisSchemas,
                             ExternalTableMaterializer materializer, ExternalStatementParser parser,
                             GlueService glue, ExternalMetadataWriter metadata, ExternalCatalogLocks catalogLocks,
                             KinesisStreamingViews views, BiFunction<String, String, String> clusterRegion) {
        this(registry, kinesisSchemas,
                new MskSchemaRegistry(AccountAwareStorageBackend.<MskSchemaBinding>inMemory("000000000000")),
                inMemoryFunctions(), materializer, parser, glue, metadata, catalogLocks, views, clusterRegion);
    }

    private static ExternalFunctionRegistry inMemoryFunctions() {
        return new ExternalFunctionRegistry(AccountAwareStorageBackend.<ExternalFunctionBinding>inMemory("000000000000"));
    }

    SpectrumQueryPreparation(ExternalCatalogRegistry registry, KinesisSchemaRegistry kinesisSchemas,
                             MskSchemaRegistry mskSchemas, ExternalFunctionRegistry functions,
                             ExternalTableMaterializer materializer, ExternalStatementParser parser,
                             GlueService glue, ExternalMetadataWriter metadata, ExternalCatalogLocks catalogLocks,
                             KinesisStreamingViews views, BiFunction<String, String, String> clusterRegion) {
        this(registry, kinesisSchemas, mskSchemas, functions, materializer, parser, glue, metadata, catalogLocks,
                views, MskStreamingViews.inMemory(), clusterRegion);
    }

    SpectrumQueryPreparation(ExternalCatalogRegistry registry, KinesisSchemaRegistry kinesisSchemas,
                             MskSchemaRegistry mskSchemas, ExternalFunctionRegistry functions,
                             ExternalTableMaterializer materializer, ExternalStatementParser parser,
                             GlueService glue, ExternalMetadataWriter metadata, ExternalCatalogLocks catalogLocks,
                             KinesisStreamingViews views, MskStreamingViews mskViews,
                             BiFunction<String, String, String> clusterRegion) {
        this.functions = functions;
        this.views = views;
        this.mskViews = mskViews;
        this.clusterRegion = clusterRegion;
        this.registry = registry;
        this.kinesisSchemas = kinesisSchemas;
        this.mskSchemas = mskSchemas;
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
                case "ResourceNotFoundException" -> "42P01";
                case "AccessDenied", "AccessDeniedException" -> "42501";
                case "InvalidInputException" -> "42601";
                default -> "58030";
            };
            throw new SpectrumSqlException(state, exception.getMessage());
        }
    }

    public boolean referencesExternal(String sql, SpectrumSession session) {
        Set<String> schemas = bindingsOf(registry, session).keySet();
        Set<String> kinesisSchemaNames = bindingsOf(kinesisSchemas, session).keySet();
        Set<String> mskSchemaNames = bindingsOf(mskSchemas, session).keySet();
        return !ExternalReferenceScanner.scan(sql, schemas).isEmpty()
                || !ExternalReferenceScanner.scan(sql, kinesisSchemaNames).isEmpty()
                || !ExternalReferenceScanner.scan(sql, mskSchemaNames).isEmpty()
                || ExternalReferenceScanner.writeTarget(sql,
                        externalSchemaNames(schemas, kinesisSchemaNames, mskSchemaNames)).isPresent();
    }

    public boolean needsBackendForDdl(String sql, SpectrumSession session) {
        return parser.parse(sql).orElse(null) instanceof ExternalStatement.DropFunction function
                && function.schemaName() == null
                && functions.hasFunctionNamed(session, function.functionName(), function.argumentTypes());
    }

    public boolean handlesDdl(String sql, SpectrumSession session) {
        return handlesDdl(sql, session, null);
    }

    public boolean handlesDdl(String sql, SpectrumSession session, BackendSql backend) {
        ExternalStatement statement = parser.parse(sql).orElse(null);
        return switch (statement) {
            case ExternalStatement.CreateSchema ignored -> true;
            case ExternalStatement.CreateKinesisSchema ignored -> true;
            case ExternalStatement.CreateMskSchema ignored -> true;
            case ExternalStatement.CreateFunction ignored -> true;
            case ExternalStatement.DropFunction function -> findFunctionToDrop(function, session, backend).isPresent();
            case ExternalStatement.CreateTable table -> session.inTransaction()
                    || bindingsOf(registry, session).containsKey(table.schemaName())
                    || bindingsOf(kinesisSchemas, session).containsKey(table.schemaName())
                    || bindingsOf(mskSchemas, session).containsKey(table.schemaName());
            case ExternalStatement.AddPartitions partitions ->
                    bindingsOf(registry, session).containsKey(partitions.schemaName());
            case ExternalStatement.DropTable table -> bindingsOf(registry, session).containsKey(table.schemaName());
            case ExternalStatement.DropSchema schema -> bindingsOf(registry, session).containsKey(schema.schemaName())
                    || bindingsOf(kinesisSchemas, session).containsKey(schema.schemaName())
                    || bindingsOf(mskSchemas, session).containsKey(schema.schemaName());
            case ExternalStatement.CreateStreamingView view -> streamSource(view.select(), session).isPresent()
                    || !ExternalReferenceScanner.scan(view.select(), bindingsOf(mskSchemas, session).keySet()).isEmpty();
            case ExternalStatement.RefreshView view ->
                    views.find(session, view.viewSchema(), view.viewName()).isPresent()
                            || mskViews.find(session, view.viewSchema(), view.viewName()).isPresent();
            case ExternalStatement.DropView view ->
                    views.find(session, view.viewSchema(), view.viewName()).isPresent()
                            || mskViews.find(session, view.viewSchema(), view.viewName()).isPresent();
            case null -> false;
        };
    }

    public String commandTag(String sql) {
        return switch (parser.parse(sql).orElse(null)) {
            case ExternalStatement.AddPartitions ignored -> "ALTER TABLE";
            case ExternalStatement.DropTable ignored -> "DROP TABLE";
            case ExternalStatement.DropSchema ignored -> "DROP SCHEMA";
            case ExternalStatement.CreateStreamingView ignored -> "CREATE MATERIALIZED VIEW";
            case ExternalStatement.RefreshView ignored -> "REFRESH MATERIALIZED VIEW";
            case ExternalStatement.DropView ignored -> "DROP MATERIALIZED VIEW";
            case ExternalStatement.CreateFunction ignored -> "CREATE FUNCTION";
            case ExternalStatement.DropFunction ignored -> "DROP FUNCTION";
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
        if (statement.orElse(null) instanceof ExternalStatement.CreateMskSchema schema) {
            return createMskSchema(schema, session, backend);
        }
        if (statement.orElse(null) instanceof ExternalStatement.CreateFunction function) {
            return createFunction(function, session, backend);
        }
        if (statement.orElse(null) instanceof ExternalStatement.DropFunction function) {
            return dropFunction(function, session, backend);
        }
        Map<String, KinesisSchemaBinding> kinesis = bindingsOf(kinesisSchemas, session);
        Map<String, MskSchemaBinding> msk = bindingsOf(mskSchemas, session);
        if (statement.orElse(null) instanceof ExternalStatement.DropSchema schema
                && kinesis.containsKey(schema.schemaName())) {
            rejectTransaction(session);
            List<KinesisViewBinding> dependents = views.overSchema(session, schema.schemaName());
            dropStreamingSchema(schema, kinesis.get(schema.schemaName()), kinesisSchemas,
                    dependents.stream().map(dependent -> StreamingViewSupport.dropSql(dependent.viewSchema(), dependent.viewName(), true, true)).toList(),
                    () -> dependents.forEach(views::unbindIfCurrent), session, backend);
            return true;
        }
        if (statement.orElse(null) instanceof ExternalStatement.DropSchema schema
                && msk.containsKey(schema.schemaName())) {
            rejectTransaction(session);
            // no view may be created between reading the schema's views and dropping it, or its binding would outlive it
            mskViews.exclusivelyOnSchema(session, schema.schemaName(), () -> {
                List<MskViewBinding> dependents = mskViews.overSchema(session, schema.schemaName());
                dropStreamingSchema(schema, msk.get(schema.schemaName()), mskSchemas,
                        dependents.stream().map(dependent -> StreamingViewSupport.dropSql(dependent.viewSchema(), dependent.viewName(), true, true)).toList(),
                        () -> dependents.forEach(mskViews::unbindIfCurrent), session, backend);
            });
            return true;
        }
        if (statement.orElse(null) instanceof ExternalStatement.CreateStreamingView view) {
            boolean overKinesis = !ExternalReferenceScanner.scan(view.select(), kinesis.keySet()).isEmpty();
            boolean overMsk = !ExternalReferenceScanner.scan(view.select(), msk.keySet()).isEmpty();
            if (overKinesis && overMsk) {
                throw new SpectrumSqlException("0A000",
                        "A streaming materialized view reads exactly one stream or topic");
            }
            if (overKinesis) {
                createStreamingView(view, kinesis, session, backend);
                return true;
            }
            if (overMsk) {
                createMskStreamingView(view, msk, session, backend);
                return true;
            }
        }
        if (statement.orElse(null) instanceof ExternalStatement.RefreshView refresh) {
            Optional<KinesisViewBinding> view = views.find(session, refresh.viewSchema(), refresh.viewName());
            if (view.isPresent()) {
                refreshStreamingView(view.get(), kinesis, session, backend);
                return true;
            }
            Optional<MskViewBinding> mskView = mskViews.find(session, refresh.viewSchema(), refresh.viewName());
            if (mskView.isPresent()) {
                refreshMskStreamingView(mskView.get(), msk, session, backend);
                return true;
            }
        }
        if (statement.orElse(null) instanceof ExternalStatement.DropView drop) {
            Optional<KinesisViewBinding> view = views.find(session, drop.viewSchema(), drop.viewName());
            if (view.isPresent()) {
                rejectTransaction(session);
                views.drop(view.get(), drop.ifExists(), drop.cascade(), backend);
                return true;
            }
            Optional<MskViewBinding> mskView = mskViews.find(session, drop.viewSchema(), drop.viewName());
            if (mskView.isPresent()) {
                rejectTransaction(session);
                mskViews.drop(mskView.get(), drop.ifExists(), drop.cascade(), backend);
                return true;
            }
        }
        Map<String, ExternalSchemaBinding> bindings = bindingsOf(registry, session);
        if (statement.orElse(null) instanceof ExternalStatement.CreateTable && session.inTransaction()) {
            // Legacy-catalog tables are saved immediately too, so a rollback would leave them behind.
            throw new SpectrumSqlException("25001", "CREATE EXTERNAL TABLE cannot run inside a transaction block");
        }
        if (statement.orElse(null) instanceof ExternalStatement.CreateTable table
                && (kinesis.containsKey(table.schemaName()) || msk.containsKey(table.schemaName()))) {
            throw new SpectrumSqlException("0A000", "CREATE EXTERNAL TABLE is not supported in streaming external schema \""
                    + table.schemaName() + "\"");
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
        Set<ExternalReferenceScanner.Reference> topics = ExternalReferenceScanner.scan(sql, msk.keySet());
        if (!topics.isEmpty()) {
            ExternalReferenceScanner.Reference topic = topics.iterator().next();
            throw new SpectrumSqlException("0A000", "\"" + topic.schema() + "." + topic.table()
                    + "\" is in an MSK external schema, whose topics are only read through a streaming materialized view");
        }
        Optional<ExternalReferenceScanner.Reference> write = ExternalReferenceScanner.writeTarget(sql,
                externalSchemaNames(bindings.keySet(), kinesis.keySet(), msk.keySet()));
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

    private static Set<String> externalSchemaNames(Set<String> glueSchemas, Set<String> kinesisSchemas,
                                                   Set<String> mskSchemas) {
        Set<String> names = new HashSet<>(glueSchemas);
        names.addAll(kinesisSchemas);
        names.addAll(mskSchemas);
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
        if (!binding.equals(bindingsOf(registry, session).get(binding.schemaName()))) {
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

    private boolean createMskSchema(ExternalStatement.CreateMskSchema schema, SpectrumSession session,
                                    BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("0A000", "CREATE EXTERNAL SCHEMA inside a transaction is not supported");
        }
        if (schemaExists(session, schema.schemaName())) {
            return existingSchemaOutcome(schema.schemaName(), schema.ifNotExists());
        }
        MskSchemaBinding binding = new MskSchemaBinding(session.accountId(), session.clusterKey(),
                session.databaseName(), schema.schemaName(), schema.iamRoleArn(),
                schema.authentication(), schema.clusterArn());
        createBoundSchema(backend, schema.schemaName(), schema.ifNotExists(),
                () -> metadata.refreshMsk(backend, binding), () -> mskSchemas.bind(binding));
        return true;
    }

    /**
     * Creates the stub function the backend holds and remembers what the statement declared. The stub refuses every
     * call with {@code 0A000}: the backend cannot reach Lambda, so evaluating a call is not wired yet.
     */
    private boolean createFunction(ExternalStatement.CreateFunction function, SpectrumSession session,
                                   BackendSql backend) {
        rejectTransaction(session);
        String schema = function.schemaName() != null ? function.schemaName() : currentSchema(backend);
        ExternalFunctionBinding binding = new ExternalFunctionBinding(session.accountId(), session.clusterKey(),
                session.databaseName(), schema, function.functionName(), function.argumentTypes(),
                function.returnType(), function.volatility(), function.lambdaFunction(), function.iamRoleArn(),
                function.retryTimeoutMillis(), function.maxBatchRows(), function.maxBatchSizeBytes());
        // OR REPLACE only replaces a function this registry made: any other function of the signature is an
        // ordinary one that a stub must not overwrite, so it is created plainly and the backend refuses it (42723).
        // The backend still decides whether the return type differs on a replace (42P13).
        boolean replace = function.orReplace() && functions.findFunction(session, schema, function.functionName(),
                function.argumentTypes()).isPresent();
        backend.execute(stubSql(function, schema, replace));
        try {
            functions.replace(binding);
        } catch (RuntimeException exception) {
            if (!replace) {
                try {
                    backend.execute(dropStubSql(schema, function.functionName(), function.argumentTypes(), false, false));
                } catch (RuntimeException cleanupFailure) {
                    LOG.warnv(cleanupFailure, "Could not clean up failed external function {0}", function.functionName());
                }
            }
            throw exception;
        }
        return true;
    }

    private boolean dropFunction(ExternalStatement.DropFunction function, SpectrumSession session, BackendSql backend) {
        Optional<ExternalFunctionBinding> binding = findFunctionToDrop(function, session, backend);
        if (binding.isEmpty()) {
            return false;
        }
        rejectTransaction(session);
        String schema = binding.get().schemaName();
        try {
            backend.execute(dropStubSql(schema, function.functionName(), function.argumentTypes(),
                    function.ifExists(), function.cascade()));
        } catch (SpectrumReadException exception) {
            // The stub went with its schema, so the binding describes nothing and must not outlive the failure.
            if (UNDEFINED_FUNCTION.equals(exception.sqlState())) {
                functions.unbindFunction(binding.get());
            }
            throw exception;
        }
        functions.unbindFunction(binding.get());
        return true;
    }

    private Optional<ExternalFunctionBinding> findFunctionToDrop(ExternalStatement.DropFunction function,
                                                                 SpectrumSession session, BackendSql backend) {
        if (function.schemaName() != null) {
            return functions.findFunction(session, function.schemaName(), function.functionName(),
                    function.argumentTypes());
        }
        if (!functions.hasFunctionNamed(session, function.functionName(), function.argumentTypes())) {
            return Optional.empty();
        }
        if (backend == null) {
            return functions.findFunction(session, DEFAULT_FUNCTION_SCHEMA, function.functionName(),
                    function.argumentTypes());
        }
        Optional<String> resolved = backend.queryString(resolveFunctionSchemaSql(
                function.functionName(), function.argumentTypes()));
        if (resolved.isPresent()) {
            return functions.findFunction(session, resolved.get(), function.functionName(), function.argumentTypes());
        }
        // The stub may already be gone; check the current schema so a stale binding is cleared when DROP fails (42883).
        return currentSchemaOf(backend)
                .flatMap(schema -> functions.findFunction(session, schema, function.functionName(),
                        function.argumentTypes()));
    }

    private static String currentSchema(BackendSql backend) {
        return currentSchemaOf(backend).orElseThrow(() -> new SpectrumSqlException(INVALID_SCHEMA_NAME,
                "no schema has been selected to create in"));
    }

    /** The session's current schema, empty when the search_path selects none. */
    private static Optional<String> currentSchemaOf(BackendSql backend) {
        return backend.queryString(CURRENT_SCHEMA_SQL).filter(schema -> !schema.isEmpty());
    }

    static String resolveFunctionSchemaSql(String functionName, List<String> argumentTypes) {
        String signature = ExternalTableMaterializer.quote(functionName)
                + "(" + String.join(", ", argumentTypes) + ")";
        return "SELECT n.nspname FROM pg_catalog.pg_proc p "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE p.oid = pg_catalog.to_regprocedure(" + PostgresWire.quoteLiteral(signature) + ")";
    }

    private static String stubSql(ExternalStatement.CreateFunction function, String schema, boolean replace) {
        // The message is an argument, not the format string, so a % in a quoted name is not read as a placeholder.
        String body = "BEGIN RAISE EXCEPTION '%', "
                + PostgresWire.quoteLiteral("External function " + schema + "." + function.functionName()
                        + " cannot be called: invoking Lambda from SQL is not supported yet")
                + " USING ERRCODE = '0A000'; END";
        return "CREATE " + (replace ? "OR REPLACE " : "") + "FUNCTION "
                + signature(schema, function.functionName(), function.argumentTypes())
                + " RETURNS " + function.returnType() + " LANGUAGE plpgsql " + function.volatility() + " AS "
                + PostgresWire.quoteLiteral(body);
    }

    private static String dropStubSql(String schema, String functionName, List<String> argumentTypes,
                                      boolean ifExists, boolean cascade) {
        return "DROP FUNCTION " + (ifExists ? "IF EXISTS " : "") + signature(schema, functionName, argumentTypes)
                + (cascade ? " CASCADE" : " RESTRICT");
    }

    private static String signature(String schema, String functionName, List<String> argumentTypes) {
        return ExternalTableMaterializer.quote(schema) + "." + ExternalTableMaterializer.quote(functionName)
                + "(" + String.join(", ", argumentTypes) + ")";
    }

    /** A name is taken when any kind of external schema already holds it. */
    private boolean schemaExists(SpectrumSession session, String schemaName) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(), schemaName).isPresent()
                || kinesisSchemas.find(session.accountId(), session.clusterKey(), session.databaseName(),
                schemaName).isPresent()
                || mskSchemas.find(session.accountId(), session.clusterKey(), session.databaseName(),
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

    private <B extends SchemaBinding> void dropStreamingSchema(ExternalStatement.DropSchema statement, B binding,
                                                               SchemaBindingRegistry<B> schemaRegistry,
                                                               List<String> dropDependentSql,
                                                               Runnable unbindDependents,
                                                               SpectrumSession session, BackendSql backend) {
        requireSchemaOwner(backend, binding.schemaName());
        // CASCADE takes the streaming views that read the schema with it; RESTRICT stops on their staging tables.
        StringBuilder dropViews = new StringBuilder();
        if (statement.cascade()) {
            for (String dropDependent : dropDependentSql) {
                dropViews.append(dropDependent).append("; ");
            }
        }
        // One implicit PostgreSQL transaction keeps the local schema and its SVV metadata in sync.
        backend.execute(dropViews + "DROP SCHEMA " + (statement.ifExists() ? "IF EXISTS " : "")
                + ExternalTableMaterializer.quote(binding.schemaName()) + (statement.cascade() ? " CASCADE" : " RESTRICT")
                + "; " + metadata.purgeSql(binding.schemaName()));
        if (statement.cascade()) {
            unbindDependents.run();
        }
        // a concurrent drop and re-create of the name may have bound a newer schema meanwhile
        if (binding.equals(schemaRegistry.find(session.accountId(), session.clusterKey(), session.databaseName(),
                binding.schemaName()).orElse(null))) {
            schemaRegistry.unbind(session.accountId(), session.clusterKey(), session.databaseName(),
                    binding.schemaName());
        }
    }

    private Optional<ExternalReferenceScanner.Reference> streamSource(String select, SpectrumSession session) {
        return ExternalReferenceScanner.scan(select, bindingsOf(kinesisSchemas, session).keySet()).stream().findFirst();
    }

    private void createStreamingView(ExternalStatement.CreateStreamingView view,
                                     Map<String, KinesisSchemaBinding> kinesis,
                                     SpectrumSession session, BackendSql backend) {
        requireStreamingViewShape(view, session);
        Set<ExternalReferenceScanner.Reference> streams =
                ExternalReferenceScanner.scan(view.select(), kinesis.keySet());
        if (streams.size() != 1) {
            throw new SpectrumSqlException("0A000", "A streaming materialized view reads exactly one stream");
        }
        ExternalReferenceScanner.Reference stream = streams.iterator().next();
        views.create(view, stream.table(), kinesis.get(stream.schema()), session, catalogRegion(session), backend);
    }

    private void createMskStreamingView(ExternalStatement.CreateStreamingView view,
                                        Map<String, MskSchemaBinding> msk,
                                        SpectrumSession session, BackendSql backend) {
        requireStreamingViewShape(view, session);
        Set<ExternalReferenceScanner.Reference> topics = ExternalReferenceScanner.scan(view.select(), msk.keySet());
        if (topics.size() != 1) {
            throw new SpectrumSqlException("0A000", "A streaming materialized view reads exactly one topic");
        }
        ExternalReferenceScanner.Reference topic = topics.iterator().next();
        mskViews.create(view, topic.table(), msk.get(topic.schema()), session, backend);
    }

    /** What a streaming view over any source must satisfy, before the source is looked at. */
    private static void requireStreamingViewShape(ExternalStatement.CreateStreamingView view, SpectrumSession session) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("0A000",
                    "CREATE MATERIALIZED VIEW over a stream inside a transaction is not supported");
        }
        if (view.autoRefresh()) {
            throw new SpectrumSqlException("0A000",
                    "AUTO REFRESH YES is not supported; refresh the view with REFRESH MATERIALIZED VIEW");
        }
        String unquoted = SqlParameterParser.blankQuoted(view.select(), SqlParameterParser.Options.REDSHIFT);
        if (JOIN.matcher(unquoted).find()) {
            throw new SpectrumSqlException("0A000", "A streaming materialized view cannot contain a JOIN");
        }
        if (AGGREGATE.matcher(unquoted).find()) {
            throw new SpectrumSqlException("0A000",
                    "A streaming materialized view cannot group or aggregate: each refresh batch is inserted on its own");
        }
    }

    private void refreshStreamingView(KinesisViewBinding view, Map<String, KinesisSchemaBinding> kinesis,
                                      SpectrumSession session, BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("25001",
                    "REFRESH MATERIALIZED VIEW of a streaming view cannot run inside a transaction block");
        }
        KinesisSchemaBinding schema = kinesis.get(view.streamSchema());
        if (schema == null) {
            throw new SpectrumSqlException("42P01", "external schema \"" + view.streamSchema() + "\" does not exist");
        }
        views.refresh(view, schema, catalogRegion(session), backend);
    }

    private void refreshMskStreamingView(MskViewBinding view, Map<String, MskSchemaBinding> msk,
                                         SpectrumSession session, BackendSql backend) {
        if (session.inTransaction()) {
            throw new SpectrumSqlException("25001",
                    "REFRESH MATERIALIZED VIEW of a streaming view cannot run inside a transaction block");
        }
        MskSchemaBinding schema = msk.get(view.topicSchema());
        if (schema == null) {
            throw new SpectrumSqlException("42P01", "external schema \"" + view.topicSchema() + "\" does not exist");
        }
        mskViews.refresh(view, schema, backend);
    }

    private <B extends SchemaBinding> Map<String, B> bindingsOf(SchemaBindingRegistry<B> schemaRegistry,
                                                                 SpectrumSession session) {
        Map<String, B> bindings = new LinkedHashMap<>();
        for (B binding : schemaRegistry.list(session.accountId(), session.clusterKey(), session.databaseName())) {
            bindings.put(binding.schemaName(), binding);
        }
        return bindings;
    }

    public void forgetRuntime(String accountId, String clusterKey) {
        registry.removeCluster(accountId, clusterKey);
        kinesisSchemas.removeCluster(accountId, clusterKey);
        mskSchemas.removeCluster(accountId, clusterKey);
        functions.removeCluster(accountId, clusterKey);
        views.forgetCluster(accountId, clusterKey);
        mskViews.forgetCluster(accountId, clusterKey);
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
