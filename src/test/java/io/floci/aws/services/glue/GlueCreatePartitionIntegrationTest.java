package io.floci.aws.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.glue.model.AlreadyExistsException;
import software.amazon.awssdk.services.glue.model.BatchCreatePartitionResponse;
import software.amazon.awssdk.services.glue.model.Partition;
import software.amazon.awssdk.services.glue.model.PartitionInput;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueCreatePartitionIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateCreatePreservesTheStoredPartition(boolean createdInBatch) {
        GlueCatalogPaginationTestSupport.withCatalog("741648700013", "create_partition_", (client, database) -> {
            client.createTable(request -> request.databaseName(database).tableInput(input -> input.name("events")
                    .partitionKeys(key -> key.name("year").type("string"), key -> key.name("month").type("string"))
                    .storageDescriptor(sd -> sd.columns(column -> column.name("id").type("string")))));
            PartitionInput original = PartitionInput.builder().values("2026", "11")
                    .parameters(Map.of("revision", "original"))
                    .lastAccessTime(Instant.parse("2026-11-01T00:00:00Z"))
                    .storageDescriptor(sd -> sd.location("s3://catalog-fixture/events/year=2026/month=11/")
                            .columns(column -> column.name("id").type("string"))).build();
            if (createdInBatch) {
                assertTrue(client.batchCreatePartition(request -> request.databaseName(database).tableName("events")
                        .partitionInputList(original)).errors().isEmpty());
            } else {
                client.createPartition(request -> request.databaseName(database).tableName("events")
                        .partitionInput(original));
            }
            Partition stored = client.getPartition(request -> request.databaseName(database).tableName("events")
                    .partitionValues(original.values())).partition();
            assertNotNull(stored.creationTime());
            PartitionInput replacement = original.toBuilder().parameters(Map.of("revision", "replacement"))
                    .lastAccessTime(Instant.parse("2026-11-02T00:00:00Z"))
                    .storageDescriptor(sd -> sd.location("s3://catalog-fixture/replacement/")
                            .columns(column -> column.name("id").type("string"))).build();

            AlreadyExistsException refused = assertThrows(AlreadyExistsException.class,
                    () -> client.createPartition(request -> request.databaseName(database).tableName("events")
                            .partitionInput(replacement)));
            assertEquals(400, refused.statusCode());
            assertEquals(stored, client.getPartition(request -> request.databaseName(database).tableName("events")
                    .partitionValues(original.values())).partition());

            PartitionInput nextMonth = original.toBuilder().values("2026", "12").build();
            BatchCreatePartitionResponse batch = client.batchCreatePartition(request -> request.databaseName(database)
                    .tableName("events").partitionInputList(replacement, nextMonth));
            assertEquals(1, batch.errors().size());
            assertEquals(original.values(), batch.errors().getFirst().partitionValues());
            assertEquals("AlreadyExistsException", batch.errors().getFirst().errorDetail().errorCode());
            assertEquals(stored, client.getPartition(request -> request.databaseName(database).tableName("events")
                    .partitionValues(original.values())).partition());
            assertEquals(List.of("2026", "12"), client.getPartition(request -> request.databaseName(database)
                    .tableName("events").partitionValues(nextMonth.values())).partition().values());
            assertEquals(2, client.getPartitions(request -> request.databaseName(database).tableName("events"))
                    .partitions().size());

            client.updatePartition(request -> request.databaseName(database).tableName("events")
                    .partitionValueList(original.values()).partitionInput(replacement));
            Partition updated = client.getPartition(request -> request.databaseName(database).tableName("events")
                    .partitionValues(original.values())).partition();
            assertEquals(replacement.parameters(), updated.parameters());
            assertEquals(replacement.storageDescriptor().location(), updated.storageDescriptor().location());
            assertEquals(replacement.lastAccessTime(), updated.lastAccessTime());
        });
    }
}
