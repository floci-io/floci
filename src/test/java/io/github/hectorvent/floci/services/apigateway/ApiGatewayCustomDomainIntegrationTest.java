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
 * Verifies that API Gateway custom domain routing via Host header works correctly.
 *
 * <p>When a request arrives with a Host header matching a registered custom domain's
 * {@code regionalDomainName}, the filter resolves the base path mapping and routes
 * the request to the appropriate REST API and stage.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayCustomDomainIntegrationTest {

    private static String apiId;
    private static String rootId;
    private static String resourceId;
    private static String deploymentId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"custom-domain-test-api\"}")
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
                .body("{\"pathPart\":\"items\"}")
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
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"message\\\":\\\"custom-domain-works\\\"}\"}}")
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
    void createCustomDomain() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"domainName\":\"api.example.com\",\"certificateArn\":\"arn:aws:acm:us-east-1:123456789012:certificate/abc\"}")
                .when().post("/domainnames")
                .then()
                .statusCode(201)
                .body("domainName", equalTo("api.example.com"))
                .body("regionalDomainName", equalTo("api.example.com.regional.local"));
    }

    @Test @Order(5)
    void createBasePathMapping_withBasePath() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"basePath\":\"v1\",\"restApiId\":\"" + apiId + "\",\"stage\":\"prod\"}")
                .when().post("/domainnames/api.example.com/basepathmappings")
                .then()
                .statusCode(201)
                .body("basePath", equalTo("v1"))
                .body("restApiId", equalTo(apiId));
    }

    @Test @Order(6)
    void invokeViaCustomDomain_withBasePath() {
        given()
                .header("Host", "api.example.com.regional.local:4566")
                .when().get("/v1/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(7)
    void invokeViaCustomDomain_withoutPort() {
        given()
                .header("Host", "api.example.com.regional.local")
                .when().get("/v1/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(8)
    void invokeViaBareDomainName() {
        given()
                .header("Host", "api.example.com")
                .when().get("/v1/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(9)
    void invokeViaBareDomainName_withPort() {
        given()
                .header("Host", "api.example.com:4566")
                .when().get("/v1/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(10)
    void createBasePathMapping_catchAll() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"basePath\":\"(none)\",\"restApiId\":\"" + apiId + "\",\"stage\":\"prod\"}")
                .when().post("/domainnames/api.example.com/basepathmappings")
                .then()
                .statusCode(201);
    }

    @Test @Order(11)
    void invokeViaCustomDomain_catchAllMapping() {
        // With catch-all, /items should route directly (no base path prefix needed)
        given()
                .header("Host", "api.example.com.regional.local")
                .when().get("/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(12)
    void invokeViaCustomDomain_specificPathTakesPrecedence() {
        // /v1/items should still match the /v1 mapping (longer prefix wins)
        given()
                .header("Host", "api.example.com.regional.local")
                .when().get("/v1/items")
                .then()
                .statusCode(200)
                .body("message", equalTo("custom-domain-works"));
    }

    @Test @Order(13)
    void invokeViaCustomDomain_unknownDomain_passesThrough() {
        // Unknown domain should not be intercepted by the filter
        given()
                .header("Host", "unknown.regional.local")
                .when().get("/v1/items")
                .then()
                .statusCode(anyOf(is(403), is(404)));
    }

    @Test @Order(14)
    void cleanup() {
        given().when().delete("/domainnames/api.example.com/basepathmappings/v1").then().statusCode(anyOf(is(200), is(202), is(204)));
        given().when().delete("/domainnames/api.example.com/basepathmappings/(none)").then().statusCode(anyOf(is(200), is(202), is(204)));
        given().when().delete("/domainnames/api.example.com").then().statusCode(anyOf(is(200), is(202), is(204)));
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}
