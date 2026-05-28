package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class ApiGatewayV2WebSocketIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createStack_apiGatewayV2WebSocket() {
        String template = """
            {
              "Resources": {
                "MyWsApi": {
                  "Type": "AWS::ApiGatewayV2::Api",
                  "Properties": {
                    "Name": "ws-test-api",
                    "ProtocolType": "WEBSOCKET",
                    "RouteSelectionExpression": "$request.body.action",
                    "Description": "A test WS API",
                    "ApiKeySelectionExpression": "$request.header.x-api-key"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ws-api-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify stack status is CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ws-api-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Verify the API exists and has the correct properties
        given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetApis")
            .contentType("application/x-amz-json-1.1")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Items.find { it.Name == 'ws-test-api' }.RouteSelectionExpression", org.hamcrest.Matchers.equalTo("$request.body.action"))
            .body("Items.find { it.Name == 'ws-test-api' }.ProtocolType", org.hamcrest.Matchers.equalTo("WEBSOCKET"))
            .body("Items.find { it.Name == 'ws-test-api' }.Description", org.hamcrest.Matchers.equalTo("A test WS API"))
            .body("Items.find { it.Name == 'ws-test-api' }.ApiKeySelectionExpression", org.hamcrest.Matchers.equalTo("$request.header.x-api-key"));
    }
}
