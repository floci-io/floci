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
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.SessionRecord;
import io.floci.aws.services.emrserverless.model.SessionSummary;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMR Serverless sessions: "a managed connection between your local PySpark client and a Spark driver running on Amazon
 * EMR Serverless", through Spark Connect (EMR Serverless user guide, "Run interactive sessions with Amazon EMR
 * Serverless through Spark Connect"). Floci keeps each session and its lifecycle but starts no Spark driver for it: a
 * session is IDLE once started, nothing answers Spark Connect at its endpoint, and it ends when it is terminated, when
 * its idle timeout or its 24 hours pass, or when its application stops.
 */
@ApplicationScoped
public class EmrServerlessSessionService {

    private static final Logger LOG = Logger.getLogger(EmrServerlessSessionService.class);

    static final String STATE_IDLE = "IDLE";
    static final String STATE_TERMINATED = "TERMINATED";
    private static final List<String> STATES =
            List.of("SUBMITTED", "STARTING", "STARTED", STATE_IDLE, "BUSY", "FAILED", "TERMINATING", STATE_TERMINATED);
    private static final Set<String> ENDED = Set.of("FAILED", STATE_TERMINATED);
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
    // Where GetSessionEndpoint points; nothing answers Spark Connect there, as Floci runs no driver for a session.
    static final String ENDPOINT_PATH = "/_aws/emr-serverless/sessions/";
    private static final String SPARK_TYPE = "SPARK";
    // No source gives AWS's wording for these ends; they are Floci's.
    static final String IDLE_TIMED_OUT = "The session was idle for its idle timeout.";
    static final String HARD_LIMIT_REACHED = "The session reached its 24 hour limit.";
    static final String APPLICATION_STOPPED = "The session's application was stopped.";

    private final AccountAwareStorageBackend<SessionRecord> store;
    private final EmrServerlessService applications;
    private final EmrServerlessJobRunService jobRuns;
    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String baseUrl;

    @Inject
    public EmrServerlessSessionService(StorageFactory storageFactory, EmrServerlessService applications,
                                       EmrServerlessJobRunService jobRuns, IamService iamService,
                                       RegionResolver regionResolver, RequestContext requestContext,
                                       ObjectMapper objectMapper, EmulatorConfig config, Clock clock) {
        this(storageFactory.create("emrserverless", "emr-serverless-sessions.json", new TypeReference<>() {}),
                applications, jobRuns, iamService, regionResolver, requestContext, objectMapper, clock,
                config.effectiveBaseUrl());
    }

    EmrServerlessSessionService(AccountAwareStorageBackend<SessionRecord> store, EmrServerlessService applications,
                                EmrServerlessJobRunService jobRuns, IamService iamService,
                                RegionResolver regionResolver, RequestContext requestContext,
                                ObjectMapper objectMapper, Clock clock, String baseUrl) {
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
     * then the TERMINATED state." With no driver to stop, Floci's is TERMINATED at once. One that has already ended
     * is refused; no source gives AWS's answer for it, so the wording is Floci's.
     */
    public synchronized Session terminateSession(String applicationId, String sessionId) {
        applications.getApplication(applicationId);
        SessionRecord record = settle(findSession(applicationId, sessionId));
        if (ENDED.contains(record.getSession().getState())) {
            throw new AwsException("ValidationException", "Session " + sessionId + " has already ended.", 400);
        }
        end(record, STATE_TERMINATED, null, now());
        LOG.infov("Terminated EMR Serverless session {0} on application {1}", sessionId, applicationId);
        return copy(record.getSession());
    }

    /**
     * Where a client connects to a session that has not ended, with an authentication token, which is "time-limited
     * to 1 hour". The endpoint is on Floci's own address, where nothing answers Spark Connect, as Floci runs no driver
     * for a session. No source gives AWS's answer for a session that has ended, so the refusal's wording is Floci's.
     */
    public synchronized SparkConnectEndpoint sessionEndpoint(String applicationId, String sessionId) {
        applications.getApplication(applicationId);
        Session session = settle(findSession(applicationId, sessionId)).getSession();
        if (ENDED.contains(session.getState())) {
            throw new AwsException("ValidationException", "Session " + sessionId + " has ended.", 400);
        }
        return SparkConnectEndpoint.issue(baseUrl, ENDPOINT_PATH + applicationId + "/" + sessionId, now(),
                TOKEN_VALIDITY);
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
        // With no driver to start, a session is ready, and idle, as it starts.
        session.setState(STATE_IDLE);
        session.setStateDetails("");
        session.setReleaseLabel(app.getReleaseLabel());
        session.setExecutionRoleArn(request.getExecutionRoleArn());
        session.setCreatedBy(EmrServerlessService.createdBy(iamService, requestContext, regionResolver,
                applicationArn.accountId()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setStartedAt(now);
        session.setIdleSince(now);
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
        store.put(sessionId, record);
        return record;
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
}
