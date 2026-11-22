package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.ImageConfiguration;
import io.floci.aws.services.emrserverless.model.JobDriver;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.JobRunAttemptSummary;
import io.floci.aws.services.emrserverless.model.JobRunRecord;
import io.floci.aws.services.emrserverless.model.JobRunSummary;
import io.floci.aws.services.emrserverless.model.MaximumCapacity;
import io.floci.aws.services.emrserverless.model.MonitoringConfiguration;
import io.floci.aws.services.emrserverless.model.RuntimeConfiguration;
import io.floci.aws.services.emrserverless.model.SchedulerConfiguration;
import io.floci.aws.services.emrserverless.model.StartJobRunRequest;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmrServerlessJobRunServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-job";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String OTHER_DIGEST = "sha256:" + "b".repeat(64);

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
    void runsStartedWithinTheSameMillisecondListNewestFirst() {
        EmrServerlessJobRunService service = new EmrServerlessJobRunService(store, applications, iamService,
                new RegionResolver(REGION, ACCOUNT_ID), null, objectMapper, 0,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        List<String> started = new ArrayList<>();
        for (int run = 0; run < 12; run++) {
            started.add(service.startJobRun(applicationId, request(null, null)).getJobRunId());
        }

        List<String> listed = service.listJobRuns(applicationId, null, null, null, null, null, null).items().stream()
                .map(JobRunSummary::getId).toList();

        assertEquals(started.reversed(), listed);
    }

    @Test
    void aRetryIsTheOriginalRunWhereTheRunWouldHoldTheSameAndConflictsWhereItWouldNot() {
        EmrServerlessJobRunService service = service(0);
        StartJobRunRequest first = request(null, 720L);
        first.setTags(Map.of());
        JobRun original = service.startJobRun(applicationId, first);
        // The run holds the default timeout and no tags either way.
        StartJobRunRequest retry = request(null, null);
        retry.setClientToken(first.getClientToken());
        // A mode, an empty-valued tag and a name the original's run does not hold.
        StartJobRunRequest batch = request("BATCH", null);
        batch.setClientToken(first.getClientToken());
        StartJobRunRequest emptyTag = request(null, null);
        emptyTag.setClientToken(first.getClientToken());
        emptyTag.setTags(Map.of("team", ""));
        StartJobRunRequest renamed = request(null, null);
        renamed.setClientToken(first.getClientToken());
        renamed.setName("another");

        assertEquals(original.getJobRunId(), service.startJobRun(applicationId, retry).getJobRunId());
        for (StartJobRunRequest conflicting : List.of(batch, emptyTag, renamed)) {
            assertEquals("ConflictException", assertThrows(AwsException.class,
                    () -> service.startJobRun(applicationId, conflicting)).getErrorCode());
        }
    }

    @Test
    void aNextTokenGivenBeforeRunsKeptASequenceIsRefused() {
        EmrServerlessJobRunService service = service(0);
        List<String> ids = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            ids.add(service.startJobRun(applicationId, request(null, null)).getJobRunId());
        }
        // A token of the listing's earlier cursors, the creation time and the id, under its earlier namespace.
        String earlier = Pagination.paginate(ids, id -> id, 1, null,
                String.join("|", "ListJobRuns", applicationId, "[]", "null", "null", "null"),
                token -> new AwsException("ValidationException", "Invalid nextToken.", 400)).nextToken();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.listJobRuns(applicationId, null, null, null, null, 1, earlier));

        assertEquals("ValidationException", refused.getErrorCode());
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
    void anApplicationStopsOnlyOnceNoneOfItsRunsIsStillGoing() {
        EmrServerlessJobRunService service = service(30);
        String other = application("other", "emr-7.5.0", null);
        service.startJobRun(applicationId, request(null, null));
        service.startJobRun(other, request("STREAMING", null));

        AwsException running = assertThrows(AwsException.class, () -> service.stopApplication(applicationId));
        clock.advance(Duration.ofSeconds(30));
        service.stopApplication(applicationId);

        assertEquals("ValidationException", running.getErrorCode());
        assertEquals("All scheduled and running jobs must be completed or cancelled before stopping an application.",
                running.getMessage());
        assertEquals("STOPPED", applications.getApplication(applicationId).getState());
        assertEquals("STARTED", applications.getApplication(other).getState());
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
    void anAttemptStoredBeforeItsManagedStorageWasKeptTakesItFromItsRun() throws Exception {
        EmrServerlessJobRunService service = service(0);
        StartJobRunRequest unmanaged = request("BATCH", null);
        unmanaged.setConfigurationOverrides(Map.of("monitoringConfiguration",
                Map.of("managedPersistenceMonitoringConfiguration", Map.of("enabled", false))));
        String unmanagedRun = service.startJobRun(applicationId, unmanaged).getJobRunId();
        String managedRun = service.startJobRun(applicationId, request("BATCH", null)).getJobRunId();

        // As an earlier Floci stored them: each attempt's destinations without whether its logs were managed.
        for (String jobRunId : List.of(unmanagedRun, managedRun)) {
            ObjectNode stored = objectMapper.valueToTree(store.get(jobRunId).orElseThrow());
            stored.get("driverLogs").forEach(logs -> ((ObjectNode) logs).remove("managedStorage"));
            store.put(jobRunId, objectMapper.treeToValue(stored, JobRunRecord.class));
        }

        assertFalse(service.dashboard(applicationId, unmanagedRun, 1).logs().inManagedStorage());
        assertTrue(service.dashboard(applicationId, managedRun, 1).logs().inManagedStorage());
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
        assertEquals(Map.of(), job.environment());
        assertEquals(List.of(), job.archives());
        // An EMR Serverless application has no cluster, nor its storage.
        assertNull(job.storage());
    }

    @Test
    void theRunnerGetsTheDriverWorkerTypesCustomImageElseTheApplicationsElseTheReleases() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String driverImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/driver:7.5.0";
        CreateApplicationRequest wholeApplication = applicationRequest("custom", "emr-7.5.0", "SPARK");
        wholeApplication.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        // The executors' worker type's image is theirs, not the driver's.
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
    void aRunOfAnApplicationThatResolvedItsImageAsItStartedPullsThatImageByItsDigest() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String digest = "sha256:" + "c".repeat(64);
        applications.ecrService = EmrServerlessServiceTest.ecrHolding("spark/custom", "7.5.0", digest);
        CreateApplicationRequest custom = applicationRequest("resolved", "emr-7.5.0", "SPARK");
        custom.setImageConfiguration(EmrServerlessServiceTest.image(
                "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0"));
        String customId = applications.createApplication(custom).getApplicationId();

        // The run starts the application, which resolves its image as it starts.
        String jobRunId = service.startJobRun(customId, request(null, null)).getJobRunId();
        runNext();

        assertEquals("000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom@" + digest,
                runner.jobs.get(0).customImage());
        assertEquals(digest, service.getJobRun(customId, jobRunId, null).getImageConfiguration()
                .getResolvedImageDigest());
    }

    @Test
    void aRunReportsTheImageConfigurationsItAppliesEachWithTheDigestOfTheImageItsRunnerResolvedForIt() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner, 2);
        String applicationImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String driverImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/driver:7.5.0";
        String executorImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/executor:7.5.0";
        CreateApplicationRequest custom = applicationRequest("custom", "emr-7.5.0", "SPARK");
        custom.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        custom.getImageConfiguration().setApplicationLevelDigestResolution(false);
        custom.setWorkerTypeSpecifications(Map.of("Driver", EmrServerlessServiceTest.workerType(driverImage),
                "Executor", EmrServerlessServiceTest.workerType(executorImage)));
        String customId = applications.createApplication(custom).getApplicationId();
        String jobRunId = service.startJobRun(customId, request(null, null)).getJobRunId();
        String plain = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        runner.digests.put(driverImage, DIGEST);
        runner.digests.put(executorImage, OTHER_DIGEST);

        JobRun submitted = service.getJobRun(customId, jobRunId, null);
        runNext();
        runNext();

        assertEquals(List.of("application " + applicationImage + " null", "Driver " + driverImage + " null",
                "Executor " + executorImage + " null"), images(submitted));
        // The driver and the executors each run their worker type's image, so the application's runs on none.
        JobRun ran = service.getJobRun(customId, jobRunId, null);
        assertEquals(List.of("application " + applicationImage + " null", "Driver " + driverImage + " " + DIGEST,
                "Executor " + executorImage + " " + OTHER_DIGEST), images(ran));
        assertNull(ran.getImageConfiguration().getApplicationLevelDigestResolution());
        // The application's own stay as it gave them.
        Application app = applications.getApplication(customId);
        assertNull(app.getImageConfiguration().getResolvedImageDigest());
        assertNull(app.getWorkerTypeSpecifications().get("Driver").getImageConfiguration().getResolvedImageDigest());
        JobRun withoutImages = service.getJobRun(applicationId, plain, null);
        assertNull(withoutImages.getImageConfiguration());
        assertNull(withoutImages.getWorkerTypeSpecifications());
    }

    @Test
    void aDigestGoesOnlyToTheConfigurationItsWorkerTypeTookItsImageFromNotToAnotherNamingThatImage() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String hiveDriverImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/hive/driver:7.5.0";
        CreateApplicationRequest spark = applicationRequest("local-executors", "emr-7.5.0", "SPARK");
        spark.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        spark.setWorkerTypeSpecifications(Map.of("Executor", EmrServerlessServiceTest.workerType(applicationImage)));
        CreateApplicationRequest hive = applicationRequest("tez-tasks", "emr-7.5.0", "HIVE");
        hive.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        hive.setWorkerTypeSpecifications(Map.of("HiveDriver", EmrServerlessServiceTest.workerType(hiveDriverImage),
                "TezTask", EmrServerlessServiceTest.workerType(hiveDriverImage)));
        String sparkId = applications.createApplication(spark).getApplicationId();
        String hiveId = applications.createApplication(hive).getApplicationId();
        String sparkRun = service.startJobRun(sparkId, request(null, null)).getJobRunId();
        String hiveRun = service.startJobRun(hiveId, hiveRequest("s3://bucket/queries/report.sql", null, null))
                .getJobRunId();
        runner.digests.put(applicationImage, DIGEST);
        runner.digests.put(hiveDriverImage, OTHER_DIGEST);

        runNext();
        runNext();

        // Local mode runs the executors in the driver's container, and Tez a Hive run's tasks, so neither image runs.
        assertEquals(List.of("application " + applicationImage + " " + DIGEST,
                "Executor " + applicationImage + " null"), images(service.getJobRun(sparkId, sparkRun, null)));
        assertEquals(List.of("application " + applicationImage + " null",
                "HiveDriver " + hiveDriverImage + " " + OTHER_DIGEST, "TezTask " + hiveDriverImage + " null"),
                images(service.getJobRun(hiveId, hiveRun, null)));
    }

    @Test
    void eachWorkerTypeKeepsTheDigestOfItsOwnPullAndAConfigurationTheyShareHoldsTheDrivers() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner, 2);
        String sameTag = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String shared = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/shared:7.5.0";
        CreateApplicationRequest apart = applicationRequest("apart", "emr-7.5.0", "SPARK");
        apart.setWorkerTypeSpecifications(Map.of("Driver", EmrServerlessServiceTest.workerType(sameTag),
                "Executor", EmrServerlessServiceTest.workerType(sameTag)));
        CreateApplicationRequest together = applicationRequest("together", "emr-7.5.0", "SPARK");
        together.setImageConfiguration(EmrServerlessServiceTest.image(shared));
        String apartId = applications.createApplication(apart).getApplicationId();
        String togetherId = applications.createApplication(together).getApplicationId();
        String apartRun = service.startJobRun(apartId, request(null, null)).getJobRunId();
        String togetherRun = service.startJobRun(togetherId, request(null, null)).getJobRunId();
        // The tag pushed again between the driver's pull and the executors'.
        runner.digests.put(sameTag, DIGEST);
        runner.executorDigests.put(sameTag, OTHER_DIGEST);
        runner.digests.put(shared, DIGEST);
        runner.executorDigests.put(shared, OTHER_DIGEST);

        runNext();
        runNext();

        assertEquals(List.of("Driver " + sameTag + " " + DIGEST, "Executor " + sameTag + " " + OTHER_DIGEST),
                images(service.getJobRun(apartId, apartRun, null)));
        assertEquals(List.of("application " + shared + " " + DIGEST),
                images(service.getJobRun(togetherId, togetherRun, null)));
    }

    @Test
    void eachAttemptAppliesItsApplicationsImageAgainAndKeepsTheDigestItsOwnPullResolved() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        CreateApplicationRequest custom = applicationRequest("retagged", "emr-7.5.0", "SPARK");
        custom.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        String customId = applications.createApplication(custom).getApplicationId();
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 2));
        String jobRunId = service.startJobRun(customId, resilient).getJobRunId();

        runner.digests.put(applicationImage, DIGEST);
        runNext();
        JobRun secondScheduled = service.getJobRun(customId, jobRunId, null);
        // The tag pushed again: "either by retagging an image in Amazon ECR or by updating the imageConfiguration".
        runner.digests.put(applicationImage, OTHER_DIGEST);
        runner.outcome = SparkJobRunner.Outcome.exitedWith(0);
        runNext();

        assertEquals(List.of("application " + applicationImage + " null"), images(secondScheduled));
        assertEquals(List.of("application " + applicationImage + " " + OTHER_DIGEST),
                images(service.getJobRun(customId, jobRunId, null)));
        assertEquals(List.of("application " + applicationImage + " " + DIGEST),
                images(service.getJobRun(customId, jobRunId, 1)));
    }

    @Test
    void aDigestResolvedOnceAResetHasBegunIsNotWritten() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "000000000000.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        CreateApplicationRequest custom = applicationRequest("reset", "emr-7.5.0", "SPARK");
        custom.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        String customId = applications.createApplication(custom).getApplicationId();
        String jobRunId = service.startJobRun(customId, request(null, null)).getJobRunId();
        runner.digests.put(applicationImage, DIGEST);
        runner.whileResolving = service::beforeReset;

        runNext();

        assertEquals(List.of("application " + applicationImage + " null"),
                images(store.get(jobRunId).orElseThrow().getJobRun()));
    }

    // A run's image configurations, each "<whose> <imageUri> <resolvedImageDigest>": its own, then its worker types'
    // by name.
    private static List<String> images(JobRun run) {
        List<String> images = new ArrayList<>();
        if (run.getImageConfiguration() != null) {
            images.add(described("application", run.getImageConfiguration()));
        }
        if (run.getWorkerTypeSpecifications() != null) {
            new TreeMap<>(run.getWorkerTypeSpecifications()).forEach((workerType, specification) ->
                    images.add(described(workerType, specification.getImageConfiguration())));
        }
        return images;
    }

    private static String described(String whose, ImageConfiguration image) {
        return whose + " " + image.getImageUri() + " " + image.getResolvedImageDigest();
    }

    @Test
    void aRunsContainersRunForItsApplicationsArchitectureElseTheDockerHostsOwn() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = honouringService(runner);
        CreateApplicationRequest graviton = applicationRequest("graviton", "emr-7.5.0", "SPARK");
        graviton.setArchitecture("ARM64");
        CreateApplicationRequest x86 = applicationRequest("x86", "emr-7.5.0", "SPARK");
        x86.setArchitecture("X86_64");
        CreateApplicationRequest hive = applicationRequest("hive-graviton", "emr-7.5.0", "HIVE");
        hive.setArchitecture("ARM64");

        service.startJobRun(applications.createApplication(graviton).getApplicationId(), request(null, null));
        service.startJobRun(applications.createApplication(x86).getApplicationId(), request(null, null));
        service.startJobRun(applicationId, request(null, null));
        service.startJobRun(applications.createApplication(hive).getApplicationId(),
                hiveRequest("s3://bucket/queries/report.sql", null, null));
        for (int i = 0; i < 4; i++) {
            runNext();
        }

        assertEquals("linux/arm64", runner.jobs.get(0).platform());
        assertEquals("linux/amd64", runner.jobs.get(1).platform());
        // An application that names none runs on the Docker host's own platform.
        assertNull(runner.jobs.get(2).platform());
        assertEquals("linux/arm64", runner.jobs.get(3).platform());
    }

    @Test
    void aRunsContainersRunForItsApplicationsArchitectureOnlyWithHonourArchitecturesOn() {
        for (boolean honour : List.of(true, false)) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().emrserverless().runnerMode()).thenReturn("docker");
            when(config.services().emrserverless().honourArchitectures()).thenReturn(honour);
            EmrServerlessSparkRunner sparkRunner = mock(EmrServerlessSparkRunner.class);
            when(sparkRunner.run(any(), any())).thenReturn(SparkJobRunner.Outcome.exitedWith(0));
            EmrServerlessJobRunService service = new EmrServerlessJobRunService(new InMemoryStorageFactory(ACCOUNT_ID),
                    applications, iamService, new RegionResolver(REGION, ACCOUNT_ID), null, objectMapper, config, clock,
                    sparkRunner);
            CreateApplicationRequest graviton = applicationRequest("graviton-" + honour, "emr-7.5.0", "SPARK");
            graviton.setArchitecture("ARM64");
            try {
                service.startJobRun(applications.createApplication(graviton).getApplicationId(), request(null, null));

                // Off, the default, an ARM64 application's run stays on the Docker host's own platform.
                String platform = honour ? "linux/arm64" : null;
                verify(sparkRunner, timeout(10_000))
                        .run(argThat(job -> Objects.equals(platform, job.platform())), any());
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void aSparkRunsExecutorsRunApartInTheirWorkerTypesImageWithinTheApplicationsMaximumCapacity() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner, 2);
        String applicationImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String executorImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/executor:7.5.0";
        CreateApplicationRequest capped = applicationRequest("capped", "emr-7.5.0", "SPARK");
        capped.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        capped.setWorkerTypeSpecifications(Map.of("Executor", EmrServerlessServiceTest.workerType(executorImage)));
        MaximumCapacity capacity = new MaximumCapacity();
        capacity.setCpu("12 vCPU");
        capacity.setMemory("64 GB");
        capacity.setDisk("200 GB");
        capped.setMaximumCapacity(capacity);
        CreateApplicationRequest whole = applicationRequest("whole", "emr-7.5.0", "SPARK");
        whole.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        String hive = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();

        Application cappedApplication = applications.createApplication(capped);
        Application wholeApplication = applications.createApplication(whole);
        service.startJobRun(cappedApplication.getApplicationId(), request(null, null));
        service.startJobRun(wholeApplication.getApplicationId(), request(null, null));
        service.startJobRun(applicationId, request(null, null));
        service.startJobRun(hive, hiveRequest("s3://bucket/queries/report.sql", null, null));
        for (int i = 0; i < 4; i++) {
            runNext();
        }

        // Each within its application's capacity, which that application's runs share.
        assertEquals(new SparkJobRunner.Executors(2, executorImage, 12, 64, 200, cappedApplication.getArn()),
                runner.jobs.get(0).executors());
        // The application's image applies to every worker type; without one, the release's.
        assertEquals(new SparkJobRunner.Executors(2, applicationImage, 0, 0, 0, wholeApplication.getArn()),
                runner.jobs.get(1).executors());
        assertEquals(new SparkJobRunner.Executors(2, null, 0, 0, 0,
                applications.getApplication(applicationId).getArn()), runner.jobs.get(2).executors());
        // Tez runs a Hive run's tasks in its driver's container.
        assertNull(runner.jobs.get(3).executors());
    }

    @Test
    void aRunGoingKeepsWhatItWasLaunchedWithAndTheNextTakesItsStartedApplicationsNewImageAndMaximumCapacity() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner, 2);
        String firstImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String nextImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.1";
        CreateApplicationRequest create = applicationRequest("updated-while-started", "emr-7.5.0", "SPARK");
        create.setImageConfiguration(EmrServerlessServiceTest.image(firstImage));
        create.setMaximumCapacity(EmrServerlessServiceTest.capacity("400 vCPU"));
        Application app = applications.createApplication(create);
        UpdateApplicationRequest update = new UpdateApplicationRequest();
        update.setImageConfiguration(EmrServerlessServiceTest.image(nextImage));
        update.setMaximumCapacity(EmrServerlessServiceTest.capacity("100 vCPU"));
        List<String> statesUpdated = new ArrayList<>();
        runner.whileRunning = () -> statesUpdated.add(
                applications.updateApplication(app.getApplicationId(), update).getState());

        service.startJobRun(app.getApplicationId(), request(null, null));
        runNext();
        runner.whileRunning = () -> { };
        service.startJobRun(app.getApplicationId(), request(null, null));
        runNext();

        // The first run started the application, which took the update while that run went on.
        assertEquals(List.of("STARTED"), statesUpdated);
        assertEquals(firstImage, runner.jobs.get(0).customImage());
        assertEquals(new SparkJobRunner.Executors(2, firstImage, 400, 0, 0, app.getArn()),
                runner.jobs.get(0).executors());
        assertEquals(nextImage, runner.jobs.get(1).customImage());
        assertEquals(new SparkJobRunner.Executors(2, nextImage, 100, 0, 0, app.getArn()),
                runner.jobs.get(1).executors());
    }

    @Test
    void aRunsNextAttemptTakesTheImageItsStartedApplicationWasUpdatedTo() {
        FakeRunner runner = new FakeRunner();
        runner.outcome = SparkJobRunner.Outcome.exitedWith(1);
        EmrServerlessJobRunService service = dockerService(runner);
        String firstImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
        String nextImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.1";
        CreateApplicationRequest create = applicationRequest("retried-after-update", "emr-7.5.0", "SPARK");
        create.setImageConfiguration(EmrServerlessServiceTest.image(firstImage));
        String id = applications.createApplication(create).getApplicationId();
        UpdateApplicationRequest update = new UpdateApplicationRequest();
        update.setImageConfiguration(EmrServerlessServiceTest.image(nextImage));
        runner.whileRunning = () -> applications.updateApplication(id, update);
        StartJobRunRequest resilient = request(null, null);
        resilient.setRetryPolicy(Map.of("maxAttempts", 2));

        service.startJobRun(id, resilient);
        runNext();
        runner.whileRunning = () -> { };
        runNext();

        // "New job attempts will pick new images".
        assertEquals(firstImage, runner.jobs.get(0).customImage());
        assertEquals(nextImage, runner.jobs.get(1).customImage());
    }

    @Test
    void withoutExecutorContainersARunsExecutorsRunInItsDriversContainer() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner, 0);

        service.startJobRun(applicationId, request(null, null));
        runNext();

        assertNull(runner.jobs.get(0).executors());
    }

    @Test
    void aMaximumCapacitysAmountIsTheNumberItStartsWith() {
        assertEquals(400, EmrServerlessJobRunService.capacityAmount("400 vCPU"));
        assertEquals(1024, EmrServerlessJobRunService.capacityAmount("1024GB"));
        assertEquals(0, EmrServerlessJobRunService.capacityAmount(null));
        assertEquals(0, EmrServerlessJobRunService.capacityAmount("GB"));
        assertEquals(Long.MAX_VALUE, EmrServerlessJobRunService.capacityAmount("9".repeat(30)));
    }

    @Test
    void aHiveRunGetsTheHiveDriverWorkerTypesCustomImageElseTheApplicationsElseTheReleases() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String applicationImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/hive/custom:7.5.0";
        String driverImage = "123456789012.dkr.ecr.us-east-1.amazonaws.com/hive/driver:7.5.0";
        CreateApplicationRequest wholeApplication = applicationRequest("custom", "emr-7.5.0", "HIVE");
        wholeApplication.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        // Tez in local mode runs its tasks in the driver's container, so a TezTask image is not used.
        wholeApplication.setWorkerTypeSpecifications(
                Map.of("TezTask", EmrServerlessServiceTest.workerType(driverImage)));
        CreateApplicationRequest perWorkerType = applicationRequest("per-worker-type", "emr-7.5.0", "HIVE");
        perWorkerType.setImageConfiguration(EmrServerlessServiceTest.image(applicationImage));
        perWorkerType.setWorkerTypeSpecifications(
                Map.of("HiveDriver", EmrServerlessServiceTest.workerType(driverImage)));
        String release = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();

        for (CreateApplicationRequest create : List.of(wholeApplication, perWorkerType)) {
            service.startJobRun(applications.createApplication(create).getApplicationId(),
                    hiveRequest("s3://bucket/queries/report.sql", null, null));
        }
        service.startJobRun(release, hiveRequest("s3://bucket/queries/report.sql", null, null));
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
    void aRunCancelledWithAGracePeriodGetsSigtermAndMayEndByItselfUntilThePeriodEnds() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String graceful = application("graceful", "emr-7.9.0", null);
        String ended = service.startJobRun(graceful, request(null, null)).getJobRunId();
        List<String> cancelling = new ArrayList<>();
        runner.whileRunning = () -> {
            cancelling.add(service.cancelJobRun(graceful, ended, 60).getState());
            // A second cancel while it is CANCELLING changes nothing.
            service.cancelJobRun(graceful, ended, 0);
            clock.advance(Duration.ofSeconds(59));
        };
        runNext();
        runNext();

        assertEquals(List.of("CANCELLING"), cancelling);
        assertEquals(List.of(false, false), runner.cancelledAsRunning, "its driver may run on within the period");
        assertEquals(Set.of(ended), runner.interrupted);
        assertTrue(runner.stopped.isEmpty());
        assertTrue(tasks.isEmpty());
        assertTrue(runner.jobs.get(0).interruptible(), "its driver gets the SIGTERM through an init process");
        assertEquals("CANCELLED", service.getJobRun(graceful, ended, null).getState());

        String outlasted = service.startJobRun(graceful, request(null, null)).getJobRunId();
        runner.cancelledAsRunning.clear();
        runner.whileRunning = () -> {
            service.cancelJobRun(graceful, outlasted, 15);
            clock.advance(Duration.ofSeconds(15));
        };
        runNext();
        runNext();

        assertEquals(List.of(false, true), runner.cancelledAsRunning, "its driver is stopped when the period ends");
        assertEquals("CANCELLED", service.getJobRun(graceful, outlasted, null).getState());
    }

    @Test
    void aStreamingRunHasAGracePeriodByDefaultAndABatchRunOrAnEarlierReleaseNone() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String graceful = application("graceful", "emr-7.9.0", null);
        String earlier = application("earlier", "emr-7.8.0", null);
        String streaming = service.startJobRun(graceful, request("STREAMING", null)).getJobRunId();
        runner.whileRunning = () -> {
            service.cancelJobRun(graceful, streaming);
            clock.advance(Duration.ofSeconds(119));
        };
        runNext();
        runNext();
        String batch = service.startJobRun(graceful, request(null, null)).getJobRunId();
        runner.whileRunning = () -> service.cancelJobRun(graceful, batch);
        runNext();
        runNext();
        String immediate = service.startJobRun(graceful, request("STREAMING", null)).getJobRunId();
        runner.whileRunning = () -> service.cancelJobRun(graceful, immediate, 0);
        runNext();
        runNext();
        String older = service.startJobRun(earlier, request("STREAMING", null)).getJobRunId();
        runner.whileRunning = () -> service.cancelJobRun(earlier, older, 60);
        runNext();
        runNext();

        assertEquals(Set.of(streaming), runner.interrupted);
        assertEquals(Set.of(batch, immediate, older), runner.stopped);
        assertEquals(List.of(false, false, false, true, false, true, false, true), runner.cancelledAsRunning);
        assertFalse(runner.jobs.get(3).interruptible(), "an earlier release's driver needs no init process");
        for (String run : List.of(streaming, batch, immediate)) {
            assertEquals("CANCELLED", service.getJobRun(graceful, run, null).getState());
        }
        assertEquals("CANCELLED", service.getJobRun(earlier, older, null).getState());
    }

    @Test
    void aGracePeriodOutsideItsRangeIsRefused() {
        EmrServerlessJobRunService service = service(60);
        String running = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        for (int seconds : List.of(14, 1801, -1)) {
            AwsException refused = assertThrows(AwsException.class,
                    () -> service.cancelJobRun(applicationId, running, seconds));
            assertEquals("ValidationException", refused.getErrorCode());
            assertEquals("shutdownGracePeriodInSeconds must be 0, or from 15 to 1800 seconds.", refused.getMessage());
        }
        assertEquals("CANCELLED", service.cancelJobRun(applicationId, running, 1800).getState());
    }

    @Test
    void aRunKeepsItsFlexExecutionAndANonBooleanEnabledIsRefused() {
        EmrServerlessJobRunService service = service(0);
        StartJobRunRequest flex = request(null, null);
        // A member FlexExecution does not have is not kept.
        flex.setFlexExecution(Map.of("enabled", true, "capacity", "spare"));
        String jobRunId = service.startJobRun(applicationId, flex).getJobRunId();

        assertEquals(Map.of("enabled", true), service.getJobRun(applicationId, jobRunId, null).getFlexExecution());
        JobRunSummary listed = service.listJobRuns(applicationId, null, null, null, null, null, null).items().stream()
                .filter(summary -> jobRunId.equals(summary.getId())).findFirst().orElseThrow();
        assertEquals(Map.of("enabled", true), listed.getFlexExecution());
        assertEquals(Map.of("enabled", true),
                service.listJobRunAttempts(applicationId, jobRunId, null, null).items().get(0).getFlexExecution());

        StartJobRunRequest notBoolean = request(null, null);
        notBoolean.setFlexExecution(Map.of("enabled", "yes"));
        AwsException refused = assertThrows(AwsException.class, () -> service.startJobRun(applicationId, notBoolean));
        assertEquals("SerializationException", refused.getErrorCode());
    }

    @Test
    void anEndedSparkRunGivesItsResourceUtilizationAndAHiveRunNone() {
        EmrServerlessJobRunService service = service(90);
        StartJobRunRequest sized = request(null, null);
        sized.getJobDriver().getSparkSubmit()
                .setSparkSubmitParameters("--conf spark.dynamicAllocation.enabled=false"
                        + " --conf spark.executor.instances=1 --conf spark.executor.cores=2");
        String spark = service.startJobRun(applicationId, sized).getJobRunId();
        String hiveApplication = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();
        String hive = service.startJobRun(hiveApplication, hiveRequest("s3://bucket/queries/report.sql", null, null))
                .getJobRunId();

        assertNull(service.getJobRun(applicationId, spark, null).getTotalResourceUtilization(),
                "a run still running has used nothing yet");
        clock.advance(Duration.ofSeconds(90));
        JobRun ended = service.getJobRun(applicationId, spark, null);

        // A default driver, 4 vCPUs, 16 GB and 20 GB, and one executor of 2 vCPUs, whose 14G and 1.4G of overhead
        // round up to a 2-vCPU worker's largest 16 GB, for 90 seconds.
        assertEquals("SUCCESS", ended.getState());
        assertEquals(Map.of("vCPUHour", 6 * 90 / 3600.0, "memoryGBHour", 32 * 90 / 3600.0,
                "storageGBHour", 40 * 90 / 3600.0), ended.getTotalResourceUtilization());
        assertEquals(Map.of("vCPUHour", 6 * 90 / 3600.0, "memoryGBHour", 32 * 90 / 3600.0, "storageGBHour", 0.0),
                ended.getBilledResourceUtilization());
        JobRun hiveRun = service.getJobRun(hiveApplication, hive, null);
        assertEquals("SUCCESS", hiveRun.getState());
        assertNull(hiveRun.getTotalResourceUtilization());
    }

    @Test
    void aRunWhoseExecutorsHadContainersCountsTheTimeEachRanAndOneStoredWithoutWorkersGetsThemAsItEnds() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String measured = service.startJobRun(applicationId, request(null, null)).getJobRunId();
        runner.whileRunning = () -> clock.advance(Duration.ofSeconds(100));
        runner.executorLifetimes = List.of(Duration.ofSeconds(30), Duration.ofMillis(90_500));
        runNext();

        // The driver for its attempt, a little over 100 seconds as the test's clock ticks, rounded up; the executors
        // for 121 seconds between them, each billed a minute at least.
        JobRun ended = service.getJobRun(applicationId, measured, null);
        long driver = Math.ceilDiv(Duration.between(ended.getStartedAt(), ended.getEndedAt()).toMillis(), 1000);
        assertEquals(101, driver);
        assertEquals((4 * driver + 4 * 121) / 3600.0, ended.getTotalResourceUtilization().get("vCPUHour"));
        assertEquals((4 * driver + 4 * 151) / 3600.0, ended.getBilledResourceUtilization().get("vCPUHour"));

        EmrServerlessJobRunService immediate = service(90);
        String stored = immediate.startJobRun(applicationId, request(null, null)).getJobRunId();
        JobRunRecord record = store.get(stored).orElseThrow();
        record.setWorkers(null);
        store.put(stored, record);
        clock.advance(Duration.ofSeconds(90));

        assertEquals(16 * 90 / 3600.0,
                immediate.getJobRun(applicationId, stored, null).getTotalResourceUtilization().get("vCPUHour"));
    }

    @Test
    void aRunsCredentialsAreScopedByItsInlinePolicyAndItKeepsOnlyThePolicysMembers() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
        String readOnly = "arn:aws:iam::aws:policy/ReadOnlyAccess";
        StartJobRunRequest scoped = request(null, null);
        scoped.setExecutionIamPolicy(Map.of("policy", policy, "policyArns", List.of(readOnly), "extra", "dropped"));
        String scopedRun = service.startJobRun(applicationId, scoped).getJobRunId();
        runNext();
        service.startJobRun(applicationId, request(null, null));
        runNext();

        assertEquals(Arrays.asList(policy, null), runner.sessionPolicies);
        assertEquals(Map.of("policy", policy, "policyArns", List.of(readOnly)),
                service.getJobRun(applicationId, scopedRun, null).getExecutionIamPolicy());
    }

    @Test
    void anExecutionIamPolicyOutsideTheModelsBoundsIsRefused() {
        EmrServerlessJobRunService service = service(0);
        List<String> elevenArns = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            elevenArns.add("arn:aws:iam::aws:policy/Policy" + i);
        }
        Map<Map<String, Object>, String> refusals = new LinkedHashMap<>();
        refusals.put(Map.of("policy", ""), "'executionIamPolicy.policy'");
        refusals.put(Map.of("policy", "x".repeat(2049)), "'executionIamPolicy.policy'");
        refusals.put(Map.of("policyArns", elevenArns), "'executionIamPolicy.policyArns'");
        refusals.put(Map.of("policyArns", List.of("arn:aws:iam::1:p/x")), "'executionIamPolicy.policyArns.1.member'");
        for (Map.Entry<Map<String, Object>, String> refusal : refusals.entrySet()) {
            StartJobRunRequest refused = request(null, null);
            refused.setExecutionIamPolicy(refusal.getKey());
            AwsException error = assertThrows(AwsException.class, () -> service.startJobRun(applicationId, refused));
            assertEquals("ValidationException", error.getErrorCode());
            assertTrue(error.getMessage().contains(refusal.getValue()), error.getMessage());
        }
        for (Map<String, Object> malformed : List.<Map<String, Object>>of(Map.of("policy", 1),
                Map.of("policyArns", "arn:aws:iam::aws:policy/ReadOnlyAccess"))) {
            StartJobRunRequest refused = request(null, null);
            refused.setExecutionIamPolicy(malformed);
            assertEquals("SerializationException", assertThrows(AwsException.class,
                    () -> service.startJobRun(applicationId, refused)).getErrorCode());
        }
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
    // An application on releaseLabel with these scheduler members, none for no scheduler configuration.
    private String queuingApplication(String releaseLabel, Map<String, Object> scheduler) {
        CreateApplicationRequest create = applicationRequest("queuing", releaseLabel, "SPARK");
        if (!scheduler.isEmpty()) {
            create.setSchedulerConfiguration(EmrServerlessServiceTest.scheduler(scheduler));
        }
        return applications.createApplication(create).getApplicationId();
    }

    private String start(EmrServerlessJobRunService service, String app, String mode) {
        return service.startJobRun(app, request(mode, null)).getJobRunId();
    }

    @Test
    void aRunBeyondMaxConcurrentRunsIsQueuedAndStartsTheMomentARunEnds() {
        EmrServerlessJobRunService service = service(600);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1));
        String first = start(service, app, null);
        clock.advance(Duration.ofSeconds(1));
        String second = start(service, app, null);

        assertEquals("RUNNING", service.getJobRun(app, first, null).getState());
        JobRun queued = service.getJobRun(app, second, null);
        assertEquals("QUEUED", queued.getState());
        assertNull(queued.getStartedAt());
        assertNull(queued.getQueuedDurationMilliseconds());
        assertEquals(List.of(second), service.listJobRuns(app, List.of("QUEUED"), null, null, null, null, null)
                .items().stream().map(JobRunSummary::getId).toList());

        // Read only once both have ended: the second started the moment the first ended.
        clock.advance(Duration.ofHours(1));
        JobRun ended = service.getJobRun(app, first, null);
        JobRun started = service.getJobRun(app, second, null);
        assertEquals("SUCCESS", started.getState());
        assertEquals(ended.getEndedAt(), started.getStartedAt());
        assertEquals(Duration.between(started.getCreatedAt(), ended.getEndedAt()).toMillis(),
                started.getQueuedDurationMilliseconds());
        assertEquals(600, started.getTotalExecutionDurationSeconds());
    }

    @Test
    void aRunStillQueuedAtTheQueueTimeoutIsCancelled() {
        EmrServerlessJobRunService service = service(0);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1, "queueTimeoutMinutes", 15));
        start(service, app, "STREAMING");
        String queued = start(service, app, null);

        clock.advance(Duration.ofMinutes(15));
        JobRun run = service.getJobRun(app, queued, null);
        assertEquals("CANCELLED", run.getState());
        assertEquals(run.getCreatedAt().plus(Duration.ofMinutes(15)), run.getEndedAt());
        assertEquals(900_000L, run.getQueuedDurationMilliseconds());
        assertNull(run.getStartedAt());
        assertEquals(0, run.getTotalExecutionDurationSeconds());
        assertTrue(run.getStateDetails().contains("15 minutes"), run.getStateDetails());
    }

    @Test
    void aCancelOrAHigherLimitOnTheStartedApplicationStartsTheQueuedRunsInOrderAtOnce() {
        EmrServerlessJobRunService service = service(0);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1));
        String first = start(service, app, "STREAMING");
        String second = start(service, app, "STREAMING");
        String third = start(service, app, "STREAMING");
        String fourth = start(service, app, "STREAMING");

        clock.advance(Duration.ofMinutes(1));
        service.cancelJobRun(app, third);
        JobRun cancelled = service.getJobRun(app, third, null);
        assertEquals("CANCELLED", cancelled.getState());
        assertEquals(Duration.between(cancelled.getCreatedAt(), cancelled.getEndedAt()).toMillis(),
                cancelled.getQueuedDurationMilliseconds());
        assertTrue(cancelled.getQueuedDurationMilliseconds() >= 60_000L);

        service.cancelJobRun(app, first);
        Instant firstEnded = service.getJobRun(app, first, null).getEndedAt();
        JobRun next = service.getJobRun(app, second, null);
        assertEquals("RUNNING", next.getState());
        assertFalse(next.getStartedAt().isBefore(firstEnded));
        assertTrue(Duration.between(firstEnded, next.getStartedAt()).toSeconds() < 1, next.getStartedAt()::toString);
        assertEquals("QUEUED", service.getJobRun(app, fourth, null).getState());
        AwsException stop = assertThrows(AwsException.class, () -> service.stopApplication(app));
        assertEquals("ValidationException", stop.getErrorCode());

        UpdateApplicationRequest raise = new UpdateApplicationRequest();
        raise.setSchedulerConfiguration(EmrServerlessServiceTest.scheduler(Map.of("maxConcurrentRuns", 2)));
        service.updateApplication(app, raise);
        assertEquals("RUNNING", service.getJobRun(app, fourth, null).getState());
    }

    @Test
    void runsQueueByDefaultFromRelease730AndNeverBeforeRelease700() {
        EmrServerlessJobRunService service = service(0);
        String byDefault = queuingApplication("emr-7.3.0", Map.of());
        String sparkRuntime = queuingApplication("emr-spark-8.0.0", Map.of());
        String notByDefault = queuingApplication("emr-7.2.0", Map.of());
        String tooEarly = queuingApplication("emr-6.15.0", Map.of("maxConcurrentRuns", 1));
        for (int run = 0; run < 15; run++) {
            for (String app : List.of(byDefault, sparkRuntime, notByDefault, tooEarly)) {
                start(service, app, "STREAMING");
            }
        }

        assertEquals("QUEUED", service.getJobRun(byDefault, start(service, byDefault, "STREAMING"), null).getState());
        assertEquals("QUEUED",
                service.getJobRun(sparkRuntime, start(service, sparkRuntime, "STREAMING"), null).getState());
        assertEquals("RUNNING",
                service.getJobRun(notByDefault, start(service, notByDefault, "STREAMING"), null).getState());
        assertEquals("RUNNING", service.getJobRun(tooEarly, start(service, tooEarly, "STREAMING"), null).getState());
    }

    @Test
    void aQueuedBatchRunThatStartsWithoutADurationEndsAsItStarts() {
        EmrServerlessJobRunService service = service(0);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1));
        String streaming = start(service, app, "STREAMING");
        String batch = start(service, app, null);
        assertEquals("QUEUED", service.getJobRun(app, batch, null).getState());

        service.cancelJobRun(app, streaming);

        JobRun run = service.getJobRun(app, batch, null);
        assertEquals("SUCCESS", run.getState());
        assertEquals(run.getStartedAt(), run.getEndedAt());
    }

    @Test
    void manyRunsEndingTogetherHandTheirSlotsOnInOrder() {
        EmrServerlessJobRunService service = service(600);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 5));
        List<String> runs = new ArrayList<>();
        for (int run = 0; run < 20; run++) {
            runs.add(start(service, app, null));
        }

        clock.advance(Duration.ofHours(1));
        List<JobRun> ended = runs.stream().map(id -> service.getJobRun(app, id, null)).toList();
        for (int run = 0; run < ended.size(); run++) {
            assertEquals("SUCCESS", ended.get(run).getState(), "run " + run);
            if (run >= 5) {
                assertEquals(ended.get(run - 5).getEndedAt(), ended.get(run).getStartedAt(), "run " + run);
            }
        }
    }

    @Test
    void anEmptySchedulerConfigurationTurnsQueuingOffOnAReleaseThatQueuesByDefault() {
        EmrServerlessJobRunService service = service(0);
        CreateApplicationRequest create = applicationRequest("off", "emr-7.5.0", "SPARK");
        create.setSchedulerConfiguration(new SchedulerConfiguration());
        String app = applications.createApplication(create).getApplicationId();
        assertNull(applications.getApplication(app).getSchedulerConfiguration());
        for (int run = 0; run < 15; run++) {
            start(service, app, "STREAMING");
        }

        assertEquals("RUNNING", service.getJobRun(app, start(service, app, "STREAMING"), null).getState());
    }

    @Test
    void aQueuedRunWhoseDriverIsNotItsApplicationsFailsAsItLeavesTheQueueAndTakesNoSlot() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1));
        String first = start(service, app, null);
        StartJobRunRequest hive = request(null, null);
        JobDriver.Hive query = new JobDriver.Hive();
        query.setQuery("s3://bucket/query.sql");
        JobDriver hiveDriver = new JobDriver();
        hiveDriver.setHive(query);
        hive.setJobDriver(hiveDriver);
        String mismatched = service.startJobRun(app, hive).getJobRunId();
        String next = start(service, app, null);
        clock.advance(Duration.ofSeconds(5));

        runNext();

        JobRun failed = service.getJobRun(app, mismatched, null);
        assertEquals("FAILED", failed.getState());
        assertEquals(EmrServerlessJobRunService.DRIVER_NOT_THE_APPLICATIONS, failed.getStateDetails());
        assertTrue(failed.getEndedAt().isAfter(failed.getCreatedAt()), "it fails as it leaves the queue");
        assertEquals(failed.getCreatedAt().plusMillis(failed.getQueuedDurationMilliseconds()), failed.getEndedAt());
        assertEquals("SCHEDULED", service.getJobRun(app, next, null).getState());
        assertEquals("SUCCESS", service.getJobRun(app, first, null).getState());
    }

    @Test
    void aQueuedContainerRunStartsItsDriverOnlyOnceTheRunningOneHasEnded() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String app = queuingApplication("emr-7.5.0", Map.of("maxConcurrentRuns", 1));
        String first = start(service, app, null);
        String second = start(service, app, null);
        String third = start(service, app, null);

        assertEquals(1, tasks.size(), "only the first run's driver is launched");
        assertEquals("QUEUED", service.getJobRun(app, second, null).getState());
        service.cancelJobRun(app, third);
        assertEquals("CANCELLED", service.getJobRun(app, third, null).getState());
        assertFalse(runner.stopped.contains(third), "a queued run has no driver to stop");

        runNext();
        assertEquals("SUCCESS", service.getJobRun(app, first, null).getState());
        assertEquals("SCHEDULED", service.getJobRun(app, second, null).getState());
        runNext();
        assertEquals("SUCCESS", service.getJobRun(app, second, null).getState());
        assertEquals(List.of(first, second), runner.driversStarted);
        assertTrue(tasks.isEmpty());
    }

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

    // A run of query, after initQueryFile, with parameters, either of the two null for none.
    private static StartJobRunRequest hiveRequest(String query, String initQueryFile, String parameters) {
        JobDriver.Hive hive = new JobDriver.Hive();
        hive.setQuery(query);
        hive.setInitQueryFile(initQueryFile);
        hive.setParameters(parameters);
        StartJobRunRequest request = request(null, null);
        request.getJobDriver().setSparkSubmit(null);
        request.getJobDriver().setHive(hive);
        return request;
    }

    private static RuntimeConfiguration configuration(String classification, Map<String, String> properties) {
        RuntimeConfiguration configuration = new RuntimeConfiguration();
        configuration.setMember("classification", classification);
        configuration.setMember("properties", properties);
        return configuration;
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

    @Test
    void aRunsDriverEnvAndArchivesAreItsSparkPropertiesTheSubmitParametersWinning() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        RuntimeConfiguration defaults = new RuntimeConfiguration();
        defaults.setMember("classification", "spark-defaults");
        defaults.setMember("properties", Map.of(
                "spark.emr-serverless.driverEnv.JAVA_HOME", "/usr/lib/jvm/java-17-amazon-corretto.x86_64/",
                "spark.archives", "s3://bucket/envs/previous.tar.gz#environment"));
        CreateApplicationRequest create = applicationRequest("python", "emr-7.5.0", "SPARK");
        create.setRuntimeConfiguration(List.of(defaults));
        String python = applications.createApplication(create).getApplicationId();
        // The user guide's "Building a Python virtual environment".
        StartJobRunRequest request = request(null, null);
        request.getJobDriver().getSparkSubmit().setSparkSubmitParameters(String.join(" ",
                "--conf spark.archives=s3://bucket/envs/pyspark_venv.tar.gz#environment",
                "--conf spark.emr-serverless.driverEnv.PYSPARK_DRIVER_PYTHON=./environment/bin/python",
                "--conf spark.emr-serverless.driverEnv.PYSPARK_PYTHON=./environment/bin/python",
                "--conf spark.executorEnv.PYSPARK_PYTHON=./environment/bin/python"));

        service.startJobRun(python, request);
        runNext();

        SparkJobRunner.Job job = runner.jobs.get(0);
        assertEquals(Map.of("JAVA_HOME", "/usr/lib/jvm/java-17-amazon-corretto.x86_64/",
                "PYSPARK_DRIVER_PYTHON", "./environment/bin/python", "PYSPARK_PYTHON", "./environment/bin/python"),
                job.environment());
        assertEquals(List.of("s3://bucket/envs/pyspark_venv.tar.gz#environment"), job.archives());
    }

    @Test
    void sparkPropertiesTakeEachFormOfConfASubmitGivesALaterOneWinning() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setSparkSubmitParameters("--conf spark.archives=s3://bucket/a.zip "
                + "--conf='spark.archives= s3://bucket/b.tar.gz#b ,, s3://bucket/c.tgz' "
                + "-c spark.emr-serverless.driverEnv.A=1=2 --conf spark.emr-serverless.driverEnv.=x "
                + "--conf no-value --name spark.archives=ignored --conf");

        Map<String, String> properties = EmrServerlessJobRunService.sparkProperties(
                Map.of("spark.executor.cores", "4"), sparkSubmit);

        assertEquals(Map.of("spark.executor.cores", "4",
                "spark.archives", " s3://bucket/b.tar.gz#b ,, s3://bucket/c.tgz",
                "spark.emr-serverless.driverEnv.A", "1=2", "spark.emr-serverless.driverEnv.", "x"), properties);
        assertEquals(List.of("s3://bucket/b.tar.gz#b", "s3://bucket/c.tgz"),
                EmrServerlessJobRunService.archives(properties));
        assertEquals(Map.of("A", "1=2"), EmrServerlessJobRunService.driverEnvironment(properties));
    }

    @Test
    void theLastArchivesOptionIsSparkArchivesWinningOverASettingOfItWhereverThatComes() {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setSparkSubmitParameters("--archives s3://bucket/first.zip "
                + "--archives=s3://bucket/envs/pyspark_venv.tar.gz#environment "
                + "--conf spark.archives=s3://bucket/conf.zip");

        Map<String, String> properties = EmrServerlessJobRunService.sparkProperties(
                Map.of("spark.archives", "s3://bucket/defaults.zip"), sparkSubmit);

        assertEquals(Map.of("spark.archives", "s3://bucket/envs/pyspark_venv.tar.gz#environment"), properties);
        sparkSubmit.setSparkSubmitParameters("--conf spark.archives=s3://bucket/conf.zip --archives");
        assertEquals(Map.of("spark.archives", "s3://bucket/conf.zip"),
                EmrServerlessJobRunService.sparkProperties(Map.of(), sparkSubmit));
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
    void aHiveRunGoesToTheRunnerWithItsSettingsQueryFilesAndParametersInTheGuidesOrder() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        CreateApplicationRequest create = applicationRequest("hive", "emr-7.5.0", "HIVE");
        create.setRuntimeConfiguration(List.of(
                configuration("core-site", Map.of("fs.s3a.connection.maximum", "50")),
                configuration("hive-site", Map.of("hive.exec.scratchdir", "s3://bucket/app-scratch/"))));
        create.setMonitoringConfiguration(s3LogUri("s3://logs/hive"));
        String hive = applications.createApplication(create).getApplicationId();
        StartJobRunRequest request = hiveRequest("s3://bucket/queries/report.sql", "s3://bucket/queries/init.sql",
                "--hiveconf hive.exec.reducers.max=4 --hivevar day=2026-11-03");
        // The run's hive-site replaces the application's, and its tez-site adds Tez's file.
        request.setConfigurationOverrides(Map.of("applicationConfiguration", List.of(
                Map.of("classification", "hive-site", "properties",
                        Map.of("hive.exec.scratchdir", "s3://bucket/scratch/")),
                Map.of("classification", "tez-site", "properties", Map.of("tez.grouping.max-size", "268435456")))));

        String jobRunId = service.startJobRun(hive, request).getJobRunId();
        runNext();

        SparkJobRunner.Job job = runner.jobs.get(0);
        assertEquals(SparkJobRunner.Engine.HIVE, job.engine());
        assertNull(job.imageTemplate());
        assertEquals(List.of(
                "--hiveconf", "fs.s3a.connection.maximum=50",
                "--hiveconf", "tez.grouping.max-size=268435456",
                "--hiveconf", "hive.exec.scratchdir=s3://bucket/scratch/",
                "-i", "s3://bucket/queries/init.sql", "-f", "s3://bucket/queries/report.sql",
                "--hiveconf", "hive.exec.reducers.max=4", "--hivevar", "day=2026-11-03"), job.application());
        assertEquals(Map.of(), job.sparkDefaults());
        assertEquals(List.of(), job.archives());
        assertEquals("s3://logs/hive/applications/" + hive + "/jobs/" + jobRunId + "/HIVE_DRIVER/", job.logFolder());
        assertEquals("SUCCESS", service.getJobRun(hive, jobRunId, null).getState());
    }

    @Test
    void aSparkRunGetsTheImageTemplateAndAHiveRunTheHiveImageTemplate() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = new EmrServerlessJobRunService(store, applications, iamService,
                new RegionResolver(REGION, ACCOUNT_ID), null, objectMapper, 0, clock, runner, tasks::add,
                "registry.internal/emr-serverless/spark/%s:20250425",
                "registry.internal/emr-serverless/hive/%s:20250425", "floci-network");
        String hive = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();

        service.startJobRun(applicationId, request(null, null));
        service.startJobRun(hive, hiveRequest("s3://bucket/queries/report.sql", null, null));
        runNext();
        runNext();

        assertEquals("registry.internal/emr-serverless/spark/%s:20250425", runner.jobs.get(0).imageTemplate());
        assertEquals("registry.internal/emr-serverless/hive/%s:20250425", runner.jobs.get(1).imageTemplate());
        assertEquals("floci-network", runner.jobs.get(1).dockerNetwork());
    }

    @Test
    void aHiveRunWithoutAnInitQueryFileOrParametersRunsItsQuery() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String hive = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();

        service.startJobRun(hive, hiveRequest("s3://bucket/queries/report.sql", null, null));
        runNext();

        assertEquals(List.of("-f", "s3://bucket/queries/report.sql"), runner.jobs.get(0).application());
    }

    @Test
    void aRunWhoseDriverIsNotItsApplicationsTypeFailsWithoutRunning() {
        FakeRunner runner = new FakeRunner();
        EmrServerlessJobRunService service = dockerService(runner);
        String hive = applications.createApplication(applicationRequest("hive", "emr-7.5.0", "HIVE"))
                .getApplicationId();

        JobRun sparkOnHive = service.startJobRun(hive, request(null, null));
        JobRun hiveOnSpark = service.startJobRun(applicationId,
                hiveRequest("s3://bucket/queries/report.sql", null, null));

        for (JobRun run : List.of(sparkOnHive, hiveOnSpark)) {
            assertEquals("FAILED", run.getState());
            assertEquals(EmrServerlessJobRunService.DRIVER_NOT_THE_APPLICATIONS, run.getStateDetails());
        }
        assertEquals(List.of(), tasks);
        assertEquals(List.of(), runner.jobs);
    }

    @Test
    void aHiveRunsDriverLogsToCloudWatchUnderItsHiveDriverAsLogTypesNamesIt() {
        Application app = new Application();
        app.setMonitoringConfiguration(cloudWatchLogging(Map.of("enabled", true)));
        JobRun run = run("emr-7.5.0", "BATCH", null);
        JobDriver driver = new JobDriver();
        driver.setHive(new JobDriver.Hive());
        run.setJobDriver(driver);

        assertEquals(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless",
                "/applications/app/jobs/run/HIVE_DRIVER/stdout", "/applications/app/jobs/run/HIVE_DRIVER/stderr"),
                EmrServerlessJobRunService.driverCloudWatchLogs(app, run));

        // "HIVE_DRIVER : ["STDERR", "STDOUT", "HIVE_LOG", "TEZ_AM"]": its driver's, not SPARK_DRIVER's.
        app.setMonitoringConfiguration(cloudWatchLogging(Map.of("enabled", true,
                "logTypes", Map.of("HIVE_DRIVER", List.of("STDERR"), "SPARK_DRIVER", List.of("STDOUT")))));

        assertEquals(new SparkJobRunner.CloudWatchLogs("/aws/emr-serverless", null,
                "/applications/app/jobs/run/HIVE_DRIVER/stderr"),
                EmrServerlessJobRunService.driverCloudWatchLogs(app, run));
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

    // A service whose Spark runs have up to executorContainers executors in containers of their own.
    private EmrServerlessJobRunService dockerService(SparkJobRunner runner, int executorContainers) {
        return new EmrServerlessJobRunService(store, applications, iamService, new RegionResolver(REGION, ACCOUNT_ID),
                null, objectMapper, 0, clock, runner, tasks::add, null, null, null, executorContainers);
    }

    // A service whose container runs run for their application's architecture, as with honour-architectures on.
    private EmrServerlessJobRunService honouringService(SparkJobRunner runner) {
        return new EmrServerlessJobRunService(store, applications, iamService, new RegionResolver(REGION, ACCOUNT_ID),
                null, objectMapper, 0, clock, runner, tasks::add, null, null, null, 0, true);
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
        final Set<String> interrupted = new HashSet<>();
        final List<String> driversStarted = new ArrayList<>();
        // The session policy each run's credentials were scoped by, as its driver started.
        final List<String> sessionPolicies = new ArrayList<>();
        // What progress.cancelled() said once the driver ran, and again after whileRunning.
        final List<Boolean> cancelledAsRunning = new ArrayList<>();
        Outcome outcome = Outcome.exitedWith(0);
        Runnable whileRunning = () -> { };
        Runnable whileTimingOut = () -> { };
        // How long its executors' workers ran, told as the run ends, as the Docker runner tells it for executors with
        // containers of their own; null for none told.
        List<Duration> executorLifetimes;
        // The manifest digest each custom image resolves to, told before the driver starts as the Docker runner does,
        // and the executors' where their pull resolves another, as a push between the two pulls gives.
        final Map<String, String> digests = new HashMap<>();
        final Map<String, String> executorDigests = new HashMap<>();
        Runnable whileResolving = () -> { };

        @Override
        public Outcome run(Job job, Progress progress) {
            jobs.add(job);
            // As the Docker runner, a stop counts only for a run in progress, so a cancel before then is read here.
            if (progress.cancelled()) {
                return Outcome.halted();
            }
            // The driver's first, so an executors' digest written over it shows.
            resolved(progress, WorkerType.DRIVER, job.customImage(), digests);
            if (job.executors() != null) {
                Map<String, String> executorsResolve = new HashMap<>(digests);
                executorsResolve.putAll(executorDigests);
                resolved(progress, WorkerType.EXECUTOR, job.executors().image(), executorsResolve);
            }
            driversStarted.add(job.runId());
            sessionPolicies.add(progress.sessionPolicy());
            progress.started();
            cancelledAsRunning.add(progress.cancelled());
            whileRunning.run();
            cancelledAsRunning.add(progress.cancelled());
            if (outcome.timedOut()) {
                progress.timedOut();
                whileTimingOut.run();
            }
            if (executorLifetimes != null) {
                progress.executorsRan(executorLifetimes);
            }
            return stopped.contains(job.runId()) ? Outcome.halted() : outcome;
        }

        private void resolved(Progress progress, WorkerType workerType, String image, Map<String, String> resolve) {
            if (image != null && resolve.containsKey(image)) {
                whileResolving.run();
                progress.imageResolved(workerType, resolve.get(image));
            }
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
        }

        @Override
        public void interrupt(String runId) {
            interrupted.add(runId);
        }

        @Override
        public void removeLeftoverDrivers(String service) {
            // A test's drivers live no longer than its runs.
        }
    }
}
