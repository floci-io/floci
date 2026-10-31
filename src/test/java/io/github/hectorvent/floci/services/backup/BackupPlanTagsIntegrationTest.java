package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.backup.model.BackupPlan;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.BackupRuleInput;
import software.amazon.awssdk.services.backup.model.CreateBackupPlanResponse;
import software.amazon.awssdk.services.backup.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BackupPlanTagsIntegrationTest {
    private static final String ACCOUNT = "741677900001";
    private static final String REGION = "eu-west-2";
    private final String name = "tags-" + UUID.randomUUID();
    private final List<String> plans = new ArrayList<>();
    private BackupClient client;

    @Inject BackupService service;
    @Inject ObjectMapper mapper;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        client = AwsTestClients.create(BackupClient.builder(), ACCOUNT, Region.of(REGION));
        client.createBackupVault(request -> request.backupVaultName(name).backupVaultTags(Map.of("vault", "kept")));
    }

    @AfterEach
    void cleanUp() {
        try {
            plans.forEach(id -> client.deleteBackupPlan(request -> request.backupPlanId(id)));
            client.deleteBackupVault(request -> request.backupVaultName(name));
        } finally {
            client.close();
        }
    }

    @Test
    void sdkCanTagReadReplaceAndRemoveTagsFromAnExistingPlan() {
        CreateBackupPlanResponse plan = create(Map.of());
        String arn = plan.backupPlanArn();
        assertTrue(client.listTags(request -> request.resourceArn(arn)).tags().isEmpty());
        assertDoesNotThrow(() -> client.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "dev", "keep", "yes"))));
        assertEquals(Map.of("env", "dev", "keep", "yes"), client.listTags(request -> request.resourceArn(arn)).tags());
        client.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "prod", "added", "yes")));
        assertEquals(Map.of("env", "prod", "keep", "yes", "added", "yes"),
                client.listTags(request -> request.resourceArn(arn)).tags());
        client.untagResource(request -> request.resourceArn(arn).tagKeyList("keep", "absent"));
        assertEquals(Map.of("env", "prod", "added", "yes"), client.listTags(request -> request.resourceArn(arn)).tags());
        client.untagResource(request -> request.resourceArn(arn).tagKeyList("env", "added"));
        assertTrue(client.listTags(request -> request.resourceArn(arn)).tags().isEmpty());
        client.deleteBackupPlan(request -> request.backupPlanId(plan.backupPlanId()));
        plans.remove(plan.backupPlanId());
        assertThrows(ResourceNotFoundException.class, () -> client.listTags(request -> request.resourceArn(arn)));
        assertThrows(ResourceNotFoundException.class, () -> client.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "new"))));
    }

    @Test
    void creationTagsSurvivePlanUpdatesAndStoredJsonRoundTrips() throws Exception {
        CreateBackupPlanResponse plan = create(Map.of("initial", "kept"));
        String arn = plan.backupPlanArn();
        assertEquals(Map.of("initial", "kept"), client.listTags(request -> request.resourceArn(arn)).tags());
        client.updateBackupPlan(request -> request.backupPlanId(plan.backupPlanId()).backupPlan(body -> body
                .backupPlanName(name + "-renamed").rules(rule())));
        assertEquals(Map.of("initial", "kept"), client.listTags(request -> request.resourceArn(arn)).tags());
        BackupPlan stored = RequestScopes.callAs(ACCOUNT, REGION, () -> service.getBackupPlan(plan.backupPlanId()));
        String json = mapper.writeValueAsString(stored);
        assertEquals("kept", mapper.readTree(mapper.writeValueAsString(mapper.readValue(json, BackupPlan.class)))
                .path("Tags").path("initial").asText());
    }

    @Test
    void anotherAccountOrNonMatchingArnCannotTagThePlan() {
        CreateBackupPlanResponse plan = create(Map.of());
        String arn = plan.backupPlanArn();
        try (BackupClient other = AwsTestClients.create(BackupClient.builder(), "741677900002", Region.of(REGION))) {
            assertThrows(ResourceNotFoundException.class, () -> other.listTags(request -> request.resourceArn(arn)));
            assertThrows(ResourceNotFoundException.class, () -> other.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "wrong"))));
            assertThrows(ResourceNotFoundException.class, () -> other.untagResource(request -> request.resourceArn(arn).tagKeyList("env")));
        }
        String wrongRegion = arn.replace(":" + REGION + ":", ":us-east-1:");
        assertThrows(ResourceNotFoundException.class, () -> client.tagResource(request -> request.resourceArn(wrongRegion).tags(Map.of("env", "wrong"))));
        assertTrue(client.listTags(request -> request.resourceArn(arn)).tags().isEmpty());
    }

    @Test
    void vaultTaggingKeepsItsExistingBehavior() {
        String arn = client.describeBackupVault(request -> request.backupVaultName(name)).backupVaultArn();
        client.tagResource(request -> request.resourceArn(arn).tags(Map.of("env", "dev")));
        assertEquals(Map.of("vault", "kept", "env", "dev"), client.listTags(request -> request.resourceArn(arn)).tags());
        client.untagResource(request -> request.resourceArn(arn).tagKeyList("env"));
        assertEquals(Map.of("vault", "kept"), client.listTags(request -> request.resourceArn(arn)).tags());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"env\":123}", "{\"env\":true}", "{\"env\":null}",
            "{\"env\":{}}", "{\"env\":[]}", "[]", "123", "\"invalid\""})
    void malformedCreationTagsAreRejectedWithoutCreatingAPlan(String tags) {
        Response response = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261028/"
                        + REGION + "/backup/aws4_request")
                .contentType("application/json")
                .body("""
                        {"BackupPlan":{"BackupPlanName":"%s","Rules":[
                          {"RuleName":"daily","TargetBackupVaultName":"%s"}]},"BackupPlanTags":%s}
                        """.formatted(name, name, tags))
                .put("/backup/plans/");
        if (response.statusCode() == 200) {
            plans.add(response.jsonPath().getString("BackupPlanId"));
        }
        assertEquals(400, response.statusCode());
        assertEquals("InvalidParameterValueException", response.jsonPath().getString("__type"));
        assertTrue(client.listBackupPlans().backupPlansList().stream()
                .noneMatch(plan -> name.equals(plan.backupPlanName())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"Tags\":null}"})
    void legacyStoredPlansHaveMutableEmptyTags(String json) throws Exception {
        BackupPlan restored = mapper.readValue(json, BackupPlan.class);
        assertTrue(restored.getTags().isEmpty());
        restored.getTags().put("after-restart", "kept");
        assertEquals("kept", mapper.readTree(mapper.writeValueAsString(restored))
                .path("Tags").path("after-restart").asText());
    }

    private CreateBackupPlanResponse create(Map<String, String> tags) {
        CreateBackupPlanResponse plan = client.createBackupPlan(request -> request.backupPlan(body -> body
                .backupPlanName(name).rules(rule())).backupPlanTags(tags));
        plans.add(plan.backupPlanId());
        return plan;
    }

    private BackupRuleInput rule() {
        return BackupRuleInput.builder().ruleName("daily").targetBackupVaultName(name)
                .scheduleExpression("cron(0 12 * * ? *)").build();
    }
}
