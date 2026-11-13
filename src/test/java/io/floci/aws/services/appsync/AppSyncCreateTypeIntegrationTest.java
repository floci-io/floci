package io.floci.aws.services.appsync;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.appsync.model.AuthenticationType;
import software.amazon.awssdk.services.appsync.model.BadRequestException;
import software.amazon.awssdk.services.appsync.model.NotFoundException;
import software.amazon.awssdk.services.appsync.model.Type;
import software.amazon.awssdk.services.appsync.model.TypeDefinitionFormat;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppSyncCreateTypeIntegrationTest {

    @ParameterizedTest
    @EnumSource(value = TypeDefinitionFormat.class, names = {"SDL", "JSON"})
    void missingDefinitionDoesNotStoreTheTypeAndValidCreationStillSucceeds(TypeDefinitionFormat format) {
        try (AppSyncClient client = AwsTestClients.create(
                AppSyncClient.builder(), "741648700016", Region.US_EAST_1)) {
            String apiId = client.createGraphqlApi(request -> request.name("type-create-" + UUID.randomUUID())
                    .authenticationType(AuthenticationType.API_KEY)).graphqlApi().apiId();
            try {
                BadRequestException refused = assertThrows(BadRequestException.class,
                        () -> client.createType(request -> request.apiId(apiId).format(format)));

                assertEquals(400, refused.statusCode());
                assertTrue(refused.getMessage().contains("definition"));
                assertTrue(client.listTypes(request -> request.apiId(apiId).format(TypeDefinitionFormat.SDL))
                        .types().isEmpty());
                assertThrows(NotFoundException.class, () -> client.getType(request -> request.apiId(apiId)
                        .typeName("Item").format(TypeDefinitionFormat.SDL)));

                Type created = client.createType(request -> request.apiId(apiId)
                        .definition("type Item { id: ID }").format(TypeDefinitionFormat.SDL)).type();
                assertEquals("Item", created.name());
                assertEquals("type Item { id: ID }", created.definition());
            } finally {
                client.deleteGraphqlApi(request -> request.apiId(apiId));
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"BOGUS", ""})
    void rejectedFormatDoesNotStoreTheTypeAndValidCreationStillSucceeds(String format) {
        try (AppSyncClient client = AwsTestClients.create(
                AppSyncClient.builder(), "741648700016", Region.US_EAST_1)) {
            String apiId = client.createGraphqlApi(request -> request.name("type-create-" + UUID.randomUUID())
                    .authenticationType(AuthenticationType.API_KEY)).graphqlApi().apiId();
            try {
                String definition = "type Item { id: ID }";
                BadRequestException refused = assertThrows(BadRequestException.class,
                        () -> client.createType(request -> request.apiId(apiId)
                                .definition(definition).format(format)));

                assertEquals(400, refused.statusCode());
                assertTrue(client.listTypes(request -> request.apiId(apiId).format(TypeDefinitionFormat.SDL))
                        .types().isEmpty());
                assertThrows(NotFoundException.class, () -> client.getType(request -> request.apiId(apiId)
                        .typeName("Item").format(TypeDefinitionFormat.SDL)));

                Type created = client.createType(request -> request.apiId(apiId)
                        .definition(definition).format(TypeDefinitionFormat.SDL)).type();
                assertEquals("Item", created.name());
                assertEquals(definition, created.definition());
                assertEquals(TypeDefinitionFormat.SDL, created.format());
                assertEquals(created, client.getType(request -> request.apiId(apiId).typeName("Item")
                        .format(TypeDefinitionFormat.SDL)).type());
            } finally {
                client.deleteGraphqlApi(request -> request.apiId(apiId));
            }
        }
    }
}
