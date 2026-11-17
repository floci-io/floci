package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.EmrSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A cluster's Spark Connect sessions, through {@link EmrService}: their lifecycle and what refuses one. */
class EmrSessionsTest {

    private static final String RELEASE = "emr-spark-8.0.0";
    private static final String SPARK = "[{\"Name\":\"Spark\"}]";

    private final EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 0);
    private final EmrService service = fixture.service;

    @Test
    void aSessionIsSubmittedAsItStartsAndIdleOnceRead() {
        String clusterId = sessionCluster();

        EmrSession started = service.startSession(clusterId, request("notebook"));

        assertEquals("SUBMITTED", started.getState());
        assertTrue(started.getId().matches("is-[A-Z0-9]{13}"), started.getId());
        assertEquals("arn:aws:elasticmapreduce:us-east-1:000000000000:cluster/" + clusterId + "/session/"
                + started.getId(), started.getArn());
        assertEquals("000000000000", started.getAccountId());
        EmrSession read = service.getSession(clusterId, started.getId());
        assertEquals("IDLE", read.getState());
        assertEquals(read.getCreatedAt(), read.getStartedAt());
        assertEquals(read.getCreatedAt(), read.getIdleSince());
        assertEquals(60, read.getSessionIdleTimeoutInMinutes());
        assertEquals(RELEASE, read.getReleaseLabel());
        assertEquals("notebook", read.getName());
    }

    @Test
    void anIdleSessionEndsOnceItsIdleTimeoutHasPassed() {
        String clusterId = sessionCluster();
        EmrSessions.Request request = request("short", 30L, null);
        String sessionId = service.startSession(clusterId, request).getId();
        Instant idleSince = service.getSession(clusterId, sessionId).getIdleSince();

        fixture.clock.advance(Duration.ofMinutes(29));
        assertEquals("IDLE", service.getSession(clusterId, sessionId).getState());
        fixture.clock.advance(Duration.ofMinutes(1));
        EmrSession ended = service.getSession(clusterId, sessionId);

        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrSessions.IDLE_TIMED_OUT, ended.getStateChangeReason());
        assertEquals(idleSince.plus(Duration.ofMinutes(30)), ended.getEndedAt());
        assertNull(ended.getIdleSince());
    }

    @Test
    void terminatingTheClusterEndsItsSessions() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request("on-a-cluster")).getId();
        service.getSession(clusterId, sessionId);

        service.terminateJobFlows(List.of(clusterId));

        EmrSession ended = service.getSession(clusterId, sessionId);
        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrSessions.CLUSTER_ENDED, ended.getStateChangeReason());
    }

    @Test
    void aClusterThatCannotHoldASessionRefusesIt() {
        assertInvalid(clusterOf(RELEASE, SPARK, false), "does not have sessions enabled");
        assertInvalid(clusterOf("emr-7.5.0", SPARK, true), "emr-spark-8.0.0");
        assertInvalid(clusterOf("emr-spark-7.9.0", SPARK, true), "emr-spark-8.0.0");
        assertInvalid(clusterOf(RELEASE, "[{\"Name\":\"Hadoop\"}]", true), "does not have Spark installed");

        EmrCluster highlyAvailable = clusterSpec(RELEASE, SPARK, true);
        EmrInstanceGroup primaries = new EmrInstanceGroup();
        primaries.setInstanceGroupType("MASTER");
        primaries.setInstanceType("m5.xlarge");
        primaries.setRequestedInstanceCount(3);
        highlyAvailable.getInstanceGroups().add(primaries);
        assertInvalid(service.runJobFlow(highlyAvailable, REGION).getId(), "multiple primary nodes");

        EmrServiceFixture starting = new EmrServiceFixture(600, 0, 0);
        String startingId = starting.service.runJobFlow(clusterSpec(RELEASE, SPARK, true), REGION).getId();
        AwsException notReady = assertThrows(AwsException.class,
                () -> starting.service.startSession(startingId, request("early")));
        assertTrue(notReady.getMessage().contains("RUNNING or WAITING"), notReady.getMessage());
    }

    @Test
    void aRequestIsRefusedWithoutANameOrWithAnIdleTimeoutPastADayOrAMalformedRole() {
        String clusterId = sessionCluster();

        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.startSession(clusterId, request(null))).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.startSession(clusterId, request(" "))).getErrorCode());
        for (long minutes : new long[]{0, 1441}) {
            assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                    () -> service.startSession(clusterId, request("t", minutes, null))).getErrorCode());
        }
        assertEquals(1440, service.getSession(clusterId,
                service.startSession(clusterId, request("t", 1440L, null)).getId()).getSessionIdleTimeoutInMinutes());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.startSession(clusterId, request("r", null, "arn:aws:iam::000000000000:user/u")))
                .getErrorCode());
    }

    @Test
    void aRetryWithTheSameTokenReturnsTheOriginalAnswerAndADifferentRequestIsRefused() {
        String clusterId = sessionCluster();
        String token = UUID.randomUUID().toString();
        EmrSession started = service.startSession(clusterId,
                new EmrSessions.Request("retried", null, null, null, null, token, null));
        service.getSession(clusterId, started.getId());

        // The same session asked for again, its idle timeout and tags now given at what they were.
        EmrSession retried = service.startSession(clusterId,
                new EmrSessions.Request("retried", null, null, null, 60L, token, Map.of()));
        assertEquals(started.getId(), retried.getId());
        assertEquals("SUBMITTED", retried.getState());

        AwsException conflict = assertThrows(AwsException.class, () -> service.startSession(clusterId,
                new EmrSessions.Request("other", null, null, null, null, token, Map.of())));
        assertEquals("InvalidRequestException", conflict.getErrorCode());
        assertEquals(1, service.listSessions(clusterId, null, null, null).items().size());
    }

    @Test
    void aClusterHoldsAtMostAThousandSessionsThatHaveNotEnded() {
        String clusterId = sessionCluster();
        Instant now = fixture.clock.instant();
        for (int i = 0; i < EmrSessions.MAX_CONCURRENT_SESSIONS; i++) {
            storeSession(clusterId, "is-" + String.format("%013d", i), i + 1, now);
        }

        AwsException full = assertThrows(AwsException.class, () -> service.startSession(clusterId, request("one")));
        assertTrue(full.getMessage().contains("1000 concurrent active sessions"), full.getMessage());

        service.terminateSession(clusterId, "is-0000000000000");
        assertEquals("SUBMITTED", service.startSession(clusterId, request("one")).getState());
    }

    @Test
    void sessionsMadeInTheSameMillisecondListTheNewerFirst() {
        String clusterId = sessionCluster();
        Instant now = fixture.clock.instant();
        storeSession(clusterId, "is-FIRST0000000", 1, now);
        storeSession(clusterId, "is-SECOND000000", 2, now);

        assertEquals(List.of("is-SECOND000000", "is-FIRST0000000"),
                ids(service.listSessions(clusterId, null, null, null)));
        String third = service.startSession(clusterId, request("third")).getId();
        assertEquals(List.of(third, "is-SECOND000000", "is-FIRST0000000"),
                ids(service.listSessions(clusterId, null, null, null)));
    }

    @Test
    void aSessionThatHasEndedCannotBeTerminatedAgainNorConnectedTo() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request("once")).getId();

        EmrSession terminated = service.terminateSession(clusterId, sessionId);

        assertEquals("TERMINATED", terminated.getState());
        assertEquals(EmrSessions.USER_REQUEST, terminated.getStateChangeReason());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.terminateSession(clusterId, sessionId)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.sessionEndpoint(clusterId, sessionId)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.getSession(clusterId, "is-NONE")).getErrorCode());
    }

    @Test
    void anEndpointsTokenLastsAnHourAndEachCallGivesANewOne() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request("connect")).getId();

        SparkConnectEndpoint endpoint = service.sessionEndpoint(clusterId, sessionId);

        assertEquals("http://localhost:4566/_aws/emr/sessions/" + clusterId + "/" + sessionId, endpoint.url());
        assertTrue(endpoint.authToken().matches("[0-9a-f]{64}"), endpoint.authToken());
        assertTrue(Duration.between(fixture.clock.instant(), endpoint.authTokenExpiresAt())
                .compareTo(Duration.ofMinutes(59)) > 0);
        assertNotEquals(endpoint.authToken(), service.sessionEndpoint(clusterId, sessionId).authToken());
    }

    @Test
    void listSessionsGivesTheNewestFirstFilteredByStateAPageAtATime() {
        String clusterId = sessionCluster();
        String older = service.startSession(clusterId, request("older")).getId();
        fixture.clock.advance(Duration.ofSeconds(1));
        String newer = service.startSession(clusterId, request("newer")).getId();
        fixture.clock.advance(Duration.ofSeconds(1));
        String ended = service.startSession(clusterId, request("ended")).getId();
        service.terminateSession(clusterId, ended);

        assertEquals(List.of(ended, newer, older), ids(service.listSessions(clusterId, null, null, null)));
        assertEquals(List.of(newer, older), ids(service.listSessions(clusterId, List.of("IDLE"), null, null)));
        PaginatedResult<EmrSession> first = service.listSessions(clusterId, null, 2, null);
        assertEquals(List.of(ended, newer), ids(first));
        assertEquals(List.of(older), ids(service.listSessions(clusterId, null, 2, first.nextToken())));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.listSessions(clusterId, List.of("RUNNING"), null, null)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.listSessions(clusterId, null, 101, null)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.listSessions(clusterId, null, null, "not-a-token")).getErrorCode());
    }

    private void assertInvalid(String clusterId, String message) {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.startSession(clusterId, request("refused")));
        assertEquals("InvalidRequestException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains(message), refused.getMessage());
    }

    private static List<String> ids(PaginatedResult<EmrSession> page) {
        return page.items().stream().map(EmrSession::getId).toList();
    }

    private String sessionCluster() {
        return clusterOf(RELEASE, SPARK, true);
    }

    private String clusterOf(String releaseLabel, String applications, boolean sessionEnabled) {
        return service.runJobFlow(clusterSpec(releaseLabel, applications, sessionEnabled), REGION).getId();
    }

    private static EmrCluster clusterSpec(String releaseLabel, String applications, boolean sessionEnabled) {
        EmrCluster cluster = cluster("sessions", true);
        cluster.setReleaseLabel(releaseLabel);
        cluster.setApplications(applications);
        cluster.setSessionEnabled(sessionEnabled);
        return cluster;
    }

    private static EmrSessions.Request request(String name) {
        return request(name, null, null);
    }

    private static EmrSessions.Request request(String name, Long idleTimeoutMinutes, String role) {
        return new EmrSessions.Request(name, role, null, null, idleTimeoutMinutes, null, Map.of());
    }

    // An IDLE session of the cluster, stored as made, the cluster's sequence-th.
    private void storeSession(String clusterId, String id, long sequence, Instant createdAt) {
        AccountAwareStorageBackend<EmrSession> sessions = fixture.storage.create("emr", "emr-sessions.json",
                new TypeReference<Map<String, EmrSession>>() {});
        EmrSession session = new EmrSession();
        session.setId(id);
        session.setClusterId(clusterId);
        session.setSequence(sequence);
        session.setState("IDLE");
        session.setCreatedAt(createdAt);
        session.setIdleSince(createdAt);
        session.setSessionIdleTimeoutInMinutes(EmrSessions.MAX_IDLE_TIMEOUT_MINUTES);
        sessions.put(id, session);
    }
}
