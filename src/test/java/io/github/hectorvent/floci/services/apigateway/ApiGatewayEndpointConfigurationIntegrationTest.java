package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ApiGatewayEndpointConfigurationIntegrationTest {

    @Test
    void createRestApi_withEndpointConfiguration_Private() {
        String body = """
                {
                  "name": "private-api",
                  "endpointConfiguration": {
                    "types": ["PRIVATE"],
                    "vpcEndpointIds": ["vpce-123"]
                  }
                }
                """;

        String apiId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("endpointConfiguration.types", contains("PRIVATE"))
                .body("endpointConfiguration.vpcEndpointIds", contains("vpce-123"))
                .extract().path("id");

        // Verify preservation during OpenAPI update (putRestApi)
        String openApiSpec = """
                openapi: 3.0.0
                info:
                  title: Updated API
                  version: 1.0.0
                paths:
                  /:
                    get:
                      responses:
                        '200':
                          description: OK
                """;

        given()
                .contentType(ContentType.JSON)
                .body(openApiSpec)
                .when().put("/restapis/" + apiId)
                .then()
                .statusCode(200)
                .body("name", equalTo("Updated API"))
                .body("endpointConfiguration.types", contains("PRIVATE"))
                .body("endpointConfiguration.vpcEndpointIds", contains("vpce-123"));
    }

    @Test
    void createRestApi_withDefaultEndpointConfiguration() {
        String body = """
                {"name": "default-api"}
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("endpointConfiguration.types", contains("REGIONAL"))
                .body("endpointConfiguration.vpcEndpointIds", empty());
    }

    @Test
    void createRestApi_ValidationFailures() {
        // Multiple types
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"bad-api\", \"endpointConfiguration\":{\"types\":[\"REGIONAL\", \"EDGE\"]}}")
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("message", containsString("exactly one value"));

        // Invalid type
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"bad-api\", \"endpointConfiguration\":{\"types\":[\"INVALID\"]}}")
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("message", containsString("REGIONAL, EDGE, or PRIVATE"));

        // Private without VPC IDs
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"bad-api\", \"endpointConfiguration\":{\"types\":[\"PRIVATE\"]}}")
                .when().post("/restapis")
                .then()
                .statusCode(400)
                .body("message", containsString("At least one vpcEndpointId is required"));
    }

    @Test
    void createRestApi_RegionalIgnoresVpcIds() {
        String body = """
                {
                  "name": "regional-api",
                  "endpointConfiguration": {
                    "types": ["REGIONAL"],
                    "vpcEndpointIds": ["vpce-123"]
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("endpointConfiguration.types", contains("REGIONAL"))
                .body("endpointConfiguration.vpcEndpointIds", empty());
    }
}
