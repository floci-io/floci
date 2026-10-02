package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UpdateStack keeps an AWS::ApiGateway::RestApi and the AWS::ApiGateway::Resource under it, in the
 * redeploy shape the Serverless Framework produces: only the Deployment's logical id changes. The
 * API's description and endpoint type change in place, a resource the template drops goes from the
 * kept API, and an update that fails puts the API back as it was. An update that declares more
 * than one endpoint type is rejected, as CreateRestApi rejects it.
 */
@QuarkusTest
class ApiGatewayRestApiCfnUpdateIntegrationTest {

    private static final String API_NAME = "cfn-restapi-update";
    /** The API's description and endpoint type, and the suffix of the Deployment's logical id. */
    private static final String TEMPLATE = """
            {
              "Resources": {
                "Api": {"Type":"AWS::ApiGateway::RestApi", "Properties":{"Name":"cfn-restapi-update",
                  "Description":"%s", "EndpointConfiguration":{"Types":["%s"]}}},
                "Items": {"Type":"AWS::ApiGateway::Resource", "Properties":{
                  "RestApiId":{"Ref":"Api"},
                  "ParentId":{"Fn::GetAtt":["Api","RootResourceId"]},
                  "PathPart":"items"}},
                "Options": {"Type":"AWS::ApiGateway::Method", "Properties":{
                  "RestApiId":{"Ref":"Api"}, "ResourceId":{"Ref":"Items"},
                  "HttpMethod":"OPTIONS", "AuthorizationType":"NONE",
                  "Integration":{"Type":"MOCK"}}},
                "Deployment%s": {"Type":"AWS::ApiGateway::Deployment", "DependsOn":"Options",
                  "Properties":{"RestApiId":{"Ref":"Api"}, "StageName":"local"}}
              },
              "Outputs":{"ApiId":{"Value":{"Ref":"Api"}},
                         "ResourceId":{"Value":{"Ref":"Items"}}}
            }
            """;
    private static final String API_ONLY_TEMPLATE = """
            {
              "Resources": {
                "Api": {"Type":"AWS::ApiGateway::RestApi", "Properties":{"Name":"cfn-restapi-update",
                  "Description":"second", "EndpointConfiguration":{"Types":["EDGE"]}}}
              },
              "Outputs":{"ApiId":{"Value":{"Ref":"Api"}}}
            }
            """;
    /**
     * The API's description and endpoint type, then any extra resources. No Method or Deployment:
     * their in-place updates have no rollback yet, so a failed update could not roll back cleanly.
     */
    private static final String ROLLBACK_TEMPLATE = """
            {
              "Resources": {
                "Api": {"Type":"AWS::ApiGateway::RestApi", "Properties":{"Name":"cfn-restapi-rollback",
                  "Description":"%s", "EndpointConfiguration":{"Types":["%s"]}}},
                "Items": {"Type":"AWS::ApiGateway::Resource", "Properties":{
                  "RestApiId":{"Ref":"Api"},
                  "ParentId":{"Fn::GetAtt":["Api","RootResourceId"]},
                  "PathPart":"items"}}%s
              },
              "Outputs":{"ApiId":{"Value":{"Ref":"Api"}}}
            }
            """;
    /** The API's description, then its endpoint types as a JSON list. */
    private static final String TYPES_TEMPLATE = """
            {
              "Resources": {
                "Api": {"Type":"AWS::ApiGateway::RestApi", "Properties":{"Name":"cfn-restapi-types",
                  "Description":"%s", "EndpointConfiguration":{"Types":%s}}}
              },
              "Outputs":{"ApiId":{"Value":{"Ref":"Api"}}}
            }
            """;
    /** Fails after the API has been patched: its parent does not exist. */
    private static final String FAILING_RESOURCE = """
            ,
                "Broken": {"Type":"AWS::ApiGateway::Resource", "DependsOn":"Items", "Properties":{
                  "RestApiId":{"Ref":"Api"}, "ParentId":"missing0", "PathPart":"broken"}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void updateKeepsTheRestApiAndRemovesTheResourcesItDrops() {
        String stack = "apigw-cfn-restapi-update-it";
        try {
            stackAction(stack, "CreateStack", TEMPLATE.formatted("first", "REGIONAL", "1"));
            Map<String, String> created = outputsOnceStatusIs(stack, "CREATE_COMPLETE");

            stackAction(stack, "UpdateStack", TEMPLATE.formatted("second", "EDGE", "2"));
            Map<String, String> updated = outputsOnceStatusIs(stack, "UPDATE_COMPLETE");

            String apiId = created.get("ApiId");
            assertEquals(apiId, updated.get("ApiId"));
            assertEquals(created.get("ResourceId"), updated.get("ResourceId"));
            given().when().get("/restapis").then().statusCode(200)
                    .body("item.findAll { it.name == '" + API_NAME + "' }.size()", equalTo(1));
            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("description", equalTo("second"))
                    .body("endpointConfiguration.types", contains("EDGE"));
            assertPaths(apiId, "/", "/items");

            stackAction(stack, "UpdateStack", API_ONLY_TEMPLATE);
            assertEquals(apiId, outputsOnceStatusIs(stack, "UPDATE_COMPLETE").get("ApiId"));
            assertPaths(apiId, "/");
        } finally {
            deleteStack(stack);
        }
    }

    @Test
    void failedUpdatePutsTheRestApiBack() {
        String stack = "apigw-cfn-restapi-rollback-it";
        try {
            stackAction(stack, "CreateStack", ROLLBACK_TEMPLATE.formatted("first", "REGIONAL", ""));
            String apiId = outputsOnceStatusIs(stack, "CREATE_COMPLETE").get("ApiId");

            stackAction(stack, "UpdateStack", ROLLBACK_TEMPLATE.formatted("second", "EDGE", FAILING_RESOURCE));
            assertEquals(apiId, outputsOnceStatusIs(stack, "UPDATE_ROLLBACK_COMPLETE").get("ApiId"));

            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("description", equalTo("first"))
                    .body("endpointConfiguration.types", contains("REGIONAL"));
            assertPaths(apiId, "/", "/items");
        } finally {
            deleteStack(stack);
        }
    }

    @Test
    void updateWithMoreThanOneEndpointTypeIsRejected() {
        String stack = "apigw-cfn-restapi-types-it";
        try {
            stackAction(stack, "CreateStack", TYPES_TEMPLATE.formatted("first", "[\"REGIONAL\"]"));
            String apiId = outputsOnceStatusIs(stack, "CREATE_COMPLETE").get("ApiId");

            // CreateRestApi rejects more than one type, so an update does too.
            stackAction(stack, "UpdateStack", TYPES_TEMPLATE.formatted("second", "[\"REGIONAL\", \"EDGE\"]"));
            assertEquals(apiId, outputsOnceStatusIs(stack, "UPDATE_ROLLBACK_COMPLETE").get("ApiId"));

            given().when().get("/restapis/" + apiId).then().statusCode(200)
                    .body("description", equalTo("first"))
                    .body("endpointConfiguration.types", contains("REGIONAL"));
        } finally {
            deleteStack(stack);
        }
    }

    private static void assertPaths(String apiId, String... paths) {
        given().when().get("/restapis/" + apiId + "/resources").then().statusCode(200)
                .body("item.path", containsInAnyOrder(paths));
    }

    private static void stackAction(String stack, String action, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("StackName", stack)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputsOnceStatusIs(String stack, String status) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(status, state.status(), state.reason());
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200)
                .extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
