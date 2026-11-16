package io.floci.aws.services.apigateway;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With IAM enforcement off, the function policy is not consulted, so a REST API Lambda proxy
 * integration naming a function that does not exist is answered with 404 and
 * {@code Function not found}.
 */
@QuarkusTest
class ApiGatewayLambdaMissingFunctionIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

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
        apiId = createProxyApi(functionArn);

        given().when().get("/execute-api/" + apiId + "/test/items")
        .then()
                .statusCode(404)
                .body("message", equalTo("Function not found: " + functionArn));
    }

    private static String createProxyApi(String functionArn) {
        String id = apiGateway().body("{\"name\":\"lambda-missing-function\"}")
                .post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = apiGateway().get("/restapis/" + id + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = apiGateway().body("{\"pathPart\":\"items\"}")
                .post("/restapis/" + id + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        String methodPath = "/restapis/" + id + "/resources/" + resourceId + "/methods/GET";
        apiGateway().body("{\"authorizationType\":\"NONE\"}").put(methodPath).then().statusCode(201);

        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("type", "AWS_PROXY");
        integration.put("httpMethod", "POST");
        integration.put("uri", "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + functionArn + "/invocations");
        apiGateway().body(integration).put(methodPath + "/integration").then().statusCode(201);

        String deploymentId = apiGateway().body("{}").post("/restapis/" + id + "/deployments")
                .then().statusCode(201).extract().path("id");
        apiGateway().body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .post("/restapis/" + id + "/stages").then().statusCode(201);
        return id;
    }

    private static RequestSpecification apiGateway() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261011/" + REGION
                + "/apigateway/aws4_request, SignedHeaders=host, Signature=abc").contentType(ContentType.JSON);
    }
}
