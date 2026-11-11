package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.BatchGetNamedQueryResponse;
import software.amazon.awssdk.services.athena.model.CreateNamedQueryRequest;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;
import software.amazon.awssdk.services.athena.model.ListNamedQueriesResponse;
import software.amazon.awssdk.services.athena.model.NamedQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AthenaNamedQueryIntegrationTest {

    private static final String REGION = "eu-west-2";
    private static final AtomicLong ACCOUNTS = new AtomicLong(741680800000L);
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
    void sdkCreatesReadsUpdatesAndDeletesASavedQueryWithoutExecutingSql() {
        String id = client.createNamedQuery(request -> request.name("saved query").database("unregistered_db")
                .queryString("select * from a_table_that_does_not_exist").description("original")).namedQueryId();
        assertDoesNotThrow(() -> UUID.fromString(id));
        NamedQuery saved = client.getNamedQuery(request -> request.namedQueryId(id)).namedQuery();
        assertEquals("saved query", saved.name());
        assertEquals("unregistered_db", saved.database());
        assertEquals("select * from a_table_that_does_not_exist", saved.queryString());
        assertEquals("original", saved.description());
        assertEquals("primary", saved.workGroup());
        assertEquals(id, saved.namedQueryId());
        assertTrue(client.listQueryExecutions().queryExecutionIds().isEmpty());

        client.updateNamedQuery(request -> request.namedQueryId(id).name("renamed").queryString("select 2"));
        NamedQuery updated = client.getNamedQuery(request -> request.namedQueryId(id)).namedQuery();
        assertEquals("renamed", updated.name());
        assertEquals("select 2", updated.queryString());
        assertEquals("original", updated.description());
        assertEquals(saved.database(), updated.database());
        assertEquals(saved.workGroup(), updated.workGroup());
        client.updateNamedQuery(request -> request.namedQueryId(id).name("renamed")
                .queryString("select 2").description(""));
        assertEquals("", client.getNamedQuery(request -> request.namedQueryId(id)).namedQuery().description());

        client.deleteNamedQuery(request -> request.namedQueryId(id));
        assertTrue(client.listNamedQueries().namedQueryIds().isEmpty());
        InvalidRequestException missing = assertThrows(InvalidRequestException.class,
                () -> client.getNamedQuery(request -> request.namedQueryId(id)));
        assertTrue(missing.getMessage().contains("does not exist"));
        assertDoesNotThrow(() -> client.deleteNamedQuery(request -> request.namedQueryId(id)));
        assertThrows(InvalidRequestException.class, () -> client.updateNamedQuery(request -> request
                .namedQueryId(id).name("missing").queryString("select 1")));
    }

    @Test
    void retriesReturnTheOriginalIdAndRejectChangedParametersEvenAfterAnUpdate() {
        CreateNamedQueryRequest request = CreateNamedQueryRequest.builder().name("retry").database("db")
                .queryString("select 1").description("initial").clientRequestToken(UUID.randomUUID().toString()).build();
        String id = client.createNamedQuery(request).namedQueryId();
        assertEquals(id, client.createNamedQuery(request).namedQueryId());
        assertEquals(1, client.listNamedQueries().namedQueryIds().size());
        for (CreateNamedQueryRequest changed : List.of(request.toBuilder().name("other").build(),
                request.toBuilder().database("other").build(), request.toBuilder().queryString("select 2").build(),
                request.toBuilder().description("other").build(), request.toBuilder().workGroup("other").build())) {
            assertThrows(InvalidRequestException.class, () -> client.createNamedQuery(changed));
        }
        client.updateNamedQuery(update -> update.namedQueryId(id).name("updated").queryString("select 3"));
        assertEquals(id, client.createNamedQuery(request).namedQueryId());
        assertEquals("updated", client.getNamedQuery(get -> get.namedQueryId(id)).namedQuery().name());
        assertNotEquals(id, client.createNamedQuery(request.toBuilder()
                .clientRequestToken(UUID.randomUUID().toString()).build()).namedQueryId());
    }

    @Test
    void batchGetReturnsSavedQueriesAndPerIdErrorsForMissingQueries() {
        String first = create("primary");
        String second = create("primary");
        String missing = UUID.randomUUID().toString();
        BatchGetNamedQueryResponse response = client.batchGetNamedQuery(request -> request
                .namedQueryIds(first, missing, second));
        assertEquals(List.of(first, second), response.namedQueries().stream().map(NamedQuery::namedQueryId).toList());
        assertEquals(1, response.unprocessedNamedQueryIds().size());
        assertEquals(missing, response.unprocessedNamedQueryIds().getFirst().namedQueryId());
        assertNotNull(response.unprocessedNamedQueryIds().getFirst().errorCode());
        assertNotNull(response.unprocessedNamedQueryIds().getFirst().errorMessage());
        assertTrue(client.batchGetNamedQuery(request -> request.namedQueryIds(first)).unprocessedNamedQueryIds().isEmpty());
        assertThrows(InvalidRequestException.class,
                () -> client.batchGetNamedQuery(request -> request.namedQueryIds(List.of())));
        assertThrows(InvalidRequestException.class, () -> client.batchGetNamedQuery(request -> request
                .namedQueryIds(Collections.nCopies(51, first))));
    }

    @Test
    void sdkPagesAndPaginatorFilterByWorkGroupAndResumeAfterADeletedCursor() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create("primary"));
        }
        client.createWorkGroup(request -> request.name("other"));
        String other = create("other");
        List<String> paged = client.listNamedQueriesPaginator(request -> request.maxResults(1)).stream()
                .flatMap(page -> page.namedQueryIds().stream()).toList();
        assertEquals(ids.stream().sorted().toList(), paged);
        assertEquals(List.of(other), client.listNamedQueries(request -> request.workGroup("other")).namedQueryIds());

        ListNamedQueriesResponse first = client.listNamedQueries(request -> request.maxResults(1));
        assertNotNull(first.nextToken());
        client.deleteNamedQuery(request -> request.namedQueryId(first.namedQueryIds().getFirst()));
        ListNamedQueriesResponse last = client.listNamedQueries(request -> request.nextToken(first.nextToken()));
        assertEquals(paged.subList(1, 3), last.namedQueryIds());
        assertNull(last.nextToken());
        assertThrows(InvalidRequestException.class, () -> client.listNamedQueries(request -> request
                .workGroup("other").nextToken(first.nextToken())));
    }

    @Test
    void omittedZeroAndMaximumPageSizesAreBounded() {
        for (int i = 0; i < 51; i++) {
            create("primary");
        }
        for (Integer size : new Integer[]{null, 0, 50}) {
            ListNamedQueriesResponse first = client.listNamedQueries(request -> request.maxResults(size));
            assertEquals(50, first.namedQueryIds().size());
            assertNotNull(first.nextToken());
            ListNamedQueriesResponse last = client.listNamedQueries(request -> request.nextToken(first.nextToken()));
            assertEquals(1, last.namedQueryIds().size());
            assertNull(last.nextToken());
        }
    }

    @Test
    void queriesAndRetryTokensAreIsolatedByAccountAndRegion() {
        CreateNamedQueryRequest request = CreateNamedQueryRequest.builder().name("isolated").database("db")
                .queryString("select 1").clientRequestToken(UUID.randomUUID().toString()).build();
        String id = client.createNamedQuery(request).namedQueryId();
        try (AthenaClient anotherAccount = AwsTestClients.create(AthenaClient.builder(), "741680899999", Region.of(REGION));
             AthenaClient anotherRegion = AwsTestClients.create(AthenaClient.builder(), account, Region.US_WEST_2)) {
            for (AthenaClient isolated : List.of(anotherAccount, anotherRegion)) {
                assertTrue(isolated.listNamedQueries().namedQueryIds().isEmpty());
                assertThrows(InvalidRequestException.class, () -> isolated.getNamedQuery(get -> get.namedQueryId(id)));
                assertThrows(InvalidRequestException.class, () -> isolated.updateNamedQuery(update -> update
                        .namedQueryId(id).name("stolen").queryString("select 2")));
                isolated.deleteNamedQuery(delete -> delete.namedQueryId(id));
                assertNotEquals(id, isolated.createNamedQuery(request).namedQueryId());
            }
        }
        assertEquals("isolated", client.getNamedQuery(get -> get.namedQueryId(id)).namedQuery().name());
    }

    @Test
    void paginationTokensCannotCrossAccountRegionOrAction() {
        create("primary");
        create("primary");
        String token = client.listNamedQueries(request -> request.maxResults(1)).nextToken();
        try (AthenaClient anotherAccount = AwsTestClients.create(AthenaClient.builder(), "741680899998", Region.of(REGION));
             AthenaClient anotherRegion = AwsTestClients.create(AthenaClient.builder(), account, Region.US_WEST_2)) {
            for (AthenaClient isolated : List.of(anotherAccount, anotherRegion)) {
                assertThrows(InvalidRequestException.class,
                        () -> isolated.listNamedQueries(request -> request.nextToken(token)));
            }
        }
        assertThrows(InvalidRequestException.class,
                () -> client.listQueryExecutions(request -> request.nextToken(token)));
    }

    @Test
    void rejectsMissingWorkGroups() {
        assertThrows(InvalidRequestException.class, () -> create("missing"));
        assertThrows(InvalidRequestException.class,
                () -> client.listNamedQueries(request -> request.workGroup("missing")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"Name\":\"\"}", "{\"Name\":3}", "{\"Database\":\"\"}", "{\"Database\":[]}",
            "{\"QueryString\":\"\"}", "{\"Description\":\"\"}", "{\"WorkGroup\":\"bad group\"}",
            "{\"ClientRequestToken\":\"short\"}"
    })
    void createRejectsInvalidInput(String overrides) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        if (!overrides.equals("{}")) {
            body.put("Name", "valid").put("Database", "db").put("QueryString", "select 1")
                    .put("ClientRequestToken", "12345678901234567890123456789012");
            body.setAll((ObjectNode) mapper.readTree(overrides));
        }
        invalidRequest("CreateNamedQuery", body.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"MaxResults\":-1}", "{\"MaxResults\":51}", "{\"MaxResults\":1.5}",
            "{\"MaxResults\":\"1\"}", "{\"MaxResults\":2147483648}", "{\"NextToken\":false}",
            "{\"NextToken\":\"\"}", "{\"NextToken\":\"invalid\"}", "{\"WorkGroup\":4}"})
    void listRejectsInvalidInput(String body) {
        invalidRequest("ListNamedQueries", body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"NamedQueryIds\":[]}", "{\"NamedQueryIds\":\"id\"}",
            "{\"NamedQueryIds\":[null]}", "{\"NamedQueryIds\":[1]}", "{\"NamedQueryIds\":[\"\"]}"})
    void batchRejectsInvalidInput(String body) {
        invalidRequest("BatchGetNamedQuery", body);
    }

    @Test
    void rejectsInvalidIdsAndOversizedFields() {
        for (String action : List.of("GetNamedQuery", "DeleteNamedQuery", "UpdateNamedQuery")) {
            invalidRequest(action, "{}");
            invalidRequest(action, "{\"NamedQueryId\":\"has space\",\"Name\":\"n\",\"QueryString\":\"q\"}");
        }
        for (CreateNamedQueryRequest request : List.of(
                CreateNamedQueryRequest.builder().name("n".repeat(129)).database("db").queryString("q").build(),
                CreateNamedQueryRequest.builder().name("n").database("d".repeat(256)).queryString("q").build(),
                CreateNamedQueryRequest.builder().name("n").database("db").queryString("q".repeat(262145)).build(),
                CreateNamedQueryRequest.builder().name("n").database("db").queryString("q")
                        .description("d".repeat(1025)).build())) {
            assertThrows(InvalidRequestException.class, () -> client.createNamedQuery(request));
        }
        assertThrows(InvalidRequestException.class,
                () -> client.listNamedQueries(request -> request.nextToken("t".repeat(1025))));
    }

    private String create(String workGroup) {
        return client.createNamedQuery(request -> request.name("saved").database("db")
                .queryString("select 1").workGroup(workGroup)).namedQueryId();
    }

    private void invalidRequest(String action, String body) {
        given().header("X-Amz-Target", "AmazonAthena." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20261111/" + REGION
                        + "/athena/aws4_request, SignedHeaders=host, Signature=test")
                .contentType("application/x-amz-json-1.1").body(body)
                .when().post("/").then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }
}
