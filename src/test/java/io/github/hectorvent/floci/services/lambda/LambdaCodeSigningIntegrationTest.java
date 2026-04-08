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
class LambdaCodeSigningIntegrationTest {

    private static final String SIGNING_PATH = "/2020-06-30";
    private static final String LAMBDA_PATH = "/2015-03-31";

    @Test
    @Order(1)
    void setUp() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "signing-test-fn",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler"
                }
                """)
        .when()
            .post(LAMBDA_PATH + "/functions")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(2)
    void getFunctionCodeSigningConfig_returnsEmptyArnForExistingFunction() {
        given()
        .when()
            .get(SIGNING_PATH + "/functions/signing-test-fn/code-signing-config")
        .then()
            .statusCode(200)
            .body("FunctionName", equalTo("signing-test-fn"))
            .body("CodeSigningConfigArn", equalTo(""));
    }

    @Test
    @Order(3)
    void getFunctionCodeSigningConfig_returns404ForUnknownFunction() {
        given()
        .when()
            .get(SIGNING_PATH + "/functions/does-not-exist/code-signing-config")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(4)
    void tearDown() {
        given()
        .when()
            .delete(LAMBDA_PATH + "/functions/signing-test-fn")
        .then()
            .statusCode(204);
    }
}
