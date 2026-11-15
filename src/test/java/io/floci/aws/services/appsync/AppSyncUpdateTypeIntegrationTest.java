package io.floci.aws.services.appsync;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AuthenticationType;
import software.amazon.awssdk.services.appsync.model.BadRequestException;
import software.amazon.awssdk.services.appsync.model.Type;
import software.amazon.awssdk.services.appsync.model.TypeDefinitionFormat;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppSyncUpdateTypeIntegrationTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"BOGUS", ""})
    void rejectedFormatPreservesTheTypeAndValidUpdateStillSucceeds(String format) {
        try (AppSyncClient client = AwsTestClients.create(
                AppSyncClient.builder(), "741648700015", Region.US_EAST_1)) {
            String apiId = client.createGraphqlApi(request -> request.name("type-update-" + UUID.randomUUID())
                    .authenticationType(AuthenticationType.API_KEY)).graphqlApi().apiId();
            try {
                String original = "type Item { id: ID }";
                String replacement = "type Item { changed: ID }";
                client.createType(request -> request.apiId(apiId).definition(original).format(TypeDefinitionFormat.SDL));
                Type stored = client.getType(request -> request.apiId(apiId).typeName("Item")
                        .format(TypeDefinitionFormat.SDL)).type();

                BadRequestException refused = assertThrows(BadRequestException.class,
                        () -> client.updateType(request -> request.apiId(apiId).typeName("Item")
                                .definition(replacement).format(format)));

                assertEquals(400, refused.statusCode());
                assertEquals(stored, client.getType(request -> request.apiId(apiId).typeName("Item")
                        .format(TypeDefinitionFormat.SDL)).type());
                assertEquals(original, client.listTypes(request -> request.apiId(apiId).format(TypeDefinitionFormat.SDL))
                        .types().getFirst().definition());

                Type updated = client.updateType(request -> request.apiId(apiId).typeName("Item")
                        .definition(replacement).format(TypeDefinitionFormat.SDL)).type();
                assertEquals(replacement, updated.definition());
                assertEquals(TypeDefinitionFormat.SDL, updated.format());
                assertEquals(updated, client.getType(request -> request.apiId(apiId).typeName("Item")
                        .format(TypeDefinitionFormat.SDL)).type());
            } finally {
                client.deleteGraphqlApi(request -> request.apiId(apiId));
            }
        }
    }
}
