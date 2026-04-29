package io.github.hectorvent.floci.services.glue.schemaregistry;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Compatibility;
import software.amazon.awssdk.services.glue.model.CreateRegistryRequest;
import software.amazon.awssdk.services.glue.model.CreateSchemaRequest;
import software.amazon.awssdk.services.glue.model.DataFormat;
import software.amazon.awssdk.services.glue.model.DeleteRegistryRequest;
import software.amazon.awssdk.services.glue.model.DeleteSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetSchemaRequest;
import software.amazon.awssdk.services.glue.model.GetSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.ListSchemaVersionsRequest;
import software.amazon.awssdk.services.glue.model.RegisterSchemaVersionRequest;
import software.amazon.awssdk.services.glue.model.RegistryId;
import software.amazon.awssdk.services.glue.model.SchemaId;
import software.amazon.awssdk.services.glue.model.SchemaVersionNumber;
import software.amazon.awssdk.services.glue.model.UpdateSchemaRequest;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GlueSchemaRegistrySdkIntegrationTest {

    private static final String AVRO_V1 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"sdk\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"}]}";

    private static final String AVRO_V2 =
            "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"sdk\","
                    + "\"fields\":[{\"name\":\"id\",\"type\":\"long\"},"
                    + "{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void awsSdkGlueClientCanUseSchemaRegistryLifecycleAndPagination() {
        String registryName = "sdk-reg-" + UUID.randomUUID().toString().substring(0, 8);
        String schemaName = "sdk-schema-" + UUID.randomUUID().toString().substring(0, 8);

        try (GlueClient glue = glueClient()) {
            var registry = glue.createRegistry(CreateRegistryRequest.builder()
                    .registryName(registryName)
                    .build());
            assertEquals(registryName, registry.registryName());
            assertTrue(registry.registryArn().contains(":registry/" + registryName));

            var created = glue.createSchema(CreateSchemaRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .schemaName(schemaName)
                    .dataFormat(DataFormat.AVRO)
                    .compatibility(Compatibility.BACKWARD)
                    .schemaDefinition(AVRO_V1)
                    .build());
            assertEquals(schemaName, created.schemaName());
            assertNotNull(created.schemaVersionId());

            var registered = glue.registerSchemaVersion(RegisterSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaDefinition(AVRO_V2)
                    .build());
            assertEquals(2L, registered.versionNumber());

            var firstPage = glue.listSchemaVersions(ListSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .maxResults(1)
                    .build());
            assertEquals(1, firstPage.schemas().size());
            assertNotNull(firstPage.nextToken());

            var secondPage = glue.listSchemaVersions(ListSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .maxResults(1)
                    .nextToken(firstPage.nextToken())
                    .build());
            assertEquals(1, secondPage.schemas().size());
            assertNull(secondPage.nextToken());

            glue.updateSchema(UpdateSchemaRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(SchemaVersionNumber.builder().versionNumber(2L).build())
                    .build());

            var deleteVersions = glue.deleteSchemaVersions(DeleteSchemaVersionsRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .versions("1")
                    .build());
            assertTrue(deleteVersions.schemaVersionErrors().isEmpty());

            var latest = glue.getSchemaVersion(GetSchemaVersionRequest.builder()
                    .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                    .schemaVersionNumber(SchemaVersionNumber.builder().latestVersion(true).build())
                    .build());
            assertEquals(2L, latest.versionNumber());

            glue.deleteRegistry(DeleteRegistryRequest.builder()
                    .registryId(RegistryId.builder().registryName(registryName).build())
                    .build());

            assertThrows(EntityNotFoundException.class, () ->
                    glue.getSchema(GetSchemaRequest.builder()
                            .schemaId(SchemaId.builder().registryName(registryName).schemaName(schemaName).build())
                            .build()));
        }
    }

    private GlueClient glueClient() {
        return GlueClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
