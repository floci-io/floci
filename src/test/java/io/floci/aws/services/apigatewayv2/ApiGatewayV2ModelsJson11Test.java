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
 * Tests for API Gateway v2 Models CRUD via the JSON 1.1 path.
 * Verifies PascalCase key normalization and all Model CRUD operations
 * through the AmazonApiGatewayV2.* X-Amz-Target header.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2ModelsJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;
    private static String modelId;

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
                        {"Name":"models-json11-test","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("models-json11-test"))
                .extract().path("ApiId");
    }

    // ──────────────────────────── CreateModel ────────────────────────────

    @Test
    @Order(2)
    void createModel() {
        modelId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateModel")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {
                          "ApiId": "%s",
                          "Name": "PetModel",
                          "Schema": "{\\"$schema\\":\\"http://json-schema.org/draft-04/schema#\\",\\"title\\":\\"Pet\\",\\"type\\":\\"object\\"}",
                          "ContentType": "application/json"
                        }
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ModelId", notNullValue())
                .body("Name", equalTo("PetModel"))
                .body("Schema", notNullValue())
                .body("ContentType", equalTo("application/json"))
                .extract().path("ModelId");
    }

    // ──────────────────────────── GetModel ────────────────────────────

    @Test
    @Order(3)
    void getModel() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetModel")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","ModelId":"%s"}
                        """.formatted(apiId, modelId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("ModelId", equalTo(modelId))
                .body("Name", equalTo("PetModel"))
                .body("Schema", notNullValue())
                .body("ContentType", equalTo("application/json"));
    }

    // ──────────────────────────── GetModels ────────────────────────────

    @Test
    @Order(4)
    void getModels() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetModels")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(apiId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Items", notNullValue())
                .body("Items.ModelId", hasItem(modelId));
    }

    // ──────────────────────────── UpdateModel ────────────────────────────

    @Test
    @Order(5)
    void updateModel() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateModel")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","ModelId":"%s","Description":"updated description"}
                        """.formatted(apiId, modelId))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("ModelId", equalTo(modelId))
                .body("Description", equalTo("updated description"))
                .body("Name", equalTo("PetModel"));
    }

    // ──────────────────────────── DeleteModel ────────────────────────────

    @Test
    @Order(6)
    void deleteModel() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteModel")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","ModelId":"%s"}
                        """.formatted(apiId, modelId))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── GetModel after delete ────────────────────────────

    @Test
    @Order(7)
    void getModelAfterDelete() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetModel")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","ModelId":"%s"}
                        """.formatted(apiId, modelId))
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
