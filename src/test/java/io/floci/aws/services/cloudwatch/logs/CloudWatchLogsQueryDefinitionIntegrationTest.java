package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeQueryDefinitionsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutQueryDefinitionRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryDefinition;
import software.amazon.awssdk.services.cloudwatchlogs.model.QueryParameter;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.floci.aws.testing.LogsRequests.logs;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudWatchLogsQueryDefinitionIntegrationTest {

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sdkCreatesReadsUpdatesAndDeletesTheSameDefinition() {
        String name = "saved-7999/" + UUID.randomUUID();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, "111111111111")) {
            long before = System.currentTimeMillis();
            String id = client.putQueryDefinition(request -> request.name(name).queryString("fields @message")
                    .logGroupNames("/not-created-yet").parameters(QueryParameter.builder()
                            .name("level").defaultValue("ERROR").description("Log level").build())).queryDefinitionId();
            try {
                UUID.fromString(id);
                QueryDefinition saved = client.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(name)).queryDefinitions().getFirst();
                assertEquals(id, saved.queryDefinitionId());
                assertEquals(name, saved.name());
                assertEquals("fields @message", saved.queryString());
                assertEquals(List.of("/not-created-yet"), saved.logGroupNames());
                assertEquals("CWLI", saved.queryLanguageAsString());
                assertEquals("ERROR", saved.parameters().getFirst().defaultValue());
                assertTrue(saved.lastModified() >= before);
                assertEquals(id, client.putQueryDefinition(request -> request.queryDefinitionId(id)
                        .name(name + "/updated").queryString("stats count()")).queryDefinitionId());
                QueryDefinition updated = client.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(name)).queryDefinitions().getFirst();
                assertEquals(id, updated.queryDefinitionId());
                assertEquals(name + "/updated", updated.name());
                assertEquals("stats count()", updated.queryString());
                assertTrue(updated.logGroupNames().isEmpty());
                assertTrue(updated.parameters().isEmpty());
                assertTrue(updated.lastModified() >= saved.lastModified());
            } finally {
                assertTrue(client.deleteQueryDefinition(request -> request.queryDefinitionId(id)).success());
            }
            assertTrue(client.describeQueryDefinitions(request -> request.queryDefinitionNamePrefix(name))
                    .queryDefinitions().isEmpty());
            assertThrows(ResourceNotFoundException.class, () -> client.putQueryDefinition(request -> request
                    .queryDefinitionId(id).name(name).queryString("fields @message")));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.deleteQueryDefinition(request -> request.queryDefinitionId(id)));
        }
    }

    @Test
    void sdkPaginatesFilteredDefinitionsAndKeepsAccountsAndRegionsSeparate() {
        String prefix = "pages-7999/" + UUID.randomUUID();
        Set<String> owned = new HashSet<>();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, "111111111111");
                CloudWatchLogsClient otherAccount = client(Region.US_EAST_1, "222222222222");
                CloudWatchLogsClient otherRegion = client(Region.EU_WEST_1, "111111111111")) {
            try {
                for (int i = 0; i < 3; i++) {
                    String name = prefix + "/" + i;
                    owned.add(client.putQueryDefinition(request -> request.name(name)
                            .queryString("fields @message")).queryDefinitionId());
                }
                String sql = client.putQueryDefinition(request -> request.name(prefix + "/sql")
                        .queryLanguage("SQL").queryString("SELECT * FROM logs")).queryDefinitionId();
                owned.add(sql);
                assertEquals(List.of(sql), client.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(prefix).queryLanguage("SQL")).queryDefinitions().stream()
                        .map(QueryDefinition::queryDefinitionId).toList());
                Set<String> seen = new HashSet<>();
                DescribeQueryDefinitionsResponse page = client.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(prefix).queryLanguage("CWLI").maxResults(1));
                String token = page.nextToken();
                assertNotNull(token);
                assertTrue(otherAccount.describeQueryDefinitions(request -> request.queryDefinitionNamePrefix(prefix))
                        .queryDefinitions().isEmpty());
                assertTrue(otherRegion.describeQueryDefinitions(request -> request.queryDefinitionNamePrefix(prefix))
                        .queryDefinitions().isEmpty());
                String id = page.queryDefinitions().getFirst().queryDefinitionId();
                assertThrows(ResourceNotFoundException.class, () -> otherAccount.deleteQueryDefinition(request -> request
                        .queryDefinitionId(id)));
                assertThrows(ResourceNotFoundException.class, () -> otherRegion.putQueryDefinition(request -> request
                        .queryDefinitionId(id).name("wrong-region").queryString("fields @message")));
                assertThrows(InvalidParameterException.class, () -> otherAccount.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(prefix).queryLanguage("CWLI").nextToken(token)));
                assertThrows(InvalidParameterException.class, () -> otherRegion.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(prefix).queryLanguage("CWLI").nextToken(token)));
                while (true) {
                    assertEquals(1, page.queryDefinitions().size());
                    assertTrue(seen.add(page.queryDefinitions().getFirst().queryDefinitionId()));
                    if (page.nextToken() == null) {
                        break;
                    }
                    String next = page.nextToken();
                    page = client.describeQueryDefinitions(request -> request.queryDefinitionNamePrefix(prefix)
                            .queryLanguage("CWLI").maxResults(1).nextToken(next));
                }
                assertEquals(3, seen.size());
            } finally {
                for (String id : owned) {
                    client.deleteQueryDefinition(request -> request.queryDefinitionId(id));
                }
            }
        }
    }

    @Test
    void sdkRetriesWithClientTokenDoNotCreateDuplicates() {
        String name = "retry-7999/" + UUID.randomUUID();
        PutQueryDefinitionRequest request = PutQueryDefinitionRequest.builder().name(name)
                .queryString("fields @message").clientToken(UUID.randomUUID().toString()).build();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, "111111111111")) {
            String id = client.putQueryDefinition(request).queryDefinitionId();
            try {
                assertEquals(id, client.putQueryDefinition(request).queryDefinitionId());
                client.putQueryDefinition(update -> update.queryDefinitionId(id).name(name + "/updated")
                        .queryString("stats count()"));
                assertEquals(id, client.putQueryDefinition(request).queryDefinitionId());
                List<QueryDefinition> saved = client.describeQueryDefinitions(describe -> describe
                        .queryDefinitionNamePrefix(name)).queryDefinitions();
                assertEquals(1, saved.size());
                assertEquals(name + "/updated", saved.getFirst().name());
                assertEquals("stats count()", saved.getFirst().queryString());
                assertThrows(InvalidParameterException.class, () -> client.putQueryDefinition(request.toBuilder()
                        .queryString("different query").build()));
            } finally {
                client.deleteQueryDefinition(delete -> delete.queryDefinitionId(id));
            }
            assertEquals(id, client.putQueryDefinition(request).queryDefinitionId());
            assertTrue(client.describeQueryDefinitions(describe -> describe.queryDefinitionNamePrefix(name))
                    .queryDefinitions().isEmpty());
        }
    }

    @Test
    void sdkAcceptsModeledBoundsForSavedQueryFields() {
        String name = UUID.randomUUID() + "x".repeat(219);
        String query = "q".repeat(10000);
        String group = "g".repeat(512);
        List<QueryParameter> parameters = IntStream.range(0, 20).mapToObj(index -> QueryParameter.builder()
                .name(index == 0 ? "p".repeat(128) : "parameter_" + index)
                .defaultValue("v".repeat(1024)).description("d".repeat(512)).build()).toList();
        try (CloudWatchLogsClient client = client(Region.US_EAST_1, "111111111111")) {
            String id = client.putQueryDefinition(request -> request.name(name).queryString(query)
                    .queryLanguage("CWLI").logGroupNames(group).parameters(parameters)
                    .clientToken(UUID.randomUUID() + "t".repeat(92))).queryDefinitionId();
            try {
                QueryDefinition saved = client.describeQueryDefinitions(request -> request
                        .queryDefinitionNamePrefix(name).maxResults(1000)).queryDefinitions().getFirst();
                assertEquals(id, saved.queryDefinitionId());
                assertEquals(name, saved.name());
                assertEquals(query, saved.queryString());
                assertEquals(List.of(group), saved.logGroupNames());
                assertEquals(parameters, saved.parameters());
            } finally {
                client.deleteQueryDefinition(request -> request.queryDefinitionId(id));
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "PutQueryDefinition;{}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":7}",
            "PutQueryDefinition;{\"name\":7,\"queryString\":\"q\"}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"logGroupNames\":\"group\"}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"logGroupNames\":[7]}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[7]}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[{}]}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[{\"name\":\"1bad\"}]}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"parameters\":[{\"name\":\"p\"}],\"queryLanguage\":\"SQL\"}",
            "PutQueryDefinition;{\"name\":\"q\",\"queryString\":\"q\",\"queryLanguage\":\"invalid\"}",
            "DescribeQueryDefinitions;{\"maxResults\":0}",
            "DescribeQueryDefinitions;{\"maxResults\":1001}",
            "DescribeQueryDefinitions;{\"maxResults\":1.5}",
            "DescribeQueryDefinitions;{\"maxResults\":\"1\"}",
            "DescribeQueryDefinitions;{\"nextToken\":7}",
            "DescribeQueryDefinitions;{\"nextToken\":\"\"}",
            "DescribeQueryDefinitions;{\"nextToken\":\"bogus\"}",
            "DeleteQueryDefinition;{}"
    })
    void invalidRequestsReturnAwsErrors(String action, String body) {
        logs(action, body).then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    private static CloudWatchLogsClient client(Region region, String account) {
        return AwsTestClients.create(CloudWatchLogsClient.builder(), account, region);
    }
}
