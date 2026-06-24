package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ApiGatewayV2DeleteApiCascadeTest {

    @Test
    void deleteApiCascadesEveryChildResourceAnd404sOnSubsequentReferences() {
        String apiId = null;
        try {
            // 1. setup: create the API and every flavor of child resource under it.
            apiId = createApi("cascade-probe");
            String integrationId = createIntegration(apiId);
            String routeId = createRoute(apiId, integrationId);
            createStage(apiId);
            createAuthorizer(apiId);
            createDeployment(apiId);
            createModel(apiId);
            createRouteResponse(apiId, routeId);
            createIntegrationResponse(apiId, integrationId);

            // 2. delete the API.
            given().when().delete("/v2/apis/" + apiId).then().statusCode(204);

            // 3. every reference to the deleted apiId must 404.
            List<String> probes = List.of(
                    "",
                    "/routes",
                    "/integrations",
                    "/stages",
                    "/authorizers",
                    "/deployments",
                    "/models",
                    "/routes/" + routeId + "/routeresponses",
                    "/integrations/" + integrationId + "/integrationresponses"
            );
            for (String suffix : probes) {
                given().when().get("/v2/apis/" + apiId + suffix)
                        .then().statusCode(404);
            }
        } finally {
            // best-effort cleanup in case any assertion above failed before delete-api ran.
            if (apiId != null) {
                given().when().delete("/v2/apis/" + apiId);
            }
        }
    }

    @Test
    void cascadeIsScopedToSingleApi() {
        // Create two APIs with children under each; delete only api A; api B's children must remain.
        String aId = null;
        String bId = null;
        try {
            aId = createApi("cascade-iso-a");
            bId = createApi("cascade-iso-b");
            createIntegration(aId);
            createIntegration(bId);

            given().when().delete("/v2/apis/" + aId).then().statusCode(204);

            // A is gone — every reference 404s.
            given().when().get("/v2/apis/" + aId + "/integrations").then().statusCode(404);

            // B is untouched.
            given().when().get("/v2/apis/" + bId + "/integrations")
                    .then().statusCode(200).body("items", hasSize(1));
        } finally {
            if (bId != null) given().when().delete("/v2/apis/" + bId);
            if (aId != null) given().when().delete("/v2/apis/" + aId);
        }
    }

    // ──────────────────────────── creation helpers ────────────────────────────

    private static String createApi(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
    }

    private static String createIntegration(String apiId) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://example.com",
                         "integrationMethod":"GET","payloadFormatVersion":"1.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
    }

    private static String createRoute(String apiId, String integrationId) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /hello","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");
    }

    private static void createStage(String apiId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"dev","autoDeploy":true}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    private static void createAuthorizer(String apiId) {
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

    private static void createDeployment(String apiId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"probe-dep","stageName":"dev"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then().statusCode(201);
    }

    private static void createModel(String apiId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"ProbeModel","contentType":"application/json",
                         "schema":"{\\"type\\":\\"object\\"}"}
                        """)
                .when().post("/v2/apis/" + apiId + "/models")
                .then().statusCode(201);
    }

    private static void createRouteResponse(String apiId, String routeId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeResponseKey":"$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes/" + routeId + "/routeresponses")
                .then().statusCode(201);
    }

    private static void createIntegrationResponse(String apiId, String integrationId) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationResponseKey":"$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then().statusCode(201);
    }
}
