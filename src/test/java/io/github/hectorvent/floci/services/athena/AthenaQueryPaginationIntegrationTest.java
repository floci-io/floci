package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;
import software.amazon.awssdk.services.athena.model.ListQueryExecutionsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AthenaQueryPaginationIntegrationTest {
    private static final String REGION = "eu-west-2";
    private static final AtomicLong ACCOUNTS = new AtomicLong(741680600000L);
    private String account;
    private AthenaClient client;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void openClient() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        client = AwsTestClients.create(AthenaClient.builder(), account, Region.of(REGION));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void sdkPagesAndPaginatorReturnOnlyTheRequestedWorkGroupWithoutDuplicates() {
        List<String> ids = createQueries(3, "primary");
        client.createWorkGroup(request -> request.name("other"));
        createQueries(1, "other");
        List<String> seen = new ArrayList<>();
        String token = null;
        for (int i = 0; i < 3; i++) {
            String current = token;
            ListQueryExecutionsResponse page = client.listQueryExecutions(request -> request
                    .maxResults(1).nextToken(current));
            assertEquals(1, page.queryExecutionIds().size());
            seen.addAll(page.queryExecutionIds());
            token = page.nextToken();
            if (i < 2) {
                assertNotNull(token);
            }
        }
        assertNull(token);
        assertEquals(ids.stream().sorted().toList(), seen);
        assertEquals(seen, client.listQueryExecutionsPaginator(request -> request.maxResults(1))
                .stream().flatMap(page -> page.queryExecutionIds().stream()).toList());
        assertEquals(1, client.listQueryExecutions(request -> request.workGroup("other")).queryExecutionIds().size());
    }

    @Test
    void omittedZeroAndMaximumSizesUseABoundedPage() {
        createQueries(51, "primary");
        for (Integer size : new Integer[]{null, 0, 50}) {
            ListQueryExecutionsResponse first = client.listQueryExecutions(request -> request.maxResults(size));
            assertEquals(50, first.queryExecutionIds().size());
            assertNotNull(first.nextToken());
            ListQueryExecutionsResponse last = client.listQueryExecutions(request -> request
                    .nextToken(first.nextToken()).maxResults(50));
            assertEquals(1, last.queryExecutionIds().size());
            assertNull(last.nextToken());
            assertFalse(first.queryExecutionIds().contains(last.queryExecutionIds().getFirst()));
        }
    }

    @Test
    void tokensCannotCrossWorkGroupAccountOrRegion() {
        createQueries(2, "primary");
        client.createWorkGroup(request -> request.name("other"));
        createQueries(2, "other");
        String token = client.listQueryExecutions(request -> request.maxResults(1)).nextToken();
        assertNotNull(token);
        assertThrows(InvalidRequestException.class, () -> client.listQueryExecutions(request -> request
                .workGroup("other").maxResults(1).nextToken(token)));
        try (AthenaClient other = AwsTestClients.create(AthenaClient.builder(), "741680699999", Region.of(REGION));
             AthenaClient region = AwsTestClients.create(AthenaClient.builder(), account, Region.US_EAST_1)) {
            assertTrue(other.listQueryExecutions().queryExecutionIds().isEmpty());
            assertThrows(InvalidRequestException.class, () -> other.listQueryExecutions(request -> request.nextToken(token)));
            assertThrows(InvalidRequestException.class, () -> region.listQueryExecutions(request -> request.nextToken(token)));
        }
    }

    @Test
    void emptyListingOmitsContinuationTokenAndNullFieldsUseDefaults() {
        ListQueryExecutionsResponse page = client.listQueryExecutions();
        assertTrue(page.queryExecutionIds().isEmpty());
        assertNull(page.nextToken());
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", authorization())
                .header("X-Amz-Target", "AmazonAthena.ListQueryExecutions")
                .body("{\"MaxResults\":null,\"NextToken\":null,\"WorkGroup\":null}")
                .post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"MaxResults\":-1}", "{\"MaxResults\":51}",
            "{\"MaxResults\":4294967297}", "{\"MaxResults\":1.5}", "{\"MaxResults\":\"1\"}",
            "{\"MaxResults\":true}", "{\"MaxResults\":{}}", "{\"MaxResults\":[]}",
            "{\"NextToken\":\"\"}", "{\"NextToken\":\"%%%\"}", "{\"NextToken\":1}",
            "{\"NextToken\":true}", "{\"NextToken\":{}}", "{\"NextToken\":[]}",
            "{\"WorkGroup\":\"\"}", "{\"WorkGroup\":\"wrong:name\"}", "{\"WorkGroup\":1}",
            "{\"WorkGroup\":\"missing\"}"})
    void invalidPaginationInputsReturnAwsErrors(String body) {
        invalid(body);
    }

    @Test
    void oversizedTokenIsRejected() {
        invalid("{\"NextToken\":\"" + "A".repeat(1025) + "\"}");
    }

    private List<String> createQueries(int count, String workGroup) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(client.startQueryExecution(request -> request.queryString("SELECT 1").workGroup(workGroup))
                    .queryExecutionId());
        }
        return ids;
    }

    private void invalid(String body) {
        given().contentType("application/x-amz-json-1.1")
                .header("Authorization", authorization())
                .header("X-Amz-Target", "AmazonAthena.ListQueryExecutions")
                .body(body).post("/").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    private String authorization() {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261028/" + REGION + "/athena/aws4_request";
    }
}
