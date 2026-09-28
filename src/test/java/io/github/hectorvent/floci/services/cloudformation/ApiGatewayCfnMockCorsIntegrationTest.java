package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Serverless Framework CORS shape: an OPTIONS method with a MOCK integration in CFN. */
@QuarkusTest
class ApiGatewayCfnMockCorsIntegrationTest {

    private static final String STACK = "apigw-cfn-mock-cors-it";
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Api": {"Type":"AWS::ApiGateway::RestApi", "Properties":{"Name":"cfn-mock-cors"}},
                "Items": {"Type":"AWS::ApiGateway::Resource", "Properties":{
                  "RestApiId":{"Ref":"Api"},
                  "ParentId":{"Fn::GetAtt":["Api","RootResourceId"]},
                  "PathPart":"items"}},
                "Options": {"Type":"AWS::ApiGateway::Method", "Properties":{
                  "RestApiId":{"Ref":"Api"}, "ResourceId":{"Ref":"Items"},
                  "HttpMethod":"OPTIONS", "AuthorizationType":"NONE",
                  "MethodResponses":[{"StatusCode":"200", "ResponseParameters":{
                    "method.response.header.Access-Control-Allow-Origin":true,
                    "method.response.header.Access-Control-Allow-Methods":true,
                    "method.response.header.Access-Control-Allow-Headers":true}}],
                  "Integration":{"Type":"MOCK", "PassthroughBehavior":"NEVER",
                    "RequestTemplates":{"application/json":"{\\"statusCode\\":200}"},
                    "IntegrationResponses":[{"StatusCode":"200", "ResponseParameters":{
                      "method.response.header.Access-Control-Allow-Origin":"'*'",
                      "method.response.header.Access-Control-Allow-Methods":"'GET,OPTIONS'",
                      "method.response.header.Access-Control-Allow-Headers":"'Content-Type,X-Api-Key'"}}]}}},
                "Deployment": {"Type":"AWS::ApiGateway::Deployment", "DependsOn":"Options",
                  "Properties":{"RestApiId":{"Ref":"Api"},"StageName":"dev"}}
              },
              "Outputs":{"ApiId":{"Value":{"Ref":"Api"}},
                         "ResourceId":{"Value":{"Ref":"Items"}}}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void cloudFormationMockPreflightReturnsItsConfiguredCorsHeaders() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", STACK)
                .formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        try {
            String xml = given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DescribeStacks")
                    .formParam("StackName", STACK)
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
                    .extract().asString();
            String apiId = XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue")
                    .get("ApiId");
            String resourceId = XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue")
                    .get("ResourceId");

            given().when().get("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/OPTIONS/integration/responses/200")
                    .then().statusCode(200)
                    .body("responseParameters.'method.response.header.Access-Control-Allow-Origin'",
                            equalTo("'*'"));

            for (String path : new String[]{"/execute-api/" + apiId + "/dev/items",
                    "/restapis/" + apiId + "/dev/_user_request_/items"}) {
                var response = given().header("Origin", "https://example.com")
                        .header("Access-Control-Request-Method", "GET")
                        .when().options(path).then().statusCode(200)
                        .header("Access-Control-Allow-Origin", equalTo("*"))
                        .header("Access-Control-Allow-Methods", equalTo("GET,OPTIONS"))
                        .header("Access-Control-Allow-Headers", equalTo("Content-Type,X-Api-Key"))
                        .extract().response();
                assertEquals(200, response.statusCode());
            }
        } finally {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", STACK)
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(STACK);
        }
    }
}
