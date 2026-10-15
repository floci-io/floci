package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupJob;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BackupJobRecoveryIntegrationTest {
    @Inject StorageFactory storage;
    @Inject EmulatorConfig config;
    @Inject RegionResolver resolver;
    @Inject ObjectMapper mapper;

    @Test
    void restartRestoresJobsInEveryAccountAndTheirOriginalRegion() {
        String vaultName = "restart-" + UUID.randomUUID();
        List<String> accounts = List.of("811111111111", "822222222222");
        BackupService first = new BackupService(storage, config, resolver, mapper);
        AccountAwareStorageBackend<BackupJob> jobs = storage.create("backup", "backup-jobs.json",
                new TypeReference<Map<String, BackupJob>>() {});
        try {
            for (String account : accounts) {
                RequestScopes.runAs(account, "eu-west-1", () -> {
                    first.createBackupVault(vaultName, null, null, Map.of(), "eu-west-1");
                    BackupJob job = first.startBackupJob(vaultName, "arn:aws:s3:::bucket", "role", null, "eu-west-1");
                    job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
                    jobs.put(job.getBackupJobId(), job);
                });
            }
        } finally {
            first.shutdown();
        }
        BackupService restarted = new BackupService(storage, config, resolver, mapper);
        try {
            restarted.resumeJobs();
            for (String account : accounts) {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> RequestScopes.runAs(account, () -> {
                    assertEquals("COMPLETED", restarted.listBackupJobs(vaultName, null, null, null).getFirst().getState());
                    assertEquals(1, restarted.listRecoveryPointsByBackupVault(vaultName, "eu-west-1").size());
                    String arn = restarted.listRecoveryPointsByBackupVault(vaultName, "eu-west-1")
                            .getFirst().getRecoveryPointArn();
                    assertTrue(arn.contains(":eu-west-1:" + account + ":"));
                    assertEquals(1, restarted.describeBackupVault(vaultName, "eu-west-1").getNumberOfRecoveryPoints());
                }));
            }
        } finally {
            restarted.shutdown();
            for (String account : accounts) {
                RequestScopes.runAs(account, () -> {
                    restarted.listRecoveryPointsByBackupVault(vaultName, "eu-west-1").forEach(point ->
                            restarted.deleteRecoveryPoint(vaultName, point.getRecoveryPointArn(), "eu-west-1"));
                    restarted.listBackupJobs(vaultName, null, null, null).forEach(job -> jobs.delete(job.getBackupJobId()));
                    restarted.deleteBackupVault(vaultName, "eu-west-1");
                });
            }
        }
    }
}
