package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Verifies that the LocalStack-compatible {@code _user_request_} URL format
 * correctly routes to the same execution logic as the standard execute-api path.
 *
 * <p>LocalStack URL: {@code /restapis/{apiId}/{stageName}/_user_request_/{proxy+}}
 * <p>Standard URL:   {@code /execute-api/{apiId}/{stageName}/{proxy+}}
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayUserRequestIntegrationTest {

    private static String apiId;
    private static String rootId;
    private static String resourceId;
    private static String deploymentId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"user-request-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");
    }

    @Test @Order(2)
    void setupMockIntegration() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"hello\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"ok\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
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
                .body("{\"stageName\":\"prod\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test @Order(4)
    void executeViaUserRequestPath() {
        given()
                .when().get("/restapis/" + apiId + "/prod/_user_request_/hello")
                .then()
                .statusCode(200)
                .body("message", equalTo("ok"));
    }

    @Test @Order(5)
    void executeViaStandardExecuteApiPath() {
        given()
                .when().get("/execute-api/" + apiId + "/prod/hello")
                .then()
                .statusCode(200)
                .body("message", equalTo("ok"));
    }

    @Test @Order(6)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test @Order(7)
    void executeViaUserRequestPathFindsRestApiCreatedInNonDefaultRegionWithoutAuthHeader() {
        String euWest2Auth = "AWS4-HMAC-SHA256 Credential=test/20260525/eu-west-2/apigateway/aws4_request, SignedHeaders=host, Signature=test";
        String localApiId = null;

        try {
            localApiId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"name\":\"user-request-region-fallback-test\"}")
                    .when().post("/restapis")
                    .then()
                    .statusCode(201)
                    .body("id", notNullValue())
                    .extract().path("id");

            String localRootId = given()
                    .header("Authorization", euWest2Auth)
                    .when().get("/restapis/" + localApiId + "/resources")
                    .then()
                    .statusCode(200)
                    .extract().path("item[0].id");

            String localResourceId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"pathPart\":\"hello\"}")
                    .when().post("/restapis/" + localApiId + "/resources/" + localRootId)
                    .then()
                    .statusCode(201)
                    .extract().path("id");

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"authorizationType\":\"NONE\"}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"responseParameters\":{}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/responses/200")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/integration")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"ok\\\"}\"}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/integration/responses/200")
                    .then()
                    .statusCode(201);

            String localDeploymentId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"description\":\"region fallback\"}")
                    .when().post("/restapis/" + localApiId + "/deployments")
                    .then()
                    .statusCode(201)
                    .extract().path("id");

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"stageName\":\"dev\",\"deploymentId\":\"" + localDeploymentId + "\"}")
                    .when().post("/restapis/" + localApiId + "/stages")
                    .then()
                    .statusCode(201);

            given()
                    .when().get("/restapis/" + localApiId + "/dev/_user_request_/hello")
                    .then()
                    .statusCode(200)
                    .body("message", equalTo("ok"));
        } finally {
            if (localApiId != null) {
                given()
                        .header("Authorization", euWest2Auth)
                        .when().delete("/restapis/" + localApiId)
                        .then()
                        .statusCode(anyOf(equalTo(202), equalTo(404)));
            }

        }
    }

    @Test @Order(8)
    void executeViaUserRequestPathDoesNotFallbackWhenSigV4RegionIsExplicit() {
        String euWest2Auth = "AWS4-HMAC-SHA256 Credential=test/20260525/eu-west-2/apigateway/aws4_request, SignedHeaders=host, Signature=test";
        String usEast1Auth = "AWS4-HMAC-SHA256 Credential=test/20260525/us-east-1/apigateway/aws4_request, SignedHeaders=host, Signature=test";
        String localApiId = null;

        try {
            localApiId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"name\":\"user-request-explicit-region-test\"}")
                    .when().post("/restapis")
                    .then()
                    .statusCode(201)
                    .body("id", notNullValue())
                    .extract().path("id");

            String localRootId = given()
                    .header("Authorization", euWest2Auth)
                    .when().get("/restapis/" + localApiId + "/resources")
                    .then()
                    .statusCode(200)
                    .extract().path("item[0].id");

            String localResourceId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"pathPart\":\"hello\"}")
                    .when().post("/restapis/" + localApiId + "/resources/" + localRootId)
                    .then()
                    .statusCode(201)
                    .extract().path("id");

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"authorizationType\":\"NONE\"}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"responseParameters\":{}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/responses/200")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/integration")
                    .then()
                    .statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"ok\\\"}\"}}")
                    .when().put("/restapis/" + localApiId + "/resources/" + localResourceId + "/methods/GET/integration/responses/200")
                    .then()
                    .statusCode(201);

            String localDeploymentId = given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"description\":\"explicit region\"}")
                    .when().post("/restapis/" + localApiId + "/deployments")
                    .then()
                    .statusCode(201)
                    .extract().path("id");

            given()
                    .contentType(ContentType.JSON)
                    .header("Authorization", euWest2Auth)
                    .body("{\"stageName\":\"dev\",\"deploymentId\":\"" + localDeploymentId + "\"}")
                    .when().post("/restapis/" + localApiId + "/stages")
                    .then()
                    .statusCode(201);

            given()
                    .header("Authorization", usEast1Auth)
                    .when().get("/restapis/" + localApiId + "/dev/_user_request_/hello")
                    .then()
                    .statusCode(404)
                    .body("message", equalTo("Invalid API id specified"));
        } finally {
            if (localApiId != null) {
                given()
                        .header("Authorization", euWest2Auth)
                        .when().delete("/restapis/" + localApiId)
                        .then()
                        .statusCode(anyOf(equalTo(202), equalTo(404)));
            }
        }
    }
}