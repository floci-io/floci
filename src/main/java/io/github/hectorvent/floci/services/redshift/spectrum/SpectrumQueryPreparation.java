package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

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
    private final Map<BackendSql, List<ExternalSchemaBinding>> pendingBindings = new ConcurrentHashMap<>();

    public SpectrumQueryPreparation(ExternalCatalogRegistry registry, ExternalTableMaterializer materializer,
                                    ExternalStatementParser parser, GlueService glue, ExternalMetadataWriter metadata) {
        this.registry = registry;
        this.materializer = materializer;
        this.parser = parser;
        this.glue = glue;
        this.metadata = metadata;
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
        Optional<ExternalStatement> statement = parser.parse(sql);
        return statement.orElse(null) instanceof ExternalStatement.CreateSchema
                || statement.orElse(null) instanceof ExternalStatement.CreateTable table
                && (session.inTransaction() || bindings(session).containsKey(table.schemaName()));
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
            glue.createTable(binding.glueDatabase(), GlueTableBuilder.toGlueTable(table));
            metadata.refresh(backend, session.accountId(), binding);
            return true;
        }
        Optional<ExternalReferenceScanner.Reference> write = ExternalReferenceScanner.writeTarget(sql, bindings.keySet());
        if (write.isPresent()) {
            throw new SpectrumSqlException("0A000", "Writes to external relation \""
                    + write.get().schema() + "." + write.get().table() + "\" are not supported");
        }
        Set<String> refreshed = new HashSet<>();
        for (ExternalReferenceScanner.Reference reference : ExternalReferenceScanner.scan(sql, bindings.keySet())) {
            ExternalSchemaBinding binding = bindings.get(reference.schema());
            ExternalTableMaterializer.Outcome outcome = materializer.ensureCurrent(backend, session, binding, reference.table());
            if (outcome == ExternalTableMaterializer.Outcome.NOT_EXTERNAL) {
                throw new SpectrumSqlException("42P01", "external relation \"" + reference.schema()
                        + "." + reference.table() + "\" does not exist");
            }
            if (outcome == ExternalTableMaterializer.Outcome.LOADED && refreshed.add(binding.schemaName())) {
                metadata.refresh(backend, session.accountId(), binding);
            }
        }
        return false;
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
