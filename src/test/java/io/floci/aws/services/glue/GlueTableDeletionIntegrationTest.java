package io.floci.aws.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.glue.model.BatchDeleteTableResponse;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.TableVersion;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueTableDeletionIntegrationTest {

    @Test
    void batchSizeValidationPrecedesDeletionAndAcceptsTheApiBoundaries() {
        GlueCatalogPaginationTestSupport.withCatalog("814800000002", "batch_deletion_", (client, database) -> {
            client.createTable(request -> request.databaseName(database).tableInput(input -> input.name("table_0")));
            List<String> oversized = IntStream.range(0, 101).mapToObj(index -> "table_" + index).toList();

            InvalidInputException tooMany = assertThrows(InvalidInputException.class,
                    () -> client.batchDeleteTable(request -> request.databaseName(database).tablesToDelete(oversized)));
            assertEquals(400, tooMany.statusCode());
            assertTrue(tooMany.getMessage().contains("TablesToDelete"));
            InvalidInputException missing = assertThrows(InvalidInputException.class,
                    () -> client.batchDeleteTable(request -> request.databaseName(database)));
            assertEquals(400, missing.statusCode());
            assertTrue(missing.getMessage().contains("TablesToDelete"));

            assertTrue(client.batchDeleteTable(request -> request.databaseName(database)
                    .tablesToDelete(List.of())).errors().isEmpty());
            assertEquals("table_0", client.getTable(request -> request.databaseName(database).name("table_0"))
                    .table().name());

            List<String> maximum = oversized.subList(0, 100);
            BatchDeleteTableResponse response = client.batchDeleteTable(request -> request.databaseName(database)
                    .tablesToDelete(maximum));
            assertEquals(maximum.subList(1, 100), response.errors().stream().map(error -> error.tableName()).toList());
            assertTrue(response.errors().stream()
                    .allMatch(error -> "EntityNotFoundException".equals(error.errorDetail().errorCode())));
            assertThrows(EntityNotFoundException.class,
                    () -> client.getTable(request -> request.databaseName(database).name("table_0")));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionHidesArchivedVersionsAndAllowsFreshCreation(boolean batch) {
        GlueCatalogPaginationTestSupport.withCatalog("814800000001", "table_deletion_", (client, database) -> {
            client.createTable(request -> request.databaseName(database)
                    .tableInput(input -> input.name("example").description("first")));
            client.updateTable(request -> request.databaseName(database)
                    .tableInput(input -> input.name("example").description("second")));
            assertEquals(2, client.getTableVersions(request -> request.databaseName(database).tableName("example"))
                    .tableVersions().size());

            if (batch) {
                BatchDeleteTableResponse response = client.batchDeleteTable(request -> request.databaseName(database)
                        .tablesToDelete("example", "missing"));
                assertEquals(1, response.errors().size());
                assertEquals("missing", response.errors().getFirst().tableName());
                assertEquals("EntityNotFoundException", response.errors().getFirst().errorDetail().errorCode());
            } else {
                client.deleteTable(request -> request.databaseName(database).name("example"));
            }

            EntityNotFoundException missing = assertThrows(EntityNotFoundException.class,
                    () -> client.getTable(request -> request.databaseName(database).name("example")));
            assertEquals(400, missing.statusCode());
            assertThrows(EntityNotFoundException.class,
                    () -> client.getTableVersions(request -> request.databaseName(database).tableName("example")));
            assertThrows(EntityNotFoundException.class, () -> client.updateTable(request -> request.databaseName(database)
                    .tableInput(input -> input.name("example").description("too late"))));

            client.createTable(request -> request.databaseName(database)
                    .tableInput(input -> input.name("example").description("fresh")));
            assertEquals(List.of("0"), client.getTableVersions(request -> request
                    .databaseName(database).tableName("example")).tableVersions().stream()
                    .map(TableVersion::versionId).toList());
            assertEquals("fresh", client.getTable(request -> request.databaseName(database).name("example"))
                    .table().description());
        });
    }
}
