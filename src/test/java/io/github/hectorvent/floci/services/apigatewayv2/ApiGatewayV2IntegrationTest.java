package io.github.hectorvent.floci.services.apigatewayv2;

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
class ApiGatewayV2IntegrationTest {

    private static String apiId;
    private static String routeId;
    private static String integrationId;
    private static String authorizerId;
    private static String deploymentId;

    // ──────────────────────────── API lifecycle ────────────────────────────

    @Test @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"test-http-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("test-http-api"))
                .body("protocolType", equalTo("HTTP"))
                .body("apiEndpoint", notNullValue())
                // AWS defaults must be populated
                .body("routeSelectionExpression", equalTo("${request.method} ${request.path}"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"))
                .extract().path("apiId");
    }

    @Test @Order(2)
    void getApi() {
        given()
                .when().get("/v2/apis/" + apiId)
                .then()
                .statusCode(200)
                .body("apiId", equalTo(apiId))
                .body("name", equalTo("test-http-api"))
                .body("routeSelectionExpression", equalTo("${request.method} ${request.path}"))
                .body("apiKeySelectionExpression", equalTo("$request.header.x-api-key"));
    }

    @Test @Order(3)
    void listApis() {
        given()
                .when().get("/v2/apis")
                .then()
                .statusCode(200)
                .body("items.apiId", hasItem(apiId));
    }

    @Test @Order(4)
    void getApiNotFound() {
        given()
                .when().get("/v2/apis/doesnotexist")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Integrations ────────────────────────────

    @Test @Order(10)
    void createIntegration() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://example.com","payloadFormatVersion":"2.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .body("integrationType", equalTo("HTTP_PROXY"))
                .body("integrationUri", equalTo("https://example.com"))
                .body("payloadFormatVersion", equalTo("2.0"))
                .extract().path("integrationId");
    }

    @Test @Order(11)
    void getIntegration() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200)
                .body("integrationId", equalTo(integrationId))
                .body("integrationType", equalTo("HTTP_PROXY"));
    }

    @Test @Order(12)
    void listIntegrations() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(200)
                .body("items.integrationId", hasItem(integrationId));
    }

    @Test @Order(13)
    void getIntegrationNotFound() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/doesnotexist")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Routes ────────────────────────────

    @Test @Order(20)
    void createRoute() {
        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /users","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201)
                .body("routeId", notNullValue())
                .body("routeKey", equalTo("GET /users"))
                .body("target", equalTo("integrations/" + integrationId))
                .extract().path("routeId");
    }

    @Test @Order(21)
    void getRoute() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then()
                .statusCode(200)
                .body("routeId", equalTo(routeId))
                .body("routeKey", equalTo("GET /users"));
    }

    @Test @Order(22)
    void listRoutes() {
        given()
                .when().get("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(200)
                .body("items.routeId", hasItem(routeId));
    }

    // ──────────────────────────── Authorizers ────────────────────────────

    @Test @Order(30)
    void createAuthorizer() {
        authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizerType":"JWT","name":"test-jwt-auth","identitySource":["$request.header.Authorization"],"jwtConfiguration":{"issuer":"https://example.com","audience":["api"]}}
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then()
                .statusCode(201)
                .body("authorizerId", notNullValue())
                .body("name", equalTo("test-jwt-auth"))
                .body("authorizerType", equalTo("JWT"))
                .body("identitySource", hasItem("$request.header.Authorization"))
                .body("jwtConfiguration.issuer", equalTo("https://example.com"))
                .body("jwtConfiguration.audience", hasItem("api"))
                .extract().path("authorizerId");
    }

    @Test @Order(31)
    void getAuthorizer() {
        given()
                .when().get("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(200)
                .body("authorizerId", equalTo(authorizerId))
                .body("authorizerType", equalTo("JWT"))
                .body("name", equalTo("test-jwt-auth"))
                .body("identitySource", hasItem("$request.header.Authorization"))
                .body("jwtConfiguration.issuer", equalTo("https://example.com"))
                .body("jwtConfiguration.audience", hasItem("api"));
    }

    @Test @Order(32)
    void listAuthorizers() {
        given()
                .when().get("/v2/apis/" + apiId + "/authorizers")
                .then()
                .statusCode(200)
                .body("items.authorizerId", hasItem(authorizerId));
    }

    // ──────────────────────────── Deployments ────────────────────────────

    @Test @Order(40)
    void createDeployment() {
        deploymentId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"v1"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .body("deploymentId", notNullValue())
                .body("deploymentStatus", equalTo("DEPLOYED"))
                .body("description", equalTo("v1"))
                .extract().path("deploymentId");
    }

    @Test @Order(41)
    void getDeployment() {
        given()
                .when().get("/v2/apis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(200)
                .body("deploymentId", equalTo(deploymentId))
                .body("deploymentStatus", equalTo("DEPLOYED"))
                .body("description", equalTo("v1"));
    }

    @Test @Order(42)
    void listDeployments() {
        given()
                .when().get("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(200)
                .body("items.deploymentId", hasItem(deploymentId));
    }

    // ──────────────────────────── Stages ────────────────────────────

    @Test @Order(50)
    void createStage() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s","autoDeploy":false}
                        """.formatted(deploymentId))
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201)
                .body("stageName", equalTo("prod"))
                .body("autoDeploy", equalTo(false))
                .body("deploymentId", equalTo(deploymentId));
    }

    @Test @Order(51)
    void getStage() {
        given()
                .when().get("/v2/apis/" + apiId + "/stages/prod")
                .then()
                .statusCode(200)
                .body("stageName", equalTo("prod"))
                .body("deploymentId", equalTo(deploymentId))
                .body("autoDeploy", equalTo(false));
    }

    @Test @Order(52)
    void listStages() {
        given()
                .when().get("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(200)
                .body("items.stageName", hasItem("prod"));
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test @Order(60)
    void deleteStage() {
        given()
                .when().delete("/v2/apis/" + apiId + "/stages/prod")
                .then()
                .statusCode(204);
    }

    @Test @Order(61)
    void deleteDeployment() {
        given()
                .when().delete("/v2/apis/" + apiId + "/deployments/" + deploymentId)
                .then()
                .statusCode(204);
    }

    @Test @Order(62)
    void deleteAuthorizer() {
        given()
                .when().delete("/v2/apis/" + apiId + "/authorizers/" + authorizerId)
                .then()
                .statusCode(204);
    }

    @Test @Order(63)
    void deleteRoute() {
        given()
                .when().delete("/v2/apis/" + apiId + "/routes/" + routeId)
                .then()
                .statusCode(204);
    }

    @Test @Order(64)
    void deleteIntegration() {
        given()
                .when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(204);
    }

    @Test @Order(65)
    void deleteApi() {
        given()
                .when().delete("/v2/apis/" + apiId)
                .then()
                .statusCode(204);
    }

    @Test @Order(66)
    void getDeletedApiReturns404() {
        given()
                .when().get("/v2/apis/" + apiId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Override IDs ────────────────────────────

    @Test @Order(100)
    void createApi_customIdTag_usesTagValueAsApiId() {
        String body = """
                {"name":"custom-id-api","protocolType":"HTTP","tags":{"_custom_id_":"MYV2CUSTOM"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", equalTo("MYV2CUSTOM"))
                .body("tags", nullValue()); // Should be stripped and thus omitted
    }

    @Test @Order(101)
    void createApi_flociOverrideIdTag_usesTagValueAsApiId() {
        String body = """
                {"name":"override-id-api","protocolType":"HTTP","tags":{"floci:override-id":"MYV2OVERRIDE"}}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", equalTo("MYV2OVERRIDE"))
                .body("tags", nullValue()); // V2 omits empty maps if all tags stripped
    }

    @Test @Order(102)
    void getApi_customId_resolvesById() {
        given()
                .when().get("/v2/apis/MYV2CUSTOM")
                .then()
                .statusCode(200)
                .body("apiId", equalTo("MYV2CUSTOM"));
    }

    @Test @Order(103)
    void deleteApi_customAndOverrideId() {
        given().when().delete("/v2/apis/MYV2CUSTOM").then().statusCode(204);
        given().when().delete("/v2/apis/MYV2OVERRIDE").then().statusCode(204);
    }
}
