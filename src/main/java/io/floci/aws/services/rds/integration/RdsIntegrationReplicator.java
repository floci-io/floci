package io.floci.aws.services.rds.integration;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.rds.integration.PgOutputMessages.Commit;
import io.floci.aws.services.rds.integration.PgOutputMessages.Delete;
import io.floci.aws.services.rds.integration.PgOutputMessages.Insert;
import io.floci.aws.services.rds.integration.PgOutputMessages.Message;
import io.floci.aws.services.rds.integration.PgOutputMessages.Relation;
import io.floci.aws.services.rds.integration.PgOutputMessages.Truncate;
import io.floci.aws.services.rds.integration.PgOutputMessages.Tuple;
import io.floci.aws.services.rds.integration.PgOutputMessages.Update;
import io.floci.aws.services.rds.integration.PgOutputMessages.Value;
import io.floci.aws.services.rds.integration.PostgresRedshiftTypes.MappedColumn;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Change;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.Source;
import io.floci.aws.services.rds.integration.RdsIntegrationSource.SourceTable;
import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ColumnValue;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ReplicatedTable;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.RowChange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replicates the changes made to the source of an RDS zero-ETL integration after its initial load: the inserts,
 * updates, deletes and truncates of the tables the load copied reach the destination database on the target.
 *
 * <p>Each loaded destination is followed by one background loop that reads its replication slot, applies the
 * changes of whole transactions to the target in one transaction, and only then advances the slot. The slot keeps
 * its position on the source, so a restart resumes from the first change not yet applied, and changes applied
 * again after a failure leave the same rows. Deleting the integration stops the loop, which drops the slot and the
 * publication from the source.
 */
@ApplicationScoped
public class RdsIntegrationReplicator implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsIntegrationReplicator.class);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    /** About how many changes one batch reads; a batch always ends on a transaction's commit. */
    static final int BATCH_CHANGES = 1000;

    private final RdsIntegrationService integrations;
    private final RdsIntegrationSource sources;
    private final RedshiftZeroEtlWriter writer;
    private final boolean mock;
    private final Executor workers;
    private final Duration pollInterval;
    private final Duration retryDelay;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Bumped by a reset, so a loop started before it stops. */
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public RdsIntegrationReplicator(RdsIntegrationService integrations, RdsIntegrationSource sources,
                                    RedshiftZeroEtlWriter writer, EmulatorConfig config) {
        this(integrations, sources, writer, config.services().rds().mock(),
                task -> Thread.ofVirtual().name("rds-integration-replication").start(task), POLL_INTERVAL,
                RETRY_DELAY);
    }

    RdsIntegrationReplicator(RdsIntegrationService integrations, RdsIntegrationSource sources,
                             RedshiftZeroEtlWriter writer, boolean mock, Executor workers, Duration pollInterval,
                             Duration retryDelay) {
        this.integrations = integrations;
        this.sources = sources;
        this.writer = writer;
        this.mock = mock;
        this.workers = workers;
        this.pollInterval = pollInterval;
        this.retryDelay = retryDelay;
    }

    /** What one poll of a destination's slot did. */
    enum Poll { APPLIED, IDLE, RETRY, STOPPED }

    /** One destination being replicated, and what it learnt of its source. */
    static final class Replication {
        final Integration integration;
        final String sourceDatabase;
        final String databaseName;
        final String name;
        /** The tables replicated, by schema and name; read from the source's catalog when the loop starts. */
        Map<String, ReplicatedSourceTable> tables;

        Replication(Integration integration, String sourceDatabase, String databaseName) {
            this.integration = integration;
            this.sourceDatabase = sourceDatabase;
            this.databaseName = databaseName;
            this.name = RdsIntegrationSource.replicationName(integration, sourceDatabase);
        }
    }

    /** A replicated table: the target table and how each of its columns' values is converted, by column name. */
    record ReplicatedSourceTable(ReplicatedTable target, Map<String, MappedColumn> columns) {}

    /** Starts replicating {@code sourceDatabase} of the integration into {@code databaseName} in the background. */
    public void start(Integration integration, String sourceDatabase, String databaseName) {
        if (mock) {
            return;
        }
        String key = integration.getIntegrationArn() + "\u0000" + sourceDatabase + "\u0000" + databaseName;
        if (!running.add(key)) {
            return;
        }
        long startedIn = generation.get();
        Replication replication = new Replication(integration, sourceDatabase, databaseName);
        workers.execute(() -> {
            try {
                RequestScopes.runAs(integration.getAccountId(), integration.getRegion(),
                        () -> run(replication, startedIn));
            } finally {
                running.remove(key);
            }
        });
    }

    /** Starts again the replication of each loaded destination, which a restart stopped. */
    public void resumeReplication() {
        for (Integration integration : integrations.integrationsWithLoadedDestinations()) {
            for (Integration.Destination destination : integration.getDestinations()) {
                if (Integration.Destination.STATE_SYNCED.equals(destination.getState())) {
                    start(integration, destination.getSourceDatabase(), destination.getDatabaseName());
                }
            }
        }
    }

    private void run(Replication replication, long startedIn) {
        LOG.infov("Integration {0} replicates {1} into {2}", replication.integration.getIntegrationArn(),
                replication.sourceDatabase, replication.databaseName);
        while (generation.get() == startedIn) {
            Poll result = poll(replication);
            Duration wait = switch (result) {
                case APPLIED -> Duration.ZERO;
                case IDLE -> pollInterval;
                case RETRY -> retryDelay;
                case STOPPED -> null;
            };
            if (wait == null) {
                return;
            }
            if (!wait.isZero()) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Reads one batch of the slot's changes and applies it to the target. */
    Poll poll(Replication replication) {
        Integration integration = replication.integration;
        if (!integrations.isReplicating(integration, replication.sourceDatabase, replication.databaseName)) {
            if (!integrations.exists(integration)) {
                release(replication);
            }
            return Poll.STOPPED;
        }
        Source source;
        List<Change> changes;
        try {
            source = sources.source(integration);
            if (replication.tables == null) {
                if (!sources.slotExists(source, replication.sourceDatabase, replication.name)) {
                    LOG.warnv("Integration {0} does not replicate {1} into {2}: the source has no replication slot {3}."
                                    + " Turn on rds.logical_replication and create the database again to load it"
                                    + " and replicate it.", integration.getIntegrationArn(), replication.sourceDatabase,
                            replication.databaseName, replication.name);
                    return Poll.STOPPED;
                }
                replication.tables = replicatedTables(source, integration, replication.sourceDatabase);
            }
            changes = sources.peekChanges(source, replication.sourceDatabase, replication.name, BATCH_CHANGES);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Integration {0} could not read the changes of {1}; trying again",
                    integration.getIntegrationArn(), replication.sourceDatabase);
            return Poll.RETRY;
        }
        if (changes.isEmpty()) {
            return Poll.IDLE;
        }
        Batch batch;
        try {
            batch = translate(replication, changes);
        } catch (RuntimeException e) {
            fail(replication, source, "could not read the source's changes: " + message(e));
            return Poll.STOPPED;
        }
        try {
            writer.applyRowChanges(integration.getAccountId(), integration.getTargetArn(), replication.databaseName,
                    batch.changes());
        } catch (RedshiftZeroEtlWriter.TargetUnavailableException e) {
            LOG.warnv(e, "Integration {0} could not reach {1} on the target; trying again",
                    integration.getIntegrationArn(), replication.databaseName);
            return Poll.RETRY;
        } catch (RuntimeException e) {
            fail(replication, source, message(e));
            return Poll.STOPPED;
        }
        if (batch.lastCommitLsn() != null) {
            try {
                sources.advance(source, replication.sourceDatabase, replication.name, batch.lastCommitLsn());
            } catch (RuntimeException e) {
                // The changes are applied again from the slot's old position, which leaves the same rows.
                LOG.warnv(e, "Integration {0} could not advance replication slot {1}; trying again",
                        integration.getIntegrationArn(), replication.name);
                return Poll.RETRY;
            }
        }
        return Poll.APPLIED;
    }

    private void fail(Replication replication, Source source, String failure) {
        integrations.failReplication(replication.integration, replication.sourceDatabase, replication.databaseName,
                failure);
        try {
            sources.dropReplication(source, replication.sourceDatabase, replication.name);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Integration {0} could not drop replication slot {1} from the source",
                    replication.integration.getIntegrationArn(), replication.name);
        }
    }

    /** Drops the slot and the publication of a deleted integration from its source. */
    private void release(Replication replication) {
        try {
            sources.dropReplication(sources.source(replication.integration), replication.sourceDatabase,
                    replication.name);
            LOG.infov("Integration {0} was deleted: dropped replication slot {1} from the source",
                    replication.integration.getIntegrationArn(), replication.name);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Integration {0} was deleted but replication slot {1} could not be dropped from the source",
                    replication.integration.getIntegrationArn(), replication.name);
        }
    }

    /** The tables the initial load copies, and so the ones replicated: the same rules give the same tables. */
    private Map<String, ReplicatedSourceTable> replicatedTables(Source source, Integration integration,
                                                                String sourceDatabase) {
        IntegrationDataFilter filter = IntegrationDataFilter.parsePostgres(integration.getDataFilter());
        Map<String, ReplicatedSourceTable> tables = new HashMap<>();
        for (SourceTable table : sources.catalog(source, sourceDatabase)) {
            Optional<List<MappedColumn>> columns = RdsIntegrationSource.mappedColumns(table);
            if (!filter.includes(sourceDatabase, table.schema(), table.table()) || table.primaryKey().isEmpty()
                    || columns.isEmpty()) {
                continue;
            }
            tables.put(tableKey(table.schema(), table.table()), replicatedTable(table, columns.get()));
        }
        return tables;
    }

    static ReplicatedSourceTable replicatedTable(SourceTable table, List<MappedColumn> columns) {
        List<ReplicatedTable.Column> targetColumns = new ArrayList<>();
        Map<String, MappedColumn> byName = new LinkedHashMap<>();
        for (MappedColumn column : columns) {
            targetColumns.add(new ReplicatedTable.Column(column.name(), column.redshiftType()));
            byName.put(column.name(), column);
        }
        return new ReplicatedSourceTable(
                new ReplicatedTable(table.schema(), table.table(), targetColumns, table.primaryKey()), byName);
    }

    private static String tableKey(String schema, String table) {
        return schema + "\u0000" + table;
    }

    /** The row changes of a batch, and the end of its last transaction, where the slot resumes once they apply. */
    record Batch(List<RowChange> changes, String lastCommitLsn) {}

    /**
     * Turns the slot's messages into row changes. An insert is an upsert, so a row the initial load already copied
     * is written over rather than refused. An update is an upsert of the new row, after a delete of the old key when
     * the key changed, or an update of the columns it carries when PostgreSQL left an unchanged TOASTed value out.
     */
    static Batch translate(Replication replication, List<Change> changes) {
        Map<Integer, Relation> relations = new HashMap<>();
        List<RowChange> rowChanges = new ArrayList<>();
        String lastCommitLsn = null;
        for (Change change : changes) {
            Message message = PgOutputMessages.decode(change.data());
            switch (message) {
                case Relation relation -> relations.put(relation.relationId(), relation);
                case Commit commit -> lastCommitLsn = commit.endLsn();
                case Insert insert -> table(replication, relations, insert.relationId()).ifPresent(table ->
                        rowChanges.add(upsert(table, row(relations.get(insert.relationId()), insert.row()))));
                case Update update -> table(replication, relations, update.relationId()).ifPresent(table ->
                        rowChanges.addAll(update(table, relations.get(update.relationId()), update)));
                case Delete delete -> table(replication, relations, delete.relationId()).ifPresent(table ->
                        rowChanges.add(new RowChange(RowChange.Kind.DELETE, table.target(), List.of(),
                                keys(table, row(relations.get(delete.relationId()), delete.oldRow())))));
                case Truncate truncate -> {
                    for (int relationId : truncate.relationIds()) {
                        table(replication, relations, relationId).ifPresent(table -> rowChanges.add(
                                new RowChange(RowChange.Kind.TRUNCATE, table.target(), List.of(), List.of())));
                    }
                }
                default -> { }
            }
        }
        return new Batch(rowChanges, lastCommitLsn);
    }

    private static Optional<ReplicatedSourceTable> table(Replication replication, Map<Integer, Relation> relations,
                                                         int relationId) {
        Relation relation = relations.get(relationId);
        if (relation == null) {
            throw new IllegalStateException("A change names relation " + relationId + " before describing it");
        }
        return Optional.ofNullable(replication.tables.get(tableKey(relation.schema(), relation.table())));
    }

    private static List<RowChange> update(ReplicatedSourceTable table, Relation relation, Update update) {
        Map<String, Value> row = row(relation, update.row());
        boolean partial = row.values().stream().anyMatch(value -> value.kind() == Value.Kind.UNCHANGED);
        List<RowChange> changes = new ArrayList<>();
        if (partial) {
            Map<String, Value> keyRow = update.oldRow() != null ? row(relation, update.oldRow()) : row;
            changes.add(new RowChange(RowChange.Kind.UPDATE, table.target(), values(table, row), keys(table, keyRow)));
            return changes;
        }
        if (update.oldRow() != null) {
            changes.add(new RowChange(RowChange.Kind.DELETE, table.target(), List.of(),
                    keys(table, row(relation, update.oldRow()))));
        }
        changes.add(upsert(table, row));
        return changes;
    }

    private static RowChange upsert(ReplicatedSourceTable table, Map<String, Value> row) {
        return new RowChange(RowChange.Kind.UPSERT, table.target(), values(table, row), List.of());
    }

    /** A tuple's values by column name. */
    private static Map<String, Value> row(Relation relation, Tuple tuple) {
        Map<String, Value> row = new LinkedHashMap<>();
        List<String> columns = relation.columns();
        for (int i = 0; i < columns.size() && i < tuple.values().size(); i++) {
            row.put(columns.get(i), tuple.values().get(i));
        }
        return row;
    }

    /** The row's values of the replicated columns, leaving out the unchanged ones and those the target lacks. */
    private static List<ColumnValue> values(ReplicatedSourceTable table, Map<String, Value> row) {
        List<ColumnValue> values = new ArrayList<>();
        for (Map.Entry<String, Value> entry : row.entrySet()) {
            MappedColumn column = table.columns().get(entry.getKey());
            if (column != null && entry.getValue().kind() != Value.Kind.UNCHANGED) {
                values.add(new ColumnValue(column.name(), column.valueExpression(), entry.getValue().text()));
            }
        }
        return values;
    }

    private static List<ColumnValue> keys(ReplicatedSourceTable table, Map<String, Value> row) {
        List<ColumnValue> keys = new ArrayList<>();
        for (String key : table.target().primaryKey()) {
            Value value = row.get(key);
            if (value == null || value.kind() != Value.Kind.TEXT) {
                throw new IllegalStateException("A change to " + table.target().schema() + "."
                        + table.target().table() + " does not carry its primary key column " + key);
            }
            MappedColumn column = table.columns().get(key);
            keys.add(new ColumnValue(key, column.valueExpression(), value.text()));
        }
        return keys;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
    }
}
