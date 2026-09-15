package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions an {@code AWS::ApiGateway::GatewayResponse} against a REST API created outside the
 * stack, the way SAM's {@code GatewayResponses} and CDK's {@code addGatewayResponse} put CORS
 * headers on gateway-generated 4XX answers. Asserts that {@code Ref} and {@code Fn::GetAtt Id}
 * resolve to {@code <restApiId>-<responseType>}, that the customisation takes effect on the
 * execute plane, that an update changes it in place, and that deleting the stack restores the
 * default without touching the API.
 */
@QuarkusTest
class ApiGatewayGatewayResponseCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "apigw-gatewayresponse-cfn-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "ApiId": {"Type": "String"},
            "Origin": {"Type": "String"},
            "StatusCode": {"Type": "String"}
          },
          "Resources": {
            "Cors4xx": {
              "Type": "AWS::ApiGateway::GatewayResponse",
              "Properties": {
                "RestApiId": {"Ref": "ApiId"},
                "ResponseType": "DEFAULT_4XX",
                "StatusCode": {"Ref": "StatusCode"},
                "ResponseParameters": {
                  "gatewayresponse.header.Access-Control-Allow-Origin": {"Fn::Sub": "'${Origin}'"},
                  "gatewayresponse.header.Access-Control-Allow-Headers": "'*'"
                },
                "ResponseTemplates": {
                  "application/json": "{\\"message\\":$context.error.messageString}"
                }
              }
            }
          },
          "Outputs": {
            "ResponseRef": {"Value": {"Ref": "Cors4xx"}},
            "ResponseId": {"Value": {"Fn::GetAtt": ["Cors4xx", "Id"]}}
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteAGatewayResponse() throws InterruptedException {
        String apiId = createMockApi(STACK);
        String deploymentId = createDeployment(apiId);
        createStage(apiId, "dev", deploymentId);
        try {
            cloudFormation("CreateStack", parameters(apiId, "https://app.example", "401"));
            String created = describeStacks("CREATE_COMPLETE");

            assertEquals(apiId + "-DEFAULT_4XX", outputValue(created, "ResponseRef"));
            assertEquals(apiId + "-DEFAULT_4XX", outputValue(created, "ResponseId"));

            getGatewayResponse(apiId)
                .statusCode(200)
                .body("defaultResponse", equalTo(false))
                .body("statusCode", equalTo("401"))
                .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Origin'",
                        equalTo("'https://app.example'"))
                .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Headers'", equalTo("'*'"));

            // The customisation shapes the gateway-generated answer to an unmatched route.
            given().when().get("/execute-api/" + apiId + "/dev/missing")
                .then().statusCode(401)
                .header("Access-Control-Allow-Origin", equalTo("https://app.example"))
                .header("Access-Control-Allow-Headers", equalTo("*"))
                .body("message", equalTo("Missing Authentication Token"));

            cloudFormation("UpdateStack", parameters(apiId, "https://other.example", "404"));
            String updated = describeStacks("UPDATE_COMPLETE");

            assertEquals(apiId + "-DEFAULT_4XX", outputValue(updated, "ResponseRef"));
            getGatewayResponse(apiId)
                .statusCode(200)
                .body("statusCode", equalTo("404"))
                .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Origin'",
                        equalTo("'https://other.example'"));
            given().when().get("/execute-api/" + apiId + "/dev/missing")
                .then().statusCode(404)
                .header("Access-Control-Allow-Origin", equalTo("https://other.example"));

            cloudFormation("DeleteStack", Map.of());
            awaitStackDeleted();

            // The stack owned the customisation, not the API: the type is back to its default.
            getGatewayResponse(apiId).statusCode(200).body("defaultResponse", equalTo(true));
            given().when().get("/restapis/" + apiId).then().statusCode(200);
            given().when().get("/execute-api/" + apiId + "/dev/missing")
                .then().statusCode(403)
                .header("Access-Control-Allow-Origin", nullValue());
        } finally {
            given().when().delete("/restapis/" + apiId);
        }
    }

    private static Map<String, String> parameters(String apiId, String origin, String statusCode) {
        return Map.of("ApiId", apiId, "Origin", origin, "StatusCode", statusCode);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getGatewayResponse(String apiId) {
        return given().when().get("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX").then();
    }

    /** A REST API whose GET /items is a MOCK integration, enough for a deployment and a stage. */
    private static String createMockApi(String name) {
        String apiId = given().contentType(ContentType.JSON).body("{\"name\":\"" + name + "\"}")
            .when().post("/restapis").then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
            .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body("{\"pathPart\":\"items\"}")
            .when().post("/restapis/" + apiId + "/resources/" + rootId).then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
            .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"responseParameters\":{}}")
            .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\": 200}\"}}")
            .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{}\"}}")
            .when().put(method + "/integration/responses/200").then().statusCode(201);
        return apiId;
    }

    private static String createDeployment(String apiId) {
        return given().contentType(ContentType.JSON).body("{\"description\":\"v1\"}")
            .when().post("/restapis/" + apiId + "/deployments").then().statusCode(201).extract().path("id");
    }

    private static void createStage(String apiId, String stage, String deploymentId) {
        given().contentType(ContentType.JSON)
            .body("{\"stageName\":\"" + stage + "\",\"deploymentId\":\"" + deploymentId + "\"}")
            .when().post("/restapis/" + apiId + "/stages").then().statusCode(201);
    }
}
