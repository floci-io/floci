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
class LambdaCodeSigningConfigIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String FUNCTION_NAME = "code-signing-test-function";

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
                    "Description": "Code signing config test function"
                }
                """, FUNCTION_NAME))
        .when()
            .post(BASE_PATH + "/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FUNCTION_NAME));
    }

    @Test
    @Order(2)
    void getFunctionCodeSigningConfig() {
        given()
        .when()
            .get(BASE_PATH + "/functions/" + FUNCTION_NAME + "/code-signing-config")
        .then()
            .statusCode(200)
            .body("FunctionName", equalTo(FUNCTION_NAME))
            .body("CodeSigningConfigArn", notNullValue());
    }

    @Test
    @Order(3)
    void getFunctionCodeSigningConfigNotFound() {
        given()
        .when()
            .get(BASE_PATH + "/functions/nonexistent-function/code-signing-config")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(4)
    void cleanup() {
        given()
        .when()
            .delete(BASE_PATH + "/functions/" + FUNCTION_NAME)
        .then()
            .statusCode(204);
    }
}
