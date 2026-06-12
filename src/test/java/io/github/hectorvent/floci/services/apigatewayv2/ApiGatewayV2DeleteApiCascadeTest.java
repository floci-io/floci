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
class ApiGatewayV2DeleteApiCascadeTest {

    private static String apiId;
    private static String routeId;
    private static String integrationId;

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"cascade-probe","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
    }

    @Test
    @Order(2)
    void createIntegration() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://example.com",
                         "integrationMethod":"GET","payloadFormatVersion":"1.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
    }

    @Test
    @Order(3)
    void createRoute() {
        routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /hello","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");
    }

    @Test
    @Order(4)
    void createStage() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"dev","autoDeploy":true}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    @Order(5)
    void createAuthorizer() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizerType":"JWT","name":"probe-jwt",
                         "identitySource":["$request.header.Authorization"],
                         "jwtConfiguration":{"issuer":"https://example.com","audience":["a"]}}
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201);
    }

    @Test
    @Order(6)
    void createDeployment() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"probe-dep","stageName":"dev"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then().statusCode(201);
    }

    @Test
    @Order(7)
    void createModel() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ProbeModel","contentType":"application/json",
                         "schema":"{\\"type\\":\\"object\\"}"}
                        """)
                .when().post("/v2/apis/" + apiId + "/models")
                .then().statusCode(201);
    }

    @Test
    @Order(8)
    void createRouteResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey":"$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then().statusCode(201);
    }

    @Test
    @Order(9)
    void createIntegrationResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationResponseKey":"$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then().statusCode(201);
    }

    // ──────────────────────────── the kill ────────────────────────────

    @Test
    @Order(20)
    void deleteApi() {
        given()
                .when().delete("/v2/apis/" + apiId)
                .then().statusCode(204);
    }

    // ──────────────────────────── every reference to the deleted apiId must 404 ────────────────────────────

    @Test
    @Order(21)
    void getApiReturns404() {
        given().when().get("/v2/apis/" + apiId).then().statusCode(404);
    }

    @Test
    @Order(22)
    void getRoutesReturns404() {
        given().when().get("/v2/apis/" + apiId + "/routes").then().statusCode(404);
    }

    @Test
    @Order(23)
    void getIntegrationsReturns404() {
        given().when().get("/v2/apis/" + apiId + "/integrations").then().statusCode(404);
    }

    @Test
    @Order(24)
    void getStagesReturns404() {
        given().when().get("/v2/apis/" + apiId + "/stages").then().statusCode(404);
    }

    @Test
    @Order(25)
    void getAuthorizersReturns404() {
        given().when().get("/v2/apis/" + apiId + "/authorizers").then().statusCode(404);
    }

    @Test
    @Order(26)
    void getDeploymentsReturns404() {
        given().when().get("/v2/apis/" + apiId + "/deployments").then().statusCode(404);
    }

    @Test
    @Order(27)
    void getModelsReturns404() {
        given().when().get("/v2/apis/" + apiId + "/models").then().statusCode(404);
    }

    @Test
    @Order(28)
    void getRouteResponsesReturns404() {
        given().when().get("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then().statusCode(404);
    }

    @Test
    @Order(29)
    void getIntegrationResponsesReturns404() {
        given().when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then().statusCode(404);
    }

    // ──────────────────────────── isolation — a sibling API must NOT be touched ────────────────────────────

    @Test
    @Order(40)
    void cascadeIsScopedToSingleApi() {
        // Create two APIs; create children under each; delete only api A; api B's children must remain.
        String aId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"cascade-iso-a\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis").then().statusCode(201).extract().path("apiId");

        String bId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"cascade-iso-b\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis").then().statusCode(201).extract().path("apiId");

        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"integrationType":"HTTP_PROXY","integrationUri":"https://a.example",
                             "integrationMethod":"GET","payloadFormatVersion":"1.0"}
                            """)
                    .when().post("/v2/apis/" + aId + "/integrations").then().statusCode(201);

            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"integrationType":"HTTP_PROXY","integrationUri":"https://b.example",
                             "integrationMethod":"GET","payloadFormatVersion":"1.0"}
                            """)
                    .when().post("/v2/apis/" + bId + "/integrations").then().statusCode(201);

            given().when().delete("/v2/apis/" + aId).then().statusCode(204);

            // A is gone — every reference 404s
            given().when().get("/v2/apis/" + aId + "/integrations").then().statusCode(404);

            // B is untouched
            given().when().get("/v2/apis/" + bId + "/integrations")
                    .then().statusCode(200).body("items", hasSize(1));
        } finally {
            given().when().delete("/v2/apis/" + bId);
        }
    }
}
