package io.floci.aws.services.glue.schemaregistry;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueSchemaRegistryAdminIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGISTRY = "admin-it-registry";
    private static final String SCHEMA = "users-admin";

    private static final String AVRO_V1 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"}]}";

    private static final String AVRO_V2 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"},"
                    + "{\\\"name\\\":\\\"email\\\",\\\"type\\\":[\\\"null\\\",\\\"string\\\"],\\\"default\\\":null}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void seed_createRegistryAndSchemaWithTwoVersions() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY + "\" }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateSchema")
            .body("{"
                    + " \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" },"
                    + " \"SchemaName\": \"" + SCHEMA + "\","
                    + " \"DataFormat\": \"AVRO\","
                    + " \"Compatibility\": \"BACKWARD\","
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\""
                    + " }")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V2 + "\" }")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(2)
    void getSchema() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("SchemaName", equalTo(SCHEMA))
            .body("LatestSchemaVersion", equalTo(2));
    }

    @Test
    @Order(3)
    void updateSchemaCompatibility() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UpdateSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"Compatibility\": \"FORWARD\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("SchemaArn", containsString(":schema/" + REGISTRY + "/" + SCHEMA));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" } }")
        .when().post("/").then()
            .body("Compatibility", equalTo("FORWARD"));
    }

    @Test
    @Order(4)
    void listSchemasIncludesCreated() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.ListSchemas")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("Schemas.SchemaName", hasItem(SCHEMA))
            .body("Schemas.find { it.SchemaName == '" + SCHEMA + "' }.DataFormat", nullValue())
            .body("Schemas.find { it.SchemaName == '" + SCHEMA + "' }.Tags", nullValue());
    }

    @Test
    @Order(5)
    void listSchemaVersionsReturnsBoth() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.ListSchemaVersions")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("Schemas", hasSize(greaterThanOrEqualTo(2)))
            .body("Schemas.VersionNumber", hasItem(1))
            .body("Schemas.VersionNumber", hasItem(2))
            .body("Schemas.find { it.VersionNumber == 1 }.SchemaArn", notNullValue())
            .body("Schemas.find { it.VersionNumber == 1 }.SchemaVersionId", notNullValue())
            .body("Schemas.find { it.VersionNumber == 1 }.Status", equalTo("AVAILABLE"))
            .body("Schemas.find { it.VersionNumber == 1 }.CreatedTime", notNullValue())
            .body("Schemas.find { it.VersionNumber == 1 }.DataFormat", nullValue())
            .body("Schemas.find { it.VersionNumber == 1 }.SchemaDefinition", nullValue());
    }

    @Test
    @Order(6)
    void getSchemaVersionsDiffReturnsTextDiff() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaVersionsDiff")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"FirstSchemaVersionNumber\": { \"VersionNumber\": 1 },"
                    + " \"SecondSchemaVersionNumber\": { \"VersionNumber\": 2 },"
                    + " \"SchemaDiffType\": \"SYNTAX_DIFF\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Diff", notNullValue())
            .body("Diff", containsString("---"));
    }

    @Test
    @Order(7)
    void checkSchemaVersionValidityForValidAvro() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CheckSchemaVersionValidity")
            .body("{ \"DataFormat\": \"AVRO\", \"SchemaDefinition\": \"" + AVRO_V1 + "\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Valid", equalTo(true))
            .body("Error", nullValue());
    }

    @Test
    @Order(8)
    void checkSchemaVersionValidityForInvalidAvro() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CheckSchemaVersionValidity")
            .body("{ \"DataFormat\": \"AVRO\", \"SchemaDefinition\": \"{not-valid-avro\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("Valid", equalTo(false))
            .body("Error", notNullValue());
    }

    @Test
    @Order(9)
    void deleteSchemaVersionsRemovesVersion1() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UpdateSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaVersionNumber\": { \"VersionNumber\": 2 } }")
        .when().post("/").then()
            .statusCode(200)
            .body("SchemaArn", containsString(":schema/" + REGISTRY + "/" + SCHEMA));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.DeleteSchemaVersions")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"Versions\": \"1\" }")
        .when().post("/").then()
            .statusCode(200)
            .body("SchemaArn", nullValue())
            .body("SchemaVersionErrors", hasSize(0));
    }

    @Test
    @Order(10)
    void deleteSchemaCascades() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.DeleteSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" } }")
        .when().post("/").then()
            .statusCode(200)
            .body("Status", equalTo("DELETING"));

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchema")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" } }")
        .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }
}
