package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.EmrSession;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a cluster's Spark Connect sessions (Amazon EMR Management Guide, "Interactive sessions"): "a managed
 * connection between your local PySpark client and a Spark Connect Server running on your Amazon EMR cluster". Floci
 * keeps a session and its lifecycle, but starts no Spark Connect server for it: a session is SUBMITTED as StartSession
 * answers, IDLE from then on, and TERMINATED when it is terminated, when its idle timeout passes, or when its cluster
 * shuts down. {@link EmrService} applies them under its lock, to the sessions it keeps apart from their clusters.
 */
final class EmrSessions {

    static final String SUBMITTED = "SUBMITTED";
    static final String IDLE = "IDLE";
    static final String TERMINATED = "TERMINATED";
    static final List<String> STATES =
            List.of(SUBMITTED, "STARTING", "STARTED", IDLE, "BUSY", "TERMINATING", TERMINATED, "FAILED");
    private static final Set<String> ENDED = Set.of(TERMINATED, "FAILED");
    // "Amazon EMR enforces a limit of 1000 concurrent active sessions per cluster."
    static final int MAX_CONCURRENT_SESSIONS = 1000;
    // "Sessions end after a configurable idle timeout. The default timeout is 60 minutes, with a maximum of 24 hours."
    static final long DEFAULT_IDLE_TIMEOUT_MINUTES = 60;
    static final long MAX_IDLE_TIMEOUT_MINUTES = Duration.ofHours(24).toMinutes();
    // "Authentication tokens expire after 1 hour."
    static final Duration TOKEN_VALIDITY = Duration.ofHours(1);
    // ListSessions' MaxResultsNumber is 1 to 100.
    static final int MAX_LIST_RESULTS = 100;
    // Where GetSessionEndpoint points; nothing answers Spark Connect there, as Floci runs no server for a session.
    static final String ENDPOINT_PATH = "/_aws/emr/sessions/";
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MIN_TOKEN_LENGTH = 1;
    private static final int MAX_TOKEN_LENGTH = 36;
    private static final String ROLE_ARN_PATTERN =
            "^arn:(aws[a-zA-Z0-9-]*):iam::(\\d{12})?:(role((\\u002F)|(\\u002F[\\u0021-\\u007F]+\\u002F))[\\w+=,.@-]+)$";
    private static final int MIN_ROLE_ARN_LENGTH = 20;
    private static final int MAX_ROLE_ARN_LENGTH = 2048;
    private static final String SPARK = "spark";
    // No source gives AWS's wording for these ends; they are Floci's.
    static final String IDLE_TIMED_OUT = "The session was idle for its idle timeout.";
    static final String CLUSTER_ENDED = "The session's cluster was terminated.";
    static final String USER_REQUEST = "The session was terminated by request.";

    private EmrSessions() {
    }

    /** What StartSession asks for, its configurations as raw JSON, null where it gives none. */
    record Request(String name, String executionRoleArn, String engineConfigurations, String monitoringConfiguration,
                   Long sessionIdleTimeoutInMinutes, String clientRequestToken, Map<String, String> tags) {

        /** The idle timeout the session gets: the request's, or else the default. */
        long idleTimeoutMinutes() {
            return sessionIdleTimeoutInMinutes != null ? sessionIdleTimeoutInMinutes : DEFAULT_IDLE_TIMEOUT_MINUTES;
        }
    }

    /**
     * Refuses a request the model's constraints refuse, with the ValidationException AWS's validation layer answers,
     * and one without a name, as "The --name parameter is required and must be a non-empty value", or with an idle
     * timeout outside 1 minute to 24 hours, with InvalidRequestException in Floci's own wording, as AWS's is not
     * recorded.
     */
    static void validate(Request request) {
        List<String> violations = new ArrayList<>();
        if (request.name() != null && request.name().length() > MAX_NAME_LENGTH) {
            violations.add(SmithyValidationErrors.lengthOutOfRange("name", request.name(), 0, MAX_NAME_LENGTH));
        }
        String role = request.executionRoleArn();
        if (role != null && (role.length() < MIN_ROLE_ARN_LENGTH || role.length() > MAX_ROLE_ARN_LENGTH)) {
            violations.add(SmithyValidationErrors.lengthOutOfRange("executionRoleArn", role, MIN_ROLE_ARN_LENGTH,
                    MAX_ROLE_ARN_LENGTH));
        } else if (role != null && !role.matches(ROLE_ARN_PATTERN)) {
            violations.add(SmithyValidationErrors.pattern("executionRoleArn", role, ROLE_ARN_PATTERN));
        }
        String token = request.clientRequestToken();
        if (token != null && (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH)) {
            violations.add(SmithyValidationErrors.lengthOutOfRange("clientRequestToken", token, MIN_TOKEN_LENGTH,
                    MAX_TOKEN_LENGTH));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        if (request.name() == null || request.name().isBlank()) {
            throw EmrService.invalid("Name is required.");
        }
        Long timeout = request.sessionIdleTimeoutInMinutes();
        if (timeout != null && (timeout < 1 || timeout > MAX_IDLE_TIMEOUT_MINUTES)) {
            throw EmrService.invalid("SessionIdleTimeoutInMinutes must be from 1 to " + MAX_IDLE_TIMEOUT_MINUTES
                    + " minutes (24 hours).");
        }
    }

    /**
     * Refuses a session the cluster cannot hold: StartSession's "The cluster must be in the RUNNING or WAITING state
     * and have sessions enabled", on "release emr-spark-8.0.0 or later" with "The Spark application installed on the
     * cluster", not one with "multiple primary nodes", which "are not supported for Spark Connect sessions", and at
     * most {@link #MAX_CONCURRENT_SESSIONS} of its {@code sessions} that have not ended. AWS's wording for each
     * refusal is not recorded; InvalidRequestException is the one error the model declares for them.
     */
    static void checkStartable(EmrCluster cluster, List<EmrSession> sessions, ObjectMapper objectMapper) {
        if (!cluster.isSessionEnabled()) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " does not have sessions enabled.");
        }
        if (!EmrServerlessJobRunService.sparkRuntime(cluster.getReleaseLabel())) {
            throw EmrService.invalid("Sessions are supported on release emr-spark-8.0.0 and later.");
        }
        if (!EmrSparkSteps.names(cluster.getApplications(), objectMapper, SPARK)) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " does not have Spark installed.");
        }
        if (highlyAvailable(cluster)) {
            throw EmrService.invalid("Sessions are not supported on a cluster with multiple primary nodes.");
        }
        if (!EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                && !EmrClusterLifecycle.WAITING.equals(cluster.getState())) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " is " + cluster.getState()
                    + ", and a session needs it RUNNING or WAITING.");
        }
        long open = sessions.stream().filter(session -> !ENDED.contains(session.getState())).count();
        if (open >= MAX_CONCURRENT_SESSIONS) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " already has " + MAX_CONCURRENT_SESSIONS
                    + " concurrent active sessions.");
        }
    }

    // More than one primary node: a primary instance group of more than one instance, or a primary fleet's capacity.
    private static boolean highlyAvailable(EmrCluster cluster) {
        for (EmrInstanceGroup group : cluster.getInstanceGroups()) {
            if (EmrService.isMasterGroup(group) && group.getRequestedInstanceCount() > 1) {
                return true;
            }
        }
        for (EmrInstanceFleet fleet : cluster.getInstanceFleets()) {
            if (EmrService.isMasterFleet(fleet)
                    && fleet.getTargetOnDemandCapacity() + fleet.getTargetSpotCapacity() > 1) {
                return true;
            }
        }
        return false;
    }

    /** The one of a cluster's {@code sessions} started with the client request token, or null. */
    static EmrSession startedWithToken(List<EmrSession> sessions, String clientRequestToken) {
        if (clientRequestToken == null) {
            return null;
        }
        return sessions.stream()
                .filter(session -> clientRequestToken.equals(session.getClientRequestToken()))
                .findFirst()
                .orElse(null);
    }

    /**
     * A new session on the cluster, SUBMITTED, as "When a session is first created, it enters the SUBMITTED state",
     * the cluster's {@code sequence}th, which orders it among its sessions created within the same millisecond.
     * {@code parameters} is what a retry of the request must repeat.
     */
    static EmrSession start(EmrCluster cluster, Request request, String id, String arn, String accountId,
                            Instant now, long sequence, String parameters) {
        EmrSession session = new EmrSession();
        session.setId(id);
        session.setClusterId(cluster.getId());
        session.setSequence(sequence);
        session.setName(request.name());
        session.setArn(arn);
        session.setState(SUBMITTED);
        session.setReleaseLabel(cluster.getReleaseLabel());
        session.setExecutionRoleArn(request.executionRoleArn());
        session.setAccountId(accountId);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setEngineConfigurations(request.engineConfigurations());
        session.setMonitoringConfiguration(request.monitoringConfiguration());
        session.setSessionIdleTimeoutInMinutes(request.idleTimeoutMinutes());
        session.setTags(request.tags() != null ? new LinkedHashMap<>(request.tags()) : new LinkedHashMap<>());
        session.setClientRequestToken(request.clientRequestToken());
        session.setRequest(parameters);
        return session;
    }

    /**
     * Brings a session of the cluster up to {@code now}: a SUBMITTED one is IDLE from when it was made, as nothing has
     * to start; an IDLE one ends once its idle timeout has passed since it went idle; and every one that has not
     * ended ends as its cluster shuts down, whichever comes first. Returns whether it changed.
     */
    static boolean settle(EmrCluster cluster, EmrSession session, Instant now) {
        if (ENDED.contains(session.getState())) {
            return false;
        }
        boolean changed = false;
        Instant clusterEnd = EmrService.shutDownAt(cluster, now);
        if (SUBMITTED.equals(session.getState())
                && (clusterEnd == null || session.getCreatedAt().isBefore(clusterEnd))) {
            session.setState(IDLE);
            session.setStartedAt(session.getCreatedAt());
            session.setIdleSince(session.getCreatedAt());
            changed = true;
        }
        Instant idleLimit = IDLE.equals(session.getState()) && session.getIdleSince() != null
                ? session.getIdleSince().plus(Duration.ofMinutes(session.getSessionIdleTimeoutInMinutes())) : null;
        if (idleLimit != null && !now.isBefore(idleLimit)
                && (clusterEnd == null || idleLimit.isBefore(clusterEnd))) {
            end(session, IDLE_TIMED_OUT, idleLimit);
            changed = true;
        } else if (clusterEnd != null) {
            end(session, CLUSTER_ENDED, clusterEnd);
            changed = true;
        }
        return changed;
    }

    /**
     * The one of a cluster's {@code sessions} with that id, or InvalidRequestException, the model's error, in Floci's
     * own wording.
     */
    static EmrSession find(EmrCluster cluster, List<EmrSession> sessions, String sessionId) {
        if (sessionId == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("sessionId"));
        }
        return sessions.stream()
                .filter(session -> sessionId.equals(session.getId()))
                .findFirst()
                .orElseThrow(() -> EmrService.invalid("Session " + sessionId + " does not exist on cluster "
                        + cluster.getId() + "."));
    }

    /**
     * Terminates a session that has not ended: "After you call this operation, the session enters the TERMINATING
     * state and then transitions to TERMINATED." With no server to stop, Floci's is TERMINATED at once. One that has
     * already ended is refused, in Floci's own wording, as AWS's answer is not recorded.
     */
    static void terminate(EmrSession session, Instant now) {
        requireNotEnded(session);
        end(session, USER_REQUEST, now);
    }

    /** Refuses a session that has ended, which has nothing to connect to or terminate. */
    static void requireNotEnded(EmrSession session) {
        if (ENDED.contains(session.getState())) {
            throw EmrService.invalid("Session " + session.getId() + " has already ended.");
        }
    }

    /** Refuses a ListSessions' SessionStates filter naming a state the model does not. */
    static void validateStates(List<String> states) {
        if (states != null && !STATES.containsAll(states)) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.notAllInEnum(states, "sessionStates", STATES));
        }
    }

    private static void end(EmrSession session, String reason, Instant at) {
        session.setState(TERMINATED);
        session.setStateChangeReason(reason);
        session.setEndedAt(at);
        session.setUpdatedAt(at);
        session.setIdleSince(null);
    }
}
