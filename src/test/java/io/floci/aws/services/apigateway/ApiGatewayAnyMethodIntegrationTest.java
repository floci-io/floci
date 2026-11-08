package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that a resource configured with HTTP method ANY matches concrete
 * incoming HTTP methods (GET, POST, PUT, PATCH, DELETE).
 *
 * @see <a href="https://github.com/floci-io/floci/issues/710">Issue #710</a>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayAnyMethodIntegrationTest {

    private static String apiId;
    private static String rootId;
    private static String anyResourceId;
    private static String getResourceId;
    private static String deploymentId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"any-method-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");
    }

    @Test @Order(2)
    void setupAnyMethodMockIntegration() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        anyResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"any\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\"any\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test @Order(3)
    void setupConcreteGetResource() {
        getResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"get\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + getResourceId + "/methods/GET")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + getResourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + getResourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\"get\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + getResourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test @Order(4)
    void createDeploymentAndStage() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test @Order(5)
    void anyMethodMatchesGet() {
        given()
                .when().get("/execute-api/" + apiId + "/test/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    @Test @Order(6)
    void anyMethodMatchesPost() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/execute-api/" + apiId + "/test/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    @Test @Order(7)
    void anyMethodMatchesPut() {
        given()
                .contentType(ContentType.JSON)
                .when().put("/execute-api/" + apiId + "/test/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    @Test @Order(8)
    void anyMethodMatchesPatch() {
        given()
                .contentType(ContentType.JSON)
                .when().patch("/execute-api/" + apiId + "/test/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    @Test @Order(9)
    void anyMethodMatchesDelete() {
        given()
                .when().delete("/execute-api/" + apiId + "/test/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    @Test @Order(10)
    void concreteMethodStillWorks() {
        given()
                .when().get("/execute-api/" + apiId + "/test/get")
                .then()
                .statusCode(200)
                .body("matched", equalTo("get"));
    }

    @Test @Order(11)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}