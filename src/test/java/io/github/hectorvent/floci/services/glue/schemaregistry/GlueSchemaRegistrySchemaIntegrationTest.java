package io.github.hectorvent.floci.services.glue.schemaregistry;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueSchemaRegistrySchemaIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGISTRY = "schema-it-registry";
    private static final String SCHEMA = "users";

    private static final String AVRO_V1 =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"}]}";

    private static final String AVRO_V2_OK =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"},"
                    + "{\\\"name\\\":\\\"email\\\",\\\"type\\\":[\\\"null\\\",\\\"string\\\"],\\\"default\\\":null}]}";

    private static final String AVRO_V2_BAD =
            "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"namespace\\\":\\\"x\\\","
                    + "\\\"fields\\\":[{\\\"name\\\":\\\"id\\\",\\\"type\\\":\\\"long\\\"},"
                    + "{\\\"name\\\":\\\"email\\\",\\\"type\\\":\\\"string\\\"}]}";

    private static final String AVRO_V1_WITH_CUSTOM_ATTRIBUTES = AVRO_V1
            .replace("\\\"fields\\\"", "\\\"x-record\\\":\\\"custom\\\",\\\"fields\\\"")
            .replace("\\\"type\\\":\\\"long\\\"", "\\\"type\\\":\\\"long\\\",\\\"x-field\\\":\\\"custom\\\"");

    private static String createdSchemaVersionId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createRegistryForSchemaTests() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createSchemaCreatesV1() {
        createdSchemaVersionId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateSchema")
            .body("{"
                    + " \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" },"
                    + " \"SchemaName\": \"" + SCHEMA + "\","
                    + " \"DataFormat\": \"AVRO\","
                    + " \"Compatibility\": \"BACKWARD\","
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\""
                    + " }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaName", equalTo(SCHEMA))
            .body("RegistryName", equalTo(REGISTRY))
            .body("SchemaArn", containsString(":schema/" + REGISTRY + "/" + SCHEMA))
            .body("DataFormat", equalTo("AVRO"))
            .body("Compatibility", equalTo("BACKWARD"))
            .body("SchemaStatus", equalTo("AVAILABLE"))
            .body("LatestSchemaVersion", equalTo(1))
            .body("NextSchemaVersion", equalTo(2))
            .body("SchemaVersionId", notNullValue())
            .body("SchemaVersionStatus", equalTo("AVAILABLE"))
        .extract().path("SchemaVersionId");
    }

    @Test
    @Order(3)
    void getSchemaByDefinitionFindsExistingVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaByDefinition")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(createdSchemaVersionId))
            .body("DataFormat", equalTo("AVRO"))
            .body("Status", equalTo("AVAILABLE"));
    }

    @Test
    @Order(4)
    void getSchemaByDefinitionMissingReturnsNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaByDefinition")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V2_OK + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    @Order(5)
    void registerSchemaVersionAcceptsBackwardCompatible() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V2_OK + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VersionNumber", equalTo(2))
            .body("Status", equalTo("AVAILABLE"))
            .body("SchemaVersionId", notNullValue());
    }

    @Test
    @Order(6)
    void registerSchemaVersionRejectsIncompatibleEvolution() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V2_BAD + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(7)
    void registerSchemaVersionDuplicateReturnsExistingId() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V1 + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(createdSchemaVersionId))
            .body("VersionNumber", equalTo(1));
    }

    @Test
    @Order(8)
    void getSchemaVersionByLatestReturnsV2() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaVersionNumber\": { \"LatestVersion\": true } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VersionNumber", equalTo(2))
            .body("DataFormat", equalTo("AVRO"))
            .body("Status", equalTo("AVAILABLE"))
            .body("SchemaDefinition", notNullValue());
    }

    @Test
    @Order(9)
    void getSchemaVersionByNumberReturnsV1() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaVersionNumber\": { \"VersionNumber\": 1 } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VersionNumber", equalTo(1))
            .body("SchemaVersionId", equalTo(createdSchemaVersionId));
    }

    @Test
    @Order(10)
    void getSchemaVersionByIdReturnsThatVersion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaVersion")
            .body("{ \"SchemaVersionId\": \"" + createdSchemaVersionId + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(createdSchemaVersionId))
            .body("VersionNumber", equalTo(1));
    }

    @Test
    @Order(11)
    void createSchemaWithInvalidAvroDefinitionReturns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateSchema")
            .body("{"
                    + " \"RegistryId\": { \"RegistryName\": \"" + REGISTRY + "\" },"
                    + " \"SchemaName\": \"bad\","
                    + " \"DataFormat\": \"AVRO\","
                    + " \"Compatibility\": \"BACKWARD\","
                    + " \"SchemaDefinition\": \"{not-valid-avro\""
                    + " }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(12)
    void customAvroAttributesReuseExistingVersionForRegistrationAndLookup() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.RegisterSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V1_WITH_CUSTOM_ATTRIBUTES + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(createdSchemaVersionId))
            .body("VersionNumber", equalTo(1));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaByDefinition")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaDefinition\": \"" + AVRO_V1_WITH_CUSTOM_ATTRIBUTES + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SchemaVersionId", equalTo(createdSchemaVersionId));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetSchemaVersion")
            .body("{ \"SchemaId\": { \"RegistryName\": \"" + REGISTRY + "\", \"SchemaName\": \"" + SCHEMA + "\" },"
                    + " \"SchemaVersionNumber\": { \"LatestVersion\": true } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("VersionNumber", equalTo(2));
    }
}
