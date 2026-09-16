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
 * API Gateway restricts a method request parameter name to {@code ^[a-zA-Z0-9:._$-]+$}. The
 * limitation is undocumented, so it is captured here from real AWS (us-west-2): both
 * {@code PutMethod} and an OpenAPI import reject a name outside that set, quoting the expression.
 *
 * <p>A JSON:API style {@code filter[a]} is therefore not importable, while {@code filter.a} is,
 * which is the workaround applications end up adopting. The restriction covers query string, header
 * and path parameters alike.
 *
 * <p>Only the declared name is restricted. At runtime a request carrying {@code ?filter[a]=1}
 * reaches a proxy integration with the brackets intact, so this is not a general ban on the
 * character in a query string.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayParameterNameValidationTest {

    private static final String EXPECTED_REGEX_MESSAGE =
            "Parameter name should match the following regular expression: ^[a-zA-Z0-9:._$-]+$";

    private static String specWithQueryParam(String paramName) {
        return """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "ParamNameAPI", "version": "1.0" },
                  "paths": {
                    "/items": {
                      "get": {
                        "parameters": [
                          { "name": "%s", "in": "query", "required": true,
                            "schema": { "type": "string" } }
                        ],
                        "responses": { "200": { "description": "ok" } },
                        "x-amazon-apigateway-integration": {
                          "type": "MOCK",
                          "requestTemplates": { "application/json": "{\\"statusCode\\": 200}" },
                          "responses": { "default": { "statusCode": "200" } }
                        }
                      }
                    }
                  }
                }
                """.formatted(paramName);
    }

    private static String createApiWithItemsResource() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"param-name-validation-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"items\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        return apiId + "|" + resourceId;
    }

    // ──────────────────────────── OpenAPI import ────────────────────────────

    @Test @Order(1)
    void importRejectsABracketedQueryParameterName() {
        // Real AWS names the method and the path before the validation message.
        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(specWithQueryParam("filter[a]"))
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("message", containsString("Errors found during import:"))
                .body("message", containsString("Unable to put method 'GET' on resource at path '/items'"))
                .body("message", containsString(EXPECTED_REGEX_MESSAGE));
    }

    @Test @Order(2)
    void importAcceptsADottedQueryParameterName() {
        // filter.a is the workaround, and it is genuinely accepted by AWS.
        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(specWithQueryParam("filter.a"))
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", notNullValue());
    }

    // ──────────────────────────── PutMethod ────────────────────────────

    @Test @Order(3)
    void putMethodRejectsABracketedQueryParameterName() {
        String[] ids = createApiWithItemsResource().split("\\|");

        // Called directly, AWS returns the bare validation message with no import envelope.
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\","
                        + "\"requestParameters\":{\"method.request.querystring.filter[a]\":true}}")
                .when().put("/restapis/" + ids[0] + "/resources/" + ids[1] + "/methods/GET")
                .then()
                .statusCode(400)
                .body("message", containsString(EXPECTED_REGEX_MESSAGE))
                .body("message", not(containsString("Errors found during import")));
    }

    @Test @Order(4)
    void putMethodRejectsABracketedHeaderParameterName() {
        String[] ids = createApiWithItemsResource().split("\\|");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\","
                        + "\"requestParameters\":{\"method.request.header.x[a]\":true}}")
                .when().put("/restapis/" + ids[0] + "/resources/" + ids[1] + "/methods/POST")
                .then()
                .statusCode(400)
                .body("message", containsString(EXPECTED_REGEX_MESSAGE));
    }

    @Test @Order(5)
    void putMethodAcceptsEveryCharacterInTheAllowedSet() {
        String[] ids = createApiWithItemsResource().split("\\|");

        // $ : - . _ are all inside the expression, and a name may contain dots of its own.
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\","
                        + "\"requestParameters\":{\"method.request.querystring.a$b:c-d.e_f\":true}}")
                .when().put("/restapis/" + ids[0] + "/resources/" + ids[1] + "/methods/PUT")
                .then()
                // Accepted rather than rejected is the whole assertion here. The response is not
                // checked for the parameter itself: toMethodNode does not serialise
                // requestParameters at all, which is a separate gap from this validation.
                .statusCode(201);
    }
}
