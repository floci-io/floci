package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Database;
import software.amazon.awssdk.services.glue.model.GetDatabasesResponse;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueDatabasesPaginationIntegrationTest {

    @Test
    void sdkPagesDatabasesWithoutLosingFieldsOrCrossingAccounts() {
        String prefix = "page-" + UUID.randomUUID();
        List<String> names = List.of(prefix + "-a", prefix + "-b", prefix + "-c");
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), "741648700611", Region.US_EAST_1);
                GlueClient foreignAccount = AwsTestClients.create(GlueClient.builder(), "741648700612", Region.US_EAST_1)) {
            for (String name : names.reversed()) {
                client.createDatabase(request -> request.databaseInput(builder -> builder
                        .name(name).description("details-" + name)));
            }
            foreignAccount.createDatabase(request -> request.databaseInput(builder -> builder.name(prefix + "-0")));
            try {
                assertEquals(names, client.getDatabases(request -> {}).databaseList().stream()
                        .map(Database::name).sorted().toList());
                GetDatabasesResponse first = client.getDatabases(request -> request.maxResults(1));
                assertEquals(List.of(names.get(0)), first.databaseList().stream()
                        .map(Database::name).toList());
                assertNotNull(first.nextToken());
                assertEquals("details-" + names.getFirst(), first.databaseList().getFirst().description());
                assertEquals("741648700611", first.databaseList().getFirst().catalogId());
                GetDatabasesResponse second = client.getDatabases(request -> request
                        .maxResults(1).nextToken(first.nextToken()));
                assertEquals(List.of(names.get(1)), second.databaseList().stream()
                        .map(Database::name).toList());
                assertNotNull(second.nextToken());
                GetDatabasesResponse last = client.getDatabases(request -> request
                        .maxResults(1).nextToken(second.nextToken()));
                assertEquals(List.of(names.get(2)), last.databaseList().stream()
                        .map(Database::name).toList());
                assertNull(last.nextToken());
                assertEquals(names, client.getDatabasesPaginator(request -> request.maxResults(2))
                        .stream().flatMap(page -> page.databaseList().stream()).map(Database::name).toList());
            } finally {
                names.forEach(name -> client.deleteDatabase(request -> request.name(name)));
                foreignAccount.deleteDatabase(request -> request.name(prefix + "-0"));
            }
        }
    }

    @Test
    void sdkReadsAnEmptyPageAtTheMaximumPageSizeWithoutAToken() {
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), "741648700613", Region.US_EAST_1)) {
            GetDatabasesResponse response = client.getDatabases(request -> request.maxResults(100));
            assertTrue(response.databaseList().isEmpty());
            assertNull(response.nextToken());
        }
    }
}
