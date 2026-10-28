package io.github.hectorvent.floci.services.backup;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.backup.model.BackupRule;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.BackupException;
import software.amazon.awssdk.services.backup.model.BackupPlansListMember;
import software.amazon.awssdk.services.backup.model.BackupRuleInput;
import software.amazon.awssdk.services.backup.model.ListBackupPlansRequest;
import software.amazon.awssdk.services.backup.model.ListBackupPlansResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BackupListPlansPaginationIntegrationTest {

    private static final String ACCOUNT = "741664900001";
    private static final String REGION = "eu-north-1";
    private final String name = "bp-" + UUID.randomUUID();
    private final List<String> plans = new ArrayList<>();
    private BackupClient client;
    private int sequence;

    @Inject
    BackupService service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        client = AwsTestClients.create(BackupClient.builder(), ACCOUNT, Region.of(REGION));
        client.createBackupVault(request -> request.backupVaultName(name));
    }

    @AfterEach
    void cleanUp() {
        try {
            RequestScopes.runAs(ACCOUNT, REGION, () -> plans.forEach(service::deleteBackupPlan));
            client.deleteBackupVault(request -> request.backupVaultName(name));
        } finally {
            client.close();
        }
    }

    @Test
    void sdkPagesPlansByStableIdsAcrossNameChanges() {
        create();
        create();
        create();
        ListBackupPlansRequest request = ListBackupPlansRequest.builder().maxResults(1).build();
        ListBackupPlansResponse first = client.listBackupPlans(request);
        assertEquals(1, first.backupPlansList().size());
        BackupPlansListMember plan = first.backupPlansList().getFirst();
        assertTrue(plan.backupPlanName().startsWith(name + "-"));
        assertNotNull(plan.backupPlanArn());
        assertNotNull(plan.creationDate());
        assertNotNull(plan.versionId());
        assertNotNull(first.nextToken());
        client.updateBackupPlan(update -> update.backupPlanId(plan.backupPlanId()).backupPlan(body -> body
                .backupPlanName(name + "-renamed").rules(BackupRuleInput.builder().ruleName("daily")
                        .targetBackupVaultName(name).scheduleExpression("cron(0 12 * * ? *)").build())));
        ListBackupPlansResponse second = client.listBackupPlans(request.toBuilder().nextToken(first.nextToken()).build());
        assertNotNull(second.nextToken());
        ListBackupPlansResponse last = client.listBackupPlans(request.toBuilder().nextToken(second.nextToken()).build());
        assertNull(last.nextToken());
        assertEquals(plans.stream().sorted().toList(), List.of(first, second, last).stream()
                .flatMap(page -> page.backupPlansList().stream()).map(BackupPlansListMember::backupPlanId).toList());
    }

    @Test
    void includeDeletedIsBoundToTheListingToken() {
        create();
        create();
        String token = client.listBackupPlans(request -> request.maxResults(1)).nextToken();
        assertNotNull(token);
        assertEquals(1, client.listBackupPlans(request -> request.maxResults(1).includeDeleted(false)
                .nextToken(token)).backupPlansList().size());
        BackupException error = assertThrows(BackupException.class,
                () -> client.listBackupPlans(request -> request.maxResults(1).includeDeleted(true).nextToken(token)));
        assertEquals("InvalidParameterValueException", error.awsErrorDetails().errorCode());
        assertEquals(2, client.listBackupPlans(request -> request.includeDeleted(true)).backupPlansList().size());
    }

    @Test
    void retainedDeletionDatesAreFilteredBeforePagingAndIncludedInSummaries() {
        create();
        create();
        create();
        String deletedId = plans.getFirst();
        Instant deletion = Instant.ofEpochSecond(1720000000);
        RequestScopes.runAs(ACCOUNT, REGION,
                () -> service.getBackupPlan(deletedId).setDeletionDate(deletion.getEpochSecond()));
        assertEquals(2, client.listBackupPlans(request -> request.includeDeleted(false)).backupPlansList().size());
        ListBackupPlansResponse first = client.listBackupPlans(request -> request.maxResults(1));
        assertEquals(1, first.backupPlansList().size());
        assertNotNull(first.nextToken());
        ListBackupPlansResponse last = client.listBackupPlans(request -> request.maxResults(1).nextToken(first.nextToken()));
        assertNull(last.nextToken());
        assertEquals(plans.stream().filter(id -> !id.equals(deletedId)).sorted().toList(), List.of(first, last).stream()
                .flatMap(page -> page.backupPlansList().stream()).map(BackupPlansListMember::backupPlanId).toList());
        ListBackupPlansResponse all = client.listBackupPlans(request -> request.includeDeleted(true));
        assertEquals(3, all.backupPlansList().size());
        assertEquals(deletion, all.backupPlansList().stream().filter(plan -> plan.backupPlanId().equals(deletedId))
                .findFirst().orElseThrow().deletionDate());
    }

    @Test
    void vaultListingTokenCannotBeUsedForPlans() {
        List<String> vaults = new ArrayList<>();
        try {
            for (String vault : List.of(name + "-a", name + "-b")) {
                client.createBackupVault(request -> request.backupVaultName(vault));
                vaults.add(vault);
            }
            String token = client.listBackupVaults(request -> request.maxResults(1)).nextToken();
            assertNotNull(token);
            BackupException error = assertThrows(BackupException.class,
                    () -> client.listBackupPlans(request -> request.nextToken(token)));
            assertEquals("InvalidParameterValueException", error.awsErrorDetails().errorCode());
        } finally {
            vaults.forEach(vault -> client.deleteBackupVault(request -> request.backupVaultName(vault)));
        }
    }

    @ParameterizedTest
    @CsvSource({"741664900002,eu-north-1", "741664900001,us-west-2"})
    void listingsAndTokensStayWithinTheirAccountAndRegion(String account, String region) {
        create();
        create();
        try (BackupClient other = AwsTestClients.create(BackupClient.builder(), account, Region.of(region))) {
            other.createBackupVault(request -> request.backupVaultName(name));
            String otherId = create(other);
            try {
                assertEquals(plans.stream().sorted().toList(), client.listBackupPlans().backupPlansList().stream()
                        .map(BackupPlansListMember::backupPlanId).sorted().toList());
                assertEquals(List.of(otherId), other.listBackupPlans().backupPlansList().stream()
                        .map(BackupPlansListMember::backupPlanId).toList());
                String token = client.listBackupPlans(request -> request.maxResults(1)).nextToken();
                assertNotNull(token);
                BackupException error = assertThrows(BackupException.class,
                        () -> other.listBackupPlans(request -> request.nextToken(token)));
                assertEquals("InvalidParameterValueException", error.awsErrorDetails().errorCode());
            } finally {
                other.deleteBackupPlan(request -> request.backupPlanId(otherId));
                other.deleteBackupVault(request -> request.backupVaultName(name));
            }
        }
    }

    @Test
    void deletingTheCursorPlanDoesNotSkipTheNextPlan() {
        create();
        create();
        create();
        ListBackupPlansResponse first = client.listBackupPlans(request -> request.maxResults(1));
        assertNotNull(first.nextToken());
        String removed = first.backupPlansList().getFirst().backupPlanId();
        client.deleteBackupPlan(request -> request.backupPlanId(removed));
        plans.remove(removed);
        ListBackupPlansResponse last = client.listBackupPlans(request -> request.nextToken(first.nextToken()));
        assertEquals(plans.stream().sorted().toList(), last.backupPlansList().stream()
                .map(BackupPlansListMember::backupPlanId).toList());
        assertNull(last.nextToken());
    }

    @Test
    void omittedAndMaximumPageSizesReturnAtMostOneThousandPlans() {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            for (int i = 0; i <= 1000; i++) {
                BackupRule rule = new BackupRule();
                rule.setRuleName("daily");
                rule.setTargetBackupVaultName(name);
                rule.setScheduleExpression("cron(0 12 * * ? *)");
                plans.add(service.createBackupPlan(name + "-" + ++sequence, List.of(rule), null, REGION)
                        .getBackupPlanId());
            }
        });
        for (ListBackupPlansRequest request : List.of(ListBackupPlansRequest.builder().build(),
                ListBackupPlansRequest.builder().maxResults(1000).build())) {
            ListBackupPlansResponse first = client.listBackupPlans(request);
            assertEquals(1000, first.backupPlansList().size());
            assertNotNull(first.nextToken());
            ListBackupPlansResponse last = client.listBackupPlans(request.toBuilder().nextToken(first.nextToken()).build());
            assertEquals(1, last.backupPlansList().size());
            assertNull(last.nextToken());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "1.5", "abc", "2147483648"})
    void invalidPageSizesReturnAwsErrors(String size) {
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261027/" + REGION
                        + "/backup/aws4_request").queryParam("maxResults", size)
                .get("/backup/plans/").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token!", "MQ=="})
    void malformedTokensReturnAwsErrors(String token) {
        BackupException error = assertThrows(BackupException.class,
                () -> client.listBackupPlans(request -> request.nextToken(token)));
        assertEquals(400, error.statusCode());
        assertEquals("InvalidParameterValueException", error.awsErrorDetails().errorCode());
    }

    @Test
    void emptyListingHasNoContinuationToken() {
        ListBackupPlansResponse result = client.listBackupPlans(request -> request.maxResults(1));
        assertTrue(result.backupPlansList().isEmpty());
        assertNull(result.nextToken());
    }

    private void create() {
        plans.add(create(client));
    }

    private String create(BackupClient sdk) {
        String planName = name + "-" + ++sequence;
        return sdk.createBackupPlan(request -> request.backupPlan(plan -> plan.backupPlanName(planName)
                .rules(BackupRuleInput.builder().ruleName("daily").targetBackupVaultName(name)
                        .scheduleExpression("cron(0 12 * * ? *)").build()))).backupPlanId();
    }

}
