package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.function.Supplier;

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
    // The sessions' Spark Connect servers, and the tokens given for them.
    private final SparkConnectServers servers;
    private final SessionOwner owner = new SessionOwner();
    // The latest sequence a session was given, EmrServerlessService.nextSequence.
    private long lastSequence;

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
                SparkConnectServers.checkThread("emr-serverless-session-checks"), SparkConnectServers.probe(proxy));
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
                                Executor serverExecutor, ScheduledExecutorService checks,
                                SparkConnectServers.Probe probe) {
        this.sparkRunner = sparkRunner;
        this.servers = new SparkConnectServers(sparkRunner, serverExecutor, checks, probe, clock);
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
        servers.shutdown();
    }

    /**
     * A reset has begun: the servers' threads from before it write nothing more, and their tokens go. The runner's
     * teardown stops their containers.
     */
    @Override
    public void beforeReset() {
        servers.reset();
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
            if (!sameSession(existing.getRequest(), applicationId, body)) {
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
        List<SessionRecord> matching = new ArrayList<>();
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
            matching.add(record);
        }
        String namespace = String.join("|", "ListSessions", applicationId,
                String.valueOf(states != null ? states : List.of()), String.valueOf(createdAtAfter),
                String.valueOf(createdAtBefore));
        PaginatedResult<SessionRecord> page = EmrServerlessService.pageByState(matching,
                record -> record.getSession().getSessionId(), record -> record.getSession().getState(),
                record -> record.getSession().getCreatedAt(), SessionRecord::getSequence, states, maxResults,
                MAX_LIST_RESULTS, nextToken, namespace);
        return new PaginatedResult<>(page.items().stream().map(record -> SessionSummary.of(record.getSession()))
                .toList(), page.nextToken());
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
        if (servers.runs(sessionId)) {
            Session session = record.getSession();
            session.setState(STATE_TERMINATING);
            session.setStateDetails("");
            session.setIdleSince(null);
            session.setUpdatedAt(now());
            store.put(sessionId, record);
            servers.stop(sessionId);
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
        SparkConnectEndpoint endpoint = SparkConnectEndpoint.issue(baseUrl, "", now(), TOKEN_VALIDITY);
        AwsArnUtils.Arn arn = AwsArnUtils.parse(session.getArn());
        servers.grant(endpoint.authToken(), sessionId, arn.accountId(), arn.region(), endpoint.authTokenExpiresAt());
        return endpoint;
    }

    /**
     * Where a Spark Connect call carrying {@code token} goes: the server of the session the token was given for, while
     * the token has not expired and the session is IDLE or BUSY. The call makes the session BUSY until it ends. No
     * source gives AWS's answer for a session that has not started or has ended, so the refusals' wording is Floci's.
     */
    @Override
    public Optional<Route> route(String token) {
        return servers.route(token, owner);
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
     * The sessions, of every account, whose kept history stays ({@link EmrServerlessHistories}): those that have not
     * ended, and those that ended after {@code cutoff}.
     */
    synchronized Set<String> historiesKept(Instant cutoff) {
        List<Session> all = store.scanAllAccountEntries(key -> true).stream()
                .map(entry -> entry.value().getSession()).toList();
        return EmrServerlessHistories.stillKept(all, Session::getSessionId,
                session -> STATE_TERMINATED.equals(session.getState()) || STATE_FAILED.equals(session.getState()),
                Session::getEndedAt, cutoff);
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

    /** The session {@code sessionId} of {@code account} as stored, read in place, for IAM's lookup of stored tags. */
    Optional<Session> storedSession(String account, String sessionId) {
        return AccountAwareStorageBackend.readInAccount(store, account, sessionId).map(SessionRecord::getSession);
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
        requested(session, request);
        session.setStateDetails("");
        session.setReleaseLabel(app.getReleaseLabel());
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
        session.setNetworkConfiguration(app.getNetworkConfiguration());

        SessionRecord record = new SessionRecord();
        record.setSession(session);
        lastSequence = EmrServerlessService.nextSequence(lastSequence, now);
        record.setSequence(lastSequence);
        record.setClientToken(request.getClientToken());
        record.setRequest(original);
        record.setContainerSession(sparkRunner != null);
        store.put(sessionId, record);
        if (sparkRunner != null) {
            launch(record, app);
        }
        return record;
    }

    // Runs a session's server until the session ends.
    private void launch(SessionRecord record, Application app) {
        // The session's 24 hours count from when its server answers, where settle() reads them, so the runner sets
        // none of its own.
        servers.launch(jobRuns.sessionJob(app, record.getSession(), Duration.ZERO, SPARK_CONNECT_PORT), owner);
    }

    /**
     * Why a session takes no Spark Connect call now, or null while it does, IDLE or BUSY. No source gives AWS's answer
     * for a session that has not started or has ended, so the wording is Floci's.
     */
    private synchronized String refusal(String sessionId) {
        SessionRecord record = store.get(sessionId).orElse(null);
        if (record == null) {
            return String.format(NOT_READY, sessionId, STATE_TERMINATED);
        }
        Session session = settle(record).getSession();
        if (!record.isContainerSession()) {
            return String.format(NO_SERVER, sessionId);
        }
        boolean ready = STATE_IDLE.equals(session.getState()) || STATE_BUSY.equals(session.getState());
        return ready ? null : String.format(NOT_READY, sessionId, session.getState());
    }

    /**
     * Checks a session with a server again at its next deadline: once its idle timeout passes, while it is idle, or
     * once its 24 hours pass, whichever comes first, so it ends then, its server with it. Each session keeps one check,
     * which this replaces, and which ending it cancels.
     */
    private void checkLater(SessionRecord record) {
        Session session = record.getSession();
        if (session.getStartedAt() == null) {
            return;
        }
        Instant deadline = session.getStartedAt().plus(HARD_LIMIT);
        Long minutes = session.getIdleTimeoutMinutes();
        if (STATE_IDLE.equals(session.getState()) && session.getIdleSince() != null && minutes != null
                && minutes > 0) {
            Instant idleLimit = session.getIdleSince().plus(Duration.ofMinutes(minutes));
            deadline = idleLimit.isBefore(deadline) ? idleLimit : deadline;
        }
        String sessionId = session.getSessionId();
        servers.checkAt(sessionId, deadline, () -> checkNow(sessionId));
    }

    private synchronized void checkNow(String sessionId) {
        store.get(sessionId).ifPresent(this::settle);
    }

    /**
     * Sets on {@code session} what a session takes from the request that starts it, as it holds it: its name, role,
     * runtime configuration overrides alone, as "Only runtime configuration overrides are supported", idle timeout
     * ({@value #DEFAULT_IDLE_TIMEOUT_MINUTES} minutes where the request gives none) and tags (none where it gives
     * none).
     */
    private static void requested(Session session, StartSessionRequest request) {
        session.setName(request.getName());
        session.setExecutionRoleArn(request.getExecutionRoleArn());
        if (request.getConfigurationOverrides() != null
                && request.getConfigurationOverrides().get("runtimeConfiguration") != null) {
            session.setConfigurationOverrides(Map.of("runtimeConfiguration",
                    request.getConfigurationOverrides().get("runtimeConfiguration")));
        }
        session.setIdleTimeoutMinutes(request.getIdleTimeoutMinutes() != null
                ? request.getIdleTimeoutMinutes() : DEFAULT_IDLE_TIMEOUT_MINUTES);
        session.setTags(request.getTags() != null ? new LinkedHashMap<>(request.getTags()) : new LinkedHashMap<>());
    }

    /**
     * Whether a retry asks for the session its original started: on the same application, with what the two would
     * have the session hold alike, {@link #requested}, as a job run's retry is compared.
     */
    private boolean sameSession(JsonNode original, String applicationId, StartSessionRequest retried) {
        ObjectNode first = ((ObjectNode) original).deepCopy();
        String firstApplication = first.path("applicationId").asText(null);
        first.remove("applicationId");
        return applicationId.equals(firstApplication)
                && held(objectMapper.convertValue(first, StartSessionRequest.class)).equals(held(retried));
    }

    private JsonNode held(StartSessionRequest request) {
        Session session = new Session();
        requested(session, request);
        return objectMapper.valueToTree(session);
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
        if (record.isContainerSession() && !servers.runs(session.getSessionId())) {
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
        // An ended session's server goes with it, its tokens and its check; its thread finds the session ended and
        // leaves it so.
        servers.stop(session.getSessionId());
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

    /** The service as its servers' owner ({@link SparkConnectSessionOwner}), its sessions stored in records. */
    private final class SessionOwner extends SparkConnectSessionOwner<SessionRecord> {

        @Override
        protected <T> T locked(Supplier<T> body) {
            synchronized (EmrServerlessSessionService.this) {
                return body.get();
            }
        }

        @Override
        protected Optional<SessionRecord> find(String sessionId) {
            return store.get(sessionId);
        }

        @Override
        protected SparkConnectSession session(SessionRecord stored) {
            return stored.getSession();
        }

        @Override
        protected void save(SessionRecord stored) {
            store.put(stored.getSession().getSessionId(), stored);
        }

        @Override
        protected Instant now() {
            return EmrServerlessSessionService.this.now();
        }

        @Override
        protected void end(SessionRecord stored, String state, String reason) {
            EmrServerlessSessionService.this.end(stored, state, reason, now());
        }

        // A session past its 24 hours says so; one terminated or stopped otherwise says nothing more.
        @Override
        protected String endReason(SessionRecord stored, SparkJobRunner.Outcome outcome) {
            return outcome.timedOut() && !STATE_TERMINATING.equals(stored.getSession().getState())
                    ? HARD_LIMIT_REACHED : null;
        }

        @Override
        protected void checkLater(SessionRecord stored) {
            EmrServerlessSessionService.this.checkLater(stored);
        }

        @Override
        public String refusal(String sessionId) {
            return EmrServerlessSessionService.this.refusal(sessionId);
        }
    }
}
