package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A MOCK integration answers with the integration response API Gateway <em>selects</em> for the
 * {@code statusCode} its request template renders, not with whichever response happens to be
 * keyed {@code "200"} (#2847).
 *
 * <p>The regression this guards: CDK's {@code addCorsPreflight} declares a single {@code 204}
 * integration response carrying the {@code Access-Control-Allow-*} headers and a
 * {@code { statusCode: 200 }} request template. Floci used to look up {@code "200"} only, found
 * nothing, and returned a bare 200, so browser preflights against CDK-built REST APIs failed
 * with a missing {@code Access-Control-Allow-Origin}.</p>
 */
@QuarkusTest
class ApiGatewayMockStatusCodeSelectionIntegrationTest {

    private static final String STAGE = "api";

    private String apiId;
    private String resourceId;

    @BeforeEach
    void createRestApiAndResource() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"mock-status-selection-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        String rootId = given()
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
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void cdkShapedPreflightUsesTheSingle204IntegrationResponse() {
        // Exactly what aws-cdk-lib's Resource.addCorsPreflight synthesizes: an unquoted
        // "{ statusCode: 200 }" request template and one "204" integration/method response.
        putMethod("OPTIONS");
        putMockIntegration("OPTIONS", "{ statusCode: 200 }");
        putIntegrationResponse("OPTIONS", "204", "",
                "\"method.response.header.Access-Control-Allow-Origin\":\"'*'\","
                        + "\"method.response.header.Access-Control-Allow-Methods\":\"'OPTIONS,GET,POST'\","
                        + "\"method.response.header.Access-Control-Allow-Headers\":\"'Content-Type,Authorization'\"");
        putMethodResponse("OPTIONS", "204");
        deploy();

        given()
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Methods", equalTo("OPTIONS,GET,POST"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type,Authorization"));

        given()
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/restapis/" + apiId + "/" + STAGE + "/_user_request_/items")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @Test
    void selectionPatternMatchingTheMockStatusCodeWinsOverTheDefault() {
        putMethod("GET");
        putMockIntegration("GET", "{\"statusCode\": 404}");
        putIntegrationResponse("GET", "200", "",
                "\"method.response.header.X-Selected\":\"'default'\"");
        putIntegrationResponse("GET", "404", "4\\\\d{2}",
                "\"method.response.header.X-Selected\":\"'not-found'\","
                        + "\"method.response.header.Access-Control-Allow-Origin\":\"'*'\"");
        putMethodResponse("GET", "200");
        putMethodResponse("GET", "404");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(404)
                .header("X-Selected", equalTo("not-found"))
                .header("Access-Control-Allow-Origin", equalTo("*"));
    }

    @Test
    void defaultIntegrationResponseAnswersWhenNoPatternMatches() {
        putMethod("GET");
        putMockIntegration("GET", "{\"statusCode\": 200}");
        putIntegrationResponse("GET", "200", "",
                "\"method.response.header.X-Selected\":\"'default'\"");
        putIntegrationResponse("GET", "500", "5\\\\d{2}",
                "\"method.response.header.X-Selected\":\"'error'\"");
        putMethodResponse("GET", "200");
        putMethodResponse("GET", "500");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(200)
                .header("X-Selected", equalTo("default"));
    }

    @Test
    void onlyTheRenderedRequestTemplateDecidesTheStatusCode() {
        // The raw template mentions 404, but VTL never emits it: AWS renders first and only then
        // reads statusCode, so the default (200) response must answer, not the 404 one.
        putMethod("GET");
        putMockIntegration("GET", "#if(false){\"statusCode\": 404}#end{\"statusCode\": 200}");
        putIntegrationResponse("GET", "200", "",
                "\"method.response.header.X-Selected\":\"'default'\"");
        putIntegrationResponse("GET", "404", "4\\\\d{2}",
                "\"method.response.header.X-Selected\":\"'not-found'\"");
        putMethodResponse("GET", "200");
        putMethodResponse("GET", "404");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(200)
                .header("X-Selected", equalTo("default"));
    }

    @Test
    void invalidSelectionPatternIsSkippedAndTheDefaultStillAnswers() {
        putMethod("GET");
        putMockIntegration("GET", "{\"statusCode\": 200}");
        putIntegrationResponse("GET", "200", "",
                "\"method.response.header.X-Selected\":\"'default'\"");
        putIntegrationResponse("GET", "500", "[unterminated",
                "\"method.response.header.X-Selected\":\"'broken'\"");
        putMethodResponse("GET", "200");
        putMethodResponse("GET", "500");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(200)
                .header("X-Selected", equalTo("default"));
    }

    @Test
    void requestTemplateThatFailsToRenderIsAConfigurationError() {
        // An unterminated #if is a VTL parse error. AWS surfaces that as a 500 configuration
        // error; it must never be masked by picking the 200/default response.
        putMethod("GET");
        putMockIntegration("GET", "#if($input.params('x') == 'y') {\"statusCode\": 200}");
        putIntegrationResponse("GET", "200", "",
                "\"method.response.header.X-Selected\":\"'default'\"");
        putMethodResponse("GET", "200");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(500)
                .header("X-Selected", nullValue())
                .body("message", equalTo("Internal server error"));
    }

    @Test
    void bareMockWithoutIntegrationResponsesStaysAnEmpty200EvenIfTheTemplateIsBroken() {
        // Documented leniency: a MOCK with no integration responses is a stub answering an empty
        // 200, and that must hold before the request template is ever rendered.
        putMethod("GET");
        putMockIntegration("GET", "#if($input.params('x') == 'y') {\"statusCode\": 200}");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(200)
                .header("Content-Length", equalTo("0"));
    }

    @Test
    void mockWithNoMatchingResponseAndNoDefaultIsAConfigurationError() {
        putMethod("GET");
        putMockIntegration("GET", "{\"statusCode\": 200}");
        putIntegrationResponse("GET", "404", "4\\\\d{2}",
                "\"method.response.header.X-Selected\":\"'not-found'\"");
        putMethodResponse("GET", "404");
        deploy();

        given()
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/items")
                .then()
                .statusCode(500)
                .header("X-Selected", nullValue())
                .body("message", equalTo("Internal server error"));
    }

    private void putMethod(String httpMethod) {
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod)
                .then()
                .statusCode(201);
    }

    private void putMockIntegration(String httpMethod, String requestTemplate) {
        String escaped = requestTemplate.replace("\\", "\\\\").replace("\"", "\\\"");
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"" + escaped + "\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod + "/integration")
                .then()
                .statusCode(201);
    }

    private void putIntegrationResponse(String httpMethod, String statusCode, String selectionPattern,
                                        String responseParametersJson) {
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"" + selectionPattern + "\",\"responseParameters\":{"
                        + responseParametersJson + "}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod
                        + "/integration/responses/" + statusCode)
                .then()
                .statusCode(201);
    }

    private void putMethodResponse(String httpMethod, String statusCode) {
        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod
                        + "/responses/" + statusCode)
                .then()
                .statusCode(201);
    }

    private void deploy() {
        String deploymentId = given()
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
}
