package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetTableVersionsResponse;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.TableVersion;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GlueTableVersionsPaginationIntegrationTest {

    @Test
    void sdkTraversesCurrentAndArchivedVersionsWithoutDuplicates() {
        withVersions((client, database) -> {
            List<String> all = client.getTableVersions(request -> request.databaseName(database).tableName("example"))
                    .tableVersions().stream().map(TableVersion::versionId).toList();
            assertEquals(3, all.size());
            GetTableVersionsResponse first = client.getTableVersions(request -> request
                    .databaseName(database).tableName("example").maxResults(1));
            assertEquals(all.subList(0, 1), first.tableVersions().stream().map(TableVersion::versionId).toList());
            assertNotNull(first.nextToken());
            GetTableVersionsResponse second = client.getTableVersions(request -> request
                    .databaseName(database).tableName("example").maxResults(1).nextToken(first.nextToken()));
            assertEquals(all.subList(1, 2), second.tableVersions().stream().map(TableVersion::versionId).toList());
            assertNotNull(second.nextToken());
            GetTableVersionsResponse last = client.getTableVersions(request -> request
                    .databaseName(database).tableName("example").maxResults(1).nextToken(second.nextToken()));
            assertEquals(all.subList(2, 3), last.tableVersions().stream().map(TableVersion::versionId).toList());
            assertNull(last.nextToken());
            assertEquals(all, client.getTableVersionsPaginator(request -> request
                    .databaseName(database).tableName("example").maxResults(2)).stream().flatMap(page -> page.tableVersions().stream())
                    .map(TableVersion::versionId).toList());
            GetTableVersionsResponse maximum = client.getTableVersions(request -> request
                    .databaseName(database).tableName("example").maxResults(100));
            assertEquals(all, maximum.tableVersions().stream().map(TableVersion::versionId).toList());
            assertNull(maximum.nextToken());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void sdkRejectsPageSizesOutsideTheDocumentedRange(int size) {
        withVersions((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getTableVersions(request -> request.databaseName(database)
                            .tableName("example").maxResults(size)));
            assertEquals(400, failure.statusCode());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "-1", "2147483647"})
    void sdkRejectsMalformedOrOutOfRangeTokens(String token) {
        withVersions((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getTableVersions(request -> request.databaseName(database)
                            .tableName("example").nextToken(token)));
            assertEquals(400, failure.statusCode());
        });
    }

    private static void withVersions(BiConsumer<GlueClient, String> action) {
        String database = "versions_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), "741648700005", Region.US_EAST_1)) {
            client.createDatabase(request -> request.databaseInput(input -> input.name(database)));
            try {
                client.createTable(request -> request.databaseName(database)
                        .tableInput(input -> input.name("example").description("first")));
                client.updateTable(request -> request.databaseName(database)
                        .tableInput(input -> input.name("example").description("second")));
                client.updateTable(request -> request.databaseName(database)
                        .tableInput(input -> input.name("example").description("third")));
                action.accept(client, database);
            } finally {
                client.deleteTable(request -> request.databaseName(database).name("example"));
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }
}
