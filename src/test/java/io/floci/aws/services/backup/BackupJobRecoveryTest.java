package io.floci.aws.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.backup.model.BackupJob;
import io.floci.aws.services.backup.model.BackupVault;
import io.floci.aws.services.backup.model.RecoveryPoint;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.model.MessageAttributeValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupJobRecoveryTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private static final String VAULT = "review";
    private static final String TOPIC = "arn:aws:sns:eu-west-1:" + ACCOUNT + ":backup";

    @Test
    void restartResumesEachNonTerminalStateAndDoesNotDuplicateRecoveryPoints() {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(Instant.now().getEpochSecond() - 60);
            BackupJob original = fixture.service.startBackupJob(VAULT, "arn:aws:s3:::bucket", "role", null, REGION);
            fixture.service.shutdown();
            long now = Instant.now().getEpochSecond();
            original.setExpectedCompletionDate(now - 1);
            fixture.jobs.put(original.getBackupJobId(), original);
            for (String state : List.of("RUNNING", "ABORTING", "COMPLETED", "FAILED", "ABORTED")) {
                BackupJob job = fixture.legacy(state, now - 10);
                job.setBackupJobId(state);
                fixture.jobs.put(state, job);
            }
            fixture.jobs.delete("legacy");
            BackupJob expired = fixture.legacy("CREATED", now - 10);
            expired.setBackupJobId("expired");
            expired.setStartBy(now - 1);
            fixture.jobs.put("expired", expired);
            BackupJob missing = fixture.legacy("RUNNING", now - 10);
            missing.setBackupJobId("missing-vault");
            missing.setBackupVaultName("deleted");
            fixture.jobs.put("missing-vault", missing);
            BackupJob future = fixture.legacy("RUNNING", now - 10);
            future.setBackupJobId("future");
            future.setExpectedCompletionDate(now + 3600);
            fixture.jobs.put("future", future);
            fixture.jobs.delete("legacy");
            RecoveryPoint existing = new RecoveryPoint();
            existing.setBackupVaultName(VAULT);
            existing.setBackupVaultArn(fixture.vault().getBackupVaultArn());
            existing.setRecoveryPointArn("arn:aws:backup:" + REGION + ":" + ACCOUNT + ":recovery-point:RUNNING");
            existing.setCreationDate(now - 20);
            fixture.points.put(existing.getRecoveryPointArn(), existing);
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            assertEquals("RUNNING", fixture.service.describeBackupJob("future").getState());
            assertEquals(now + 3600, fixture.service.describeBackupJob("future").getExpectedCompletionDate());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("COMPLETED", fixture.service.describeBackupJob(original.getBackupJobId()).getState());
                assertEquals("COMPLETED", fixture.service.describeBackupJob("RUNNING").getState());
                assertEquals("ABORTED", fixture.service.describeBackupJob("ABORTING").getState());
                assertEquals("EXPIRED", fixture.service.describeBackupJob("expired").getState());
                assertEquals("FAILED", fixture.service.describeBackupJob("missing-vault").getState());
            });
            fixture.service.resumeJobs();
            assertEquals(2, fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).size());
            assertEquals(2, fixture.vault().getNumberOfRecoveryPoints());
            assertEquals(now - 20, fixture.points.get(existing.getRecoveryPointArn()).orElseThrow().getCreationDate());
            assertTrue(fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).stream()
                    .allMatch(point -> point.getRecoveryPointArn().contains(":" + REGION + ":" + ACCOUNT + ":")));
            for (String state : List.of("COMPLETED", "FAILED", "ABORTED")) {
                assertEquals(state, fixture.service.describeBackupJob(state).getState());
                assertNull(fixture.service.describeBackupJob(state).getRecoveryPointArn());
            }
        }
    }

    @Test
    void deletionWaitsForCompletionMonitorAndLeavesCountConsistent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob first = fixture.startRunning();
            fixture.complete(first);
            BackupJob second = fixture.startRunning();
            Object monitor = fixture.vaultMonitor();
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<Void> deletion = new FutureTask<>(() -> {
                entered.countDown();
                fixture.service.deleteRecoveryPoint(VAULT, first.getRecoveryPointArn(), REGION);
                return null;
            });
            Thread worker = new Thread(deletion, "backup-review-delete");
            boolean waited;
            try {
                synchronized (monitor) {
                    worker.start();
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    await().atMost(Duration.ofSeconds(5)).until(() ->
                            worker.getState() == Thread.State.BLOCKED || deletion.isDone());
                    waited = worker.getState() == Thread.State.BLOCKED && !deletion.isDone();
                    fixture.complete(second);
                }
                deletion.get(5, TimeUnit.SECONDS);
                assertAll(
                        () -> assertTrue(waited, "Point deletion must wait on completion's vault monitor"),
                        () -> assertEquals(1, fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).size()),
                        () -> assertEquals(1, fixture.vault().getNumberOfRecoveryPoints()),
                        () -> assertThrows(AwsException.class,
                                () -> fixture.service.deleteBackupVault(VAULT, REGION)));
                fixture.service.deleteRecoveryPoint(VAULT, second.getRecoveryPointArn(), REGION);
                assertTrue(fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).isEmpty());
                assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
                fixture.service.deleteBackupVault(VAULT, REGION);
                assertThrows(AwsException.class, fixture::vault);
            } finally {
                worker.join(5000);
                assertFalse(worker.isAlive(), "Deletion worker must be cleaned up");
            }
        }
    }

    @Test
    void retentionReadPointDeleteAndCountReadWriteAllHoldCompletionMonitor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob job = fixture.startRunning();
            fixture.complete(job);
            Object monitor = fixture.vaultMonitor();
            RecoveryPoint point = fixture.points.get(job.getRecoveryPointArn()).orElseThrow();
            fixture.service.putBackupVaultLockConfiguration(VAULT, REGION, 1L, null, null);
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Retention and count reads must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.vaults).get(REGION + ":" + VAULT);
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Point lookup for retention must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.points).get(job.getRecoveryPointArn());
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Point deletion must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.points).delete(job.getRecoveryPointArn());
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Count update must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.vaults).put(eq(REGION + ":" + VAULT), any());
            AwsException protectedPoint = assertThrows(AwsException.class,
                    () -> fixture.service.deleteRecoveryPoint(VAULT, job.getRecoveryPointArn(), REGION));
            assertEquals("InvalidRequestException", protectedPoint.getErrorCode());
            verify(fixture.points, never()).delete(anyString());
            point.setCreationDate(Instant.now().minus(Duration.ofDays(2)).getEpochSecond());
            fixture.service.deleteRecoveryPoint(VAULT, job.getRecoveryPointArn(), REGION);
            verify(fixture.points).delete(job.getRecoveryPointArn());
            doCallRealMethod().when(fixture.vaults).get(anyString());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
            fixture.service.deleteBackupVault(VAULT, REGION);
        }
    }

    @Test
    void brandNewPointIncrementsCountWithoutScanningRecoveryStore() throws Exception {
        try (Fixture fixture = new Fixture()) {
            doAnswer(call -> {
                BackupJob written = call.getArgument(1);
                if ("COMPLETED".equals(written.getState())) {
                    assertTrue(fixture.points.get(written.getRecoveryPointArn()).isPresent(),
                            "The point must be persisted before the terminal job write");
                    assertTrue(Thread.holdsLock(fixture.vaultMonitor()));
                }
                return call.callRealMethod();
            }).when(fixture.jobs).put(anyString(), any());
            BackupJob first = fixture.startRunning();
            fixture.complete(first);
            BackupJob second = fixture.startRunning();
            fixture.complete(second);
            verify(fixture.points, never()).scan(any());
            assertEquals(2, fixture.vault().getNumberOfRecoveryPoints());
            assertEquals(2, fixture.points.keys().size());
            fixture.complete(first);
            assertEquals(2, fixture.vault().getNumberOfRecoveryPoints());
            verify(fixture.points, never()).scan(any());
        }
    }

    @Test
    void stablePointRetryRecountsAndRestartDoesNotDuplicateIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob job = fixture.startRunning();
            fixture.complete(job);
            String pointArn = job.getRecoveryPointArn();
            RecoveryPoint original = fixture.points.get(pointArn).orElseThrow();
            original.setCreationDate(42L);
            fixture.vault().setNumberOfRecoveryPoints(9);
            job.setState("RUNNING");
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            fixture.jobs.put(job.getBackupJobId(), job);
            fixture.service.shutdown();
            clearInvocations(fixture.points);
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("COMPLETED", fixture.service.describeBackupJob(job.getBackupJobId()).getState()));
            verify(fixture.points, atLeastOnce()).scan(any());
            assertEquals(1, fixture.points.keys().size());
            assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
            assertEquals(42L, fixture.points.get(pointArn).orElseThrow().getCreationDate());
            assertEquals(pointArn, fixture.service.describeBackupJob(job.getBackupJobId()).getRecoveryPointArn());
            fixture.service.resumeJobs();
            fixture.complete(job);
            assertEquals(1, fixture.points.keys().size());
            assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    @Test
    void newJobPersistsVaultIncarnationBeforeScheduling() {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(100L);
            doAnswer(call -> {
                BackupJob written = call.getArgument(1);
                assertEquals(fixture.vault().getVaultId(), written.getVaultId());
                return call.callRealMethod();
            }).when(fixture.jobs).put(anyString(), any());
            BackupJob job = fixture.startRunning();
            JsonNode stored = fixture.mapper.valueToTree(fixture.jobs.get(job.getBackupJobId()).orElseThrow());
            assertEquals(fixture.vault().getVaultId(), stored.path("FlociVaultId").asText(),
                    "New jobs must persist the captured incarnation");
        }
    }

    @Test
    void capturedIncarnationRejectsNewerSameArnReplacement() throws Exception {
        assertReplacementRejected(200L);
    }

    @Test
    void capturedIncarnationRequiresEqualityEvenForOlderSameArnReplacement() throws Exception {
        assertReplacementRejected(99L);
    }

    private void assertReplacementRejected(long replacementDate) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(100L);
            BackupJob job = fixture.startRunning();
            String originalArn = fixture.vault().getBackupVaultArn();
            fixture.service.deleteBackupVault(VAULT, REGION);
            BackupVault replacement = fixture.service.createBackupVault(VAULT, null, null, Map.of(), REGION);
            replacement.setCreationDate(replacementDate);
            assertEquals(originalArn, replacement.getBackupVaultArn());
            assertNotEquals(job.getVaultId(), replacement.getVaultId());
            fixture.complete(job);
            assertEquals("FAILED", fixture.service.describeBackupJob(job.getBackupJobId()).getState());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
            fixture.service.deleteBackupVault(VAULT, REGION);
        }
    }

    @Test
    void legacyAbsentIdentityAllowsOlderAndSameSecondVaults() throws Exception {
        for (long vaultDate : new long[]{99L, 100L}) {
            try (Fixture fixture = new Fixture()) {
                fixture.vault().setCreationDate(vaultDate);
                BackupJob legacy = fixture.legacy("RUNNING", 100L);
                fixture.complete(legacy);
                assertEquals("COMPLETED", fixture.service.describeBackupJob(legacy.getBackupJobId()).getState());
                assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
                assertEquals("arn:aws:backup:" + REGION + ":" + ACCOUNT + ":recovery-point:legacy",
                        legacy.getRecoveryPointArn());
                assertEquals(ACCOUNT, legacy.getAccountId());
                assertNull(legacy.getVaultId(), "Recovery must not invent a missing captured id");
            }
        }
    }

    @Test
    void legacyAbsentIdentityRejectsVaultNewerThanJob() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(101L);
            BackupJob legacy = fixture.legacy("RUNNING", 100L);
            fixture.complete(legacy);
            assertEquals("FAILED", fixture.service.describeBackupJob(legacy.getBackupJobId()).getState());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    @Test
    void restartKeepsLegacyStartAndCompletionDeadlinesInOriginalRegion() {
        try (Fixture fixture = new Fixture()) {
            long now = Instant.now().getEpochSecond();
            fixture.vault().setCreationDate(now - 20);
            BackupJob expired = fixture.legacy("CREATED", now - 10);
            expired.setStartBy(now - 1);
            BackupJob future = fixture.legacy("RUNNING", now - 10);
            future.setBackupJobId("future");
            future.setExpectedCompletionDate(now + 3600);
            fixture.jobs.put("future", future);
            fixture.jobs.put("legacy", expired);
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("EXPIRED", fixture.service.describeBackupJob("legacy").getState()));
            assertEquals("RUNNING", fixture.service.describeBackupJob("future").getState());
            assertEquals(now + 3600, fixture.service.describeBackupJob("future").getExpectedCompletionDate());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    @Test
    void sameSecondSameNameSameArnReplacementRejectsCapturedIdWithoutPointsOrNotifications() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob job = fixture.startRunning();
            BackupVault original = fixture.vault();
            fixture.service.deleteBackupVault(VAULT, REGION);
            BackupVault replacement = fixture.service.createBackupVault(VAULT, null, null, Map.of(), REGION);
            replacement.setCreationDate(original.getCreationDate());
            fixture.service.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_COMPLETED"));
            assertEquals(original.getBackupVaultName(), replacement.getBackupVaultName());
            assertEquals(original.getBackupVaultArn(), replacement.getBackupVaultArn());
            assertEquals(original.getCreationDate(), replacement.getCreationDate());
            assertNotEquals(original.getVaultId(), replacement.getVaultId());
            fixture.complete(job);
            assertEquals("FAILED", fixture.service.describeBackupJob(job.getBackupJobId()).getState());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
            verifyNoInteractions(fixture.sns);
        }
    }

    @Test
    void jsonPersistenceRoundtripAndServiceRecreationAcceptOriginalAndRejectReplacement() throws Exception {
        for (boolean replace : List.of(false, true)) {
            try (Fixture fixture = new Fixture()) {
                BackupJob job = fixture.startRunning();
                job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
                BackupVault original = fixture.vault();
                String capturedId = original.getVaultId();
                String jobJson = fixture.mapper.writeValueAsString(job);
                String vaultJson = fixture.mapper.writeValueAsString(original);
                fixture.service.shutdown();
                fixture.jobs.put(job.getBackupJobId(), fixture.mapper.readValue(jobJson, BackupJob.class));
                fixture.vaults.put(REGION + ":" + VAULT, fixture.mapper.readValue(vaultJson, BackupVault.class));
                fixture.service = fixture.newService();
                assertEquals(capturedId, fixture.service.describeBackupJob(job.getBackupJobId()).getVaultId());
                assertEquals(capturedId, fixture.vault().getVaultId());
                if (replace) {
                    fixture.service.deleteBackupVault(VAULT, REGION);
                    fixture.service.createBackupVault(VAULT, null, null, Map.of(), REGION)
                            .setCreationDate(original.getCreationDate());
                }
                fixture.service.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_COMPLETED"));
                fixture.service.resumeJobs();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                        replace ? "FAILED" : "COMPLETED",
                        fixture.service.describeBackupJob(job.getBackupJobId()).getState()));
                assertEquals(capturedId, fixture.service.describeBackupJob(job.getBackupJobId()).getVaultId());
                assertEquals(replace ? 0 : 1, fixture.points.keys().size());
                assertEquals(replace ? 0 : 1, fixture.vault().getNumberOfRecoveryPoints());
                if (replace) {
                    verifyNoInteractions(fixture.sns);
                } else {
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            verify(fixture.sns).publish(eq(TOPIC), isNull(), isNull(), anyString(),
                                    eq("Notification from AWS Backup"), anyMap(), eq(REGION)));
                }
            }
        }
    }

    @Test
    void resumedCompletionPreservesSnsPayloadAndAttributesAndFiltersOtherEvents() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.service.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_FAILED"));
            BackupJob quiet = fixture.startRunning();
            quiet.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            fixture.service.shutdown();
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("COMPLETED", fixture.service.describeBackupJob(quiet.getBackupJobId()).getState()));
            // The terminal state is visible before notify returns; wait for the completion monitor
            // before changing the event filter for the next job.
            synchronized (fixture.vaultMonitor()) {
                verifyNoInteractions(fixture.sns);
                fixture.service.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_COMPLETED"));
            }
            BackupJob job = fixture.startRunning();
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            fixture.service.shutdown();
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, MessageAttributeValue>> attrs = ArgumentCaptor.forClass(Map.class);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(fixture.sns).publish(eq(TOPIC), isNull(), isNull(), eq(
                            "An AWS Backup job was completed successfully. Recovery point ARN: "
                                    + job.getRecoveryPointArn() + ". Resource ARN : " + job.getResourceArn()
                                    + ". BackupJob ID : " + job.getBackupJobId()),
                            eq("Notification from AWS Backup"), attrs.capture(), eq(REGION)));
            Map<String, MessageAttributeValue> expected = Map.of(
                    "EventType", new MessageAttributeValue("BACKUP_JOB", "String"),
                    "State", new MessageAttributeValue("COMPLETED", "String"),
                    "AccountId", new MessageAttributeValue(ACCOUNT, "String"),
                    "Id", new MessageAttributeValue(job.getBackupJobId(), "String"),
                    "StartTime", new MessageAttributeValue(Instant.ofEpochSecond(job.getCreationDate()).toString(), "String"));
            assertEquals(fixture.mapper.valueToTree(expected), fixture.mapper.valueToTree(attrs.getValue()));
            fixture.service.resumeJobs();
            fixture.complete(job);
            verifyNoMoreInteractions(fixture.sns);
        }
    }

    @Test
    void resumedPublishFailureDoesNotUndoCompletionOrPoint() {
        try (Fixture fixture = new Fixture()) {
            fixture.service.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_COMPLETED"));
            doThrow(new IllegalStateException("offline topic")).when(fixture.sns)
                    .publish(anyString(), isNull(), isNull(), anyString(), anyString(), anyMap(), anyString());
            BackupJob job = fixture.startRunning();
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            fixture.service.shutdown();
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(fixture.sns).publish(eq(TOPIC), isNull(), isNull(), anyString(), anyString(), anyMap(), eq(REGION));
                assertEquals("COMPLETED", fixture.service.describeBackupJob(job.getBackupJobId()).getState());
                assertEquals(1, fixture.points.keys().size());
                assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
            });
        }
    }

    @Test
    void terminalJobsCannotBeStoppedOrCompletedAgain() throws Exception {
        for (String state : List.of("COMPLETED", "ABORTED", "FAILED", "EXPIRED", "PARTIAL")) {
            try (Fixture fixture = new Fixture()) {
                BackupJob job = fixture.legacy(state, Instant.now().getEpochSecond());
                AwsException error = assertThrows(AwsException.class, () -> fixture.service.stopBackupJob("legacy"));
                assertEquals("InvalidRequestException", error.getErrorCode());
                fixture.complete(job);
                fixture.service.resumeJobs();
                assertEquals(state, fixture.service.describeBackupJob("legacy").getState());
                assertNull(job.getRecoveryPointArn());
                assertTrue(fixture.points.keys().isEmpty());
                verifyNoInteractions(fixture.sns);
            }
        }
    }

    @Test
    void persistentJsonStoresReloadCapturedIdentityBeforeResumingOriginalOrReplacement(@TempDir Path directory)
            throws Exception {
        for (boolean replace : List.of(false, true)) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            Path path = directory.resolve(Boolean.toString(replace));
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(3600);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("backup")).thenReturn("persistent");
            StorageFactory firstStorage = new StorageFactory(config, access);
            RegionResolver resolver = new RegionResolver("us-east-1", ACCOUNT);
            ObjectMapper mapper = new ObjectMapper();
            SnsService sns = mock(SnsService.class);
            BackupService first = new BackupService(firstStorage, config, resolver, mapper, sns);
            String jobId;
            String capturedId;
            long capturedDate;
            try {
                BackupVault vault = first.createBackupVault(VAULT, null, null, Map.of(), REGION);
                capturedId = vault.getVaultId();
                capturedDate = vault.getCreationDate();
                BackupJob job = first.startBackupJob(VAULT, "arn:aws:s3:::persisted", "role", null, REGION);
                jobId = job.getBackupJobId();
                job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
                AccountAwareStorageBackend<BackupJob> jobs = firstStorage.create("backup", "backup-jobs.json",
                        new TypeReference<Map<String, BackupJob>>() {});
                jobs.put(jobId, job);
            } finally {
                first.shutdown();
                firstStorage.shutdownAll();
            }
            JsonNode disk = mapper.readTree(path.resolve("backup-jobs.json").toFile()).path(ACCOUNT + "/" + jobId);
            assertEquals(capturedId, disk.path("FlociVaultId").asText());
            StorageFactory reloadedStorage = new StorageFactory(config, access);
            BackupService restarted = new BackupService(reloadedStorage, config, resolver, mapper, sns);
            try {
                assertEquals(capturedId, restarted.describeBackupJob(jobId).getVaultId());
                assertEquals(capturedId, restarted.describeBackupVault(VAULT, REGION).getVaultId());
                if (replace) {
                    restarted.deleteBackupVault(VAULT, REGION);
                    BackupVault replacement = restarted.createBackupVault(VAULT, null, null, Map.of(), REGION);
                    replacement.setCreationDate(capturedDate);
                    assertNotEquals(capturedId, replacement.getVaultId());
                }
                restarted.putBackupVaultNotifications(VAULT, REGION, TOPIC, List.of("BACKUP_JOB_COMPLETED"));
                restarted.resumeJobs();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(
                        replace ? "FAILED" : "COMPLETED", restarted.describeBackupJob(jobId).getState()));
                assertEquals(replace ? 0 : 1, restarted.listRecoveryPointsByBackupVault(VAULT, REGION).size());
                assertEquals(replace ? 0 : 1, restarted.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
                assertEquals(capturedId, restarted.describeBackupJob(jobId).getVaultId());
                if (replace) {
                    verifyNoInteractions(sns);
                } else {
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            verify(sns).publish(eq(TOPIC), isNull(), isNull(), anyString(), anyString(), anyMap(), eq(REGION)));
                }
            } finally {
                restarted.shutdown();
                reloadedStorage.shutdownAll();
            }
        }
    }

    @Test
    void capturedIdStillRequiresOriginalArnAfterJsonRoundtrip() throws Exception {
        for (boolean matchingArn : List.of(true, false)) {
            try (Fixture fixture = new Fixture()) {
                BackupJob job = fixture.startRunning();
                String capturedId = job.getVaultId();
                job = fixture.mapper.readValue(fixture.mapper.writeValueAsString(job), BackupJob.class);
                if (!matchingArn) {
                    job.setBackupVaultArn(job.getBackupVaultArn() + "-different");
                }
                fixture.jobs.put(job.getBackupJobId(), job);
                fixture.complete(job);
                assertEquals(matchingArn ? "COMPLETED" : "FAILED", job.getState());
                assertEquals(matchingArn ? 1 : 0, fixture.points.keys().size());
                assertEquals(matchingArn ? 1 : 0, fixture.vault().getNumberOfRecoveryPoints());
                assertEquals(capturedId, job.getVaultId());
                assertEquals(fixture.vault().getVaultId(), job.getVaultId());
            }
        }
    }

    @Test
    void legacyMissingCompletionDeadlineUsesCreationPlusConfiguredDelay() {
        try (Fixture fixture = new Fixture()) {
            long now = Instant.now().getEpochSecond();
            fixture.vault().setCreationDate(now - 7200);
            BackupJob job = fixture.legacy("CREATED", now - 3601);
            job.setExpectedCompletionDate(null);
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("COMPLETED", fixture.service.describeBackupJob("legacy").getState()));
            assertEquals(1, fixture.points.keys().size());
            assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
            assertTrue(job.getRecoveryPointArn().contains(":" + REGION + ":" + ACCOUNT + ":"));
        }
    }

    private static class Fixture implements AutoCloseable {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        private final StorageFactory storage = mock(StorageFactory.class);
        private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        private final RegionResolver resolver = new RegionResolver("us-east-1", ACCOUNT);
        private final ObjectMapper mapper = new ObjectMapper();
        private final SnsService sns = mock(SnsService.class);
        private final AccountAwareStorageBackend<BackupVault> vaults;
        private final AccountAwareStorageBackend<BackupJob> jobs;
        private final AccountAwareStorageBackend<RecoveryPoint> points;
        private BackupService service;

        Fixture() {
            when(storage.create(eq("backup"), anyString(), any())).thenAnswer(call ->
                    stores.computeIfAbsent(call.getArgument(1), ignored ->
                            spy(AccountAwareStorageBackend.inMemory(ACCOUNT))));
            when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(3600);
            service = newService();
            vaults = store("backup-vaults.json");
            jobs = store("backup-jobs.json");
            points = store("backup-recovery-points.json");
            service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        }

        @SuppressWarnings("unchecked")
        private <T> AccountAwareStorageBackend<T> store(String name) {
            return (AccountAwareStorageBackend<T>) stores.get(name);
        }

        private BackupService newService() {
            return new BackupService(storage, config, resolver, mapper, sns);
        }

        private BackupVault vault() {
            return service.describeBackupVault(VAULT, REGION);
        }

        private BackupJob startRunning() {
            BackupJob job = service.startBackupJob(VAULT, "arn:aws:s3:::review-bucket", "role", null, REGION);
            job.setState("RUNNING");
            jobs.put(job.getBackupJobId(), job);
            return job;
        }

        private BackupJob legacy(String state, long creationDate) {
            BackupJob job = new BackupJob();
            job.setBackupJobId("legacy");
            job.setBackupVaultName(VAULT);
            job.setBackupVaultArn(vault().getBackupVaultArn());
            job.setResourceArn("arn:aws:s3:::review-bucket");
            job.setAccountId(ACCOUNT);
            job.setState(state);
            job.setCreationDate(creationDate);
            job.setStartBy(Instant.now().getEpochSecond() + 3600);
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            jobs.put(job.getBackupJobId(), job);
            return job;
        }

        private void complete(BackupJob job) throws Exception {
            Method method = BackupService.class.getDeclaredMethod("completeJob", String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(service, job.getBackupJobId(), VAULT, REGION);
        }

        private Object vaultMonitor() throws Exception {
            Field field = BackupService.class.getDeclaredField("vaultLocks");
            field.setAccessible(true);
            Map<?, ?> locks = (Map<?, ?>) field.get(service);
            return locks.entrySet().stream()
                    .filter(entry -> entry.getKey().toString().endsWith(REGION + ":" + VAULT))
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
        }

        @Override
        public void close() {
            service.shutdown();
        }
    }
}
