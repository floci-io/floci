package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import io.github.hectorvent.floci.services.kinesisanalytics.model.Snapshot;
import io.github.hectorvent.floci.services.kinesisanalytics.model.SnapshotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class KinesisAnalyticsV2ServiceTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/x";

    private KinesisAnalyticsV2Service service;

    @BeforeEach
    void setUp() {
        service = mockModeService();
    }

    private FlinkApplication create(String name) {
        return service.createApplication(name, "FLINK-1_18", ROLE, "desc", "STREAMING");
    }

    @Test
    void createApplicationLandsInReady() {
        // AWS-faithful: a freshly created application is READY, not RUNNING.
        FlinkApplication app = create("demo");
        assertEquals("demo", app.getApplicationName());
        assertEquals("FLINK-1_18", app.getRuntimeEnvironment());
        assertEquals(ApplicationStatus.READY, app.getApplicationStatus());
        assertEquals(1L, app.getApplicationVersionId());
        assertTrue(app.getApplicationArn().contains(":kinesisanalytics:"));
        assertTrue(app.getApplicationArn().contains("application/demo"));
    }

    @Test
    void createApplicationRequiresName() {
        assertThrows(AwsException.class,
                () -> service.createApplication(" ", "FLINK-1_18", ROLE, null, null));
    }

    @Test
    void createApplicationRejectsNamesOutsideAwsCharsetAndLength() {
        // AWS ApplicationName Pattern: [a-zA-Z0-9_.-]+, 1-128 chars. Rejecting this at the API
        // boundary (matching real AWS) is also what keeps a name containing '%', '"', '\', or '$'
        // from ever reaching FlinkContainerManager's generated log4j2 CloudWatch-log-format pattern,
        // where those characters would otherwise be a log4j2 conversion-specifier/Lookup or JSON
        // injection risk.
        for (String badName : List.of("has spaces", "quote\"here", "percent%here", "dollar${x}",
                "back\\slash", "a".repeat(129))) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.createApplication(badName, "FLINK-1_18", ROLE, null, null));
            assertEquals("InvalidArgumentException", ex.getErrorCode());
        }
    }

    @Test
    void startApplicationRejectsLegacyStatePersistedBeforeNameValidationExisted() {
        // Simulates an application created by a floci build older than
        // createApplicationRejectsNamesOutsideAwsCharsetAndLength's check, by writing directly to
        // storage (bypassing createApplication). startApplication must still reject it rather than
        // silently generating a CloudWatch-log applicationARN that either drops the '$' (mismatching
        // the real ApplicationARN) or, if some future change stopped dropping it, resolves it as a
        // log4j2 Lookup.
        AccountAwareStorageBackend<FlinkApplication> store =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.doReturn(store).when(storageFactory)
                .create(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        KinesisAnalyticsV2Service legacyState = new KinesisAnalyticsV2Service(
                storageFactory, config, regionResolver, Mockito.mock(FlinkContainerManager.class));
        FlinkApplication legacyApp = new FlinkApplication("dollar${x}",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/dollar${x}",
                "FLINK-1_18", ROLE, "STREAMING");
        store.putForAccount("000000000000", "dollar${x}", legacyApp);

        AwsException ex = assertThrows(AwsException.class, () -> legacyState.startApplication("dollar${x}"));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void createApplicationRequiresRuntimeEnvironment() {
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", null, ROLE, null, null));
    }

    @Test
    void createApplicationRequiresServiceExecutionRole() {
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", "FLINK-1_18", null, null, null));
    }

    @Test
    void createApplicationRejectsUnsupportedRuntime() {
        // SQL/ZEPPELIN studio runtimes and bogus values are not backable with a Flink image.
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", "ZEPPELIN-FLINK-3_0", ROLE, null, null));
    }

    @Test
    void createApplicationRejectsDuplicateName() {
        create("demo");
        assertThrows(AwsException.class, () -> create("demo"));
    }

    @Test
    void describeApplicationThrowsWhenMissing() {
        assertThrows(AwsException.class, () -> service.describeApplication("nope"));
    }

    @Test
    void startApplicationRunsInMockMode() {
        create("demo");
        FlinkApplication started = service.startApplication("demo");
        // In mock mode there is no container: the application comes up RUNNING immediately.
        assertEquals(ApplicationStatus.RUNNING, started.getApplicationStatus());
    }

    @Test
    void startApplicationRejectedWhenNotReady() {
        create("demo");
        service.startApplication("demo");
        // Already RUNNING → cannot start again.
        assertThrows(AwsException.class, () -> service.startApplication("demo"));
    }

    @Test
    void stopApplicationReturnsToReady() {
        create("demo");
        service.startApplication("demo");
        FlinkApplication stopped = service.stopApplication("demo");
        assertEquals(ApplicationStatus.READY, stopped.getApplicationStatus());
    }

    @Test
    void stopApplicationRejectedWhenNotRunning() {
        create("demo");
        // Still READY (never started) → cannot stop.
        assertThrows(AwsException.class, () -> service.stopApplication("demo"));
    }

    @Test
    void updateApplicationBumpsVersion() {
        create("demo");
        FlinkApplication updated = service.updateApplication("demo", 1L, "arn:aws:iam::000000000000:role/y");
        assertEquals(2L, updated.getApplicationVersionId());
        assertEquals("arn:aws:iam::000000000000:role/y", updated.getServiceExecutionRole());
    }

    @Test
    void updateApplicationRejectsStaleVersion() {
        create("demo"); // version 1
        // Optimistic concurrency: a mismatched CurrentApplicationVersionId is rejected.
        assertThrows(AwsException.class, () -> service.updateApplication("demo", 5L, null));
    }

    @Test
    void deleteApplicationRemovesIt() {
        FlinkApplication app = create("demo");
        service.deleteApplication("demo", app.getCreateTimestamp());
        assertTrue(service.listApplications().isEmpty());
    }

    @Test
    void deleteApplicationRejectsWrongTimestamp() {
        create("demo");
        assertThrows(AwsException.class,
                () -> service.deleteApplication("demo", java.time.Instant.ofEpochSecond(1)));
    }

    @Test
    void deleteApplicationRejectedWhileRunning() {
        FlinkApplication app = create("demo");
        service.startApplication("demo"); // mock mode → RUNNING
        // AWS rejects delete of a running application; it must be stopped first.
        assertThrows(AwsException.class,
                () -> service.deleteApplication("demo", app.getCreateTimestamp()));
    }

    @Test
    void startApplicationWrapsProvisioningFailure() {
        // Real mode with a container manager that fails: the AWS envelope must not leak the
        // internal cause, but the operation must throw. "InternalFailure" (no "Exception" suffix) is
        // AWS's documented generic 500 common to every service's API, not a kinesisanalyticsv2-specific
        // shape (this service's model declares no 5xx shape at all).
        KinesisAnalyticsV2Service realMode = realModeServiceWithFailingManager();
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        AwsException ex = assertThrows(AwsException.class, () -> realMode.startApplication("demo"));
        assertEquals("InternalFailure", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    void startApplicationPreservesAwsExceptionFromCodeFetch() {
        // A bad application-code S3 location makes startCluster throw AwsException(InvalidArgumentException).
        // It must propagate as-is (400 client error), not be masked as InternalFailure (500).
        FlinkContainerManager failing = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new AwsException("InvalidArgumentException",
                        "Unable to fetch application code from s3://b/missing.jar", 400))
                .when(failing).startCluster(Mockito.any());
        KinesisAnalyticsV2Service realMode = buildService(false, failing);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);

        AwsException ex = assertThrows(AwsException.class, () -> realMode.startApplication("demo"));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void createApplicationStoresCodeConfig() {
        FlinkApplication app = service.createApplication("coded", "FLINK-1_18", ROLE, null, null,
                "flink-code", "app.jar", "v2", 4);
        assertTrue(app.hasCode());
        assertEquals("flink-code", app.getCodeS3Bucket());
        assertEquals("app.jar", app.getCodeS3Key());
        assertEquals("v2", app.getCodeS3ObjectVersion());
        assertEquals(4, app.getParallelism());
    }

    @Test
    void createApplicationWithoutCodeHasNoCode() {
        FlinkApplication app = create("bare");
        assertFalse(app.hasCode());
        assertEquals(1, app.getParallelism());
    }

    @Test
    void createApplicationStoresTags() {
        FlinkApplication app = service.createApplication("tagged", "FLINK-1_18", ROLE, null, null,
                null, null, null, 1, Map.of("env", "dev"));
        assertEquals("dev", app.getTags().get("env"));
    }

    @Test
    void createApplicationStoresEnvironmentProperties() {
        Map<String, Map<String, String>> groups = Map.of(
                "ProducerConfigProperties", Map.of("aws.region", "us-west-2"));
        FlinkApplication app = service.createApplication("props", "FLINK-1_18", ROLE, null, null,
                null, null, null, 1, null, groups);
        assertEquals("us-west-2",
                app.getEnvironmentProperties().get("ProducerConfigProperties").get("aws.region"));
    }

    @Test
    void createApplicationDefaultsSnapshotsEnabledToTrue() {
        FlinkApplication app = create("demo");
        assertTrue(app.isSnapshotsEnabled());
    }

    @Test
    void createApplicationWithSnapshotsDisabled() {
        FlinkApplication app = service.createApplication("demo", "FLINK-1_18", ROLE, null, null,
                null, null, null, 1, null, null, false);
        assertFalse(app.isSnapshotsEnabled());
    }

    @Test
    void createApplicationRejectsTooManyTags() {
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            tooMany.put("k" + i, "v");
        }
        AwsException ex = assertThrows(AwsException.class, () -> service.createApplication(
                "tagged", "FLINK-1_18", ROLE, null, null, null, null, null, 1, tooMany));
        assertEquals("TooManyTagsException", ex.getErrorCode());
    }

    @Test
    void tagResourceThenListTagsForResourceRoundTrips() {
        FlinkApplication app = create("demo");

        Map<String, String> after = service.tagResource(app.getApplicationArn(), Map.of("team", "platform"));
        assertEquals("platform", after.get("team"));
        assertEquals("platform", service.listTagsForResource(app.getApplicationArn()).get("team"));
    }

    @Test
    void untagResourceRemovesKeys() {
        FlinkApplication app = create("demo");
        service.tagResource(app.getApplicationArn(), Map.of("team", "platform", "env", "dev"));

        Map<String, String> after = service.untagResource(app.getApplicationArn(), List.of("team"));
        assertFalse(after.containsKey("team"));
        assertEquals("dev", after.get("env"));
    }

    @Test
    void tagResourceRejectsUnknownArn() {
        assertThrows(AwsException.class, () -> service.tagResource(
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/nope", Map.of("k", "v")));
    }

    @Test
    void tagResourceRejectsExceedingMaxTags() {
        FlinkApplication app = create("demo");
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            tooMany.put("k" + i, "v");
        }
        AwsException ex = assertThrows(AwsException.class,
                () -> service.tagResource(app.getApplicationArn(), tooMany));
        assertEquals("TooManyTagsException", ex.getErrorCode());
    }

    private FlinkApplication createRunningWithCode(String name) {
        service.createApplication(name, "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        return service.startApplication(name); // mock mode: RUNNING immediately
    }

    @Test
    void createApplicationSnapshotRequiresRunningApplication() {
        create("demo"); // READY, never started
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("demo", "snap1"));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void createApplicationSnapshotRejectsWhenSnapshotsDisabled() {
        service.createApplication("demo", "FLINK-1_18", ROLE, null, null,
                "bucket", "app.jar", null, 1, null, null, false);
        service.startApplication("demo"); // mock mode: RUNNING immediately

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("demo", "snap1"));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void updateApplicationTogglesSnapshotsEnabled() {
        FlinkApplication app = createRunningWithCode("demo");
        assertTrue(app.isSnapshotsEnabled());

        service.updateApplication("demo", 1L, null, null, null, null, null, false);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("demo", "snap1"));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void createApplicationSnapshotRequiresCode() {
        create("demo");
        service.startApplication("demo"); // mock mode: RUNNING even with no code
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("demo", "snap1"));
        assertEquals("InvalidRequestException", ex.getErrorCode());
    }

    @Test
    void createApplicationSnapshotSucceedsInMockMode() {
        createRunningWithCode("demo");
        Snapshot snapshot = service.createApplicationSnapshot("demo", "snap1");
        assertEquals(SnapshotStatus.READY, snapshot.getSnapshotStatus());
        assertEquals(1L, snapshot.getApplicationVersionId());
    }

    @Test
    void createApplicationSnapshotRejectsDuplicateName() {
        createRunningWithCode("demo");
        service.createApplicationSnapshot("demo", "snap1");
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("demo", "snap1"));
        assertEquals("ResourceInUseException", ex.getErrorCode());
    }

    @Test
    void describeApplicationSnapshotThrowsWhenMissing() {
        createRunningWithCode("demo");
        assertThrows(AwsException.class, () -> service.describeApplicationSnapshot("demo", "nope"));
    }

    @Test
    void listApplicationSnapshotsIncludesCreated() {
        createRunningWithCode("demo");
        service.createApplicationSnapshot("demo", "snap1");
        service.createApplicationSnapshot("demo", "snap2");
        assertEquals(2, service.listApplicationSnapshots("demo").size());
    }

    @Test
    void deleteApplicationSnapshotRemovesIt() {
        createRunningWithCode("demo");
        Snapshot snapshot = service.createApplicationSnapshot("demo", "snap1");
        service.deleteApplicationSnapshot("demo", "snap1", snapshot.getSnapshotCreationTimestamp());
        assertTrue(service.listApplicationSnapshots("demo").isEmpty());
    }

    @Test
    void deleteApplicationSnapshotRejectsWrongTimestamp() {
        createRunningWithCode("demo");
        service.createApplicationSnapshot("demo", "snap1");
        assertThrows(AwsException.class, () -> service.deleteApplicationSnapshot(
                "demo", "snap1", java.time.Instant.ofEpochSecond(1)));
    }

    @Test
    void deleteApplicationSnapshotRejectsWhileStillCreating() {
        // Real mode, but the mocked FlinkContainerManager.createSnapshot never actually advances the
        // snapshot to READY/FAILED, so it stays CREATING — matching a real Flink savepoint still
        // in flight when a delete is attempted.
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        // Real mode's startApplication only marks STARTING; force RUNNING directly to isolate the
        // snapshot precondition from the readiness-poller mechanics already covered elsewhere.
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        Snapshot snapshot = realMode.createApplicationSnapshot("demo", "snap1");
        assertEquals(SnapshotStatus.CREATING, snapshot.getSnapshotStatus());

        AwsException ex = assertThrows(AwsException.class, () -> realMode.deleteApplicationSnapshot(
                "demo", "snap1", snapshot.getSnapshotCreationTimestamp()));
        assertEquals("ResourceInUseException", ex.getErrorCode());
    }

    @Test
    void createApplicationSnapshotMarksFailedWhenTriggerThrows() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new RuntimeException("jobmanager unreachable"))
                .when(manager).createSnapshot(Mockito.any(), Mockito.any());
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);

        // The trigger failure marks the snapshot FAILED rather than failing CreateApplicationSnapshot
        // itself, the same async-follow-up-failure shape job submission already uses.
        Snapshot snapshot = realMode.createApplicationSnapshot("demo", "snap1");
        assertEquals(SnapshotStatus.FAILED, snapshot.getSnapshotStatus());
    }

    @Test
    void updateApplicationWithNewCodeWhileReadyJustUpdatesStoredCode() {
        service.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);

        FlinkApplication updated = service.updateApplication("demo", 1L, null,
                "flink-code", "v2.jar", null, null);

        assertEquals("flink-code", updated.getCodeS3Bucket());
        assertEquals("v2.jar", updated.getCodeS3Key());
        assertEquals(ApplicationStatus.READY, updated.getApplicationStatus());
        assertEquals(2L, updated.getApplicationVersionId());
    }

    @Test
    void updateApplicationUpdatesParallelism() {
        create("demo");
        FlinkApplication updated = service.updateApplication("demo", 1L, null, null, null, null, 5);
        assertEquals(5, updated.getParallelism());
    }

    @Test
    void updateApplicationRedeploysCodeInPlaceWhenRunningInRealMode() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        app.setTaskManagerContainerId("tm-1");
        app.setFlinkJobId("job-1");

        FlinkApplication updated = realMode.updateApplication("demo", 1L, null,
                "bucket", "v2.jar", null, null);

        Mockito.verify(manager).redeployCode(app);
        assertEquals("v2.jar", updated.getCodeS3Key());
        assertEquals(ApplicationStatus.STARTING, updated.getApplicationStatus());
    }

    @Test
    void updateApplicationWrapsRedeployFailureAsInternalFailure() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(manager).redeployCode(Mockito.any());
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        app.setTaskManagerContainerId("tm-1");

        AwsException ex = assertThrows(AwsException.class, () -> realMode.updateApplication(
                "demo", 1L, null, "bucket", "v2.jar", null, null));
        assertEquals("InternalFailure", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    void updateApplicationRejectsCodeChangeOnRunningBareCluster() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);

        AwsException ex = assertThrows(AwsException.class, () -> realMode.updateApplication(
                "demo", 1L, null, "bucket", "app.jar", null, null));
        assertEquals("InvalidRequestException", ex.getErrorCode());
        Mockito.verify(manager, Mockito.never()).redeployCode(Mockito.any());
    }

    @Test
    void updateApplicationWithCodeChangeInMockModeSkipsContainerRedeploy() {
        service.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        service.startApplication("demo"); // mock mode: RUNNING immediately, no container

        FlinkApplication updated = service.updateApplication("demo", 1L, null,
                "bucket", "v2.jar", null, null);

        assertEquals("v2.jar", updated.getCodeS3Key());
        // Mock mode has no container to redeploy against, so the application simply stays RUNNING.
        assertEquals(ApplicationStatus.RUNNING, updated.getApplicationStatus());
    }

    @Test
    void createApplicationPresignedUrlReturnsRestEndpointWhenRunning() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        app.setRestEndpoint("http://localhost:41000");

        String url = realMode.createApplicationPresignedUrl("demo", "FLINK_DASHBOARD_URL", 1800L);
        assertEquals("http://localhost:41000", url);
    }

    @Test
    void createApplicationPresignedUrlRejectsWhenNotRunning() {
        create("demo"); // READY, never started
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createApplicationPresignedUrl("demo", "FLINK_DASHBOARD_URL", null));
        assertEquals("ResourceInUseException", ex.getErrorCode());
    }

    @Test
    void createApplicationPresignedUrlRejectsUnsupportedUrlType() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        app.setRestEndpoint("http://localhost:41000");

        AwsException ex = assertThrows(AwsException.class,
                () -> realMode.createApplicationPresignedUrl("demo", "ZEPPELIN_UI_URL", null));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void createApplicationPresignedUrlRejectsOutOfRangeExpiration() {
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service realMode = buildService(false, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.RUNNING);
        app.setRestEndpoint("http://localhost:41000");

        AwsException ex = assertThrows(AwsException.class,
                () -> realMode.createApplicationPresignedUrl("demo", "FLINK_DASHBOARD_URL", 100L));
        assertEquals("InvalidArgumentException", ex.getErrorCode());
    }

    @Test
    void createApplicationPresignedUrlRejectsUnknownApplication() {
        assertThrows(AwsException.class,
                () -> service.createApplicationPresignedUrl("nope", "FLINK_DASHBOARD_URL", null));
    }

    @Test
    void readinessPollerPersistsFlinkJobIdAssignedBeforeReachingRunning() throws InterruptedException {
        // Regression test: advanceToRunning can assign flinkJobId on a tick where the Flink job has
        // been submitted but hasn't reached RUNNING yet (it returns false in that case). That
        // intermediate state must still be persisted — not just the final RUNNING transition — since
        // pendingJars (a separate in-process-only cache) is already cleared by then; an emulator
        // restart before the next persist would otherwise leave the application permanently stuck.
        AccountAwareStorageBackend<FlinkApplication> store =
                Mockito.spy(AccountAwareStorageBackend.inMemory("000000000000"));
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.doReturn(store).when(storageFactory)
                .create(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(false);
        when(config.defaultRegion()).thenReturn("us-east-1");

        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        when(manager.advanceToRunning(Mockito.any())).thenAnswer(invocation -> {
            FlinkApplication app = invocation.getArgument(0);
            if (app.getFlinkJobId() == null) {
                app.setFlinkJobId("job-1"); // job just submitted; not yet RUNNING
            }
            return false;
        });

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        KinesisAnalyticsV2Service realMode =
                new KinesisAnalyticsV2Service(storageFactory, config, regionResolver, manager);
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null, "bucket", "app.jar", null, 1);
        FlinkApplication app = realMode.describeApplication("demo");
        app.setApplicationStatus(ApplicationStatus.STARTING);
        Mockito.clearInvocations(store); // ignore createApplication's own put()

        try {
            // @PostConstruct isn't wired by a plain `new` in this unit test; start the poller manually.
            realMode.init();
            // The poller's first tick fires ~1s after scheduling; give it margin.
            Thread.sleep(1500);

            Mockito.verify(store, Mockito.atLeastOnce())
                    .putForAccount(Mockito.eq("000000000000"), Mockito.eq("demo"), Mockito.any());
            assertEquals("job-1", realMode.describeApplication("demo").getFlinkJobId());
        } finally {
            realMode.shutdown();
        }
    }

    private KinesisAnalyticsV2Service mockModeService() {
        return buildService(true, Mockito.mock(FlinkContainerManager.class));
    }

    private KinesisAnalyticsV2Service realModeServiceWithFailingManager() {
        FlinkContainerManager failing = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failing).startCluster(Mockito.any());
        return buildService(false, failing);
    }

    private KinesisAnalyticsV2Service buildService(boolean mock, FlinkContainerManager manager) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(mock);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        return new KinesisAnalyticsV2Service(storageFactory, config, regionResolver, manager);
    }
}
