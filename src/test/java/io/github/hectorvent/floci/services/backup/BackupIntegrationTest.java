package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
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

    /**
     * Blocks until a backup job reports {@code COMPLETED}.
     *
     * <p>A job finishes asynchronously, so the recovery point it produces is not readable the
     * instant PutBackupJob returns. Polling the state rather than sleeping a fixed span is what
     * keeps this honest on a slow runner: a sleep sized for this machine only means the job is
     * still RUNNING on a slower one, and the assertion that follows fails for a reason that has
     * nothing to do with the behaviour under test.
     */
    private static void awaitJobCompleted(String backupJobId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    String state = given()
                        .header("Authorization", AUTH)
                    .when()
                        .get("/backup-jobs/" + backupJobId)
                    .then()
                        .statusCode(200)
                        .extract().path("State");
                    return "COMPLETED".equals(state);
                });
    }

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

    @Test
    @Order(14)
    void getBackupVaultNotificationsReturnsResourceNotFound() {
        // Notifications are never configured in the emulator; the AWS Backup API returns
        // ResourceNotFoundException (HTTP 400) in that case, which clients branch on.
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/notification-configuration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void getBackupVaultAccessPolicyReturnsResourceNotFound() {
        // Access policy is never configured in the emulator; the AWS Backup API returns
        // ResourceNotFoundException (HTTP 400) in that case, which clients branch on.
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/backup-vaults/" + VAULT_NAME + "/access-policy")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
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
    void describeBackupJobCompleted() {
        awaitJobCompleted(jobId);
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

    // ── Vault sub-resources: access policy, notifications, lock ────────────────
    //
    // These run on their own vault, at orders above every existing test, so that
    // configuring a policy here cannot change what the Order(14)/Order(15) tests above
    // observe. Those two assert the unconfigured case, which is still the right answer
    // for a vault nobody has configured -- the gap this section covers was that there
    // was no way to configure one.

    private static final String SUB_VAULT = "sub-resource-vault";
    private static final String POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AllowDescribe\","
            + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},"
            + "\"Action\":\"backup:DescribeBackupVault\",\"Resource\":\"*\"}]}";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:backup-events";

    @Test
    @Order(100)
    void createSubResourceVault() {
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200).body("BackupVaultName", equalTo(SUB_VAULT));
    }

    @Test
    @Order(101)
    void anUnlockedVaultReportsLockedFalse() {
        // Locked is always present on a vault in AWS. A client cannot tell "not locked"
        // from "this emulator does not model locking" unless the member is there.
        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200)
            .body("Locked", equalTo(false))
            .body("$", not(hasKey("LockDate")));
    }

    @Test
    @Order(110)
    void putAccessPolicyThenReadItBack() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"Policy\":\"" + POLICY.replace("\"", "\\\"") + "\"}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(200)
            .body("BackupVaultName", equalTo(SUB_VAULT))
            .body("BackupVaultArn", containsString("backup-vault:" + SUB_VAULT))
            .body("Policy", containsString("AllowDescribe"));
    }

    @Test
    @Order(111)
    void putAccessPolicyWithoutADocumentClearsTheStoredOne() {
        // Policy is documented "Required: No", so refusing a request that omits it fails a call
        // real AWS accepts. Order(110) put a policy on this vault, so the read-back below is
        // what proves the omission cleared it rather than simply finding nothing there.
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(112)
    void accessPolicyOnAVaultThatDoesNotExistSaysSo() {
        // The distinction that matters: "no such vault" is a different answer from
        // "that vault has no policy", and a caller with a typo needs the first.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"Policy\":\"{}\"}")
        .when().put("/backup-vaults/no-such-vault/access-policy")
        .then().statusCode(404).body("message", containsString("no-such-vault"));
    }

    @Test
    @Order(113)
    void deleteAccessPolicyLeavesItUnconfigured() {
        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(114)
    void deleteAccessPolicyIsIdempotent() {
        // Terraform destroys a configuration it has already destroyed on a re-run.
        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(204);
    }

    @Test
    @Order(115)
    void putAccessPolicyStillRejectsAnEmptyDocument() {
        // Present and empty is not the same as absent: an empty string is not a policy document,
        // so it stays an InvalidParameterValueException.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"Policy\":\"\"}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(120)
    void putNotificationsThenReadThemBack() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\","
                + "\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\",\"RESTORE_JOB_FAILED\"]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(200)
            .body("BackupVaultName", equalTo(SUB_VAULT))
            .body("SNSTopicArn", equalTo(TOPIC))
            .body("BackupVaultEvents", hasItems("BACKUP_JOB_COMPLETED", "RESTORE_JOB_FAILED"));
    }

    @Test
    @Order(121)
    void notificationsRejectAnEventAwsDoesNotDefine() {
        // Accepting a misspelt event and echoing it back would let a configuration real
        // AWS refuses pass against the emulator.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\","
                + "\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\",\"NOT_AN_EVENT\"]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("NOT_AN_EVENT"));
    }

    @Test
    @Order(122)
    void notificationsRequireATopicAndAtLeastOneEvent() {
        // A missing required parameter is MissingParameterValueException, which the operation
        // lists in its Errors section; present-but-empty is InvalidParameterValueException. An
        // SDK maps __type to a typed exception, so collapsing the two hands the caller an error
        // other than the one the reference names for a missing parameter.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400)
            .body("__type", equalTo("MissingParameterValueException"))
            .body("message", containsString("SNSTopicArn"));

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\"}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400)
            .body("__type", equalTo("MissingParameterValueException"))
            .body("message", containsString("BackupVaultEvents"));

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"\",\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("SNSTopicArn"));

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\",\"BackupVaultEvents\":[]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("BackupVaultEvents"));
    }

    @Test
    @Order(123)
    void deleteNotificationsLeavesThemUnconfigured() {
        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    // ── Vault Lock ─────────────────────────────────────────────────────────────
    //
    // ChangeableForDays selects the mode, in the direction that reads backwards:
    // PRESENT means compliance (immutable on and after LockDate), ABSENT means
    // governance (no LockDate, removable at any time). An earlier revision of this
    // branch had them the wrong way round and these tests asserted the inversion, so
    // the suite could not catch it. Both directions are now pinned separately.

    @Test
    @Order(130)
    void aComplianceLockCarriesALockDateAndIsVisibleOnTheVault() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":7,\"MaxRetentionDays\":30,\"ChangeableForDays\":3}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200)
            .body("Locked", equalTo(true))
            .body("MinRetentionDays", equalTo(7))
            .body("MaxRetentionDays", equalTo(30))
            .body("LockDate", notNullValue());
    }

    @Test
    @Order(131)
    void aComplianceLockCanBeRemovedBeforeItsLockDate() {
        // "Before the lock date, you can delete Vault Lock from the vault using
        // DeleteBackupVaultLockConfiguration." The vault above was locked seconds ago
        // with a three-day cooling-off period, so it is inside that window.
        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200)
            .body("Locked", equalTo(false))
            .body("$", not(hasKey("LockDate")))
            .body("$", not(hasKey("MinRetentionDays")));
    }

    @Test
    @Order(132)
    void aGovernanceLockHasNoLockDateAndIsAlwaysRemovable() {
        // No ChangeableForDays: "If this parameter is not specified, you can delete
        // Vault Lock from the vault ... at any time."
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":7,\"MaxRetentionDays\":30}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200)
            .body("Locked", equalTo(true))
            .body("$", not(hasKey("LockDate")));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200).body("Locked", equalTo(false));
    }

    @Test
    @Order(133)
    void aGovernanceLockCanBeReconfiguredInPlace() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":7}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":14,\"MaxRetentionDays\":100}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200)
            .body("MinRetentionDays", equalTo(14))
            .body("MaxRetentionDays", equalTo(100));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/vault-lock").then().statusCode(204);
    }

    @Test
    @Order(134)
    void lockRejectsAMaximumBelowTheMinimum() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":30,\"MaxRetentionDays\":7,\"ChangeableForDays\":3}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(135)
    void lockRejectsACoolingOffPeriodBelowThreeDays() {
        // "AWS Backup enforces a 72-hour cooling-off period ... you must set
        // ChangeableForDays to 3 or greater."
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":7,\"ChangeableForDays\":2}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("ChangeableForDays"));
    }

    @Test
    @Order(136)
    void lockRejectsRetentionPeriodsBelowOneDay() {
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":0}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MaxRetentionDays\":0}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(140)
    void lockRejectsANonIntegralRetention() {
        // asLong() would have coerced each of these instead of refusing: 1.5 truncates
        // to 1, true becomes 1, "7" parses to 7. AWS rejects a non-integral value for a
        // long-typed member, so a coercing emulator lets a request AWS refuses succeed
        // against a number the caller never sent.
        // 18446744073709551623 is 2^64 + 7, the case isIntegralNumber() alone lets through:
        // longValue() keeps the low 64 bits, so it wraps to 7, a value the retention checks
        // are happy with. Only canConvertToLong() refuses it, and only a value that lands
        // INSIDE the valid range tests that guard: an out-of-range truncation is caught by
        // the range check instead, leaving the guard free to be deleted unnoticed.
        for (String bad : new String[] {"1.5", "true", "\"7\"", "18446744073709551623"}) {
            given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"MinRetentionDays\":" + bad + "}")
            .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("MinRetentionDays"));
        }
    }

    @Test
    @Order(137)
    void anEmptyVaultCanBeDeletedWhileLocked() {
        // A lock protects the recovery points, not the vault shell: AWS deletes an empty
        // vault even under a lock. An earlier revision refused this, which would also
        // have broken CloudFormation stack teardown.
        String vault = "locked-but-empty";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":7,\"ChangeableForDays\":3}")
        .when().put("/backup-vaults/" + vault + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);
    }

    @Test
    @Order(138)
    void accessPolicyAcceptsAnObjectBodyAsWellAsAString() {
        // Terraform and the console send Policy as a JSON string; an object is accepted
        // and re-serialised rather than refused, since refusing would be stricter than
        // AWS for no benefit.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"Policy\":{\"Version\":\"2012-10-17\",\"Statement\":[]}}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT + "/access-policy")
        .then().statusCode(200).body("Policy", containsString("2012-10-17"));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/access-policy").then().statusCode(204);
    }

    @Test
    @Order(139)
    void notificationsAreDroppedWithTheVaultToo() {
        // The policy case is covered at Order(141); this is the other sub-resource
        // store, which has its own delete call and could be forgotten independently.
        String vault = "recycled-notify";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\","
                + "\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + vault + "/notification-configuration")
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/notification-configuration")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(141)
    void subResourcesDoNotOutliveTheirVault() {
        // A fresh vault, configured, deleted and recreated under the same name must not
        // inherit the old policy: the sub-resource stores are keyed by vault name.
        String vault = "recycled-vault";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"Policy\":\"{}\"}")
        .when().put("/backup-vaults/" + vault + "/access-policy").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/access-policy")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }
    @Test
    @Order(142)
    void lockRejectsADayValueAboveTheDocumentedCeiling() {
        // The ceiling is documented per field, not once for all three: MaxRetentionDays, "The
        // longest maximum retention period you can specify is 36500 days", and ChangeableForDays,
        // "The maximum value you can specify is 36,500 days". Without it a Terraform
        // configuration with changeable_for_days = 40000 applies here and fails on real AWS,
        // which is the exact divergence this corpus exists to find.
        for (String field : new String[] {"MaxRetentionDays", "ChangeableForDays"}) {
            given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"" + field + "\":40000}")
            .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString(field));
        }

        // MinRetentionDays is the exception: the reference gives it a floor, "The shortest
        // minimum retention period you can specify is 1 day", and no maximum at all. Refusing
        // 40000 here would be stricter than the reference, which risks the same divergence
        // pointing the other way.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":40000}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + SUB_VAULT)
        .then().statusCode(200).body("MinRetentionDays", equalTo(40000));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + SUB_VAULT + "/vault-lock").then().statusCode(204);
    }

    @Test
    @Order(143)
    void lockRejectsLongMaxDaysWithA400RatherThanOverflowingTo500() {
        // Long.MAX_VALUE is a perfectly good long, so it survives the integral check and used to
        // reach Instant.plus, which throws ArithmeticException: long overflow. The only exception
        // mapper handles AwsException, so that escaped as a 500. The ceiling makes the overflow
        // unreachable rather than catching it afterwards; this pins the status code, because a
        // 500 here would be indistinguishable from the emulator simply falling over.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"ChangeableForDays\":9223372036854775807}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/vault-lock")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("ChangeableForDays"));
    }

    // ── What the lock enforces, and the validation that stops a bad value looking good ──
    //
    // Every test below covers a finding Greptile raised on the fork PR that our own review
    // had not: four of its six findings were one shape, state written that nothing consumed.
    // A round-trip test cannot see that shape -- the write works and the read works -- so
    // these assert on the BEHAVIOUR the stored value is supposed to change, which is the only
    // place the difference shows.

    private static final String LOCK_VAULT = "lock-enforcement-vault";

    @Test
    @Order(150)
    void aLockedVaultRefusesToDeleteARecoveryPointInsideMinRetention() {
        // The finding: PutBackupVaultLockConfiguration succeeded, DescribeBackupVault
        // reported Locked, and every recovery point the lock was supposed to protect deleted
        // exactly as before, because deleteRecoveryPoint read no lock field. The lock was
        // decorative. Note the shape of the old test suite: it could lock a vault and read the
        // lock back, and both passed, while this was true.
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + LOCK_VAULT).then().statusCode(200);

        String jid = given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(LOCK_VAULT, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs").then().statusCode(200).extract().path("BackupJobId");

        awaitJobCompleted(jid);
        String rpArn = given().header("Authorization", AUTH)
        .when().get("/backup-jobs/" + jid).then().statusCode(200)
            .body("State", equalTo("COMPLETED"))
            .extract().path("RecoveryPointArn");

        // Governance mode: no ChangeableForDays. It still enforces retention here, because
        // Floci does not model backup:DisableGovernanceRetention and so cannot grant the
        // override that would let a privileged caller through on real AWS.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":30,\"MaxRetentionDays\":365}")
        .when().put("/backup-vaults/" + LOCK_VAULT + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + LOCK_VAULT + "/recovery-points/" + rpArn)
        .then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", containsString("30"));

        // And the vault is still non-empty, so the refusal really did refuse rather than
        // reporting an error after deleting. A 400 with the row gone would be worse than
        // no check at all.
        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + LOCK_VAULT)
        .then().statusCode(200).body("NumberOfRecoveryPoints", equalTo(1));
    }

    @Test
    @Order(151)
    void aLockWithNoMinimumRetentionDoesNotBlockDeletion() {
        // The other direction, and the one that makes the test above worth trusting. A guard
        // measured only where it fires is indistinguishable from one that refuses everything,
        // and a lock that blocked every deletion would fail a Terraform destroy outright.
        // MinRetentionDays is optional, so a lock carrying none protects nothing by retention.
        String vault = "lock-no-minimum";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        String jid = given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(vault, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs").then().statusCode(200).extract().path("BackupJobId");

        awaitJobCompleted(jid);
        String rpArn = given().header("Authorization", AUTH)
        .when().get("/backup-jobs/" + jid).then().extract().path("RecoveryPointArn");

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MaxRetentionDays\":365}")
        .when().put("/backup-vaults/" + vault + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault + "/recovery-points/" + rpArn)
        .then().statusCode(204);
    }

    @Test
    @Order(152)
    void aBackupJobIsRefusedWhenItsLifecycleFallsOutsideTheLock() {
        // "Backup jobs will fail if the lifecycle policy of the backup plan is outside the
        // vault lock's retention period." Until this existed, getMaxRetentionDays had ZERO
        // readers anywhere in the tree: the ceiling was accepted, stored, and reported by
        // DescribeBackupVault while bounding nothing. A Terraform configuration pairing a lock
        // with a shorter plan lifecycle applied cleanly here and failed its first real backup.
        // LOCK_VAULT is locked at [30, 365] by the test above.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s",
                  "Lifecycle": {"DeleteAfterDays": 7}
                }
                """.formatted(LOCK_VAULT, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("minimum retention"));

        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s",
                  "Lifecycle": {"DeleteAfterDays": 4000}
                }
                """.formatted(LOCK_VAULT, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("maximum retention"));

        // Omitting the lifecycle entirely is refused too, and this one is worth spelling out
        // because the first version of the fix got it backwards and waved it through, on the
        // reasoning that indefinite retention cannot exceed a maximum. Retaining forever is the
        // LARGEST retention there is, so it exceeds every finite maximum: omitting the member
        // was a one-line bypass of the ceiling the lock exists to impose.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(LOCK_VAULT, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs")
        .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", containsString("DeleteAfterDays is required"));

        // Inside the window: accepted. The guard has to let a valid job through, or a locked
        // vault could never be backed up at all.
        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s",
                  "Lifecycle": {"DeleteAfterDays": 90}
                }
                """.formatted(LOCK_VAULT, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs").then().statusCode(200);
    }

    @Test
    @Order(156)
    void aLockWithOnlyAMinimumStillAcceptsAJobWithNoLifecycle() {
        // The other side of the rule above, and the reason it is scoped to a MAXIMUM. A floor is
        // satisfied by indefinite retention, so refusing an absent lifecycle here would refuse a
        // request AWS accepts -- the divergence this emulator exists to remove. lock-no-minimum
        // carries MaxRetentionDays only, so this needs its own vault with the inverse.
        String vault = "lock-only-minimum";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"MinRetentionDays\":30}")
        .when().put("/backup-vaults/" + vault + "/vault-lock").then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {
                  "BackupVaultName": "%s",
                  "ResourceArn": "%s",
                  "IamRoleArn": "%s"
                }
                """.formatted(vault, RESOURCE_ARN, IAM_ROLE))
        .when().put("/backup-jobs").then().statusCode(200);
    }

    @Test
    @Order(157)
    void subResourcesAreKeyedPerVaultIncarnationRatherThanPerName() {
        // Why the keying matters rather than what it does: the stores used to be keyed by vault
        // name, which made one key shared between every vault that ever held the name. A record
        // surviving a delete grafted an old policy onto the next vault, and cleanup for a deleted
        // vault could erase a live configuration belonging to the vault that had replaced it.
        //
        // Honest about what this test can and cannot show: it pins the sequential behaviour, and
        // the sequential behaviour was already correct once deleteBackupVault removed the
        // sub-resources. The races the keying removes need two requests interleaved and are not
        // reachable from a single-threaded HTTP test. What is reachable, and is asserted here, is
        // that a vault carries only the configuration applied to IT.
        String vault = "incarnation-keyed-vault";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\",\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + vault + "/notification-configuration").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/notification-configuration")
        .then().statusCode(200).body("SNSTopicArn", equalTo(TOPIC));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/notification-configuration")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(153)
    void anUnparseablePolicyIsRefusedRatherThanStored() {
        // The finding: the blank check accepted any other string, so `not a policy` was stored,
        // PUT reported success and GET returned it unchanged. An invalid policy looked applied.
        // That is the emulator being MORE permissive than AWS, the one direction that costs a
        // user real time: the configuration passes locally and the deploy is where they learn.
        for (String bad : new String[] {"not a policy", "{\"Version\":", "[]", "42", "\"a string\""}) {
            given().header("Authorization", AUTH).contentType("application/json")
                .body(Map.of("Policy", bad))
            .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        }
        // A real document still works, so the check refuses only what it should.
        given().header("Authorization", AUTH).contentType("application/json")
            .body(Map.of("Policy", POLICY))
        .when().put("/backup-vaults/" + SUB_VAULT + "/access-policy").then().statusCode(204);
    }

    @Test
    @Order(154)
    void aNonStringTopicArnIsRefusedRatherThanCoerced() {
        // asText() turns the JSON number 123 into the nonblank string "123", which passed every
        // present-and-nonblank check and was stored as the topic: an unusable configuration
        // that reads back as valid. Same coercion Greptile found in longOrNull, other direction.
        for (String bad : new String[] {"123", "true", "{}", "[]"}) {
            given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"SNSTopicArn\":" + bad + ",\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
            .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
            .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("SNSTopicArn"));
        }
        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\",\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + SUB_VAULT + "/notification-configuration")
        .then().statusCode(204);
    }

    @Test
    @Order(155)
    void aRecreatedVaultDoesNotInheritTheDeletedVaultsConfiguration() {
        // The stores are keyed by vault name and there is no transaction across the three, so
        // a leftover record grafts an old policy onto a new vault of the same name.
        //
        // Measured, and stated because the measurement is unflattering: this test still PASSES
        // with the create-side sweep removed, because the sequential path is already covered by
        // the deletes in deleteBackupVault. What it pins is the sequential guarantee, not the
        // fix. The concurrent interleaving the fix exists for -- a Put landing between the
        // sub-resource deletes and the vault delete -- is not reachable from a single-threaded
        // HTTP test, so no test here pins the sweep. Keeping it is a judgement that the race is
        // real rather than a claim that it is covered.
        String vault = "recycled-vault-name";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH).contentType("application/json")
            .body("{\"SNSTopicArn\":\"" + TOPIC + "\",\"BackupVaultEvents\":[\"BACKUP_JOB_COMPLETED\"]}")
        .when().put("/backup-vaults/" + vault + "/notification-configuration").then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/notification-configuration")
        .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

}
