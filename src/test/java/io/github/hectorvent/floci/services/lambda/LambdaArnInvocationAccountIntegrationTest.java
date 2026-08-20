package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaArnInvocationAccountIntegrationTest {

    private static final String CALLER_ACCOUNT = "234567890123";
    private static final String TARGET_ACCOUNT = "345678901234";
    private static final String REGION = "us-east-1";
    private static final String FUNCTION_NAME = "arn-invocation-account-routing";

    @Test
    void fullArnInvocationUsesArnAccountInsteadOfCallerAccount() {
        given()
                .contentType("application/json")
                .header("Authorization", authorization(TARGET_ACCOUNT))
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs20.x",
                          "Role":"arn:aws:iam::%s:role/lambda-role",
                          "Handler":"index.handler"
                        }
                        """.formatted(FUNCTION_NAME, TARGET_ACCOUNT))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        String functionArn = "arn:aws:lambda:" + REGION + ":" + TARGET_ACCOUNT
                + ":function:" + FUNCTION_NAME;

        given()
                .header("Authorization", authorization(CALLER_ACCOUNT))
                .header("X-Amz-Invocation-Type", "DryRun")
                .contentType("application/json")
                .body("{}")
                .when().post("/2015-03-31/functions/{functionArn}/invocations", functionArn)
                .then()
                .statusCode(204)
                .header("X-Amz-Executed-Version", equalTo("$LATEST"));
    }

    private String authorization(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260812/" + REGION + "/lambda/aws4_request";
    }
}
