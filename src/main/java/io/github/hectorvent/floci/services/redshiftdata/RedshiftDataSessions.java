package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditSql.UserChange;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditSql.UserChanges;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@ApplicationScoped
class RedshiftDataSessions implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedshiftDataSessions.class);
    private static final Pattern SESSION_ID = Pattern.compile("[a-z0-9]{8}(-[a-z0-9]{4}){3}-[a-z0-9]{12}(:\\d{0,2})?");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final List<String> TARGET_FIELDS = List.of("ClusterIdentifier", "WorkgroupName", "Database", "DbUser", "SecretArn");

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionInfo> history = new ConcurrentHashMap<>();
    private final Clock clock;
    private ScheduledExecutorService sweeper;
    private boolean closed;

    RedshiftDataSessions() {
        this(Clock.systemUTC());
    }

    RedshiftDataSessions(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "redshift-data-session-sweep");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleAtFixedRate(this::sweepSafely, 1, 1, TimeUnit.SECONDS);
    }

    static void validate(JsonNode request) {
        keepAlive(request);
        JsonNode id = request.get("SessionId");
        if (id != null && (!id.isTextual() || !SESSION_ID.matcher(id.textValue()).matches())) {
            throw validation("SessionId must be a valid session identifier");
        }
    }

    private static Integer keepAlive(JsonNode request) {
        JsonNode seconds = request.get("SessionKeepAliveSeconds");
        if (seconds == null) {
            return null;
        }
        if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.intValue() < 0 || seconds.intValue() > 86400) {
            throw validation("SessionKeepAliveSeconds must be an integer between 0 and 86400");
        }
        return seconds.intValue();
    }

    Session find(Scope owner, String id) {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) {
            throw missing();
        }
        synchronized (session) {
            if (!session.closed && !expired(session)) {
                return session;
            }
        }
        retire(session);
        throw missing();
    }

    Reservation reserve(Scope owner, JsonNode request, RedshiftDataResourceResolver.DatabaseTarget target) {
        Integer seconds = keepAlive(request);
        String id = request.has("SessionId") ? request.get("SessionId").asText() : null;
        if (id == null && (seconds == null || seconds == 0)) {
            return null;
        }
        Session session;
        boolean created = id == null;
        if (created) {
            sweepSafely();
            synchronized (this) {
                if (closed) {
                    throw validation("Data API sessions are shutting down");
                }
                long count = sessions.values().stream().filter(existing -> existing.target.arn().equals(target.arn())
                        && existing.owner.accountId().equals(owner.accountId()) && existing.owner.region().equals(owner.region())).count();
                if (count >= 500) {
                    throw new AwsException("ActiveSessionsExceededException", "Too many active sessions for this resource", 400);
                }
                session = new Session(owner, target, request, clock.instant());
                sessions.put(session.id, session);
            }
        } else {
            session = find(owner, id);
        }
        synchronized (session) {
            if (session.closed || expired(session)) {
                throw missing();
            }
            if (session.active != null) {
                throw validation("Session is busy; wait for its current statement to finish");
            }
            Reservation reservation = new Reservation(session, created, seconds == null ? session.keepAlive : seconds);
            session.active = reservation;
            return reservation;
        }
    }

    void sweep() {
        history.values().removeIf(info -> !clock.instant().isBefore(info.createdAt().plus(MAX_AGE)));
        RuntimeException failure = null;
        for (Session session : sessions.values()) {
            boolean expire;
            synchronized (session) {
                expire = expired(session);
            }
            if (expire) {
                try {
                    retire(session);
                } catch (RuntimeException cleanupFailure) {
                    failure = accumulate(failure, cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException failure) {
            // Maintenance has no request to report to; keep future sweeps running and report the failure.
            LOG.errorv(failure, "Failed to clean up expired Redshift Data API sessions");
        }
    }

    private boolean expired(Session session) {
        Instant now = clock.instant();
        return !now.isBefore(session.createdAt.plus(MAX_AGE))
                || session.active == null && !now.isBefore(session.idleUntil);
    }

    private void retire(Session session) {
        retire(session, true);
    }

    private void retire(Session session, boolean cancel) {
        Retirement retirement;
        synchronized (session) {
            retirement = retireLocked(session, cancel);
        }
        cleanUp(retirement);
    }

    private Retirement retireLocked(Session session, boolean cancel) {
        if (session.closed) {
            return null;
        }
        session.closed = true;
        session.updatedAt = expired(session) ? session.deadline() : clock.instant();
        if (session.admitted && clock.instant().isBefore(session.createdAt.plus(MAX_AGE))) {
            history.put(session.id, session.info());
        }
        sessions.remove(session.id, session);
        Retirement retirement = new Retirement(session.id, session.connection, cancel ? session.execution : null,
                session.connectionClosed);
        session.active = null;
        session.execution = null;
        session.connection = null;
        session.userChanges.rollback();
        return retirement;
    }

    private record Retirement(String id, Connection connection, RedshiftDataExecution execution, Runnable connectionClosed) { }

    private static void cleanUp(Retirement retirement) {
        if (retirement == null) {
            return;
        }
        Runnable cleanup = () -> {
            try {
                if (retirement.execution() != null) {
                    retirement.execution().cancel();
                }
            } finally {
                try {
                    closeConnection(retirement.connection(), retirement.id());
                } finally {
                    if (retirement.connection() != null && retirement.connectionClosed() != null) {
                        retirement.connectionClosed().run();
                    }
                }
            }
        };
        if (retirement.execution() == null) {
            cleanup.run();
        } else {
            Thread.startVirtualThread(() -> {
                try {
                    cleanup.run();
                } catch (RuntimeException failure) {
                    // Expiration/reset cancellation finishes asynchronously after the session is unavailable.
                    LOG.errorv(failure, "Failed to clean up Redshift Data API session {0}", retirement.id());
                }
            });
        }
    }

    private static void closeConnection(Connection connection, String id) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                if (failure instanceof SQLException sql && "08003".equals(sql.getSQLState())) {
                    LOG.debugv("Redshift Data API session {0} connection was already closed", id);
                } else {
                    throw new IllegalStateException("Unable to close Redshift Data API session " + id + ": " + failure.getMessage(), failure);
                }
            }
        }
    }

    synchronized void clear() {
        RuntimeException failure = null;
        for (Session session : sessions.values()) {
            try {
                retire(session);
            } catch (RuntimeException cleanupFailure) {
                failure = accumulate(failure, cleanupFailure);
            }
        }
        history.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException accumulate(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        clear();
    }

    List<SessionInfo> snapshots() {
        sweepSafely();
        ConcurrentHashMap<String, SessionInfo> result = new ConcurrentHashMap<>();
        for (Session session : sessions.values()) {
            synchronized (session) {
                if (session.admitted && clock.instant().isBefore(session.createdAt.plus(MAX_AGE))) {
                    result.put(session.id, session.info());
                }
            }
        }
        result.putAll(history);
        return new ArrayList<>(result.values());
    }

    record Scope(String accountId, String region, String caller, String principal) {
        Scope(String accountId, String region, String caller) {
            this(accountId, region, caller, caller);
        }
    }

    record SessionInfo(String id, Scope owner, String targetArn, String cluster, String workgroup,
                       String database, String user, Instant createdAt, Instant updatedAt,
                       Instant ttl, int keepAlive, String status, String statementId) { }

    static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final Scope owner;
        private final RedshiftDataResourceResolver.DatabaseTarget target;
        private final ObjectNode parameters;
        private final Instant createdAt;
        private Instant updatedAt;
        private boolean admitted;
        private Instant idleUntil;
        private int keepAlive;
        private boolean closed;
        private boolean transaction;
        private boolean transactionFailed;
        private Connection connection;
        private RedshiftDataExecution execution;
        private Reservation active;
        private Runnable connectionClosed;
        private UserChanges userChanges = new UserChanges();

        private Session(Scope owner, RedshiftDataResourceResolver.DatabaseTarget target, JsonNode request, Instant createdAt) {
            this.owner = owner;
            this.target = target;
            this.parameters = ((ObjectNode) request).objectNode();
            for (String field : TARGET_FIELDS) {
                if (request.has(field)) {
                    parameters.set(field, request.get(field).deepCopy());
                }
            }
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.idleUntil = createdAt.plus(MAX_AGE);
        }

        private Instant deadline() {
            Instant maximum = createdAt.plus(MAX_AGE);
            return active != null || maximum.isBefore(idleUntil) ? maximum : idleUntil;
        }

        private SessionInfo info() {
            boolean busy = !closed && active != null;
            AwsArnUtils.Arn arn = AwsArnUtils.parse(target.arn());
            boolean serverless = "redshift-serverless".equals(arn.service());
            String cluster = serverless ? null : arn.resource().substring("cluster:".length());
            String workgroup = serverless ? target.workgroupName() != null ? target.workgroupName()
                    : parameters.path("WorkgroupName").asText(target.arn()) : null;
            String user = parameters.path("DbUser").asText(target.auditUser());
            return new SessionInfo(id, owner, target.arn(), cluster, workgroup, target.database(), user,
                    createdAt, updatedAt, closed ? updatedAt : deadline(), busy ? active.seconds : keepAlive,
                    closed ? "CLOSED" : busy ? "BUSY" : "AVAILABLE", busy ? active.statementId : null);
        }

        RedshiftDataResourceResolver.DatabaseTarget target(JsonNode request) {
            for (String field : TARGET_FIELDS) {
                JsonNode value = request.get(field);
                if (value != null && !Objects.equals(value, parameters.get(field))
                        && !("WorkgroupName".equals(field) && value.isTextual() && target.arn().equals(value.textValue()))) {
                    throw validation(field + " does not match the session");
                }
            }
            return target;
        }

        ObjectNode parameters() {
            return parameters.deepCopy();
        }
    }

    final class Reservation {
        private final Session session;
        private final boolean created;
        private final int seconds;
        private final AtomicBoolean released = new AtomicBoolean();
        private boolean admitted;
        private String statementId;

        private Reservation(Session session, boolean created, int seconds) {
            this.session = session;
            this.created = created;
            this.seconds = seconds;
        }

        String id() {
            return session.id;
        }

        void admitted() {
            admitted(null);
        }

        void admitted(String id) {
            synchronized (session) {
                admitted = true;
                statementId = id;
                session.admitted = true;
                session.updatedAt = clock.instant();
            }
        }

        void bind(RedshiftDataExecution execution) throws SQLException {
            synchronized (session) {
                if (session.closed || expired(session)) {
                    throw new SQLException("Session has expired", "08003");
                }
                session.execution = execution;
            }
        }

        Connection connection(RedshiftDataConnectionFactory factory) throws SQLException {
            return connection(factory, () -> { }, () -> { });
        }

        Connection connection(RedshiftDataConnectionFactory factory, Runnable openedAudit, Runnable closedAudit) throws SQLException {
            synchronized (session) {
                if (session.closed) {
                    throw new SQLException("Session has expired", "08003");
                }
                if (session.connection != null) {
                    return session.connection;
                }
            }
            Connection opened = factory.open(session.target);
            openedAudit.run();
            synchronized (session) {
                if (!session.closed && !expired(session)) {
                    session.connection = opened;
                    session.connectionClosed = closedAudit;
                    return opened;
                }
            }
            try {
                closeConnection(opened, session.id);
            } finally {
                closedAudit.run();
            }
            throw new SQLException("Session has expired", "08003");
        }

        boolean inTransaction() {
            return session.transaction;
        }

        void transaction(boolean active) {
            session.transaction = active;
            if (!active) {
                session.transactionFailed = false;
            }
        }

        boolean transactionFailed() {
            return session.transactionFailed;
        }

        void failTransaction() {
            session.transactionFailed = true;
        }

        void completeUserChanges(String sql) {
            synchronized (session) {
                if (!session.closed) {
                    session.userChanges.complete(sql);
                }
            }
        }

        List<UserChange> takeUserChanges() {
            UserChanges changes = takeUserChangeState();
            changes.commit();
            return changes.drainCommitted();
        }

        UserChanges takeUserChangeState() {
            synchronized (session) {
                UserChanges changes = session.userChanges;
                session.userChanges = new UserChanges();
                return changes;
            }
        }

        void abort() {
            release(false);
        }

        void finish() {
            release(admitted);
        }

        void discard() {
            retire(session, false);
        }

        private void release(boolean completed) {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            Retirement retirement = null;
            synchronized (session) {
                if (session.active != this) {
                    return;
                }
                boolean remove = !completed && created || completed && (seconds == 0 || session.connection == null);
                if (completed) {
                    session.keepAlive = seconds;
                    session.updatedAt = clock.instant();
                }
                if (remove) {
                    retirement = retireLocked(session, false);
                } else {
                    session.active = null;
                    session.execution = null;
                    if (completed) {
                        session.keepAlive = seconds;
                        session.idleUntil = clock.instant().plusSeconds(seconds);
                    }
                }
            }
            cleanUp(retirement);
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException missing() {
        return new AwsException("ResourceNotFoundException", "Session was not found or is no longer available", 400);
    }
}
