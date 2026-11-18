package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AppUiRoute;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.InteractiveConfiguration;
import io.floci.aws.services.emrserverless.model.JobDriver;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.StartJobRunRequest;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The links GetDashboardForJobRun and GetResourceDashboard give in the docker runner mode
 * ({@link EmrServerlessAppUis}): while a Spark run's attempt, or a session, has not ended, its driver's live Spark UI,
 * on a host of its own that routes to where Floci reaches the driver's UI, once the driver runs; any other, Floci's
 * page.
 */
class EmrServerlessAppUisTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-runs";
    private static final String BASE_URL = "http://localhost:4566";
    private static final String PAGE = BASE_URL + EmrServerlessDashboards.PATH + "?";
    private static final ContainerLifecycleManager.EndpointInfo DRIVER_UI =
            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", 41040);

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final FakeDriverRunner runner = new FakeDriverRunner();
    private final Executor threads = task -> Thread.ofPlatform().daemon().start(task);
    private final MutableClock clock = new MutableClock();
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
    private EmrServerlessService applications;
    private EmrServerlessJobRunService jobRuns;
    private EmrServerlessSessionService sessions;
    private EmrServerlessAppUis appUis;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        applications = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID), regionResolver, clock);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        jobRuns = new EmrServerlessJobRunService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                iamService, regionResolver, null, objectMapper, 0, clock, runner, threads);
        sessions = new EmrServerlessSessionService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                jobRuns, iamService, regionResolver, null, objectMapper, clock, BASE_URL, runner, threads, null,
                (host, port) -> null);
        appUis = new EmrServerlessAppUis(new EmrServerlessDashboards(BASE_URL, clock, new byte[32]), jobRuns, sessions,
                regionResolver, BASE_URL + "/", clock);
    }

    @AfterEach
    void tearDown() {
        runner.starting.complete(null);
        runner.ends.values().forEach(end -> end.complete(SparkJobRunner.Outcome.halted()));
    }

    @Test
    void aRunningSparkRunsLinkOpensItsDriversSparkUiOnAHostOfItsOwn() {
        String applicationId = application("SPARK");
        String jobRunId = jobRuns.startJobRun(applicationId, sparkRun()).getJobRunId();
        awaitRun(applicationId, jobRunId, "RUNNING");

        URI url = URI.create(appUis.jobRunUrl(applicationId, jobRunId, 1));

        assertTrue(url.getAuthority().matches("[a-z2-7]{26}-4040\\.emr-serverless-ui\\.localhost:4566"),
                url.toString());
        assertEquals("/", url.getPath());
        AppUiRoute route = appUis.route(url.getAuthority(), "http");
        assertNull(route.refusal());
        assertEquals("127.0.0.1", route.host());
        assertEquals(41040, route.port());
        // Until the driver's Spark has started its UI, a request to it is asked again later.
        assertEquals(503, route.unansweredStatus());
        assertEquals(EmrServerlessAppUis.NOT_ANSWERING, route.unanswered());
    }

    @Test
    void aLinkGivenBeforeTheDriverRunsIsRefusedUntilItDoes() {
        runner.starting = new CompletableFuture<>();
        String applicationId = application("SPARK");
        String jobRunId = jobRuns.startJobRun(applicationId, sparkRun()).getJobRunId();
        String authority = URI.create(appUis.jobRunUrl(applicationId, jobRunId, 1)).getAuthority();

        AppUiRoute notYet = appUis.route(authority, "http");
        runner.starting.complete(null);
        awaitRun(applicationId, jobRunId, "RUNNING");

        assertEquals(503, notYet.status());
        assertEquals(EmrServerlessAppUis.NOT_RUNNING, notYet.refusal());
        assertEquals(41040, appUis.route(authority, "http").port());
    }

    @Test
    void onceItsAttemptHasEndedTheLinkReachesNothingAndANewOneOpensFlocisPage() {
        String applicationId = application("SPARK");
        String jobRunId = jobRuns.startJobRun(applicationId, sparkRun()).getJobRunId();
        awaitRun(applicationId, jobRunId, "RUNNING");
        String authority = URI.create(appUis.jobRunUrl(applicationId, jobRunId, 1)).getAuthority();

        runner.end(jobRunId).complete(SparkJobRunner.Outcome.exitedWith(0));
        awaitRun(applicationId, jobRunId, "SUCCESS");

        AppUiRoute ended = appUis.route(authority, "http");
        assertEquals(404, ended.status());
        assertEquals(String.format(EmrServerlessAppUis.RUN_ENDED, 1, jobRunId), ended.refusal());
        assertTrue(appUis.jobRunUrl(applicationId, jobRunId, 1).startsWith(PAGE));
    }

    @Test
    void aRunWhoseLogsAreNotInManagedStorageOrAHiveRunOpensFlocisPage() {
        String sparkApplication = application("SPARK");
        StartJobRunRequest unmanaged = sparkRun();
        unmanaged.setConfigurationOverrides(Map.of("monitoringConfiguration",
                Map.of("managedPersistenceMonitoringConfiguration", Map.of("enabled", false))));
        String unmanagedRun = jobRuns.startJobRun(sparkApplication, unmanaged).getJobRunId();
        String hiveApplication = application("HIVE");
        String hiveRun = jobRuns.startJobRun(hiveApplication, hiveRun()).getJobRunId();
        awaitRun(sparkApplication, unmanagedRun, "RUNNING");
        awaitRun(hiveApplication, hiveRun, "RUNNING");

        // "Otherwise, you can't use the built-in application UIs."
        assertTrue(appUis.jobRunUrl(sparkApplication, unmanagedRun, 1).startsWith(PAGE));
        // Floci runs no Tez UI.
        assertTrue(appUis.jobRunUrl(hiveApplication, hiveRun, 1).startsWith(PAGE));
    }

    @Test
    void aLinkPastItsHourNotThisProcesssOrToAnotherPortIsRefused() {
        String applicationId = application("SPARK");
        String jobRunId = jobRuns.startJobRun(applicationId, sparkRun()).getJobRunId();
        awaitRun(applicationId, jobRunId, "RUNNING");
        String authority = URI.create(appUis.jobRunUrl(applicationId, jobRunId, 1)).getAuthority();
        String secret = authority.substring(0, 26);

        assertEquals(404, appUis.route(secret + "-18080.emr-serverless-ui.localhost:4566", "http").status());
        assertEquals(403, appUis.route("a".repeat(26) + "-4040.emr-serverless-ui.localhost:4566", "http").status());
        // Not a link's host at all, which goes on to the rest of Floci.
        assertNull(appUis.route(secret + "-4040.emrappui.localhost:4566", "http"));
        clock.advance(EmrServerlessDashboards.VALIDITY);
        AppUiRoute expired = appUis.route(authority, "http");
        assertEquals(403, expired.status());
        assertEquals(EmrServerlessDashboardController.INVALID, expired.refusal());
    }

    @Test
    void aSessionsLinkOpensItsServersSparkUiWhileItIsActive() {
        String applicationId = application("SPARK");
        String sessionId = sessions.startSession(applicationId, sessionRequest()).getSessionId();
        awaitSession(applicationId, sessionId, "IDLE");
        String authority = URI.create(appUis.sessionUrl(applicationId,
                sessions.getSession(applicationId, sessionId))).getAuthority();
        assertEquals(41040, appUis.route(authority, "http").port());

        sessions.terminateSession(applicationId, sessionId);
        Session terminated = awaitSession(applicationId, sessionId, "TERMINATED");

        AppUiRoute ended = appUis.route(authority, "http");
        assertEquals(404, ended.status());
        assertEquals(String.format(EmrServerlessAppUis.SESSION_ENDED, sessionId), ended.refusal());
        assertTrue(appUis.sessionUrl(applicationId, terminated).startsWith(PAGE));
    }

    @Test
    void inTheImmediateRunnerModeEveryLinkOpensFlocisPage() {
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        EmrServerlessJobRunService immediate = new EmrServerlessJobRunService(
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications, iamService, regionResolver, null,
                objectMapper, 60, clock);
        EmrServerlessAppUis pages = new EmrServerlessAppUis(new EmrServerlessDashboards(BASE_URL, clock,
                new byte[32]), immediate, sessions, regionResolver, BASE_URL, clock);
        String applicationId = application("SPARK");
        String jobRunId = immediate.startJobRun(applicationId, sparkRun()).getJobRunId();

        assertEquals("RUNNING", immediate.getJobRun(applicationId, jobRunId, null).getState());
        assertTrue(pages.jobRunUrl(applicationId, jobRunId, 1).startsWith(PAGE));
    }

    private void awaitRun(String applicationId, String jobRunId, String state) {
        await(() -> jobRuns.getJobRun(applicationId, jobRunId, null).getState(), state, "Job run " + jobRunId);
    }

    private Session awaitSession(String applicationId, String sessionId, String state) {
        await(() -> sessions.getSession(applicationId, sessionId).getState(), state, "Session " + sessionId);
        return sessions.getSession(applicationId, sessionId);
    }

    // Polls until what is looked at is in state, as a run's or a session's thread moves it on its own.
    private static void await(Supplier<String> current, String state, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!state.equals(current.get())) {
            if (System.nanoTime() - deadline > 0) {
                fail(what + " is " + current.get() + ", not " + state);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private String application(String type) {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("dashboards");
        create.setReleaseLabel("emr-7.13.0");
        create.setType(type);
        create.setClientToken(UUID.randomUUID().toString());
        if ("SPARK".equals(type)) {
            InteractiveConfiguration interactive = new InteractiveConfiguration();
            interactive.setProperty("sessionEnabled", true);
            create.setInteractiveConfiguration(interactive);
        }
        return applications.createApplication(create).getApplicationId();
    }

    private static StartJobRunRequest sparkRun() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/report.py");
        JobDriver driver = new JobDriver();
        driver.setSparkSubmit(sparkSubmit);
        return runRequest(driver);
    }

    private static StartJobRunRequest hiveRun() {
        JobDriver.Hive hive = new JobDriver.Hive();
        hive.setQuery("s3://bucket/queries/report.sql");
        JobDriver driver = new JobDriver();
        driver.setHive(hive);
        return runRequest(driver);
    }

    private static StartJobRunRequest runRequest(JobDriver driver) {
        StartJobRunRequest request = new StartJobRunRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        request.setJobDriver(driver);
        return request;
    }

    private static StartSessionRequest sessionRequest() {
        StartSessionRequest request = new StartSessionRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        return request;
    }

    /**
     * Runs each driver as the Docker runner would, its Spark UI reachable from once it starts until it ends: once
     * {@link #starting} completes, and until it is stopped or told how it ends.
     */
    private static final class FakeDriverRunner implements SparkJobRunner {
        volatile CompletableFuture<Void> starting = CompletableFuture.completedFuture(null);
        final Map<String, CompletableFuture<Outcome>> ends = new ConcurrentHashMap<>();
        private final Map<String, ContainerLifecycleManager.EndpointInfo> driverUis = new ConcurrentHashMap<>();

        @Override
        public Outcome run(Job job, Progress progress) {
            starting.join();
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            driverUis.put(job.runId(), DRIVER_UI);
            try {
                progress.started();
                if (job.listenPort() > 0) {
                    progress.listening("127.0.0.1", 40123);
                }
                return end(job.runId()).join();
            } finally {
                driverUis.remove(job.runId());
            }
        }

        @Override
        public Optional<ContainerLifecycleManager.EndpointInfo> driverUi(String runId) {
            return Optional.ofNullable(driverUis.get(runId));
        }

        @Override
        public boolean servesDriverUis(String dockerNetwork) {
            return true;
        }

        @Override
        public void stop(String runId) {
            end(runId).complete(Outcome.halted());
        }

        @Override
        public void removeLeftoverDrivers(String service) {
        }

        CompletableFuture<Outcome> end(String runId) {
            return ends.computeIfAbsent(runId, id -> new CompletableFuture<>());
        }
    }
}
