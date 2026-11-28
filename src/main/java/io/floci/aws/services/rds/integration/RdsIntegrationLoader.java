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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>When the source runs with logical replication, a publication of the tables and a replication slot are created
 * before the first table is read, and {@link RdsIntegrationReplicator} applies the changes the slot holds once the
 * load ends. The changes made while a table was being copied are so applied again over it, which leaves the rows the
 * source has, since each change replaces or deletes a whole row by its primary key.
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
    private final RdsIntegrationReplicator replicator;
    private final RedshiftZeroEtlWriter writer;
    private final boolean mock;
    private final Executor workers;
    private final int attempts;
    private final Duration retryDelay;
    /** Bumped by a reset, so a load started before it stops writing. */
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public RdsIntegrationLoader(RdsIntegrationService integrations, RdsIntegrationSource sources,
                                RdsIntegrationReplicator replicator, RedshiftZeroEtlWriter writer,
                                EmulatorConfig config) {
        this(integrations, sources, replicator, writer, config.services().rds().mock(),
                task -> Thread.ofVirtual().name("rds-integration-load").start(task), LOAD_ATTEMPTS, RETRY_DELAY);
    }

    RdsIntegrationLoader(RdsIntegrationService integrations, RdsIntegrationSource sources,
                         RdsIntegrationReplicator replicator, RedshiftZeroEtlWriter writer, boolean mock,
                         Executor workers, int attempts, Duration retryDelay) {
        this.integrations = integrations;
        this.sources = sources;
        this.replicator = replicator;
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
            finish(integration, sourceDatabase, databaseName, loadId, startedIn, null, false);
            return;
        }
        String failure = null;
        boolean replicating = false;
        AtomicBoolean replicationCreated = new AtomicBoolean();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Copy copy = copyTables(integration, sourceDatabase, databaseName, loadId, startedIn,
                        replicationCreated);
                replicating = copy.replicating();
                if (copy.stopped()) {
                    if (replicationCreated.get() && !integrations.exists(integration)) {
                        dropReplication(integration, sourceDatabase);
                    }
                    return;
                }
                List<String> failedTables = copy.failedTables();
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
                if (replicationCreated.get() && !integrations.exists(integration)) {
                    dropReplication(integration, sourceDatabase);
                }
                return;
            }
        }
        finish(integration, sourceDatabase, databaseName, loadId, startedIn, failure,
                failure == null ? replicating : replicationCreated.get());
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

    /**
     * Ends the load. A loaded destination whose source replicates starts replicating; when the load failed, or the
     * integration was deleted meanwhile, the slot and the publication it set up are dropped from the source before
     * marking the destination failed so a retry cannot have its new slot dropped by this worker.
     */
    private void finish(Integration integration, String sourceDatabase, String databaseName, String loadId,
                        long startedIn, String failure, boolean replicating) {
        if (generation.get() != startedIn) {
            return;
        }
        if (failure != null && replicating
                && (isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn)
                        || !integrations.exists(integration))) {
            dropReplication(integration, sourceDatabase);
        }
        integrations.finishDestination(integration, sourceDatabase, databaseName, loadId, failure);
        if (!replicating || failure != null) {
            return;
        }
        if (integrations.isReplicating(integration, sourceDatabase, databaseName, loadId)) {
            replicator.start(integration, sourceDatabase, databaseName, loadId);
        } else if (!integrations.exists(integration)) {
            dropReplication(integration, sourceDatabase);
        }
    }

    private void dropReplication(Integration integration, String sourceDatabase) {
        String name = RdsIntegrationSource.replicationName(integration, sourceDatabase);
        try {
            sources.dropReplication(sources.source(integration), sourceDatabase, name);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Integration {0} could not drop replication slot {1} from the source",
                    integration.getIntegrationArn(), name);
        }
    }

    /**
     * How a copy ended: the tables that could not be loaded, whether it stopped because the load was stopped or
     * superseded, and whether the source's changes are replicated.
     */
    private record Copy(List<String> failedTables, boolean stopped, boolean replicating) {}

    private Copy copyTables(Integration integration, String sourceDatabase, String databaseName, String loadId,
                            long startedIn, AtomicBoolean replicationCreated) {
        Source source = sources.source(integration);
        IntegrationDataFilter filter = IntegrationDataFilter.parsePostgres(integration.getDataFilter());
        Map<SourceTable, List<MappedColumn>> included = new LinkedHashMap<>();
        for (SourceTable table : sources.catalog(source, sourceDatabase)) {
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
                included.put(table, columns.get());
            }
        }
        if (!isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn)) {
            LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(), sourceDatabase);
            return new Copy(List.of(), true, false);
        }
        boolean replicating = startReplication(integration, source, sourceDatabase, included.keySet());
        if (replicating) {
            replicationCreated.set(true);
        }
        List<String> failedTables = new ArrayList<>();
        for (Map.Entry<SourceTable, List<MappedColumn>> entry : included.entrySet()) {
            SourceTable table = entry.getKey();
            if (!isCurrentLoad(integration, sourceDatabase, databaseName, loadId, startedIn)) {
                LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(), sourceDatabase);
                return new Copy(List.of(), true, replicating);
            }
            try {
                OptionalLong rows = copyTable(integration, source, sourceDatabase, databaseName, loadId,
                        startedIn, table, entry.getValue());
                if (rows.isEmpty()) {
                    LOG.infov("Integration {0} stopped loading {1}", integration.getIntegrationArn(),
                            sourceDatabase);
                    return new Copy(List.of(), true, replicating);
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
        return new Copy(failedTables, false, replicating);
    }

    /**
     * Sets up the replication of the tables before they are copied, when the source runs with logical replication.
     *
     * @return whether the source's changes are replicated
     */
    private boolean startReplication(Integration integration, Source source, String sourceDatabase,
                                     Collection<SourceTable> tables) {
        try {
            if (!sources.logicalReplicationEnabled(source, sourceDatabase)) {
                LOG.warnv("Integration {0} copies {1} once and does not replicate its changes: the source runs"
                                + " without logical replication. Set rds.logical_replication to 1 in its parameter"
                                + " group and reboot it.", integration.getIntegrationArn(), sourceDatabase);
                return false;
            }
            sources.startReplication(source, sourceDatabase,
                    RdsIntegrationSource.replicationName(integration, sourceDatabase), new ArrayList<>(tables));
            return true;
        } catch (RuntimeException e) {
            throw new SourceUnavailableException("Could not set up replication from source database "
                    + sourceDatabase + ": " + e.getMessage(), e);
        }
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
            RdsIntegrationSource.deleteStaged(staged);
        }
    }

    private static final class SourceUnavailableException extends RuntimeException {
        private SourceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
    }
}
