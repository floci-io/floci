package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ApiGatewayImportApiKeysIntegrationTest {

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
}
