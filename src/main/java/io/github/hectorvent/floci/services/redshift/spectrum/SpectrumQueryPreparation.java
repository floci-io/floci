package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Table;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SpectrumQueryPreparation {
    private static final Logger LOG = Logger.getLogger(SpectrumQueryPreparation.class);
    private final ExternalCatalogRegistry registry;
    private final ExternalTableMaterializer materializer;
    private final ExternalStatementParser parser;
    private final GlueService glue;
    private final ExternalMetadataWriter metadata;
    private final Map<BackendSql, List<ExternalSchemaBinding>> pendingBindings = new ConcurrentHashMap<>();
    private final Map<Object, List<StagedGlueTable>> stagedGlueTables = new ConcurrentHashMap<>();

    private final Map<Object, String> pendingSavepointRollbacks = new ConcurrentHashMap<>();
    private final Map<Object, List<Savepoint>> savepoints = new ConcurrentHashMap<>();

    private static final Pattern SAVEPOINT = Pattern.compile(
            "^\\s*SAVEPOINT\\s+(\\S+?)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLLBACK_TO_SAVEPOINT = Pattern.compile(
            "^\\s*ROLLBACK\\s+(?:WORK\\s+|TRANSACTION\\s+)?TO\\s+(?:SAVEPOINT\\s+)?(\\S+?)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELEASE_SAVEPOINT = Pattern.compile(
            "^\\s*RELEASE\\s+(?:SAVEPOINT\\s+)?(\\S+?)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    private record StagedGlueTable(String accountId, String database, String table) { }

    /** A savepoint and how many Glue tables the transaction had staged when it was taken. */
    private record Savepoint(String name, int stagedTables) { }

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
                && bindings(session).containsKey(table.schemaName());
    }

    private boolean prepareInScope(String sql, SpectrumSession session, BackendSql backend) {
        if (session.inTransaction()) {
            trackSavepoints(sql, backend);
        }
        Optional<ExternalStatement> statement = parser.parse(sql);
        if (statement.orElse(null) instanceof ExternalStatement.CreateSchema schema) {
            return createSchema(schema, session, backend);
        }
        Map<String, ExternalSchemaBinding> bindings = bindings(session);
        if (statement.orElse(null) instanceof ExternalStatement.CreateTable table && bindings.containsKey(table.schemaName())) {
            ExternalSchemaBinding binding = bindings.get(table.schemaName());
            Table glueTable = GlueTableBuilder.toGlueTable(table);
            glue.createTable(binding.glueDatabase(), glueTable);
            if (session.inTransaction()) {
                stagedGlueTables.computeIfAbsent(backend.transactionScope(), ignored -> new CopyOnWriteArrayList<>())
                        .add(new StagedGlueTable(session.accountId(), binding.glueDatabase(), glueTable.getName()));
            }
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
        backend.execute("CREATE SCHEMA " + quote(schema.schemaName()));
        try {
            metadata.refresh(backend, session.accountId(), binding);
            bindWhenCommitted(backend, binding);
        } catch (RuntimeException exception) {
            try {
                backend.execute("DROP SCHEMA " + quote(schema.schemaName()) + " CASCADE");
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

    /** The transaction rolled back: drop everything staged for it, including the Glue tables it created. */
    public void finishCycle(BackendSql backend) {
        pendingBindings.remove(backend);
        undoStagedGlueTables(backend);
        materializer.finishCycle(backend);
    }

    public void finishCycle(BackendSql backend, boolean committed) {
        List<ExternalSchemaBinding> completed = pendingBindings.remove(backend);
        if (committed && completed != null) {
            completed.forEach(registry::bind);
        }
        pendingSavepointRollbacks.remove(backend.transactionScope());
        if (committed) {
            stagedGlueTables.remove(backend.transactionScope());
            savepoints.remove(backend.transactionScope());
        } else {
            undoStagedGlueTables(backend);
        }
        materializer.finishCycle(backend, committed);
    }

    /**
     * A rollback to a savepoint leaves the transaction open, so only the loads are dropped: they may have been
     * made after the savepoint, and their fingerprints would no longer match the backend tables.
     */
    public void discardPendingLoads(BackendSql backend) {
        materializer.finishCycle(backend);
        applySavepointRollback(backend);
    }

    /** The backend confirmed the statement: a ROLLBACK TO SAVEPOINT prepared before it now takes effect on Glue. */
    public void applySavepointRollback(BackendSql backend) {
        Object scope = backend.transactionScope();
        String name = pendingSavepointRollbacks.remove(scope);
        if (name != null) {
            rollbackToSavepoint(scope, name);
        }
    }

    private void trackSavepoints(String sql, BackendSql backend) {
        Matcher savepoint = SAVEPOINT.matcher(sql);
        if (savepoint.matches()) {
            Object scope = backend.transactionScope();
            List<StagedGlueTable> staged = stagedGlueTables.get(scope);
            savepoints.computeIfAbsent(scope, ignored -> new CopyOnWriteArrayList<>())
                    .add(new Savepoint(savepointName(savepoint.group(1)), staged == null ? 0 : staged.size()));
            return;
        }
        Matcher rollback = ROLLBACK_TO_SAVEPOINT.matcher(sql);
        if (rollback.matches()) {
            // The backend may still reject it, so Glue is only touched once it confirms the rollback.
            pendingSavepointRollbacks.put(backend.transactionScope(), savepointName(rollback.group(1)));
            return;
        }
        Matcher release = RELEASE_SAVEPOINT.matcher(sql);
        if (release.matches()) {
            List<Savepoint> marks = savepoints.get(backend.transactionScope());
            int index = marks == null ? -1 : lastIndexOf(marks, savepointName(release.group(1)));
            if (index >= 0) {
                marks.subList(index, marks.size()).clear();
            }
        }
    }

    /** ROLLBACK TO SAVEPOINT undoes the Glue tables created after it while the transaction stays open. */
    private void rollbackToSavepoint(Object scope, String name) {
        List<Savepoint> marks = savepoints.get(scope);
        int index = marks == null ? -1 : lastIndexOf(marks, name);
        if (index < 0) {
            return;
        }
        int keep = marks.get(index).stagedTables();
        marks.subList(index + 1, marks.size()).clear();
        List<StagedGlueTable> staged = stagedGlueTables.get(scope);
        if (staged == null || staged.size() <= keep) {
            return;
        }
        List<StagedGlueTable> undone = new ArrayList<>(staged.subList(keep, staged.size()));
        staged.subList(keep, staged.size()).clear();
        deleteGlueTables(undone);
    }

    private static int lastIndexOf(List<Savepoint> marks, String name) {
        for (int i = marks.size() - 1; i >= 0; i--) {
            if (marks.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String savepointName(String raw) {
        return raw.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private void undoStagedGlueTables(BackendSql backend) {
        Object scope = backend.transactionScope();
        savepoints.remove(scope);
        pendingSavepointRollbacks.remove(scope);
        List<StagedGlueTable> staged = stagedGlueTables.remove(scope);
        if (staged != null) {
            deleteGlueTables(staged);
        }
    }

    private void deleteGlueTables(List<StagedGlueTable> staged) {
        for (StagedGlueTable table : staged) {
            try {
                RequestScopes.runAs(table.accountId(), () -> glue.deleteTable(table.database(), table.table()));
            } catch (AwsException exception) {
                LOG.warnv(exception, "Could not undo Glue table {0}.{1} after a rollback", table.database(), table.table());
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
