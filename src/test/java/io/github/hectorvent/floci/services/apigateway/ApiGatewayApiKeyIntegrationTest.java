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
 * Integration tests for API Gateway API key tags and the GetApiKey route.
 *
 * <p>Validates that {@code CreateApiKey} persists {@code tags} and returns them
 * from {@code CreateApiKey}, {@code GetApiKey} and {@code GetApiKeys}, and that
 * {@code GET /apikeys/{apiKeyId}} is served (returning the key, omitting the
 * value unless {@code includeValue=true}, and 404 when the key is unknown).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayApiKeyIntegrationTest {

    private static String apiKeyId = "uninitialized";

    @Test @Order(1)
    void createApiKeyPersistsTags() {
        String body = """
                {"name":"k","enabled":true,"tags":{"Team":"platform","Project":"demo"}}
                """;
        apiKeyId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("k"))
                .body("enabled", equalTo(true))
                .body("tags.Team", equalTo("platform"))
                .body("tags.Project", equalTo("demo"))
                .extract().path("id");
    }

    @Test @Order(2)
    void getApiKeyReturnsTagsAndOmitsValueByDefault() {
        given()
                .when().get("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("id", equalTo(apiKeyId))
                .body("tags.Team", equalTo("platform"))
                .body("tags.Project", equalTo("demo"))
                .body("value", nullValue());
    }

    @Test @Order(3)
    void getApiKeyIncludesValueWhenRequested() {
        given()
                .when().get("/apikeys/" + apiKeyId + "?includeValue=true")
                .then()
                .statusCode(200)
                .body("value", notNullValue());
    }

    @Test @Order(4)
    void listApiKeysIncludesTagsAndOmitsValueByDefault() {
        given()
                .when().get("/apikeys")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + apiKeyId + "' }.tags.Team", equalTo("platform"))
                .body("item.find { it.id == '" + apiKeyId + "' }.value", nullValue());

        given()
                .when().get("/apikeys?includeValues=true")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + apiKeyId + "' }.value", notNullValue());
    }

    @Test @Order(5)
    void getApiKeyNotFound() {
        given()
                .when().get("/apikeys/doesnotexist")
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid API Key identifier specified"));
    }
}
