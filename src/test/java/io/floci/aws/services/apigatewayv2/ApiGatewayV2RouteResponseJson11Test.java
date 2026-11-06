package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for API Gateway v2 Route Response CRUD via the JSON 1.1 path.
 * Verifies PascalCase key normalization and all Route Response CRUD operations
 * through the AmazonApiGatewayV2.* X-Amz-Target header.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2RouteResponseJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;
    private static String routeId;
    private static String routeResponseId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── CreateApi ────────────────────────────

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"rr-json11-test","ProtocolType":"WEBSOCKET","RouteSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("rr-json11-test"))
                .body("ProtocolType", equalTo("WEBSOCKET"))
                .extract().path("ApiId");
    }

    // ──────────────────────────── CreateRoute ────────────────────────────

    @Test
    @Order(2)
    void createRoute() {
        routeId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"$default","AuthorizationType":"NONE","RouteResponseSelectionExpression":"$default"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("RouteId", notNullValue())
                .body("RouteKey", equalTo("$default"))
                .extract().path("RouteId");
    }

    // ──────────────────────────── CreateRouteResponse ────────────────────────────

    @Test
    @Order(3)
    void createRouteResponse() {
        routeResponseId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRouteResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseKey":"$default","ModelSelectionExpression":"$default"}
                        """.formatted(apiId, routeId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("RouteResponseId", notNullValue())
                .body("RouteResponseKey", equalTo("$default"))
                .body("ModelSelectionExpression", equalTo("$default"))
                .extract().path("RouteResponseId");
    }

    // ──────────────────────────── GetRouteResponse ────────────────────────────

    @Test
    @Order(4)
    void getRouteResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRouteResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseId":"%s"}
                        """.formatted(apiId, routeId, routeResponseId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteResponseId", equalTo(routeResponseId))
                .body("RouteResponseKey", equalTo("$default"))
                .body("ModelSelectionExpression", equalTo("$default"));
    }

    // ──────────────────────────── GetRouteResponses ────────────────────────────

    @Test
    @Order(5)
    void getRouteResponses() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRouteResponses")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s"}
                        """.formatted(apiId, routeId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Items", notNullValue())
                .body("Items.RouteResponseId", hasItem(routeResponseId));
    }

    // ──────────────────────────── UpdateRouteResponse ────────────────────────────

    @Test
    @Order(6)
    void updateRouteResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateRouteResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseId":"%s","RouteResponseKey":"$updated"}
                        """.formatted(apiId, routeId, routeResponseId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("RouteResponseId", equalTo(routeResponseId))
                .body("RouteResponseKey", equalTo("$updated"))
                .body("ModelSelectionExpression", equalTo("$default"));
    }

    // ──────────────────────────── DeleteRouteResponse ────────────────────────────

    @Test
    @Order(7)
    void deleteRouteResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteRouteResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseId":"%s"}
                        """.formatted(apiId, routeId, routeResponseId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── GetRouteResponse after delete ────────────────────────────

    @Test
    @Order(8)
    void getRouteResponseAfterDelete() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRouteResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","RouteResponseId":"%s"}
                        """.formatted(apiId, routeId, routeResponseId))
                .when().post("/")
                .then()
                .statusCode(not(equalTo(200)));
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (apiId != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s"}
                            """.formatted(apiId))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
