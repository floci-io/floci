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
 * JSON 1.1 path integration tests for the three standalone tagging operations:
 * AmazonApiGatewayV2.TagResource, AmazonApiGatewayV2.UntagResource, and
 * AmazonApiGatewayV2.GetTags.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2TaggingJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260413/us-east-1/apigatewayv2/aws4_request";

    private static String apiId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Builds the ARN for the given API ID. */
    private static String arn(String id) {
        return "arn:aws:apigateway:us-east-1::/apis/" + id;
    }

    // ──────────────────────────── Setup: create shared API ────────────────────────────

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"tagging-json11-test-api","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .body("ApiId", notNullValue())
                .body("Name", equalTo("tagging-json11-test-api"))
                .extract().path("ApiId");
    }

    // ──────────────────────────── TagResource — HTTP 201 + empty body ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.TagResource with PascalCase ResourceArn and Tags,
     * verify HTTP 201 and {} response.
     */
    @Test
    @Order(10)
    void tagResource_returns200AndEmptyBody() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","Tags":{"env":"production","team":"platform"}}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));
    }

    // ──────────────────────────── GetTags after TagResource ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.GetTags after tagging — verify Tags map in response
     * contains the added tags.
     */
    @Test
    @Order(11)
    void getTags_afterTagResource_containsAddedTags() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetTags")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s"}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("production"))
                .body("Tags.team", equalTo("platform"));
    }

    // ──────────────────────────── TagResource merge semantics ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.TagResource twice with different keys — verify merge
     * semantics via GetTags (both sets present).
     */
    @Test
    @Order(20)
    void tagResource_mergeSemantics_bothSetsPresent() {
        // First call: add "env" and "team"
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","Tags":{"env":"staging","team":"backend"}}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200);

        // Second call: add a different key "owner"
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","Tags":{"owner":"alice"}}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200);

        // Both sets must be present
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetTags")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s"}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("staging"))
                .body("Tags.team", equalTo("backend"))
                .body("Tags.owner", equalTo("alice"));
    }

    // ──────────────────────────── TagResource not-found ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.TagResource with a non-existent ARN — verify HTTP 404.
     */
    @Test
    @Order(30)
    void tagResource_notFound_returns404() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"arn:aws:apigateway:us-east-1::/apis/nonexistent","Tags":{"key":"value"}}
                        """)
                .when().post("/")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── UntagResource — removes keys ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.UntagResource with a TagKeys array — verify HTTP 204
     * and keys are removed via GetTags.
     */
    @Test
    @Order(40)
    void untagResource_removesKeys_returns204() {
        // Ensure tags are present first
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","Tags":{"env":"to-be-removed","keep":"this"}}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200);

        // Remove "env"
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UntagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","TagKeys":["env"]}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(204);

        // "env" must be absent; "keep" must still be present
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetTags")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s"}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags", not(hasKey("env")))
                .body("Tags.keep", equalTo("this"));
    }

    // ──────────────────────────── UntagResource silent-ignore ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.UntagResource with a key that does not exist —
     * verify HTTP 204 (silent ignore).
     */
    @Test
    @Order(41)
    void untagResource_nonexistentKey_silentIgnore_returns204() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UntagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s","TagKeys":["this-key-does-not-exist"]}
                        """.formatted(arn(apiId)))
                .when().post("/")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── UntagResource not-found ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.UntagResource with a non-existent ARN — verify HTTP 404.
     */
    @Test
    @Order(50)
    void untagResource_notFound_returns404() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UntagResource")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"arn:aws:apigateway:us-east-1::/apis/nonexistent","TagKeys":["somekey"]}
                        """)
                .when().post("/")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── GetTags on API with no tags ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.GetTags on an API with no tags — verify HTTP 200
     * and {"Tags": {}}.
     */
    @Test
    @Order(60)
    void getTags_noTags_returnsEmptyMap() {
        // Create a fresh API with no tags
        String freshApiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"tagging-json11-notags-api","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then()
                .statusCode(201)
                .extract().path("ApiId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetTags")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"%s"}
                        """.formatted(arn(freshApiId)))
                .when().post("/")
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());

        // Cleanup the fresh API
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(freshApiId))
                .when().post("/")
                .then()
                .statusCode(anyOf(equalTo(204), equalTo(404)));
    }

    // ──────────────────────────── GetTags not-found ────────────────────────────

    /**
     * Send AmazonApiGatewayV2.GetTags on a non-existent ARN — verify HTTP 404.
     */
    @Test
    @Order(70)
    void getTags_notFound_returns404() {
        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetTags")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ResourceArn":"arn:aws:apigateway:us-east-1::/apis/nonexistent"}
                        """)
                .when().post("/")
                .then()
                .statusCode(404);
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
