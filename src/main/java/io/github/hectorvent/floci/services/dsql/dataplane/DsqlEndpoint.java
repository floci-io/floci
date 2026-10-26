package io.github.hectorvent.floci.services.dsql.dataplane;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.dsql.DsqlPrincipals;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProviders;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The address every DSQL cluster is reached at: one port, as on AWS, where the server name a client
 * sends picks the cluster. A connection is admitted as Aurora DSQL admits it (measured on AWS,
 * eu-west-1, 2026-10-16): the password is a SigV4 token for the endpoint's host, {@code admin}
 * signs {@code DbConnectAdmin} and every other role {@code DbConnect}, a token is taken up to 5
 * seconds past its expiry, and a role other than {@code admin} logs in only for an IAM role that
 * {@code AWS IAM GRANT} mapped to it. With IAM enforcement on, the caller's policies and the
 * cluster's resource-based policy have to let it connect. Every refusal is the same
 * {@code access denied}, with a hint only where DSQL gives one. DSQL lets clients in while a
 * cluster is still CREATING; Floci's clusters are CREATING only while their database starts, and
 * serve once it answers.
 */
@ApplicationScoped
public class DsqlEndpoint {

    private static final Logger LOG = Logger.getLogger(DsqlEndpoint.class);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(5);
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private static final String ADMIN = "admin";

    private final DsqlTlsCertificates certificates;
    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final Instance<ScpProvider> scpProvider;
    private final Instance<ResourcePolicyProvider> resourcePolicyProviders;
    private final DsqlPrincipals principals;
    private final SigV4RequestValidator validator;
    private final boolean enforcement;
    private final int configuredPort;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<Long, DsqlDatabases.Backend> cancelKeys = new ConcurrentHashMap<>();
    private final Map<String, Set<Socket>> connections = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private final Clock clock;
    // The sessions' age checks, timed by the clock on one thread for the whole endpoint. Each runs on a
    // thread of its own: ending a session waits for its client to take what it is sent, and for its
    // database, and a client that stops reading must not hold back the other sessions' checks.
    private final ScheduledExecutorService ageChecks = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("dsql-age-checks").factory());
    private final ExecutorService ageCheckRuns = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("dsql-age-check-", 0).factory());
    private final Set<ScheduledCheck> pendingChecks = ConcurrentHashMap.newKeySet();
    // DSQL's (measured): a transaction lives 300 seconds, and one idle past that is ended 15 later.
    private static final Duration TRANSACTION_AGE_LIMIT = Duration.ofSeconds(300);
    private static final Duration TRANSACTION_IDLE_GRACE = Duration.ofSeconds(15);

    /** A cluster being served, and where its database answers. */
    record Route(String host, String arn, String region, String accountId, DsqlDatabases.Backend backend) {
    }

    /**
     * Whether a connection may go on, and as whom; when it may not, the SQLSTATE DSQL answers
     * ({@code 08006} for the token, {@code 28000} for the role it would log in as) and its hint.
     */
    record Admission(boolean allowed, String state, String hint, Route route, String principalArn) {

        static Admission refused(String hint) {
            return new Admission(false, "08006", hint, null, null);
        }

        static Admission unauthorized() {
            return new Admission(false, "28000", null, null, null);
        }
    }

    @Inject
    public DsqlEndpoint(DsqlTlsCertificates certificates, IamService iamService, IamPolicyEvaluator policyEvaluator,
                        Instance<ScpProvider> scpProvider, Instance<ResourcePolicyProvider> resourcePolicyProviders,
                        DsqlPrincipals principals, EmulatorConfig config, Clock clock) {
        this.clock = clock;
        this.certificates = certificates;
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.scpProvider = scpProvider;
        this.resourcePolicyProviders = resourcePolicyProviders;
        this.principals = principals;
        this.validator = new SigV4RequestValidator(iamService);
        this.enforcement = config.services().iam().enforcementEnabled();
        this.configuredPort = config.services().dsql().port();
    }

    /** Serves {@code cluster} from {@code backend}, opening the endpoint's port on first use. */
    public void register(DsqlCluster cluster, DsqlDatabases.Backend backend) throws IOException {
        listen();
        String host = cluster.getEndpoint().toLowerCase(Locale.ROOT);
        routes.put(host, new Route(host, cluster.getArn(), cluster.getRegion(), cluster.getAccountId(), backend));
    }

    /** Stops serving {@code cluster} and closes its connections, as deleting a cluster does. */
    public void unregister(DsqlCluster cluster) {
        String host = cluster.getEndpoint().toLowerCase(Locale.ROOT);
        routes.remove(host);
        Set<Socket> open = connections.remove(host);
        if (open != null) {
            open.forEach(DsqlEndpoint::closeQuietly);
        }
    }

    /** The port the endpoint listens on, once it does: the configured one, or the one picked for 0. */
    public int port() {
        ServerSocket listening = server;
        return listening == null ? configuredPort : listening.getLocalPort();
    }

    @PreDestroy
    public synchronized void stop() {
        ageChecks.shutdownNow();
        ageCheckRuns.shutdownNow();
        pendingChecks.clear();
        ServerSocket listening = server;
        server = null;
        if (listening != null) {
            closeQuietly(listening);
        }
        connections.values().forEach(open -> open.forEach(DsqlEndpoint::closeQuietly));
        connections.clear();
        routes.clear();
    }

    private synchronized void listen() throws IOException {
        if (server != null) {
            return;
        }
        ServerSocket listening = new ServerSocket();
        listening.setReuseAddress(true);
        listening.bind(new InetSocketAddress(configuredPort));
        server = listening;
        Thread.ofVirtual().name("dsql-endpoint-accept").start(() -> accept(listening));
        LOG.infov("DSQL endpoint listening on port {0}", String.valueOf(listening.getLocalPort()));
    }

    private void accept(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket client = listening.accept();
                Thread.ofVirtual().name("dsql-endpoint-connection").start(() -> {
                    try {
                        new DsqlSession(client, this).serve();
                    } catch (IOException | RuntimeException e) {
                        LOG.debugv("DSQL connection ended: {0}", e.getMessage());
                    } finally {
                        closeQuietly(client);
                    }
                });
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    LOG.warnv("DSQL endpoint accept failed: {0}", e.getMessage());
                }
            }
        }
    }

    SSLContext sslContext() {
        return certificates.sslContext();
    }

    int handshakeTimeoutMillis() {
        return HANDSHAKE_TIMEOUT_MILLIS;
    }

    /** The time every transaction's age is read by. */
    Clock clock() {
        return clock;
    }

    /** A check that runs once, when the clock reaches its time, unless it is cancelled first. */
    final class ScheduledCheck {

        private final Instant due;
        private final Runnable check;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;

        private ScheduledCheck(Instant due, Runnable check) {
            this.due = due;
            this.check = check;
        }

        void cancel() {
            if (done.compareAndSet(false, true)) {
                pendingChecks.remove(this);
                future.cancel(false);
            }
        }

        private void run() {
            if (done.compareAndSet(false, true)) {
                pendingChecks.remove(this);
                check.run();
            }
        }
    }

    /** Runs {@code check} when the clock reaches {@code due}. */
    ScheduledCheck schedule(Instant due, Runnable check) {
        ScheduledCheck scheduled = new ScheduledCheck(due, check);
        pendingChecks.add(scheduled);
        // toMillis drops what is below a millisecond, which would run the check just before it is due.
        long delay = Math.max(0, Duration.between(clock.instant(), due).toMillis() + 1);
        scheduled.future = ageChecks.schedule(() -> ageCheckRuns.execute(scheduled::run), delay, TimeUnit.MILLISECONDS);
        return scheduled;
    }

    /**
     * Runs every check the clock has reached, and those they schedule that it has reached too: the
     * checks a clock moved forward has made due before their delay ran out.
     */
    void runDueChecks() {
        boolean ran = true;
        while (ran) {
            ran = false;
            Instant now = clock.instant();
            for (ScheduledCheck scheduled : List.copyOf(pendingChecks)) {
                if (!scheduled.due.isAfter(now)) {
                    scheduled.future.cancel(false);
                    scheduled.run();
                    ran = true;
                }
            }
        }
    }

    /** How long a transaction may live. */
    Duration transactionAgeLimit() {
        return TRANSACTION_AGE_LIMIT;
    }

    /** How long past its age limit an idle transaction is let be. */
    Duration transactionIdleGrace() {
        return TRANSACTION_IDLE_GRACE;
    }

    /**
     * Whether {@code token} lets {@code user} in from {@code sourceIp}, checked in the order DSQL
     * reports: the cluster the client named as its TLS server, the action the user needs, the
     * token's expiry, then its signature, the policies with IAM enforcement on and, for a role other
     * than {@code admin}, the IAM role mapped to it.
     */
    Admission admit(String token, String user, String serverName, String sourceIp) {
        URI uri;
        try {
            uri = URI.create("https://" + token);
        } catch (IllegalArgumentException ignored) {
            // A password that is no token is refused without a hint, as DSQL refuses one.
            return Admission.refused(null);
        }
        String host = uri.getHost();
        String rawQuery = uri.getRawQuery();
        if (host == null || rawQuery == null || uri.getPort() != -1) {
            return Admission.refused(null);
        }
        host = host.toLowerCase(Locale.ROOT);
        Route route = routes.get(serverName.toLowerCase(Locale.ROOT));
        if (route == null || !route.host().equals(host)) {
            return Admission.refused(null);
        }

        String action = SigV4RequestValidator.queryParameter(rawQuery, "Action");
        String needed = ADMIN.equals(user) ? "DbConnectAdmin" : "DbConnect";
        if (!needed.equals(action)) {
            boolean dsqlAction = "DbConnectAdmin".equals(action) || "DbConnect".equals(action);
            return Admission.refused(dsqlAction
                    ? "Wrong user to action mapping. user: " + user + ", action: " + action : null);
        }

        String expiredHint = expiredHint(rawQuery);
        if (expiredHint != null) {
            return Admission.refused(expiredHint);
        }

        String[] scope = credentialScope(rawQuery);
        if (scope == null || !route.region().equals(scope[2]) || !"dsql".equals(scope[3])) {
            return Admission.refused(null);
        }
        // The endpoint checks tokens outside any request, so the key is looked up in the account
        // that owns the cluster, as ElastiCache's proxy does: a long-term key of another account
        // never signs in, and one of the cluster's own account does whichever account is Floci's default.
        if (!validator.validate(rawQuery, host, needed, "DBUser", false, null, "DSQL IAM token", route.accountId(),
                EXPIRY_GRACE)) {
            return Admission.refused(null);
        }

        String accessKeyId = scope[0];
        // DSQL takes no cross-account access yet (its resource-based policy guide), so credentials of
        // another account are refused like any other token, a session's included.
        if (!route.accountId().equals(iamService.resolveAccountId(accessKeyId).orElse(route.accountId()))) {
            return Admission.refused(null);
        }
        // The endpoint runs outside any request: the caller's identity and policies are read in the
        // cluster's account, as the enforcement filter reads them in the request's.
        Admission identified = RequestScopes.callAs(route.accountId(), route.region(), () -> {
            Admission refused = enforcement ? refusedByIam(accessKeyId, "dsql:" + needed, route, sourceIp) : null;
            return refused != null ? refused : new Admission(true, null, null, route,
                    iamService.resolvePrincipalArn(accessKeyId).orElseGet(() -> AwsArnUtils.Arn.global(
                            AwsRegions.partitionFor(route.region()), "iam", route.accountId(), "root").toString()));
        });
        if (!identified.allowed()) {
            return identified;
        }
        String principal = identified.principalArn();
        if (!ADMIN.equals(user)) {
            try {
                if (!mapped(route.backend(), principal, user)) {
                    return Admission.unauthorized();
                }
            } catch (SQLException e) {
                // A database that cannot be asked says nothing about the role: the connection fails
                // as one the cluster cannot serve, not as a role refused.
                LOG.warn("Could not read the IAM role mappings of a DSQL cluster", e);
                return Admission.refused(null);
            }
        }
        return new Admission(true, null, null, route, principal);
    }

    /**
     * DSQL's hint for a token past its expiry and the 5 seconds of grace after it:
     * {@code Signature expired: <expiry> is now earlier than <now - 5 s> (<now> - 0 min.)}.
     */
    private static String expiredHint(String rawQuery) {
        // A token without an expiry that parses gets no hint here: the signature check that
        // follows refuses it without one, as DSQL does.
        Optional<Instant> expiry = SigV4RequestValidator.expiry(rawQuery);
        Instant now = Instant.now();
        Instant earliest = now.minus(EXPIRY_GRACE);
        if (expiry.isEmpty() || !expiry.get().isBefore(earliest)) {
            return null;
        }
        return "Signature expired: " + AMZ_DATE.format(expiry.get()) + " is now earlier than "
                + AMZ_DATE.format(earliest) + " (" + AMZ_DATE.format(now) + " - 0 min.)";
    }

    /**
     * The refusal DSQL gives unless the caller's policies, under its organization's service control
     * policies, and the cluster's resource-based policy let it {@code action} the cluster, or null when
     * they do. DSQL evaluates the cluster's policy on connect, with the client's address as
     * {@code aws:SourceIp}, and hints only at an explicit Deny in it (measured). A known credential
     * with no policies to evaluate is let through, as the enforcement filter lets it.
     */
    private Admission refusedByIam(String accessKeyId, String action, Route route, String sourceIp) {
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            return null;
        }
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(route.accountId()) : null;
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }
        CallerArns arns = principals.callerOf(accessKeyId);
        List<String> clusterPolicies = ResourcePolicyProviders.policiesFor(resourcePolicyProviders, "dsql",
                route.arn()).stream().map(ResourcePolicyProvider.ResourcePolicy::policyDocument).toList();
        Map<String, List<String>> context = principals.policyContext(arns, route.arn(), route.region(),
                Map.of("aws:SourceIp", List.of(sourceIp)));
        ResourcePolicyDecision clusterPolicy = policyEvaluator.evaluateResourcePolicyFor(clusterPolicies,
                RequestPrincipal.caller(arns), action, route.arn(), context);
        if (policyEvaluator.evaluateResolvedResourcePolicy(caller, clusterPolicy,
                ResourceAccountRelationship.SAME_ACCOUNT, action, route.arn(), context) == Decision.ALLOW) {
            return null;
        }
        return Admission.refused(clusterPolicy == ResourcePolicyDecision.EXPLICIT_DENY
                ? DsqlPrincipals.notAuthorized(arns.callerArn(), action, route.arn(),
                        DsqlPrincipals.EXPLICIT_RESOURCE_DENY)
                : null);
    }

    /**
     * Whether {@code AWS IAM GRANT} mapped the IAM role {@code principal} to {@code role}. A
     * superuser is never one, as DSQL never lets {@code dbowner} in.
     */
    private static boolean mapped(DsqlDatabases.Backend backend, String principal, String role) throws SQLException {
        try (Connection connection = DsqlDatabases.superuser(backend);
             PreparedStatement query = connection.prepareStatement("SELECT 1 FROM sys.iam_pg_role_mappings m"
                     + " JOIN pg_roles r ON r.oid = m.pg_role_oid WHERE m.arn = ? AND m.pg_role_name = ?"
                     + " AND NOT r.rolsuper")) {
            query.setString(1, principal);
            query.setString(2, role);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Records who the session on backend process {@code pid} is, for {@code sys.iam_session_user()}
     * and {@code sys.current_session_id()}. The superuser writes it into a table no role of the
     * cluster can write, so nothing the session runs can change it, and drops the rows of sessions
     * that have ended. False, with the cause logged, when the database could not be written.
     */
    boolean recordSession(DsqlDatabases.Backend backend, int pid, String principal, String sessionId) {
        try (Connection connection = DsqlDatabases.superuser(backend);
             PreparedStatement purge = connection.prepareStatement("DELETE FROM sys.session_identity"
                     + " WHERE pid NOT IN (SELECT pid FROM pg_stat_activity WHERE pid IS NOT NULL)");
             PreparedStatement record = connection.prepareStatement("INSERT INTO sys.session_identity"
                     + " (pid, iam_session_user, session_id) VALUES (?, ?, ?) ON CONFLICT (pid) DO UPDATE"
                     + " SET iam_session_user = EXCLUDED.iam_session_user, session_id = EXCLUDED.session_id")) {
            purge.executeUpdate();
            record.setInt(1, pid);
            record.setString(2, principal);
            record.setString(3, sessionId);
            record.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.warn("Could not record the identity of a DSQL session", e);
            return false;
        }
    }

    private static String[] credentialScope(String rawQuery) {
        String credential = SigV4RequestValidator.queryParameter(rawQuery, "X-Amz-Credential");
        if (credential == null) {
            return null;
        }
        String[] parts = credential.split("/");
        return parts.length >= 5 ? parts : null;
    }

    void opened(Route route, Socket socket) {
        connections.computeIfAbsent(route.host(), host -> ConcurrentHashMap.newKeySet()).add(socket);
        if (!routes.containsKey(route.host())) {
            closeQuietly(socket);
        }
    }

    void closed(Route route, Socket socket) {
        Set<Socket> open = connections.get(route.host());
        if (open != null) {
            open.remove(socket);
        }
    }

    /** Remembers which database a BackendKeyData came from, so a CancelRequest can reach it. */
    long registerCancelKey(byte[] keyData, DsqlDatabases.Backend backend) {
        long key = ByteBuffer.wrap(keyData).getLong(0);
        cancelKeys.put(key, backend);
        return key;
    }

    void forgetCancelKey(long key) {
        cancelKeys.remove(key);
    }

    /** Hands a CancelRequest's process id and secret to the database that issued them. */
    void forwardCancel(byte[] keyData) {
        if (keyData.length < 8) {
            return;
        }
        DsqlDatabases.Backend backend = cancelKeys.get(ByteBuffer.wrap(keyData).getLong(0));
        if (backend == null) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.host(), backend.port()), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(ByteBuffer.allocate(16).putInt(16).putInt(PostgresWire.CANCEL_REQUEST)
                    .put(keyData, 0, 8).array());
            out.flush();
        } catch (IOException e) {
            LOG.debugv("Could not forward a DSQL cancel request: {0}", e.getMessage());
        }
    }

    private static void closeQuietly(AutoCloseable closing) {
        try {
            closing.close();
        } catch (Exception ignored) {
            // Already closed.
        }
    }
}
