package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunAttemptSummary;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunRecord;
import io.github.hectorvent.floci.services.emrserverless.model.MonitoringConfiguration;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        applicationId = application("jobs", "emr-7.5.0", null);
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
        assertNull(job.customImage());
        assertNull(job.dockerNetwork());
        assertEquals(ROLE, job.executionRole());
        assertEquals(List.of("s3://bucket/jobs/app.jar"), job.application());
        assertEquals(Duration.ofMinutes(30), job.timeout());
        assertEquals(Duration.ZERO, runner.jobs.get(1).timeout());
        assertNull(job.logFolder());
        assertNull(job.cloudWatchLogs());
    }

    @Test
    void theRunnerGetsTheDriverWorkerTypesCustomImageElseTheApplicationsElseTheReleases() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String driverImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/driver:7.5.0";
        CreateApplicationRequest wholeApplication = applicationRequest("custom", "emr-7.5.0", "SPARK");
        wholeApplication.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        // Local mode runs the executors in the driver's container, so an Executor image is not used.
        wholeApplication.setWorkerTypeSpecifications(
                Map.of("Executor", EmrServerlessServiceTest.workerType(driverImage)));
        CreateApplicationRequest perWorkerType = applicationRequest("per-worker-type", "emr-7.5.0", "SPARK");
        perWorkerType.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        perWorkerType.setWorkerTypeSpecifications(Map.of("Driver", EmrServerlessServiceTest.workerType(driverImage)));

        service.startJobRun(applications.createApplication(wholeApplication).getApplicationId(), request(null, null));
        service.startJobRun(applications.createApplication(perWorkerType).getApplicationId(), request(null, null));
        service.startJobRun(applicationId, request(null, null));
        for (int i = 0; i < 3; i++) {
            runNext();
        }

        assertEquals(applicationImage, runner.jobs.get(0).customImage());
        assertEquals(driverImage, runner.jobs.get(1).customImage());
        assertNull(runner.jobs.get(2).customImage());
    }

    @Test
    void aRunsDriverOutputGoesToCloudWatchWhenItsOwnOrTheApplicationsLoggingIsEnabled() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String watched = application("watched", "emr-7.5.0", cloudWatchLogging(Map.of("enabled", true)));
        StartJobRunRequest own = request(null, null);
        own.setConfigurationOverrides(Map.of("monitoringConfiguration", Map.of("cloudWatchLoggingConfiguration",
                Map.of("enabled", true, "logGroupName", "spark-jobs", "logStreamNamePrefix", "nightly",
                        "logTypes", Map.of("SPARK_DRIVER", List.of("STDERR"))))));
        // "To remove cloudWatchLogging, pass the enabled flag as false."
        StartJobRunRequest removed = request(null, null);
        removed.setConfigurationOverrides(Map.of("monitoringConfiguration",
                Map.of("cloudWatchLoggingConfiguration", Map.of("enabled", false))));

        String inherited = service.startJobRun(watched, request(null, null)).getJobRunId();
        String overridden = service.startJobRun(watched, own).getJobRunId();
        service.startJobRun(watched, removed);
        for (int i = 0; i < 3; i++) {
            runNext();
        }

        String driver = "/applications/" + watched + "/jobs/" + inherited + "/SPARK_DRIVER/";
        assertEquals(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless", driver + "stdout", driver + "stderr"),
                runner.jobs.get(0).cloudWatchLogs());
        assertEquals(new SparkJobRunner.CloudWatchLogs("spark-jobs", null,
                "nightly/applications/" + watched + "/jobs/" + overridden + "/SPARK_DRIVER/stderr"),
                runner.jobs.get(1).cloudWatchLogs());
        assertNull(runner.jobs.get(2).cloudWatchLogs());
    }

    @Test
    void aRetriedRunsCloudWatchLogStreamsAreUnderItsAttempt() {
        Application app = new Application();
        app.setMonitoringConfiguration(cloudWatchLogging(Map.of("enabled", true)));

        assertEquals("/applications/app/jobs/run/attempts/1/SPARK_DRIVER/stdout",
                EmrServerlessJobRunService.driverCloudWatchLogs(app, run("emr-7.1.0", "STREAMING", null))
                        .stdoutStream());
        assertEquals("/applications/app/jobs/run/SPARK_DRIVER/stdout",
                EmrServerlessJobRunService.driverCloudWatchLogs(app, run("emr-7.0.0", "STREAMING", null))
                        .stdoutStream());
    }

    @Test
    void logTypesThatNameOnlyTheExecutorsSendNoneOfTheDriversOutput() {
        Application app = new Application();
        // "If you don't specify any log types, driver STDOUT and STDERR logs will be published to CloudWatch Logs
        // by default."
        app.setMonitoringConfiguration(cloudWatchLogging(Map.of("enabled", true,
                "logTypes", Map.of("SPARK_EXECUTOR", List.of("STDOUT", "STDERR")))));

        assertNull(EmrServerlessJobRunService.driverCloudWatchLogs(app, run("emr-7.5.0", "BATCH", null)));

        app.setMonitoringConfiguration(cloudWatchLogging(Map.of("enabled", true, "logTypes", Map.of())));

        assertEquals(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless",
                "/applications/app/jobs/run/SPARK_DRIVER/stdout", "/applications/app/jobs/run/SPARK_DRIVER/stderr"),
                EmrServerlessJobRunService.driverCloudWatchLogs(app, run("emr-7.5.0", "BATCH", null)));
    }

    private static MonitoringConfiguration cloudWatchLogging(Map<String, Object> configuration) {
        MonitoringConfiguration monitoring = new MonitoringConfiguration();
        monitoring.setProperty("cloudWatchLoggingConfiguration", configuration);
        return monitoring;
    }

    @Test
    void aRunsDriverLogsGoUnderItsOwnS3LogUriOrElseTheApplications() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String logged = application("logged", "emr-7.5.0", s3LogUri("s3://logs/app-level"));
        StartJobRunRequest ownLogUri = request(null, null);
        ownLogUri.setConfigurationOverrides(Map.of("monitoringConfiguration",
                Map.of("s3MonitoringConfiguration", Map.of("logUri", "s3://logs/job-level/"))));
        // "To remove s3MonitoringConfiguration, pass an empty declaration {} for the configuration."
        StartJobRunRequest removed = request(null, null);
        removed.setConfigurationOverrides(Map.of("monitoringConfiguration",
                Map.of("s3MonitoringConfiguration", Map.of())));

        String inherited = service.startJobRun(logged, request(null, null)).getJobRunId();
        String overridden = service.startJobRun(logged, ownLogUri).getJobRunId();
        service.startJobRun(logged, removed);
        for (int i = 0; i < 3; i++) {
            runNext();
        }

        assertEquals("s3://logs/app-level/applications/" + logged + "/jobs/" + inherited + "/SPARK_DRIVER/",
                runner.jobs.get(0).logFolder());
        assertEquals("s3://logs/job-level/applications/" + logged + "/jobs/" + overridden + "/SPARK_DRIVER/",
                runner.jobs.get(1).logFolder());
        assertNull(runner.jobs.get(2).logFolder());
    }

    @Test
    void aRetriedRunsDriverLogsAreUnderItsAttemptFromRelease710() {
        Application app = new Application();
        app.setMonitoringConfiguration(s3LogUri("s3://logs"));

        assertEquals("s3://logs/applications/app/jobs/run/attempts/1/SPARK_DRIVER/",
                EmrServerlessJobRunService.driverLogFolder(app, run("emr-7.1.0", "STREAMING", null)));
        assertEquals("s3://logs/applications/app/jobs/run/attempts/1/SPARK_DRIVER/",
                EmrServerlessJobRunService.driverLogFolder(app, run("emr-spark-8.1.0", "BATCH", 3)));
        assertEquals("s3://logs/applications/app/jobs/run/SPARK_DRIVER/",
                EmrServerlessJobRunService.driverLogFolder(app, run("emr-7.1.0", "BATCH", 1)));
        assertEquals("s3://logs/applications/app/jobs/run/SPARK_DRIVER/",
                EmrServerlessJobRunService.driverLogFolder(app, run("emr-7.0.0", "STREAMING", null)));
    }

    @Test
    void aBatchRunWhoseDriverFailsIsTriedAgainUntilItSucceedsEachAttemptWithItsOwnLogs() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String logged = application("logged", "emr-7.5.0", s3LogUri("s3://logs/"));
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 3));
        String jobRunId = service.startJobRun(logged, resilient).getJobRunId();

        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        runNext();
        JobRun scheduled = service.getJobRun(logged, jobRunId, null);
        clock.advance(Duration.ofSeconds(10));
        runNext();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(0);
        clock.advance(Duration.ofSeconds(10));
        runNext();

        assertEquals(List.of("SCHEDULED", "2"), List.of(scheduled.getState(), String.valueOf(scheduled.getAttempt())));
        JobRun latest = service.getJobRun(logged, jobRunId, null);
        assertEquals("SUCCESS", latest.getState());
        assertEquals(3, latest.getAttempt());
        // "the attempt generates its own set of logs".
        String driver = "s3://logs/applications/" + logged + "/jobs/" + jobRunId + "/attempts/%d/SPARK_DRIVER/";
        assertEquals(List.of(driver.formatted(1), driver.formatted(2), driver.formatted(3)),
                runner.jobs.stream().map(SparkJobRunner.Job::logFolder).toList());
        JobRun first = service.getJobRun(logged, jobRunId, 1);
        assertEquals("FAILED", first.getState());
        assertEquals("Job run failed: the Spark driver exited with code 1.", first.getStateDetails());
        assertEquals(1, first.getAttempt());
        List<JobRunAttemptSummary> attempts = service.listJobRunAttempts(logged, jobRunId, null, null).items();
        assertEquals(List.of(1, 2, 3), attempts.stream().map(JobRunAttemptSummary::getAttempt).toList());
        assertEquals(List.of("FAILED", "FAILED", "SUCCESS"),
                attempts.stream().map(JobRunAttemptSummary::getState).toList());
        // Each attempt after the first is made as the one before it fails.
        assertEquals(List.of(attempts.get(0).getUpdatedAt(), attempts.get(1).getUpdatedAt()),
                List.of(attempts.get(1).getCreatedAt(), attempts.get(2).getCreatedAt()));
        assertEquals(10, Duration.between(attempts.get(1).getCreatedAt(), attempts.get(2).getCreatedAt()).toSeconds());
        assertEquals(List.of(latest.getCreatedAt(), latest.getCreatedAt(), latest.getCreatedAt()),
                attempts.stream().map(JobRunAttemptSummary::getJobCreatedAt).toList());
    }

    @Test
    void aBatchRunIsFailedOnceItHasMadeItsMaxAttempts() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 2));
        String jobRunId = service.startJobRun(applicationId, resilient).getJobRunId();

        runNext();
        runNext();

        JobRun failed = service.getJobRun(applicationId, jobRunId, null);
        assertEquals(List.of("FAILED", "2"), List.of(failed.getState(), String.valueOf(failed.getAttempt())));
        assertEquals(2, runner.jobs.size());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void aRunIsNotTriedAgainWithoutARetryPolicyOrBeforeRelease710() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 3));
        String once = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        // "EMR Serverless releases 7.1.0 and higher include support for job resiliency".
        String older = application("older", "emr-7.0.0", null);
        String beforeResiliency = service.startJobRun(older, resilient).getJobRunId();

        runNext();
        runNext();

        assertEquals(1, service.getJobRun(applicationId, once, null).getAttempt());
        assertEquals("FAILED", service.getJobRun(applicationId, once, null).getState());
        assertEquals(1, service.getJobRun(older, beforeResiliency, null).getAttempt());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void aStreamingRunIsTriedAgainUntilItFailsMaxFailedAttemptsPerHourWithinAnHour() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        StartJobRunRequest streaming = request("STREAMING", null);
        streaming.setRetryPolicy(Map.of("maxFailedAttemptsPerHour", 2));
        String jobRunId = service.startJobRun(applicationId, streaming).getJobRunId();

        runNext();
        // The first failure is more than an hour old by the second, so it no longer counts.
        clock.advance(Duration.ofMinutes(61));
        runNext();
        clock.advance(Duration.ofMinutes(10));
        runNext();

        JobRun failed = service.getJobRun(applicationId, jobRunId, null);
        assertEquals(List.of("FAILED", "3"), List.of(failed.getState(), String.valueOf(failed.getAttempt())));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void aCancelledRunOrOnePastItsTimeoutIsNotTriedAgain() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 3));
        String cancelled = service.startJobRun(applicationId, resilient).getJobRunId();
        runner.whileRunning = () -> service.cancelJobRun(applicationId, cancelled);
        runNext();
        runNext();
        runner.whileRunning = () -> { };
        StartJobRunRequest timed = request(null, 5L);
        timed.setRetryPolicy(Map.of("maxAttempts", 3));
        String timedOut = service.startJobRun(applicationId, timed).getJobRunId();
        runner.outcome = SparkJobRunner.Outcome.outOfTime();
        runNext();

        assertEquals("CANCELLED", service.getJobRun(applicationId, cancelled, null).getState());
        assertEquals("CANCELLED", service.getJobRun(applicationId, timedOut, null).getState());
        assertEquals(List.of(1, 1), List.of(service.getJobRun(applicationId, cancelled, null).getAttempt(),
                service.getJobRun(applicationId, timedOut, null).getAttempt()));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void eachAttemptIsLaunchedWhileItsApplicationCannotBeDeleted() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        // Whether the lock DeleteApplication takes was held as each attempt's driver was handed to its thread.
        List<Boolean> launchedHoldingTheApplications = new ArrayList<>();
        EmrServerlessJobRunService service = new EmrServerlessJobRunService(store, applications, iamService,
                new RegionResolver(REGION, ACCOUNT_ID), null, objectMapper, 0, clock, runner, task -> {
                    launchedHoldingTheApplications.add(Thread.holdsLock(applications));
                    tasks.add(task);
                });
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 2));
        service.startJobRun(applicationId, resilient);

        runNext();

        assertEquals(List.of(true, true), launchedHoldingTheApplications);
    }

    @Test
    void aRunWhoseApplicationIsDeletedWhileItsDriverRunsIsNotTriedAgain() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 3));
        String jobRunId = service.startJobRun(applicationId, resilient).getJobRunId();
        runner.whileRunning = () -> {
            applications.stopApplication(applicationId);
            applications.deleteApplication(applicationId);
        };

        runNext();

        JobRun failed = store.get(jobRunId).orElseThrow().getJobRun();
        assertEquals(List.of("FAILED", "1"), List.of(failed.getState(), String.valueOf(failed.getAttempt())));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void aRetryPolicyMemberOutsideOneToTenOrNotAnIntegerIsRefused() {
        EmrServerlessJobRunService service = service(0);

        assertEquals("1 validation error detected: Value '0' at 'retryPolicy.maxAttempts' failed to satisfy "
                + "constraint: Member must have value greater than or equal to 1",
                refused(service, Map.of("maxAttempts", 0), "ValidationException"));
        assertEquals("1 validation error detected: Value '11' at 'retryPolicy.maxFailedAttemptsPerHour' failed to "
                + "satisfy constraint: Member must have value less than or equal to 10",
                refused(service, Map.of("maxFailedAttemptsPerHour", 11), "ValidationException"));
        // As Smithy's restJson1 malformed-request tests have it: a string, a boolean or a fraction for an integer.
        for (Object malformed : List.of("3", true, 2.5)) {
            assertEquals("retryPolicy.maxAttempts must be an integer",
                    refused(service, Map.of("maxAttempts", malformed), "SerializationException"));
        }
        StartJobRunRequest bounds = request(null, null);
        bounds.setRetryPolicy(Map.of("maxAttempts", 10, "maxFailedAttemptsPerHour", 1));
        assertEquals(Map.of("maxAttempts", 10, "maxFailedAttemptsPerHour", 1),
                service.startJobRun(applicationId, bounds).getRetryPolicy());
    }

    // The message StartJobRun refuses a run with retryPolicy with, as errorCode with HTTP 400.
    private String refused(EmrServerlessJobRunService service, Map<String, Object> retryPolicy, String errorCode) {
        StartJobRunRequest request = request(null, null);
        request.setRetryPolicy(retryPolicy);
        AwsException refusal = assertThrows(AwsException.class, () -> service.startJobRun(applicationId, request));
        assertEquals(List.of(errorCode, 400), List.of(refusal.getErrorCode(), refusal.getHttpStatus()));
        return refusal.getMessage();
    }

    // A SPARK application on releaseLabel, with monitoring as its monitoring configuration (null for none).
    private String application(String name, String releaseLabel, MonitoringConfiguration monitoring) {
        CreateApplicationRequest create = applicationRequest(name, releaseLabel, "SPARK");
        create.setMonitoringConfiguration(monitoring);
        return applications.createApplication(create).getApplicationId();
    }

    // The request for an application of type on releaseLabel, which a test adds to as it needs.
    private static CreateApplicationRequest applicationRequest(String name, String releaseLabel, String type) {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName(name);
        create.setReleaseLabel(releaseLabel);
        create.setType(type);
        create.setClientToken(UUID.randomUUID().toString());
        return create;
    }

    private static MonitoringConfiguration s3LogUri(String logUri) {
        MonitoringConfiguration monitoring = new MonitoringConfiguration();
        monitoring.setProperty("s3MonitoringConfiguration", Map.of("logUri", logUri));
        return monitoring;
    }

    private static JobRun run(String releaseLabel, String mode, Integer maxAttempts) {
        JobRun run = new JobRun();
        run.setApplicationId("app");
        run.setJobRunId("run");
        run.setReleaseLabel(releaseLabel);
        run.setMode(mode);
        run.setRetryPolicy(maxAttempts != null ? Map.of("maxAttempts", maxAttempts) : null);
        run.setAttempt(1);
        return run;
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
        CreateApplicationRequest create = applicationRequest("configured-" + releaseLabel, releaseLabel, "SPARK");
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
        String hive = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();
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
