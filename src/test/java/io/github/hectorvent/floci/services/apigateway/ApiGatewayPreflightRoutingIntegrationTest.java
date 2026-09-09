package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end coverage for CORS preflight (OPTIONS) handling on deployed API Gateway
 * REST and HTTP APIs (#1928).
 *
 * <p>{@code GlobalCorsFilterTest} unit-tests {@link io.github.hectorvent.floci.core.common.GlobalCorsFilter#isDeployedApiPath}
 * in isolation, and {@code GlobalCorsFilterIntegrationTest} verifies the filter still
 * short-circuits preflights on <em>non-deployed</em> paths. Neither exercises the flow this
 * PR actually changes: with global CORS <em>enabled</em>, a browser preflight to a deployed
 * {@code /execute-api/...} path must be skipped by the filter and routed all the way to the
 * integration. This test drives that full chain — filter skip → route match → OPTIONS/ANY
 * method resolution → integration invocation — so a regression in any single stage fails here.</p>
 */
@QuarkusTest
@TestProfile(ApiGatewayPreflightRoutingIntegrationTest.CorsEnabledProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayPreflightRoutingIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String STAGE = "cors";

    private static String apiId;
    private static String rootId;
    private static String optionsResourceId;
    private static String anyResourceId;
    private static String deploymentId;
    private static String httpApiId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"preflight-routing-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");
    }

    @Test @Order(2)
    void setupExplicitOptionsMethodIntegration() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        optionsResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"widgets\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + optionsResourceId + "/methods/OPTIONS")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + optionsResourceId + "/methods/OPTIONS/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + optionsResourceId + "/methods/OPTIONS/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"handledBy\\\":\\\"options-integration\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + optionsResourceId + "/methods/OPTIONS/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test @Order(3)
    void setupAnyMethodIntegration() {
        anyResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"any\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\"any\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + anyResourceId + "/methods/ANY/integration/responses/200")
                .then()
                .statusCode(201);
    }

    @Test @Order(4)
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
                .body("{\"stageName\":\"" + STAGE + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    /**
     * The regression guard: a real browser preflight (carries {@code Origin} and
     * {@code Access-Control-Request-Method}) to a deployed path must NOT be answered by the
     * global CORS filter (which would return 204 with no body). The deployed-path exclusion
     * lets it fall through to the explicit OPTIONS method's integration, whose body proves the
     * integration was actually invoked.
     */
    @Test @Order(5)
    void preflightToDeployedPathReachesExplicitOptionsIntegration() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "content-type")
                .when().options("/execute-api/" + apiId + "/" + STAGE + "/widgets")
                .then()
                .statusCode(200)
                .body("handledBy", equalTo("options-integration"));
    }

    /** An OPTIONS preflight must also resolve to a resource configured with the ANY method. */
    @Test @Order(6)
    void preflightResolvesToAnyMethodIntegration() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .when().options("/execute-api/" + apiId + "/" + STAGE + "/any")
                .then()
                .statusCode(200)
                .body("matched", equalTo("any"));
    }

    /**
     * HTTP APIs differ from REST APIs: API Gateway itself answers preflight from the API's
     * CorsConfiguration, even when no OPTIONS route exists.
     */
    @Test @Order(7)
    void setupHttpApiWithCorsConfiguration() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                      "name":"preflight-routing-http-api",
                      "protocolType":"HTTP",
                      "corsConfiguration":{
                        "allowOrigins":["%s"],
                        "allowMethods":["GET","POST"],
                        "allowHeaders":["content-type"],
                        "exposeHeaders":["x-request-id"],
                        "maxAge":600,
                        "allowCredentials":true
                      }
                    }
                    """.formatted(ORIGIN))
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /items\"}")
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test @Order(8)
    void httpApiPreflightUsesApiCorsConfigurationWithoutOptionsRoute() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "content-type")
                .when().options("/execute-api/" + httpApiId + "/$default/items")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo(ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET, POST"))
                .header("Access-Control-Allow-Headers", equalTo("content-type"))
                .header("Access-Control-Expose-Headers", equalTo("x-request-id"))
                .header("Access-Control-Max-Age", equalTo("600"))
                .header("Access-Control-Allow-Credentials", equalTo("true"));
    }

    /**
     * The discriminator: a preflight to a non-deployed management path ({@code /restapis}) is
     * still owned by the global CORS filter — short-circuited with 204 and CORS headers, never
     * routed to an integration. Proves the deployed-path exclusion is scoped, not blanket.
     */
    @Test @Order(9)
    void preflightToManagementPathIsShortCircuitedByFilter() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/restapis")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo(ORIGIN))
                .body(equalTo(""));
    }

    @Test @Order(10)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
        given().when().delete("/v2/apis/" + httpApiId).then().statusCode(204);
    }

    public static final class CorsEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Global CORS on: without the deployed-path exclusion, this filter would answer the
            // preflight itself and the integration would never run.
            return Map.of("floci.security.extra-cors-allowed-origins", ORIGIN);
        }
    }
}
