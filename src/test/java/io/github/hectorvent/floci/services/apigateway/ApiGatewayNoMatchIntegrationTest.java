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
 * A REST API request that reaches no method answers 403 "Missing Authentication Token", whether the
 * path matched nothing at all or matched resources that declare no usable method. AWS resolves path
 * and method together, so both are the same failure.
 *
 * <p>Captured against a real REST API in us-west-2 with /users/{userId} GET and a literal /users/me
 * PATCH deployed: POST /users/me, DELETE /users/abc123, GET /zzz, GET /users/a/b/c and GET / all
 * returned 403 with x-amzn-ErrorType: MissingAuthenticationTokenException.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayNoMatchIntegrationTest {

    private static String apiId;

    private static String createResource(String parentId, String pathPart) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + parentId)
                .then()
                .statusCode(201)
                .extract().path("id");
    }

    private static void createMockMethod(String resourceId, String httpMethod, String marker) {
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod)
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod + "/responses/200")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod + "/integration")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"matched\\\":\\\""
                        + marker + "\\\"}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod
                        + "/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(1)
    void setupApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"no-match-test-api\"}")
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

        // /users/{userId} carries GET, and a literal /users/me carries only PATCH. There is no
        // {proxy+} anywhere, so an unmatched path really matches no resource at all.
        String usersId = createResource(rootId, "users");
        createMockMethod(createResource(usersId, "{userId}"), "GET", "users-get");
        createMockMethod(createResource(usersId, "me"), "PATCH", "users-me-patch");

        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
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

    @Test @Order(2)
    void methodDeclaredByNoCandidateReturns403() {
        // /users/me declares PATCH and /users/{userId} declares GET, so neither can serve POST.
        given()
                .contentType(ContentType.JSON)
                .when().post("/execute-api/" + apiId + "/test/users/me")
                .then()
                .statusCode(403)
                .header("x-amzn-ErrorType", equalTo("MissingAuthenticationTokenException"))
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test @Order(3)
    void methodMissingOnTheParameterisedResourceReturns403() {
        given()
                .when().delete("/execute-api/" + apiId + "/test/users/abc123")
                .then()
                .statusCode(403)
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test @Order(4)
    void unmatchedPathReturns403RatherThan404() {
        given()
                .when().get("/execute-api/" + apiId + "/test/zzz")
                .then()
                .statusCode(403)
                .header("x-amzn-ErrorType", equalTo("MissingAuthenticationTokenException"))
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test @Order(5)
    void pathDeeperThanAnyResourceReturns403() {
        given()
                .when().get("/execute-api/" + apiId + "/test/users/a/b/c")
                .then()
                .statusCode(403)
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test @Order(6)
    void aMatchingMethodStillSucceeds() {
        // The no-match response must not swallow requests that do resolve.
        given()
                .when().get("/execute-api/" + apiId + "/test/users/me")
                .then()
                .statusCode(200)
                .body("matched", equalTo("users-get"));
    }
}
