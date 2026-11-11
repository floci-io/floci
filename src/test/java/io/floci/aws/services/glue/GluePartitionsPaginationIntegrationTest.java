package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetPartitionsResponse;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.Partition;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GluePartitionsPaginationIntegrationTest {

    @Test
    void sdkTraversesAllSelectedPartitionsWithoutDuplicates() {
        withPartitions((client, database) -> {
            List<List<String>> all = client.getPartitions(request -> request.databaseName(database).tableName("example"))
                    .partitions().stream().map(Partition::values).toList();
            assertEquals(3, all.size());
            GetPartitionsResponse first = client.getPartitions(request -> request
                    .databaseName(database).tableName("example").maxResults(1));
            assertEquals(all.subList(0, 1), first.partitions().stream().map(Partition::values).toList());
            assertNotNull(first.nextToken());
            GetPartitionsResponse second = client.getPartitions(request -> request
                    .databaseName(database).tableName("example").maxResults(1).nextToken(first.nextToken()));
            assertEquals(all.subList(1, 2), second.partitions().stream().map(Partition::values).toList());
            assertNotNull(second.nextToken());
            GetPartitionsResponse last = client.getPartitions(request -> request
                    .databaseName(database).tableName("example").maxResults(1).nextToken(second.nextToken()));
            assertEquals(all.subList(2, 3), last.partitions().stream().map(Partition::values).toList());
            assertNull(last.nextToken());
            assertEquals(all, client.getPartitionsPaginator(request -> request
                    .databaseName(database).tableName("example").maxResults(2)).stream().flatMap(page -> page.partitions().stream())
                    .map(Partition::values).toList());
            GetPartitionsResponse maximum = client.getPartitions(request -> request
                    .databaseName(database).tableName("example").maxResults(1000));
            assertEquals(all, maximum.partitions().stream().map(Partition::values).toList());
            assertNull(maximum.nextToken());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1001})
    void sdkRejectsPageSizesOutsideTheDocumentedRange(int size) {
        withPartitions((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getPartitions(request -> request.databaseName(database).tableName("example")
                            .maxResults(size)));
            assertEquals(400, failure.statusCode());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "-1", "2147483647"})
    void sdkRejectsMalformedOrOutOfRangeTokens(String token) {
        withPartitions((client, database) -> {
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> client.getPartitions(request -> request.databaseName(database).tableName("example")
                            .nextToken(token)));
            assertEquals(400, failure.statusCode());
        });
    }

    @Test
    void expressionSelectionPrecedesPaging() {
        withPartitions((client, database) -> {
            GetPartitionsResponse response = client.getPartitions(request -> request.databaseName(database)
                    .tableName("example").expression("day = 'second'").maxResults(1));
            assertEquals(List.of(List.of("second")), response.partitions().stream().map(Partition::values).toList());
            assertNull(response.nextToken());
        });
    }

    @Test
    void segmentSelectionPrecedesPaging() {
        withPartitions((client, database) -> {
            List<List<String>> all = client.getPartitions(request -> request.databaseName(database)
                    .tableName("example").segment(segment -> segment.segmentNumber(1).totalSegments(2)))
                    .partitions().stream().map(Partition::values).toList();
            assertEquals(2, all.size());
            GetPartitionsResponse first = client.getPartitions(request -> request.databaseName(database)
                    .tableName("example").segment(segment -> segment.segmentNumber(1).totalSegments(2)).maxResults(1));
            assertEquals(all.subList(0, 1), first.partitions().stream().map(Partition::values).toList());
            assertNotNull(first.nextToken());
            GetPartitionsResponse last = client.getPartitions(request -> request.databaseName(database)
                    .tableName("example").segment(segment -> segment.segmentNumber(1).totalSegments(2))
                    .maxResults(1).nextToken(first.nextToken()));
            assertEquals(all.subList(1, 2), last.partitions().stream().map(Partition::values).toList());
            assertNull(last.nextToken());
        });
    }

    private static void withPartitions(BiConsumer<GlueClient, String> action) {
        String database = "partitions_" + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), "741648700007", Region.US_EAST_1)) {
            client.createDatabase(request -> request.databaseInput(input -> input.name(database)));
            try {
                client.createTable(request -> request.databaseName(database).tableInput(input -> input.name("example")
                        .partitionKeys(key -> key.name("day").type("string"))
                        .storageDescriptor(sd -> sd.columns(column -> column.name("id").type("string")))));
                for (String value : List.of("first", "second", "third")) {
                    client.createPartition(request -> request.databaseName(database).tableName("example")
                            .partitionInput(input -> input.values(value)
                                    .storageDescriptor(sd -> sd.columns(column -> column.name("id").type("string")))));
                }
                action.accept(client, database);
            } finally {
                client.deleteTable(request -> request.databaseName(database).name("example"));
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }
}
