package io.floci.aws.services.backup;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.backup.model.RecoveryPoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BackupPaginationIntegrationTest {

    private static final String REGION = "ap-south-2";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261025/ap-south-2/backup/aws4_request";
    private static final String OTHER_REGION_AUTH = "AWS4-HMAC-SHA256 Credential=test/20261025/ap-southeast-4/backup/aws4_request";

    @Inject BackupService service;

    private final List<String> vaultNames = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String name : vaultNames) {
            try {
                // Delete recovery points first so the vault can be removed.
                PaginatedResult<RecoveryPoint> points = service.listRecoveryPointsByBackupVault(name, REGION, null, null);
                for (RecoveryPoint rp : points.items()) {
                    try { service.deleteRecoveryPoint(name, rp.getRecoveryPointArn(), REGION); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            try { service.deleteBackupVault(name, REGION); } catch (Exception ignored) {}
        }
    }

    @Test
    void listBackupVaultsHonorsMaxResultsAndResumes() {
        createVault("page-c");
        createVault("page-a");
        createVault("page-b");

        JsonPath first = given().header("Authorization", AUTH).queryParam("maxResults", 1)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("page-a"))
                .body("BackupVaultList[0]", not(hasKey("FlociVaultId")))
                .body("BackupVaultList[0].VaultType", equalTo("BACKUP_VAULT"))
                .extract().jsonPath();
        String token = first.getString("NextToken");
        assertNotNull(token);

        given().header("Authorization", AUTH).queryParam("maxResults", 2).queryParam("nextToken", token)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("page-b", "page-c"))
                .body("$", not(hasKey("NextToken")));
    }

    @Test
    void maximumAndOmittedPageSizesReturnAtMostOneThousandVaults() {
        for (int i = 0; i < 1001; i++) {
            String name = "boundary-%04d".formatted(i);
            service.createBackupVault(name, null, null, Map.of(), REGION);
            vaultNames.add(name);
        }

        for (Map<String, ?> params : List.of(Map.<String, Object>of(), Map.of("maxResults", 1000))) {
            String token = given().header("Authorization", AUTH).queryParams(params)
                    .get("/backup-vaults/").then().statusCode(200)
                    .body("BackupVaultList", hasSize(1000))
                    .extract().path("NextToken");
            assertNotNull(token);
            given().header("Authorization", AUTH).queryParam("nextToken", token)
                    .get("/backup-vaults/").then().statusCode(200)
                    .body("BackupVaultList.BackupVaultName", contains("boundary-1000"))
                    .body("$", not(hasKey("NextToken")));
        }
    }

    private List<String> startThreeJobs(String vault) {
        String iamRole = "arn:aws:iam::000000000000:role/backup-role";
        String resourceArn = "arn:aws:dynamodb:" + REGION + ":000000000000:table/my-table";
        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String jobId = given().header("Authorization", AUTH).contentType("application/json")
                    .body("{\"BackupVaultName\":\"%s\",\"ResourceArn\":\"%s\",\"IamRoleArn\":\"%s\"}"
                            .formatted(vault, resourceArn, iamRole))
                    .put("/backup-jobs").then().statusCode(200)
                    .extract().path("BackupJobId");
            jobIds.add(jobId);
            awaitLastStatus(
                    () -> given().header("Authorization", AUTH).get("/backup-jobs/" + jobId)
                            .then().statusCode(200).extract().jsonPath(),
                    jp -> jp.getString("State"),
                    Duration.ofSeconds(10), "COMPLETED");
        }
        return jobIds;
    }

    @Test
    void listBackupJobsPagesThroughJobs() {
        String vault = "jobs-page-vault";
        createVault(vault);
        startThreeJobs(vault);

        pageThrough("/backup-jobs/", Map.of("byBackupVaultName", vault, "maxResults", 1),
                "BackupJobs.BackupJobId", "each page must hold exactly one job");
    }

    @Test
    void listRecoveryPointsByBackupVaultPagesThroughPoints() {
        String vault = "rp-page-vault";
        createVault(vault);
        startThreeJobs(vault);

        pageThrough("/backup-vaults/" + vault + "/recovery-points/",
                Map.of("maxResults", 1), "RecoveryPoints.RecoveryPointArn",
                "each page must hold exactly one recovery point");
    }


    private void pageThrough(String path, Map<String, ?> query, String listField, String sizeMessage) {
        List<String> collected = new ArrayList<>();
        int pages = 0;
        String token = null;
        do {
            RequestSpecification req = given().header("Authorization", AUTH).queryParams(query);
            if (token != null) req = req.queryParam("nextToken", token);
            JsonPath page = req.get(path).then().statusCode(200).extract().jsonPath();
            List<String> items = page.getList(listField);
            assertNotNull(items);
            assertEquals(1, items.size(), sizeMessage);
            collected.addAll(items);
            token = page.getString("NextToken");
            pages++;
        } while (token != null);
        assertEquals(3, pages, "expected three pages");
        assertEquals(3, collected.size());
        assertEquals(3, new HashSet<>(collected).size(), "no duplicate items across pages");
    }

    @Test
    void emptyRegionOmitsNextToken() {
        given().header("Authorization", OTHER_REGION_AUTH).queryParam("maxResults", 1)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList", empty())
                .body("$", not(hasKey("NextToken")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "1.5", "abc", "2147483648"})
    void invalidPageSizesReturnAwsError(String size) {
        given().header("Authorization", AUTH).queryParam("maxResults", size)
                .get("/backup-vaults/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token!", "MQ=="})
    void invalidTokensReturnAwsError(String token) {
        given().header("Authorization", AUTH).queryParam("nextToken", token)
                .get("/backup-vaults/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void vaultTypeFilterIsAppliedBeforePagingAndBoundToToken() {
        createAirGappedVault("filter-a");
        createVault("filter-b");
        createAirGappedVault("filter-c");
        createVault("filter-d");

        String token = given().header("Authorization", AUTH).queryParam("maxResults", 1)
                .queryParam("vaultType", "BACKUP_VAULT")
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("filter-b"))
                .extract().path("NextToken");
        assertNotNull(token);
        given().header("Authorization", AUTH).queryParam("maxResults", 1)
                .queryParam("vaultType", "BACKUP_VAULT").queryParam("nextToken", token)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("filter-d"))
                .body("$", not(hasKey("NextToken")));

        given().header("Authorization", AUTH).queryParam("vaultType", "LOGICALLY_AIR_GAPPED_BACKUP_VAULT")
                .queryParam("nextToken", token).get("/backup-vaults/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        given().header("Authorization", AUTH).queryParam("nextToken", token)
                .get("/backup-vaults/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        given().header("Authorization", OTHER_REGION_AUTH).queryParam("vaultType", "BACKUP_VAULT")
                .queryParam("nextToken", token).get("/backup-vaults/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void continuationTokenCannotCrossAccounts() {
        String firstAccount = "111111111111";
        String secondAccount = "222222222222";
        String prefix = "account-pages-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> firstNames = List.of(prefix + "-a", prefix + "-b");
        List<String> secondNames = List.of(prefix + "-c", prefix + "-d");

        RequestScopes.runAs(firstAccount, REGION, () -> firstNames.forEach(name ->
                service.createBackupVault(name, null, null, Map.of(), REGION)));
        RequestScopes.runAs(secondAccount, REGION, () -> secondNames.forEach(name ->
                service.createBackupVault(name, null, null, Map.of(), REGION)));
        try {
            String token = RequestScopes.callAs(firstAccount, REGION, () ->
                    service.listBackupVaults(REGION, null, 1, null).nextToken());
            assertNotNull(token);

            AwsException error = assertThrows(AwsException.class, () ->
                    RequestScopes.callAs(secondAccount, REGION, () ->
                            service.listBackupVaults(REGION, null, 1, token)));
            assertEquals("InvalidParameterValueException", error.getErrorCode());
        } finally {
            RequestScopes.runAs(firstAccount, REGION, () -> firstNames.forEach(name ->
                    service.deleteBackupVault(name, REGION)));
            RequestScopes.runAs(secondAccount, REGION, () -> secondNames.forEach(name ->
                    service.deleteBackupVault(name, REGION)));
        }
    }

    @Test
    void removingPreviousPageDoesNotSkipTheNextVault() {
        createVault("delete-a");
        createVault("delete-b");
        createVault("delete-c");
        String token = given().header("Authorization", AUTH).queryParam("maxResults", 1)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("delete-a"))
                .extract().path("NextToken");
        assertNotNull(token);
        given().header("Authorization", AUTH).delete("/backup-vaults/delete-a").then().statusCode(204);
        vaultNames.remove("delete-a");

        given().header("Authorization", AUTH).queryParam("nextToken", token)
                .get("/backup-vaults/").then().statusCode(200)
                .body("BackupVaultList.BackupVaultName", contains("delete-b", "delete-c"))
                .body("$", not(hasKey("NextToken")));
    }

    private void createAirGappedVault(String name) {
        given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"MinRetentionDays\":7,\"MaxRetentionDays\":30}")
                .put("/logically-air-gapped-backup-vaults/" + name).then().statusCode(200);
        vaultNames.add(name);
    }

    private void createVault(String name) {
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
                .put("/backup-vaults/" + name).then().statusCode(200);
        vaultNames.add(name);
    }
}
