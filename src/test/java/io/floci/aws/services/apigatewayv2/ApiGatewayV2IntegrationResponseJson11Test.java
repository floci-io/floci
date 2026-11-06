package io.floci.aws.services.apigatewayv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for API Gateway v2 Integration Response CRUD via the JSON 1.1 path.
 * Verifies PascalCase key normalization and all Integration Response CRUD operations
 * through the AmazonApiGatewayV2.* X-Amz-Target header.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2IntegrationResponseJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;
    private static String integrationId;
    private static String integrationResponseId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── CreateApi ────────────────────────────

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"ir-json11-test","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("ir-json11-test"))
                .extract().path("ApiId");
    }

    // ──────────────────────────── CreateIntegration ────────────────────────────

    @Test
    @Order(2)
    void createIntegration() {
        integrationId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegration")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationType":"HTTP_PROXY","IntegrationUri":"http://example.com","PayloadFormatVersion":"2.0"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("IntegrationId", notNullValue())
                .extract().path("IntegrationId");
    }

    // ──────────────────────────── CreateIntegrationResponse ────────────────────────────

    @Test
    @Order(3)
    void createIntegrationResponse() {
        integrationResponseId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateIntegrationResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationResponseKey":"$default","ContentHandlingStrategy":"CONVERT_TO_TEXT","TemplateSelectionExpression":"$default"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("IntegrationResponseId", notNullValue())
                .body("IntegrationResponseKey", equalTo("$default"))
                .body("ContentHandlingStrategy", equalTo("CONVERT_TO_TEXT"))
                .body("TemplateSelectionExpression", equalTo("$default"))
                .extract().path("IntegrationResponseId");
    }

    // ──────────────────────────── GetIntegrationResponse ────────────────────────────

    @Test
    @Order(4)
    void getIntegrationResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetIntegrationResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationResponseId":"%s"}
                        """.formatted(apiId, integrationId, integrationResponseId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("IntegrationResponseId", equalTo(integrationResponseId))
                .body("IntegrationResponseKey", equalTo("$default"))
                .body("ContentHandlingStrategy", equalTo("CONVERT_TO_TEXT"))
                .body("TemplateSelectionExpression", equalTo("$default"));
    }

    // ──────────────────────────── GetIntegrationResponses ────────────────────────────

    @Test
    @Order(5)
    void getIntegrationResponses() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetIntegrationResponses")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s"}
                        """.formatted(apiId, integrationId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Items", notNullValue())
                .body("Items.IntegrationResponseId", hasItem(integrationResponseId));
    }

    // ──────────────────────────── UpdateIntegrationResponse ────────────────────────────

    @Test
    @Order(6)
    void updateIntegrationResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateIntegrationResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationResponseId":"%s","ContentHandlingStrategy":"CONVERT_TO_BINARY"}
                        """.formatted(apiId, integrationId, integrationResponseId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("ContentHandlingStrategy", equalTo("CONVERT_TO_BINARY"))
                .body("IntegrationResponseKey", equalTo("$default"));
    }

    // ──────────────────────────── DeleteIntegrationResponse ────────────────────────────

    @Test
    @Order(7)
    void deleteIntegrationResponse() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteIntegrationResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationResponseId":"%s"}
                        """.formatted(apiId, integrationId, integrationResponseId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── GetIntegrationResponse after delete ────────────────────────────

    @Test
    @Order(8)
    void getIntegrationResponseAfterDelete() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetIntegrationResponse")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","IntegrationId":"%s","IntegrationResponseId":"%s"}
                        """.formatted(apiId, integrationId, integrationResponseId))
                .when().post("/")
                .then()
                .statusCode(not(equalTo(200)));
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (apiId != null) {
            given()
                    .contentType(AMZ_JSON)
                    .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                    .header("Authorization", AUTH_HEADER)
                    .body("""
                            {"ApiId":"%s"}
                            """.formatted(apiId))
                    .when().post("/")
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
