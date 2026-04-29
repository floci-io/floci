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
import software.amazon.awssdk.services.glue.model.CreateRegistryRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetRegistryRequest;
import software.amazon.awssdk.services.glue.model.InvalidInputException;
import software.amazon.awssdk.services.glue.model.RegistryId;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlueSchemaRegistryIntegrationTest {

    private static final String REGISTRY_NAME = "integration-registry";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @TestHTTPResource("/")
    URI endpoint;

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
        try (GlueClient glue = glueClient()) {
            assertThrows(AlreadyExistsException.class, () ->
                    glue.createRegistry(CreateRegistryRequest.builder()
                            .registryName(REGISTRY_NAME)
                            .build()));
        }
    }

    @Test
    @Order(3)
    void createRegistryWithInvalidNameReturnsInvalidInput() {
        try (GlueClient glue = glueClient()) {
            assertThrows(InvalidInputException.class, () ->
                    glue.createRegistry(CreateRegistryRequest.builder()
                            .registryName("bad name with spaces")
                            .build()));
        }
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
            .body("CreatedTime", notNullValue());
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
            .body("Registries.RegistryName", hasItem(REGISTRY_NAME));
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
            .body("Description", equalTo("updated"));
    }

    @Test
    @Order(7)
    void getRegistryWithMalformedArnReturnsInvalidInput() {
        try (GlueClient glue = glueClient()) {
            assertThrows(InvalidInputException.class, () ->
                    glue.getRegistry(GetRegistryRequest.builder()
                            .registryId(RegistryId.builder().registryArn("not-an-arn").build())
                            .build()));
        }
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
        try (GlueClient glue = glueClient()) {
            assertThrows(EntityNotFoundException.class, () ->
                    glue.getRegistry(GetRegistryRequest.builder()
                            .registryId(RegistryId.builder().registryName(REGISTRY_NAME).build())
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
