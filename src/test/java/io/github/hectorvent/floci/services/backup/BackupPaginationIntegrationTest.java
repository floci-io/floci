package io.github.hectorvent.floci.services.backup;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            service.deleteBackupVault(name, REGION);
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
