package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Regression test for MOCK-integration response parameters: a MOCK integration whose "200"
 * integration response declares static {@code responseParameters} (e.g. {@code
 * method.response.header.Access-Control-Allow-Origin -> '*'}) must emit those as real HTTP
 * response headers. Previously {@code invokeMock} returned early on the empty-response-template
 * path and dropped the static header mappings entirely.
 *
 * <p>The integration is shaped like the CORS methods API-Gateway OpenAPI import produces (mock
 * integration, {@code requestTemplates {"statusCode":200}}, NO responseTemplates), because that
 * is the configuration the bug was found with. It is exercised over GET rather than OPTIONS:
 * the dropped-header bug is verb-agnostic, and routing OPTIONS to the configured method is a
 * separate fix owned by #1955, which also skips {@code GlobalCorsFilter} for deployed-API paths
 * so a real browser preflight reaches the route at all.
 */
@QuarkusTest
class ApiGatewayMockCorsHeadersTest {

    private String apiId;
    private String rootId;
    private String resourceId;

    @BeforeEach
    void setup() {
        createRestApi();
        setupMockIntegration();
        createDeploymentAndStage();
    }

    private void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"mock-cors-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");
    }

    private void setupMockIntegration() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"cors\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // No authorization, mirroring an OpenAPI-imported CORS method.
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

        // Classic CORS mock: request template short-circuits to 200; NO response template.
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        // Static CORS headers as integration-response responseParameters.
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseParameters\":{"
                        + "\"method.response.header.Access-Control-Allow-Origin\":\"'*'\","
                        + "\"method.response.header.Access-Control-Allow-Methods\":\"'GET,OPTIONS,POST'\","
                        + "\"method.response.header.Access-Control-Allow-Headers\":\"'Content-Type,Authorization,X-Custom-CFN-Header'\""
                        + "}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);
    }

    private void createDeploymentAndStage() {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"api\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    void mockIntegrationEmitsStaticResponseParameterHeaders() {
        given()
                .when().get("/execute-api/" + apiId + "/api/cors")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Methods", equalTo("GET,OPTIONS,POST"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type,Authorization,X-Custom-CFN-Header"));
    }

    @Test
    void alsoViaUserRequestPath() {
        given()
                .when().get("/restapis/" + apiId + "/api/_user_request_/cors")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }
}
