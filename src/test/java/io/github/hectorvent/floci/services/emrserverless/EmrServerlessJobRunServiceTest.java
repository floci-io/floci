package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunRecord;
import io.github.hectorvent.floci.services.emrserverless.model.RuntimeConfiguration;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmrServerlessJobRunServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private EmrServerlessService applications;
    private IamService iamService;
    private AccountAwareStorageBackend<JobRunRecord> store;
    private MutableClock clock;
    private String applicationId;
    // The docker runner mode's thread pool, run by hand: a container run's thread is the test's.
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        clock = new MutableClock();
        applications = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID),
                new RegionResolver(REGION, ACCOUNT_ID), clock);
        iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);

        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("jobs");
        create.setReleaseLabel("emr-7.5.0");
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        applicationId = applications.createApplication(create).getApplicationId();
    }

    private EmrServerlessJobRunService service(int runDurationSeconds) {
        return new EmrServerlessJobRunService(store, applications, iamService, new RegionResolver(REGION, ACCOUNT_ID),
                null, objectMapper, runDurationSeconds, clock);
    }

    private static StartJobRunRequest request(String mode, Long executionTimeoutMinutes) {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/app.jar");
        JobDriver driver = new JobDriver();
        driver.setSparkSubmit(sparkSubmit);
        StartJobRunRequest request = new StartJobRunRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        request.setJobDriver(driver);
        request.setMode(mode);
        request.setExecutionTimeoutMinutes(executionTimeoutMinutes);
        return request;
    }

    @Test
    void runSucceedsAsSoonAsItStartsByDefault() {
        JobRun run = service(0).startJobRun(applicationId, request(null, null));

        assertEquals("SUCCESS", run.getState());
        assertEquals(run.getCreatedAt(), run.getStartedAt());
        assertEquals(run.getCreatedAt(), run.getEndedAt());
        assertEquals(run.getCreatedAt(), run.getUpdatedAt());
        assertEquals(0, run.getTotalExecutionDurationSeconds());
    }

    @Test
    void positiveDurationKeepsTheRunRunningUntilItHasPassed() {
        EmrServerlessJobRunService service = service(30);
        JobRun started = service.startJobRun(applicationId, request(null, null));
        String jobRunId = started.getJobRunId();
        Instant submittedAt = started.getCreatedAt();

        clock.advance(Duration.ofSeconds(29));
        JobRun running = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("RUNNING", running.getState());
        assertNull(running.getEndedAt());
        assertNull(running.getTotalExecutionDurationSeconds());

        clock.advance(Duration.ofSeconds(1));
        JobRun finished = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("SUCCESS", finished.getState());
        assertEquals(submittedAt.plusSeconds(30), finished.getEndedAt());
        assertEquals(submittedAt.plusSeconds(30), finished.getUpdatedAt());
        assertEquals(30, finished.getTotalExecutionDurationSeconds());
    }

    @Test
    void cancellingARunningRunEndsItCancelled() {
        EmrServerlessJobRunService service = service(60);
        String jobRunId = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        clock.advance(Duration.ofSeconds(10));

        JobRun cancelled = service.cancelJobRun(applicationId, jobRunId);

        assertEquals("CANCELLED", cancelled.getState());
        assertEquals(10, cancelled.getTotalExecutionDurationSeconds());
        clock.advance(Duration.ofMinutes(5));
        assertEquals("CANCELLED", service.getJobRun(applicationId, jobRunId, null).getState());
    }

    @Test
    void runIsCancelledAtItsExecutionTimeoutWhenItWouldRunLonger() {
        EmrServerlessJobRunService service = service(600);
        JobRun started = service.startJobRun(applicationId, request(null, 5L));
        String jobRunId = started.getJobRunId();
        Instant submittedAt = started.getCreatedAt();

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertEquals("RUNNING", service.getJobRun(applicationId, jobRunId, null).getState());

        clock.advance(Duration.ofSeconds(1));
        JobRun timedOut = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("CANCELLED", timedOut.getState());
        assertEquals(submittedAt.plus(Duration.ofMinutes(5)), timedOut.getEndedAt());
    }

    @Test
    void anAttemptTheRunNeverMadeIsRefusedBeforeTheRunSettles() {
        EmrServerlessJobRunService service = service(30);
        JobRun started = service.startJobRun(applicationId, request(null, null));
        String jobRunId = started.getJobRunId();
        clock.advance(Duration.ofSeconds(30));

        AwsException noAttempt = assertThrows(AwsException.class,
                () -> service.getJobRun(applicationId, jobRunId, 2));
        assertEquals("ResourceNotFoundException", noAttempt.getErrorCode());
        assertEquals("RUNNING", store.get(jobRunId).orElseThrow().getJobRun().getState());

        JobRun finished = service.getJobRun(applicationId, jobRunId, 1);
        assertEquals("SUCCESS", finished.getState());
        assertEquals(started.getCreatedAt().plusSeconds(30), finished.getEndedAt());
    }

    @Test
    void streamingRunIgnoresTheRunDuration() {
        EmrServerlessJobRunService service = service(0);
        String jobRunId = service.startJobRun(applicationId, request("STREAMING", null)).getJobRunId();

        clock.advance(Duration.ofDays(2));

        assertEquals("RUNNING", service.getJobRun(applicationId, jobRunId, null).getState());
    }

    @Test
    void retryIsRecognisedAfterTheStoredRunWasWrittenAndReadBack() throws Exception {
        EmrServerlessJobRunService service = service(0);
        StartJobRunRequest request = request("BATCH", 15L);
        request.setTags(Map.of("team", "data"));
        String jobRunId = service.startJobRun(applicationId, request).getJobRunId();

        // As persistent storage does: the record goes out as JSON and comes back.
        JobRunRecord stored = store.get(jobRunId).orElseThrow();
        store.put(jobRunId, objectMapper.readValue(objectMapper.writeValueAsString(stored), JobRunRecord.class));

        assertEquals(jobRunId, service.startJobRun(applicationId, request).getJobRunId());
        request.setExecutionTimeoutMinutes(30L);
        AwsException conflict = assertThrows(AwsException.class, () -> service.startJobRun(applicationId, request));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
    }

    @Test
    void negativeRunDurationIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> service(-1));
    }

    @Test
    void parseTimestampReadsIso8601AndEpochSeconds() {
        assertEquals(Instant.parse("2026-10-21T12:00:00Z"),
                EmrServerlessJobRunService.parseTimestamp("2026-10-21T12:00:00Z", "createdAtAfter"));
        assertEquals(Instant.parse("2026-10-21T12:00:00.500Z"),
                EmrServerlessJobRunService.parseTimestamp("1792584000.5", "createdAtAfter"));
        AwsException invalid = assertThrows(AwsException.class,
                () -> EmrServerlessJobRunService.parseTimestamp("yesterday", "createdAtAfter"));
        assertEquals("ValidationException", invalid.getErrorCode());
    }

    @Test
    void aContainerRunIsScheduledUntilItsDriverRunsThenSucceedsWhenItExitsZero() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        JobRun submitted = service.startJobRun(applicationId, request(null, null));
        String jobRunId = submitted.getJobRunId();
        List<JobRun> whileRunning = new ArrayList<>();
        runner.whileRunning = () -> {
            whileRunning.add(service.getJobRun(applicationId, jobRunId, null));
            clock.advance(Duration.ofSeconds(42));
        };

        assertEquals("SCHEDULED", service.getJobRun(applicationId, jobRunId, null).getState());
        assertNull(service.getJobRun(applicationId, jobRunId, null).getStartedAt());
        clock.advance(Duration.ofSeconds(3));
        runNext();

        assertEquals("RUNNING", whileRunning.get(0).getState());
        assertEquals(3, Duration.between(submitted.getCreatedAt(), whileRunning.get(0).getStartedAt()).toSeconds());
        JobRun finished = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("SUCCESS", finished.getState());
        assertEquals(42, Duration.between(finished.getStartedAt(), finished.getEndedAt()).toSeconds());
        assertEquals(42, finished.getTotalExecutionDurationSeconds());
    }

    @Test
    void theRunnerGetsTheRunsAccountReleaseImageRoleApplicationAndBatchTimeout() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String batch = service.startJobRun(applicationId, request(null, 30L)).getJobRunId();
        service.startJobRun(applicationId, request("STREAMING", 30L));
        runNext();
        runNext();

        SparkJobRunner.Job job = runner.jobs.get(0);
        assertEquals("emr-serverless", job.service());
        assertEquals(batch, job.runId());
        assertEquals(ACCOUNT_ID, job.accountId());
        assertEquals(REGION, job.region());
        assertEquals("emr-7.5.0", job.releaseLabel());
        assertNull(job.imageTemplate());
        assertNull(job.dockerNetwork());
        assertEquals(ROLE, job.executionRole());
        assertEquals(List.of("s3://bucket/jobs/app.jar"), job.application());
        assertEquals(Duration.ofMinutes(30), job.timeout());
        assertEquals(Duration.ZERO, runner.jobs.get(1).timeout());
    }

    @Test
    void aRunsSparkDefaultsReplaceTheApplicationsUntilEmrSpark810MergesThem() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);

        for (String release : List.of("emr-7.10.0", "emr-spark-8.0.0", "emr-spark-8.1.0")) {
            String configured = applicationWithSparkDefaults(release);
            StartJobRunRequest overriding = request(null, null);
            overriding.setConfigurationOverrides(Map.of("applicationConfiguration", List.of(Map.of(
                    "classification", "spark-defaults", "properties", Map.of("spark.executor.cores", "2")))));
            service.startJobRun(configured, request(null, null));
            service.startJobRun(configured, overriding);
            runNext();
            runNext();
        }

        Map<String, String> fromApp = Map.of("spark.executor.cores", "4", "spark.app.name", "from-app");
        List<Map<String, String>> sparkDefaults = runner.jobs.stream().map(SparkJobRunner.Job::sparkDefaults).toList();
        assertEquals(List.of(fromApp, Map.of("spark.executor.cores", "2"), fromApp, Map.of("spark.executor.cores", "2"),
                fromApp, Map.of("spark.executor.cores", "2", "spark.app.name", "from-app")), sparkDefaults);
        assertEquals("emr-spark-8.1.0", runner.jobs.get(5).releaseLabel());
    }

    @Test
    void aRunsSparkSubmitParametersComeBeforeItsEntryPointAndItsArguments() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/report.py");
        sparkSubmit.setEntryPointArguments(List.of("s3://bucket/input/", "s3://bucket/output/"));
        sparkSubmit.setSparkSubmitParameters("--conf spark.executor.cores=2 --py-files 's3://bucket/jobs/lib.zip'");

        assertEquals(List.of("--conf", "spark.executor.cores=2", "--py-files", "s3://bucket/jobs/lib.zip",
                "s3://bucket/jobs/report.py", "s3://bucket/input/", "s3://bucket/output/"),
                EmrServerlessJobRunService.application(sparkSubmit));
    }

    private String applicationWithSparkDefaults(String releaseLabel) {
        RuntimeConfiguration defaults = new RuntimeConfiguration();
        defaults.setMember("classification", "spark-defaults");
        defaults.setMember("properties", Map.of("spark.executor.cores", "4", "spark.app.name", "from-app"));
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("configured-" + releaseLabel);
        create.setReleaseLabel(releaseLabel);
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        create.setRuntimeConfiguration(List.of(defaults));
        return applications.createApplication(create).getApplicationId();
    }

    @Test
    void aDriverThatExitsNonZeroOrNeverStartsFailsTheRun() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String exited = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        String unstarted = service.startJobRun(applicationId, request(null, null)).getJobRunId();

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        runNext();
        runner.outcome = SparkJobRunner.Outcome.couldNotStart("no such image");
        runNext();

        JobRun failed = service.getJobRun(applicationId, exited, null);
        assertEquals("FAILED", failed.getState());
        assertEquals("Job run failed: the Spark driver exited with code 1.", failed.getStateDetails());
        assertEquals("FAILED", service.getJobRun(applicationId, unstarted, null).getState());
        assertEquals("Job run could not start: no such image",
                service.getJobRun(applicationId, unstarted, null).getStateDetails());
    }

    @Test
    void cancellingARunningContainerRunStopsItsDriver() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String jobRunId = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        List<String> whileStopping = new ArrayList<>();
        runner.whileRunning = () -> {
            clock.advance(Duration.ofSeconds(10));
            service.cancelJobRun(applicationId, jobRunId);
            whileStopping.add(service.getJobRun(applicationId, jobRunId, null).getState());
            runNext();
        };

        runNext();

        assertEquals(List.of("CANCELLING"), whileStopping);
        assertEquals(Set.of(jobRunId), runner.stopped);
        JobRun cancelled = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("CANCELLED", cancelled.getState());
        assertEquals(10, cancelled.getTotalExecutionDurationSeconds());
    }

    @Test
    void aRunCancelledBeforeItsDriverRunsNeverStarts() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String jobRunId = service.startJobRun(applicationId, request(null, null)).getJobRunId();

        service.cancelJobRun(applicationId, jobRunId);
        tasks.remove(1).run();
        runNext();

        JobRun cancelled = service.getJobRun(applicationId, jobRunId, null);
        assertEquals("CANCELLED", cancelled.getState());
        assertNull(cancelled.getStartedAt());
        assertEquals(0, cancelled.getTotalExecutionDurationSeconds());
        assertEquals(List.of(), runner.driversStarted);
    }

    @Test
    void aContainerRunPastItsTimeoutIsCancelled() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.outOfTime();
        EmrServerlessJobRunService service = dockerService(runner);
        String jobRunId = service.startJobRun(applicationId, request(null, 5L)).getJobRunId();
        List<String> whileStopping = new ArrayList<>();
        runner.whileTimingOut = () -> whileStopping.add(service.getJobRun(applicationId, jobRunId, null).getState());

        runNext();

        assertEquals(List.of("CANCELLING"), whileStopping);
        assertEquals("CANCELLED", service.getJobRun(applicationId, jobRunId, null).getState());
    }

    @Test
    void aHiveRunFailsWithoutRunning() {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("hive");
        create.setReleaseLabel("emr-7.5.0");
        create.setType("HIVE");
        create.setClientToken(UUID.randomUUID().toString());
        String hive = applications.createApplication(create).getApplicationId();
        JobDriver.Hive query = new JobDriver.Hive();
        query.setQuery("s3://bucket/queries/report.sql");
        StartJobRunRequest request = request(null, null);
        request.getJobDriver().setSparkSubmit(null);
        request.getJobDriver().setHive(query);

        JobRun run = dockerService(new FakeRunner()).startJobRun(hive, request);

        assertEquals("FAILED", run.getState());
        assertEquals(EmrServerlessJobRunService.HIVE_NOT_RUN, run.getStateDetails());
        assertEquals(List.of(), tasks);
    }

    @Test
    void aContainerRunNothingRunsAnyMoreFails() {
        FakeRunner runner = new FakeRunner();
        String leftByRestart = dockerService(runner).startJobRun(applicationId, request(null, null)).getJobRunId();
        EmrServerlessJobRunService service = dockerService(runner);
        String stoppedByShutdown = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        runner.whileRunning = () -> runner.stop(stoppedByShutdown);
        tasks.remove(1).run();

        for (String jobRunId : List.of(leftByRestart, stoppedByShutdown)) {
            JobRun run = service.getJobRun(applicationId, jobRunId, null);
            assertEquals("FAILED", run.getState());
            assertEquals(EmrServerlessJobRunService.INTERRUPTED, run.getStateDetails());
        }
    }

    private EmrServerlessJobRunService dockerService(SparkJobRunner runner) {
        return new EmrServerlessJobRunService(store, applications, iamService, new RegionResolver(REGION, ACCOUNT_ID),
                null, objectMapper, 0, clock, runner, tasks::add);
    }

    private void runNext() {
        tasks.remove(0).run();
    }

    @Test
    void aRunsThreadWritesNothingOnceAResetHasBegun() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String beforeReset = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        // The reset's teardown stops the driver while it runs; beforeReset() comes before the storage wipe.
        runner.whileRunning = service::beforeReset;

        runNext();

        assertEquals("RUNNING", store.get(beforeReset).orElseThrow().getJobRun().getState());
        runner.whileRunning = () -> { };
        String afterReset = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        runNext();
        assertEquals("SUCCESS", store.get(afterReset).orElseThrow().getJobRun().getState());
        // A running driver goes on, and the runner stops one whose run the reset removed.
        assertEquals(List.of(false, true, false, false), runner.cancelledAsRunning);
    }

    @Test
    void leftoverDriversAreRemovedWhenTheServiceStartsUnlessReconcilingIsOff() {
        for (boolean reconcile : List.of(true, false)) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().emrserverless().runnerMode()).thenReturn("docker");
            when(config.services().emrserverless().reconcileContainersOnStartup()).thenReturn(reconcile);
            EmrServerlessSparkRunner sparkRunner = mock(EmrServerlessSparkRunner.class);

            new EmrServerlessJobRunService(mock(StorageFactory.class), applications, iamService,
                    new RegionResolver(REGION, ACCOUNT_ID), null, objectMapper, config, clock, sparkRunner).shutdown();

            verify(sparkRunner, times(reconcile ? 1 : 0)).removeLeftoverDrivers("emr-serverless");
        }
    }

    /**
     * Runs a container run on the test's thread: its driver starts, does what the test asks, and ends
     * with {@code outcome}, or stopped once {@link #stop} has been called for it.
     */
    private static final class FakeRunner implements SparkJobRunner {
        final List<Job> jobs = new ArrayList<>();
        final Set<String> stopped = new HashSet<>();
        final List<String> driversStarted = new ArrayList<>();
        // What progress.cancelled() said once the driver ran, and again after whileRunning.
        final List<Boolean> cancelledAsRunning = new ArrayList<>();
        Outcome outcome = Outcome.exitedWith(0);
        Runnable whileRunning = () -> { };
        Runnable whileTimingOut = () -> { };

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
            // As the Docker runner, a stop counts only for a run in progress, so a cancel before then is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            driversStarted.add(job.runId());
            progress.started();
            cancelledAsRunning.add(progress.cancelled());
            whileRunning.run();
            cancelledAsRunning.add(progress.cancelled());
            if (outcome.timedOut()) {
                progress.timedOut();
                whileTimingOut.run();
            }
            return stopped.contains(job.runId()) ? Outcome.halted() : outcome;
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
        }

        @Override
        public void removeLeftoverDrivers(String service) {
            // A test's drivers live no longer than its runs.
        }
    }
}
