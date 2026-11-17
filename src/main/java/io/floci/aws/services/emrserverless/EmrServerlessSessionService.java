package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.core.common.SparkConnectProxy;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.SessionRecord;
import io.floci.aws.services.emrserverless.model.SessionSummary;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * EMR Serverless sessions: "a managed connection between your local PySpark client and a Spark driver running on Amazon
 * EMR Serverless", through Spark Connect (EMR Serverless user guide, "Run interactive sessions with Amazon EMR
 * Serverless through Spark Connect"). A session ends when it is terminated, when its idle timeout or its 24 hours pass,
 * or when its application stops.
 *
 * <p>In the docker runner mode a session runs a Spark Connect server, its driver, in a container, as a job run's driver
 * runs ({@link EmrServerlessJobRunService#sessionJob}): it is SUBMITTED, then STARTING once its container runs, and
 * IDLE once the server answers. A client reaches the server through {@link SparkConnectProxy}, with a token
 * GetSessionEndpoint gave; the session is BUSY while a call runs there, and IDLE again, its idle timeout counting from
 * then, once none does. A server that exits on its own fails its session. In the immediate runner mode no server runs:
 * a session is IDLE once started, and a call to it is refused.
 */
@ApplicationScoped
public class EmrServerlessSessionService implements SparkConnectTargets, Resettable {

    private static final Logger LOG = Logger.getLogger(EmrServerlessSessionService.class);

    static final String STATE_SUBMITTED = "SUBMITTED";
    static final String STATE_STARTING = "STARTING";
    static final String STATE_IDLE = "IDLE";
    static final String STATE_BUSY = "BUSY";
    static final String STATE_FAILED = "FAILED";
    static final String STATE_TERMINATING = "TERMINATING";
    static final String STATE_TERMINATED = "TERMINATED";
    private static final List<String> STATES = List.of(STATE_SUBMITTED, STATE_STARTING, "STARTED", STATE_IDLE,
            STATE_BUSY, STATE_FAILED, STATE_TERMINATING, STATE_TERMINATED);
    private static final Set<String> ENDED = Set.of(STATE_FAILED, STATE_TERMINATED);
    // The port a session's Spark Connect server listens on in its container, Spark Connect's own default.
    static final int SPARK_CONNECT_PORT = 15002;
    // How long a server may take to answer once its container runs, and how often it is asked meanwhile.
    static final Duration SERVER_START_LIMIT = Duration.ofMinutes(10);
    private static final long SERVER_PROBE_INTERVAL_MILLIS = 500;
    private static final List<String> RESOURCE_TYPES = List.of("SESSION");
    // SessionStateSet holds 1 to 8 states; a ListSessions page holds 1 to 50 sessions.
    private static final int MAX_FILTER_STATES = 8;
    private static final int MAX_LIST_RESULTS = 50;
    // "Each EMR Serverless application supports up to 25 concurrent sessions by default."
    static final int MAX_CONCURRENT_SESSIONS = 25;
    // "Sessions end after a configurable idle timeout. Default timeout is set to 1 hour."
    static final long DEFAULT_IDLE_TIMEOUT_MINUTES = 60;
    private static final long MAX_IDLE_TIMEOUT_MINUTES = 1_000_000;
    // "Each session has a hard limit of 24 hours by default, after which it auto-terminates even if it is actively
    // running a task."
    static final Duration HARD_LIMIT = Duration.ofHours(24);
    // "Authentication tokens are time-limited to 1 hour."
    static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    private static final String SPARK_TYPE = "SPARK";
    // No source gives AWS's wording for these ends; they are Floci's.
    static final String IDLE_TIMED_OUT = "The session was idle for its idle timeout.";
    static final String HARD_LIMIT_REACHED = "The session reached its 24 hour limit.";
    static final String APPLICATION_STOPPED = "The session's application was stopped.";
    static final String INTERRUPTED = "Floci stopped while the session was running.";
    static final String SERVER_EXITED = "The session's Spark Connect server exited with code %d.";
    static final String SERVER_NOT_STARTED = "The session's Spark Connect server could not start: %s";
    static final String SERVER_SILENT = "The session's Spark Connect server did not answer within "
            + SERVER_START_LIMIT.toMinutes() + " minutes: %s";
    static final String SERVER_ENDED = "The session's Spark Connect server ended with no exit code.";
    static final String TOKEN_EXPIRED = "The authentication token has expired. Call GetSessionEndpoint for a new one.";
    static final String NO_SERVER = "Floci runs no Spark Connect server for session %s, as the EMR Serverless runner"
            + " mode is immediate; floci.services.emrserverless.runner-mode docker runs one.";
    static final String NOT_READY = "Session %s is %s, not ready for calls.";

    private final AccountAwareStorageBackend<SessionRecord> store;
    private final EmrServerlessService applications;
    private final EmrServerlessJobRunService jobRuns;
    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String baseUrl;
    // The docker runner mode's runner, which runs each session's Spark Connect server; null in the immediate mode.
    private final SparkJobRunner sparkRunner;
    private final Executor serverExecutor;
    // Ends each session at its next deadline, its idle timeout or its 24 hours, and its server with it.
    private final ScheduledExecutorService idleChecks;
    private final ServerProbe probe;
    // The sessions whose server a thread of this process runs; one not here that has not ended was left by a restart.
    private final Map<String, Server> servers = new HashMap<>();
    // The authentication tokens GetSessionEndpoint gave, until they expire or their session ends.
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    // Raised by each reset, so a server's thread from before it writes nothing back after the wipe.
    private long generation;

    @Inject
    public EmrServerlessSessionService(StorageFactory storageFactory, EmrServerlessService applications,
                                       EmrServerlessJobRunService jobRuns, IamService iamService,
                                       RegionResolver regionResolver, RequestContext requestContext,
                                       ObjectMapper objectMapper, EmulatorConfig config, Clock clock,
                                       SparkConnectProxy proxy) {
        this(storageFactory.create("emrserverless", "emr-serverless-sessions.json", new TypeReference<>() {}),
                applications, jobRuns, iamService, regionResolver, requestContext, objectMapper, clock,
                config.effectiveBaseUrl(), jobRuns.containerRunner(),
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-serverless-session-", 0)
                        .factory()),
                sessionChecks(),
                (host, port) -> unanswered(proxy, host, port));
    }

    EmrServerlessSessionService(AccountAwareStorageBackend<SessionRecord> store, EmrServerlessService applications,
                                EmrServerlessJobRunService jobRuns, IamService iamService,
                                RegionResolver regionResolver, RequestContext requestContext,
                                ObjectMapper objectMapper, Clock clock, String baseUrl) {
        this(store, applications, jobRuns, iamService, regionResolver, requestContext, objectMapper, clock, baseUrl,
                null, Runnable::run, null, (host, port) -> "no server runs");
    }

    EmrServerlessSessionService(AccountAwareStorageBackend<SessionRecord> store, EmrServerlessService applications,
                                EmrServerlessJobRunService jobRuns, IamService iamService,
                                RegionResolver regionResolver, RequestContext requestContext,
                                ObjectMapper objectMapper, Clock clock, String baseUrl, SparkJobRunner sparkRunner,
                                Executor serverExecutor, ScheduledExecutorService idleChecks, ServerProbe probe) {
        this.sparkRunner = sparkRunner;
        this.serverExecutor = serverExecutor;
        this.idleChecks = idleChecks;
        this.probe = probe;
        this.store = store;
        this.applications = applications;
        this.jobRuns = jobRuns;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @PreDestroy
    void shutdown() {
        if (serverExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
        if (idleChecks != null) {
            idleChecks.shutdownNow();
        }
    }

    /**
     * A reset has begun: the servers' threads from before it write nothing more, and their tokens go. The runner's
     * teardown stops their containers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        servers.clear();
        grants.clear();
    }

    @Override
    public void clear() {
        // The sessions live in storage, which the reset wipes.
    }

    /**
     * Starts a session, which "The application must be in the STARTED state or have AutoStart enabled, and have
     * interactive sessions enabled" for, on "EMR release 7.13.0 and later", and, as "Spark Connect is supported only
     * for the Apache Spark engine", on a Spark application. A retry with the same client token returns the session it
     * started, and one whose request differs is refused, as StartJobRun's is. An application holds at most
     * {@link #MAX_CONCURRENT_SESSIONS} sessions that have not ended.
     */
    public synchronized Session startSession(String applicationId, StartSessionRequest request) {
        StartSessionRequest body = request != null ? request : new StartSessionRequest();
        validate(body);
        applications.getApplication(applicationId);
        JsonNode original = EmrServerlessService.retriedRequest(objectMapper, applicationId, body);
        SessionRecord existing = EmrServerlessService.startedWithClientToken(store.scan(key -> true),
                body.getClientToken(), SessionRecord::getClientToken, record -> inRequestRegion(record.getSession()));
        if (existing != null) {
            if (!original.equals(existing.getRequest())) {
                throw new AwsException("ConflictException", "Request did not match the original", 409);
            }
            return copy(settle(existing).getSession());
        }
        // Before admission, which may auto-start the application: a refused session leaves it as it was.
        if (body.getTags() != null) {
            EmrServerlessService.validateTags(body.getTags());
        }
        SessionRecord record = applications.admit(applicationId, "a session is started", this::checkAdmissible,
                app -> newSession(app, body, original));
        LOG.infov("Started EMR Serverless session {0} on application {1}", record.getSession().getSessionId(),
                applicationId);
        return copy(record.getSession());
    }

    public synchronized Session getSession(String applicationId, String sessionId) {
        applications.getApplication(applicationId);
        return copy(settle(findSession(applicationId, sessionId)).getSession());
    }

    /**
     * The application's sessions in the request's Region, newest first, grouped by state as ListJobRuns' runs are.
     */
    public synchronized PaginatedResult<SessionSummary> listSessions(String applicationId, List<String> states,
                                                                     Instant createdAtAfter, Instant createdAtBefore,
                                                                     Integer maxResults, String nextToken) {
        List<String> violations = EmrServerlessService.listViolations(maxResults, MAX_LIST_RESULTS, states, STATES,
                MAX_FILTER_STATES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        applications.getApplication(applicationId);
        List<Session> matching = new ArrayList<>();
        for (SessionRecord record : store.scan(key -> true)) {
            Session session = record.getSession();
            if (!applicationId.equals(session.getApplicationId()) || !inRequestRegion(session)) {
                continue;
            }
            settle(record);
            if ((states != null && !states.isEmpty() && !states.contains(session.getState()))
                    || (createdAtAfter != null && !session.getCreatedAt().isAfter(createdAtAfter))
                    || (createdAtBefore != null && !session.getCreatedAt().isBefore(createdAtBefore))) {
                continue;
            }
            matching.add(session);
        }
        String namespace = String.join("|", "ListSessions", applicationId,
                String.valueOf(states != null ? states : List.of()), String.valueOf(createdAtAfter),
                String.valueOf(createdAtBefore));
        PaginatedResult<Session> page = EmrServerlessService.pageByState(matching, Session::getSessionId,
                Session::getState, Session::getCreatedAt, states, maxResults, MAX_LIST_RESULTS, nextToken, namespace);
        return new PaginatedResult<>(page.items().stream().map(SessionSummary::of).toList(), page.nextToken());
    }

    /**
     * Terminates a session that has not ended: "After you terminate a session, it enters the TERMINATING state and
     * then the TERMINATED state." One whose server runs is TERMINATING until its container has stopped; with no
     * server, it is TERMINATED at once. One that has already ended is refused; no source gives AWS's answer for it,
     * so the wording is Floci's.
     */
    public synchronized Session terminateSession(String applicationId, String sessionId) {
        applications.getApplication(applicationId);
        SessionRecord record = settle(findSession(applicationId, sessionId));
        if (ENDED.contains(record.getSession().getState())) {
            throw new AwsException("ValidationException", "Session " + sessionId + " has already ended.", 400);
        }
        Server server = servers.get(sessionId);
        if (server != null) {
            cancelCheck(server);
            Session session = record.getSession();
            session.setState(STATE_TERMINATING);
            session.setStateDetails("");
            session.setIdleSince(null);
            session.setUpdatedAt(now());
            store.put(sessionId, record);
            dropGrants(sessionId);
            sparkRunner.stop(sessionId);
            LOG.infov("Terminating EMR Serverless session {0} on application {1}", sessionId, applicationId);
            return copy(session);
        }
        end(record, STATE_TERMINATED, null, now());
        LOG.infov("Terminated EMR Serverless session {0} on application {1}", sessionId, applicationId);
        return copy(record.getSession());
    }

    /**
     * Where a client connects to a session that has not ended, with an authentication token, which is "time-limited
     * to 1 hour". The endpoint is Floci's own address, where {@link SparkConnectProxy} takes a call carrying the token
     * on to the session's server: Spark Connect's {@code sc://} URL holds a host and a port but no path, so the token
     * alone names the session. No source gives AWS's answer for a session that has ended, so the refusal's wording is
     * Floci's.
     */
    public synchronized SparkConnectEndpoint sessionEndpoint(String applicationId, String sessionId) {
        applications.getApplication(applicationId);
        Session session = settle(findSession(applicationId, sessionId)).getSession();
        if (ENDED.contains(session.getState()) || STATE_TERMINATING.equals(session.getState())) {
            throw new AwsException("ValidationException", "Session " + sessionId + " has ended.", 400);
        }
        Instant now = now();
        SparkConnectEndpoint endpoint = SparkConnectEndpoint.issue(baseUrl, "", now, TOKEN_VALIDITY);
        grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt()));
        AwsArnUtils.Arn arn = AwsArnUtils.parse(session.getArn());
        grants.put(endpoint.authToken(), new Grant(arn.accountId(), arn.region(), sessionId,
                endpoint.authTokenExpiresAt()));
        return endpoint;
    }

    /**
     * Where a Spark Connect call carrying {@code token} goes: the server of the session the token was given for, while
     * the token has not expired and the session is IDLE or BUSY. The call makes the session BUSY until it ends. No
     * source gives AWS's answer for a session that has not started or has ended, so the refusals' wording is Floci's.
     */
    @Override
    public Optional<Route> route(String token) {
        Grant grant = grants.get(token);
        if (grant == null) {
            return Optional.empty();
        }
        return Optional.of(RequestScopes.callAs(grant.accountId(), grant.region(), () -> callStarted(token, grant)));
    }

    private synchronized Route callStarted(String token, Grant grant) {
        if (!clock.instant().isBefore(grant.expiresAt())) {
            grants.remove(token);
            return Route.refused(Route.UNAUTHENTICATED, TOKEN_EXPIRED);
        }
        SessionRecord record = store.get(grant.sessionId()).orElse(null);
        if (record == null) {
            grants.remove(token);
            return Route.refused(Route.UNAUTHENTICATED, SparkConnectProxy.UNKNOWN_TOKEN);
        }
        Session session = settle(record).getSession();
        if (!record.isContainerSession()) {
            return Route.refused(Route.UNAVAILABLE, String.format(NO_SERVER, grant.sessionId()));
        }
        Server server = servers.get(grant.sessionId());
        boolean ready = STATE_IDLE.equals(session.getState()) || STATE_BUSY.equals(session.getState());
        if (server == null || server.host == null || !ready) {
            return Route.refused(Route.UNAVAILABLE, String.format(NOT_READY, grant.sessionId(), session.getState()));
        }
        server.calls++;
        if (STATE_IDLE.equals(session.getState())) {
            session.setState(STATE_BUSY);
            session.setIdleSince(null);
            session.setUpdatedAt(now());
            store.put(session.getSessionId(), record);
            // A busy session's idle timeout does not run, so only its 24 hours are left to check.
            checkLater(record);
        }
        long launchedIn = generation;
        return Route.to(server.host, server.port, () -> RequestScopes.runAs(grant.accountId(), grant.region(),
                () -> callEnded(grant.sessionId(), launchedIn)));
    }

    // A call has ended: with none left, the session is IDLE again from now.
    private synchronized void callEnded(String sessionId, long launchedIn) {
        Server server = servers.get(sessionId);
        if (launchedIn != generation || server == null) {
            return;
        }
        server.calls = Math.max(0, server.calls - 1);
        if (server.calls > 0) {
            return;
        }
        store.get(sessionId).filter(record -> STATE_BUSY.equals(record.getSession().getState())).ifPresent(record -> {
            Instant now = now();
            record.getSession().setState(STATE_IDLE);
            record.getSession().setIdleSince(now);
            record.getSession().setUpdatedAt(now);
            store.put(sessionId, record);
            checkLater(record);
        });
    }

    /**
     * Stops the application as StopApplication does once its job runs have ended
     * ({@link EmrServerlessJobRunService#stopApplication}), and then terminates its sessions that have not ended. Once
     * an application is stopped "no resources are running on the application" (EMR Serverless user guide, "Application
     * states"), and while StopApplication requires that "All scheduled and running jobs must be completed or cancelled
     * before stopping an application", it names no such requirement for sessions. Under this service's lock, as
     * StartSession's admission is, so no session starts between the two.
     */
    public synchronized void stopApplication(String applicationId) {
        jobRuns.stopApplication(applicationId);
        Instant now = now();
        for (SessionRecord record : store.scan(key -> true)) {
            Session session = record.getSession();
            if (applicationId.equals(session.getApplicationId()) && inRequestRegion(session)
                    && !ENDED.contains(settle(record).getSession().getState())) {
                end(record, STATE_TERMINATED, APPLICATION_STOPPED, now);
            }
        }
    }

    /**
     * The session GetResourceDashboard links to, in any state: "Currently, only Session is supported" as its
     * {@code resourceType}.
     */
    public synchronized Session dashboardResource(String applicationId, String resourceId, String resourceType) {
        List<String> violations = new ArrayList<>();
        if (resourceId == null) {
            violations.add(SmithyValidationErrors.notNull("resourceId"));
        }
        if (resourceType == null) {
            violations.add(SmithyValidationErrors.notNull("resourceType"));
        } else if (!RESOURCE_TYPES.contains(resourceType)) {
            violations.add(SmithyValidationErrors.notInEnum(resourceType, "resourceType", RESOURCE_TYPES));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        return getSession(applicationId, resourceId);
    }

    public synchronized Map<String, String> listTags(EmrServerlessService.ResourceArn resource) {
        Session session = sessionFor(resource).getSession();
        return session.getTags() != null ? new LinkedHashMap<>(session.getTags()) : new LinkedHashMap<>();
    }

    public synchronized void tagResource(EmrServerlessService.ResourceArn resource, Map<String, String> tags) {
        SessionRecord record = sessionFor(resource);
        record.getSession().setTags(EmrServerlessService.withTags(record.getSession().getTags(), tags));
        store.put(record.getSession().getSessionId(), record);
    }

    public synchronized void untagResource(EmrServerlessService.ResourceArn resource, List<String> tagKeys) {
        SessionRecord record = sessionFor(resource);
        record.getSession().setTags(EmrServerlessService.withoutTags(record.getSession().getTags(), tagKeys));
        store.put(record.getSession().getSessionId(), record);
    }

    private static void validate(StartSessionRequest request) {
        List<String> violations = new ArrayList<>();
        if (request.getClientToken() == null) {
            violations.add(SmithyValidationErrors.notNull("clientToken"));
        }
        String roleViolation = EmrServerlessJobRunService.executionRoleViolation(request.getExecutionRoleArn());
        if (roleViolation != null) {
            violations.add(roleViolation);
        }
        if (request.getConfigurationOverrides() != null) {
            Object configurations = request.getConfigurationOverrides().get("runtimeConfiguration");
            if (configurations != null) {
                violations.addAll(configurationViolations(configurations,
                        "configurationOverrides.runtimeConfiguration"));
            }
        }
        if (request.getIdleTimeoutMinutes() != null) {
            String violation = SmithyValidationErrors.outOfRange("idleTimeoutMinutes", request.getIdleTimeoutMinutes(),
                    0, MAX_IDLE_TIMEOUT_MINUTES);
            if (violation != null) {
                violations.add(violation);
            }
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    /**
     * The violations of a ConfigurationList at {@code path}: each configuration needs its {@code classification}.
     * One of the wrong JSON type, the list, a configuration, its {@code classification}, its {@code properties}, a
     * map of strings, or its nested {@code configurations}, is refused at once with SerializationException, as a
     * restJson1 service refuses a body member of another type (Smithy's restJson1 malformed-request tests), in
     * Floci's own wording.
     */
    private static List<String> configurationViolations(Object configurations, String path) {
        if (!(configurations instanceof List<?> list)) {
            throw malformed(path + " must be a list of configurations");
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String member = path + "." + (i + 1) + ".member";
            if (!(list.get(i) instanceof Map<?, ?> configuration)) {
                throw malformed(member + " must be a configuration");
            }
            Object classification = configuration.get("classification");
            if (classification == null) {
                violations.add(SmithyValidationErrors.notNull(member + ".classification"));
            } else if (!(classification instanceof String)) {
                throw malformed(member + ".classification must be a string");
            }
            Object properties = configuration.get("properties");
            if (properties != null && (!(properties instanceof Map<?, ?> map)
                    || !map.values().stream().allMatch(String.class::isInstance))) {
                throw malformed(member + ".properties must be a map of strings");
            }
            Object nested = configuration.get("configurations");
            if (nested != null) {
                violations.addAll(configurationViolations(nested, member + ".configurations"));
            }
        }
        return violations;
    }

    private static AwsException malformed(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    /**
     * Refuses a session the application cannot hold, before auto-start can change it. No source gives AWS's wording
     * for any of these refusals, so it is Floci's; ServiceQuotaExceededException is the model's error for the quota.
     */
    private void checkAdmissible(Application app) {
        if (!SPARK_TYPE.equalsIgnoreCase(app.getType())) {
            throw new AwsException("ValidationException",
                    "Sessions are supported only on Spark applications.", 400);
        }
        if (!supportsSessions(app.getReleaseLabel())) {
            throw new AwsException("ValidationException",
                    "Sessions are supported on EMR release 7.13.0 and later.", 400);
        }
        Object enabled = app.getInteractiveConfiguration() != null
                ? app.getInteractiveConfiguration().getProperties().get("sessionEnabled") : null;
        if (!Boolean.TRUE.equals(enabled)) {
            throw new AwsException("ValidationException", "Application " + app.getApplicationId()
                    + " does not have interactive sessions enabled.", 400);
        }
        long open = 0;
        for (SessionRecord record : store.scan(key -> true)) {
            Session session = record.getSession();
            if (app.getApplicationId().equals(session.getApplicationId()) && inRequestRegion(session)
                    && !ENDED.contains(settle(record).getSession().getState())) {
                open++;
            }
        }
        if (open >= MAX_CONCURRENT_SESSIONS) {
            throw new AwsException("ServiceQuotaExceededException", "Application " + app.getApplicationId()
                    + " already has " + MAX_CONCURRENT_SESSIONS + " concurrent sessions.", 402);
        }
    }

    /**
     * "EMR release 7.13.0 and later": an emr- release from 7.13.0 on, or a release of the AWS runtime for Apache
     * Spark, which the release list puts after them.
     */
    static boolean supportsSessions(String releaseLabel) {
        return EmrServerlessJobRunService.releaseAtLeast(EmrServerlessJobRunService.RELEASE_LABEL, releaseLabel, 7, 13)
                || EmrServerlessJobRunService.sparkRuntime(releaseLabel);
    }

    private SessionRecord newSession(Application app, StartSessionRequest request, JsonNode original) {
        AwsArnUtils.Arn applicationArn = AwsArnUtils.parse(app.getArn());
        String sessionId = EmrServerlessService.newId();
        Instant now = now();

        Session session = new Session();
        session.setApplicationId(app.getApplicationId());
        session.setSessionId(sessionId);
        session.setArn(AwsArnUtils.Arn.of("emr-serverless", applicationArn.region(), applicationArn.accountId(),
                applicationArn.resource() + "/sessions/" + sessionId).toString());
        session.setName(request.getName());
        session.setStateDetails("");
        session.setReleaseLabel(app.getReleaseLabel());
        session.setExecutionRoleArn(request.getExecutionRoleArn());
        session.setCreatedBy(EmrServerlessService.createdBy(iamService, requestContext, regionResolver,
                applicationArn.accountId()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        if (sparkRunner == null) {
            // With no driver to start, a session is ready, and idle, as it starts.
            session.setState(STATE_IDLE);
            session.setStartedAt(now);
            session.setIdleSince(now);
        } else {
            session.setState(STATE_SUBMITTED);
        }
        // "Only runtime configuration overrides are supported."
        if (request.getConfigurationOverrides() != null
                && request.getConfigurationOverrides().get("runtimeConfiguration") != null) {
            session.setConfigurationOverrides(Map.of("runtimeConfiguration",
                    request.getConfigurationOverrides().get("runtimeConfiguration")));
        }
        session.setNetworkConfiguration(app.getNetworkConfiguration());
        session.setIdleTimeoutMinutes(request.getIdleTimeoutMinutes() != null
                ? request.getIdleTimeoutMinutes() : DEFAULT_IDLE_TIMEOUT_MINUTES);
        session.setTags(request.getTags() != null ? new LinkedHashMap<>(request.getTags()) : new LinkedHashMap<>());

        SessionRecord record = new SessionRecord();
        record.setSession(session);
        record.setClientToken(request.getClientToken());
        record.setRequest(original);
        record.setContainerSession(sparkRunner != null);
        store.put(sessionId, record);
        if (sparkRunner != null) {
            launch(record, app);
        }
        return record;
    }

    // Hands a session's server to a thread of its own, which runs it until the session ends.
    private void launch(SessionRecord record, Application app) {
        Session session = record.getSession();
        // The session's 24 hours count from when its server answers, where settle() reads them, so the runner sets
        // none of its own.
        SparkJobRunner.Job job = jobRuns.sessionJob(app, session, Duration.ZERO, SPARK_CONNECT_PORT);
        servers.put(session.getSessionId(), new Server());
        long launchedIn = generation;
        serverExecutor.execute(() -> runServer(job, launchedIn));
    }

    private void runServer(SparkJobRunner.Job job, long launchedIn) {
        SparkJobRunner.Outcome outcome;
        try {
            outcome = sparkRunner.run(job, new ServerProgress(job, launchedIn));
        } catch (RuntimeException e) {
            LOG.warnv(e, "The Spark Connect server of EMR Serverless session {0} could not run", job.runId());
            outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        SparkJobRunner.Outcome ended = outcome;
        RequestScopes.runAs(job.accountId(), job.region(), () -> serverEnded(job.runId(), ended, launchedIn));
    }

    // Asks the server whether it answers until it does, its session stops starting, or its time to start is up.
    private void awaitServer(SparkJobRunner.Job job, String host, int port, long launchedIn) {
        long giveUp = System.nanoTime() + SERVER_START_LIMIT.toNanos();
        while (RequestScopes.callAs(job.accountId(), job.region(), () -> starting(job.runId(), launchedIn))) {
            String unanswered = probe.unanswered(host, port);
            if (unanswered == null) {
                RequestScopes.runAs(job.accountId(), job.region(), () -> serverReady(job.runId(), launchedIn));
                return;
            }
            if (System.nanoTime() - giveUp >= 0) {
                LOG.warnv("The Spark Connect server of EMR Serverless session {0} did not answer: {1}", job.runId(),
                        unanswered);
                RequestScopes.runAs(job.accountId(), job.region(),
                        () -> serverSilent(job.runId(), unanswered, launchedIn));
                return;
            }
            try {
                Thread.sleep(SERVER_PROBE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized boolean starting(String sessionId, long launchedIn) {
        return launchedIn == generation && store.get(sessionId)
                .map(record -> STATE_STARTING.equals(record.getSession().getState())).orElse(false);
    }

    // Whether a session's server may still start, or go on.
    private synchronized boolean stillGoing(String sessionId, long launchedIn) {
        return launchedIn == generation && servers.containsKey(sessionId) && store.get(sessionId)
                .map(record -> !ENDED.contains(record.getSession().getState())
                        && !STATE_TERMINATING.equals(record.getSession().getState()))
                .orElse(false);
    }

    private synchronized void serverStarted(String sessionId, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(sessionId).filter(record -> STATE_SUBMITTED.equals(record.getSession().getState()))
                .ifPresent(record -> {
                    record.getSession().setState(STATE_STARTING);
                    record.getSession().setUpdatedAt(now());
                    store.put(sessionId, record);
                });
    }

    private synchronized boolean serverListening(String sessionId, String host, int port, long launchedIn) {
        Server server = servers.get(sessionId);
        if (launchedIn != generation || server == null) {
            return false;
        }
        server.host = host;
        server.port = port;
        return true;
    }

    // The server answers: the session is ready, and IDLE, its idle timeout counting from now.
    private synchronized void serverReady(String sessionId, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(sessionId).filter(record -> STATE_STARTING.equals(record.getSession().getState()))
                .ifPresent(record -> {
                    Instant now = now();
                    Session session = record.getSession();
                    session.setState(STATE_IDLE);
                    session.setStartedAt(now);
                    session.setIdleSince(now);
                    session.setUpdatedAt(now);
                    store.put(sessionId, record);
                    checkLater(record);
                    LOG.infov("EMR Serverless session {0} is ready", sessionId);
                });
    }

    private synchronized void serverSilent(String sessionId, String unanswered, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(sessionId).filter(record -> STATE_STARTING.equals(record.getSession().getState()))
                .ifPresent(record -> end(record, STATE_FAILED, String.format(SERVER_SILENT, unanswered), now()));
    }

    /**
     * A session's server has stopped: a session being terminated is TERMINATED, as is one whose server Floci stopped or
     * that ran past its 24 hours, and one whose server stopped on its own, or never started, has FAILED.
     */
    private synchronized void serverEnded(String sessionId, SparkJobRunner.Outcome outcome, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        Server server = servers.remove(sessionId);
        if (server != null) {
            cancelCheck(server);
        }
        dropGrants(sessionId);
        store.get(sessionId).ifPresent(record -> {
            String state = record.getSession().getState();
            if (ENDED.contains(state)) {
                return;
            }
            Instant now = now();
            if (STATE_TERMINATING.equals(state)) {
                end(record, STATE_TERMINATED, null, now);
            } else if (outcome.timedOut()) {
                end(record, STATE_TERMINATED, HARD_LIMIT_REACHED, now);
            } else if (outcome.stopped()) {
                end(record, STATE_TERMINATED, null, now);
            } else if (outcome.failure() != null) {
                end(record, STATE_FAILED, String.format(SERVER_NOT_STARTED, outcome.failure()), now);
            } else if (outcome.exitCode() != null) {
                end(record, STATE_FAILED, String.format(SERVER_EXITED, outcome.exitCode()), now);
            } else {
                end(record, STATE_FAILED, SERVER_ENDED, now);
            }
        });
    }

    /**
     * Checks a session with a server again at its next deadline: once its idle timeout passes, while it is idle, or
     * once its 24 hours pass, whichever comes first, so it ends then, its server with it. Each session keeps one check,
     * which this replaces, and which ending it cancels.
     */
    private void checkLater(SessionRecord record) {
        Session session = record.getSession();
        Server server = servers.get(session.getSessionId());
        if (idleChecks == null || server == null || session.getStartedAt() == null) {
            return;
        }
        Instant deadline = session.getStartedAt().plus(HARD_LIMIT);
        Long minutes = session.getIdleTimeoutMinutes();
        if (STATE_IDLE.equals(session.getState()) && session.getIdleSince() != null && minutes != null
                && minutes > 0) {
            Instant idleLimit = session.getIdleSince().plus(Duration.ofMinutes(minutes));
            deadline = idleLimit.isBefore(deadline) ? idleLimit : deadline;
        }
        if (server.check != null) {
            server.check.cancel(false);
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(session.getArn());
        String sessionId = session.getSessionId();
        server.check = idleChecks.schedule(
                () -> RequestScopes.runAs(arn.accountId(), arn.region(), () -> checkNow(sessionId)),
                Math.max(0, Duration.between(clock.instant(), deadline).toMillis()), TimeUnit.MILLISECONDS);
    }

    private static void cancelCheck(Server server) {
        if (server.check != null) {
            server.check.cancel(false);
            server.check = null;
        }
    }

    private synchronized void checkNow(String sessionId) {
        store.get(sessionId).ifPresent(this::settle);
    }

    // One thread that ends sessions at their deadlines; a check a later one replaces leaves its queue at once.
    private static ScheduledExecutorService sessionChecks() {
        ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("emr-serverless-session-checks").factory());
        checks.setRemoveOnCancelPolicy(true);
        return checks;
    }

    private void dropGrants(String sessionId) {
        grants.values().removeIf(grant -> grant.sessionId().equals(sessionId));
    }

    // Why no server answers at host and port yet, or null once one does.
    private static String unanswered(SparkConnectProxy proxy, String host, int port) {
        try {
            return proxy.unanswered(host, port).toCompletionStage().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            LOG.debugv(e, "Probing the Spark Connect server at {0}:{1} failed", host, port);
            return e.toString();
        }
    }

    /**
     * Ends a session whose time has run out by now: an idle one once its idle timeout has passed since it went idle,
     * and any once its 24 hours have passed since it started, whichever comes first. An idle timeout of 0 ends
     * nothing: no source says what it does, and Floci reads it as a job run's execution timeout of 0 is read, as none.
     */
    private SessionRecord settle(SessionRecord record) {
        Session session = record.getSession();
        if (ENDED.contains(session.getState())) {
            return record;
        }
        // Its thread ends it; without one, Floci stopped while its server ran, and nothing is left to. A server
        // container the startup's cleanup left behind goes now.
        if (record.isContainerSession() && !servers.containsKey(session.getSessionId())) {
            boolean terminating = STATE_TERMINATING.equals(session.getState());
            end(record, terminating ? STATE_TERMINATED : STATE_FAILED, terminating ? null : INTERRUPTED, now());
            if (sparkRunner != null) {
                sparkRunner.removeLeftoverDriver(EmrServerlessJobRunService.SERVICE, session.getSessionId());
            }
            return record;
        }
        Instant hardLimit = session.getStartedAt() != null ? session.getStartedAt().plus(HARD_LIMIT) : null;
        Long idleMinutes = session.getIdleTimeoutMinutes();
        Instant idleLimit = STATE_IDLE.equals(session.getState()) && session.getIdleSince() != null
                && idleMinutes != null && idleMinutes > 0
                ? session.getIdleSince().plus(Duration.ofMinutes(idleMinutes)) : null;
        Instant now = clock.instant();
        if (idleLimit != null && !now.isBefore(idleLimit) && (hardLimit == null || idleLimit.isBefore(hardLimit))) {
            end(record, STATE_TERMINATED, IDLE_TIMED_OUT, idleLimit);
        } else if (hardLimit != null && !now.isBefore(hardLimit)) {
            end(record, STATE_TERMINATED, HARD_LIMIT_REACHED, hardLimit);
        }
        return record;
    }

    private void end(SessionRecord record, String state, String details, Instant at) {
        Session session = record.getSession();
        session.setState(state);
        session.setStateDetails(details != null ? details : "");
        session.setEndedAt(at);
        session.setUpdatedAt(at);
        session.setIdleSince(null);
        if (session.getStartedAt() != null) {
            session.setTotalExecutionDurationSeconds(Duration.between(session.getStartedAt(), at).toSeconds());
        }
        store.put(session.getSessionId(), record);
        dropGrants(session.getSessionId());
        // An ended session's server goes with it, and its check; its thread finds the session ended and leaves it so.
        Server server = servers.get(session.getSessionId());
        if (server != null) {
            cancelCheck(server);
            sparkRunner.stop(session.getSessionId());
        }
    }

    private SessionRecord findSession(String applicationId, String sessionId) {
        return store.get(sessionId)
                .filter(record -> applicationId.equals(record.getSession().getApplicationId()))
                .filter(record -> inRequestRegion(record.getSession()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Session " + sessionId + " not found", 404));
    }

    // The session a tagging ARN names. The ARN must be the session's own, and a deleted application's sessions name
    // nothing, as with job runs.
    private SessionRecord sessionFor(EmrServerlessService.ResourceArn resource) {
        return store.get(resource.sessionId())
                .filter(record -> resource.applicationId().equals(record.getSession().getApplicationId()))
                .filter(record -> resource.arn().equals(record.getSession().getArn()))
                .filter(record -> applications.isLive(resource.applicationId()))
                .orElseThrow(() -> EmrServerlessService.resourceNotFound(resource.arn()));
    }

    private boolean inRequestRegion(Session session) {
        return regionResolver.getRegion().equals(AwsArnUtils.parse(session.getArn()).region());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private Session copy(Session session) {
        return objectMapper.convertValue(session, Session.class);
    }

    /** Why no Spark Connect server answers at a host and port, or null once one does. */
    @FunctionalInterface
    interface ServerProbe {
        String unanswered(String host, int port);
    }

    // A session's running server: where Floci reaches it, once its container runs, how many calls it is taking, and
    // the check that ends the session at its next deadline.
    private static final class Server {
        String host;
        int port;
        int calls;
        ScheduledFuture<?> check;
    }

    // What an authentication token was given for: a session, in its account and Region, until it expires.
    private record Grant(String accountId, String region, String sessionId, Instant expiresAt) {
    }

    /** What the thread running a session's server tells the service, and asks it, as that session. */
    private final class ServerProgress implements SparkJobRunner.Progress {

        private final SparkJobRunner.Job job;
        private final long launchedIn;

        ServerProgress(SparkJobRunner.Job job, long launchedIn) {
            this.job = job;
            this.launchedIn = launchedIn;
        }

        @Override
        public void started() {
            RequestScopes.runAs(job.accountId(), job.region(), () -> serverStarted(job.runId(), launchedIn));
        }

        @Override
        public void listening(String host, int port) {
            if (RequestScopes.callAs(job.accountId(), job.region(),
                    () -> serverListening(job.runId(), host, port, launchedIn))) {
                serverExecutor.execute(() -> awaitServer(job, host, port, launchedIn));
            }
        }

        @Override
        public void timedOut() {
            // The run's outcome says so, which serverEnded reads.
        }

        @Override
        public boolean cancelled() {
            return !RequestScopes.callAs(job.accountId(), job.region(), () -> stillGoing(job.runId(), launchedIn));
        }
    }
}
