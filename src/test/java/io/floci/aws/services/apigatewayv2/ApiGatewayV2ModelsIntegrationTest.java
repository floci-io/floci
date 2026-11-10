package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2ModelsIntegrationTest {

    private static String apiId;
    private static String modelId;

    // ──────────────────────────── Prerequisites ────────────────────────────

    @Test @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"models-rest-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("models-rest-test"))
                .body("protocolType", equalTo("HTTP"))
                .extract().path("apiId");
    }

    // ──────────────────────────── Model CRUD ────────────────────────────

    @Test @Order(2)
    void createModel() {
        modelId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name": "PetModel",
                          "schema": "{\\"$schema\\":\\"http://json-schema.org/draft-04/schema#\\",\\"title\\":\\"Pet\\",\\"type\\":\\"object\\"}",
                          "description": "Schema for a pet object",
                          "contentType": "application/json"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/models")
                .then()
                .statusCode(201)
                .body("modelId", notNullValue())
                .body("name", equalTo("PetModel"))
                .body("schema", notNullValue())
                .body("description", equalTo("Schema for a pet object"))
                .body("contentType", equalTo("application/json"))
                .extract().path("modelId");
    }

    @Test @Order(3)
    void getModel() {
        given()
                .when().get("/v2/apis/" + apiId + "/models/" + modelId)
                .then()
                .statusCode(200)
                .body("modelId", equalTo(modelId))
                .body("name", equalTo("PetModel"))
                .body("schema", notNullValue())
                .body("description", equalTo("Schema for a pet object"))
                .body("contentType", equalTo("application/json"));
    }

    @Test @Order(4)
    void getModels() {
        given()
                .when().get("/v2/apis/" + apiId + "/models")
                .then()
                .statusCode(200)
                .body("items", notNullValue())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.modelId", hasItem(modelId));
    }

    @Test @Order(5)
    void updateModel() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"updated description"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/models/" + modelId)
                .then()
                .statusCode(200)
                .body("modelId", equalTo(modelId))
                .body("description", equalTo("updated description"))
                .body("name", equalTo("PetModel"))
                .body("contentType", equalTo("application/json"));
    }

    @Test @Order(6)
    void deleteModel() {
        given()
                .when().delete("/v2/apis/" + apiId + "/models/" + modelId)
                .then()
                .statusCode(204);
    }

    @Test @Order(7)
    void getModelAfterDelete() {
        given()
                .when().get("/v2/apis/" + apiId + "/models/" + modelId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Parent Validation ────────────────────────────

    @Test @Order(8)
    void createModelWithNonExistentApi() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"test"}
                        """)
                .when().post("/v2/apis/nonexistent/models")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Not Found Errors ────────────────────────────

    @Test @Order(9)
    void getModelNotFound() {
        given()
                .when().get("/v2/apis/" + apiId + "/models/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(10)
    void updateModelNotFound() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"x"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/models/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(11)
    void deleteModelNotFound() {
        given()
                .when().delete("/v2/apis/" + apiId + "/models/nonexistent")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Listing Isolation ────────────────────────────

    @Test @Order(12)
    void listingIsolation() {
        // Create a second API
        String secondApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"models-rest-test-2","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        // Create a Model on the second API
        String secondModelId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"OtherModel","contentType":"application/json"}
                        """)
                .when().post("/v2/apis/" + secondApiId + "/models")
                .then()
                .statusCode(201)
                .body("modelId", notNullValue())
                .extract().path("modelId");

        // List Models for the first API — the second API's Model must NOT appear
        given()
                .when().get("/v2/apis/" + apiId + "/models")
                .then()
                .statusCode(200)
                .body("items.modelId", not(hasItem(secondModelId)));
    }
}
