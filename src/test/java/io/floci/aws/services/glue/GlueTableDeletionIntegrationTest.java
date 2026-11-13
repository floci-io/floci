package io.floci.aws.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.glue.model.BatchDeleteTableResponse;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.TableVersion;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GlueTableDeletionIntegrationTest {

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
