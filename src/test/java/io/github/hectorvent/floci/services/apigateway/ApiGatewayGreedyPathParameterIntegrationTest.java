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
 * A greedy resource whose parameter is not named {@code proxy} must route and capture like one.
 *
 * <p>AWS does not reserve the name: "you can use any string for the greedy path parameter name",
 * and {@code /parent/{name+}} matches every descendant below the parent. Exercised through the real
 * request path rather than the event builder, because the defect this covers was in routing: keying
 * on the literal {@code {proxy+}} left {@code /assets/{rest+}} to the single-segment template
 * matcher, so {@code /assets/foo} matched while the multi-segment {@code /assets/img/logo.png},
 * the whole point of a greedy resource, matched nothing at all.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayGreedyPathParameterIntegrationTest {

    private static String apiId;
    private static String greedyResourceId;

    @Test @Order(1)
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"greedy-path-parameter-test-api\"}")
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    @Test @Order(2)
    void setupGreedyResources() {
        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        String assetsId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"assets\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // /assets/{rest+}: greedy, deliberately not named "proxy"
        greedyResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{rest+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + assetsId)
                .then()
                .statusCode(201)
                .body("path", equalTo("/assets/{rest+}"))
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + greedyResourceId + "/methods/ANY")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + greedyResourceId + "/methods/ANY/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + greedyResourceId + "/methods/ANY/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":"
                        + "\"{\\\"matched\\\":\\\"assets-greedy\\\","
                        + "\\\"rest\\\":\\\"$input.params('rest')\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + greedyResourceId
                        + "/methods/ANY/integration/responses/200")
                .then()
                .statusCode(201);

        // /v1/admin/catalog/{kind} lists, /v1/admin/catalog/{kind}/{name+} serves one item
        String catalogId = createResource(createResource(createResource(rootId, "v1"), "admin"), "catalog");
        String kindId = createResource(catalogId, "{kind}");
        mockEcho(kindId, "{\"matched\":\"catalog-list\",\"kind\":\"$input.params('kind')\"}");
        mockEcho(createResource(kindId, "{name+}"), "{\"matched\":\"catalog-item\","
                + "\"kind\":\"$input.params('kind')\",\"name\":\"$input.params('name')\"}");
    }

    private static String createResource(String parentId, String pathPart) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + parentId)
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    private static void mockEcho(String resourceId, String responseTemplate) {
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"responseParameters\":{}}")
                .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\""
                        + responseTemplate.replace("\"", "\\\"") + "\"}}")
                .when().put(method + "/integration/responses/200").then().statusCode(201);
    }

    @Test @Order(3)
    void createDeploymentAndStage() {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test @Order(4)
    void multiSegmentRequestReachesTheGreedyResource() {
        // The case that used to 404: more segments than the template, so the single-segment
        // template matcher rejected it and greedy matching never considered a non-"proxy" name.
        given()
                .when().get("/execute-api/" + apiId + "/test/assets/img/logo.png")
                .then()
                .statusCode(200)
                .body("matched", equalTo("assets-greedy"))
                // The captured value is the remainder after the literal prefix, under the name the
                // template declares - not "proxy", and not the whole request path.
                .body("rest", equalTo("img/logo.png"));
    }

    @Test @Order(5)
    void singleSegmentRequestStillReachesTheGreedyResource() {
        // This one passed even before the fix, by accident, which is why a manual check looked fine.
        given()
                .when().get("/execute-api/" + apiId + "/test/assets/logo.png")
                .then()
                .statusCode(200)
                .body("matched", equalTo("assets-greedy"))
                .body("rest", equalTo("logo.png"));
    }

    @Test @Order(6)
    void deeplyNestedRequestReachesTheGreedyResource() {
        given()
                .when().get("/execute-api/" + apiId + "/test/assets/a/b/c/d")
                .then()
                .statusCode(200)
                .body("matched", equalTo("assets-greedy"))
                .body("rest", equalTo("a/b/c/d"));
    }

    @Test @Order(7)
    void pathOutsideTheParentPrefixDoesNotMatch() {
        // The greedy resource must not have become a catch-all for the whole API. Reaching no
        // resource at all is the no-match response, which AWS renders as 403 "Missing
        // Authentication Token" rather than 404.
        given()
                .when().get("/execute-api/" + apiId + "/test/other/img/logo.png")
                .then()
                .statusCode(403)
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test @Order(8)
    void greedyResourceBelowAPathParameterReceivesBothParameters() {
        // Used to fall through to the no-match 403: the greedy matcher compared the request with
        // the literal text "/v1/admin/catalog/{kind}/", which no real path starts with.
        given()
                .when().get("/execute-api/" + apiId + "/test/v1/admin/catalog/scopes/teos/sur-df")
                .then()
                .statusCode(200)
                .body("matched", equalTo("catalog-item"))
                .body("kind", equalTo("scopes"))
                .body("name", equalTo("teos/sur-df"));
    }

    @Test @Order(9)
    void parentResourceStillServesItsOwnPath() {
        given()
                .when().get("/execute-api/" + apiId + "/test/v1/admin/catalog/scopes")
                .then()
                .statusCode(200)
                .body("matched", equalTo("catalog-list"))
                .body("kind", equalTo("scopes"));
    }

    @Test @Order(99)
    void cleanup() {
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }
}
