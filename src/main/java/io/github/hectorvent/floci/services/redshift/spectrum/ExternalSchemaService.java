package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftRoleAccess;
import io.github.hectorvent.floci.services.redshift.proxy.S3CopySimulator;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExternalSchemaService {
    private final ExternalCatalogRegistry registry;
    private final SpectrumCatalogResolver catalogResolver;
    private final ExternalTableMaterializer materializer;
    private final ExternalMetadataWriter metadata;
    private final GlueService glueService;
    private final IamService iamService;
    public ExternalSchemaService(ExternalCatalogRegistry registry, SpectrumCatalogResolver catalogResolver,
                                 ExternalTableMaterializer materializer, ExternalMetadataWriter metadata,
                                 GlueService glueService, IamService iamService) {
        this.registry = registry;
        this.catalogResolver = catalogResolver;
        this.materializer = materializer;
        this.metadata = metadata;
        this.glueService = glueService;
        this.iamService = iamService;
    }
    public List<ExternalReferenceScanner.Reference> referencesIn(String sql, SpectrumSession session) { return List.copyOf(ExternalReferenceScanner.scan(sql, schemaNames(session))); }
    public void rejectExternalWrites(String sql, SpectrumSession session) { if (ExternalReferenceScanner.writeTarget(sql, schemaNames(session)).isPresent()) throw new SpectrumSqlException("0A000", "cannot modify external table"); }
    public void loadReferences(List<ExternalReferenceScanner.Reference> refs, SpectrumSession session, BackendSql backend) {
        for (ExternalReferenceScanner.Reference ref : refs) {
            Optional<SpectrumCatalogResolver.Resolution> resolution = resolveCatalog(ref, session);
            if (resolution.isEmpty()) {
                throw new SpectrumSqlException("3F000", "schema \"" + ref.schema() + "\" does not exist");
            }
            switch (resolution.get()) {
                case SpectrumCatalogResolver.Resolution.Glue glue -> {
                    ExternalTableMaterializer.Outcome outcome = materializer.ensureCurrent(backend, session, glue.binding(), ref.table());
                    if (outcome == ExternalTableMaterializer.Outcome.NOT_EXTERNAL) {
                        throw new SpectrumSqlException("42P01", "table \"" + ref.schema() + "." + ref.table() + "\" does not exist in the Glue Data Catalog");
                    }
                }
                case SpectrumCatalogResolver.Resolution.PhaseOne ignored -> throw new SpectrumSqlException(
                        "0A000", "legacy Spectrum tables require a supported single-table SELECT");
            }
        }
    }
    public Optional<BoundGlueTable> resolveGlueTable(ExternalReferenceScanner.Reference ref, SpectrumSession session) {
        Optional<SpectrumCatalogResolver.Resolution> resolution = resolveCatalog(ref, session);
        if (resolution.isEmpty() || !(resolution.get() instanceof SpectrumCatalogResolver.Resolution.Glue glue)) {
            return Optional.empty();
        }
        Table table = findGlueTable(session, glue.binding(), ref.table());
        return Optional.of(new BoundGlueTable(glue.binding(), table));
    }
    public Optional<SpectrumCatalogResolver.Resolution> resolveCatalog(ExternalReferenceScanner.Reference ref, SpectrumSession session) {
        return catalogResolver.resolve(session.accountId(), session.clusterKey(), session.databaseName(), ref.schema());
    }
    public Optional<SpectrumExternalTable> legacyTable(ExternalReferenceScanner.Reference ref, SpectrumSession session) {
        return catalogResolver.legacyTable(session.accountId(), session.databaseName(), ref.schema(), ref.table());
    }
    public boolean referencesLegacy(List<ExternalReferenceScanner.Reference> refs, SpectrumSession session) {
        return refs.stream().anyMatch(ref -> resolveCatalog(ref, session)
                .filter(SpectrumCatalogResolver.Resolution.PhaseOne.class::isInstance).isPresent());
    }
    public boolean touchesCatalogViews(String sql) {
        return ExternalReferenceScanner.containsIdentifierPrefix(sql, "svv_external_");
    }
    public void refreshMetadata(SpectrumSession session, BackendSql backend) { for (ExternalSchemaBinding binding : registry.list(session.accountId(),session.clusterKey(),session.databaseName())) metadata.refresh(backend,session.accountId(),binding); }
    public void forgetCluster(String accountId, String clusterKey) { registry.removeCluster(accountId, clusterKey); materializer.forgetCluster(clusterKey); }
    private Set<String> schemaNames(SpectrumSession session) {
        Set<String> names = registry.list(session.accountId(), session.clusterKey(), session.databaseName()).stream()
                .map(ExternalSchemaBinding::schemaName).collect(Collectors.toSet());
        names.addAll(catalogResolver.legacySchemaNames(session.accountId()));
        return names;
    }
    public Optional<String> execute(ExternalStatement statement, SpectrumSession session, BackendSql backend) {
        return switch (statement) {
            case ExternalStatement.CreateSchema create -> Optional.of(createSchema(create, session, backend));
            case ExternalStatement.CreateTable create -> Optional.of(createTable(create, session, backend));
            case ExternalStatement.AddPartitions add -> Optional.of(addPartitions(add, session, backend));
            case ExternalStatement.DropSchema drop -> dropSchema(drop, session, backend);
            case ExternalStatement.DropTable drop -> dropTable(drop, session, backend);
        };
    }

    private String createSchema(ExternalStatement.CreateSchema statement, SpectrumSession session, BackendSql backend) {
        if (registry.find(session.accountId(), session.clusterKey(), session.databaseName(), statement.schemaName()).isPresent()) {
            throw new SpectrumSqlException("42P06", "schema \"" + statement.schemaName() + "\" already exists");
        }
        try {
            RedshiftRoleAccess.RoleSession role = RedshiftRoleAccess.resolveRoleSession(statement.iamRoleArn(), iamService, session.accountId(), session.iamRoleArns());
            RedshiftRoleAccess.releaseRoleSession(role, statement.iamRoleArn(), iamService);
        } catch (S3CopySimulator.S3TransferException exception) {
            throw new SpectrumSqlException(exception.sqlState(), exception.getMessage());
        }
        RequestScopes.runAs(session.accountId(), () -> {
            try { glueService.getDatabase(statement.glueDatabase()); }
            catch (AwsException exception) {
                if (!"EntityNotFoundException".equals(exception.getErrorCode())) throw exception;
                if (!statement.createDatabaseIfNotExists()) throw new SpectrumSqlException("XX000", "Glue database \"" + statement.glueDatabase() + "\" not found");
                glueService.createDatabase(new Database(statement.glueDatabase()));
            }
        });
        backend.execute("CREATE SCHEMA " + quote(statement.schemaName()));
        // A new schema starts with no loaded tables, whatever a schema of the same name left behind.
        materializer.forgetSchema(session.clusterKey(), session.databaseName(), statement.schemaName());
        ExternalSchemaBinding binding = new ExternalSchemaBinding(session.accountId(), session.clusterKey(), session.databaseName(), statement.schemaName(), statement.glueDatabase(), statement.iamRoleArn());
        registry.bind(binding);
        metadata.refresh(backend, session.accountId(), binding);
        return "CREATE SCHEMA";
    }

    private String createTable(ExternalStatement.CreateTable statement, SpectrumSession session, BackendSql backend) {
        ExternalSchemaBinding binding = requireBinding(statement.schemaName(), session);
        Table table = GlueTableBuilder.toGlueTable(statement);
        try { RequestScopes.runAs(session.accountId(), () -> glueService.createTable(binding.glueDatabase(), table)); }
        catch (AwsException exception) {
            if ("AlreadyExistsException".equals(exception.getErrorCode())) throw new SpectrumSqlException("42P07", "relation \"" + statement.tableName() + "\" already exists");
            throw exception;
        }
        metadata.refresh(backend, session.accountId(), binding);
        return "CREATE TABLE";
    }

    private String addPartitions(ExternalStatement.AddPartitions statement, SpectrumSession session, BackendSql backend) {
        ExternalSchemaBinding binding = requireBinding(statement.schemaName(), session);
        Table table = findGlueTable(session, binding, statement.tableName());
        for (ExternalStatement.PartitionSpec spec : statement.partitions()) {
            Partition partition = GlueTableBuilder.toGluePartition(table, spec);
            try { RequestScopes.runAs(session.accountId(), () -> glueService.createPartition(binding.glueDatabase(), table.getName(), partition)); }
            catch (AwsException exception) {
                boolean duplicate = "AlreadyExistsException".equals(exception.getErrorCode());
                if (!duplicate || !statement.ifNotExists()) throw duplicate ? new SpectrumSqlException("42710", "partition already exists") : exception;
            }
        }
        metadata.refresh(backend, session.accountId(), binding);
        return "ALTER TABLE";
    }

    private Optional<String> dropSchema(ExternalStatement.DropSchema statement, SpectrumSession session, BackendSql backend) {
        Optional<ExternalSchemaBinding> binding = registry.find(session.accountId(), session.clusterKey(), session.databaseName(), statement.schemaName());
        if (binding.isEmpty()) return Optional.empty();
        String schema = quote(statement.schemaName());
        if (statement.cascade()) {
            backend.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } else {
            // RESTRICT is the default: drop only the tables Floci loaded for this schema, each without
            // CASCADE so a user's dependent view still blocks the drop, then the schema itself without
            // CASCADE. One message is one implicit transaction, so a refusal leaves everything in place.
            StringBuilder sql = new StringBuilder();
            for (String table : managedTables(binding.get(), session)) {
                sql.append("DROP TABLE IF EXISTS ").append(schema).append('.').append(quote(table)).append("; ");
            }
            backend.execute(sql.append("DROP SCHEMA IF EXISTS ").append(schema).toString());
        }
        registry.unbind(session.accountId(), session.clusterKey(), session.databaseName(), statement.schemaName());
        materializer.forgetSchema(session.clusterKey(), session.databaseName(), statement.schemaName());
        metadata.purge(backend, statement.schemaName());
        return Optional.of("DROP SCHEMA");
    }

    /** Tables Floci itself put in the external schema: the catalog's tables plus any it loaded and still tracks. */
    private Set<String> managedTables(ExternalSchemaBinding binding, SpectrumSession session) {
        Set<String> names = new TreeSet<>(materializer.loadedTables(
                session.clusterKey(), session.databaseName(), binding.schemaName()));
        try {
            List<Table> tables = RequestScopes.callAs(session.accountId(), () -> glueService.getTables(binding.glueDatabase()));
            for (Table table : tables) {
                names.add(table.getName());
            }
        } catch (AwsException exception) {
            if (!"EntityNotFoundException".equals(exception.getErrorCode())) {
                throw exception;
            }
            // the Glue database is already gone, so only the tables this schema loaded remain to drop
        }
        return names;
    }

    /**
     * Whether {@code statement} is really for an external schema. A DROP or ALTER only looks like one
     * syntactically; when its target is not a bound schema it is an ordinary PostgreSQL statement.
     */
    public boolean handles(ExternalStatement statement, SpectrumSession session) {
        return switch (statement) {
            case ExternalStatement.CreateSchema ignored -> true;
            case ExternalStatement.CreateTable ignored -> true;
            case ExternalStatement.AddPartitions add -> isBound(add.schemaName(), session);
            case ExternalStatement.DropSchema drop -> isBound(drop.schemaName(), session);
            case ExternalStatement.DropTable drop -> isBound(drop.schemaName(), session);
        };
    }

    private boolean isBound(String schemaName, SpectrumSession session) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(), schemaName).isPresent();
    }

    private Optional<String> dropTable(ExternalStatement.DropTable statement, SpectrumSession session, BackendSql backend) {
        Optional<ExternalSchemaBinding> binding = registry.find(session.accountId(), session.clusterKey(), session.databaseName(), statement.schemaName());
        if (binding.isEmpty()) return Optional.empty();
        try { RequestScopes.runAs(session.accountId(), () -> glueService.deleteTable(binding.get().glueDatabase(), statement.tableName())); }
        catch (AwsException exception) {
            if (!"EntityNotFoundException".equals(exception.getErrorCode())) throw exception;
            if (!statement.ifExists()) throw new SpectrumSqlException("42P01", "table \"" + statement.tableName() + "\" does not exist");
        }
        materializer.forget(session.clusterKey(), session.databaseName(), statement.schemaName(), statement.tableName());
        backend.execute("DROP TABLE IF EXISTS " + quote(statement.schemaName()) + "." + quote(statement.tableName())
                + (statement.cascade() ? " CASCADE" : ""));
        metadata.refresh(backend, session.accountId(), binding.get());
        return Optional.of("DROP TABLE");
    }

    private ExternalSchemaBinding requireBinding(String schemaName, SpectrumSession session) {
        return registry.find(session.accountId(), session.clusterKey(), session.databaseName(), schemaName)
                .orElseThrow(() -> new SpectrumSqlException("3F000", "schema \"" + schemaName + "\" does not exist"));
    }

    private Table findGlueTable(SpectrumSession session, ExternalSchemaBinding binding, String tableName) {
        try { return RequestScopes.callAs(session.accountId(), () -> glueService.getTable(binding.glueDatabase(), tableName)); }
        catch (AwsException exception) {
            if ("EntityNotFoundException".equals(exception.getErrorCode())) throw new SpectrumSqlException("42P01", "table \"" + tableName + "\" does not exist");
            throw exception;
        }
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    public record BoundGlueTable(ExternalSchemaBinding binding, Table table) { }
}
