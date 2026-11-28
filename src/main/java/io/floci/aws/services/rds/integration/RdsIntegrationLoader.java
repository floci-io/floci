package io.floci.aws.services.rds.integration;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.MappedColumn;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Source;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.SourceTable;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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

    // How long one table may take to read out of the source.
    static final int TABLE_READ_TIMEOUT_SECONDS = 3600;
    private static final int LOAD_ATTEMPTS = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final RdsIntegrationService integrations;
    private final RdsIntegrationSource sources;
    private final RedshiftZeroEtlWriter writer;
    private final boolean mock;
    private final Executor workers;
    private final int attempts;
    private final Duration retryDelay;
    /** Bumped by a reset, so a load started before it stops writing. */
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public RdsIntegrationLoader(RdsIntegrationService integrations, RdsIntegrationSource sources,
                                RedshiftZeroEtlWriter writer, EmulatorConfig config) {
        this(integrations, sources, writer, config.services().rds().mock(),
                task -> Thread.ofVirtual().name("rds-integration-load").start(task), LOAD_ATTEMPTS, RETRY_DELAY);
    }

    RdsIntegrationLoader(RdsIntegrationService integrations, RdsIntegrationSource sources,
                         RedshiftZeroEtlWriter writer, boolean mock, Executor workers, int attempts,
                         Duration retryDelay) {
        this.integrations = integrations;
        this.sources = sources;
        this.writer = writer;
        this.mock = mock;
        this.workers = workers;
        this.attempts = attempts;
        this.retryDelay = retryDelay;
    }

    /**
     * Starts loading {@code sourceDatabase} of the integration into {@code databaseName} in the background
     * under the load identity {@code loadId}.
     */
    public void submit(Integration integration, String sourceDatabase, String databaseName, String loadId) {
        long startedIn = generation.get();
        String accountId = integration.getAccountId();
        workers.execute(() -> RequestScopes.runAs(accountId, integration.getRegion(),
                () -> load(integration, sourceDatabase, databaseName, loadId, startedIn)));
    }

    /** Starts again the loads a restart interrupted, and reconciles creations left mid-flight. */
    public void resumeInterruptedLoads() {
        integrations.reconcileInterruptedCreations();
        for (Integration integration : integrations.integrationsWithLoadsInProgress()) {
            for (Integration.Destination destination : integration.getDestinations()) {
                if (Integration.Destination.STATE_SYNCING.equals(destination.getState())) {
                    LOG.infov("Restarting the initial load of {0} into {1} for integration {2}",
                            destination.getSourceDatabase(), destination.getDatabaseName(),
                            integration.getIntegrationArn());
                    submit(integration, destination.getSourceDatabase(), destination.getDatabaseName(),
                            destination.getLoadId());
                }
            }
        }
    }

    /**
     * Loads the destination, trying again a few times when the source or the target cannot be reached: a load
     * resumed at startup may start before the containers it reads and writes are back, and loading replaces each
     * table, so a retry leaves the same rows.
     */
    void load(Integration integration, String sourceDatabase, String databaseName, String loadId, long startedIn) {
        if (mock) {
            LOG.infov("RDS runs without database containers, so integration {0} copies no rows into {1}",
                    integration.getIntegrationArn(), databaseName);
            finish(integration, sourceDatabase, databaseName, loadId, startedIn, null);
            return;
        }
        String failure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Optional<List<String>> result = copyTables(integration, sourceDatabase, databaseName, loadId,
                        startedIn);
                if (result.isEmpty()) {
                    return;
                }
                List<String> failedTables = result.get();
                if (!failedTables.isEmpty()) {
                    failure = "Could not load " + failedTables.size() + " table(s): "
                            + String.join(", ", failedTables);
                } else {
                    failure = null;
                }
                break;
            } catch (RuntimeException e) {
                LOG.warnv(e, "Attempt {0} of {1} at the initial load of {2} into {3} for integration {4} failed",
                        attempt, attempts, sourceDatabase, databaseName, integration.getIntegrationArn());
                failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            if (attempt < attempts && !waitBeforeRetry(integration, sourceDatabase, databaseName, loadId, startedIn)) {
                return;
            }
        }
        finish(integration, sourceDatabase, databaseName, loadId, startedIn, failure);
    }

    /** Waits before the next attempt; false when the load should stop instead. */
    private boolean waitBeforeRetry(Integration integration, String sourceDatabase, String databaseName,
                                    String loadId, long startedIn) {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn);
    }

    private boolean isCurrentLoad(Integration integration, String sourceDatabase, String databaseName,
                                  String loadId, long startedIn) {
        return generation.get() == startedIn
                && integrations.isLoading(integration, sourceDatabase, databaseName, loadId);
    }

    private void finish(Integration integration, String sourceDatabase, String databaseName, String loadId,
                        long startedIn, String failure) {
        if (generation.get() == startedIn) {
            integrations.finishDestination(integration, sourceDatabase, databaseName, loadId, failure);
        }
    }

    /** Returns empty when the load was stopped or superseded while running. */
    private Optional<List<String>> copyTables(Integration integration, String sourceDatabase, String databaseName,
                                              String loadId, long startedIn) {
        Source source = sources.source(integration);
        IntegrationDataFilter filter = IntegrationDataFilter.parsePostgres(integration.getDataFilter());
        List<String> failedTables = new ArrayList<>();
        for (SourceTable table : sources.catalog(source, sourceDatabase)) {
            if (!isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn)) {
                LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(), sourceDatabase);
                return Optional.empty();
            }
            if (!filter.includes(sourceDatabase, table.schema(), table.table())) {
                continue;
            }
            Optional<List<MappedColumn>> columns = RdsIntegrationSource.mappedColumns(table);
            if (table.primaryKey().isEmpty()) {
                LOG.warnv("Integration {0} skips table {1}.{2}.{3}: it has no primary key",
                        integration.getIntegrationArn(), sourceDatabase, table.schema(), table.table());
            } else if (columns.isEmpty()) {
                LOG.warnv("Integration {0} skips table {1}.{2}.{3}: a column has a type zero-ETL does not "
                                + "replicate", integration.getIntegrationArn(), sourceDatabase, table.schema(),
                        table.table());
            } else {
                try {
                    OptionalLong rows = copyTable(integration, source, sourceDatabase, databaseName, loadId,
                            startedIn, table, columns.get());
                    if (rows.isEmpty()) {
                        LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(),
                                sourceDatabase);
                        return Optional.empty();
                    }
                    LOG.infov("Integration {0} loaded {1} rows of {2}.{3}.{4} into {5}",
                            integration.getIntegrationArn(), rows.getAsLong(), sourceDatabase, table.schema(),
                            table.table(), databaseName);
                } catch (RedshiftZeroEtlWriter.TargetUnavailableException | SourceUnavailableException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // Continue copying remaining tables, then report the destination as failed.
                    LOG.warnv(e, "Integration {0} could not load table {1}.{2}.{3}", integration.getIntegrationArn(),
                            sourceDatabase, table.schema(), table.table());
                    failedTables.add(table.schema() + "." + table.table());
                }
            }
        }
        return Optional.of(failedTables);
    }

    /** Returns empty when the load was stopped or superseded during the source export, before writing. */
    private OptionalLong copyTable(Integration integration, Source source, String sourceDatabase, String databaseName,
                                   String loadId, long startedIn, SourceTable table, List<MappedColumn> columns) {
        StringBuilder select = new StringBuilder("COPY (SELECT ");
        List<ReplicatedTable.Column> replicated = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            MappedColumn column = columns.get(i);
            select.append(i > 0 ? ", " : "").append(column.selectExpression());
            replicated.add(new ReplicatedTable.Column(column.name(), column.redshiftType()));
        }
        select.append(" FROM ").append(PostgresWire.quoteIdentifier(table.schema())).append('.')
                .append(PostgresWire.quoteIdentifier(table.table()))
                .append(") TO STDOUT WITH (FORMAT csv)");
        ReplicatedTable target = new ReplicatedTable(table.schema(), table.table(), replicated, table.primaryKey());
        Path staged = null;
        try {
            staged = Files.createTempFile("floci-rds-integration-", ".csv");
            try (OutputStream out = Files.newOutputStream(staged)) {
                try {
                    sources.query(source, sourceDatabase, select.toString(), out, TABLE_READ_TIMEOUT_SECONDS);
                } catch (RuntimeException e) {
                    throw new SourceUnavailableException("Could not read table " + table.schema() + "."
                            + table.table() + " from source: " + e.getMessage(), e);
                }
            }
            if (!isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn)) {
                return OptionalLong.empty();
            }
            try (InputStream in = Files.newInputStream(staged)) {
                return OptionalLong.of(writer.loadTable(integration.getAccountId(), integration.getTargetArn(),
                        databaseName, target, in));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not stage the rows of " + table.schema() + "." + table.table(), e);
        } finally {
            deleteStaged(staged);
        }
    }

    private static final class SourceUnavailableException extends RuntimeException {
        private SourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
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

    @Override
    public void clear() {
        generation.incrementAndGet();
    }
}
