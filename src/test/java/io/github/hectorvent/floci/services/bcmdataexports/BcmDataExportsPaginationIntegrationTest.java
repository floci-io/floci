package io.github.hectorvent.floci.services.bcmdataexports;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bcmdataexports.BcmDataExportsClient;
import software.amazon.awssdk.services.bcmdataexports.model.ListExecutionsResponse;
import software.amazon.awssdk.services.bcmdataexports.model.ListExportsRequest;
import software.amazon.awssdk.services.bcmdataexports.model.ListExportsResponse;
import software.amazon.awssdk.services.bcmdataexports.model.ValidationException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class BcmDataExportsPaginationIntegrationTest {

    private static final String ACCOUNT = "741660000001";
    private static final String OTHER_ACCOUNT = "741660000002";
    private static final String TARGET = "AWSBillingAndCostManagementDataExports.";

    @Inject
    StorageFactory storageFactory;

    @TestHTTPResource
    URI endpoint;

    private final List<StoredKey> storedKeys = new ArrayList<>();
    private AccountAwareStorageBackend<Export> exports;
    private AccountAwareStorageBackend<ExportExecution> executions;
    private BcmDataExportsClient client;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        exports = storageFactory.create("bcmdataexports", "bcm-exports.json",
                new TypeReference<Map<String, Export>>() {});
        executions = storageFactory.create("bcmdataexports", "bcm-executions.json",
                new TypeReference<Map<String, ExportExecution>>() {});
        client = client(ACCOUNT);
    }

    @AfterEach
    void cleanUp() {
        client.close();
        for (StoredKey key : storedKeys) {
            if (key.execution()) {
                executions.deleteForAccount(key.account(), key.key());
            } else {
                exports.deleteForAccount(key.account(), key.key());
            }
        }
    }

    @Test
    void sdkPaginatesExportsWithoutRepeatingOrLosingItems() {
        List<String> expected = List.of(export(ACCOUNT, "a"), export(ACCOUNT, "b"), export(ACCOUNT, "c"));
        ListExportsResponse first = client.listExports(request -> request.maxResults(1));
        assertEquals(1, first.exports().size());
        assertNotNull(first.nextToken());
        List<String> actual = client.listExportsPaginator(request -> request.maxResults(1)).stream()
                .flatMap(page -> page.exports().stream()).map(item -> item.exportArn()).toList();
        assertEquals(expected, actual);
        ListExportsResponse last = client.listExports(request -> request.maxResults(300).nextToken(first.nextToken()));
        assertEquals(2, last.exports().size());
        assertNull(last.nextToken());
    }

    @Test
    void sdkPaginatesExecutionsWithinTheRequestedExport() {
        String arn = export(ACCOUNT, "executions");
        List<String> expected = List.of(execution(arn, "a"), execution(arn, "b"), execution(arn, "c"));
        execution(export(ACCOUNT, "other"), "unrelated");
        ListExecutionsResponse first = client.listExecutions(request -> request.exportArn(arn).maxResults(1));
        assertEquals(1, first.executions().size());
        assertNotNull(first.nextToken());
        List<String> actual = client.listExecutionsPaginator(request -> request.exportArn(arn).maxResults(1)).stream()
                .flatMap(page -> page.executions().stream()).map(item -> item.executionId()).toList();
        assertEquals(expected, actual);
        assertNull(client.listExecutions(request -> request.exportArn(arn).maxResults(300)
                .nextToken(first.nextToken())).nextToken());
    }

    @Test
    void omittedAndMaximumPageSizesAreBoundedAt300() {
        String arn = export(ACCOUNT, "defaults");
        for (int i = 0; i < 300; i++) {
            export(ACCOUNT, "bulk-" + i);
        }
        for (int i = 0; i < 301; i++) {
            execution(arn, "exec-" + i);
        }
        ListExportsResponse exportPage = client.listExports(ListExportsRequest.builder().build());
        assertEquals(300, exportPage.exports().size());
        assertNotNull(exportPage.nextToken());
        assertEquals(1, client.listExports(request -> request.maxResults(300).nextToken(exportPage.nextToken())).exports().size());
        ListExecutionsResponse executionPage = client.listExecutions(request -> request.exportArn(arn));
        assertEquals(300, executionPage.executions().size());
        assertNotNull(executionPage.nextToken());
        assertEquals(1, client.listExecutions(request -> request.exportArn(arn).maxResults(300)
                .nextToken(executionPage.nextToken())).executions().size());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 301})
    void sdkRejectsOutOfRangeLimitsForBothActions(int limit) {
        String arn = export(ACCOUNT, "limits");
        assertEquals(400, assertThrows(ValidationException.class,
                () -> client.listExports(request -> request.maxResults(limit))).statusCode());
        assertEquals(400, assertThrows(ValidationException.class,
                () -> client.listExecutions(request -> request.exportArn(arn).maxResults(limit))).statusCode());
    }

    @Test
    void tokensCannotCrossAccountsActionsOrExports() {
        String arn = export(ACCOUNT, "a");
        String otherArn = export(ACCOUNT, "b");
        execution(arn, "a");
        execution(arn, "b");
        execution(otherArn, "a");
        String exportToken = client.listExports(request -> request.maxResults(1)).nextToken();
        assertNotNull(exportToken);
        assertThrows(ValidationException.class, () -> client.listExecutions(request -> request.exportArn(arn).nextToken(exportToken)));
        export(OTHER_ACCOUNT, "other-account");
        try (BcmDataExportsClient other = client(OTHER_ACCOUNT)) {
            assertThrows(ValidationException.class, () -> other.listExports(request -> request.nextToken(exportToken)));
        }
        String executionToken = client.listExecutions(request -> request.exportArn(arn).maxResults(1)).nextToken();
        assertNotNull(executionToken);
        assertThrows(ValidationException.class, () -> client.listExecutions(request -> request.exportArn(otherArn).nextToken(executionToken)));
    }

    @Test
    void deletedCursorItemsDoNotPreventResumingEitherList() {
        String firstArn = export(ACCOUNT, "a");
        String secondArn = export(ACCOUNT, "b");
        ListExportsResponse first = client.listExports(request -> request.maxResults(1));
        assertNotNull(first.nextToken());
        exports.deleteForAccount(ACCOUNT, firstArn);
        assertEquals(secondArn, client.listExports(request -> request.nextToken(first.nextToken())).exports().getFirst().exportArn());
        execution(secondArn, "a");
        execution(secondArn, "b");
        ListExecutionsResponse executionPage = client.listExecutions(request -> request.exportArn(secondArn).maxResults(1));
        assertNotNull(executionPage.nextToken());
        executions.deleteForAccount(ACCOUNT, secondArn + "::a");
        assertEquals("b", client.listExecutions(request -> request.exportArn(secondArn)
                .nextToken(executionPage.nextToken())).executions().getFirst().executionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "1.5", "\"1\"", "{}", "[]"})
    void malformedLimitShapesAreRejectedBeforeCoercion(String value) {
        String arn = export(ACCOUNT, "shape");
        raw("ListExports", "{\"MaxResults\":" + value + "}", 400);
        raw("ListExecutions", "{\"ExportArn\":\"" + arn + "\",\"MaxResults\":" + value + "}", 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"%%%%\"", "17", "{}", "[]"})
    void invalidTokenValuesAreRejected(String value) {
        String arn = export(ACCOUNT, "token");
        raw("ListExports", "{\"NextToken\":" + value + "}", 400);
        raw("ListExecutions", "{\"ExportArn\":\"" + arn + "\",\"NextToken\":" + value + "}", 400);
    }

    @Test
    void oversizedTokensAreRejectedEvenWhenTheirDecodedScopeMatches() {
        String arn = export(ACCOUNT, "long-token");
        String exportToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("ListExports:" + ACCOUNT + ":" + "x".repeat(7000)).getBytes(StandardCharsets.UTF_8));
        String executionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("ListExecutions:" + ACCOUNT + ":" + arn + ":" + "x".repeat(7000)).getBytes(StandardCharsets.UTF_8));
        raw("ListExports", "{\"NextToken\":\"" + exportToken + "\"}", 400);
        raw("ListExecutions", "{\"ExportArn\":\"" + arn + "\",\"NextToken\":\"" + executionToken + "\"}", 400);
    }

    @Test
    void emptyAndFinalPagesOmitNextTokenAndAcceptAnEmptyInputToken() {
        assertFalse(raw("ListExports", "{\"NextToken\":\"\"}", 200).containsKey("NextToken"));
        String arn = export(ACCOUNT, "final");
        assertFalse(raw("ListExports", "{}", 200).containsKey("NextToken"));
        assertFalse(raw("ListExecutions", "{\"ExportArn\":\"" + arn + "\"}", 200).containsKey("NextToken"));
    }

    private Map<String, Object> raw(String action, String body, int status) {
        Map<String, Object> response = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", TARGET + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/bcm-data-exports/aws4_request")
                .body(body).when().post("/").then().statusCode(status).extract().jsonPath().getMap("");
        if (status == 400) {
            assertEquals("ValidationException", response.get("__type"));
        }
        return response;
    }

    private String export(String account, String name) {
        String arn = "arn:aws:bcm-data-exports:us-east-1:" + account + ":export/" + name + "-" + UUID.randomUUID();
        Export export = new Export();
        export.setExportArn(arn);
        export.setName(name);
        export.setOwnerAccountId(account);
        export.setExportStatus("HEALTHY");
        export.setCreatedAt(1_700_000_000_000L);
        export.setLastUpdatedAt(1_700_000_000_000L);
        exports.putForAccount(account, arn, export);
        storedKeys.add(new StoredKey(account, arn, false));
        return arn;
    }

    private String execution(String arn, String id) {
        ExportExecution execution = new ExportExecution();
        execution.setExportArn(arn);
        execution.setExecutionId(id);
        execution.setCreatedAt(1_700_000_000_000L);
        execution.setExportStatus("DELIVERY_SUCCESS");
        executions.putForAccount(ACCOUNT, arn + "::" + id, execution);
        storedKeys.add(new StoredKey(ACCOUNT, arn + "::" + id, true));
        return id;
    }

    private BcmDataExportsClient client(String account) {
        return BcmDataExportsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"))).build();
    }

    private record StoredKey(String account, String key, boolean execution) {}
}
