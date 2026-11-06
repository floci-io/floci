package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2IntegrationResponseIntegrationTest {

    private static String apiId;
    private static String integrationId;
    private static String integrationResponseId;

    // ──────────────────────────── Prerequisites ────────────────────────────

    @Test @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"test-http-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("name", equalTo("test-http-api"))
                .body("protocolType", equalTo("HTTP"))
                .extract().path("apiId");
    }

    @Test @Order(2)
    void createIntegration() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"http://example.com","payloadFormatVersion":"2.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");
    }

    // ──────────────────────────── Integration Response CRUD ────────────────────────────

    @Test @Order(3)
    void createIntegrationResponse() {
        integrationResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationResponseKey": "$default",
                          "contentHandlingStrategy": "CONVERT_TO_TEXT",
                          "templateSelectionExpression": "$default",
                          "responseTemplates": {"application/json": "{}"},
                          "responseParameters": {"append:header.X-Custom": "integration.response.header.X-Custom"}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then()
                .statusCode(201)
                .body("integrationResponseId", notNullValue())
                .body("integrationResponseKey", equalTo("$default"))
                .body("integrationId", equalTo(integrationId))
                .body("contentHandlingStrategy", equalTo("CONVERT_TO_TEXT"))
                .body("templateSelectionExpression", equalTo("$default"))
                .body("responseTemplates", notNullValue())
                .body("responseParameters", notNullValue())
                .extract().path("integrationResponseId");
    }

    @Test @Order(4)
    void getIntegrationResponse() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/" + integrationResponseId)
                .then()
                .statusCode(200)
                .body("integrationResponseId", equalTo(integrationResponseId))
                .body("integrationResponseKey", equalTo("$default"))
                .body("integrationId", equalTo(integrationId))
                .body("contentHandlingStrategy", equalTo("CONVERT_TO_TEXT"))
                .body("templateSelectionExpression", equalTo("$default"))
                .body("responseTemplates", notNullValue())
                .body("responseParameters", notNullValue());
    }

    @Test @Order(5)
    void getIntegrationResponses() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then()
                .statusCode(200)
                .body("items", notNullValue())
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.integrationResponseId", hasItem(integrationResponseId));
    }

    @Test @Order(6)
    void updateIntegrationResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"contentHandlingStrategy": "CONVERT_TO_BINARY"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/" + integrationResponseId)
                .then()
                .statusCode(200)
                .body("integrationResponseId", equalTo(integrationResponseId))
                .body("contentHandlingStrategy", equalTo("CONVERT_TO_BINARY"))
                .body("integrationResponseKey", equalTo("$default"));
    }

    @Test @Order(7)
    void deleteIntegrationResponse() {
        given()
                .when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/" + integrationResponseId)
                .then()
                .statusCode(204);
    }

    @Test @Order(8)
    void getIntegrationResponseAfterDelete() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/" + integrationResponseId)
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Parent Validation ────────────────────────────

    @Test @Order(9)
    void createIntegrationResponseWithNonExistentApi() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/nonexistent/integrations/nonexistent/integrationresponses")
                .then()
                .statusCode(404);
    }

    @Test @Order(10)
    void createIntegrationResponseWithNonExistentIntegration() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations/nonexistent/integrationresponses")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Not Found Errors ────────────────────────────

    @Test @Order(11)
    void getIntegrationResponseNotFound() {
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(12)
    void updateIntegrationResponseNotFound() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"contentHandlingStrategy": "CONVERT_TO_BINARY"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test @Order(13)
    void deleteIntegrationResponseNotFound() {
        given()
                .when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses/nonexistent")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Listing Isolation ────────────────────────────

    @Test @Order(14)
    void listingIsolation() {
        // Create a second integration
        String secondIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"http://other.example.com","payloadFormatVersion":"2.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        // Create an integration response on the second integration
        String secondIntegrationResponseId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationResponseKey": "$default"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations/" + secondIntegrationId + "/integrationresponses")
                .then()
                .statusCode(201)
                .body("integrationResponseId", notNullValue())
                .extract().path("integrationResponseId");

        // List integration responses for the first integration — the second integration's response must NOT appear
        given()
                .when().get("/v2/apis/" + apiId + "/integrations/" + integrationId + "/integrationresponses")
                .then()
                .statusCode(200)
                .body("items.integrationResponseId", not(hasItem(secondIntegrationResponseId)));
    }
}
