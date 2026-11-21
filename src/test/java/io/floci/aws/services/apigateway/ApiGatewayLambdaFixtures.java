package io.floci.aws.services.apigateway;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/**
 * A deployed REST API whose resource at a given path has one method integrated with a Lambda
 * function, served on the {@code test} stage, and the signed requests that build it.
 */
final class ApiGatewayLambdaFixtures {

    static final String REGION = "us-east-1";
    static final String ACCOUNT = "000000000000";

    private ApiGatewayLambdaFixtures() {
    }

    static String createLambdaRestApi(String resourcePath, String httpMethod, String type, String functionArn,
                                      String credentials) {
        String apiId = apiGateway()
                .body("{\"name\":\"lambda-integration-" + UUID.randomUUID().toString().substring(0, 8) + "\"}")
                .post("/restapis").then().statusCode(201).extract().path("id");
        String resourceId = apiGateway().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        for (String pathPart : resourcePath.substring(1).split("/")) {
            resourceId = apiGateway().body(Map.of("pathPart", pathPart))
                    .post("/restapis/" + apiId + "/resources/" + resourceId)
                    .then().statusCode(201).extract().path("id");
        }
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/" + httpMethod;
        apiGateway().body("{\"authorizationType\":\"NONE\"}").put(methodPath).then().statusCode(201);

        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("type", type);
        integration.put("httpMethod", "POST");
        integration.put("uri", "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + functionArn + "/invocations");
        if (credentials != null) {
            integration.put("credentials", credentials);
        }
        apiGateway().body(integration).put(methodPath + "/integration").then().statusCode(201);
        if ("AWS".equals(type)) {
            apiGateway().body("{\"selectionPattern\":\"\"}")
                    .put(methodPath + "/integration/responses/200").then().statusCode(201);
        }

        String deploymentId = apiGateway().body("{}").post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        apiGateway().body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .post("/restapis/" + apiId + "/stages").then().statusCode(201);
        return apiId;
    }

    static RequestSpecification apiGateway() {
        return signed("apigateway").contentType(ContentType.JSON);
    }

    static RequestSpecification signed(String service) {
        return signed(service, REGION);
    }

    static RequestSpecification signed(String service, String region) {
        return given().header("Authorization", auth(ACCOUNT, region, service));
    }
}
