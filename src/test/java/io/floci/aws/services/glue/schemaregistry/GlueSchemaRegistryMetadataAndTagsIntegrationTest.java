package io.floci.aws.services.glue.schemaregistry;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

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
            .body("RegistryName", equalTo(REGISTRY))
            .body("SchemaName", equalTo(SCHEMA))
            .body("LatestVersion", equalTo(true))
            .body("SchemaVersionId", equalTo(schemaVersionId));
    }

    @Test
    @Order(6)
    void putDuplicateMetadataReturnsAlreadyExists() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.PutSchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\","
                    + " \"MetadataKeyValue\": { \"MetadataKey\": \"owner\", \"MetadataValue\": \"alice\" } }")
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"));
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
            .body("MetadataInfoMap.owner.CreatedTime", matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
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
            .body("MetadataKey", equalTo("owner"))
            .body("RegistryName", equalTo(REGISTRY))
            .body("SchemaName", equalTo(SCHEMA))
            .body("LatestVersion", equalTo(true));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.QuerySchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\" }")
        .when().post("/").then()
            .body("MetadataInfoMap.owner", nullValue());
    }

    @Test
    @Order(9)
    void removeUnknownMetadataReturnsNotFound() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RemoveSchemaVersionMetadata")
            .body("{ \"SchemaVersionId\": \"" + schemaVersionId + "\","
                    + " \"MetadataKeyValue\": { \"MetadataKey\": \"missing\", \"MetadataValue\": \"x\" } }")
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(10)
    void getTagsOnUnknownArnReturnsInvalidInput() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetTags")
            .body("{ \"ResourceArn\": \"not-an-arn\" }")
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }
}
