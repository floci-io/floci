package io.floci.aws.services.kinesisanalytics;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kinesisanalytics.container.FlinkContainerManager;
import io.floci.aws.services.kinesisanalytics.model.ApplicationStatus;
import io.floci.aws.services.kinesisanalytics.model.FlinkApplication;
import io.floci.aws.services.kinesisanalytics.model.Snapshot;
import io.floci.aws.services.kinesisanalytics.model.SnapshotStatus;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
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
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.KinesisAnalyticsServiceConfig kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
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
    void sameApplicationNameCanExistInDifferentRegions() {
        FlinkApplication east = create("regional");
        FlinkApplication west = service.createApplication("regional", "FLINK-1_18", ROLE,
                null, null, null, null, null, 1, null, null, null, "us-west-2");

        assertEquals("us-east-1", AwsArnUtils.parse(east.getApplicationArn()).region());
        assertEquals("us-west-2", AwsArnUtils.parse(west.getApplicationArn()).region());
        assertEquals(1, service.listApplications("us-east-1").size());
        assertEquals(1, service.listApplications("us-west-2").size());
        assertEquals("us-east-1", AwsArnUtils
                .parse(service.describeApplication("regional", "us-east-1").getApplicationArn()).region());
        assertEquals("us-west-2", AwsArnUtils
                .parse(service.describeApplication("regional", "us-west-2").getApplicationArn()).region());
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
                () -> service.deleteApplication("demo", Instant.ofEpochSecond(1)));
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
    void tagResourceMigratesLegacyApplicationToRegionalKey() {
        AccountAwareStorageBackend<FlinkApplication> store =
                AccountAwareStorageBackend.inMemory("000000000000");
        KinesisAnalyticsV2Service legacyState = mockModeService(store);
        FlinkApplication legacyApp = legacyApplication("legacy");
        store.putForAccount("000000000000", "legacy", legacyApp);

        Map<String, String> tags = legacyState.tagResource(
                legacyApp.getApplicationArn(), Map.of("team", "platform"));

        assertEquals("platform", tags.get("team"));
        assertTrue(store.getForAccount("000000000000", "legacy").isEmpty());
        assertEquals("platform", store.getForAccount("000000000000", "us-east-1/legacy")
                .orElseThrow().getTags().get("team"));
        assertEquals("platform", legacyState.listTagsForResource(legacyApp.getApplicationArn()).get("team"));
    }

    @Test
    void tagResourceRejectsArnOwnedByAnotherAccount() {
        FlinkApplication local = create("shared-name");
        String foreignArn = AwsArnUtils.Arn.of("kinesisanalytics", "us-east-1", "111122223333",
                "application/shared-name").toString();

        assertThrows(AwsException.class,
                () -> service.tagResource(foreignArn, Map.of("team", "foreign")));

        assertTrue(service.listTagsForResource(local.getApplicationArn()).isEmpty());
    }

    @Test
    void tagResourceDoesNotMigrateLegacyApplicationIntoAnotherRegion() {
        AccountAwareStorageBackend<FlinkApplication> store =
                AccountAwareStorageBackend.inMemory("000000000000");
        KinesisAnalyticsV2Service legacyState = mockModeService(store);
        store.putForAccount("000000000000", "legacy", legacyApplication("legacy"));
        String westArn = AwsArnUtils.Arn.of("kinesisanalytics", "us-west-2", "000000000000",
                "application/legacy").toString();

        assertThrows(AwsException.class,
                () -> legacyState.tagResource(westArn, Map.of("team", "platform")));

        assertTrue(store.getForAccount("000000000000", "legacy").isPresent());
        assertTrue(store.getForAccount("000000000000", "us-west-2/legacy").isEmpty());
    }

    @Test
    void listApplicationsMigratesLegacyApplicationToRegionalKey() {
        AccountAwareStorageBackend<FlinkApplication> store =
                AccountAwareStorageBackend.inMemory("000000000000");
        KinesisAnalyticsV2Service legacyState = mockModeService(store);
        store.putForAccount("000000000000", "legacy", legacyApplication("legacy"));

        List<FlinkApplication> applications = legacyState.listApplications("us-east-1");

        assertEquals(List.of("legacy"), applications.stream()
                .map(FlinkApplication::getApplicationName)
                .toList());
        assertTrue(store.getForAccount("000000000000", "legacy").isEmpty());
        assertTrue(store.getForAccount("000000000000", "us-east-1/legacy").isPresent());
    }

    @Test
    void untagResourceRemovesKeys() {
        FlinkApplication app = create("demo");
        service.tagResource(app.getApplicationArn(), Map.of("team", "platform", "env", "dev"));

        Map<String, String> after = service.untagResource(app.getApplicationArn(), List.of("team"));
        assertFalse(after.containsKey("team"));
        assertEquals("dev", after.get("env"));
    }

    /**
     * IAM's lookup reads the tags the application the ARN names holds, as the tag calls leave them, and nothing for
     * another account's, another Region's or an unknown application; a tag call puts a new map, so one a reader
     * already holds stays as it was.
     */
    @Test
    void storedTagsAreTheTagsOfTheApplicationTheArnNamesAndTaggingPutsANewMap() {
        FlinkApplication app = create("demo");
        String arn = app.getApplicationArn();
        service.tagResource(arn, Map.of("team", "data", "env", "dev"));
        Map<String, String> held = app.getTags();

        service.untagResource(arn, List.of("env"));

        assertEquals(Map.of("team", "data", "env", "dev"), held);
        assertEquals("kinesisanalytics", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertTrue(service.storedTags(arn.replace("000000000000", "111122223333")).isEmpty());
        assertTrue(service.storedTags(arn.replace("us-east-1", "us-west-2")).isEmpty());
        assertTrue(service.storedTags(arn.replace("application/demo", "application/nope")).isEmpty());
        assertTrue(service.storedTags("arn:aws:kinesis:us-east-1:000000000000:stream/demo").isEmpty());
    }

    /**
     * An application stored under its name alone, before applications were kept by Region, or before storage was
     * account-aware, is read where the handlers find it, in place: the lookup migrates nothing.
     */
    @Test
    void storedTagsReadALegacyRecordInPlaceWithoutMigratingIt() {
        InMemoryStorage<String, FlinkApplication> raw = new InMemoryStorage<>();
        KinesisAnalyticsV2Service legacyState =
                mockModeService(new AccountAwareStorageBackend<>(raw, null, "000000000000"));
        FlinkApplication byName = legacyApplication("by-name");
        byName.setTags(Map.of("team", "data"));
        raw.put("000000000000/by-name", byName);
        FlinkApplication bare = legacyApplication("bare");
        bare.setTags(Map.of("team", "ops"));
        raw.put("bare", bare);
        FlinkApplication unscoped = legacyApplication("unscoped");
        unscoped.setTags(Map.of("team", "web"));
        raw.put("us-east-1/unscoped", unscoped);

        assertEquals(Map.of("team", "data"), legacyState.storedTags(byName.getApplicationArn()));
        assertEquals(Map.of("team", "ops"), legacyState.storedTags(bare.getApplicationArn()));
        assertEquals(Map.of("team", "web"), legacyState.storedTags(unscoped.getApplicationArn()));
        assertTrue(legacyState.storedTags(byName.getApplicationArn().replace("us-east-1", "us-west-2")).isEmpty());
        assertEquals(Set.of("000000000000/by-name", "bare", "us-east-1/unscoped"), raw.keys());
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
                "demo", "snap1", Instant.ofEpochSecond(1)));
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
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.KinesisAnalyticsServiceConfig kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
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
                    .putForAccount(Mockito.eq("000000000000"), Mockito.eq("us-east-1/demo"), Mockito.any());
            assertEquals("job-1", realMode.describeApplication("demo").getFlinkJobId());
        } finally {
            realMode.shutdown();
        }
    }

    /**
     * #5664: shutdown removes every application's Flink cluster. After a restart an application that was running
     * or starting goes back to STARTING and gets a new cluster, its old job cleared, in every account; the poller
     * leaves it alone while its restore runs, and a READY application stays as it is.
     */
    @Test
    void restoringStartsARunningOrStartingApplicationAgain() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service[] holder = new KinesisAnalyticsV2Service[1];
        Mockito.doAnswer(start -> {
            FlinkApplication app = start.getArgument(0);
            assertEquals(ApplicationStatus.STARTING, app.getApplicationStatus());
            assertNull(app.getFlinkJobId(), "the old job ran on the removed cluster");
            holder[0].pollReadiness();
            // The poller must not drive an application whose restore is running toward its old cluster.
            Mockito.verify(manager, Mockito.never()).advanceToRunning(
                    Mockito.argThat(probed -> app.getApplicationName().equals(probed.getApplicationName())));
            app.setRestEndpoint("http://new-jm:8081");
            return null;
        }).when(manager).startCluster(Mockito.any());
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        holder[0] = restoring;
        FlinkApplication withoutAccount = storedApplication("running", "111111111111", "us-west-2",
                ApplicationStatus.RUNNING);
        // A record saved before applications carried their account: the account it is stored under owns it.
        withoutAccount.setAccountId(null);
        store.putForAccount("111111111111", "us-west-2/running", withoutAccount);
        store.putForAccount("000000000000", "us-east-1/starting",
                storedApplication("starting", "000000000000", "us-east-1", ApplicationStatus.STARTING));
        store.putForAccount("000000000000", "us-east-1/ready",
                storedApplication("ready", "000000000000", "us-east-1", ApplicationStatus.READY));

        restoring.restorePersistedRuntime().join();

        FlinkApplication running = store.getForAccount("111111111111", "us-west-2/running").orElseThrow();
        assertEquals(ApplicationStatus.STARTING, running.getApplicationStatus());
        assertEquals("111111111111", running.getAccountId());
        assertTrue(store.getForAccount("000000000000", "us-west-2/running").isEmpty());
        assertEquals("http://new-jm:8081", running.getRestEndpoint());
        assertEquals(ApplicationStatus.STARTING,
                store.getForAccount("000000000000", "us-east-1/starting").orElseThrow().getApplicationStatus());
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/ready").orElseThrow().getApplicationStatus());
        Mockito.verify(manager, Mockito.times(2)).startCluster(Mockito.any());
        Mockito.verify(manager, Mockito.never()).startCluster(
                Mockito.argThat(app -> "ready".equals(app.getApplicationName())));
        // Once the restore is done, the poller drives the application to RUNNING on its new cluster.
        when(manager.advanceToRunning(Mockito.argThat(app -> "running".equals(app.getApplicationName()))))
                .thenReturn(true);
        restoring.pollReadiness();
        assertEquals(ApplicationStatus.RUNNING,
                store.getForAccount("111111111111", "us-west-2/running").orElseThrow().getApplicationStatus());
    }

    /** An application whose cluster outlived Floci keeps it, and with it its running job. */
    @Test
    void anApplicationWhoseClusterSurvivedKeepsIt() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        when(manager.adoptRunningCluster(Mockito.any())).thenReturn(true);
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/kept",
                storedApplication("kept", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        restoring.restorePersistedRuntime().join();

        FlinkApplication kept = store.getForAccount("000000000000", "us-east-1/kept").orElseThrow();
        assertEquals(ApplicationStatus.RUNNING, kept.getApplicationStatus());
        assertEquals("job-1", kept.getFlinkJobId());
        Mockito.verify(manager, Mockito.never()).startCluster(Mockito.any());
    }

    /**
     * An application whose cluster cannot be started goes back to READY, from which StartApplication starts it
     * again, and leaves the others to restore; its failure fails the future.
     */
    @Test
    void anApplicationThatCannotBeRestoredGoesBackToReady() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        RuntimeException cause = new IllegalStateException("docker unavailable");
        Mockito.doThrow(cause).when(manager).startCluster(
                Mockito.argThat(app -> "first".equals(app.getApplicationName())));
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/first",
                storedApplication("first", "000000000000", "us-east-1", ApplicationStatus.RUNNING));
        store.putForAccount("000000000000", "us-east-1/second",
                storedApplication("second", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> restoring.restorePersistedRuntime().join());

        assertEquals("Could not restore Kinesis Analytics V2 application first of account 000000000000",
                thrown.getCause().getMessage());
        assertSame(cause, thrown.getCause().getCause());
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/first").orElseThrow().getApplicationStatus());
        Mockito.verify(manager).removeOwnCluster(Mockito.argThat(app -> "first".equals(app.getApplicationName())));
        // What survived Floci goes before the start, and what the failed start left under the cluster's names after
        // it, before the application is READY.
        Mockito.verify(manager, Mockito.times(2)).removeSurvivingCluster(
                Mockito.argThat(app -> "first".equals(app.getApplicationName())));
        assertEquals(ApplicationStatus.STARTING,
                store.getForAccount("000000000000", "us-east-1/second").orElseThrow().getApplicationStatus());
    }

    /**
     * An application stopped while its new cluster starts is STOPPING until the restore has removed that cluster, as
     * AWS reports a stop in progress, so StartApplication and DeleteApplication refuse it until then and no second
     * start runs under the cluster's names. It is READY after, and starts again as usual.
     */
    @Test
    void anApplicationStoppedWhileItsClusterStartsIsStoppingUntilTheClusterIsGone() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service[] restoring = new KinesisAnalyticsV2Service[1];
        AtomicBoolean restoreStart = new AtomicBoolean(true);
        Mockito.doAnswer(start -> {
            if (restoreStart.getAndSet(false)) {
                FlinkApplication app = start.getArgument(0);
                assertEquals(ApplicationStatus.STOPPING,
                        restoring[0].stopApplication("stopped", "us-east-1").getApplicationStatus());
                AwsException restart = assertThrows(AwsException.class,
                        () -> restoring[0].startApplication("stopped", "us-east-1"));
                assertEquals("ResourceInUseException", restart.getErrorCode());
                AwsException delete = assertThrows(AwsException.class,
                        () -> restoring[0].deleteApplication("stopped", app.getCreateTimestamp(), "us-east-1"));
                assertEquals("ResourceInUseException", delete.getErrorCode());
            }
            return null;
        }).when(manager).startCluster(Mockito.any());
        restoring[0] = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/stopped",
                storedApplication("stopped", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        restoring[0].restorePersistedRuntime().join();

        // The restore removed the cluster it started, by its containers; the stop itself left the cluster alone.
        Mockito.verify(manager).removeOwnCluster(Mockito.argThat(app -> "stopped".equals(app.getApplicationName())));
        Mockito.verify(manager, Mockito.never()).stopCluster(Mockito.any());
        Mockito.clearInvocations(manager);
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/stopped").orElseThrow().getApplicationStatus());
        restoring[0].startApplication("stopped", "us-east-1");
        Mockito.verify(manager).startCluster(Mockito.any());
        // Its restore is over: a stop now stops the cluster itself and leaves the application READY at once.
        assertEquals(ApplicationStatus.READY,
                restoring[0].stopApplication("stopped", "us-east-1").getApplicationStatus());
        Mockito.verify(manager).stopCluster(Mockito.argThat(app -> "stopped".equals(app.getApplicationName())));
    }

    /** Once its restore is through, an application stops as any other: its cluster goes, and it is READY at once. */
    @Test
    void anApplicationStopsAsUsualOnceItsRestoreIsThrough() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/restored",
                storedApplication("restored", "000000000000", "us-east-1", ApplicationStatus.RUNNING));
        restoring.restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.READY,
                restoring.stopApplication("restored", "us-east-1").getApplicationStatus());

        Mockito.verify(manager).stopCluster(Mockito.argThat(app -> "restored".equals(app.getApplicationName())));
    }

    /** An application deleted and created again, and started, while its old cluster starts keeps what it has. */
    @Test
    void anApplicationCreatedAgainWhileItsClusterStartsIsNotOverwritten() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        FlinkApplication again = storedApplication("again", "000000000000", "us-east-1", ApplicationStatus.STARTING);
        Mockito.doAnswer(start -> {
            FlinkApplication app = start.getArgument(0);
            again.setCreateTimestamp(app.getCreateTimestamp().plusSeconds(60));
            again.setRestEndpoint("http://new-jm:8081");
            store.putForAccount("000000000000", "us-east-1/again", again);
            return null;
        }).when(manager).startCluster(Mockito.any());
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/again",
                storedApplication("again", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        restoring.restorePersistedRuntime().join();

        FlinkApplication stored = store.getForAccount("000000000000", "us-east-1/again").orElseThrow();
        assertSame(again, stored);
        assertEquals("http://new-jm:8081", stored.getRestEndpoint());
        // Only the restore's own cluster goes, not what the manager tracks for the application created again.
        Mockito.verify(manager).removeOwnCluster(Mockito.argThat(app -> app != again));
        Mockito.verify(manager, Mockito.never()).stopCluster(Mockito.any());
    }

    /**
     * An application stopped while the restore starts another one is not started: a cluster that outlived Floci goes,
     * and it is READY once its turn comes.
     */
    @Test
    void anApplicationStoppedWhileItWaitsIsNotStarted() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service[] restoring = new KinesisAnalyticsV2Service[1];
        List<String> stopped = new ArrayList<>();
        Mockito.doAnswer(start -> {
            FlinkApplication app = start.getArgument(0);
            String waiting = "first".equals(app.getApplicationName()) ? "second" : "first";
            assertEquals(ApplicationStatus.STOPPING,
                    restoring[0].stopApplication(waiting, "us-east-1").getApplicationStatus());
            stopped.add(waiting);
            return null;
        }).when(manager).startCluster(Mockito.any());
        restoring[0] = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/first",
                storedApplication("first", "000000000000", "us-east-1", ApplicationStatus.RUNNING));
        store.putForAccount("000000000000", "us-east-1/second",
                storedApplication("second", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        restoring[0].restorePersistedRuntime().join();

        Mockito.verify(manager, Mockito.times(1)).startCluster(Mockito.any());
        String waiting = stopped.getFirst();
        Mockito.verify(manager).removeSurvivingCluster(
                Mockito.argThat(app -> waiting.equals(app.getApplicationName())));
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/" + waiting).orElseThrow().getApplicationStatus());
        // Its restore is over: started again and stopped, it is READY at once.
        Mockito.doNothing().when(manager).startCluster(Mockito.any());
        restoring[0].startApplication(waiting, "us-east-1");
        assertEquals(ApplicationStatus.READY,
                restoring[0].stopApplication(waiting, "us-east-1").getApplicationStatus());
    }

    /**
     * An application stopped during a restore that Floci did not live to finish is not started at the next start:
     * what survives of its cluster is removed, and it is READY then.
     */
    @Test
    void aStopFlociDidNotLiveToFinishStandsAtTheNextStart() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        store.putForAccount("000000000000", "us-east-1/stopping",
                storedApplication("stopping", "000000000000", "us-east-1", ApplicationStatus.STOPPING));

        buildService(false, manager, store).restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/stopping").orElseThrow().getApplicationStatus());
        Mockito.verify(manager).removeSurvivingCluster(
                Mockito.argThat(app -> "stopping".equals(app.getApplicationName())));
        Mockito.verify(manager, Mockito.never()).startCluster(Mockito.any());
    }

    /** A surviving cluster the restore does not keep is removed, under either name form, before a new one starts. */
    @Test
    void aClusterNotKeptIsRemovedBeforeANewOneStarts() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        store.putForAccount("000000000000", "us-east-1/replaced",
                storedApplication("replaced", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        buildService(false, manager, store).restorePersistedRuntime().join();

        InOrder order = Mockito.inOrder(manager);
        order.verify(manager).adoptRunningCluster(Mockito.any());
        order.verify(manager).removeSurvivingCluster(
                Mockito.argThat(app -> "replaced".equals(app.getApplicationName())));
        order.verify(manager).startCluster(Mockito.argThat(app -> "replaced".equals(app.getApplicationName())));
    }

    /**
     * A shutdown overtaking the start of an application stopped meanwhile leaves it STOPPING, and the next start
     * removes what survives of its cluster and leaves it READY: the stop stands.
     */
    @Test
    void aShutdownDuringTheStartOfAStoppedApplicationLeavesTheStopToTheNextStart() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service[] restoring = new KinesisAnalyticsV2Service[1];
        Mockito.doAnswer(start -> {
            restoring[0].stopApplication("overtaken", "us-east-1");
            throw new FlinkContainerManager.ShuttingDownException("overtaken");
        }).when(manager).startCluster(Mockito.any());
        restoring[0] = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/overtaken",
                storedApplication("overtaken", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        restoring[0].restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.STOPPING,
                store.getForAccount("000000000000", "us-east-1/overtaken").orElseThrow().getApplicationStatus());
        FlinkContainerManager next = Mockito.mock(FlinkContainerManager.class);
        buildService(false, next, store).restorePersistedRuntime().join();
        Mockito.verify(next).removeSurvivingCluster(Mockito.any());
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/overtaken").orElseThrow().getApplicationStatus());
    }

    /**
     * An application stopped during its restore whose cluster Docker will not remove stays STOPPING, naming it, and
     * so does it at a start where Docker still keeps it; the start where Docker removes it leaves it READY.
     */
    @Test
    void aStoppedApplicationWhoseClusterDockerKeepsStaysStopping() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service[] restoring = new KinesisAnalyticsV2Service[1];
        Mockito.doAnswer(start -> {
            restoring[0].stopApplication("kept", "us-east-1");
            return null;
        }).when(manager).startCluster(Mockito.any());
        IllegalStateException refused = new IllegalStateException("Failed to remove stale container jm");
        Mockito.doThrow(refused).when(manager).removeOwnCluster(Mockito.any());
        restoring[0] = buildService(false, manager, store);
        store.putForAccount("000000000000", "us-east-1/kept",
                storedApplication("kept", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> restoring[0].restorePersistedRuntime().join());

        assertSame(refused, thrown.getCause().getCause());
        assertEquals(ApplicationStatus.STOPPING,
                store.getForAccount("000000000000", "us-east-1/kept").orElseThrow().getApplicationStatus());
        FlinkContainerManager stillKept = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(refused).when(stillKept).removeSurvivingCluster(Mockito.any());
        assertThrows(CompletionException.class,
                () -> buildService(false, stillKept, store).restorePersistedRuntime().join());
        assertEquals(ApplicationStatus.STOPPING,
                store.getForAccount("000000000000", "us-east-1/kept").orElseThrow().getApplicationStatus());
        buildService(false, Mockito.mock(FlinkContainerManager.class), store).restorePersistedRuntime().join();
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/kept").orElseThrow().getApplicationStatus());
    }

    /**
     * An application whose cluster cannot start, and which Docker will not then remove, is STOPPING rather than
     * READY: the start's failure is thrown, the refused removal with it.
     */
    @Test
    void aFailedStartWhoseClusterDockerKeepsLeavesItStopping() {
        assertAFailedStartLeavesItStopping((manager, refused) ->
                Mockito.doThrow(refused).when(manager).removeOwnCluster(Mockito.any()));
        // A container the failed start left under the cluster's names, and Docker keeps, counts the same; the
        // removal before the start went through.
        assertAFailedStartLeavesItStopping((manager, refused) ->
                Mockito.doNothing().doThrow(refused).when(manager).removeSurvivingCluster(Mockito.any()));
    }

    private void assertAFailedStartLeavesItStopping(
            BiConsumer<FlinkContainerManager, RuntimeException> refusedRemoval) {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        IllegalStateException cause = new IllegalStateException("Docker unavailable");
        IllegalStateException refused = new IllegalStateException("Failed to remove stale container jm");
        Mockito.doThrow(cause).when(manager).startCluster(Mockito.any());
        refusedRemoval.accept(manager, refused);
        store.putForAccount("000000000000", "us-east-1/failed",
                storedApplication("failed", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> buildService(false, manager, store).restorePersistedRuntime().join());

        assertSame(cause, thrown.getCause().getCause());
        assertSame(refused, cause.getSuppressed()[0]);
        assertEquals(ApplicationStatus.STOPPING,
                store.getForAccount("000000000000", "us-east-1/failed").orElseThrow().getApplicationStatus());
    }

    /** A surviving cluster that cannot be looked for fails the restore like a cluster that cannot start. */
    @Test
    void aClusterThatCannotBeLookedForFailsTheRestore() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        IllegalStateException cause = new IllegalStateException("Docker unavailable");
        when(manager.adoptRunningCluster(Mockito.any())).thenThrow(cause);
        store.putForAccount("000000000000", "us-east-1/unknown",
                storedApplication("unknown", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> buildService(false, manager, store).restorePersistedRuntime().join());

        assertSame(cause, thrown.getCause().getCause());
        assertEquals(ApplicationStatus.READY,
                store.getForAccount("000000000000", "us-east-1/unknown").orElseThrow().getApplicationStatus());
        Mockito.verify(manager, Mockito.never()).startCluster(Mockito.any());
    }

    /**
     * Records stored under the keys of older builds move onto their current keys, so each application is restored
     * once and no second record is left for the next start to restore again.
     */
    @Test
    void aRecordUnderAnOlderKeyIsRestoredOnce() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        KinesisAnalyticsV2Service restoring = buildService(false, manager, store);
        store.putForAccount("000000000000", "legacy",
                storedApplication("legacy", "000000000000", "us-east-1", ApplicationStatus.RUNNING));
        FlinkApplication both = storedApplication("both", "000000000000", "us-east-1", ApplicationStatus.RUNNING);
        store.putForAccount("000000000000", "both", both);
        store.putForAccount("000000000000", "us-east-1/both", both);

        restoring.restorePersistedRuntime().join();

        Mockito.verify(manager, Mockito.times(2)).startCluster(Mockito.any());
        assertEquals(List.of("us-east-1/both", "us-east-1/legacy"), store.scanAllAccountEntries(key -> true).stream()
                .map(entry -> entry.key()).sorted().toList());
        assertEquals(ApplicationStatus.STARTING,
                store.getForAccount("000000000000", "us-east-1/legacy").orElseThrow().getApplicationStatus());
    }

    /**
     * A shutdown during the restore stops it: applications it has not started yet, and one whose start shutdown
     * overtook, stay STARTING for the next start, rather than going back to READY.
     */
    @Test
    void aShutdownDuringTheRestoreLeavesItsApplicationsStarting() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager stopped = Mockito.mock(FlinkContainerManager.class);
        when(stopped.isShuttingDown()).thenReturn(true);
        store.putForAccount("000000000000", "us-east-1/waiting",
                storedApplication("waiting", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        buildService(false, stopped, store).restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.STARTING,
                store.getForAccount("000000000000", "us-east-1/waiting").orElseThrow().getApplicationStatus());
        Mockito.verify(stopped, Mockito.never()).startCluster(Mockito.any());

        FlinkContainerManager overtaken = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new FlinkContainerManager.ShuttingDownException("waiting"))
                .when(overtaken).startCluster(Mockito.any());

        KinesisAnalyticsV2Service cutShort = buildService(false, overtaken, store);
        cutShort.restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.STARTING,
                store.getForAccount("000000000000", "us-east-1/waiting").orElseThrow().getApplicationStatus());
        Mockito.verify(overtaken, Mockito.never()).stopCluster(Mockito.any());
        Mockito.verify(overtaken, Mockito.never()).removeOwnCluster(Mockito.any());
        // Its restore is over, cut short or not: the poller drives the application as any other.
        cutShort.pollReadiness();
        Mockito.verify(overtaken).advanceToRunning(Mockito.argThat(app -> "waiting".equals(app.getApplicationName())));
    }

    @Test
    void restoringLeavesMockApplicationsAsTheyAre() {
        AccountAwareStorageBackend<FlinkApplication> store = AccountAwareStorageBackend.inMemory("000000000000");
        FlinkContainerManager manager = Mockito.mock(FlinkContainerManager.class);
        store.putForAccount("000000000000", "us-east-1/mock",
                storedApplication("mock", "000000000000", "us-east-1", ApplicationStatus.RUNNING));

        buildService(true, manager, store).restorePersistedRuntime().join();

        assertEquals(ApplicationStatus.RUNNING,
                store.getForAccount("000000000000", "us-east-1/mock").orElseThrow().getApplicationStatus());
        Mockito.verifyNoInteractions(manager);
    }

    /** An application stored in {@code account} and {@code region} whose record names the cluster it ran on. */
    private FlinkApplication storedApplication(String name, String account, String region, ApplicationStatus status) {
        FlinkApplication app = new FlinkApplication(name,
                "arn:aws:kinesisanalytics:" + region + ":" + account + ":application/" + name,
                "FLINK-1_18", ROLE, "STREAMING");
        app.setAccountId(account);
        app.setApplicationStatus(status);
        app.setRestEndpoint("http://old-jm:8081");
        app.setFlinkJobId("job-1");
        return app;
    }

    private KinesisAnalyticsV2Service mockModeService() {
        return buildService(true, Mockito.mock(FlinkContainerManager.class));
    }

    private KinesisAnalyticsV2Service mockModeService(AccountAwareStorageBackend<FlinkApplication> store) {
        return buildService(true, Mockito.mock(FlinkContainerManager.class), store);
    }

    private FlinkApplication legacyApplication(String name) {
        FlinkApplication application = new FlinkApplication(name,
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/" + name,
                "FLINK-1_18", ROLE, "STREAMING");
        application.setAccountId("000000000000");
        return application;
    }

    private KinesisAnalyticsV2Service realModeServiceWithFailingManager() {
        FlinkContainerManager failing = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failing).startCluster(Mockito.any());
        return buildService(false, failing);
    }

    private KinesisAnalyticsV2Service buildService(boolean mock, FlinkContainerManager manager) {
        return buildService(mock, manager, AccountAwareStorageBackend.inMemory("000000000000"));
    }

    private KinesisAnalyticsV2Service buildService(boolean mock, FlinkContainerManager manager,
                                                   AccountAwareStorageBackend<FlinkApplication> store) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.doReturn(store).when(storageFactory)
                .create(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.KinesisAnalyticsServiceConfig kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(mock);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        return new KinesisAnalyticsV2Service(storageFactory, config, regionResolver, manager);
    }
}
