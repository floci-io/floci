package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
class RedshiftDataStatementStore {

    private static final Logger LOG = Logger.getLogger(RedshiftDataStatementStore.class);

    @RegisterForReflection
    enum Status {
        SUBMITTED, PICKED, STARTED, FINISHED, FAILED, ABORTED;

        boolean isTerminal() {
            return this == FINISHED || this == FAILED || this == ABORTED;
        }
    }

    // Statements live in the configured storage backend like every other service's state
    // (memory mode by default, so they are still lost on restart as documented). The TTL
    // sweep below evicts entries the emulator would otherwise keep forever.
    private final AccountAwareStorageBackend<StoredStatement> statements;
    private final Duration ttl;
    private final Clock clock;
    // Created by the CDI lifecycle in start(); the test constructor never starts it, so unit
    // tests that build the store directly drive sweep() by hand and spawn no background thread.
    private ScheduledExecutorService sweeper;

    @Inject
    RedshiftDataStatementStore(StorageFactory storageFactory, EmulatorConfig config) {
        this.statements = storageFactory.create("redshift-data", "statements.json",
                new TypeReference<Map<String, StoredStatement>>() {});
        this.ttl = Duration.ofHours(Math.max(1, config.services().redshiftData().resultTtlHours()));
        this.clock = Clock.systemUTC();
    }

    RedshiftDataStatementStore(int resultTtlHours, Clock clock) {
        this.statements = AccountAwareStorageBackend.inMemory("000000000000");
        this.ttl = Duration.ofHours(Math.max(1, resultTtlHours));
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        for (AccountAwareStorageBackend.AccountEntry<StoredStatement> entry
                : statements.scanAllAccountEntries(k -> true)) {
            StoredStatement interrupted = entry.value().snapshot();
            if (interrupted.status != null && !interrupted.status.isTerminal()) {
                interrupted.status = Status.FAILED;
                interrupted.error = "Execution interrupted by emulator restart";
                interrupted.updatedAt = Instant.now(clock);
                statements.putForAccount(entry.accountId(), entry.key(), interrupted);
            }
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redshift-data-statement-sweep");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepSafely, 30, 30, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    void put(StoredStatement statement) {
        statements.put(statement.id, statement.snapshot());
    }

    void putForAccount(String accountId, StoredStatement statement) {
        statements.putForAccount(accountId, statement.id, statement.snapshot());
    }

    void deleteForAccount(String accountId, String id) {
        statements.deleteForAccount(accountId, id);
    }

    Instant expiresAt(Instant createdAt) {
        return createdAt.plus(ttl);
    }

    StoredStatement get(String id) {
        return statements.get(id).map(StoredStatement::snapshot).orElse(null);
    }

    StoredStatement getForAccount(String accountId, String id) {
        return statements.getForAccount(accountId, id).map(StoredStatement::snapshot).orElse(null);
    }

    List<StoredStatement> values() {
        return statements.scan(k -> true).stream().map(StoredStatement::snapshot).toList();
    }

    void clear() {
        statements.clear();
    }

    void sweep() {
        Instant cutoff = Instant.now(clock).minus(ttl);
        // The sweep thread has no request context, so it iterates every account's partition
        // and deletes per account rather than through the current-account view.
        for (AccountAwareStorageBackend.AccountEntry<StoredStatement> entry
                : statements.scanAllAccountEntries(k -> true)) {
            if (entry.value().status != null && entry.value().status.isTerminal()
                    && entry.value().createdAt.isBefore(cutoff)) {
                statements.deleteForAccount(entry.accountId(), entry.key());
            }
        }
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (Exception e) {
            LOG.warn("Failed to sweep expired Redshift Data API statements", e);
        }
    }

    @RegisterForReflection
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static final class StoredStatement {
        String id;
        String sql;
        List<String> sqls;
        boolean batch;
        String statementName;
        String clusterIdentifier;
        /** Set instead of {@code clusterIdentifier} for a statement run against a serverless workgroup. */
        String workgroupName;
        String database;
        String dbUser;
        String resultFormat = "JSON";
        Status status;
        String error;
        Instant createdAt;
        Instant updatedAt;
        long durationNanos;
        boolean hasResultSet;
        long resultRows;
        long resultSize;
        ArrayNode columnMetadata;
        List<ArrayNode> rows;
        List<StoredStatement> subStatements;
        String accountId;
        String region;
        String resourceArn;
        String principal;
        boolean withEvent;
        Instant expiresAt;

        StoredStatement snapshot() {
            StoredStatement copy = new StoredStatement();
            copy.id = id;
            copy.sql = sql;
            copy.sqls = sqls == null ? null : List.copyOf(sqls);
            copy.batch = batch;
            copy.statementName = statementName;
            copy.clusterIdentifier = clusterIdentifier;
            copy.workgroupName = workgroupName;
            copy.database = database;
            copy.dbUser = dbUser;
            copy.resultFormat = resultFormat;
            copy.status = status;
            copy.error = error;
            copy.createdAt = createdAt;
            copy.updatedAt = updatedAt;
            copy.durationNanos = durationNanos;
            copy.hasResultSet = hasResultSet;
            copy.resultRows = resultRows;
            copy.resultSize = resultSize;
            copy.columnMetadata = columnMetadata == null ? null : columnMetadata.deepCopy();
            copy.rows = rows == null ? null : rows.stream().map(ArrayNode::deepCopy).toList();
            copy.subStatements = subStatements == null ? null : subStatements.stream().map(StoredStatement::snapshot).toList();
            copy.accountId = accountId;
            copy.region = region;
            copy.resourceArn = resourceArn;
            copy.principal = principal;
            copy.withEvent = withEvent;
            copy.expiresAt = expiresAt;
            return copy;
        }
    }
}
