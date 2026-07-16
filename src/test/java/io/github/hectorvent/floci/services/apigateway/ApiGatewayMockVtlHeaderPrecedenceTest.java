package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for header precedence when a MOCK integration response sets the same header via
 * both a VTL {@code $context.responseOverride.header[...]} assignment and a static
 * {@code responseParameters} mapping. AWS lets the VTL responseOverride win; Floci must emit a
 * single value for that header, not two (the responseParameters value must be skipped).
 */
@QuarkusTest
class ApiGatewayMockVtlHeaderPrecedenceTest {

    private String apiId;
    private String rootId;
    private String resourceId;

    @BeforeEach
    void setup() {
        createRestApi();
        setupPostMockWithVtlOverrideAndResponseParameters();
        deploy();
    }

    private void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"mock-vtl-precedence-api\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
    }

    private void setupPostMockWithVtlOverrideAndResponseParameters() {
        rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"echo\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/responses/200")
                .then().statusCode(201);

        // MOCK: request template short-circuits to 200.
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // Integration response sets X-Precedence via VTL responseOverride AND via responseParameters.
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\","
                        + "\"responseTemplates\":{\"application/json\":"
                        + "\"#set($context.responseOverride.header[\\\"X-Precedence\\\"] = \\\"from-vtl\\\")\"},"
                        + "\"responseParameters\":{\"method.response.header.X-Precedence\":\"'from-params'\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    private void deploy() {
        String deploymentId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test
    void vtlResponseOverrideWinsWithoutDuplicatingTheHeader() {
        List<String> values = given()
                .contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/echo")
                .then().statusCode(200)
                .extract().headers().getValues("X-Precedence");
        // Exactly one value, and it is the VTL override — not the responseParameters value, and not both.
        assertEquals(1, values.size(), "expected a single X-Precedence header, got: " + values);
        assertEquals("from-vtl", values.get(0));
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }
}
