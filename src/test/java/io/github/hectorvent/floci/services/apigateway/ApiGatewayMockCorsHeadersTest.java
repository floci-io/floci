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
 * Regression test for MOCK-integration CORS: a MOCK integration whose "200" integration
 * response declares static {@code responseParameters} (e.g. {@code method.response.header.
 * Access-Control-Allow-Origin -> '*'}) must emit those as real HTTP response headers.
 *
 * <p>This mirrors the CORS-preflight OPTIONS methods produced by API-Gateway OpenAPI import
 * (mock integration, {@code requestTemplates {"statusCode":200}}, NO responseTemplates), which a
 * browser needs so it can preflight cross-origin calls to the emulated API. Previously
 * {@code invokeMock} returned early on the empty-response-template path and dropped the static
 * header mappings entirely.
 */
@QuarkusTest
class ApiGatewayMockCorsHeadersTest {

    private String apiId;
    private String rootId;
    private String resourceId;

    @BeforeEach
    void setup() {
        createRestApi();
        setupOptionsMockIntegration();
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

    private void setupOptionsMockIntegration() {
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

        // OPTIONS method, no authorization (preflight must not require auth)
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/OPTIONS")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/OPTIONS/responses/200")
                .then()
                .statusCode(201);

        // Classic CORS mock: request template short-circuits to 200; NO response template.
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/OPTIONS/integration")
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
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/OPTIONS/integration/responses/200")
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
    void optionsPreflightReturnsCorsHeaders() {
        given()
                .when().options("/execute-api/" + apiId + "/api/cors")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Methods", equalTo("GET,OPTIONS,POST"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type,Authorization,X-Custom-CFN-Header"));
    }

    @Test
    void alsoViaUserRequestPath() {
        given()
                .when().options("/restapis/" + apiId + "/api/_user_request_/cors")
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
