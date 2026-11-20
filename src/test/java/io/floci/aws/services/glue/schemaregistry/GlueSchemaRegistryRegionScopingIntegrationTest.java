package io.floci.aws.services.glue.schemaregistry;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Glue Schema Registry registries and their schemas are Regional: each is listed and reached only in the Region it
 * was created in, by its name, its ARN or a schema version's id, and its name can be used again in another Region.
 * Another Region's answers {@code EntityNotFoundException}, as one that doesn't exist does.
 */
@QuarkusTest
class GlueSchemaRegistryRegionScopingIntegrationTest {

    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";
    private static final String AVRO = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
            + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRegistryAndItsSchemaAreListedAndReachedOnlyInTheirRegion() {
        String registry = "reg-" + UUID.randomUUID().toString().substring(0, 8);
        String byName = "{\"RegistryId\":{\"RegistryName\":\"" + registry + "\"}}";
        String registryArn = call(HOME, "CreateRegistry", "{\"RegistryName\":\"" + registry + "\"}").statusCode(200)
                .extract().path("RegistryArn");
        try {
            ValidatableResponse schema = call(HOME, "CreateSchema", "{\"RegistryId\":{\"RegistryName\":\"" + registry
                    + "\"},\"SchemaName\":\"users\",\"DataFormat\":\"AVRO\",\"SchemaDefinition\":\"" + AVRO + "\"}")
                    .statusCode(200);
            String schemaArn = schema.extract().path("SchemaArn");
            String versionId = schema.extract().path("SchemaVersionId");

            call(OTHER, "ListRegistries", "{}").statusCode(200).body("Registries.RegistryName", not(hasItem(registry)));
            notFound(call(OTHER, "GetRegistry", byName));
            notFound(call(OTHER, "GetRegistry", "{\"RegistryId\":{\"RegistryArn\":\"" + registryArn + "\"}}"));
            notFound(call(OTHER, "ListSchemas", byName));
            notFound(call(OTHER, "GetSchema", "{\"SchemaId\":{\"SchemaArn\":\"" + schemaArn + "\"}}"));
            notFound(call(OTHER, "GetSchemaVersion", "{\"SchemaVersionId\":\"" + versionId + "\"}"));
            notFound(call(OTHER, "PutSchemaVersionMetadata", "{\"SchemaVersionId\":\"" + versionId + "\","
                    + "\"MetadataKeyValue\":{\"MetadataKey\":\"owner\",\"MetadataValue\":\"alice\"}}"));
            notFound(call(OTHER, "GetTags", "{\"ResourceArn\":\"" + registryArn + "\"}"));
            notFound(call(OTHER, "DeleteRegistry", byName));

            call(OTHER, "CreateRegistry", "{\"RegistryName\":\"" + registry + "\"}").statusCode(200)
                    .body("RegistryArn", startsWith("arn:aws:glue:" + OTHER + ":"));
            call(OTHER, "DeleteRegistry", byName).statusCode(200);

            call(HOME, "ListRegistries", "{}").statusCode(200).body("Registries.RegistryName", hasItem(registry));
            call(HOME, "GetSchema", "{\"SchemaId\":{\"SchemaArn\":\"" + schemaArn + "\"}}").statusCode(200)
                    .body("SchemaName", equalTo("users"));
            call(HOME, "GetSchemaVersion", "{\"SchemaVersionId\":\"" + versionId + "\"}").statusCode(200)
                    .body("SchemaArn", equalTo(schemaArn));
        } finally {
            call(HOME, "DeleteRegistry", byName);
        }
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSGlue." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/glue/aws4_request")
                .body(body)
                .when().post("/").then();
    }

    private static void notFound(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("EntityNotFoundException"));
    }
}
