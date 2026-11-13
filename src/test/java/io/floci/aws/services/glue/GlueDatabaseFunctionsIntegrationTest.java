package io.floci.aws.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.UserDefinedFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueDatabaseFunctionsIntegrationTest {

    @Test
    void recreatedDatabaseDoesNotInheritDeletedFunctions() {
        GlueCatalogPaginationTestSupport.withCatalog("741648700014", "deleted_functions_", (client, database) ->
                GlueCatalogPaginationTestSupport.withCatalog("741648700014", "retained_functions_", (neighbor, other) -> {
                    client.createUserDefinedFunction(request -> request.databaseName(database)
                            .functionInput(input -> input.functionName("shared").className("example.Original")));
                    client.createUserDefinedFunction(request -> request.databaseName(database)
                            .functionInput(input -> input.functionName("second").className("example.Second")));
                    neighbor.createUserDefinedFunction(request -> request.databaseName(other)
                            .functionInput(input -> input.functionName("shared").className("example.Neighbor")));
                    UserDefinedFunction retained = neighbor.getUserDefinedFunction(request -> request
                            .databaseName(other).functionName("shared")).userDefinedFunction();

                    client.deleteDatabase(request -> request.name(database));
                    client.createDatabase(request -> request.databaseInput(input -> input.name(database)));

                    EntityNotFoundException missing = assertThrows(EntityNotFoundException.class,
                            () -> client.getUserDefinedFunction(request -> request.databaseName(database)
                                    .functionName("shared")));
                    assertEquals(400, missing.statusCode());
                    assertTrue(client.getUserDefinedFunctions(request -> request.databaseName(database).pattern(".*"))
                            .userDefinedFunctions().isEmpty());
                    assertEquals(retained, neighbor.getUserDefinedFunction(request -> request
                            .databaseName(other).functionName("shared")).userDefinedFunction());

                    client.createUserDefinedFunction(request -> request.databaseName(database)
                            .functionInput(input -> input.functionName("shared").className("example.Replacement")));
                    assertEquals("example.Replacement", client.getUserDefinedFunction(request -> request
                            .databaseName(database).functionName("shared")).userDefinedFunction().className());
                }));
    }
}
