package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUpdateModelIntegrationTest {

    private String createApi(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String createModel(String apiId, String modelName) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + modelName + "\",\"description\":\"old\",\"contentType\":\"application/json\",\"schema\":\"{}\"}")
                .pathParam("apiId", apiId)
                .when()
                .post("/restapis/{apiId}/models")
                .then()
                .statusCode(201)
                .extract()
                .path("name");
    }

    @Test
    void testCreateApiAndModelWorkflow() {
        String apiId = createApi("model-test-api");
        String modelName = createModel(apiId, "TestModel");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]}")
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .patch("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("description", equalTo("new"));

        given()
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .get("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("name", equalTo("TestModel"))
                .body("description", equalTo("new"));
    }

    @Test
    void testUpdateModelSchemaAndContentTypeViaPatchOperations() {
        String apiId = createApi("model-patch-api");
        String modelName = createModel(apiId, "PatchModel");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/schema\",\"value\":\"{\\\"type\\\":\\\"object\\\"}\"},"
                        + "{\"op\":\"replace\",\"path\":\"/contentType\",\"value\":\"application/xml\"}]}")
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .patch("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("contentType", equalTo("application/xml"))
                .body("schema", equalTo("{\"type\":\"object\"}"));

        given()
                .pathParam("apiId", apiId)
                .pathParam("modelName", modelName)
                .when()
                .get("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("contentType", equalTo("application/xml"));
    }

    /** AWS treats model names as immutable; a rename must be rejected rather than clobber a sibling. */
    @Test
    void testRenameIsRejectedAndDoesNotDestroyAnotherModel() {
        String apiId = createApi("model-rename-api");
        createModel(apiId, "SourceModel");
        createModel(apiId, "TargetModel");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"TargetModel\"}]}")
                .pathParam("apiId", apiId)
                .pathParam("modelName", "SourceModel")
                .when()
                .patch("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(400);

        given()
                .pathParam("apiId", apiId)
                .pathParam("modelName", "SourceModel")
                .when()
                .get("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("name", equalTo("SourceModel"));

        given()
                .pathParam("apiId", apiId)
                .pathParam("modelName", "TargetModel")
                .when()
                .get("/restapis/{apiId}/models/{modelName}")
                .then()
                .statusCode(200)
                .body("name", equalTo("TargetModel"))
                .body("description", equalTo("old"));
    }
}
