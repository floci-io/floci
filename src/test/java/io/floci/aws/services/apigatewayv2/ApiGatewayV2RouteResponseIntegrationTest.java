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
class ApiGatewayV2RouteResponseIntegrationTest {

    private static String apiId;
    private static String routeId;
    private static String routeResponseId;

    // ──────────────────────────── Prerequisites ────────────────────────────

    @Test @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"test-ws-api","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("test-ws-api"))
                .body("protocolType", equalTo("WEBSOCKET"))
                .extract().path("apiId");
    }

    @Test @Order(2)
    void createRoute() {
        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeId", notNullValue())
                .body("routeKey", equalTo("$default"))
                .extract().path("routeId");
    }

    // ──────────────────────────── Route Response CRUD ────────────────────────────

    @Test @Order(3)
    void createRouteResponse() {
        routeResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeResponseKey": "$default",
                          "modelSelectionExpression": "$default",
                          "responseModels": {"application/json": "Empty"},
                          "responseParameters": {"method.response.header.Content-Type": "integration.response.header.Content-Type"}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then()
                .statusCode(201)
                .body("routeResponseId", notNullValue())
                .body("routeResponseKey", equalTo("$default"))
                .body("routeId", equalTo(routeId))
                .body("modelSelectionExpression", equalTo("$default"))
                .body("responseModels", notNullValue())
                .body("responseParameters", notNullValue())
                .extract().path("routeResponseId");
    }

    @Test @Order(4)
    void getRouteResponse() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/" + routeResponseId)
                .then()
                .statusCode(200)
                .body("routeResponseId", equalTo(routeResponseId))
                .body("routeResponseKey", equalTo("$default"))
                .body("routeId", equalTo(routeId))
                .body("modelSelectionExpression", equalTo("$default"))
                .body("responseModels", notNullValue())
                .body("responseParameters", notNullValue());
    }

    @Test @Order(5)
    void getRouteResponses() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then()
                .statusCode(200)
                .body("items", notNullValue())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.routeResponseId", hasItem(routeResponseId));
    }

    @Test @Order(6)
    void updateRouteResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey": "$updated"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/" + routeResponseId)
                .then()
                .statusCode(200)
                .body("routeResponseId", equalTo(routeResponseId))
                .body("routeResponseKey", equalTo("$updated"))
                .body("modelSelectionExpression", equalTo("$default"));
    }

    @Test @Order(7)
    void deleteRouteResponse() {
        given()
                .when().delete("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/" + routeResponseId)
                .then()
                .statusCode(204);
    }

    @Test @Order(8)
    void getRouteResponseAfterDelete() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/" + routeResponseId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Parent Validation ────────────────────────────

    @Test @Order(9)
    void createRouteResponseWithNonExistentApi() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/nonexistent/routes/nonexistent/routeresponses")
                .then()
                .statusCode(404);
    }

    @Test @Order(10)
    void createRouteResponseWithNonExistentRoute() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes/nonexistent/routeresponses")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Not Found Errors ────────────────────────────

    @Test @Order(11)
    void getRouteResponseNotFound() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(12)
    void updateRouteResponseNotFound() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey": "$default"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(13)
    void deleteRouteResponseNotFound() {
        given()
                .when().delete("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Listing Isolation ────────────────────────────

    @Test @Order(14)
    void listingIsolation() {
        // Create a second route
        String secondRouteId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"$connect"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeId", notNullValue())
                .extract().path("routeId");

        // Create a route response on the second route
        String secondRouteResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes/" + secondRouteId + "/routeresponses")
                .then()
                .statusCode(201)
                .body("routeResponseId", notNullValue())
                .extract().path("routeResponseId");

        // List route responses for the first route — the second route's response must NOT appear
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then()
                .statusCode(200)
                .body("items.routeResponseId", not(hasItem(secondRouteResponseId)));
    }
}
