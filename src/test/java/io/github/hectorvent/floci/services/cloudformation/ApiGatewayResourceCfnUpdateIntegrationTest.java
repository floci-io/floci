package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A stack that adds an AWS::ApiGateway::Resource and its methods to a REST API living outside the
 * stack, the shape Serverless Framework deploys with {@code provider.apiGateway.restApiId}. The
 * redeploy only changes the Deployment's logical id.
 */
@QuarkusTest
class ApiGatewayResourceCfnUpdateIntegrationTest {

    private static final String STACK = "apigw-cfn-resource-update-it";
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Res": {"Type":"AWS::ApiGateway::Resource", "Properties":{
                  "RestApiId":"%1$s", "ParentId":"%2$s", "PathPart":"infrastructures"}},
                "Opt": {"Type":"AWS::ApiGateway::Method", "Properties":{
                  "RestApiId":"%1$s", "ResourceId":{"Ref":"Res"},
                  "HttpMethod":"OPTIONS", "AuthorizationType":"NONE", "Integration":{"Type":"MOCK"}}},
                "RootGet": {"Type":"AWS::ApiGateway::Method", "Properties":{
                  "RestApiId":"%1$s", "ResourceId":"%2$s",
                  "HttpMethod":"GET", "AuthorizationType":"NONE", "Integration":{"Type":"MOCK"}}},
                "RootCustom": {"Type":"AWS::ApiGateway::Method", "Properties":{
                  "RestApiId":"%1$s", "ResourceId":"%2$s",
                  "HttpMethod":"X-CUSTOM", "AuthorizationType":"NONE", "Integration":{"Type":"MOCK"}}},
                "Deployment%3$s": {"Type":"AWS::ApiGateway::Deployment", "DependsOn":["Opt","RootGet"],
                  "Properties":{"RestApiId":"%1$s", "StageName":"local"}}
              },
              "Outputs":{"ResourceId":{"Value":{"Ref":"Res"}}}
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateKeepsTheResourceAndDeleteRemovesWhatTheStackAdded() {
        ValidatableResponse api = given().contentType("application/json")
                .body("{\"name\":\"cfn-resource-update\"}")
                .when().post("/restapis").then().statusCode(201);
        String apiId = api.extract().path("id");
        String rootId = api.extract().path("rootResourceId");
        try {
            stackAction("CreateStack", TEMPLATE.formatted(apiId, rootId, "1"));
            String resourceId = resourceIdOnceStatusIs("CREATE_COMPLETE");

            stackAction("UpdateStack", TEMPLATE.formatted(apiId, rootId, "2"));
            assertEquals(resourceId, resourceIdOnceStatusIs("UPDATE_COMPLETE"));
            given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200)
                    .body("item.findAll { it.path == '/infrastructures' }.size()", equalTo(1));

            deleteStack();
            given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200)
                    .body("item.path", contains("/"));
            given().when().get("/restapis/" + apiId + "/resources/" + rootId + "/methods/GET")
                    .then().statusCode(404);
            // A hyphen in the method makes its physical id ambiguous; the recorded location is not.
            given().when().get("/restapis/" + apiId + "/resources/" + rootId + "/methods/X-CUSTOM")
                    .then().statusCode(404);

            stackAction("CreateStack", TEMPLATE.formatted(apiId, rootId, "2"));
            resourceIdOnceStatusIs("CREATE_COMPLETE");
        } finally {
            deleteStack();
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private static void stackAction(String action, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", STACK)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static String resourceIdOnceStatusIs(String status) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(STACK);
        assertEquals(status, state.status(), state.reason());
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200)
                .extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get("ResourceId");
    }

    private static void deleteStack() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(STACK);
    }
}
