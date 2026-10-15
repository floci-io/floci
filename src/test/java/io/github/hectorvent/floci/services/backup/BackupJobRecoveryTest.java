package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupJob;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.backup.model.RecoveryPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupJobRecoveryTest {
    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void restartResumesEachNonTerminalStateAndDoesNotDuplicateRecoveryPoints() {
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(eq("backup"), anyString(), any())).thenAnswer(call ->
                stores.computeIfAbsent(call.getArgument(1), ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT)));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(60);
        RegionResolver resolver = new RegionResolver("us-east-1", ACCOUNT);
        BackupService first = new BackupService(storage, config, resolver, new ObjectMapper());
        BackupVault vault = first.createBackupVault("shared", null, null, Map.of(), REGION);
        BackupJob original = first.startBackupJob("shared", "arn:aws:s3:::bucket", "role", null, REGION);
        first.shutdown();

        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<BackupJob> jobs = (AccountAwareStorageBackend<BackupJob>) stores.get("backup-jobs.json");
        long now = Instant.now().getEpochSecond();
        original.setExpectedCompletionDate(now - 1);
        jobs.put(original.getBackupJobId(), original);
        for (String state : List.of("RUNNING", "ABORTING", "COMPLETED", "FAILED", "ABORTED")) {
            BackupJob job = job(state, vault, now);
            jobs.put(state, job);
        }
        BackupJob expired = job("CREATED", vault, now);
        expired.setBackupJobId("expired");
        expired.setStartBy(now - 1);
        jobs.put("expired", expired);
        BackupJob missingVault = job("RUNNING", vault, now);
        missingVault.setBackupJobId("missing-vault");
        missingVault.setBackupVaultName("deleted");
        jobs.put("missing-vault", missingVault);
        BackupJob future = job("RUNNING", vault, now);
        future.setBackupJobId("future");
        future.setExpectedCompletionDate(now + 2);
        jobs.put("future", future);
        // Simulate a crash after a recovery point was persisted, before the job's terminal write.
        RecoveryPoint existing = new RecoveryPoint();
        existing.setBackupVaultName("shared");
        existing.setBackupVaultArn(vault.getBackupVaultArn());
        existing.setRecoveryPointArn("arn:aws:backup:" + REGION + ":" + ACCOUNT + ":recovery-point:RUNNING");
        existing.setCreationDate(now - 20);
        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<RecoveryPoint> points = (AccountAwareStorageBackend<RecoveryPoint>)
                stores.get("backup-recovery-points.json");
        points.put(existing.getRecoveryPointArn(), existing);
        BackupService restarted = new BackupService(storage, config, resolver, new ObjectMapper());
        try {
            restarted.resumeJobs();
            assertEquals("RUNNING", restarted.describeBackupJob("future").getState());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("COMPLETED", restarted.describeBackupJob(original.getBackupJobId()).getState());
                assertEquals("COMPLETED", restarted.describeBackupJob("RUNNING").getState());
                assertEquals("ABORTED", restarted.describeBackupJob("ABORTING").getState());
                assertEquals("EXPIRED", restarted.describeBackupJob("expired").getState());
                assertEquals("FAILED", restarted.describeBackupJob("missing-vault").getState());
                assertEquals("COMPLETED", restarted.describeBackupJob("future").getState());
            });
            restarted.resumeJobs();
            assertEquals(3, restarted.listRecoveryPointsByBackupVault("shared", REGION).size());
            assertEquals(3, restarted.describeBackupVault("shared", REGION).getNumberOfRecoveryPoints());
            assertEquals(now - 20, points.get(existing.getRecoveryPointArn()).orElseThrow().getCreationDate());
            assertTrue(restarted.listRecoveryPointsByBackupVault("shared", REGION).stream()
                    .allMatch(point -> point.getRecoveryPointArn().contains(":" + REGION + ":" + ACCOUNT + ":")));
            for (String state : List.of("COMPLETED", "FAILED", "ABORTED")) {
                assertEquals(state, restarted.describeBackupJob(state).getState());
                assertNull(restarted.describeBackupJob(state).getRecoveryPointArn());
            }
        } finally {
            restarted.shutdown();
        }
    }

    private static BackupJob job(String state, BackupVault vault, long now) {
        BackupJob job = new BackupJob();
        job.setBackupJobId(state);
        job.setBackupVaultName("shared");
        job.setBackupVaultArn(vault.getBackupVaultArn());
        job.setResourceArn("arn:aws:s3:::bucket");
        job.setAccountId(ACCOUNT);
        job.setState(state);
        job.setCreationDate(now - 10);
        job.setExpectedCompletionDate(now - 1);
        job.setStartBy(now + 3600);
        return job;
    }
}
