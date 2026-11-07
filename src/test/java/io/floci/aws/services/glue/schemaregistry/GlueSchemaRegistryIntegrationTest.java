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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueSchemaRegistryIntegrationTest {

    private static final String REGISTRY_NAME = "integration-registry";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createRegistry() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY_NAME + "\", \"Description\": \"test\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RegistryName", equalTo(REGISTRY_NAME))
            .body("RegistryArn", containsString(":registry/" + REGISTRY_NAME))
            .body("Status", equalTo("AVAILABLE"));
    }

    @Test
    @Order(2)
    void createDuplicateRegistryReturnsAlreadyExists() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"" + REGISTRY_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AlreadyExistsException"));
    }

    @Test
    @Order(3)
    void createRegistryWithInvalidNameReturnsInvalidInput() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.CreateRegistry")
            .body("{ \"RegistryName\": \"bad name with spaces\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(4)
    void getRegistryByName() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetRegistry")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY_NAME + "\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RegistryName", equalTo(REGISTRY_NAME))
            .body("Description", equalTo("test"))
            .body("Status", equalTo("AVAILABLE"))
            .body("CreatedTime", matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
    }

    @Test
    @Order(5)
    void listRegistriesIncludesCreated() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.ListRegistries")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Registries.RegistryName", hasItem(REGISTRY_NAME))
            .body("Registries.find { it.RegistryName == '" + REGISTRY_NAME + "' }.Tags", nullValue())
            .body("Registries.find { it.RegistryName == '" + REGISTRY_NAME + "' }.CreatedTime",
                    matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
    }

    @Test
    @Order(6)
    void updateRegistryDescription() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.UpdateRegistry")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY_NAME + "\" }, \"Description\": \"updated\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RegistryName", equalTo(REGISTRY_NAME))
            .body("RegistryArn", containsString(":registry/" + REGISTRY_NAME))
            .body("Description", nullValue());

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetRegistry")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY_NAME + "\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Description", equalTo("updated"));
    }

    @Test
    @Order(7)
    void getRegistryWithMalformedArnReturnsInvalidInput() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetRegistry")
            .body("{ \"RegistryId\": { \"RegistryArn\": \"not-an-arn\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(8)
    void getRegistryWithoutIdAutoCreatesDefault() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetRegistry")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RegistryName", equalTo("default-registry"));
    }

    @Test
    @Order(9)
    void deleteRegistryReturnsDeletingStatus() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.DeleteRegistry")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY_NAME + "\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Status", equalTo("DELETING"));
    }

    @Test
    @Order(10)
    void getDeletedRegistryReturnsNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSGlue.GetRegistry")
            .body("{ \"RegistryId\": { \"RegistryName\": \"" + REGISTRY_NAME + "\" } }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }
}
