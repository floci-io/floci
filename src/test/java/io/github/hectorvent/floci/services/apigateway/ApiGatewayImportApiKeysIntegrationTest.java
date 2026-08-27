package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayImportApiKeysIntegrationTest {

    /**
     * Botocore sends ImportApiKeys with no Content-Type header at all — only {@code Accept:
     * application/json} — so the handler has to be reachable without one. RestAssured stamps a
     * default Content-Type on any request carrying a body, so this case uses a raw client.
     */
    @Test
    void testImportApiKeysWithoutContentTypeHeader() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + RestAssured.port
                                + "/apikeys?mode=import&format=csv&failonwarnings=false"))
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("Name,Key\nno-content-type-key,no-content-type-secret\n"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode(), "ImportApiKeys must dispatch without a Content-Type: " + response.body());
        assertTrue(response.body().contains("\"ids\""), response.body());
    }

    @Test
    void testImportApiKeys() {
        String importedId = given()
                .contentType("text/csv")
                .body("name,value,enabled\nimported-key,secret-value,true\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .body("ids", hasSize(1))
                .extract().path("ids[0]");

        given()
                .pathParam("importedId", importedId)
                .when()
                .get("/apikeys/{importedId}?includeValue=true")
                .then()
                .statusCode(200)
                .body("name", org.hamcrest.Matchers.equalTo("imported-key"))
                .body("value", org.hamcrest.Matchers.equalTo("secret-value"))
                .body("enabled", org.hamcrest.Matchers.equalTo(true));
    }

    /** AWS ImportApiKeys uses the TitleCase header {@code Name,Key,Description,Enabled,UsagePlanIds}. */
    @Test
    void testImportApiKeysAcceptsAwsNativeHeader() {
        String importedId = given()
                .contentType("text/csv")
                .body("Name,Key,Description,Enabled,UsagePlanIds\n"
                        + "aws-format-key,aws-format-secret-value,a description,true,\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .body("ids", hasSize(1))
                .extract().path("ids[0]");

        given()
                .pathParam("importedId", importedId)
                .when()
                .get("/apikeys/{importedId}?includeValue=true")
                .then()
                .statusCode(200)
                .body("name", equalTo("aws-format-key"))
                .body("value", equalTo("aws-format-secret-value"))
                .body("description", equalTo("a description"))
                .body("enabled", equalTo(true));
    }

    /** Columns are addressed by name, not by position, and a missing Enabled column defaults to true. */
    @Test
    void testImportApiKeysReadsColumnsByNameAndDefaultsEnabled() {
        String importedId = given()
                .contentType("text/csv")
                .body("Key,UsagePlanIds,Name\nreordered-secret,,reordered-key\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .body("ids", hasSize(1))
                .extract().path("ids[0]");

        given()
                .pathParam("importedId", importedId)
                .when()
                .get("/apikeys/{importedId}?includeValue=true")
                .then()
                .statusCode(200)
                .body("name", equalTo("reordered-key"))
                .body("value", equalTo("reordered-secret"))
                .body("enabled", equalTo(true));
    }

    /** The CSV Key column is the key VALUE; AWS generates a distinct id, so it must not be reused as the id. */
    @Test
    void testImportedKeyIdIsDistinctFromKeyValue() {
        String importedId = given()
                .contentType("text/csv")
                .body("Name,Key\ndistinct-id-key,distinct-id-secret-value\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .extract().path("ids[0]");

        org.junit.jupiter.api.Assertions.assertNotEquals("distinct-id-secret-value", importedId);

        given()
                .pathParam("importedId", importedId)
                .when()
                .get("/apikeys/{importedId}?includeValue=true")
                .then()
                .statusCode(200)
                .body("id", not(equalTo("distinct-id-secret-value")))
                .body("value", equalTo("distinct-id-secret-value"));
    }

    @Test
    void testDuplicateKeyValueEmitsWarning() {
        given()
                .contentType("text/csv")
                .body("Name,Key\nwarn-a,duplicate-secret\nwarn-b,duplicate-secret\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(201)
                .body("ids", hasSize(2))
                .body("warnings", hasSize(1));
    }

    @Test
    void testDuplicateKeyValueFailsWhenFailOnWarnings() {
        given()
                .contentType("text/csv")
                .body("Name,Key\nfail-a,fail-duplicate-secret\nfail-b,fail-duplicate-secret\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=true")
                .then()
                .statusCode(400);
    }

    @Test
    void testHeaderWithoutKeyColumnIsRejected() {
        given()
                .contentType("text/csv")
                .body("Name,Description\nno-key,nope\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(400);
    }

    /**
     * A malformed CSV (unterminated quote) fails inside {@code ApiKeyCsvParser.parse} with an
     * {@code IllegalArgumentException}, which no exception mapper handles — only {@code AwsException}
     * is mapped. The request must still answer the modelled 400, not a 500 that escapes the AWS
     * error boundary.
     */
    @Test
    void testMalformedCsvWithUnterminatedQuoteIsRejectedAsBadRequest() {
        given()
                .contentType("text/csv")
                .body("Name,Key\n\"unterminated,secret-value\n")
                .when()
                .post("/apikeys?mode=import&format=csv&failonwarnings=false")
                .then()
                .statusCode(400);
    }
}
