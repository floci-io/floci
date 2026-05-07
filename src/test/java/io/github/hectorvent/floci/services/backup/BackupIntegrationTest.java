package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for AWS Backup via REST JSON protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackupIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";
    private static final String VAULT_NAME = "test-vault";
    private static final String IAM_ROLE = "arn:aws:iam::000000000000:role/backup-role";
    private static final String RESOURCE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/my-table";

    private static String planId;
    private static String selectionId;
    private static String jobId;
    private static String recoveryPointArn;

    // ── Vault ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createBackupVault() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"BackupVaultTags\":{\"env\":\"test\"}}")
        .when()
            .put("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("BackupVaultArn", containsString("backup-vault:" + VAULT_NAME));
    }

    @Test
    @Order(11)
    void describeBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("NumberOfRecoveryPoints", equalTo(0));
    }

    @Test
    @Order(12)
    void listBackupVaults() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/")
        .then()
            .statusCode(200)
            .body("BackupVaultList", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupVaultList[0].BackupVaultName", notNullValue());
    }

    @Test
    @Order(13)
    void createVaultAlreadyExistsReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .put("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(400);
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createBackupPlan() {
        planId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupPlan": {
                    "BackupPlanName": "daily-backup",
                    "Rules": [{
                      "RuleName": "daily",
                      "TargetBackupVaultName": "%s",
                      "ScheduleExpression": "cron(0 12 * * ? *)",
                      "StartWindowMinutes": 60,
                      "CompletionWindowMinutes": 120
                    }]
                  }
                }
                """.formatted(VAULT_NAME))
        .when()
            .put("/backup/plans/")
        .then()
            .statusCode(200)
            .body("BackupPlanId", notNullValue())
            .body("BackupPlanArn", containsString("backup-plan:"))
            .body("VersionId", notNullValue())
            .extract().path("BackupPlanId");
    }

    @Test
    @Order(21)
    void getBackupPlan() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/")
        .then()
            .statusCode(200)
            .body("BackupPlanId", equalTo(planId))
            .body("BackupPlan.BackupPlanName", equalTo("daily-backup"))
            .body("BackupPlan.Rules[0].RuleName", equalTo("daily"))
            .body("BackupPlan.Rules[0].RuleId", notNullValue());
    }

    @Test
    @Order(22)
    void updateBackupPlan() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupPlan": {
                    "BackupPlanName": "daily-backup-v2",
                    "Rules": [{
                      "RuleName": "daily-v2",
                      "TargetBackupVaultName": "%s",
                      "ScheduleExpression": "cron(0 6 * * ? *)"
                    }]
                  }
                }
                """.formatted(VAULT_NAME))
        .when()
            .post("/backup/plans/" + planId)
        .then()
            .statusCode(200)
            .body("BackupPlanId", equalTo(planId))
            .body("VersionId", notNullValue());
    }

    @Test
    @Order(23)
    void listBackupPlans() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/")
        .then()
            .statusCode(200)
            .body("BackupPlansList", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupPlansList[0].BackupPlanId", notNullValue());
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createBackupSelection() {
        selectionId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupSelection": {
                    "SelectionName": "my-selection",
                    "IamRoleArn": "%s",
                    "Resources": ["%s"]
                  }
                }
                """.formatted(IAM_ROLE, RESOURCE_ARN))
        .when()
            .put("/backup/plans/" + planId + "/selections/")
        .then()
            .statusCode(200)
            .body("SelectionId", notNullValue())
            .body("BackupPlanId", equalTo(planId))
            .extract().path("SelectionId");
    }

    @Test
    @Order(31)
    void getBackupSelection() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/selections/" + selectionId)
        .then()
            .statusCode(200)
            .body("SelectionId", equalTo(selectionId))
            .body("BackupSelection.SelectionName", equalTo("my-selection"))
            .body("BackupSelection.IamRoleArn", equalTo(IAM_ROLE));
    }

    @Test
    @Order(32)
    void listBackupSelections() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup/plans/" + planId + "/selections/")
        .then()
            .statusCode(200)
            .body("BackupSelectionsList", hasSize(1))
            .body("BackupSelectionsList[0].SelectionId", equalTo(selectionId));
    }

    @Test
    @Order(33)
    void deleteBackupPlanWithSelectionReturns400() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId)
        .then()
            .statusCode(400);
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void startBackupJob() {
        jobId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(VAULT_NAME, RESOURCE_ARN, IAM_ROLE))
        .when()
            .put("/backup-jobs")
        .then()
            .statusCode(200)
            .body("BackupJobId", notNullValue())
            .body("BackupVaultArn", containsString("backup-vault:"))
            .extract().path("BackupJobId");
    }

    @Test
    @Order(41)
    void describeBackupJobCreated() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/" + jobId)
        .then()
            .statusCode(200)
            .body("BackupJobId", equalTo(jobId))
            .body("State", oneOf("CREATED", "RUNNING", "COMPLETED"))
            .body("BackupVaultName", equalTo(VAULT_NAME));
    }

    @Test
    @Order(42)
    void describeBackupJobCompleted() throws InterruptedException {
        Thread.sleep(2000); // job-completion-delay-seconds=1 in test config
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/" + jobId)
        .then()
            .statusCode(200)
            .body("State", equalTo("COMPLETED"))
            .body("RecoveryPointArn", containsString("recovery-point:"))
            .body("CompletionDate", notNullValue());

        recoveryPointArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/" + jobId)
        .then()
            .extract().path("RecoveryPointArn");
    }

    @Test
    @Order(43)
    void listBackupJobsByVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/?byBackupVaultName=" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("BackupJobs", hasSize(greaterThanOrEqualTo(1)))
            .body("BackupJobs[0].BackupVaultName", equalTo(VAULT_NAME));
    }

    @Test
    @Order(44)
    void listBackupJobsByState() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-jobs/?byState=COMPLETED")
        .then()
            .statusCode(200)
            .body("BackupJobs", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void describeRecoveryPoint() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + recoveryPointArn)
        .then()
            .statusCode(200)
            .body("RecoveryPointArn", equalTo(recoveryPointArn))
            .body("BackupVaultName", equalTo(VAULT_NAME))
            .body("Status", equalTo("COMPLETED"));
    }

    @Test
    @Order(51)
    void listRecoveryPointsByBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/recovery-points/")
        .then()
            .statusCode(200)
            .body("RecoveryPoints", hasSize(1))
            .body("RecoveryPoints[0].RecoveryPointArn", equalTo(recoveryPointArn));
    }

    @Test
    @Order(52)
    void vaultCountIncrementedAfterJob() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("NumberOfRecoveryPoints", equalTo(1));
    }

    @Test
    @Order(53)
    void deleteVaultWithRecoveryPointsReturns400() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(400);
    }

    @Test
    @Order(54)
    void deleteRecoveryPoint() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME + "/recovery-points/" + recoveryPointArn)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(55)
    void vaultCountDecrementedAfterDelete() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(200)
            .body("NumberOfRecoveryPoints", equalTo(0));
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void tagBackupVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"Tags\":{\"team\":\"platform\"}}")
        .when()
            .post("/tags/" + vaultArn)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(61)
    void listTagsForVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/tags/" + vaultArn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("test"))
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(62)
    void untagBackupVault() {
        String vaultArn = given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .extract().path("BackupVaultArn");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"TagKeyList\":[\"team\"]}")
        .when()
            .post("/untag/" + vaultArn)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/tags/" + vaultArn)
        .then()
            .statusCode(200)
            .body("Tags.team", nullValue())
            .body("Tags.env", equalTo("test"));
    }

    // ── Supported Resource Types ───────────────────────────────────────────────

    @Test
    @Order(70)
    void getSupportedResourceTypes() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/supported-resource-types")
        .then()
            .statusCode(200)
            .body("ResourceTypes", hasSize(greaterThan(0)))
            .body("ResourceTypes", hasItem("S3"))
            .body("ResourceTypes", hasItem("DynamoDB"));
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    void deleteBackupSelection() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId + "/selections/" + selectionId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(81)
    void deleteBackupPlan() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup/plans/" + planId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(82)
    void deleteBackupVault() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(83)
    void describeDeletedVaultReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME)
        .then()
            .statusCode(404);
    }
}
