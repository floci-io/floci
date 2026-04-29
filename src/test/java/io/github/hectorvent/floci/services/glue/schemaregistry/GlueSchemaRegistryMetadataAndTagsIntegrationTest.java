package io.github.hectorvent.floci.services.glue.schemaregistry;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.AlreadyExistsException;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetTagsRequest;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.MetadataKeyValuePair;
import software.amazon.awssdk.services.glue.model.PutSchemaVersionMetadataRequest;
import software.amazon.awssdk.services.glue.model.RemoveSchemaVersionMetadataRequest;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueSchemaRegistryMetadataAndTagsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGISTRY = "tags-it-registry";
    private static final String SCHEMA = "tags-schema";
    private static final String AVRO_V1 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"}]}";

    private static String registryArn;
    private static String schemaVersionId;

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void seed() {
        registryArn = given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY + "\" }")
        .when().post("/").then().statusCode(200)
            .extract().path("RegistryArn");

        schemaVersionId = given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateSchema")
            .body("{"
                    + " \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" },"
                    + " \"SchemaName\": \"" + SCHEMA + "\","
                    + " \"DataFormat\": \"AVRO\","
                    + " \"Compatibility\": \"BACKWARD\","
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\""
                    + " }")
        .when().post("/").then().statusCode(200)
            .extract().path("SchemaVersionId");
    }

    @Test
    @Order(2)
    void tagRegistry() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.TagResource")
            .body("{ \"ResourceArn\": \"" + registryArn + "\", \"TagsToAdd\": { \"env\": \"prod\", \"team\": \"platform\" } }")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(3)
    void getTagsReturnsAddedTags() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTags")
            .body("{ \"ResourceArn\": \"" + registryArn + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Tags.env", equalTo("prod"))
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(4)
    void untagResourceRemovesKey() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UntagResource")
            .body("{ \"ResourceArn\": \"" + registryArn + "\", \"TagsToRemove\": [\"env\"] }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTags")
            .body("{ \"ResourceArn\": \"" + registryArn + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Tags.env", nullValue())
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(5)
    void putSchemaVersionMetadata() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.PutSchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\","
                    + " \"MetadataKeyValue\": { \"MetadataKey\": \"owner\", \"MetadataValue\": \"alice\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("MetadataKey", equalTo("owner"))
            .body("MetadataValue", equalTo("alice"))
            .body("SchemaVersionId", equalTo(schemaVersionId));
    }

    @Test
    @Order(6)
    void putDuplicateMetadataReturnsAlreadyExists() {
        try (GlueClient glue = glueClient()) {
            assertThrows(AlreadyExistsException.class, () ->
                    glue.putSchemaVersionMetadata(PutSchemaVersionMetadataRequest.builder()
                            .schemaVersionId(schemaVersionId)
                            .metadataKeyValue(MetadataKeyValuePair.builder()
                                    .metadataKey("owner").metadataValue("alice").build())
                            .build()));
        }
    }

    @Test
    @Order(7)
    void querySchemaVersionMetadataReturnsKey() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.QuerySchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(schemaVersionId))
            .body("MetadataInfoMap.owner.MetadataValue", equalTo("alice"))
            .body("MetadataInfoMap.owner.CreatedTime", notNullValue());
    }

    @Test
    @Order(8)
    void removeSchemaVersionMetadata() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RemoveSchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\","
                    + " \"MetadataKeyValue\": { \"MetadataKey\": \"owner\", \"MetadataValue\": \"alice\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("MetadataKey", equalTo("owner"));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.QuerySchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\" }")
        .when().post("/").then()
            .body("MetadataInfoMap.owner", nullValue());
    }

    @Test
    @Order(9)
    void removeUnknownMetadataReturnsNotFound() {
        try (GlueClient glue = glueClient()) {
            assertThrows(EntityNotFoundException.class, () ->
                    glue.removeSchemaVersionMetadata(RemoveSchemaVersionMetadataRequest.builder()
                            .schemaVersionId(schemaVersionId)
                            .metadataKeyValue(MetadataKeyValuePair.builder()
                                    .metadataKey("missing").metadataValue("x").build())
                            .build()));
        }
    }

    @Test
    @Order(10)
    void getTagsOnUnknownArnReturnsInvalidInput() {
        try (GlueClient glue = glueClient()) {
            assertThrows(InvalidInputException.class, () ->
                    glue.getTags(GetTagsRequest.builder()
                            .resourceArn("not-an-arn")
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
