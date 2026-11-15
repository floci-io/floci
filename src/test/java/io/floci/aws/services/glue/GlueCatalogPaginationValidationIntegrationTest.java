package io.floci.aws.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.InvalidInputException;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GlueCatalogPaginationValidationIntegrationTest {

    enum Operation {
        DATABASES(100), TABLES(100), VERSIONS(100), PARTITIONS(1000);

        final int maximum;

        Operation(int maximum) {
            this.maximum = maximum;
        }

        void call(GlueClient client, String database, Integer size, String token) {
            switch (this) {
                case DATABASES -> client.getDatabases(request -> request.maxResults(size).nextToken(token));
                case TABLES -> client.getTables(request -> request.databaseName(database).maxResults(size).nextToken(token));
                case VERSIONS -> client.getTableVersions(request -> request.databaseName(database).tableName("example")
                        .maxResults(size).nextToken(token));
                case PARTITIONS -> client.getPartitions(request -> request.databaseName(database).tableName("example")
                        .maxResults(size).nextToken(token));
            }
        }
    }

    static Stream<Arguments> invalidSizes() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(-1, 0, operation.maximum + 1)
                .map(size -> Arguments.of(operation, size)));
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of("not-a-token", "-1", "2147483647")
                .map(token -> Arguments.of(operation, token)));
    }

    @ParameterizedTest
    @MethodSource("invalidSizes")
    void rejectsSizesOutsideEachOperationsRange(Operation operation, int size) {
        assertInvalid(operation, size, null);
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void rejectsMalformedTokensForEachOperation(Operation operation, String token) {
        assertInvalid(operation, null, token);
    }

    private static void assertInvalid(Operation operation, Integer size, String token) {
        GlueCatalogPaginationTestSupport.withCatalog("741648700614", "validation_", (client, database) -> {
            client.createTable(request -> request.databaseName(database).tableInput(input -> input.name("example")));
            InvalidInputException failure = assertThrows(InvalidInputException.class,
                    () -> operation.call(client, database, size, token));
            assertEquals(400, failure.statusCode());
        });
    }
}
