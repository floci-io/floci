package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class ApiGatewayOpenApiWarningsIntegrationTest {

    private static final String SPEC = """
            {"openapi":"3.0.1","info":{"title":"WarningsAPI","version":"1.0"},
             "paths":{"/p":{"get":{"security":"not-an-array",
               "responses":{"200":{"description":"ok"}}}}}}
            """;
    private static final String CLEAN_SPEC = SPEC.replace("\"security\":\"not-an-array\",", "");

    @Test
    void lenientImportReturnsAndPersistsParserWarnings() {
        String id = given().queryParam("mode", "import").body(SPEC).post("/restapis")
                .then().statusCode(201).body("warnings", hasItem(containsString("security")))
                .extract().path("id");
        try {
            given().get("/restapis/" + id).then().statusCode(200)
                    .body("warnings", hasItem(containsString("security")));
        } finally {
            given().delete("/restapis/" + id).then().statusCode(202);
        }
    }

    @Test
    void strictImportDoesNotCreateAnApi() {
        List<String> before = apiIds();
        given().queryParam("mode", "import").queryParam("failonwarnings", true)
                .body(SPEC).post("/restapis").then().statusCode(400)
                .body("__type", containsString("BadRequestException"))
                .body("message", containsString("security"));
        assertEquals(before, apiIds());
    }

    @Test
    void strictOverwritePreservesApiMetadataAndResources() {
        String id = importCleanApi();
        try {
            String resourcesBefore = given().get("/restapis/" + id + "/resources")
                    .then().statusCode(200).extract().asString();
            given().queryParam("mode", "overwrite").queryParam("failonwarnings", true)
                    .body(SPEC.replace("WarningsAPI", "RejectedAPI"))
                    .put("/restapis/" + id).then().statusCode(400);
            given().get("/restapis/" + id).then().statusCode(200)
                    .body("name", not("RejectedAPI"));
            assertEquals(resourcesBefore, given().get("/restapis/" + id + "/resources")
                    .then().statusCode(200).extract().asString());
        } finally {
            given().delete("/restapis/" + id).then().statusCode(202);
        }
    }

    @Test
    void lenientOverwriteReturnsWarningsAndCleanOverwriteClearsThem() {
        String id = importCleanApi();
        try {
            given().queryParam("mode", "overwrite").queryParam("failonwarnings", false)
                    .body(SPEC).put("/restapis/" + id).then().statusCode(200)
                    .body("warnings", hasItem(containsString("security")));
            given().queryParam("mode", "overwrite").queryParam("failonwarnings", true)
                    .body(CLEAN_SPEC).put("/restapis/" + id).then().statusCode(200);
            List<String> warnings = given().get("/restapis/" + id).then().statusCode(200)
                    .extract().path("warnings");
            assertFalse(warnings != null && !warnings.isEmpty());
        } finally {
            given().delete("/restapis/" + id).then().statusCode(202);
        }
    }

    private static String importCleanApi() {
        return given().queryParam("mode", "import").queryParam("failonwarnings", true)
                .body(CLEAN_SPEC).post("/restapis").then().statusCode(201)
                .body("id", notNullValue()).extract().path("id");
    }

    private static List<String> apiIds() {
        return given().get("/restapis").then().statusCode(200).extract().path("item.id");
    }

    @Test
    void anyMethodWarningsAreAlsoReturned() {
        String id = given().queryParam("mode", "import")
                .body(SPEC.replace("\"get\"", "\"x-amazon-apigateway-any-method\""))
                .post("/restapis").then().statusCode(201)
                .body("warnings", hasItem(containsString("security"))).extract().path("id");
        given().delete("/restapis/" + id).then().statusCode(202);
    }

    @Test
    void strictAnyMethodImportDoesNotCreateAnApi() {
        List<String> before = apiIds();
        given().queryParam("mode", "import").queryParam("failonwarnings", true)
                .body(SPEC.replace("\"get\"", "\"x-amazon-apigateway-any-method\""))
                .post("/restapis").then().statusCode(400)
                .body("message", containsString("security"));
        assertEquals(before, apiIds());
    }
}
