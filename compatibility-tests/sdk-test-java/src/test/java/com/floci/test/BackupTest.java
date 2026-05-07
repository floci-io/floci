package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AWS Backup")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupTest {

    private static BackupClient backup;

    private static final String VAULT_NAME = "compat-test-vault";
    private static final String IAM_ROLE   = "arn:aws:iam::000000000000:role/backup-role";
    private static final String RESOURCE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/my-table";

    private static String vaultArn;
    private static String planId;
    private static String planArn;
    private static String selectionId;
    private static String jobId;
    private static String recoveryPointArn;

    @BeforeAll
    static void setup() {
        backup = TestFixtures.backupClient();
    }

    @AfterAll
    static void cleanup() {
        if (backup == null) return;
        try { backup.deleteBackupSelection(r -> r.backupPlanId(planId).selectionId(selectionId)); } catch (Exception ignored) {}
        try { backup.deleteBackupPlan(r -> r.backupPlanId(planId)); } catch (Exception ignored) {}
        try {
            backup.listRecoveryPointsByBackupVault(r -> r.backupVaultName(VAULT_NAME))
                  .recoveryPoints()
                  .forEach(rp -> {
                      try {
                          backup.deleteRecoveryPoint(r -> r
                                  .backupVaultName(VAULT_NAME)
                                  .recoveryPointArn(rp.recoveryPointArn()));
                      } catch (Exception ignored2) {}
                  });
        } catch (Exception ignored) {}
        try { backup.deleteBackupVault(r -> r.backupVaultName(VAULT_NAME)); } catch (Exception ignored) {}
        backup.close();
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("CreateBackupVault - creates vault with tags")
    void createBackupVault() {
        CreateBackupVaultResponse resp = backup.createBackupVault(r -> r
                .backupVaultName(VAULT_NAME)
                .backupVaultTags(Map.of("env", "compat-test")));

        vaultArn = resp.backupVaultArn();
        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(vaultArn).contains("backup-vault:" + VAULT_NAME);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(11)
    @DisplayName("CreateBackupVault - duplicate returns AlreadyExistsException")
    void createVaultDuplicateFails() {
        assertThatThrownBy(() -> backup.createBackupVault(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @Order(12)
    @DisplayName("DescribeBackupVault - returns vault metadata")
    void describeBackupVault() {
        DescribeBackupVaultResponse resp = backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME));

        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(resp.backupVaultArn()).isEqualTo(vaultArn);
        assertThat(resp.numberOfRecoveryPoints()).isEqualTo(0);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("ListBackupVaults - includes created vault")
    void listBackupVaults() {
        ListBackupVaultsResponse resp = backup.listBackupVaults(r -> r.build());

        assertThat(resp.backupVaultList()).isNotEmpty();
        assertThat(resp.backupVaultList())
                .anyMatch(v -> VAULT_NAME.equals(v.backupVaultName()));
    }

    @Test
    @Order(14)
    @DisplayName("DescribeBackupVault - non-existent returns ResourceNotFoundException")
    void describeNonExistentVaultFails() {
        assertThatThrownBy(() -> backup.describeBackupVault(r -> r.backupVaultName("no-such-vault")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("CreateBackupPlan - creates plan with rules")
    void createBackupPlan() {
        CreateBackupPlanResponse resp = backup.createBackupPlan(r -> r
                .backupPlan(p -> p
                        .backupPlanName("compat-daily")
                        .rules(BackupRuleInput.builder()
                                .ruleName("daily")
                                .targetBackupVaultName(VAULT_NAME)
                                .scheduleExpression("cron(0 12 * * ? *)")
                                .startWindowMinutes(60L)
                                .completionWindowMinutes(120L)
                                .build())));

        planId  = resp.backupPlanId();
        planArn = resp.backupPlanArn();
        assertThat(planId).isNotNull();
        assertThat(planArn).contains("backup-plan:");
        assertThat(resp.versionId()).isNotNull();
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(21)
    @DisplayName("GetBackupPlan - returns plan with rules")
    void getBackupPlan() {
        GetBackupPlanResponse resp = backup.getBackupPlan(r -> r.backupPlanId(planId));

        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.backupPlan().backupPlanName()).isEqualTo("compat-daily");
        assertThat(resp.backupPlan().rules()).hasSize(1);
        assertThat(resp.backupPlan().rules().get(0).ruleName()).isEqualTo("daily");
        assertThat(resp.backupPlan().rules().get(0).ruleId()).isNotNull();
    }

    @Test
    @Order(22)
    @DisplayName("UpdateBackupPlan - replaces rules and bumps versionId")
    void updateBackupPlan() {
        String oldVersionId = backup.getBackupPlan(r -> r.backupPlanId(planId)).versionId();

        UpdateBackupPlanResponse resp = backup.updateBackupPlan(r -> r
                .backupPlanId(planId)
                .backupPlan(p -> p
                        .backupPlanName("compat-daily-v2")
                        .rules(BackupRuleInput.builder()
                                .ruleName("daily-v2")
                                .targetBackupVaultName(VAULT_NAME)
                                .scheduleExpression("cron(0 6 * * ? *)")
                                .build())));

        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.versionId()).isNotEqualTo(oldVersionId);
    }

    @Test
    @Order(23)
    @DisplayName("ListBackupPlans - includes created plan")
    void listBackupPlans() {
        ListBackupPlansResponse resp = backup.listBackupPlans(r -> r.build());

        assertThat(resp.backupPlansList()).isNotEmpty();
        assertThat(resp.backupPlansList())
                .anyMatch(p -> planId.equals(p.backupPlanId()));
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("CreateBackupSelection - creates selection")
    void createBackupSelection() {
        CreateBackupSelectionResponse resp = backup.createBackupSelection(r -> r
                .backupPlanId(planId)
                .backupSelection(s -> s
                        .selectionName("compat-selection")
                        .iamRoleArn(IAM_ROLE)
                        .resources(RESOURCE_ARN)));

        selectionId = resp.selectionId();
        assertThat(selectionId).isNotNull();
        assertThat(resp.backupPlanId()).isEqualTo(planId);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(31)
    @DisplayName("GetBackupSelection - returns selection detail")
    void getBackupSelection() {
        GetBackupSelectionResponse resp = backup.getBackupSelection(r -> r
                .backupPlanId(planId)
                .selectionId(selectionId));

        assertThat(resp.selectionId()).isEqualTo(selectionId);
        assertThat(resp.backupSelection().selectionName()).isEqualTo("compat-selection");
        assertThat(resp.backupSelection().iamRoleArn()).isEqualTo(IAM_ROLE);
        assertThat(resp.backupSelection().resources()).contains(RESOURCE_ARN);
    }

    @Test
    @Order(32)
    @DisplayName("ListBackupSelections - includes created selection")
    void listBackupSelections() {
        ListBackupSelectionsResponse resp = backup.listBackupSelections(r -> r.backupPlanId(planId));

        assertThat(resp.backupSelectionsList()).hasSize(1);
        assertThat(resp.backupSelectionsList().get(0).selectionId()).isEqualTo(selectionId);
        assertThat(resp.backupSelectionsList().get(0).selectionName()).isEqualTo("compat-selection");
    }

    @Test
    @Order(33)
    @DisplayName("DeleteBackupPlan with active selection returns InvalidRequestException")
    void deletePlanWithSelectionFails() {
        assertThatThrownBy(() -> backup.deleteBackupPlan(r -> r.backupPlanId(planId)))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("StartBackupJob - returns job ID in CREATED state")
    void startBackupJob() {
        StartBackupJobResponse resp = backup.startBackupJob(r -> r
                .backupVaultName(VAULT_NAME)
                .resourceArn(RESOURCE_ARN)
                .iamRoleArn(IAM_ROLE));

        jobId = resp.backupJobId();
        assertThat(jobId).isNotNull();
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(41)
    @DisplayName("DescribeBackupJob - job is in progress shortly after start")
    void describeBackupJobInProgress() {
        DescribeBackupJobResponse resp = backup.describeBackupJob(r -> r.backupJobId(jobId));

        assertThat(resp.backupJobId()).isEqualTo(jobId);
        assertThat(resp.state().toString()).isIn("CREATED", "RUNNING", "COMPLETED");
        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(resp.resourceArn()).isEqualTo(RESOURCE_ARN);
    }

    @Test
    @Order(42)
    @DisplayName("DescribeBackupJob - job completes within 10 seconds")
    void describeBackupJobCompleted() throws InterruptedException {
        BackupJobState state = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            DescribeBackupJobResponse resp = backup.describeBackupJob(r -> r.backupJobId(jobId));
            state = resp.state();
            if (state == BackupJobState.COMPLETED) {
                recoveryPointArn = resp.recoveryPointArn();
                assertThat(recoveryPointArn).contains("recovery-point:");
                assertThat(resp.completionDate()).isNotNull();
                return;
            }
        }
        Assertions.fail("Backup job did not complete within 10 seconds, last state: " + state);
    }

    @Test
    @Order(43)
    @DisplayName("ListBackupJobs - filter by vault name")
    void listBackupJobsByVault() {
        ListBackupJobsResponse resp = backup.listBackupJobs(r -> r.byBackupVaultName(VAULT_NAME));

        assertThat(resp.backupJobs()).isNotEmpty();
        assertThat(resp.backupJobs()).allMatch(j -> VAULT_NAME.equals(j.backupVaultName()));
    }

    @Test
    @Order(44)
    @DisplayName("ListBackupJobs - filter by COMPLETED state")
    void listBackupJobsByState() {
        ListBackupJobsResponse resp = backup.listBackupJobs(r -> r.byState(BackupJobState.COMPLETED));

        assertThat(resp.backupJobs()).isNotEmpty();
        assertThat(resp.backupJobs()).allMatch(j -> j.state() == BackupJobState.COMPLETED);
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("DescribeRecoveryPoint - returns completed recovery point")
    void describeRecoveryPoint() {
        DescribeRecoveryPointResponse resp = backup.describeRecoveryPoint(r -> r
                .backupVaultName(VAULT_NAME)
                .recoveryPointArn(recoveryPointArn));

        assertThat(resp.recoveryPointArn()).isEqualTo(recoveryPointArn);
        assertThat(resp.backupVaultName()).isEqualTo(VAULT_NAME);
        assertThat(resp.status()).isEqualTo(RecoveryPointStatus.COMPLETED);
        assertThat(resp.resourceArn()).isEqualTo(RESOURCE_ARN);
        assertThat(resp.completionDate()).isNotNull();
    }

    @Test
    @Order(51)
    @DisplayName("ListRecoveryPointsByBackupVault - returns created recovery point")
    void listRecoveryPointsByBackupVault() {
        ListRecoveryPointsByBackupVaultResponse resp = backup.listRecoveryPointsByBackupVault(r -> r
                .backupVaultName(VAULT_NAME));

        assertThat(resp.recoveryPoints()).hasSize(1);
        assertThat(resp.recoveryPoints().get(0).recoveryPointArn()).isEqualTo(recoveryPointArn);
    }

    @Test
    @Order(52)
    @DisplayName("DescribeBackupVault - NumberOfRecoveryPoints incremented")
    void vaultCountAfterJob() {
        DescribeBackupVaultResponse resp = backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME));
        assertThat(resp.numberOfRecoveryPoints()).isEqualTo(1);
    }

    @Test
    @Order(53)
    @DisplayName("DeleteBackupVault - non-empty vault returns InvalidRequestException")
    void deleteNonEmptyVaultFails() {
        assertThatThrownBy(() -> backup.deleteBackupVault(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @Order(54)
    @DisplayName("DeleteRecoveryPoint - removes recovery point and decrements vault count")
    void deleteRecoveryPoint() {
        backup.deleteRecoveryPoint(r -> r
                .backupVaultName(VAULT_NAME)
                .recoveryPointArn(recoveryPointArn));

        DescribeBackupVaultResponse vault = backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME));
        assertThat(vault.numberOfRecoveryPoints()).isEqualTo(0);

        assertThatThrownBy(() -> backup.describeRecoveryPoint(r -> r
                .backupVaultName(VAULT_NAME)
                .recoveryPointArn(recoveryPointArn)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Tagging ────────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("TagResource / ListTags / UntagResource - SDK round-trip")
    void tagRoundTrip() {
        backup.tagResource(r -> r
                .resourceArn(vaultArn)
                .tags(Map.of("team", "platform", "cost-center", "eng")));

        ListTagsResponse listed = backup.listTags(r -> r.resourceArn(vaultArn));
        assertThat(listed.tags())
                .containsEntry("env", "compat-test")
                .containsEntry("team", "platform")
                .containsEntry("cost-center", "eng");

        backup.untagResource(r -> r
                .resourceArn(vaultArn)
                .tagKeyList(List.of("team")));

        ListTagsResponse afterUntag = backup.listTags(r -> r.resourceArn(vaultArn));
        assertThat(afterUntag.tags())
                .doesNotContainKey("team")
                .containsEntry("cost-center", "eng")
                .containsEntry("env", "compat-test");
    }

    // ── Supported Resource Types ────────────────────────────────────────────────

    @Test
    @Order(70)
    @DisplayName("GetSupportedResourceTypes - returns non-empty list including S3 and DynamoDB")
    void getSupportedResourceTypes() {
        GetSupportedResourceTypesResponse resp = backup.getSupportedResourceTypes(
                GetSupportedResourceTypesRequest.builder().build());

        assertThat(resp.resourceTypes()).isNotEmpty();
        assertThat(resp.resourceTypes()).contains("S3", "DynamoDB");
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    @DisplayName("DeleteBackupSelection - removes selection")
    void deleteBackupSelection() {
        backup.deleteBackupSelection(r -> r
                .backupPlanId(planId)
                .selectionId(selectionId));

        assertThat(backup.listBackupSelections(r -> r.backupPlanId(planId))
                .backupSelectionsList()).isEmpty();
    }

    @Test
    @Order(81)
    @DisplayName("DeleteBackupPlan - removes plan")
    void deleteBackupPlan() {
        backup.deleteBackupPlan(r -> r.backupPlanId(planId));

        assertThatThrownBy(() -> backup.getBackupPlan(r -> r.backupPlanId(planId)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(82)
    @DisplayName("DeleteBackupVault - removes empty vault")
    void deleteBackupVault() {
        backup.deleteBackupVault(r -> r.backupVaultName(VAULT_NAME));

        assertThatThrownBy(() -> backup.describeBackupVault(r -> r.backupVaultName(VAULT_NAME)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
