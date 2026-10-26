package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SqlParameterParser.ParsedSql;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditLogPublisher;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditSql;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditSql.UserChange;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditSql.UserChanges;
import io.github.hectorvent.floci.services.redshift.spectrum.BackendSql;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumQueryPreparation;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumReadException;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSession;
import io.github.hectorvent.floci.services.redshift.spectrum.SpectrumSqlException;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataStatementStore.StoredParameter;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataStatementStore.StoredStatement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftDataService implements Resettable {

    private static final int PAGE_SIZE = 1000;
    static final Pattern AUTO_COMMIT_SQL = Pattern.compile(
            "(?is)\\A(?:\\s+|--[^\\r\\n]*(?:\\r?\\n|$)|/\\*.*?\\*/)*"
                    + "(?:VACUUM\\b|(?:CREATE|DROP)\\s+(?:DATABASE|TABLESPACE)\\b|ALTER\\s+SYSTEM\\b"
                    + "|CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b|DROP\\s+INDEX\\s+CONCURRENTLY\\b"
                    + "|REINDEX\\b[^;]*?\\bCONCURRENTLY\\b)");
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
            "(?is)\\A(BEGIN|START\\s+TRANSACTION|COMMIT|END|ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION))?\\s*;?\\s*\\z");
    private static final Pattern TRANSACTION_PREFIX = Pattern.compile("(?is)\\A(?:BEGIN|START|COMMIT|END|ROLLBACK|ABORT)\\b");
    private static final Pattern TRANSACTION_COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\r\\n]*");

    private final RedshiftDataResourceResolver resolver;
    private final RedshiftDataConnectionFactory connectionFactory;
    private final RedshiftDataStatementStore store;
    private final ObjectMapper objectMapper;
    private final SpectrumQueryPreparation preparation;
    private final RedshiftDataExecutor executor;
    private final RedshiftDataEventPublisher publisher;
    private final RedshiftAuditLogPublisher auditLogPublisher;
    private final RequestContext requestContext;
    private final IamService iamService;
    private final RedshiftDataIdempotencyStore idempotency;
    private final RedshiftDataSessions sessions;
    private final ConcurrentHashMap<RedshiftDataIdempotencyStore.Scope, AdmissionLock> admissionLocks = new ConcurrentHashMap<>();

    RedshiftDataService(RedshiftDataResourceResolver resolver, RedshiftDataConnectionFactory connectionFactory,
                        RedshiftDataStatementStore store, ObjectMapper objectMapper, SpectrumQueryPreparation preparation,
                        RedshiftDataExecutor executor, RedshiftDataEventPublisher publisher) {
        this(resolver, connectionFactory, store, objectMapper, preparation, executor, publisher, null,
                null, new RedshiftDataIdempotencyStore(AccountAwareStorageBackend.inMemory("000000000000"),
                        Clock.systemUTC()), new RedshiftDataSessions(), null);
    }

    RedshiftDataService(RedshiftDataResourceResolver resolver, RedshiftDataConnectionFactory connectionFactory,
                        RedshiftDataStatementStore store, ObjectMapper objectMapper, SpectrumQueryPreparation preparation,
                        RedshiftDataExecutor executor, RedshiftDataEventPublisher publisher,
                        RequestContext requestContext, IamService iamService, RedshiftDataIdempotencyStore idempotency,
                        RedshiftDataSessions sessions) {
        this(resolver, connectionFactory, store, objectMapper, preparation, executor, publisher, requestContext,
                iamService, idempotency, sessions, null);
    }

    RedshiftDataService(RedshiftDataResourceResolver resolver, RedshiftDataConnectionFactory connectionFactory,
                               RedshiftDataStatementStore store, ObjectMapper objectMapper, SpectrumQueryPreparation preparation,
                               RedshiftDataExecutor executor, RedshiftDataEventPublisher publisher,
                               RequestContext requestContext, IamService iamService, RedshiftDataIdempotencyStore idempotency) {
        this(resolver, connectionFactory, store, objectMapper, preparation, executor, publisher,
                requestContext, iamService, idempotency, new RedshiftDataSessions(), null);
    }

    @Inject
    public RedshiftDataService(RedshiftDataResourceResolver resolver, RedshiftDataConnectionFactory connectionFactory,
                               RedshiftDataStatementStore store, ObjectMapper objectMapper, SpectrumQueryPreparation preparation,
                               RedshiftDataExecutor executor, RedshiftDataEventPublisher publisher,
                               RequestContext requestContext, IamService iamService, RedshiftDataIdempotencyStore idempotency,
                               RedshiftDataSessions sessions, RedshiftAuditLogPublisher auditLogPublisher) {
        this.resolver = resolver;
        this.connectionFactory = connectionFactory;
        this.store = store;
        this.objectMapper = objectMapper;
        this.preparation = preparation;
        this.executor = executor;
        this.publisher = publisher;
        this.auditLogPublisher = auditLogPublisher;
        this.requestContext = requestContext;
        this.iamService = iamService;
        this.idempotency = idempotency;
        this.sessions = sessions;
    }

    @Override
    public void clear() {
        executor.clear(() -> {
            RuntimeException failure = null;
            try {
                sessions.clear();
            } catch (RuntimeException sessionFailure) {
                failure = sessionFailure;
            }
            try {
                store.clear();
            } catch (RuntimeException storageFailure) {
                if (failure == null) {
                    failure = storageFailure;
                } else if (failure != storageFailure) {
                    failure.addSuppressed(storageFailure);
                }
            }
            try {
                idempotency.clear();
            } catch (RuntimeException receiptFailure) {
                if (failure == null) {
                    failure = receiptFailure;
                } else if (failure != receiptFailure) {
                    failure.addSuppressed(receiptFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        });
    }

    // ── ExecuteStatement ────────────────────────────────────────────────────

    public ObjectNode executeStatement(JsonNode request, String region) {
        return executeStatement(request, region, null);
    }

    public ObjectNode executeStatement(JsonNode request, String region, String principal) {
        return execute(request, region, principal, false);
    }

    private ObjectNode execute(JsonNode request, String region, String principal, boolean batch) {
        String token = RedshiftDataIdempotencyStore.clientToken(request);
        int waitSeconds = waitSeconds(request);
        RedshiftDataSessions.validate(request);
        boolean retained = request.has("SessionId") || request.path("SessionKeepAliveSeconds").asInt() > 0;
        RedshiftDataSessions.Scope owner = retained ? sessionScope(region, principal)
                : new RedshiftDataSessions.Scope(store.accountId(), region, null);
        if (token != null && request.has("SessionId")) {
            RedshiftDataIdempotencyStore.Scope retryScope = new RedshiftDataIdempotencyStore.Scope(
                    owner.accountId(), region, batch ? "BatchExecuteStatement" : "ExecuteStatement", token);
            Admission replay = executor.admitting(() -> {
                RedshiftDataIdempotencyStore.Receipt receipt = idempotency.find(retryScope);
                return receipt == null ? null : replay(receipt, retryScope, owner, principal);
            });
            if (replay != null) {
                return awaitAdmission(replay, waitSeconds);
            }
        }
        PendingStatement pending = batch ? prepareBatch(request, region, owner) : prepareStatement(request, region, owner);
        RedshiftDataStatementStore.StoredStatement stored = pending.statement();
        RedshiftDataIdempotencyStore.Scope scope = token == null ? null : new RedshiftDataIdempotencyStore.Scope(
                stored.accountId, region, batch ? "BatchExecuteStatement" : "ExecuteStatement", token);
        Admission admission = executor.admitting(() -> scope == null ? submit(pending, principal, null)
                : admitScoped(pending, principal, scope));
        return awaitAdmission(admission, waitSeconds);
    }

    private ObjectNode awaitAdmission(Admission admission, int waitSeconds) {
        if (waitSeconds > 0 && admission.execution() != null) {
            admission.execution().await(Duration.ofSeconds(waitSeconds));
            RedshiftDataStatementStore.StoredStatement current = store.getForAccount(admission.accountId(), admission.id());
            if (current != null) {
                return executeResponse(current);
            }
        }
        return admission.response();
    }

    private RedshiftDataSessions.Scope sessionScope(String region, String principal) {
        String caller = principal;
        if (caller == null && requestContext != null && requestContext.getAccessKeyId() != null) {
            String accessKey = requestContext.getAccessKeyId();
            caller = iamService == null ? "access-key:" + accessKey
                    : iamService.resolveCallerArn(accessKey).orElse("access-key:" + accessKey);
        }
        return new RedshiftDataSessions.Scope(store.accountId(), region, caller == null ? "anonymous" : caller);
    }

    private Admission replay(RedshiftDataIdempotencyStore.Receipt receipt, RedshiftDataIdempotencyStore.Scope scope,
                             RedshiftDataSessions.Scope owner, String principal) {
        if (receipt.sessionOwner != null) {
            String caller = owner.caller() == null ? sessionScope(scope.region(), principal).caller() : owner.caller();
            if (!Objects.equals(receipt.sessionOwner, caller)) {
                throw new AwsException("ResourceNotFoundException", "Session was not found", 400);
            }
        }
        String id = receipt.response.path("Id").asText();
        RedshiftDataStatementStore.StoredStatement current = store.getForAccount(scope.accountId(), id);
        return new Admission(scope.accountId(), id, current == null ? receipt.response : executeResponse(current),
                executor.execution(scope.accountId(), id));
    }

    private record Admission(String accountId, String id, ObjectNode response, RedshiftDataExecution execution) { }

    private static final class AdmissionLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private Admission admitScoped(PendingStatement pending, String principal, RedshiftDataIdempotencyStore.Scope scope) {
        AdmissionLock scoped = admissionLocks.compute(scope, (key, existing) -> {
            AdmissionLock selected = existing == null ? new AdmissionLock() : existing;
            selected.users++;
            return selected;
        });
        scoped.lock.lock();
        try {
            RedshiftDataIdempotencyStore.Receipt receipt = idempotency.find(scope);
            if (receipt == null) {
                return submit(pending, principal, scope);
            }
            return replay(receipt, scope, pending.owner(), principal);
        } finally {
            scoped.lock.unlock();
            admissionLocks.compute(scope, (key, existing) -> --scoped.users == 0 ? null : scoped);
        }
    }

    private Admission submit(PendingStatement pending, String principal, RedshiftDataIdempotencyStore.Scope scope) {
        RedshiftDataStatementStore.StoredStatement stored = pending.statement();
        if (principal != null) {
            stored.principal = principal;
        }
        RedshiftDataSessions.Reservation session = sessions.reserve(pending.owner(), pending.request(), pending.target());
        if (session != null) {
            stored.sessionId = session.id();
        }
        ObjectNode response = executeResponse(stored.snapshot());
        RedshiftDataExecution handle;
        try {
            handle = executor.submit(stored.accountId, stored.region, stored.id,
                    () -> {
                        if (scope == null) {
                            store.putForAccount(stored.accountId, stored);
                        } else {
                            store.admitForAccount(stored.accountId, stored);
                            if (session == null) {
                                idempotency.put(scope, response);
                            } else {
                                idempotency.put(scope, response, pending.owner().caller());
                            }
                        }
                        if (session != null) {
                            session.admitted();
                        }
                    },
                    execution -> {
                        try {
                            execution.checkCancellation();
                            if (session != null) {
                                session.bind(execution);
                            }
                            stored.status = RedshiftDataStatementStore.Status.STARTED;
                            executor.publish(execution, () -> store.putForAccount(stored.accountId, stored));
                            pending.run().accept(execution, session);
                        } catch (Exception failure) {
                            stored.status = RedshiftDataStatementStore.Status.FAILED;
                            stored.error = failure.getMessage();
                        } finally {
                            execution.terminal();
                            if (session != null) {
                                session.finish();
                            }
                        }
                        complete(stored, execution);
                    },
                    (execution, failure) -> recoverStatement(stored, execution, failure));
        } catch (RuntimeException failure) {
            if (session != null) {
                session.abort();
            }
            try {
                if (scope != null) {
                    idempotency.removeIfStatement(scope, stored.id);
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                store.deleteForAccount(stored.accountId, stored.id);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new Admission(stored.accountId, stored.id, response, handle);
    }

    private void complete(RedshiftDataStatementStore.StoredStatement stored, RedshiftDataExecution execution) {
        execution.terminal();
        if (execution.isCancellationRequested()) {
            stored.status = RedshiftDataStatementStore.Status.ABORTED;
            stored.error = "Statement cancelled";
        }
        if (stored.status != RedshiftDataStatementStore.Status.FINISHED) {
            stored.hasResultSet = false;
            stored.rows = null;
            stored.resultRows = 0;
            stored.resultSize = 0;
        }
        stored.updatedAt = Instant.now();
        executor.publish(execution, () -> {
            store.putForAccount(stored.accountId, stored);
            if (stored.withEvent && publisher != null) {
                RedshiftDataStatementStore.StoredStatement terminal = stored.snapshot();
                executor.deliver(execution, stored.accountId, stored.region, stored.id,
                        () -> publisher.publish(terminal));
            }
        });
    }

    /** Last resort for a worker that died outside the statement handler: never leave a statement active. */
    private void recoverStatement(RedshiftDataStatementStore.StoredStatement stored,
                                  RedshiftDataExecution execution, RuntimeException failure) {
        RedshiftDataStatementStore.StoredStatement persisted = store.getForAccount(stored.accountId, stored.id);
        if (persisted != null && persisted.status != null && persisted.status.isTerminal()) {
            return;
        }
        stored.status = RedshiftDataStatementStore.Status.FAILED;
        stored.error = "Internal error: " + failure.getMessage();
        complete(stored, execution);
    }

    private static int waitSeconds(JsonNode request) {
        if (!request.has("WaitTimeSeconds")) {
            return 0;
        }
        JsonNode wait = request.get("WaitTimeSeconds");
        if (!wait.isIntegralNumber() || !wait.canConvertToInt() || wait.asInt() < 1 || wait.asInt() > 30) {
            throw new AwsException("ValidationException", "WaitTimeSeconds must be an integer between 1 and 30", 400);
        }
        return wait.asInt();
    }

    private void capture(RedshiftDataStatementStore.StoredStatement stored,
                         RedshiftDataResourceResolver.DatabaseTarget target, String region, JsonNode request) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(target.arn());
        stored.accountId = arn.accountId();
        stored.region = region;
        stored.resourceArn = target.arn();
        stored.withEvent = request.path("WithEvent").asBoolean(false);
        stored.expiresAt = store.expiresAt(stored.createdAt);
        if (stored.withEvent && requestContext != null && requestContext.getAccessKeyId() != null) {
            stored.principal = iamService.resolveCallerArn(requestContext.getAccessKeyId()).orElse(null);
        }
    }

    private PendingStatement prepareStatement(JsonNode request, String region, RedshiftDataSessions.Scope owner) {
        String sql = requiredText(request, "Sql");
        rejectMultiStatement(sql);
        Map<String, String> parameters = RedshiftDataSqlParameters.parseParameters(request, "Parameters");

        ResolvedTarget resolved = resolveTarget(request, region, owner);
        RedshiftDataResourceResolver.DatabaseTarget target = resolved.target();

        RedshiftDataStatementStore.StoredStatement stored = newStatement(request);
        stored.sql = sql;
        stored.sqls = List.of(sql);
        stored.clusterIdentifier = clusterIdentifierOrNull(resolved.parameters());
        stored.workgroupName = workgroupNameOrNull(resolved.parameters());
        stored.dbUser = target.user();
        stored.database = target.database();
        stored.resultFormat = request.path("ResultFormat").asText("JSON");
        stored.queryParameters = storedParameters(parameters);
        capture(stored, target, region, request);

        return new PendingStatement(stored, (execution, session) -> runStatement(stored, target, sql, parameters, execution, session),
                target, request.deepCopy(), owner);
    }

    private record PendingStatement(RedshiftDataStatementStore.StoredStatement statement,
                                    BiConsumer<RedshiftDataExecution, RedshiftDataSessions.Reservation> run,
                                    RedshiftDataResourceResolver.DatabaseTarget target, JsonNode request,
                                    RedshiftDataSessions.Scope owner) { }

    private record ResolvedTarget(RedshiftDataResourceResolver.DatabaseTarget target, JsonNode parameters) { }

    private ResolvedTarget resolveTarget(JsonNode request, String region, RedshiftDataSessions.Scope owner) {
        if (request.has("SessionId")) {
            RedshiftDataSessions.Session session = sessions.find(owner, request.get("SessionId").asText());
            return new ResolvedTarget(session.target(request), session.parameters());
        }
        return new ResolvedTarget(resolver.resolve(request, region), request);
    }

    private ConnectionUse connection(RedshiftDataResourceResolver.DatabaseTarget target,
                                     RedshiftDataSessions.Reservation session) throws SQLException {
        Runnable disconnected = () -> publishConnection(target, "disconnection", null);
        try {
            Connection connection;
            if (session == null) {
                connection = connectionFactory.open(target);
                publishConnection(target, "authenticated", null);
            } else {
                connection = session.connection(connectionFactory,
                        () -> publishConnection(target, "authenticated", null), disconnected);
            }
            return new ConnectionUse(connection, session, disconnected);
        } catch (SQLException failure) {
            publishConnection(target, "authentication failure", failure.getSQLState());
            throw failure;
        }
    }

    private void publishConnection(RedshiftDataResourceResolver.DatabaseTarget target, String event, String error) {
        if (auditLogPublisher != null && target.cluster() != null) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(target.arn());
            auditLogPublisher.publishConnection(target.cluster(), arn.accountId(), arn.region(), target.database(),
                    target.auditUser(), event, null, error);
        }
    }

    private record ConnectionUse(Connection connection, RedshiftDataSessions.Reservation session,
                                 Runnable disconnected) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            if (session == null) {
                try {
                    connection.close();
                } finally {
                    disconnected.run();
                }
                return;
            }
            try {
                if (connection.isClosed()) {
                    session.discard();
                } else if (!session.inTransaction() && !connection.getAutoCommit()) {
                    // A failed batch or worker must not leak an open transaction to the next request.
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException | RuntimeException failure) {
                try {
                    session.discard();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    private void runStatement(RedshiftDataStatementStore.StoredStatement stored,
                              RedshiftDataResourceResolver.DatabaseTarget target,
                              String sql, Map<String, String> parameters, RedshiftDataExecution execution,
                              RedshiftDataSessions.Reservation session) {
        try (ConnectionUse use = connection(target, session)) {
            Connection connection = use.connection();
            execution.track(connection);
            try {
                publishActivity(stored, target, sql);
                if (session != null && transactionControl(stored, target, sql, connection, session, execution)) {
                    return;
                }
                if (session != null && session.transactionFailed()) {
                    throw new SQLException("Current transaction is aborted; use COMMIT or ROLLBACK to end it", "25P02");
                }
                boolean autoCommit = AUTO_COMMIT_SQL.matcher(sql).find()
                        || preparation != null && target.spectrum() != null
                        && preparation.handlesDdl(sql, target.spectrum());
                if (autoCommit) {
                    if (session != null && session.inTransaction()) {
                        throw new SQLException("Statement cannot run inside a transaction block", "25001");
                    }
                    execution.commit(() -> runOnConnection(stored, connection, sql, parameters,
                            target.spectrum(), execution, true));
                    successfulUserChange(stored, target, sql, session);
                } else {
                    runOnConnection(stored, connection, sql, parameters, target.spectrum(), execution, false);
                    execution.commit(() -> {
                        if ((session == null || !session.inTransaction()) && !connection.getAutoCommit()) {
                            connection.commit();
                        }
                    });
                    successfulUserChange(stored, target, sql, session);
                }
            } catch (SQLException failure) {
                if (session != null && failure.getSQLState() != null && failure.getSQLState().startsWith("08")) {
                    session.discard();
                    throw failure;
                }
                if (session != null && session.inTransaction()) {
                    session.failTransaction();
                } else if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
                throw failure;
            } catch (RuntimeException failure) {
                if (session != null && session.inTransaction()) {
                    session.failTransaction();
                }
                throw failure;
            }
        } catch (SQLException e) {
            stored.status = RedshiftDataStatementStore.Status.FAILED;
            stored.error = e.getMessage();
        }
        stored.updatedAt = Instant.now();
    }

    private void publishActivity(RedshiftDataStatementStore.StoredStatement stored,
                                 RedshiftDataResourceResolver.DatabaseTarget target, String sql) {
        if (auditLogPublisher != null && target.cluster() != null) {
            auditLogPublisher.publishActivity(target.cluster(), stored.accountId, stored.region,
                    target.database(), target.auditUser(), sql);
        }
    }

    private void successfulUserChange(RedshiftDataStatementStore.StoredStatement stored,
                                      RedshiftDataResourceResolver.DatabaseTarget target, String sql,
                                      RedshiftDataSessions.Reservation session) {
        if (session != null && session.inTransaction()) {
            session.completeUserChanges(sql);
        } else {
            publishUserChanges(stored, target, RedshiftAuditSql.userChanges(sql));
        }
    }

    private void publishUserChanges(RedshiftDataStatementStore.StoredStatement stored,
                                    RedshiftDataResourceResolver.DatabaseTarget target, List<UserChange> changes) {
        if (auditLogPublisher != null && target.cluster() != null) {
            for (UserChange change : changes) {
                auditLogPublisher.publishUserChange(target.cluster(), stored.accountId, stored.region,
                        target.database(), change.username(), change.action(), change.oldUsername());
            }
        }
    }

    private boolean transactionControl(RedshiftDataStatementStore.StoredStatement stored,
                                       RedshiftDataResourceResolver.DatabaseTarget target, String sql,
                                       Connection connection, RedshiftDataSessions.Reservation session,
                                       RedshiftDataExecution execution) throws SQLException {
        String normalized = TRANSACTION_COMMENTS.matcher(sql).replaceAll(" ").trim();
        Matcher control = TRANSACTION_CONTROL.matcher(normalized);
        if (!control.matches()) {
            if (TRANSACTION_PREFIX.matcher(normalized).find()) {
                throw new SQLException("Transaction control modifiers are not supported", "0A000");
            }
            return false;
        }
        String command = control.group(1).toUpperCase(Locale.ROOT).split("\\s+")[0];
        execution.commit(() -> {
            switch (command) {
                case "BEGIN", "START" -> {
                    if (session.transactionFailed()) {
                        throw new SQLException("Current transaction is aborted; use COMMIT or ROLLBACK to end it", "25P02");
                    }
                    connection.setAutoCommit(false);
                    session.transaction(true);
                }
                case "COMMIT", "END" -> {
                    boolean rolledBack = session.transactionFailed();
                    if (!connection.getAutoCommit()) {
                        if (session.transactionFailed()) {
                            connection.rollback();
                        } else {
                            connection.commit();
                        }
                    }
                    List<UserChange> changes = session.takeUserChanges();
                    if (!rolledBack) {
                        publishUserChanges(stored, target, changes);
                    }
                    session.transaction(false);
                    connection.setAutoCommit(true);
                }
                default -> {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                    }
                    session.takeUserChanges();
                    session.transaction(false);
                    connection.setAutoCommit(true);
                }
            }
        });
        stored.status = RedshiftDataStatementStore.Status.FINISHED;
        stored.updatedAt = Instant.now();
        return true;
    }

    /**
     * Redshift has no line, json or jsonb type and fails a statement that produces one with
     * exactly these messages (checked on a real cluster 2026-09-13).
     */
    private static final Map<String, String> RESULT_TYPE_ERRORS = Map.of(
            "line", "ERROR: type \"line\" not yet implemented",
            "json", "ERROR: type \"json\" does not exist",
            "jsonb", "ERROR: type \"jsonb\" does not exist");

    static void rejectResultTypesRedshiftLacks(ResultSetMetaData meta) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String error = RESULT_TYPE_ERRORS.get(meta.getColumnTypeName(i));
            if (error != null) {
                throw new SQLException(error);
            }
        }
    }

    private void runOnConnection(RedshiftDataStatementStore.StoredStatement stored,
                                 Connection connection, String sql, Map<String, String> parameters, SpectrumSession context,
                                 RedshiftDataExecution execution, boolean autoCommit)
            throws SQLException {
        if (preparation != null && context != null) {
            SpectrumSession session = new SpectrumSession(context.accountId(), context.clusterKey(), context.databaseName(),
                    context.iamRoleArns(), !connection.getAutoCommit());
            try {
                if (preparation.prepare(sql, session, new RedshiftDataBackendSql(connection, execution))) {
                    stored.status = RedshiftDataStatementStore.Status.FINISHED;
                    return;
                }
            } catch (SpectrumSqlException exception) {
                throw new SQLException(exception.getMessage(), exception.sqlState(), exception);
            } catch (SpectrumReadException exception) {
                throw new SQLException(exception.getMessage(), exception.sqlState(), exception);
            } catch (RuntimeException exception) {
                // Anything else from Glue or the read path must fail the statement, not leave it running.
                throw new SQLException("External query preparation failed: " + exception.getMessage(), "58030", exception);
            }
        }
        ParsedSql parsed = RedshiftDataSqlParameters.parse(sql);
        execution.checkCancellation();
        if (!autoCommit) {
            connection.setAutoCommit(false);
        }
        long t0 = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(parsed.sql())) {
            execution.track(statement);
            try {
                RedshiftDataSqlParameters.bind(statement, parsed.parameterOrder(), parameters);
                execution.checkCancellation();
                boolean hasResultSet = statement.execute();
                if (hasResultSet) {
                    try (ResultSet rs = statement.getResultSet()) {
                        rejectResultTypesRedshiftLacks(rs.getMetaData());
                        stored.columnMetadata = RedshiftDataColumnMetadata.toColumnMetadata(objectMapper, rs.getMetaData());
                        stored.rows = RedshiftDataFieldMapper.rows(objectMapper, rs);
                        stored.hasResultSet = true;
                        stored.resultRows = stored.rows.size();
                        stored.resultSize = RedshiftDataFieldMapper.serializedSize(stored.rows);
                    }
                } else {
                    stored.hasResultSet = false;
                    stored.resultRows = Math.max(statement.getUpdateCount(), 0);
                }
            } finally {
                execution.untrack(statement);
            }
        }
        stored.durationNanos = System.nanoTime() - t0;
        stored.status = RedshiftDataStatementStore.Status.FINISHED;
    }

    private ObjectNode executeResponse(RedshiftDataStatementStore.StoredStatement stored) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", stored.id);
        if (stored.clusterIdentifier != null) {
            response.put("ClusterIdentifier", stored.clusterIdentifier);
        } else if (stored.workgroupName == null) {
            response.putNull("ClusterIdentifier");
        }
        response.put("CreatedAt", epochSeconds(stored.createdAt));
        response.put("Database", stored.database);
        if (stored.dbUser != null) {
            response.put("DbUser", stored.dbUser);
        }
        response.putArray("DbGroups");
        putWorkgroupName(response, stored);
        response.put("HasResultSet", stored.hasResultSet);
        if (stored.sessionId == null) {
            response.putNull("SessionId");
        } else {
            response.put("SessionId", stored.sessionId);
        }
        return response;
    }

    // ── DescribeStatement ───────────────────────────────────────────────────

    public ObjectNode describeStatement(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = require(request);
        ObjectNode response = objectMapper.createObjectNode();
        if (stored.sessionId != null) {
            response.put("SessionId", stored.sessionId);
        }
        response.put("Id", stored.id);
        response.put("Status", stored.status.name());
        response.put("CreatedAt", epochSeconds(stored.createdAt));
        response.put("UpdatedAt", epochSeconds(stored.updatedAt));
        response.put("Duration", stored.durationNanos);
        if (stored.error != null) {
            response.put("Error", stored.error);
        }
        response.put("QueryString", stored.sql);
        putQueryParameters(response, stored.queryParameters);
        int stableId = stored.id.hashCode() & Integer.MAX_VALUE;
        response.put("RedshiftPid", stableId);
        response.put("RedshiftQueryId", (long) stableId);
        response.put("ResultRows", stored.resultRows);
        response.put("ResultSize", stored.resultSize);
        response.put("HasResultSet", stored.hasResultSet);
        if (stored.clusterIdentifier != null) {
            response.put("ClusterIdentifier", stored.clusterIdentifier);
        }
        response.put("Database", stored.database);
        if (stored.dbUser != null) {
            response.put("DbUser", stored.dbUser);
        }
        putWorkgroupName(response, stored);
        response.put("ResultFormat", stored.resultFormat);
        if (stored.batch && stored.subStatements != null) {
            ArrayNode subs = response.putArray("SubStatements");
            for (RedshiftDataStatementStore.StoredStatement sub : stored.subStatements) {
                ObjectNode s = subs.addObject();
                s.put("Id", sub.id);
                s.put("Status", sub.status.name());
                s.put("QueryString", sub.sql);
                s.put("Duration", sub.durationNanos);
                s.put("ResultRows", sub.resultRows);
                s.put("ResultSize", sub.resultSize);
                s.put("HasResultSet", sub.hasResultSet);
                if (sub.error != null) {
                    s.put("Error", sub.error);
                }
            }
        }
        return response;
    }

    // ── GetStatementResult / GetStatementResultV2 ───────────────────────────

    public ObjectNode getStatementResult(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = resultBearing(request);
        int offset = decodeToken(textOrNull(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ColumnMetadata", stored.columnMetadata);
        ArrayNode records = response.putArray("Records");
        int end = Math.min(offset + PAGE_SIZE, stored.rows.size());
        for (int i = offset; i < end; i++) {
            records.add(stored.rows.get(i));
        }
        response.put("TotalNumRows", (long) stored.rows.size());
        if (end < stored.rows.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    public ObjectNode getStatementResultV2(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = resultBearing(request);
        int offset = decodeToken(textOrNull(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ColumnMetadata", stored.columnMetadata);
        int end = Math.min(offset + PAGE_SIZE, stored.rows.size());
        boolean csv = "CSV".equalsIgnoreCase(stored.resultFormat);
        ArrayNode records = response.putArray("Records");
        for (int i = offset; i < end; i++) {
            if (csv) {
                records.addObject().put("CSVRecords", toCsvLine(stored.rows.get(i)));
            } else {
                records.add(stored.rows.get(i));
            }
        }
        response.put("ResultFormat", csv ? "CSV" : "JSON");
        response.put("TotalNumRows", (long) stored.rows.size());
        if (end < stored.rows.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    private static String toCsvLine(ArrayNode row) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            JsonNode field = row.get(i);
            if (field.path("isNull").asBoolean(false)) {
                continue;
            }
            line.append(csvCell(fieldText(field)));
        }
        return line.toString();
    }

    private static String fieldText(JsonNode field) {
        if (field.has("stringValue")) {
            return field.get("stringValue").asText();
        }
        if (field.has("longValue")) {
            return Long.toString(field.get("longValue").asLong());
        }
        if (field.has("doubleValue")) {
            return Double.toString(field.get("doubleValue").asDouble());
        }
        if (field.has("booleanValue")) {
            return Boolean.toString(field.get("booleanValue").asBoolean());
        }
        if (field.has("blobValue")) {
            return field.get("blobValue").asText();
        }
        return "";
    }

    // RFC 4180: quote a field that contains a comma, quote, CR, or LF, doubling embedded quotes.
    private static String csvCell(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ── BatchExecuteStatement ──────────────────────────────────────────────

    public ObjectNode batchExecuteStatement(JsonNode request, String region) {
        return batchExecuteStatement(request, region, null);
    }

    public ObjectNode batchExecuteStatement(JsonNode request, String region, String principal) {
        return execute(request, region, principal, true);
    }

    private PendingStatement prepareBatch(JsonNode request, String region, RedshiftDataSessions.Scope owner) {
        JsonNode sqlsNode = request.get("Sqls");
        if (sqlsNode == null || !sqlsNode.isArray() || sqlsNode.isEmpty()) {
            throw new AwsException("ValidationException", "Sqls must be a non-empty array of SQL statements.", 400);
        }
        List<String> sqls = new ArrayList<>();
        for (JsonNode n : sqlsNode) {
            sqls.add(n.asText());
        }
        Map<String, String> parameters = RedshiftDataSqlParameters.parseBatchParameters(request, sqls);

        ResolvedTarget resolved = resolveTarget(request, region, owner);
        RedshiftDataResourceResolver.DatabaseTarget target = resolved.target();

        RedshiftDataStatementStore.StoredStatement parent = newStatement(request);
        parent.batch = true;
        parent.sql = String.join("; ", sqls);
        parent.sqls = sqls;
        parent.clusterIdentifier = clusterIdentifierOrNull(resolved.parameters());
        parent.workgroupName = workgroupNameOrNull(resolved.parameters());
        parent.dbUser = target.user();
        parent.database = target.database();
        parent.resultFormat = request.path("ResultFormat").asText("JSON");
        parent.subStatements = new ArrayList<>();
        parent.queryParameters = storedParameters(parameters);
        capture(parent, target, region, request);

        return new PendingStatement(parent, (execution, session) -> runBatch(parent, sqls, parameters, target, execution, session),
                target, request.deepCopy(), owner);
    }

    private void runBatch(RedshiftDataStatementStore.StoredStatement parent, List<String> sqls, Map<String, String> parameters,
                          RedshiftDataResourceResolver.DatabaseTarget target, RedshiftDataExecution execution,
                          RedshiftDataSessions.Reservation session) {
        long totalDuration = 0;
        if (session != null) {
            if (session.transactionFailed()) {
                parent.status = RedshiftDataStatementStore.Status.FAILED;
                parent.error = "Current transaction is aborted; use COMMIT or ROLLBACK to end it";
                parent.updatedAt = Instant.now();
                return;
            }
            session.transaction(false);
        }
        try (ConnectionUse use = connection(target, session)) {
            Connection connection = use.connection();
            execution.track(connection);
            BackendSql batchBackend = new RedshiftDataBackendSql(connection, execution);
            UserChanges userChanges = session == null ? new UserChanges() : session.takeUserChangeState();
            boolean committed = false;
            try {
                connection.setAutoCommit(false);
                boolean failed = false;
                for (int n = 0; n < sqls.size(); n++) {
                    RedshiftDataStatementStore.StoredStatement sub = new RedshiftDataStatementStore.StoredStatement();
                    sub.id = parent.id + ":" + (n + 1);
                    sub.sql = sqls.get(n);
                    sub.sqls = List.of(sqls.get(n));
                    sub.clusterIdentifier = parent.clusterIdentifier;
                    sub.workgroupName = parent.workgroupName;
                    sub.database = parent.database;
                    sub.dbUser = parent.dbUser;
                    sub.sessionId = parent.sessionId;
                    sub.resultFormat = parent.resultFormat;
                    Instant now = Instant.now();
                    sub.createdAt = now;
                    sub.updatedAt = now;
                    try {
                        execution.checkCancellation();
                        publishActivity(parent, target, sqls.get(n));
                        runBatchEntry(sub, connection, sqls.get(n), parameters, target.spectrum(), execution, userChanges);
                    } catch (SQLException e) {
                        sub.status = RedshiftDataStatementStore.Status.FAILED;
                        sub.error = e.getMessage();
                        sub.updatedAt = Instant.now();
                        parent.subStatements.add(sub);
                        executor.publish(execution, () -> store.putForAccount(parent.accountId, sub));
                        totalDuration += sub.durationNanos;
                        parent.status = RedshiftDataStatementStore.Status.FAILED;
                        parent.error = e.getMessage();
                        if (session != null && e.getSQLState() != null && e.getSQLState().startsWith("08")) {
                            session.discard();
                            throw e;
                        }
                        connection.rollback();
                        if (session != null) {
                            session.takeUserChanges();
                        }
                        failed = true;
                        break;
                    }
                    sub.updatedAt = Instant.now();
                    parent.subStatements.add(sub);
                    executor.publish(execution, () -> {
                        store.putForAccount(parent.accountId, sub);
                        store.putForAccount(parent.accountId, parent);
                    });
                    totalDuration += sub.durationNanos;
                }
                if (!failed) {
                    execution.commit(connection::commit);
                    committed = true;
                    userChanges.commit();
                    parent.status = RedshiftDataStatementStore.Status.FINISHED;
                }
            } finally {
                if (!committed) {
                    userChanges.rollback();
                }
                publishUserChanges(parent, target, userChanges.drainCommitted());
                if (!committed && session != null) {
                    session.takeUserChanges();
                }
                if (preparation != null) {
                    if (committed) {
                        preparation.finishCycle(batchBackend, true);
                    } else {
                        preparation.finishCycle(batchBackend);
                    }
                }
            }
        } catch (SQLException e) {
            parent.status = RedshiftDataStatementStore.Status.FAILED;
            parent.error = e.getMessage();
        }

        for (int i = parent.subStatements.size() - 1; i >= 0; i--) {
            RedshiftDataStatementStore.StoredStatement sub = parent.subStatements.get(i);
            if (sub.hasResultSet) {
                parent.hasResultSet = true;
                parent.columnMetadata = sub.columnMetadata;
                parent.rows = sub.rows;
                parent.resultRows = sub.resultRows;
                parent.resultSize = sub.resultSize;
                break;
            }
        }
        parent.durationNanos = totalDuration;
        parent.updatedAt = Instant.now();
    }

    private void runBatchEntry(RedshiftDataStatementStore.StoredStatement sub, Connection connection, String sql,
                               Map<String, String> parameters, SpectrumSession context,
                               RedshiftDataExecution execution, UserChanges userChanges)
            throws SQLException {
        List<String> commands = RedshiftAuditSql.statements(sql);
        if (commands.isEmpty()) {
            runOnConnection(sub, connection, sql, parameters, context, execution, false);
            return;
        }
        long duration = 0;
        for (int i = 0; i < commands.size(); i++) {
            // JDBC exposes the first result of an entry; later commands still affect transaction audit state.
            RedshiftDataStatementStore.StoredStatement result = i == 0 ? sub : new RedshiftDataStatementStore.StoredStatement();
            try {
                String command = commands.get(i);
                if (RedshiftAuditSql.isCommit(command)) {
                    execution.commit(() -> runOnConnection(result, connection, command, parameters, context, execution, false));
                } else {
                    runOnConnection(result, connection, command, parameters, context, execution, false);
                }
                userChanges.complete(command);
            } finally {
                duration += result.durationNanos;
                sub.durationNanos = duration;
            }
        }
    }

    // ── ListStatements ─────────────────────────────────────────────────────

    public ObjectNode listStatements(JsonNode request) {
        String nameFilter = textOrNull(request, "StatementName");
        String statusFilter = textOrNull(request, "Status");
        // AWS treats 0 or absent as the default and rejects anything above the documented
        // maximum of 100. Clamping 0 up also stops a follower looping on a static NextToken.
        int maxResults = request.path("MaxResults").asInt(100);
        if (maxResults <= 0) {
            maxResults = 100;
        } else if (maxResults > 100) {
            throw new AwsException("ValidationException", "MaxResults must not exceed 100.", 400);
        }
        int offset = decodeToken(textOrNull(request, "NextToken"));

        List<RedshiftDataStatementStore.StoredStatement> all = store.values().stream()
                .filter(s -> s.id.indexOf(':') < 0)
                .filter(s -> nameFilter == null || nameFilter.equals(s.statementName))
                .filter(s -> statusFilter == null || statusFilter.equalsIgnoreCase(s.status.name()))
                .sorted(Comparator.comparing((RedshiftDataStatementStore.StoredStatement s) -> s.createdAt).reversed())
                .toList();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode statements = response.putArray("Statements");
        int end = Math.min(offset + maxResults, all.size());
        for (int i = offset; i < end; i++) {
            RedshiftDataStatementStore.StoredStatement s = all.get(i);
            ObjectNode node = statements.addObject();
            node.put("Id", s.id);
            if (s.sessionId != null) {
                node.put("SessionId", s.sessionId);
            }
            node.put("QueryString", s.sql);
            ArrayNode queryStrings = node.putArray("QueryStrings");
            for (String sql : s.sqls != null ? s.sqls : List.of(s.sql)) {
                queryStrings.add(sql);
            }
            node.put("Status", s.status.name());
            node.put("CreatedAt", epochSeconds(s.createdAt));
            node.put("UpdatedAt", epochSeconds(s.updatedAt));
            if (s.statementName != null) {
                node.put("StatementName", s.statementName);
            }
            node.put("IsBatchStatement", s.batch);
            putQueryParameters(node, s.queryParameters);
            node.put("ResultRows", s.resultRows);
            node.put("ResultSize", s.resultSize);
            node.put("Duration", s.durationNanos);
        }
        if (end < all.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    // ── CancelStatement ────────────────────────────────────────────────────

    public ObjectNode cancelStatement(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = require(request);
        if (stored.status.isTerminal()) {
            throw new AwsException("ValidationException",
                    "Could not cancel a query that is already in " + stored.status.name() + " state with ID: " + stored.id,
                    400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Status", executor.cancel(stored.accountId, stored.id));
        return response;
    }

    // ── Schema introspection ───────────────────────────────────────────────

    public ObjectNode listDatabases(JsonNode request, String region) {
        RedshiftDataResourceResolver.DatabaseTarget target = resolver.resolve(request, region);
        List<String> names = new ArrayList<>();
        try (Connection c = connectionFactory.open(target);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
        return pageStrings(request, "Databases", names);
    }

    public ObjectNode listSchemas(JsonNode request, String region) {
        RedshiftDataResourceResolver.DatabaseTarget target = resolver.resolve(request, region);
        String pattern = orLike(textOrNull(request, "SchemaPattern"));
        List<String> names = new ArrayList<>();
        try (Connection c = connectionFactory.open(target);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT schema_name FROM information_schema.schemata "
                             + "WHERE schema_name LIKE ? ORDER BY schema_name")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
        return pageStrings(request, "Schemas", names);
    }

    public ObjectNode listTables(JsonNode request, String region) {
        RedshiftDataResourceResolver.DatabaseTarget target = resolver.resolve(request, region);
        String schemaPattern = orLike(textOrNull(request, "SchemaPattern"));
        String tablePattern = orLike(textOrNull(request, "TablePattern"));
        List<ObjectNode> tables = new ArrayList<>();
        try (Connection c = connectionFactory.open(target);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_schema, table_name, table_type FROM information_schema.tables "
                             + "WHERE table_schema LIKE ? AND table_name LIKE ? "
                             + "ORDER BY table_schema, table_name")) {
            ps.setString(1, schemaPattern);
            ps.setString(2, tablePattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode t = objectMapper.createObjectNode();
                    t.put("schema", rs.getString(1));
                    t.put("name", rs.getString(2));
                    t.put("type", "VIEW".equalsIgnoreCase(rs.getString(3)) ? "VIEW" : "TABLE");
                    tables.add(t);
                }
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
        return pageObjects(request, "Tables", tables);
    }

    public ObjectNode describeTable(JsonNode request, String region) {
        RedshiftDataResourceResolver.DatabaseTarget target = resolver.resolve(request, region);
        String schema = textOrNull(request, "Schema");
        String table = requiredText(request, "Table");
        boolean withSchema = schema != null && !schema.isBlank();
        // Table and Schema name a specific table, not a pattern: match exactly so a name
        // containing '_' or '%' cannot pull columns from other tables into ColumnList.
        String sql = "SELECT column_name, data_type, character_maximum_length, is_nullable, "
                + "numeric_precision, numeric_scale, column_default, table_schema, table_name "
                + "FROM information_schema.columns WHERE table_name = ? "
                + (withSchema ? "AND table_schema = ? " : "")
                + "ORDER BY ordinal_position";

        List<ObjectNode> columns = new ArrayList<>();
        try (Connection c = connectionFactory.open(target);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            if (withSchema) {
                ps.setString(2, schema);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode col = objectMapper.createObjectNode();
                    String columnName = rs.getString("column_name");
                    col.put("name", columnName);
                    col.put("label", columnName);
                    col.put("typeName", rs.getString("data_type"));
                    col.put("length", rs.getInt("character_maximum_length"));
                    col.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")) ? 1 : 0);
                    // information_schema does not carry these per-column flags. They are
                    // fixed at emulation-friendly defaults rather than driver-derived as in
                    // GetStatementResult's ColumnMetadata, which reads a live ResultSetMetaData.
                    col.put("isCaseSensitive", false);
                    col.put("isCurrency", false);
                    col.put("isSigned", true);
                    col.put("precision", rs.getInt("numeric_precision"));
                    col.put("scale", rs.getInt("numeric_scale"));
                    col.put("schemaName", rs.getString("table_schema"));
                    col.put("tableName", rs.getString("table_name"));
                    String columnDefault = rs.getString("column_default");
                    if (columnDefault != null) {
                        col.put("columnDefault", columnDefault);
                    } else {
                        col.putNull("columnDefault");
                    }
                    columns.add(col);
                }
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }

        int max = pageSize(request);
        int offset = decodeToken(textOrNull(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TableName", table);
        ArrayNode list = response.putArray("ColumnList");
        int end = Math.min(offset + max, columns.size());
        for (int i = offset; i < end; i++) {
            list.add(columns.get(i));
        }
        if (end < columns.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    private ObjectNode pageStrings(JsonNode request, String key, List<String> values) {
        int max = pageSize(request);
        int offset = decodeToken(textOrNull(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray(key);
        int end = Math.min(offset + max, values.size());
        for (int i = offset; i < end; i++) {
            arr.add(values.get(i));
        }
        if (end < values.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    private ObjectNode pageObjects(JsonNode request, String key, List<ObjectNode> values) {
        int max = pageSize(request);
        int offset = decodeToken(textOrNull(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray(key);
        int end = Math.min(offset + max, values.size());
        for (int i = offset; i < end; i++) {
            arr.add(values.get(i));
        }
        if (end < values.size()) {
            response.put("NextToken", encodeToken(end));
        }
        return response;
    }

    private static int pageSize(JsonNode request) {
        int max = request.path("MaxResults").asInt(PAGE_SIZE);
        return max > 0 ? max : PAGE_SIZE;
    }

    private static String orLike(String value) {
        return value == null || value.isBlank() ? "%" : value;
    }

    private static AwsException databaseError(SQLException e) {
        return new AwsException("ValidationException", e.getMessage(), 400);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private RedshiftDataStatementStore.StoredStatement newStatement(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = new RedshiftDataStatementStore.StoredStatement();
        stored.id = UUID.randomUUID().toString();
        stored.statementName = textOrNull(request, "StatementName");
        Instant now = Instant.now();
        stored.createdAt = now;
        stored.updatedAt = now;
        stored.status = RedshiftDataStatementStore.Status.SUBMITTED;
        return stored;
    }

    private RedshiftDataStatementStore.StoredStatement require(JsonNode request) {
        String id = requiredText(request, "Id");
        RedshiftDataStatementStore.StoredStatement stored = store.get(id);
        if (stored == null) {
            throw new AwsException("ResourceNotFoundException", "Statement " + id + " was not found.", 400);
        }
        return stored;
    }

    private RedshiftDataStatementStore.StoredStatement resultBearing(JsonNode request) {
        RedshiftDataStatementStore.StoredStatement stored = require(request);
        int childSuffix = stored.id.indexOf(':');
        if (childSuffix >= 0) {
            RedshiftDataStatementStore.StoredStatement parent = store.get(stored.id.substring(0, childSuffix));
            if (parent == null || parent.status != RedshiftDataStatementStore.Status.FINISHED) {
                throw new AwsException("ValidationException", "Statement has no result set", 400);
            }
        }
        if (stored.status != RedshiftDataStatementStore.Status.FINISHED || !stored.hasResultSet || stored.rows == null) {
            throw new AwsException("ValidationException", "Statement has no result set", 400);
        }
        return stored;
    }

    private static void rejectMultiStatement(String sql) {
        if (RedshiftDataSqlParameters.isMultiStatement(sql)) {
            throw new AwsException("ValidationException",
                    "A single Sql statement is required; use BatchExecuteStatement for multiple.", 400);
        }
    }

    private static String encodeToken(int offset) {
        return Base64.getEncoder().encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        int offset;
        try {
            offset = Integer.parseInt(new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "NextToken is not valid.", 400);
        }
        if (offset < 0) {
            throw new AwsException("ValidationException", "NextToken is not valid.", 400);
        }
        return offset;
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private String clusterIdentifierOrNull(JsonNode request) {
        return textOrNull(request, "ClusterIdentifier");
    }

    private String workgroupNameOrNull(JsonNode request) {
        return textOrNull(request, "WorkgroupName");
    }

    /** A cluster statement keeps the null member it always had; a workgroup statement names its workgroup. */
    private static void putWorkgroupName(ObjectNode response, RedshiftDataStatementStore.StoredStatement stored) {
        if (stored.workgroupName != null) {
            response.put("WorkgroupName", stored.workgroupName);
        } else {
            response.putNull("WorkgroupName");
        }
    }

    private static List<StoredParameter> storedParameters(Map<String, String> parameters) {
        return parameters.isEmpty() ? null : parameters.entrySet().stream()
                .map(entry -> new StoredParameter(entry.getKey(), entry.getValue())).toList();
    }

    private static void putQueryParameters(ObjectNode target, List<StoredParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        ArrayNode queryParameters = target.putArray("QueryParameters");
        for (StoredParameter parameter : parameters) {
            ObjectNode paramNode = queryParameters.addObject();
            paramNode.put("name", parameter.name());
            if (parameter.value() == null) {
                paramNode.putNull("value");
            } else {
                paramNode.put("value", parameter.value());
            }
        }
    }

    private static String requiredText(JsonNode request, String name) {
        String value = textOrNull(request, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationException", name + " is required.", 400);
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String name) {
        JsonNode node = request.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }
}
