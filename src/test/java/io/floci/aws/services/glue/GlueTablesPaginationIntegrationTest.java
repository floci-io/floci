package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.Table;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GlueTablesPaginationIntegrationTest {

    @Test
    void sdkTraversesAllTablesWithoutDuplicates() {
        withTables((client, database) -> {
            List<String> all = client.getTables(request -> request.databaseName(database))
                    .tableList().stream().map(Table::name).toList();
            assertEquals(3, all.size());
            GetTablesResponse first = client.getTables(request -> request
                    .databaseName(database).maxResults(1));
            assertEquals(all.subList(0, 1), first.tableList().stream().map(Table::name).toList());
            assertNotNull(first.nextToken());
            GetTablesResponse second = client.getTables(request -> request
                    .databaseName(database).maxResults(1).nextToken(first.nextToken()));
            assertEquals(all.subList(1, 2), second.tableList().stream().map(Table::name).toList());
            assertNotNull(second.nextToken());
            GetTablesResponse last = client.getTables(request -> request
                    .databaseName(database).maxResults(1).nextToken(second.nextToken()));
            assertEquals(all.subList(2, 3), last.tableList().stream().map(Table::name).toList());
            assertNull(last.nextToken());
            assertEquals(all, client.getTablesPaginator(request -> request
                    .databaseName(database).maxResults(2)).stream().flatMap(page -> page.tableList().stream())
                    .map(Table::name).toList());
            GetTablesResponse maximum = client.getTables(request -> request
                    .databaseName(database).maxResults(100));
            assertEquals(all, maximum.tableList().stream().map(Table::name).toList());
            assertNull(maximum.nextToken());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void sdkRejectsPageSizesOutsideTheDocumentedRange(int size) {
        withTables((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getTables(request -> request.databaseName(database)
                            .maxResults(size)));
            assertEquals(400, failure.statusCode());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "-1", "2147483647"})
    void sdkRejectsMalformedOrOutOfRangeTokens(String token) {
        withTables((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getTables(request -> request.databaseName(database)
                            .nextToken(token)));
            assertEquals(400, failure.statusCode());
        });
    }

    private static void withTables(BiConsumer<GlueClient, String> action) {
        String database = "tables_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), "741648700006", Region.US_EAST_1)) {
            client.createDatabase(request -> request.databaseInput(input -> input.name(database)));
            try {
                for (String name : List.of("first", "second", "third")) {
                    client.createTable(request -> request.databaseName(database)
                            .tableInput(input -> input.name(name)));
                }
                action.accept(client, database);
            } finally {
                for (String name : List.of("first", "second", "third")) {
                    client.deleteTable(request -> request.databaseName(database).name(name));
                }
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }
}
