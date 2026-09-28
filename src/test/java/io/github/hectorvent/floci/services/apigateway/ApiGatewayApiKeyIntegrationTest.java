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
                {"name":"k","enabled":true,"customerId":"marketplace-customer-1","tags":{"Team":"platform","Project":"demo"}}
                """;
        apiKeyId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("k"))
                .body("customerId", equalTo("marketplace-customer-1"))
                .body("enabled", equalTo(true))
                .body("createdDate", greaterThan(0))
                .body("lastUpdatedDate", greaterThan(0))
                .body("stageKeys", empty())
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
                .body("customerId", equalTo("marketplace-customer-1"))
                .body("createdDate", greaterThan(0))
                .body("lastUpdatedDate", greaterThan(0))
                .body("stageKeys", empty())
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
                .body("item.find { it.id == '" + apiKeyId + "' }.createdDate", greaterThan(0))
                .body("item.find { it.id == '" + apiKeyId + "' }.lastUpdatedDate", greaterThan(0))
                .body("item.find { it.id == '" + apiKeyId + "' }.stageKeys", empty())
                .body("item.find { it.id == '" + apiKeyId + "' }.customerId", equalTo("marketplace-customer-1"))
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

    @Test @Order(6)
    void updateApiKeyName() {
        String body = """
                {"patchOperations":[{"op":"replace","path":"/name","value":"updated-name"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().patch("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("id", equalTo(apiKeyId))
                .body("name", equalTo("updated-name"))
                .body("customerId", equalTo("marketplace-customer-1"))
                .body("createdDate", greaterThan(0))
                .body("lastUpdatedDate", greaterThan(0))
                .body("stageKeys", empty())
                .body("value", nullValue());
    }

    @Test @Order(7)
    void updateApiKeyCustomerId() {
        String body = """
                {"patchOperations":[{"op":"replace","path":"/customerId","value":"marketplace-customer-2"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().patch("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("customerId", equalTo("marketplace-customer-2"));

        given()
                .when().get("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("customerId", equalTo("marketplace-customer-2"));
    }

    @Test @Order(8)
    void updateApiKeyDescription() {
        String body = """
                {"patchOperations":[{"op":"replace","path":"/description","value":"a test key"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().patch("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("description", equalTo("a test key"));
    }

    @Test @Order(9)
    void updateApiKeyEnabled() {
        String body = """
                {"patchOperations":[{"op":"replace","path":"/enabled","value":"false"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().patch("/apikeys/" + apiKeyId)
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false));
    }

    @Test @Order(10)
    void updateApiKeyNotFound() {
        String body = """
                {"patchOperations":[{"op":"replace","path":"/name","value":"x"}]}
                """;
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().patch("/apikeys/doesnotexist")
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid API Key identifier specified"));
    }

    @Test @Order(11)
    void deleteApiKey() {
        given()
                .when().delete("/apikeys/" + apiKeyId)
                .then()
                .statusCode(202);
    }

    @Test @Order(12)
    void deleteApiKeyAlreadyGone() {
        given()
                .when().delete("/apikeys/" + apiKeyId)
                .then()
                .statusCode(404)
                .contentType(ContentType.JSON)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid API Key identifier specified"));
    }

    @Test @Order(13)
    void createApiKey_generateDistinctIdFalse_idEqualsValue() {
        String body = """
                {"name":"distinct-id-key-false","enabled":true,"generateDistinctId":false,"value":"11112222333344445555666677778888"}
                """;
        String keyId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("tags", anEmptyMap())
                .body("stageKeys", empty())
                .extract().path("id");

        given()
                .when().get("/apikeys/" + keyId + "?includeValue=true")
                .then()
                .statusCode(200)
                .body("id", equalTo(keyId))
                .body("value", equalTo("11112222333344445555666677778888"))
                .body("value", equalTo(keyId))
                .body("tags", anEmptyMap())
                .body("stageKeys", empty());
    }

    @Test @Order(14)
    void createApiKey_generateDistinctIdAbsent_idDiffersFromValue() {
        String body = """
                {"name":"default-id-key","enabled":true,"value":"99992222333344445555666677778888"}
                """;
        String keyId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        given()
                .when().get("/apikeys/" + keyId + "?includeValue=true")
                .then()
                .statusCode(200)
                .body("id", equalTo(keyId))
                .body("value", equalTo("99992222333344445555666677778888"))
                .body("value", not(equalTo(keyId)));
    }

    @Test @Order(15)
    void createApiKey_generateDistinctIdTrue_idDistinctFromValue() {
        String body = """
                {"name":"distinct-id-key","enabled":true,"generateDistinctId":true}
                """;
        String keyId = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/apikeys")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        given()
                .when().get("/apikeys/" + keyId + "?includeValue=true")
                .then()
                .statusCode(200)
                .body("id", equalTo(keyId))
                .body("value", not(equalTo(keyId)));
    }
    @Test @Order(16)
    void getDeletedApiKeyNotFound() {
        given()
                .when().get("/apikeys/" + apiKeyId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }
}
