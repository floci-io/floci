package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.InteractiveConfiguration;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.SessionRecord;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EMR Serverless sessions in the docker runner mode, each running a Spark Connect server through the runner: the
 * session's states as its server starts, answers, takes calls and stops, with a runner that stands in for Docker.
 */
@ResourceLock(ParallelLocks.PORTS)
class EmrServerlessSessionServerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-session";
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 40123;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AccountAwareStorageBackend<SessionRecord> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
    private final FakeServerRunner runner = new FakeServerRunner();
    private final AtomicBoolean answering = new AtomicBoolean(true);
    private final Executor threads = task -> Thread.ofPlatform().daemon().start(task);
    private EmrServerlessService applications;
    private EmrServerlessJobRunService jobRuns;
    private IamService iamService;
    private RegionResolver regionResolver;
    private EmrServerlessSessionService sessions;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        clock = new MutableClock();
        regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        applications = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID), regionResolver, clock);
        iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        jobRuns = new EmrServerlessJobRunService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                iamService, regionResolver, null, objectMapper, 0, clock, runner, Runnable::run);
        sessions = service();
    }

    private EmrServerlessSessionService service() {
        return service(null);
    }

    private EmrServerlessSessionService service(ScheduledThreadPoolExecutor checks) {
        return new EmrServerlessSessionService(store, applications, jobRuns, iamService, regionResolver, null,
                objectMapper, clock, "http://localhost:4566", runner, threads, checks,
                (host, port) -> answering.get() && SERVER_HOST.equals(host) && port == SERVER_PORT ? null
                        : "Connection refused");
    }

    @Test
    void aSessionRunsItsServerAndIsIdleOnceTheServerAnswers() {
        String applicationId = application("emr-7.13.0");
        StartSessionRequest request = request();
        request.setConfigurationOverrides(Map.of("runtimeConfiguration", List.of(Map.of(
                "classification", "spark-defaults",
                "properties", Map.of("spark.driver.memory", "2g", "spark.connect.grpc.binding.address", "10.0.0.1")))));

        Session started = sessions.startSession(applicationId, request);
        Session idle = awaitState(applicationId, started.getSessionId(), "IDLE");

        assertEquals("SUBMITTED", started.getState());
        assertNull(started.getStartedAt());
        assertNotNull(idle.getStartedAt());
        assertEquals(idle.getStartedAt(), idle.getIdleSince());
        SparkJobRunner.Job job = runner.jobs.get(started.getSessionId());
        assertEquals(EmrServerlessSessionService.SPARK_CONNECT_PORT, job.listenPort());
        assertEquals(List.of("--class", "org.apache.spark.sql.connect.service.SparkConnectServer",
                EmrServerlessJobRunService.SPARK_CONNECT_JAR), job.application());
        assertEquals("15002", job.sparkDefaults().get("spark.connect.grpc.binding.port"));
        assertEquals("2g", job.sparkDefaults().get("spark.driver.memory"));
        // "spark.connect.grpc.binding.address is reserved by EMR Serverless and cannot be overridden by users."
        assertFalse(job.sparkDefaults().containsKey("spark.connect.grpc.binding.address"));
        assertEquals(ROLE, job.executionRole());
        // The session's 24 hours count from when its server answers, so the runner keeps no clock of its own.
        assertEquals(Duration.ZERO, job.timeout());
    }

    @Test
    void aSessionsTwentyFourHoursCountFromWhenItsServerAnswers() {
        String applicationId = application("emr-7.13.0");
        StartSessionRequest request = request();
        request.setIdleTimeoutMinutes(0L);
        String sessionId = sessions.startSession(applicationId, request).getSessionId();
        Session idle = awaitState(applicationId, sessionId, "IDLE");

        clock.advance(Duration.between(clock.instant(), idle.getStartedAt().plus(Duration.ofHours(24)))
                .minusSeconds(1));
        assertEquals("IDLE", sessions.getSession(applicationId, sessionId).getState());
        clock.advance(Duration.ofSeconds(1));
        Session ended = sessions.getSession(applicationId, sessionId);

        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrServerlessSessionService.HARD_LIMIT_REACHED, ended.getStateDetails());
        assertEquals(idle.getStartedAt().plus(Duration.ofHours(24)), ended.getEndedAt());
        assertTrue(runner.stopped.contains(sessionId));
    }

    @Test
    void aSessionKeepsOneCheckForItsNextDeadlineHoweverManyCallsEnd() {
        ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1);
        checks.setRemoveOnCancelPolicy(true);
        try {
            sessions = service(checks);
            String applicationId = application("emr-7.13.0");
            String sessionId = sessions.startSession(applicationId, request()).getSessionId();
            awaitState(applicationId, sessionId, "IDLE");
            String token = sessions.sessionEndpoint(applicationId, sessionId).authToken();

            for (int call = 0; call < 20; call++) {
                sessions.route(token).orElseThrow().ended().run();
            }
            assertEquals(1, checks.getQueue().size());
            sessions.terminateSession(applicationId, sessionId);
            assertEquals(0, checks.getQueue().size());
        } finally {
            checks.shutdownNow();
        }
    }

    @Test
    void aServerThatCannotStartFailsItsSessionEvenWithNoMessage() {
        runner.launchFailure = new IllegalStateException();
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();

        Session failed = awaitState(applicationId, sessionId, "FAILED");

        assertEquals(String.format(SparkConnectSessionOwner.SERVER_NOT_STARTED, "java.lang.IllegalStateException"),
                failed.getStateDetails());
    }

    @Test
    void theAwsRuntimeForApacheSparkStartsTheServerFromSparksOwnJars() {
        assertEquals(List.of("--class", "org.apache.spark.sql.connect.service.SparkConnectServer", "spark-internal"),
                EmrServerlessJobRunService.sparkConnectServer("emr-spark-8.0.0"));
    }

    @Test
    void aSessionIsStartingUntilItsServerAnswersAndRefusesCallsMeanwhile() {
        answering.set(false);
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        awaitState(applicationId, sessionId, "STARTING");
        String token = sessions.sessionEndpoint(applicationId, sessionId).authToken();

        SparkConnectTargets.Route refused = sessions.route(token).orElseThrow();

        assertEquals(SparkConnectTargets.Route.UNAVAILABLE, refused.status());
        assertEquals(String.format(EmrServerlessSessionService.NOT_READY, sessionId, "STARTING"), refused.refusal());
        answering.set(true);
        awaitState(applicationId, sessionId, "IDLE");
    }

    @Test
    void aCallMakesTheSessionBusyUntilTheLastCallEnds() {
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        awaitState(applicationId, sessionId, "IDLE");
        String token = sessions.sessionEndpoint(applicationId, sessionId).authToken();

        SparkConnectTargets.Route first = sessions.route(token).orElseThrow();
        SparkConnectTargets.Route second = sessions.route(token).orElseThrow();

        assertNull(first.refusal());
        assertEquals(SERVER_HOST, first.host());
        assertEquals(SERVER_PORT, first.port());
        Session busy = sessions.getSession(applicationId, sessionId);
        assertEquals("BUSY", busy.getState());
        assertNull(busy.getIdleSince());
        first.ended().run();
        assertEquals("BUSY", sessions.getSession(applicationId, sessionId).getState());
        clock.advance(Duration.ofMinutes(5));
        Instant before = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        second.ended().run();
        Instant after = clock.instant();
        Session idle = sessions.getSession(applicationId, sessionId);
        assertEquals("IDLE", idle.getState());
        // Idle from when the last call ended.
        assertFalse(idle.getIdleSince().isBefore(before));
        assertFalse(idle.getIdleSince().isAfter(after));
    }

    @Test
    void aBusySessionDoesNotTimeOutAndAnIdleOneEndsItsServerAtItsIdleTimeout() {
        String applicationId = application("emr-7.13.0");
        StartSessionRequest request = request();
        request.setIdleTimeoutMinutes(30L);
        String sessionId = sessions.startSession(applicationId, request).getSessionId();
        awaitState(applicationId, sessionId, "IDLE");
        SparkConnectTargets.Route call = sessions.route(sessions.sessionEndpoint(applicationId, sessionId)
                .authToken()).orElseThrow();

        clock.advance(Duration.ofMinutes(45));
        assertEquals("BUSY", sessions.getSession(applicationId, sessionId).getState());
        call.ended().run();
        clock.advance(Duration.ofMinutes(30));
        Session ended = sessions.getSession(applicationId, sessionId);

        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrServerlessSessionService.IDLE_TIMED_OUT, ended.getStateDetails());
        assertTrue(runner.stopped.contains(sessionId));
    }

    @Test
    void aTerminatedSessionIsTerminatingUntilItsServerStopsAndItsTokensGo() {
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        awaitState(applicationId, sessionId, "IDLE");
        String token = sessions.sessionEndpoint(applicationId, sessionId).authToken();

        Session terminating = sessions.terminateSession(applicationId, sessionId);
        Session terminated = awaitState(applicationId, sessionId, "TERMINATED");

        assertEquals("TERMINATING", terminating.getState());
        assertTrue(runner.stopped.contains(sessionId));
        assertNotNull(terminated.getEndedAt());
        assertTrue(sessions.route(token).isEmpty());
    }

    @Test
    void aSessionWhoseServerExitsOnItsOwnHasFailed() {
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        awaitState(applicationId, sessionId, "IDLE");

        runner.end(sessionId).complete(SparkJobRunner.Outcome.exitedWith(1));
        Session failed = awaitState(applicationId, sessionId, "FAILED");

        assertEquals(String.format(SparkConnectSessionOwner.SERVER_EXITED, 1), failed.getStateDetails());
    }

    @Test
    void aSessionWhoseServerNoThreadRunsAnyMoreWasInterruptedByARestart() {
        String applicationId = application("emr-7.13.0");
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        awaitState(applicationId, sessionId, "IDLE");

        // A new process: the stored session is there, but no thread of it runs the session's server.
        Session after = service().getSession(applicationId, sessionId);

        assertEquals("FAILED", after.getState());
        assertEquals(EmrServerlessSessionService.INTERRUPTED, after.getStateDetails());
        // Its server's container, should the startup's cleanup have left it, goes too.
        assertEquals(List.of(sessionId), runner.leftoversRemoved);
    }

    // Polls until the session is in state, as its server's thread moves it on its own.
    private Session awaitState(String applicationId, String sessionId, String state) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            Session session = sessions.getSession(applicationId, sessionId);
            if (state.equals(session.getState())) {
                return session;
            }
            if (System.nanoTime() - deadline > 0) {
                fail("Session " + sessionId + " is " + session.getState() + ", not " + state);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static StartSessionRequest request() {
        StartSessionRequest request = new StartSessionRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        return request;
    }

    private String application(String releaseLabel) {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("sessions");
        create.setReleaseLabel(releaseLabel);
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        InteractiveConfiguration interactive = new InteractiveConfiguration();
        interactive.setProperty("sessionEnabled", true);
        create.setInteractiveConfiguration(interactive);
        return applications.createApplication(create).getApplicationId();
    }

    /** Runs each session's server as the Docker runner would, until it is stopped or told how it ends. */
    private static final class FakeServerRunner implements SparkJobRunner {
        final Map<String, Job> jobs = new ConcurrentHashMap<>();
        final Set<String> stopped = ConcurrentHashMap.newKeySet();
        final List<String> leftoversRemoved = new CopyOnWriteArrayList<>();
        volatile RuntimeException launchFailure;
        private final Map<String, CompletableFuture<Outcome>> ends = new ConcurrentHashMap<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.put(job.runId(), job);
            if (launchFailure != null) {
                throw launchFailure;
            }
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
