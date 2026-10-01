package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An OpenAPI 3 operation may list a parameter as a local reference,
 * {@code $ref: '#/components/parameters/<Name>'}, instead of writing it in place. AWS imports such a
 * definition, and the method carries the referenced parameter exactly as it would an in-place one.
 */
@QuarkusTest
class ApiGatewayOpenApiParameterRefImportIntegrationTest {

    private static final String SPEC = """
            openapi: "3.0.1"
            info:
              title: ParameterRefAPI
              version: "1.0"
            paths:
              /inline/{groupName}:
                get:
                  parameters:
                    - name: groupName
                      in: path
                      required: true
                      schema: { type: string }
                    - name: limit
                      in: query
                      schema: { type: integer }
                  responses:
                    "200": { description: ok }
              /ref/{groupName}:
                get:
                  parameters:
                    - $ref: '#/components/parameters/GroupName'
                    - $ref: '#/components/parameters/Limit'
                  responses:
                    "200": { description: ok }
            components:
              parameters:
                GroupName: { name: groupName, in: path, required: true, schema: { type: string } }
                Limit: { name: limit, in: query, schema: { type: integer } }
            """;

    @Test
    void importResolvesComponentParameterReferencesLikeInPlaceParameters() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(SPEC)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        try {
            Map<String, Boolean> inline = requestParameters(apiId, "/inline/{groupName}", "GET");
            Map<String, Boolean> referenced = requestParameters(apiId, "/ref/{groupName}", "GET");

            assertEquals(Map.of(
                    "method.request.path.groupName", true,
                    "method.request.querystring.limit", false), inline);
            assertEquals(inline, referenced);
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void importRejectsAReferenceToAMissingComponent() {
        String spec = """
                openapi: "3.0.1"
                info:
                  title: MissingParameterRefAPI
                  version: "1.0"
                paths:
                  /ref/{groupName}:
                    get:
                      parameters:
                        - $ref: '#/components/parameters/Missing'
                      responses:
                        "200": { description: ok }
                components:
                  parameters:
                    GroupName: { name: groupName, in: path, required: true, schema: { type: string } }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("#/components/parameters/Missing"));

        given()
                .when().get("/restapis")
                .then()
                .statusCode(200)
                .body("item.name", not(hasItem("MissingParameterRefAPI")));
    }

    @Test
    void importResolvesSwagger2ParameterReferencesInAnAnyMethod() {
        String spec = """
                swagger: "2.0"
                info:
                  title: Swagger2AnyParameterRefAPI
                  version: "1.0"
                paths:
                  /items:
                    x-amazon-apigateway-any-method:
                      parameters:
                        - $ref: '#/parameters/Limit'
                      responses:
                        "200": { description: ok }
                parameters:
                  Limit: { name: limit, in: query, type: integer }
                """;

        assertImportedRequestParameters(spec, "/items", "ANY", Map.of("method.request.querystring.limit", false));
    }

    @Test
    void importDecodesAnEscapedParameterReferenceInAnAnyMethod() {
        String spec = """
                swagger: "2.0"
                info:
                  title: EscapedParameterRefAPI
                  version: "1.0"
                paths:
                  /items:
                    x-amazon-apigateway-any-method:
                      parameters:
                        - $ref: '#/parameters/Foo~1Bar'
                      responses:
                        "200": { description: ok }
                parameters:
                  Foo/Bar: { name: filter, in: query, type: string }
                """;

        assertImportedRequestParameters(spec, "/items", "ANY", Map.of("method.request.querystring.filter", false));
    }

    @Test
    void importSkipsSwagger2BodyAndFormDataParameterReferencesInAnAnyMethod() {
        String spec = """
                swagger: "2.0"
                info:
                  title: Swagger2AnyBodyParameterRefAPI
                  version: "1.0"
                paths:
                  /items:
                    x-amazon-apigateway-any-method:
                      parameters:
                        - $ref: '#/parameters/Body'
                        - $ref: '#/parameters/Form'
                        - $ref: '#/parameters/Limit'
                      responses:
                        "200": { description: ok }
                parameters:
                  Body: { name: body, in: body, schema: { type: object } }
                  Form: { name: note, in: formData, type: string }
                  Limit: { name: limit, in: query, type: integer }
                """;

        assertImportedRequestParameters(spec, "/items", "ANY", Map.of("method.request.querystring.limit", false));
    }

    @Test
    void importFollowsAComponentParameterThatIsItselfAReference() {
        String spec = """
                openapi: "3.0.1"
                info:
                  title: ChainedParameterRefAPI
                  version: "1.0"
                paths:
                  /items:
                    get:
                      parameters:
                        - $ref: '#/components/parameters/Limit'
                      responses:
                        "200": { description: ok }
                components:
                  parameters:
                    Limit: { $ref: '#/components/parameters/RealLimit' }
                    RealLimit: { name: limit, in: query, schema: { type: integer } }
                """;

        assertImportedRequestParameters(spec, "/items", "GET", Map.of("method.request.querystring.limit", false));
    }

    @Test
    void importRejectsAComponentParameterThatReferencesItself() {
        String spec = """
                openapi: "3.0.1"
                info:
                  title: CyclicParameterRefAPI
                  version: "1.0"
                paths:
                  /items:
                    get:
                      parameters:
                        - $ref: '#/components/parameters/Loop'
                      responses:
                        "200": { description: ok }
                components:
                  parameters:
                    Loop: { $ref: '#/components/parameters/Loop' }
                """;

        given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", containsString("#/components/parameters/Loop"));

        given()
                .when().get("/restapis")
                .then()
                .statusCode(200)
                .body("item.name", not(hasItem("CyclicParameterRefAPI")));
    }

    private static void assertImportedRequestParameters(String spec, String path, String httpMethod,
                                                        Map<String, Boolean> expected) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .extract().path("id");

        try {
            assertEquals(expected, requestParameters(apiId, path, httpMethod));
        } finally {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static Map<String, Boolean> requestParameters(String apiId, String path, String httpMethod) {
        String resourceId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item.find { it.path == '" + path + "' }.id");
        return given()
                .when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod)
                .then()
                .statusCode(200)
                .extract().path("requestParameters");
    }
}
