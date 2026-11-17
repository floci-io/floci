package io.floci.aws.services.apigateway;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.ACCOUNT;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.REGION;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.apiGateway;
import static io.floci.aws.services.apigateway.ApiGatewayLambdaFixtures.createLambdaRestApi;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With IAM enforcement off, the function policy is not consulted, so a REST API Lambda proxy
 * integration naming a function that does not exist is answered with 404 and
 * {@code Function not found}.
 */
@QuarkusTest
class ApiGatewayLambdaMissingFunctionIntegrationTest {

    private String apiId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            apiGateway().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void proxyIntegrationToAMissingFunctionAnswersFunctionNotFound() {
        String functionArn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:missing-"
                + UUID.randomUUID().toString().substring(0, 8);
        apiId = createLambdaRestApi("/items", "GET", "AWS_PROXY", functionArn, null);

        given().when().get("/execute-api/" + apiId + "/test/items")
        .then()
                .statusCode(404)
                .body("message", equalTo("Function not found: " + functionArn));
    }
}
