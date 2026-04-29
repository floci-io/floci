package io.github.hectorvent.floci.services.apigateway;

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
class ApiGatewayIntegrationTest {

    private static String apiId;
    private static String rootId;
    private static String resourceId;
    private static String deploymentId;

    // ──────────────────────────── REST API lifecycle ────────────────────────────

    @Test @Order(1)
    void createRestApi() {
        String body = """
                {"name":"test-api","description":"Integration test API"}
                """;
        apiId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("test-api"))
                .body("description", equalTo("Integration test API"))
                .extract().path("id");
    }

    @Test @Order(2)
    void getRestApi() {
        given()
                .when().get("/restapis/" + apiId)
                .then()
                .statusCode(200)
                .body("id", equalTo(apiId))
                .body("name", equalTo("test-api"));
    }

    @Test @Order(3)
    void listRestApis() {
        given()
                .when().get("/restapis")
                .then()
                .statusCode(200)
                .body("item.id", hasItem(apiId));
    }

    @Test @Order(4)
    void getRestApiNotFound() {
        given()
                .when().get("/restapis/doesnotexist")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Resources ────────────────────────────

    @Test @Order(5)
    void getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .body("item", hasSize(1))
                .body("item[0].path", equalTo("/"))
                .extract().path("item[0].id");
    }

    @Test @Order(6)
    void createResource() {
        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"users\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("path", equalTo("/users"))
                .body("pathPart", equalTo("users"))
                .extract().path("id");
    }

    @Test @Order(7)
    void getResource() {
        given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId)
                .then()
                .statusCode(200)
                .body("path", equalTo("/users"));
    }

    // ──────────────────────────── Methods ────────────────────────────

    @Test @Order(8)
    void putMethod() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(201)
                .body("httpMethod", equalTo("GET"))
                .body("authorizationType", equalTo("NONE"));
    }

    @Test @Order(9)
    void putMethodResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(201)
                .body("statusCode", equalTo("200"));
    }

    @Test @Order(10)
    void getMethodResponse() {
        given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(200)
                .body("statusCode", equalTo("200"));
    }

    // ──────────────────────────── Integration ────────────────────────────

    @Test @Order(11)
    void putIntegration() {
        String body = """
                {"type":"MOCK","requestTemplates":{"application/json":"{\\"statusCode\\": 200}"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201)
                .body("type", equalTo("MOCK"));
    }

    @Test @Order(12)
    void getIntegration() {
        given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(200)
                .body("type", equalTo("MOCK"));
    }

    @Test @Order(13)
    void putIntegrationResponse() {
        String body = """
                {"selectionPattern":"","responseTemplates":{"application/json":""}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201)
                .body("statusCode", equalTo("200"));
    }

    @Test @Order(14)
    void getIntegrationResponse() {
        given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(200)
                .body("statusCode", equalTo("200"));
    }

    @Test @Order(15)
    void getMethodHasIntegration() {
        given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(200)
                .body("methodIntegration.type", equalTo("MOCK"));
    }

    // ──────────────────────────── Deployments ────────────────────────────

    @Test @Order(16)
    void createDeployment() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("description", equalTo("v1"))
                .extract().path("id");
    }

    @Test @Order(17)
    void getDeployments() {
        given()
                .when().get("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(200)
                .body("item.id", hasItem(deploymentId));
    }

    // ──────────────────────────── Stages ────────────────────────────

    @Test @Order(18)
    void createStage() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"prod\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201)
                .body("stageName", equalTo("prod"))
                .body("deploymentId", equalTo(deploymentId));
    }

    @Test @Order(19)
    void getStage() {
        given()
                .when().get("/restapis/" + apiId + "/stages/prod")
                .then()
                .statusCode(200)
                .body("stageName", equalTo("prod"));
    }

    @Test @Order(20)
    void listStages() {
        given()
                .when().get("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(200)
                .body("item.stageName", hasItem("prod"));
    }

    @Test @Order(21)
    void updateStage() {
        String patch = """
                {"patchOperations":[{"op":"replace","path":"/description","value":"Production"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(patch)
                .when().patch("/restapis/" + apiId + "/stages/prod")
                .then()
                .statusCode(200)
                .body("description", equalTo("Production"));
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Test @Order(22)
    void tagResource() {
        String arn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        given()
                .contentType(ContentType.JSON)
                .body("{\"tags\":{\"env\":\"test\"}}")
                .when().put("/tags/" + arn)
                .then()
                .statusCode(204);
    }

    @Test @Order(23)
    void getTags() {
        String arn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"));
    }

    @Test @Order(24)
    void untagResource() {
        String arn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId;
        given()
                .queryParam("tagKeys", "env")
                .when().delete("/tags/" + arn)
                .then()
                .statusCode(204);

        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("tags.env", nullValue());
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test @Order(25)
    void deleteStage() {
        given()
                .when().delete("/restapis/" + apiId + "/stages/prod")
                .then()
                .statusCode(202);
    }

    @Test @Order(26)
    void deleteResource() {
        given()
                .when().delete("/restapis/" + apiId + "/resources/" + resourceId)
                .then()
                .statusCode(204);
    }

    @Test @Order(27)
    void deleteRestApi() {
        given()
                .when().delete("/restapis/" + apiId)
                .then()
                .statusCode(202);
    }

    @Test @Order(28)
    void getDeletedRestApiReturns404() {
        given()
                .when().get("/restapis/" + apiId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── _custom_id_ tag ────────────────────────────

    @Test @Order(50)
    void createRestApi_customIdTag_usesTagValueAsApiId() {
        String body = """
                {"name":"custom-id-api","tags":{"_custom_id_":"MYCUSTOMNAME"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", equalTo("MYCUSTOMNAME"))
                .body("tags._custom_id_", equalTo("MYCUSTOMNAME"));
    }

    @Test @Order(51)
    void getRestApi_customId_resolvesById() {
        given()
                .when().get("/restapis/MYCUSTOMNAME")
                .then()
                .statusCode(200)
                .body("id", equalTo("MYCUSTOMNAME"))
                .body("name", equalTo("custom-id-api"));
    }

    @Test @Order(52)
    void deleteRestApi_customId() {
        given()
                .when().delete("/restapis/MYCUSTOMNAME")
                .then()
                .statusCode(202);
    }
}
