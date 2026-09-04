package io.github.hectorvent.flociappsync;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Smoke test for the two-call round trip Floci depends on: compile a schema, then execute
 * a query against it. Does not exercise auth or field-level authorization — see
 * AuthorizationDataFetcherTest-equivalent coverage to add alongside it.
 */
@QuarkusTest
class AppSyncEngineResourceTest {

    private static final String SDL = "type Query { hello: String }";

    @Test
    void compileThenExecuteReturnsData() {
        given()
                .contentType("application/json")
                .body(Map.of("sdl", SDL))
                .when().post("/schemas/test-api")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("query", "{ hello }"))
                .when().post("/schemas/test-api/execute")
                .then().statusCode(200)
                .body("data.hello", equalTo(null));
    }

    @Test
    void executeWithoutACompiledSchemaReturns502() {
        given()
                .contentType("application/json")
                .body(Map.of("query", "{ hello }"))
                .when().post("/schemas/never-compiled/execute")
                .then().statusCode(502)
                .body("errors[0].errorType", equalTo("GraphQLSchemaException"));
    }

    @Test
    void invalidSchemaReturns400WithCodeErrors() {
        given()
                .contentType("application/json")
                .body(Map.of("sdl", "type Query { hello: NotARealType }"))
                .when().post("/schemas/bad-schema")
                .then().statusCode(400);
    }
}
