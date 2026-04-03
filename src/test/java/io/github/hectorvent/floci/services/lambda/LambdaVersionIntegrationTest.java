package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaVersionIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String FUNCTION_NAME = "versioned-function";

    @Test
    @Order(1)
    void createFunction() {
        given()
            .contentType("application/json")
            .body(String.format("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Description": "Version test function"
                }
                """, FUNCTION_NAME))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("$LATEST"));
    }

    @Test
    @Order(2)
    void publishVersion() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Description": "First version"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("1"))
            .body("Description", equalTo("First version"))
            .body("FunctionArn", containsString(FUNCTION_NAME + ":1"));
    }

    @Test
    @Order(3)
    void publishSecondVersion() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "Description": "Second version"
                }
                """)
        .when()
            .post(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("2"))
            .body("Description", equalTo("Second version"))
            .body("FunctionArn", containsString(FUNCTION_NAME + ":2"));
    }

    @Test
    @Order(4)
    void listVersionsByFunction() {
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(200)
            .body("Versions", hasSize(3)) // $LATEST, 1, 2
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2"))
            .body("Versions.find { it.Version == '1' }.Description", equalTo("First version"))
            .body("Versions.find { it.Version == '2' }.Description", equalTo("Second version"));
    }

    @Test
    @Order(5)
    void deleteFunctionDeletesAllVersions() {
        // Delete function
        given()
        .when()
            .delete(BASE_PATH + "/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(204);

        // Verify it's gone
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(404);
            
        // Verify versions are gone (actually listVersionsByFunction should return 404 if function doesn't exist)
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME + "/versions")
        .then()
            .statusCode(404);
    }
}
