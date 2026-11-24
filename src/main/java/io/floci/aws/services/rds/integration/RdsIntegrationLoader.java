package io.floci.aws.services.rds.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.rds.RdsService;
import io.floci.aws.services.rds.container.RdsContainerManager;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.MappedColumn;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.SourceColumn;
import io.floci.aws.services.rds.model.DbCluster;
import io.floci.aws.services.rds.model.DbInstance;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ReplicatedTable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The initial load of an RDS zero-ETL integration: copies the tables of one source database into the
 * Redshift database created from the integration with {@code CREATE DATABASE ... FROM INTEGRATION}.
 *
 * <p>Each table the integration's {@code DataFilter} includes and that has a primary key is read out
 * of the source's PostgreSQL container with {@code COPY ... TO STDOUT}, staged in a temporary file and
 * copied into a table of the same schema and name on the target, with the column types AWS gives a
 * PostgreSQL source. A table with a column type AWS does not replicate is skipped, as AWS leaves such a
 * table out. Loading a table replaces it, so a load a restart interrupted runs again from the start.
 * Changes made to the source after the load are not replicated.
 */
@ApplicationScoped
public class RdsIntegrationLoader implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsIntegrationLoader.class);

    // Ordinary and partitioned tables, but not a partition on its own, since reading the parent reads
    // its partitions. Temporary and unlogged tables are skipped, as for a snapshot export. "key" is the
    // column's position in the primary key, 0 when it is not part of one.
    static final String CATALOG_SQL = "SELECT json_build_object('schema', n.nspname, 'table', c.relname, "
            + "'column', a.attname, 'type', t.typname, 'typtype', t.typtype, 'category', t.typcategory, "
            + "'builtin', t.typnamespace = 'pg_catalog'::regnamespace, "
            + "'element', e.typname, 'elementtype', e.typtype, "
            + "'elementbuiltin', COALESCE(e.typnamespace = 'pg_catalog'::regnamespace, false), "
            + "'formatted', format_type(a.atttypid, a.atttypmod), "
            + "'key', COALESCE(array_position(i.indkey::int2[], a.attnum), 0)) "
            + "FROM pg_class c "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
            + "JOIN pg_type t ON t.oid = a.atttypid "
            + "LEFT JOIN pg_type e ON e.oid = t.typelem AND t.typcategory = 'A' "
            + "LEFT JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary "
            + "WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relpersistence = 'p' "
            + "AND n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg\\_%' "
            + "ORDER BY n.nspname, c.relname, a.attnum";

    // How long one table may take to read out of the source.
    static final int TABLE_READ_TIMEOUT_SECONDS = 3600;

    private final RdsIntegrationService integrations;
    private final RdsService rdsService;
    private final RdsContainerManager containerManager;
    private final RedshiftZeroEtlWriter writer;
    private final ObjectMapper objectMapper;
    private final boolean mock;
    private final Executor workers;
    /** Bumped by a reset, so a load started before it stops writing. */
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public RdsIntegrationLoader(RdsIntegrationService integrations, RdsService rdsService,
                                RdsContainerManager containerManager, RedshiftZeroEtlWriter writer,
                                ObjectMapper objectMapper, EmulatorConfig config) {
        this(integrations, rdsService, containerManager, writer, objectMapper, config.services().rds().mock(),
                task -> Thread.ofVirtual().name("rds-integration-load").start(task));
    }

    RdsIntegrationLoader(RdsIntegrationService integrations, RdsService rdsService,
                         RdsContainerManager containerManager, RedshiftZeroEtlWriter writer,
                         ObjectMapper objectMapper, boolean mock, Executor workers) {
        this.integrations = integrations;
        this.rdsService = rdsService;
        this.containerManager = containerManager;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.mock = mock;
        this.workers = workers;
    }

    /** Starts loading {@code sourceDatabase} of the integration into {@code databaseName} in the background. */
    public void submit(Integration integration, String sourceDatabase, String databaseName) {
        long startedIn = generation.get();
        String accountId = integration.getAccountId();
        workers.execute(() -> RequestScopes.runAs(accountId, integration.getRegion(),
                () -> load(integration, sourceDatabase, databaseName, startedIn)));
    }

    /** Starts again the loads a restart interrupted. */
    public void resumeInterruptedLoads() {
        for (Integration integration : integrations.integrationsWithLoadsInProgress()) {
            for (Integration.Destination destination : integration.getDestinations()) {
                if (Integration.Destination.STATE_SYNCING.equals(destination.getState())) {
                    LOG.infov("Restarting the initial load of {0} into {1} for integration {2}",
                            destination.getSourceDatabase(), destination.getDatabaseName(),
                            integration.getIntegrationArn());
                    submit(integration, destination.getSourceDatabase(), destination.getDatabaseName());
                }
            }
        }
    }

    void load(Integration integration, String sourceDatabase, String databaseName, long startedIn) {
        String failure = null;
        try {
            if (mock) {
                LOG.infov("RDS runs without database containers, so integration {0} copies no rows into {1}",
                        integration.getIntegrationArn(), databaseName);
            } else {
                copyTables(integration, sourceDatabase, databaseName, startedIn);
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "The initial load of {0} into {1} for integration {2} failed", sourceDatabase,
                    databaseName, integration.getIntegrationArn());
            failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
        if (generation.get() == startedIn) {
            integrations.finishDestination(integration, sourceDatabase, databaseName, failure);
        }
    }

    private void copyTables(Integration integration, String sourceDatabase, String databaseName, long startedIn) {
        Source source = source(integration);
        IntegrationDataFilter filter = IntegrationDataFilter.parsePostgres(integration.getDataFilter());
        for (SourceTable table : catalog(source, sourceDatabase)) {
            if (generation.get() != startedIn || !integrations.isLoading(integration, sourceDatabase, databaseName)) {
                LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(), sourceDatabase);
                return;
            }
            if (!filter.includes(sourceDatabase, table.schema(), table.table())) {
                continue;
            }
            Optional<List<MappedColumn>> columns = mappedColumns(table);
            if (table.primaryKey().isEmpty()) {
                LOG.warnv("Integration {0} skips table {1}.{2}.{3}: it has no primary key",
                        integration.getIntegrationArn(), sourceDatabase, table.schema(), table.table());
            } else if (columns.isEmpty()) {
                LOG.warnv("Integration {0} skips table {1}.{2}.{3}: a column has a type zero-ETL does not "
                                + "replicate", integration.getIntegrationArn(), sourceDatabase, table.schema(),
                        table.table());
            } else {
                long rows = copyTable(integration, source, sourceDatabase, databaseName, table, columns.get());
                LOG.infov("Integration {0} loaded {1} rows of {2}.{3}.{4} into {5}", integration.getIntegrationArn(),
                        rows, sourceDatabase, table.schema(), table.table(), databaseName);
            }
        }
    }

    private long copyTable(Integration integration, Source source, String sourceDatabase, String databaseName,
                           SourceTable table, List<MappedColumn> columns) {
        StringBuilder select = new StringBuilder("COPY (SELECT ");
        List<ReplicatedTable.Column> replicated = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            MappedColumn column = columns.get(i);
            select.append(i > 0 ? ", " : "").append(column.selectExpression());
            replicated.add(new ReplicatedTable.Column(column.name(), column.redshiftType()));
        }
        select.append(" FROM ").append(PostgresRedshiftTypes.quoteIdentifier(table.schema())).append('.')
                .append(PostgresRedshiftTypes.quoteIdentifier(table.table()))
                .append(") TO STDOUT WITH (FORMAT csv)");
        ReplicatedTable target = new ReplicatedTable(table.schema(), table.table(), replicated, table.primaryKey());
        Path staged = null;
        try {
            staged = Files.createTempFile("floci-rds-integration-", ".csv");
            try (OutputStream out = Files.newOutputStream(staged)) {
                containerManager.queryPostgres(source.containerId(), source.masterUsername(), sourceDatabase,
                        select.toString(), out, TABLE_READ_TIMEOUT_SECONDS);
            }
            try (InputStream in = Files.newInputStream(staged)) {
                return writer.loadTable(integration.getAccountId(), integration.getTargetArn(), databaseName,
                        target, in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage the rows of " + table.schema() + "." + table.table(), e);
        } finally {
            deleteStaged(staged);
        }
    }

    private static void deleteStaged(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            LOG.warnv(e, "Could not delete the staged rows in {0}", staged);
        }
    }

    private static Optional<List<MappedColumn>> mappedColumns(SourceTable table) {
        List<MappedColumn> mapped = new ArrayList<>();
        for (SourceColumn column : table.columns()) {
            Optional<MappedColumn> one = PostgresRedshiftTypes.map(column);
            if (one.isEmpty()) {
                return Optional.empty();
            }
            mapped.add(one.get());
        }
        return Optional.of(mapped);
    }

    /** The source's tables, in schema and table order, each with its columns and its primary key in key order. */
    List<SourceTable> catalog(Source source, String sourceDatabase) {
        String output = containerManager.queryPostgres(source.containerId(), source.masterUsername(),
                sourceDatabase, CATALOG_SQL);
        Map<String, SourceTable> tables = new LinkedHashMap<>();
        Map<String, TreeMap<Integer, String>> keys = new LinkedHashMap<>();
        for (String line : output.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = parse(line);
            String schema = row.path("schema").asText();
            String name = row.path("table").asText();
            String tableKey = schema + "\u0000" + name;
            SourceTable table = tables.computeIfAbsent(tableKey,
                    ignored -> new SourceTable(schema, name, new ArrayList<>(), new ArrayList<>()));
            String column = row.path("column").asText();
            table.columns().add(new SourceColumn(column, row.path("type").asText(), row.path("typtype").asText(),
                    row.path("category").asText(), row.path("builtin").asBoolean(),
                    row.path("element").isNull() ? null : row.path("element").asText(null),
                    row.path("elementtype").asText(null), row.path("elementbuiltin").asBoolean(),
                    row.path("formatted").asText()));
            int keyPosition = row.path("key").asInt();
            if (keyPosition > 0) {
                keys.computeIfAbsent(tableKey, ignored -> new TreeMap<>()).put(keyPosition, column);
            }
        }
        keys.forEach((tableKey, positions) -> tables.get(tableKey).primaryKey().addAll(positions.values()));
        return new ArrayList<>(tables.values());
    }

    private JsonNode parse(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the source's catalog: " + e.getOriginalMessage(), e);
        }
    }

    /** The source's running database, started first when it was recorded without one. */
    private Source source(Integration integration) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(integration.getSourceArn());
        String resource = arn.resource();
        String identifier = resource.substring(resource.indexOf(':') + 1);
        if (resource.startsWith("cluster:")) {
            DbCluster cluster = rdsService.ensureClusterBackend(identifier, arn.region());
            return requireRunning(cluster.getContainerId(), cluster.getMasterUsername(), integration);
        }
        DbInstance instance = rdsService.ensureInstanceBackend(identifier, arn.region());
        return requireRunning(instance.getContainerId(), instance.getMasterUsername(), integration);
    }

    private static Source requireRunning(String containerId, String masterUsername, Integration integration) {
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalStateException("The source " + integration.getSourceArn()
                    + " has no running database to copy from.");
        }
        return new Source(containerId, masterUsername);
    }

    record Source(String containerId, String masterUsername) {}

    record SourceTable(String schema, String table, List<SourceColumn> columns, List<String> primaryKey) {}

    @Override
    public void clear() {
        generation.incrementAndGet();
    }
}
