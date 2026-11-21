package io.floci.aws.services.glue;

import io.floci.aws.testing.AwsTestClients;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;

import java.util.UUID;
import java.util.function.BiConsumer;

final class GlueCatalogPaginationTestSupport {

    private GlueCatalogPaginationTestSupport() {
    }

    static void withCatalog(String account, String prefix, BiConsumer<GlueClient, String> action) {
        String database = prefix + UUID.randomUUID().toString().replace("-", "");
        try (GlueClient client = AwsTestClients.create(GlueClient.builder(), account, Region.US_EAST_1)) {
            client.createDatabase(request -> request.databaseInput(input -> input.name(database)));
            try {
                action.accept(client, database);
            } finally {
                client.getTablesPaginator(request -> request.databaseName(database)).stream()
                        .flatMap(page -> page.tableList().stream()).forEach(table -> client.deleteTable(request -> request
                                .databaseName(database).name(table.name())));
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }
}
