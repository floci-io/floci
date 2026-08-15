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
class ApiGatewayRoutingFallthroughIntegrationTest {

    private static String apiId;
    private static String rootId;
    private static String proxyResourceId;
    private static String concreteResourceId;
    private static String deploymentId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"routing-fallthrough-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");
    }

    @Test @Order(2)
    void setupResourcesAndMethods() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        // Create /{proxy+} resource
        proxyResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // Create method ANY on /{proxy+}
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + proxyResourceId + "/methods/ANY")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + proxyResourceId + "/methods/ANY/responses/200")
                .then()
                .statusCode(201);

        // Put MOCK integration for /{proxy+} ANY
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + proxyResourceId + "/methods/ANY/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\"proxy\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + proxyResourceId + "/methods/ANY/integration/responses/200")
                .then()
                .statusCode(201);

        // Create /widgets resource
        concreteResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"widgets\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // NOTE: We do NOT create any methods on the /widgets resource! It is method-less.

        // Create /devices resource
        String devicesResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"devices\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // Create method POST on /devices
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + devicesResourceId + "/methods/POST")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + devicesResourceId + "/methods/POST/responses/200")
                .then()
                .statusCode(201);

        // Put MOCK integration for /devices POST
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + devicesResourceId + "/methods/POST/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\"devices-post\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + devicesResourceId + "/methods/POST/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test @Order(3)
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

    @Test @Order(4)
    void getWidgetsFallsThroughToProxy() {
        // Request GET /widgets.
        // /widgets has no GET method, so it should fall through to /{proxy+} ANY
        given()
                .when().get("/execute-api/" + apiId + "/test/widgets")
                .then()
                .statusCode(200)
                .body("matched", equalTo("proxy"));
    }

    @Test @Order(5)
    void postWidgetsFallsThroughToProxy() {
        // Request POST /widgets.
        // /widgets has no POST method, so it should fall through to /{proxy+} ANY
        given()
                .contentType(ContentType.JSON)
                .when().post("/execute-api/" + apiId + "/test/widgets")
                .then()
                .statusCode(200)
                .body("matched", equalTo("proxy"));
    }

    @Test @Order(6)
    void otherPathMatchesProxy() {
        // Request GET /other.
        // /other matches /{proxy+} ANY
        given()
                .when().get("/execute-api/" + apiId + "/test/other")
                .then()
                .statusCode(200)
                .body("matched", equalTo("proxy"));
    }

    @Test @Order(7)
    void getDevicesReturns405() {
        // Request GET /devices.
        // /devices has POST but no GET, so it should return 405 Method Not Allowed,
        // and NOT fall back to /{proxy+} ANY.
        given()
                .when().get("/execute-api/" + apiId + "/test/devices")
                .then()
                .statusCode(405)
                .body("message", equalTo("Method Not Allowed"));
    }

    @Test @Order(8)
    void postDevicesSucceeds() {
        // Request POST /devices.
        // /devices has POST, so it should succeed.
        given()
                .contentType(ContentType.JSON)
                .when().post("/execute-api/" + apiId + "/test/devices")
                .then()
                .statusCode(200)
                .body("matched", equalTo("devices-post"));
    }

    @Test @Order(9)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}
