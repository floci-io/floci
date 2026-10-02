package io.github.hectorvent.floci.services.secretsmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * RotateSecret against the real Lambda service: a function reference Lambda refuses is answered with
 * RotateSecret's own {@code InvalidParameterException}, never one of Lambda's codes (Secrets Manager
 * API Reference, RotateSecret, "Errors").
 */
@QuarkusTest
class SecretsManagerRotationFunctionRegionIntegrationTest {

    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void rotationFunctionInAnotherRegionIsAnInvalidParameter() {
        String secretName = "rotation-other-region-secret";
        secretsManager("CreateSecret", "{\"Name\": \"" + secretName + "\", \"SecretString\": \"initial-value\"}")
            .then()
            .statusCode(200);

        secretsManager("RotateSecret", "{\"SecretId\": \"" + secretName + "\", "
                + "\"RotationLambdaARN\": \"arn:aws:lambda:us-west-2:000000000000:function:rotator\"}")
            .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));

        secretsManager("DescribeSecret", "{\"SecretId\": \"" + secretName + "\"}")
            .then()
            .statusCode(200)
            .body("RotationEnabled", equalTo(false))
            .body("RotationLambdaARN", nullValue());
    }

    private static Response secretsManager(String action, String body) {
        return given()
            .header("X-Amz-Target", "secretsmanager." + action)
            .contentType(SM_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/");
    }
}
