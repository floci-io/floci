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
 * Verifies that execute-api subdomain routing works correctly.
 *
 * <p>When a request arrives with a Host header like
 * {@code {apiId}.execute-api.localhost:{port}}, the filter rewrites the path to
 * {@code /execute-api/{apiId}/{path}} so that {@link ApiGatewayExecuteController}
 * handles it. This enables AWS SDK clients to use the standard execute-api
 * endpoint URL format for features like the @connections Management API.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayExecuteApiSubdomainIntegrationTest {

    private static String apiId;

    @Test
    @Order(1)
    void createWebSocketApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"execute-api-subdomain-test","protocolType":"WEBSOCKET","routeSelectionExpression":"$request.body.action"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");
    }

    @Test
    @Order(2)
    void createStage() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","autoDeploy":true}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(3)
    void postToConnectionViaPathPrefix_returnsGoneException() {
        given()
                .contentType(ContentType.TEXT)
                .body("hello")
                .when().post("/execute-api/" + apiId + "/prod/@connections/fake-connection")
                .then()
                .statusCode(410)
                .body("__type", equalTo("GoneException"));
    }

    @Test
    @Order(4)
    void postToConnectionViaSubdomain_returnsGoneException() {
        given()
                .header("Host", apiId + ".execute-api.localhost:" + io.restassured.RestAssured.port)
                .contentType(ContentType.TEXT)
                .body("hello")
                .when().post("/prod/@connections/fake-connection")
                .then()
                .statusCode(410)
                .body("__type", equalTo("GoneException"));
    }

    @Test
    @Order(5)
    void getConnectionViaSubdomain_returnsGoneException() {
        given()
                .header("Host", apiId + ".execute-api.localhost:" + io.restassured.RestAssured.port)
                .when().get("/prod/@connections/fake-connection")
                .then()
                .statusCode(410)
                .body("__type", equalTo("GoneException"));
    }

    @Test
    @Order(6)
    void deleteConnectionViaSubdomain_returnsGoneException() {
        given()
                .header("Host", apiId + ".execute-api.localhost:" + io.restassured.RestAssured.port)
                .when().delete("/prod/@connections/fake-connection")
                .then()
                .statusCode(410)
                .body("__type", equalTo("GoneException"));
    }

    @Test
    @Order(7)
    void subdomainWithRegion_routesCorrectly() {
        given()
                .header("Host", apiId + ".execute-api.us-east-1.localhost:" + io.restassured.RestAssured.port)
                .contentType(ContentType.TEXT)
                .body("hello")
                .when().post("/prod/@connections/fake-connection")
                .then()
                .statusCode(410)
                .body("__type", equalTo("GoneException"));
    }

    @Test
    @Order(8)
    void unknownSubdomain_doesNotRouteToApiGateway() {
        given()
                .header("Host", "my-bucket.localhost:" + io.restassured.RestAssured.port)
                .when().get("/prod/@connections/fake-connection")
                .then()
                .statusCode(not(410));
    }
}
