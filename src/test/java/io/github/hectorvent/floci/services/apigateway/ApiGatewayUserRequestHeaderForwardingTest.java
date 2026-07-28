package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Reproducer: a request's custom headers must reach the integration on the {@code _user_request_}
 * dispatch path, the same as on {@code /execute-api/}. Uses a MOCK integration whose response
 * template echoes {@code $input.params('X-Custom')} so no Lambda container is needed.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayUserRequestHeaderForwardingTest {

    private static String apiId;
    private static String rootId;
    private static String resourceId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"user-request-header-echo\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
    }

    @Test @Order(2)
    void setupMockHeaderEcho() {
        rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");

        resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"echo\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        // Response template echoes the incoming X-Custom request header into the body.
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":"
                        + "\"{\\\"echo\\\":\\\"$input.params('X-Custom')\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(3)
    void deploy() {
        String deploymentId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"prod\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test @Order(4)
    void headerReachesIntegrationViaExecuteApiPath() {
        given().header("X-Custom", "reproduce-me")
                .when().get("/execute-api/" + apiId + "/prod/echo")
                .then().statusCode(200).body("echo", equalTo("reproduce-me"));
    }

    @Test @Order(5)
    void headerReachesIntegrationViaUserRequestPath() {
        given().header("X-Custom", "reproduce-me")
                .when().get("/restapis/" + apiId + "/prod/_user_request_/echo")
                .then().statusCode(200).body("echo", equalTo("reproduce-me"));
    }

    @Test @Order(6)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}
