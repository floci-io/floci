package io.floci.aws.services.emr;

import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrSession;
import io.floci.aws.services.emrserverless.SessionServers;
import io.floci.aws.services.emrserverless.SparkConnectSessionOwner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * EMR sessions in the docker runner mode, each running a Spark Connect server on its cluster as a step runs: the
 * session's states as its server starts, answers, takes calls and stops, with a runner that stands in for Docker.
 */
@ResourceLock(ParallelLocks.PORTS)
class EmrSessionServerTest {

    private static final String RELEASE = "emr-spark-8.1.0";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-session-runtime";
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 40321;

    private final FakeServerRunner runner = new FakeServerRunner();
    private final AtomicBoolean answering = new AtomicBoolean(true);
    private final SessionServers.Probe probe = (host, port) ->
            answering.get() && SERVER_HOST.equals(host) && port == SERVER_PORT ? null : "Connection refused";
    private final EmrServiceFixture fixture = EmrServiceFixture.dockerSessions(runner, mock(IamService.class), probe);
    private final EmrService service = fixture.service;

    @Test
    void aSessionRunsItsServerOnItsClusterAndIsIdleOnceTheServerAnswers() {
        String clusterId = sessionCluster();
        EmrSession started = service.startSession(clusterId, new EmrSessions.Request("notebook", ROLE,
                "[{\"Classification\":\"spark-defaults\",\"Properties\":{\"spark.executor.memory\":\"4g\"}}]", null,
                null, null, Map.of()));

        EmrSession idle = awaitState(clusterId, started.getId(), "IDLE");

        assertEquals("SUBMITTED", started.getState());
        assertNull(started.getServerUrl());
        assertNotNull(idle.getStartedAt());
        assertEquals(idle.getStartedAt(), idle.getIdleSince());
        // Floci's own address, whose proxy takes only a call with GetSessionEndpoint's token on to the server.
        assertEquals("sc://localhost:4566", idle.getServerUrl());
        SparkJobRunner.Job job = runner.jobs.get(started.getId());
        assertEquals(EmrSessions.SPARK_CONNECT_PORT, job.listenPort());
        assertEquals(List.of("--class", "org.apache.spark.sql.connect.service.SparkConnectServer", "spark-internal"),
                job.application());
        assertEquals("15002", job.sparkDefaults().get("spark.connect.grpc.binding.port"));
        assertEquals("4g", job.sparkDefaults().get("spark.executor.memory"));
        assertEquals(ROLE, job.executionRole());
        // On the cluster's storage, its HDFS running, as a step's driver is.
        assertNotNull(job.storage());
        assertTrue(fixture.clusterStorage.created.contains(clusterId), fixture.clusterStorage.created.toString());
    }

    @Test
    void aCallThroughItsTokenMakesTheSessionBusyUntilItEnds() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "IDLE");
        SparkConnectEndpoint endpoint = service.sessionEndpoint(clusterId, sessionId);

        SparkConnectTargets.Route route = service.route(endpoint.authToken()).orElseThrow();

        assertNull(route.refusal());
        assertEquals(SERVER_HOST, route.host());
        assertEquals(SERVER_PORT, route.port());
        assertEquals("BUSY", service.getSession(clusterId, sessionId).getState());
        assertNull(service.getSession(clusterId, sessionId).getIdleSince());
        route.ended().run();
        assertEquals("IDLE", service.getSession(clusterId, sessionId).getState());
        assertTrue(service.route("not-a-token").isEmpty());
    }

    @Test
    void aSessionIsStartingUntilItsServerAnswersAndRefusesCallsMeanwhile() {
        answering.set(false);
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "STARTING");

        SparkConnectTargets.Route refused = service.route(service.sessionEndpoint(clusterId, sessionId).authToken())
                .orElseThrow();

        assertEquals(SparkConnectTargets.Route.UNAVAILABLE, refused.status());
        assertEquals(String.format(EmrSessions.NOT_READY, sessionId, "STARTING"), refused.refusal());
        answering.set(true);
        awaitState(clusterId, sessionId, "IDLE");
    }

    @Test
    void aTerminatedSessionIsTerminatingUntilItsServerStopsAndItsTokensGo() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "IDLE");
        String token = service.sessionEndpoint(clusterId, sessionId).authToken();

        EmrSession terminating = service.terminateSession(clusterId, sessionId);
        EmrSession terminated = awaitState(clusterId, sessionId, "TERMINATED");

        assertEquals("TERMINATING", terminating.getState());
        assertTrue(runner.stopped.contains(sessionId));
        assertEquals(EmrSessions.USER_REQUEST, terminated.getStateChangeReason());
        assertNull(terminated.getServerUrl(), "an ended session's server has gone");
        assertTrue(service.route(token).isEmpty());
    }

    @Test
    void aSessionWhoseServerExitsOnItsOwnHasFailed() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "IDLE");

        runner.end(sessionId).complete(SparkJobRunner.Outcome.exitedWith(1));
        EmrSession failed = awaitState(clusterId, sessionId, "FAILED");

        assertEquals(String.format(SparkConnectSessionOwner.SERVER_EXITED, 1), failed.getStateChangeReason());
    }

    @Test
    void anIdleSessionEndsItsServerAtItsIdleTimeoutAndTheClustersEndEndsAnother() {
        String clusterId = sessionCluster();
        String timingOut = service.startSession(clusterId, new EmrSessions.Request("short", null, null, null, 30L,
                null, Map.of())).getId();
        awaitState(clusterId, timingOut, "IDLE");
        String other = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, other, "IDLE");

        fixture.clock.advance(Duration.ofMinutes(30));
        assertEquals("TERMINATED", service.getSession(clusterId, timingOut).getState());
        assertTrue(runner.stopped.contains(timingOut));
        assertFalse(runner.stopped.contains(other));
        service.terminateJobFlows(List.of(clusterId));

        EmrSession ended = service.getSession(clusterId, other);
        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrSessions.CLUSTER_ENDED, ended.getStateChangeReason());
        assertTrue(runner.stopped.contains(other));
    }

    @Test
    void theClustersEndStopsItsBusySessionsServerAndItsStorageGoesOnceTheServerHasGone() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "IDLE");
        // BUSY, so no idle check would ever stop it.
        SparkConnectTargets.Route call = service.route(service.sessionEndpoint(clusterId, sessionId).authToken())
                .orElseThrow();

        service.terminateJobFlows(List.of(clusterId));

        // No read of the session: the cluster's end stopped its server.
        assertTrue(runner.stopped.contains(sessionId));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fixture.stepThreadsWaiting() == 0 && System.nanoTime() - deadline < 0) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertFalse(fixture.clusterStorage.removed.contains(clusterId));
        fixture.runStepThread();
        assertTrue(fixture.clusterStorage.removed.contains(clusterId), fixture.clusterStorage.removed.toString());
        call.ended().run();
    }

    @Test
    void aSessionWhoseServerNoThreadRunsAnyMoreWasInterruptedByARestart() {
        String clusterId = sessionCluster();
        String sessionId = service.startSession(clusterId, request()).getId();
        awaitState(clusterId, sessionId, "IDLE");

        EmrSession after = fixture.restart().service.getSession(clusterId, sessionId);

        assertEquals("FAILED", after.getState());
        assertEquals(EmrSessions.INTERRUPTED, after.getStateChangeReason());
        assertEquals(List.of(sessionId), runner.leftoversRemoved);
    }

    private String sessionCluster() {
        EmrCluster cluster = cluster("sessions", true);
        cluster.setReleaseLabel(RELEASE);
        cluster.setApplications("[{\"Name\":\"Spark\"}]");
        cluster.setSessionEnabled(true);
        return service.runJobFlow(cluster, REGION).getId();
    }

    private static EmrSessions.Request request() {
        return new EmrSessions.Request("notebook", null, null, null, null, null, Map.of());
    }

    // Polls until the session is in state, as its server's thread moves it on its own.
    private EmrSession awaitState(String clusterId, String sessionId, String state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            EmrSession session = service.getSession(clusterId, sessionId);
            if (state.equals(session.getState())) {
                return session;
            }
            if (System.nanoTime() - deadline > 0) {
                fail("Session " + sessionId + " is " + session.getState() + ", not " + state + ": "
                        + session.getStateChangeReason());
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    /** Runs each session's server as the Docker runner would, until it is stopped or told how it ends. */
    private static final class FakeServerRunner implements SparkJobRunner {
        final Map<String, Job> jobs = new ConcurrentHashMap<>();
        final Set<String> stopped = ConcurrentHashMap.newKeySet();
        final List<String> leftoversRemoved = new CopyOnWriteArrayList<>();
        private final Map<String, CompletableFuture<Outcome>> ends = new ConcurrentHashMap<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.put(job.runId(), job);
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            progress.started();
            progress.listening(SERVER_HOST, SERVER_PORT);
            return end(job.runId()).join();
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
            end(runId).complete(Outcome.halted());
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }

        @Override
        public void removeLeftoverDriver(String service, String runId) {
            leftoversRemoved.add(runId);
        }

        CompletableFuture<Outcome> end(String runId) {
            return ends.computeIfAbsent(runId, id -> new CompletableFuture<>());
        }
    }
}
