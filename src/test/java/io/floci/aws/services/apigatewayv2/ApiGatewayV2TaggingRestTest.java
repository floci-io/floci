package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * REST path integration tests for the three standalone tagging operations:
 * TagResource (POST /v2/tags/{arn}), UntagResource (DELETE /v2/tags/{arn}),
 * and GetTags (GET /v2/tags/{arn}).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2TaggingRestTest {

    private static String apiId;

    /** Builds the ARN for the given API ID. */
    private static String arn(String id) {
        return "arn:aws:apigateway:us-east-1::/apis/" + id;
    }

    /** Builds the /v2/tags/{arn} path. Colons are valid in URL paths; slashes in the ARN
     *  are captured by the {@code {resourceArn: .+}} regex in the controller. */
    private static String tagsPath(String id) {
        return "/v2/tags/" + arn(id);
    }

    // ──────────────────────────── Setup: create shared API ────────────────────────────

    @Test
    @Order(1)
    void createApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"tagging-rest-test-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");
    }

    // ──────────────────────────── TagResource — HTTP 201 + empty body ────────────────────────────

    /**
     * Create API, POST /v2/tags/{arn} with a tags map, verify HTTP 201 and {} response body.
     */
    @Test
    @Order(10)
    void tagResource_returns201AndEmptyBody() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"env":"production","team":"platform"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201)
                .body(equalTo("{}"));
    }

    // ──────────────────────────── GetTags after TagResource ────────────────────────────

    /**
     * GET /v2/tags/{arn} after tagging — verify the response tags map contains the added keys.
     */
    @Test
    @Order(11)
    void getTags_afterTagResource_containsAddedTags() {
        given()
                .when().get(tagsPath(apiId))
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("production"))
                .body("tags.team", equalTo("platform"));
    }

    // ──────────────────────────── TagResource merge semantics ────────────────────────────

    /**
     * Call PUT twice with different keys — verify both sets are present via GetTags.
     */
    @Test
    @Order(20)
    void tagResource_mergeSemantics_bothSetsPresent() {
        // First PUT: add "env" and "team" (already done in order 10, but re-add to be explicit)
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"env":"staging","team":"backend"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201);

        // Second PUT: add a different key "owner"
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"owner":"alice"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201);

        // Both sets must be present
        given()
                .when().get(tagsPath(apiId))
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("staging"))
                .body("tags.team", equalTo("backend"))
                .body("tags.owner", equalTo("alice"));
    }

    // ──────────────────────────── TagResource overwrite ────────────────────────────

    /**
     * Call PUT with an existing key and new value — verify the value is updated via GetTags.
     */
    @Test
    @Order(21)
    void tagResource_overwrite_updatesExistingValue() {
        // "env" was "staging" from order 20; overwrite it
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"env":"production-overwritten"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201);

        given()
                .when().get(tagsPath(apiId))
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("production-overwritten"))
                // Other keys must still be present
                .body("tags.team", equalTo("backend"))
                .body("tags.owner", equalTo("alice"));
    }

    // ──────────────────────────── 4.5 TagResource not-found ────────────────────────────

    /**
     * 4.5 PUT /v2/tags/{arn} with a non-existent API ARN — verify HTTP 404.
     */
    @Test
    @Order(30)
    void tagResource_notFound_returns404() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"key":"value"}}
                        """)
                .when().post(tagsPath("nonexistent"))
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── UntagResource — removes key ────────────────────────────

    /**
     * Create API with tags, DELETE /v2/tags/{arn}?tagKeys=key1, verify HTTP 204 and key absent.
     */
    @Test
    @Order(40)
    void untagResource_removesKey_returns204() {
        // Ensure "env" is present first
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"env":"to-be-removed","keep":"this"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201);

        // Remove "env"
        given()
                .when().delete(tagsPath(apiId) + "?tagKeys=env")
                .then()
                .statusCode(204);

        // "env" must be absent; "keep" must still be present
        given()
                .when().get(tagsPath(apiId))
                .then()
                .statusCode(200)
                .body("tags", not(hasKey("env")))
                .body("tags.keep", equalTo("this"));
    }

    // ──────────────────────────── UntagResource silent-ignore ────────────────────────────

    /**
     * DELETE /v2/tags/{arn}?tagKeys=nonexistent — verify HTTP 204 (silent ignore).
     */
    @Test
    @Order(41)
    void untagResource_nonexistentKey_silentIgnore_returns204() {
        given()
                .when().delete(tagsPath(apiId) + "?tagKeys=this-key-does-not-exist")
                .then()
                .statusCode(204);
    }

    // ──────────────────────────── UntagResource multiple keys ────────────────────────────

    /**
     * DELETE /v2/tags/{arn}?tagKeys=key1&tagKeys=key2 — verify both keys are removed.
     */
    @Test
    @Order(42)
    void untagResource_multipleKeys_bothRemoved() {
        // Add two keys to remove
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"tags":{"alpha":"1","beta":"2","gamma":"3"}}
                        """)
                .when().post(tagsPath(apiId))
                .then()
                .statusCode(201);

        // Remove alpha and beta in one call using repeated query params (AWS SDK format)
        given()
                .queryParam("tagKeys", "alpha")
                .queryParam("tagKeys", "beta")
                .when().delete(tagsPath(apiId))
                .then()
                .statusCode(204);

        // alpha and beta must be gone; gamma must remain
        given()
                .when().get(tagsPath(apiId))
                .then()
                .statusCode(200)
                .body("tags", not(hasKey("alpha")))
                .body("tags", not(hasKey("beta")))
                .body("tags.gamma", equalTo("3"));
    }

    // ──────────────────────────── UntagResource not-found ────────────────────────────

    /**
     * DELETE /v2/tags/{arn} with a non-existent API ARN — verify HTTP 404.
     */
    @Test
    @Order(50)
    void untagResource_notFound_returns404() {
        given()
                .when().delete(tagsPath("nonexistent") + "?tagKeys=somekey")
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── GetTags on API with no tags ────────────────────────────

    /**
     * Create an API with no tags, GET /v2/tags/{arn} — verify HTTP 200 and {"tags": {}}.
     */
    @Test
    @Order(60)
    void getTags_noTags_returnsEmptyMap() {
        // Create a fresh API with no tags
        String freshApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"tagging-rest-notags-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");

        given()
                .when().get(tagsPath(freshApiId))
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());

        // Cleanup the fresh API
        given()
                .when().delete("/v2/apis/" + freshApiId)
                .then()
                .statusCode(anyOf(equalTo(204), equalTo(404)));
    }

    // ──────────────────────────── GetTags not-found ────────────────────────────

    /**
     * GET /v2/tags/{arn} with a non-existent API ARN — verify HTTP 404.
     */
    @Test
    @Order(70)
    void getTags_notFound_returns404() {
        given()
                .when().get(tagsPath("nonexistent"))
                .then()
                .statusCode(404);
    }

    // ──────────────────────────── Cleanup ────────────────────────────

    @Test
    @Order(99)
    void cleanup() {
        if (apiId != null) {
            given()
                    .when().delete("/v2/apis/" + apiId)
                    .then()
                    .statusCode(anyOf(equalTo(204), equalTo(404)));
        }
    }
}
